// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.CommitReceipt;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Outcome;
import com.miaokatze.gtmb.client.hologram.HologramOwnedPlacementProgress.Ticket;
import com.miaokatze.gtmb.hologram.HologramNativeLocalConstruction;
import com.miaokatze.gtmb.hologram.HologramNativeLocalConstruction.Attempt;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramPlacementTiming;

/** Owns the in-process request/result on the client's confirmed placement route. No packet handler is installed. */
final class HologramClientNativePlacement {

    private static Active active;

    private HologramClientNativePlacement() {}

    static boolean start(HologramLibraryClient owner, HologramLocalPlacement.Submission source, Minecraft client) {
        if (active != null || !source.current() || !client.isIntegratedServerRunning()) return false;
        var server = client.getIntegratedServer();
        var localPlayer = client.thePlayer;
        if (server == null || localPlayer == null
            || !localPlayer.getCommandSenderName()
                .equals(server.getServerOwner()))
            return false;
        var actor = localPlayer.getUniqueID();
        var connection = client.getNetHandler();
        long intent = HologramNetwork.nextConstructionIntentSequence();
        if (intent <= 0) return false;
        Attempt attempt = new Attempt(intent);
        Ticket ticket = HologramBackgroundTasks
            .beginOwnedPlacement(attempt.id, HologramNativeUiText.reason("原生蓝图施工"), attempt::stop);
        if (ticket == null) return false;
        HologramPlacementTiming.clientRequest("blueprint", attempt.id);
        var job = new Active(attempt, ticket, client.theWorld, connection, source);
        job.pin = source.source()
            .pinNative();
        active = job;
        var options = source.intent();
        var point = new int[] { options.getInteger("x"), options.getInteger("y"), options.getInteger("z") };
        int dimension = localPlayer.dimension;
        String tool = HologramLibraryClient.projectorId(localPlayer.getHeldItem());
        owner.projectedWork(() -> {
            var ready = HologramNativeLocalConstruction.prepare(
                source.display().nativeViewport.draft,
                source.source().nativeReceipt,
                () -> !job.retired && !attempt.cancelled());
            owner.projectedMain(() -> {
                if (active != job || job.retired) return;
                if (!source.current() || client.getIntegratedServer() != server
                    || client.getNetHandler() != connection) {
                    job.fail("原生施工上下文已变化");
                    return;
                }
                HologramPlacementTiming.clientPhase("blueprint", attempt.id, "integrated_submit");
                HologramNativeLocalConstruction.submit(attempt, ready, () -> {
                    var config = server.getConfigurationManager();
                    if (!server.isServerRunning() || config == null) return null;
                    for (Object current : config.playerEntityList)
                        if (current instanceof EntityPlayerMP player && actor.equals(player.getUniqueID())
                            && player.getCommandSenderName()
                                .equals(server.getServerOwner()))
                            return player;
                    return null;
                },
                    tool,
                    dimension,
                    point,
                    options.getIntArray("previewPose"),
                    options.getInteger("reviewedSources"),
                    options.getBoolean("voucherRequested"),
                    options.getIntArray("savedBindingSlots"));
            });
        }, failure -> job.fail(HologramNativeUiText.reason(failure.getMessage())));
        return true;
    }

    static void showCurrent() {
        if (active != null) HologramBackgroundTasks.showOwnedPlacement(active.ticket);
        else HologramClientRemoteNativePlacement.show();
    }

    static void tick(Minecraft client) {
        Active job = active;
        if (job == null) return;
        if (client.theWorld != job.world || client.getNetHandler() != job.connection
            || !client.isIntegratedServerRunning()) {
            job.unpin();
            job.retired = true;
            job.attempt.stop();
            active = null;
            return;
        }
        var result = job.attempt.result();
        if (result.sequence <= job.seen) return;
        job.seen = result.sequence;
        if (!job.started && (result.phase == HologramNativeLocalConstruction.Phase.EXECUTING
            || result.phase == HologramNativeLocalConstruction.Phase.COMPLETE)) {
            job.started = true;
            if (job.source.current()) client.thePlayer.closeScreen();
        }
        switch (result.phase) {
            case PREPARING -> HologramBackgroundTasks.observeOwnedPlacement(
                job.ticket,
                HologramOwnedPlacementProgress.Phase.ADMISSION,
                result.sequence,
                0,
                result.total);
            case EXECUTING -> HologramBackgroundTasks.observeOwnedPlacement(
                job.ticket,
                HologramOwnedPlacementProgress.Phase.EXECUTING,
                result.sequence,
                result.placed + result.satisfied,
                result.total);
            case COMPLETE -> {
                HologramPlacementTiming.clientPhase("blueprint", job.attempt.id, "complete_seen");
                Outcome outcome = result.placed == 0 ? Outcome.NO_WORK : Outcome.SUCCESS;
                boolean accepted = HologramBackgroundTasks.acceptOwnedPlacementCommit(
                    job.ticket,
                    new CommitReceipt(
                        job.attempt.id,
                        result.sequence,
                        outcome,
                        result.placed,
                        result.satisfied,
                        result.total),
                    HologramNativeUiText.reason(result.placed == 0 ? "没有需要放置的方块" : result.detail));
                if (accepted) HologramLocalPlacement.INSTANCE.finish(job.source, true);
                job.unpin();
                job.retired = true;
                active = null;
            }
            case CANCELLED, FAILED -> job.finish(
                result.phase == HologramNativeLocalConstruction.Phase.CANCELLED ? Outcome.CANCELLED : Outcome.FAILED,
                result.sequence,
                result.placed,
                result.satisfied,
                result.total,
                result.detail);
            default -> {}
        }
    }

    private static final class Active {

        final Attempt attempt;
        final Ticket ticket;
        final HologramLocalPlacement.Submission source;
        final Object world, connection;
        volatile boolean retired;
        com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition.Lease pin;

        void unpin() {
            if (pin != null) {
                pin.close();
                pin = null;
            }
        }

        long seen;
        boolean started;

        Active(Attempt attempt, Ticket ticket, Object world, Object connection,
            HologramLocalPlacement.Submission source) {
            this.attempt = attempt;
            this.ticket = ticket;
            this.world = world;
            this.connection = connection;
            this.source = source;
        }

        void fail(String detail) {
            boolean cancelled = attempt.cancelled();
            finish(cancelled ? Outcome.CANCELLED : Outcome.FAILED, seen + 1, 0, 0, 0, cancelled ? "施工已取消" : detail);
        }

        void finish(Outcome outcome, long serial, int placed, int satisfied, int total, String detail) {
            if (active != this) return;
            boolean accepted = HologramBackgroundTasks.finishOwnedPlacement(
                ticket,
                serial,
                outcome,
                placed,
                satisfied,
                total,
                HologramNativeUiText.reason(detail));
            if (accepted) HologramLocalPlacement.INSTANCE.finish(source, outcome == Outcome.CANCELLED);
            unpin();
            active = null;
            retired = true;
        }
    }
}
