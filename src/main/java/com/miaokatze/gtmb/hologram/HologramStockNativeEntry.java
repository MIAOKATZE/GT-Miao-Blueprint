// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

/** Authenticated native-projector admission. Saved blueprint workspaces are borrowed and restored unchanged. */
final class HologramStockNativeEntry {

    @Desugar
    record Intent(HologramConstructionTaskGate.Request request, long generation, int x, int y, int z, int side,
        int slot, int facing, int main, boolean mainExplicit, boolean noHatches, int sources,
        HologramStockNativeWire.Projection projection) {

        boolean valid() {
            return request != null && request.kind == HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE
                && generation > 0
                && projection != null
                && projection.valid()
                && side >= 0
                && side < 6
                && slot >= 0
                && slot < 9
                && facing >= 0
                && facing < ExtendedFacing.STATES_COUNT
                && main >= 1
                && main <= 64
                && sources >= 0
                && (sources & ~63) == 0
                && ((sources >>> 3) & 3) <= 2;
        }
    }

    @Desugar
    record Ingress(Submission submission, String refusal) {

        private static Ingress refused(String reason) {
            return new Ingress(null, reason);
        }
    }

    static final class Submission {

        final HologramWorkspace.ActionEndpoint endpoint;
        final Intent intent;
        final InventoryPlayer inventory;
        final ItemStack[] slots;
        final ItemStack projector;
        final String toolId;
        final HologramProjectedMainPolicy.Snapshot configured;
        final boolean creative;

        private Submission(HologramWorkspace.ActionEndpoint endpoint, Intent intent, InventoryPlayer inventory,
            ItemStack[] slots, ItemStack projector, String toolId, HologramProjectedMainPolicy.Snapshot configured,
            boolean creative) {
            this.endpoint = endpoint;
            this.intent = intent;
            this.inventory = inventory;
            this.slots = slots;
            this.projector = projector;
            this.toolId = toolId;
            this.configured = configured;
            this.creative = creative;
        }

        HologramMaterials.Settings sources() {
            return configured.selectedScope();
        }

        boolean current() {
            return refusal() == null;
        }

        String refusal() {
            var player = endpoint.player;
            if (!HologramWorkspace.currentActionEndpoint(endpoint)) return "施工连接或所在世界已变化。";
            if (player.isDead || !player.capabilities.allowEdit) return "当前玩家没有施工权限。";
            if (creative != player.capabilities.isCreativeMode) return "游戏模式已变化，请重新确认。";
            if (player.inventory != inventory || inventory.mainInventory != slots
                || inventory.currentItem != intent.slot
                || slots[intent.slot] != projector
                || player.getHeldItem() != projector
                || !ItemNekoHologramProjector.isProjector(projector)
                || !toolId.equals(toolId(projector))) return "手持投影仪已变化，请重新确认。";
            return null; // Source selection is this explicit request, not the tool's unrelated saved defaults.
        }
    }

    /** The same workspace object can change epoch and plan during a callback. Never recapture a newer state. */
    @Desugar
    private record Prior(HologramSession session, long epoch, long plan) {

        static Prior capture(HologramWorkspace.ActionEndpoint endpoint) {
            var session = HologramWorkspace.find(endpoint.actor);
            return session == null ? new Prior(null, 0, 0)
                : new Prior(session, session.editorWorkspaceEpoch, session.planRevision);
        }

        boolean unchanged() {
            return session == null || session.editorWorkspaceEpoch == epoch && session.planRevision == plan;
        }

        boolean current(HologramWorkspace.ActionEndpoint endpoint) {
            return HologramWorkspace.find(endpoint.actor) == session && unchanged();
        }

        HologramSession restorable(HologramWorkspace.ActionEndpoint endpoint) {
            return HologramWorkspace.currentActionEndpoint(endpoint) && unchanged() ? session : null;
        }
    }

    private final Submission submission;
    private final Prior prior;
    private final HologramSession session;
    private HologramNativeProjectionJob job;
    private HologramNativeProjectionJob.State state = HologramNativeProjectionJob.State.ACTIVE;
    private boolean advancing, retired;

    private HologramStockNativeEntry(Submission submission, Prior prior, HologramSession session) {
        this.submission = submission;
        this.prior = prior;
        this.session = session;
    }

    static Ingress inspect(HologramWorkspace.ActionEndpoint endpoint, HologramStockNativeWire.Confirmation confirm) {
        if (endpoint == null || confirm == null
            || confirm.tool() == null
            || confirm.dimension() != endpoint.player.dimension) return Ingress.refused("原生操作的玩家、投影仪或维度已变化。");
        var intent = confirm.intent();
        if (!intent.valid() || endpoint.constructionScope == null
            || intent.generation() != endpoint.constructionScope.generation
            || !HologramWorkspace.currentActionEndpoint(endpoint)) return Ingress.refused("原生操作确认已失效。");
        var player = endpoint.player;
        if (player.isDead || !player.capabilities.allowEdit) return Ingress.refused("当前玩家没有施工权限。");
        var inventory = player.inventory;
        var slots = inventory.mainInventory;
        if (intent.slot() >= slots.length || inventory.currentItem != intent.slot()) return Ingress.refused("手持栏已变化。");
        var held = slots[intent.slot()];
        String id = toolId(held);
        if (!ItemNekoHologramProjector.isProjector(held) || !confirm.tool()
            .toString()
            .equals(id)) return Ingress.refused("投影仪身份未同步，请重新打开结构。");
        var settings = new HologramMaterials.Settings();
        settings.main = (intent.sources() & 1) != 0;
        settings.containers = (intent.sources() & 2) != 0;
        settings.me = (intent.sources() & 4) != 0;
        settings.priority = (intent.sources() >>> 3) & 3;
        settings.recoveryDestination = (intent.sources() >>> 5) & 1;
        var configured = HologramProjectedMainPolicy.captureSaved(settings, true);
        if (configured == null) return Ingress.refused("当前材料来源选择无效。");
        return new Ingress(
            new Submission(
                endpoint,
                intent,
                inventory,
                slots,
                held,
                id,
                configured,
                player.capabilities.isCreativeMode),
            null);
    }

