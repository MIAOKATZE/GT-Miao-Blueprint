// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramStockActionTrace;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Command;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.EbfHatch;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Confirmation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Key;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Phase;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Reply;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatchCokeOven;
import gregtech.common.tileentities.machines.multi.MTECokeOven;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

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
            return other != null && handler != null && world != null && player != null
                && handler == other.handler && world == other.world && player == other.player && dimension == other.dimension;
        }
    }

    @Desugar
    record Owner(Object view, BooleanSupplier exactBeforeStart, BooleanSupplier visible,
        Consumer<String> notice, Runnable refresh) {}

    @Desugar
    record Site(TileEntity controller, BaseMetaTileEntity hatch, int controllerFacing, int hx, int hy, int hz,
        int hatchFacing, List<EbfHatch> ebfHatches) {

        boolean ebf() {
            return ebfHatches != null;
        }

        boolean current(World world) {
            if (ebf()) {
                return controller instanceof BaseMetaTileEntity base
                    && base.getMetaTileEntity() instanceof MTEElectricBlastFurnace ebf
                    && ebf.getExtendedFacing().ordinal() == controllerFacing
                    && ebfHatches.equals(HologramStockNativeWire.observedEbfHatches(world, controller));
            }
            if (world == null || controller.isInvalid() || hatch.isInvalid() || controller.getWorldObj() != world
                || hatch.getWorldObj() != world || !(controller instanceof BaseMetaTileEntity tile)
                || !(tile.getMetaTileEntity() instanceof MTECokeOven oven)
                || oven.getExtendedFacing().ordinal() != controllerFacing) return false;
            int x = controller.xCoord + hx, y = controller.yCoord + hy, z = controller.zCoord + hz;
            return world.blockExists(x, y, z) && world.getBlock(x, y, z) == GregTechAPI.sBlockMachines
                && world.getTileEntity(x, y, z) == hatch && realHatch(hatch)
                && hatch.getFrontFacing().ordinal() == hatchFacing;
        }

        String submitLabel() {
            return ebf() ? "所选来源补建 · 先备 12 个耐热外壳和 16 个所选线圈，只按缺位扣除"
                : "所选来源补建 · 先备 24 块焦炉砖，只按缺位扣除";
        }

        String description() {
            if (ebf()) return "保留已安装的 5 个电力高炉仓室";
            return "保留已安装 ULV 焦炉仓室 (" + (controller.xCoord + hx) + ", " + (controller.yCoord + hy)
                + ", " + (controller.zCoord + hz) + ") · " + net.minecraftforge.common.util.ForgeDirection.getOrientation(hatchFacing).name();
        }
    }

    @Desugar
    record DeclarationSite(BaseMetaTileEntity controller,
        gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?> machine, int controllerId,
        int facing) {

        static DeclarationSite read(World world, TileEntity target) {
            if (world == null || !(target instanceof BaseMetaTileEntity base) || base.isInvalid()
                || base.getWorldObj() != world || base.getMetaTileID() <= 0
                || !(base.getMetaTileEntity() instanceof
                    gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?> machine)) return null;
            return new DeclarationSite(base, machine, base.getMetaTileID(), machine.getExtendedFacing().ordinal());
        }

        boolean current(World world) {
            int x = controller.xCoord, y = controller.yCoord, z = controller.zCoord;
            return world != null && world.blockExists(x, y, z) && world.getTileEntity(x, y, z) == controller
                && equals(read(world, controller));
        }

        String description() { return "保留现场已匹配的方块和仓室"; }
    }

    private static final class Attempt {

        final Confirmation original;
        final Context context;
        final Owner owner;
        Key key;
        long deadline;
        int placed = -1;

        boolean placing() {
            return original.declaration() == null || original.declaration().operation() == 0;
        }

        String operationLabel() {
            if (original.declaration() == null) return "原生补建";
            return switch (original.declaration().operation()) {
                case 1 -> "原生换装";
                case 2 -> "原生拆除";
                default -> "原生施工";
            };
        }
        boolean beginSent, afterProbe, stopSent, uncertain;

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

        Flow(Wire wire) {
            this.wire = wire;
        }

        boolean submit(Confirmation original, Context context, Owner owner, long now) {
            retireChangedContext(context);
            if (active != null) {
                owner.notice.accept("另一个原生补建请求尚未结束，请先核对现场。");
                return false;
            }
            if (context == null || !context.same(context) || !owner.exactBeforeStart.getAsBoolean()) {
                owner.notice.accept("当前确认已变化，未发送施工指令。");
                return false;
            }
            var attempt = new Attempt(original, context, owner, now + FIRST_PROOF_NANOS);
            active = attempt;
            if (context.same(known) && generation > 0) return begin(attempt, generation, false, now);
            try {
                if (wire.probe(original)) {
                    HologramStockActionTrace.event("client.sent", original.key(), "PROBE");
                    owner.notice.accept("已发送施工查询，等待服务器确认。");
                    return true;
                }
            } catch (HologramStockNativeWire.InvalidConfirmation invalid) {
                // PROBE itself never starts a task. Report local contract errors separately from a missing channel.
                active = null;
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Stock construction probe rejected its input", invalid);
                owner.notice.accept("未提交施工：控制器参数未通过检查，请重新读取现场。");
                return false;
            } catch (RuntimeException | LinkageError failed) {
                active = null;
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Stock construction probe transport failed", failed);
                owner.notice.accept("未提交施工：能力查询发送出错，详情见客户端日志。");
                return false;
            }
            active = null;
            owner.notice.accept("未提交施工：服务器连接通道未就绪。");
            return false;
        }

        private boolean begin(Attempt attempt, long usableGeneration, boolean afterProbe, long now) {
            if (active != attempt || attempt.beginSent || !attempt.owner.exactBeforeStart.getAsBoolean()) {
                if (active == attempt) active = null;
                if (attempt.owner.visible.getAsBoolean()) attempt.owner.notice.accept("当前确认已变化，未开始施工。");
                return false;
            }
            Confirmation issued = attempt.original.forGeneration(usableGeneration);
            attempt.key = issued.key();
            attempt.afterProbe = afterProbe;
            attempt.beginSent = true; // A thrown transport may already have handed the frame off; do not retry.
            attempt.deadline = now + RESULT_NANOS;
            try {
                if (!wire.begin(issued, afterProbe)) {
                    active = null; // Production writer returns false only before any wire send.
                    attempt.owner.notice.accept("未提交施工：服务器连接不可用。");
                    return false;
                }
            } catch (RuntimeException | LinkageError uncertain) {
                reportUnknown(attempt);
                return true;
            }
            HologramStockActionTrace.event("client.sent", issued.key(), afterProbe ? "BEGIN_AFTER_PROBE" : "BEGIN");
            attempt.owner.notice.accept("已提交" + attempt.operationLabel() + "请求，等待服务器确认。");
            return true;
        }

        void receive(Reply reply, Object from, Context current, long now) {
            retireChangedContext(current);
            Attempt attempt = active;
            if (attempt == null || current.handler != from || !attempt.context.same(current)
                || !attempt.original.key().request().equals(reply.key().request())
                || attempt.original.key().sequence() != reply.key().sequence()) return;
            if (reply.phase() != Phase.ACTIVE) HologramStockActionTrace.event("client.reply", reply.key(), reply.phase().name());
            if (!attempt.beginSent) {
                if (now >= attempt.deadline || !attempt.owner.visible.getAsBoolean()) {
                    active = null;
                    if (attempt.owner.visible.getAsBoolean())
                        attempt.owner.notice.accept("服务器未及时确认支持此短请求；未发送施工指令。");
                    return;
                }
                if (reply.phase() == Phase.REFUSED) {
                    active = null;
                    attempt.owner.notice.accept(reply.reason());
                    return;
                }
                if (reply.phase() != Phase.CAPABLE || reply.key().generation() <= 0) return;
                if (!attempt.owner.exactBeforeStart.getAsBoolean()) {
                    active = null;
                    attempt.owner.notice.accept("当前确认已变化，未开始施工。");
                    return;
                }
                // CAPABLE is permission to submit this very confirmation, never cached warm readiness.
                begin(attempt, reply.key().generation(), true, now);
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
                generation = reply.key().generation();
            }
            int previous = attempt.placed;
            attempt.placed = reply.placed();
            if (reply.phase() == Phase.ACCEPTED || reply.phase() == Phase.ACTIVE) {
                if (previous < attempt.placed && !attempt.stopSent) attempt.deadline = now + RESULT_NANOS;
                if (attempt.owner.visible.getAsBoolean() && !attempt.stopSent && !attempt.uncertain)
                    attempt.owner.notice.accept(reply.phase() == Phase.ACCEPTED ? "服务器已受理" + attempt.operationLabel()
                        + (attempt.placing() ? "；尚未确认方块提交。" : "；尚未确认现场变更。")
                        : attempt.operationLabel() + "：已确认 " + attempt.placed + " 格。");
                return;
            }
            active = null;
            if (!attempt.owner.visible.getAsBoolean()) return;
            String status = switch (reply.phase()) {
                case COMPLETE -> attempt.operationLabel() + (attempt.placing() ? "完成，实际放置 " : "完成，实际变更 ")
                    + attempt.placed + " 格。";
                case STOPPED -> attempt.operationLabel() + "已停止，已确认 " + attempt.placed + " 格。" + reply.reason();
                case RECOVERY_PENDING -> attempt.operationLabel() + "结果待核定，已确认 " + attempt.placed + " 格。" + reply.reason();
                default -> reply.reason();
            };
            attempt.owner.notice.accept(status);
            if (attempt.placed > 0 || reply.phase() == Phase.COMPLETE) attempt.owner.refresh.run();
        }

        void tick(Object view, Context context, long now) {
            retireChangedContext(context);
            Attempt attempt = active;
            if (attempt == null || attempt.owner.view != view) return;
            if (!attempt.beginSent && (!attempt.owner.exactBeforeStart.getAsBoolean() || now >= attempt.deadline)) {
                active = null;
                if (attempt.owner.visible.getAsBoolean())
                    attempt.owner.notice.accept(now >= attempt.deadline ? "服务器未确认支持此短请求；未发送施工指令。"
                        : "当前确认已变化，未发送施工指令。");
            } else if (attempt.beginSent && now >= attempt.deadline && !attempt.uncertain) {
                reportUnknown(attempt);
            }
        }

        void close(Object view, Context context) {
            retireChangedContext(context);
            Attempt attempt = active;
            if (attempt == null || attempt.owner.view != view) return;
            if (attempt.beginSent) stopOnce(attempt);
            active = null;
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
            if (attempt.owner.visible.getAsBoolean())
                attempt.owner.notice.accept("尚无法确认" + attempt.operationLabel() + "结果。" + (stopped ? "已请求停止，未确认停工。"
                    : "没有重试；请先核对现场。"));
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

        private void retireChangedContext(Context current) {
            if (known != null && !known.same(current)) {
                known = null;
                generation = 0;
            }
            if (active != null && !active.context.same(current)) active = null;
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
            client.func_152344_a(() -> OWNER.receive(reply, from, context(client), System.nanoTime()));
        });
    }

    static Context context(Minecraft client) {
        return client == null || client.thePlayer == null || client.theWorld == null || client.getNetHandler() == null
            ? null : new Context(client.getNetHandler(), client.theWorld, client.thePlayer, client.thePlayer.dimension);
    }

    static UUID canonicalTool(String id) {
        if (id == null || id.length() != 36) return null;
        try {
            UUID value = UUID.fromString(id);
            return value.toString().equals(id) && (value.getMostSignificantBits() != 0
                || value.getLeastSignificantBits() != 0) ? value : null;
        } catch (IllegalArgumentException bad) {
            return null;
        }
    }

    /** Only exact supported stock shapes with all declared hatches installed; never guess an ambiguous site. */
    static Site choose(World world, TileEntity target) {
        if (world == null || target == null || target.getClass() != BaseMetaTileEntity.class) return null;
        BaseMetaTileEntity base = (BaseMetaTileEntity) target;
        if (base.getMetaTileID() == MetaTileEntityIDs.EBF_CONTROLLER.ID
            && base.getMetaTileEntity() instanceof MTEElectricBlastFurnace ebf) {
            List<EbfHatch> hatches = HologramStockNativeWire.observedEbfHatches(world, target);
            return hatches.isEmpty() ? null
                : new Site(target, null, ebf.getExtendedFacing().ordinal(), 0, 0, 0, 0, hatches);
        }
        if (base.getMetaTileID() != MetaTileEntityIDs.COKE_OVEN_CONTROLLER.ID
            || !(base.getMetaTileEntity() instanceof MTECokeOven oven) || oven.getClass() != MTECokeOven.class
            || oven.mMachine) return null;
        var facing = oven.getExtendedFacing();
        int[] abc = new int[3], delta = new int[3];
        Site chosen = null;
        for (int c = 0; c <= 2; c++)
            for (int b = -1; b <= 1; b++)
                for (int a = -1; a <= 1; a++) {
                    if (a == 0 && b == 0 && c < 2) continue;
                    abc[0] = a;
                    abc[1] = b;
                    abc[2] = c;
                    facing.getWorldOffset(abc, delta);
                    int x = base.xCoord + delta[0], y = base.yCoord + delta[1], z = base.zCoord + delta[2];
                    if (!world.blockExists(x, y, z)) return null;
                    if (world.getBlock(x, y, z) != GregTechAPI.sBlockMachines) continue;
                    if (!(world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity hatch) || !realHatch(hatch)) continue;
                    if (chosen != null) return null;
                    chosen = new Site(base, hatch, facing.ordinal(), delta[0], delta[1], delta[2],
                        hatch.getFrontFacing().ordinal(), null);
                }
        return chosen;
    }

    private static boolean realHatch(BaseMetaTileEntity base) {
        return base.getClass() == BaseMetaTileEntity.class && !base.isInvalid()
            && base.getMetaTileID() == MetaTileEntityIDs.COKE_OVEN_HATCH.ID
            && base.getMetaTileEntity() instanceof MTEHatchCokeOven hatch
            && hatch.getClass() == MTEHatchCokeOven.class && hatch.mTier == 0
            && base.getFrontFacing() != null && base.getFrontFacing().ordinal() < 6;
    }
}
