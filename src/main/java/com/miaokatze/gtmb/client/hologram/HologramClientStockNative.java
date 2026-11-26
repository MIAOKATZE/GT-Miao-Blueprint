// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiText.projectorOr;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramPlacementTiming;
import com.miaokatze.gtmb.hologram.HologramStockActionTrace;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Command;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;

import gregtech.api.metatileentity.BaseMetaTileEntity;

/** One existing local confirmation, one exact client owner. Preview/material browsing never touches this writer. */
public final class HologramClientStockNative {

    static final long FIRST_PROOF_NANOS = TimeUnit.SECONDS.toNanos(5);
    static final long RESULT_NANOS = TimeUnit.SECONDS.toNanos(35);

    interface Wire {

        boolean probe(Confirmation value);

        boolean begin(Confirmation value, boolean afterProbe);

        boolean stop(Key key);
    }

    @Desugar
    record Context(Object handler, Object world, Object player, int dimension) {

        boolean same(Context other) {
            return other != null && handler != null
                && world != null
                && player != null
                && handler == other.handler
                && world == other.world
                && player == other.player
                && dimension == other.dimension;
        }
    }

    @Desugar
    record Owner(Object view, BooleanSupplier exactBeforeStart, BooleanSupplier visible, Consumer<String> notice,
        Consumer<Reply> terminal, BooleanSupplier detachedBeforeStart) {

        Owner(Object view, BooleanSupplier exactBeforeStart, BooleanSupplier visible, Consumer<String> notice,
            Consumer<Reply> terminal) {
            this(view, exactBeforeStart, visible, notice, terminal, () -> false);
        }

        Owner(Object view, BooleanSupplier exactBeforeStart, BooleanSupplier visible, Consumer<String> notice,
            Runnable terminal) {
            this(view, exactBeforeStart, visible, notice, reply -> terminal.run());
        }
    }

    @Desugar
    record DeclarationSite(BaseMetaTileEntity controller,
        gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?> machine, int controllerId,
        int facing) {

        static DeclarationSite read(World world, TileEntity target) {
            if (world == null || !(target instanceof BaseMetaTileEntity base)
                || base.isInvalid()
                || base.getWorldObj() != world
                || base.getMetaTileID() <= 0
                || !(base
                    .getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>machine))
                return null;
            return new DeclarationSite(
                base,
                machine,
                base.getMetaTileID(),
                machine.getExtendedFacing()
                    .ordinal());
        }

        boolean current(World world) {
            int x = controller.xCoord, y = controller.yCoord, z = controller.zCoord;
            return world != null && world.blockExists(x, y, z)
                && world.getTileEntity(x, y, z) == controller
                && equals(read(world, controller));
        }

        String description() {
            return projectorOr("stock.retain_matching", "保留现场已匹配的方块和仓室");
        }
    }

    /** Translate known vanilla-stock protocol reasons client-side. Older or external reasons stay intact. */
    private static String serverReason(String reason) {
        return HologramNativeUiText.reason(reason);
    }

    private static final class Attempt {

        final Confirmation original;
        final Context context;
        final Owner owner;
        Key key;
        long deadline;
        int placed = -1;

        boolean placing() {
            return original.projection()
                .operation() == HologramStockNativeWire.Operation.BUILD
                || original.projection()
                    .operation() == HologramStockNativeWire.Operation.REPAIR;
        }

        String operationLabel() {
            return HologramNativeControllerState.label(
                original.projection()
                    .operation());
        }

        boolean beginSent, afterProbe, stopSent, uncertain, detached;

        boolean canStart() {
            return (detached ? owner.detachedBeforeStart : owner.exactBeforeStart).getAsBoolean();
        }

        Attempt(Confirmation original, Context context, Owner owner, long deadline) {
            this.original = original;
            this.context = context;
            this.owner = owner;
            this.deadline = deadline;
        }
    }

    static final class Flow {

        private final Wire wire;
        private Attempt active;
        private Context known;
        private long generation;
        private String terminalHud = "";
        private Context terminalContext;
        private long terminalAt;

        Flow(Wire wire) {
            this.wire = wire;
        }