    private static String toolId(ItemStack tool) {
        return tool != null && tool.hasTagCompound() ? tool.getTagCompound()
            .getString(HologramSessionKeys.TOOL_ID) : "";
    }

    static HologramStockNativeWire.ServerObservation observe(Submission submission) throws IOException {
        if (submission == null || !submission.current()) throw new IOException("原生观察请求已变化。");
        var intent = submission.intent;
        return HologramNativeProjectionJob.observe(
            submission.endpoint,
            intent.projection(),
            intent.main(),
            intent.facing(),
            intent.x(),
            intent.y(),
            intent.z());
    }

    static HologramStockNativeEntry begin(Submission submission) throws IOException {
        if (submission == null || !submission.current()) throw new IOException("没有当前有效的原生操作确认。");
        var endpoint = submission.endpoint;
        var intent = submission.intent;
        var gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null) throw new IOException("原生操作连接已变化。");
        var prior = Prior.capture(endpoint);
        boolean busy = HologramConstructionAuthority.competingWork(endpoint.player, true)
            || prior.session != null && !HologramSessionPolicy.valid(prior.session);
        var admission = gate.acquire(
            endpoint.constructionScope,
            intent.request(),
            busy,
            HologramConstructionAuthority.hasKnownRecovery(endpoint.player));
        if (admission.decision != HologramConstructionTaskGate.Decision.GRANTED)
            throw new IOException("未取得原生操作许可：" + admission.decision);
        HologramSession session = null;
        boolean retained = false;
        try {
            session = new HologramSession(
                endpoint.player,
                intent.x(),
                intent.y(),
                intent.z(),
                true,
                intent.side(),
                false);
            // The private seat is part of reservation eligibility, before the atomic workspace handoff.
            session.stockNativePrivate = true;
            if (!submission.current() || !prior.current(endpoint)
                || session.boundTool != submission.projector
                || !HologramWorkspace.reserveStockNative(endpoint, prior.session, prior.epoch, prior.plan, session))
                throw new IOException("控制器、工作区或投影仪已变化。");
            session.main = intent.main();
            session.mainExplicit = intent.mainExplicit();
            session.noHatches = intent.noHatches();
            session.materialSettings = submission.sources();
            var entry = new HologramStockNativeEntry(submission, prior, session);
            entry.job = new HologramNativeProjectionJob(
                endpoint,
                gate,
                admission.lease,
                session,
                intent.projection(),
                intent.main(),
                intent.facing(),
                submission.creative,
                () -> submission.current() && prior.unchanged());
            retained = true;
            return entry;
        } finally {
            if (!retained) {
                if (session != null) HologramWorkspace.rollbackCompactNative(prior.restorable(endpoint), session);
                gate.finish(admission.lease, HologramConstructionTaskGate.End.FAILED);
            }
        }
    }

    HologramNativeProjectionJob.State tick(long deadline) {
        return tick(deadline, HologramConstructionTaskGate::beginTransaction);
    }

    HologramNativeProjectionJob.State tick(long deadline, HologramNativeProjectionJob.PermitAdmission admission) {
        if (retired) return state;
        if (advancing) throw new IllegalStateException("Native operation cannot re-enter");
        advancing = true;
        try {
            state = job.drain(deadline, admission);
            return state;
        } finally {
            advancing = false;
            retireIfSettled();
        }
    }

    boolean stop(HologramWorkspace.ActionEndpoint endpoint, HologramConstructionTaskGate.Request request) {
        if (retired || state != HologramNativeProjectionJob.State.ACTIVE
            || endpoint != submission.endpoint
            || !submission.intent.request()
                .equals(request)
            || !HologramWorkspace.currentActionEndpoint(endpoint)
            || !job.cancel()) return false;
        state = HologramNativeProjectionJob.State.STOPPED;
        if (!advancing) retireIfSettled();
        return true;
    }

    private void retireIfSettled() {
        if (state == HologramNativeProjectionJob.State.ACTIVE || retired) return;
        retired = true;
        if (state != HologramNativeProjectionJob.State.RECOVERY_PENDING)
            HologramWorkspace.rollbackCompactNative(prior.restorable(submission.endpoint), session);
    }

    boolean owns(HologramSession current) {
        return current == session && (state == HologramNativeProjectionJob.State.ACTIVE
            || state == HologramNativeProjectionJob.State.RECOVERY_PENDING);
    }

    int placed() {
        return job.placed();
    }

    String reason() {
        return job.reason();
    }

    HologramConstructionTaskGate.Permit unsettled() {
        return job.unsettled();
    }
}
