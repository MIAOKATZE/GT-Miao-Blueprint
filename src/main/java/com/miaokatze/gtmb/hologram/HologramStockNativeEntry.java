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
    record Intent(HologramStockNativeWire.Key request, int x, int y, int z, int side, int slot, int facing, int main,
        boolean mainExplicit, boolean noHatches, int sources, HologramStockNativeWire.Projection projection) {

        boolean valid() {
            return request != null && request.valid()
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
                && HologramMaterialStockWire.decode(sources) != null;
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
            if (!HologramWorkspace.currentActionEndpoint(endpoint)) return "施工连接或所在世界已变化。";
            return contextRefusal();
        }

        String contextRefusal() {
            var player = endpoint.player;
            if (player.isDead || !player.capabilities.allowEdit) return "当前玩家没有施工权限。";
            if (creative != player.capabilities.isCreativeMode) return "游戏模式已变化，请重新确认。";
            if (player.inventory != inventory || inventory.mainInventory != slots
                || slots[intent.slot] != projector
                || !ItemNekoHologramProjector.isProjector(projector)
                || !toolId.equals(toolId(projector))) return "手持投影仪已变化，请重新确认。";
            return null; // Source selection is this explicit request, not the tool's unrelated saved defaults.
        }
    }

    private final Submission submission;
    private final HologramConstructionJobs.Job job;

    private HologramStockNativeEntry(Submission submission, HologramConstructionJobs.Job job) {
        this.submission = submission;
        this.job = job;
    }

    static Ingress inspect(HologramWorkspace.ActionEndpoint endpoint, HologramStockNativeWire.Confirmation confirm) {
        if (endpoint == null || confirm == null
            || confirm.tool() == null
            || confirm.dimension() != endpoint.player.dimension) return Ingress.refused("原生操作的玩家、投影仪或维度已变化。");
        var intent = confirm.intent();
        if (!intent.valid() || !HologramWorkspace.currentActionEndpoint(endpoint)) return Ingress.refused("原生操作确认已失效。");
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
        var settings = HologramMaterialStockWire.decode(intent.sources());
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
        if (submission == null || !submission.current()) throw new IOException("原生操作确认已失效。");
        var endpoint = submission.endpoint;
        if (HologramConstructionAuthority.competingWork(endpoint.player, true)) throw new IOException("已有施工或编辑任务。");
        var intent = submission.intent;
        var session = new HologramSession(
            endpoint.player,
            intent.x(),
            intent.y(),
            intent.z(),
            true,
            intent.side(),
            false);
        session.stockNativePrivate = true;
        session.main = intent.main();
        session.mainExplicit = intent.mainExplicit();
        session.noHatches = intent.noHatches();
        session.materialSettings = submission.sources();
        var source = new HologramNativeProjectionJob(
            endpoint,
            session,
            intent.projection(),
            intent.main(),
            intent.facing(),
            submission.creative,
            submission);
        var job = HologramConstructionJobs.start(
            intent.request()
                .request(),
            0,
            source);
        return new HologramStockNativeEntry(submission, job);
    }

    HologramNativeProjectionJob.State state() {
        if (!job.terminal) return HologramNativeProjectionJob.State.ACTIVE;
        return job.end == HologramConstructionJobs.End.COMPLETE ? HologramNativeProjectionJob.State.COMPLETE
            : job.end == HologramConstructionJobs.End.RECOVERY_PENDING
                ? HologramNativeProjectionJob.State.RECOVERY_PENDING
                : HologramNativeProjectionJob.State.STOPPED;
    }

    boolean stop(HologramWorkspace.ActionEndpoint endpoint, HologramStockNativeWire.Key request) {
        return endpoint == submission.endpoint && request.equals(submission.intent.request())
            && HologramWorkspace.currentActionEndpoint(endpoint)
            && HologramConstructionJobs.cancel(endpoint.player, request.request());
    }

    boolean owns(HologramSession current) {
        return current == job.session && !job.terminal;
    }

    int placed() {
        return (int) job.completed;
    }

    String reason() {
        return job.reason;
    }
}