        boolean submit(Confirmation original, Context context, Owner owner, long now) {
            retireChangedContext(context);
            if (active != null) {
                owner.notice.accept(projectorOr("stock.request_busy", "另一个原生补建请求尚未结束，请先核对现场。"));
                return false;
            }
            if (context == null || !context.same(context) || !owner.exactBeforeStart.getAsBoolean()) {
                owner.notice.accept(projectorOr("stock.request_stale", "当前确认已变化，未发送施工指令。"));
                return false;
            }
            var attempt = new Attempt(original, context, owner, now + FIRST_PROOF_NANOS);
            active = attempt;
            terminalHud = "";
            HologramPlacementTiming.clientRequest(
                "structure",
                original.key()
                    .request());
            if (context.same(known) && generation > 0) return begin(attempt, generation, false, now);
            try {
                HologramPlacementTiming.clientPhase(
                    "structure",
                    original.key()
                        .request(),
                    "probe");
                if (wire.probe(original)) {
                    HologramStockActionTrace.event("client.sent", original.key(), "PROBE");

                    return true;
                }
            } catch (HologramStockNativeWire.InvalidConfirmation invalid) {
                // PROBE itself never starts a task. Report local contract errors separately from a missing channel.
                active = null;
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                    .warn("Stock construction probe rejected its input", invalid);
                owner.notice.accept(projectorOr("stock.invalid_controller", "未提交施工：控制器参数未通过检查，请重新读取现场。"));
                return false;
            } catch (RuntimeException | LinkageError failed) {
                active = null;
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Stock construction probe transport failed", failed);
                owner.notice.accept(projectorOr("stock.probe_error", "未提交施工：能力查询发送出错，详情见客户端日志。"));
                return false;
            }
            active = null;
            owner.notice.accept(projectorOr("stock.channel_not_ready", "未提交施工：服务器连接通道未就绪。"));
            return false;
        }

        private boolean begin(Attempt attempt, long usableGeneration, boolean afterProbe, long now) {
            if (active != attempt || attempt.beginSent || !attempt.canStart()) {
                if (active == attempt) discard(attempt);
                if (attempt.owner.visible.getAsBoolean())
                    attempt.owner.notice.accept(projectorOr("stock.start_stale", "当前确认已变化，未开始施工。"));
                return false;
            }
            Confirmation issued = attempt.original.forGeneration(usableGeneration);
            attempt.key = issued.key();
            attempt.afterProbe = afterProbe;
            attempt.beginSent = true; // A thrown transport may already have handed the frame off; do not retry.
            attempt.deadline = now + RESULT_NANOS;
            try {
                HologramPlacementTiming.clientPhase(
                    "structure",
                    issued.key()
                        .request(),
                    "begin");
                if (!wire.begin(issued, afterProbe)) {
                    discard(attempt); // Production writer returns false only before any wire send.
                    if (attempt.owner.visible.getAsBoolean())
                        attempt.owner.notice.accept(projectorOr("stock.server_connection", "未提交施工：服务器连接不可用。"));
                    return false;
                }
            } catch (RuntimeException | LinkageError uncertain) {
                reportUnknown(attempt);
                return true;
            }
            HologramStockActionTrace.event("client.sent", issued.key(), afterProbe ? "BEGIN_AFTER_PROBE" : "BEGIN");
            if (attempt.owner.visible.getAsBoolean()) attempt.owner.notice
                .accept(projectorOr("stock.submitted", "已提交%1$s请求，等待服务器确认。", attempt.operationLabel()));
            return true;
        }

        void receive(Reply reply, Object from, Context current, long now) {
            retireChangedContext(current);
            Attempt attempt = active;
            if (attempt == null || current.handler != from
                || !attempt.context.same(current)
                || !attempt.original.key()
                    .request()
                    .equals(
                        reply.key()
                            .request())
                || attempt.original.key()
                    .sequence()
                    != reply.key()
                        .sequence())
                return;
            if (reply.phase() != Phase.ACTIVE) HologramStockActionTrace.event(
                "client.reply",
                reply.key(),
                reply.phase()
                    .name());
            if (!attempt.beginSent) {
                if (now >= attempt.deadline || !attempt.detached && !attempt.owner.visible.getAsBoolean()) {
                    discard(attempt);
                    if (attempt.owner.visible.getAsBoolean())
                        attempt.owner.notice.accept(projectorOr("stock.probe_timeout", "服务器未及时确认支持此短请求；未发送施工指令。"));
                    return;
                }
                if (reply.phase() == Phase.REFUSED) {
                    discard(attempt);
                    rememberTerminal(attempt.context, serverReason(reply.reason()), now);
                    if (attempt.owner.visible.getAsBoolean()) attempt.owner.notice.accept(serverReason(reply.reason()));
                    return;
                }
                if (reply.phase() != Phase.CAPABLE || reply.key()
                    .generation() <= 0) return;
                if (!attempt.canStart()) {
                    discard(attempt);
                    if (attempt.owner.visible.getAsBoolean())
                        attempt.owner.notice.accept(projectorOr("stock.start_stale", "当前确认已变化，未开始施工。"));
                    return;
                }
                // CAPABLE is permission to submit this very confirmation, never cached warm readiness.
                begin(
                    attempt,
                    reply.key()
                        .generation(),
                    true,
                    now);
                return;
            }
            if (!attempt.key.equals(reply.key()) || reply.phase() == Phase.CAPABLE
                || reply.placed() >= 0 && reply.placed() < attempt.placed) return;
            if (reply.phase() == Phase.UNKNOWN) {
                reportUnknown(attempt);
                return;
            }
            if (reply.phase() == Phase.NEEDS_PROOF) forgetWarm(attempt);
            else if (attempt.afterProbe && (reply.phase() == Phase.ACCEPTED || reply.phase() == Phase.ACTIVE
                || reply.phase() == Phase.COMPLETE)) {
                    known = current;
                    generation = reply.key()
                        .generation();
                }
            int previous = attempt.placed;
            attempt.placed = reply.placed();
            if (reply.phase() == Phase.ACCEPTED || reply.phase() == Phase.ACTIVE) {
                if (previous < attempt.placed && !attempt.stopSent) attempt.deadline = now + RESULT_NANOS;
                return;
            }
            discard(attempt);
            if (reply.phase() == Phase.COMPLETE) HologramPlacementTiming.clientPhase(
                "structure",
                reply.key()
                    .request(),
                "complete_seen");
            String reason = serverReason(reply.reason());
            String status = switch (reply.phase()) {
                case COMPLETE -> (attempt.placing()
                    ? projectorOr(
                        "stock.done_placement",
                        "%1$s完成，实际放置 %2$s 格。",
                        attempt.operationLabel(),
                        attempt.placed)
                    : projectorOr(
                        "stock.done_changes",
                        "%1$s完成，实际变更 %2$s 格。",
                        attempt.operationLabel(),
                        attempt.placed))
                    + (reason.isEmpty() ? "" : " " + reason);
                case STOPPED -> projectorOr(
                    "stock.stopped",
                    "%1$s已停止，已确认 %2$s 格。",
                    attempt.operationLabel(),
                    attempt.placed) + (reason.isEmpty() ? "" : " " + reason);
                case RECOVERY_PENDING -> projectorOr(
                    "stock.recovery_pending",
                    "%1$s结果待核定，已确认 %2$s 格。",
                    attempt.operationLabel(),
                    attempt.placed) + (reason.isEmpty() ? "" : " " + reason);
                default -> reason;
            };
            rememberTerminal(attempt.context, status, now);
            if (attempt.owner.visible.getAsBoolean()) attempt.owner.notice.accept(status);
            if (attempt.placed > 0 || reply.phase() == Phase.COMPLETE
                || reply.phase() == Phase.STOPPED && attempt.placing() && !reason.isEmpty())
                attempt.owner.terminal.accept(reply);
        }

        void tick(Object view, Context context, long now) {
            retireChangedContext(context);
            Attempt attempt = active;
            if (attempt == null || view != null && attempt.owner.view != view) return;
            if (!attempt.beginSent && (!attempt.canStart() || now >= attempt.deadline)) {
                discard(attempt);
                if (attempt.owner.visible.getAsBoolean()) attempt.owner.notice.accept(
                    now >= attempt.deadline ? projectorOr("stock.probe_no_confirmation", "服务器未确认支持此短请求；未发送施工指令。")
                        : projectorOr("stock.request_stale", "当前确认已变化，未发送施工指令。"));
            } else if (attempt.beginSent && now >= attempt.deadline && !attempt.uncertain) {
                reportUnknown(attempt);
            }
        }

        void close(Object view, Context context) {
            retireChangedContext(context);
            Attempt attempt = active;
            if (attempt == null || attempt.owner.view != view) return;
            if (attempt.beginSent || attempt.owner.detachedBeforeStart.getAsBoolean()) attempt.detached = true;
            else discard(attempt);
        }

        UUID requestFor(Object view, Context context) {
            retireChangedContext(context);
            return active != null && active.owner.view == view ? active.original.key()
                .request() : null;
        }

        boolean pendingHere(Context context) {
            retireChangedContext(context);
            return active != null;
        }

        boolean cancelHere(Context context, long now) {
            if (!pendingHere(context)) return false;
            if (active.beginSent) return stopOnce(active);
            rememberTerminal(context, projectorOr("stock.reason_cancelled", "Cancelled before placement."), now);
            discard(active);
            return true;
        }

        String worldStatus(Context context, long now) {
            if (pendingHere(context)) {
                if (active.stopSent) return projectorOr("stock.hud_stopping", "Stopping placement");
                if (!active.beginSent) return projectorOr("stock.hud_preparing", "Confirming placement");
                return projectorOr(
                    "stock.confirmed_cells",
                    "%1$s: %2$s cells confirmed.",
                    active.operationLabel(),
                    Math.max(0, active.placed));
            }
            return terminalContext != null && terminalContext.same(context)
                && now - terminalAt < TimeUnit.SECONDS.toNanos(3) ? terminalHud : "";
        }

        private void rememberTerminal(Context context, String status, long now) {
            terminalContext = context;
            terminalHud = status;
            terminalAt = now;
        }

        boolean pending(Object view) {
            return active != null && active.owner.view == view;
        }

        boolean serverConfirmed(Context context) {
            return context != null && context.same(known) && generation > 0;
        }

        private void reportUnknown(Attempt attempt) {
            if (attempt.uncertain) return;
            attempt.uncertain = true;
            forgetWarm(attempt);
            boolean stopped = stopOnce(attempt);
            if (attempt.owner.visible.getAsBoolean()) attempt.owner.notice.accept(
                (stopped ? projectorOr("stock.unknown_stop", "尚无法确认%1$s结果。已请求停止，未确认停工。", attempt.operationLabel())
                    : projectorOr("stock.unknown_no_retry", "尚无法确认%1$s结果。没有重试；请先核对现场。", attempt.operationLabel())));
        }

        private void forgetWarm(Attempt attempt) {
            if (attempt.key != null && attempt.context.same(known) && generation == attempt.key.generation()) {
                known = null;
                generation = 0;
            }
        }

        private boolean stopOnce(Attempt attempt) {
            if (attempt.stopSent) return false;
            attempt.stopSent = true;
            try {
                return wire.stop(attempt.key);
            } catch (RuntimeException | LinkageError unavailable) {
                return false;
            }
        }

        private void discard(Attempt attempt) {
            HologramLocalControllerGhost.finish(
                attempt.original.key()
                    .request());
            if (active == attempt) active = null;
        }

        private void retireChangedContext(Context current) {
            if (known != null && !known.same(current)) {
                known = null;
                generation = 0;
            }
            if (active != null && !active.context.same(current)) discard(active);
        }
    }

    static final Flow OWNER = new Flow(new Wire() {

        @Override
        public boolean probe(Confirmation value) {
            return HologramNetwork.sendStockNativeProbe(value);
        }

        @Override
        public boolean begin(Confirmation value, boolean afterProbe) {
            return afterProbe ? HologramNetwork.sendStockNativeAfterProbe(value)
                : HologramNetwork.sendStockNativeConfirmation(value);
        }

        @Override
        public boolean stop(Key key) {
            return HologramNetwork.sendStockNativeControl(key, Command.STOP);
        }
    });

    private HologramClientStockNative() {}

    public static void install() {
        HologramNetwork.setStockNativeReplies((reply, from) -> {
            Minecraft client = Minecraft.getMinecraft();
            client.func_152344_a(() -> {
                if (client.currentScreen instanceof HologramScreen screen
                    && screen.receiveNativeObservation(reply, from)) return;
                OWNER.receive(reply, from, context(client), System.nanoTime());
            });
        });
    }

    static Context context(Minecraft client) {
        return client == null || client.thePlayer == null || client.theWorld == null || client.getNetHandler() == null
            ? null
            : new Context(client.getNetHandler(), client.theWorld, client.thePlayer, client.thePlayer.dimension);
    }

    static UUID canonicalTool(String id) {
        if (id == null || id.length() != 36) return null;
        try {
            UUID value = UUID.fromString(id);
            return value.toString()
                .equals(id) && (value.getMostSignificantBits() != 0 || value.getLeastSignificantBits() != 0) ? value
                    : null;
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

}
