// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.common.tileentities.machines.multi.MTECokeOven;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Native ingress-to-slice adapter. Only the future authenticated stock-intent handler may call it. No preview,
 * blueprint, material quote, legacy start, or packet capture is part of this path. */
final class HologramStockNativeEntry {

    // This is ExtendedFacing.ordinal(), not a ForgeDirection or the count of unique transforms.
    // Keep all encoded rotations/flips. Admission still compares with the actual controller.
    private static boolean validControllerFacing(int facing) {
        return facing >= 0 && facing < ExtendedFacing.STATES_COUNT;
    }

    enum Funding {
        REQUIRE_FULL_BOUND,
        EXPLICIT_AVAILABLE_STOCK
    }

    static final String DECLARED = "gtmb:gregtech_declared";
    static final String EBF = "gtmb:gregtech_ebf_1000";

    @Desugar
    record EbfConfig(int coil, List<HologramStockEbfProfile.Installed> installed) {

        EbfConfig {
            installed = installed == null || installed.size() != 5 ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(installed));
        }

        NBTTagCompound channels() {
            var channels = new NBTTagCompound();
            if (coil != 0) channels.setInteger("coil", coil);
            return channels;
        }
    }

    @Desugar
    record Intent(HologramConstructionTaskGate.Request request, long generation, String source, int controller,
        int x, int y, int z, int side, int slot, int facing, int main, boolean mainExplicit, boolean noHatches,
        int materialPriority, Funding funding, HologramStockNativeConstruction.HatchChoice hatch, EbfConfig ebf,int sources,
        HologramDeclaredNativeSelection declaration) {
        Intent(HologramConstructionTaskGate.Request request,long generation,String source,int controller,int x,int y,int z,
            int side,int slot,int facing,int main,boolean mainExplicit,boolean noHatches,int materialPriority,Funding funding,
            HologramStockNativeConstruction.HatchChoice hatch,EbfConfig ebf,int sources) {
            this(request,generation,source,controller,x,y,z,side,slot,facing,main,mainExplicit,noHatches,materialPriority,
                funding,hatch,ebf,sources,null);
        }
        Intent(HologramConstructionTaskGate.Request request,long generation,String source,int controller,int x,int y,int z,
            int side,int slot,int facing,int main,boolean mainExplicit,boolean noHatches,int materialPriority,Funding funding,
            HologramStockNativeConstruction.HatchChoice hatch,EbfConfig ebf) {
            this(request,generation,source,controller,x,y,z,side,slot,facing,main,mainExplicit,noHatches,
                materialPriority,funding,hatch,ebf,1|(materialPriority<<3));
        }

        Intent(HologramConstructionTaskGate.Request request, long generation, String source, int controller,
            int x, int y, int z, int side, int slot, int facing, int main, boolean mainExplicit, boolean noHatches,
            int materialPriority, Funding funding, HologramStockNativeConstruction.HatchChoice hatch) {
            this(request, generation, source, controller, x, y, z, side, slot, facing, main, mainExplicit, noHatches,
                materialPriority, funding, hatch, null);
        }

        private boolean preservesIdleDraft() {
            return declaration != null && declaration.operation() == 0;
        }

        private boolean allowsCreative() {
            return preservesIdleDraft();
        }

        boolean valid() {
            // Every request freezes the exact enabled providers, ordering and recovery destination.
            // No missing/zero mask may silently fall back to a main-inventory-only payment.
            if(materialPriority<0||materialPriority>2)return false;
            if(sources<=0 || (sources&~63)!=0 || (sources&7)==0 || ((sources>>>3)&3)!=materialPriority)
                return false;
            if (declaration != null) return request != null
                && request.kind == HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE && generation > 0
                && DECLARED.equals(source) && controller == declaration.controller() && declaration.valid()
                && (declaration.operation() != 1 || !noHatches)
                && hatch == null && ebf == null && side >= 0 && side < 6 && slot >= 0 && slot < 9
                && validControllerFacing(facing) && main >= 1 && main <= 64 && funding == Funding.REQUIRE_FULL_BOUND;
            if (ebf != null) return request != null
                && request.kind == HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE && generation > 0
                && EBF.equals(source) && controller == HologramStockEbfProfile.CONTROLLER && hatch == null
                && side >= 0 && side < 6 && slot >= 0 && slot < 9 && validControllerFacing(facing)
                && main >= 1 && main <= 64 && funding == Funding.REQUIRE_FULL_BOUND
                && ebf.coil >= 0 && ebf.coil <= 64 && HologramStockEbfProfile.validInstalled(ebf.installed);

            // This schema represents only stock mode-0/full-scope intent. It cannot express edits,
            // geometry, arbitrary NBT, creative supply or destructive operations.
            return request != null && request.kind == HologramConstructionTaskGate.Kind.INCREMENTAL_NATIVE
                && generation > 0 && HologramStockCokeOvenProfile.SOURCE_ID.equals(source)
                && controller == HologramStockCokeOvenProfile.CONTROLLER
                && side >= 0 && side < 6 && slot >= 0 && slot < 9 && validControllerFacing(facing)
                && main >= 1 && main <= 64 && materialPriority >= 0 && materialPriority < 3 && funding != null
                && hatch != null && hatch.id == HologramStockCokeOvenProfile.HATCH
                && hatch.tier == HologramStockCokeOvenProfile.HATCH_TIER && hatch.facing >= 0 && hatch.facing < 6
                && Math.abs((long) hatch.dx) <= 2 && Math.abs((long) hatch.dy) <= 2 && Math.abs((long) hatch.dz) <= 2;
        }
    }

    /** Only bounded owner/config scalars at ingress; a projector may carry a large unrelated NBT compound. */
    @Desugar
    private record EbfToolStamp(NBTTagCompound identity, int damage, int count, int main, boolean mainExplicit,
        int coil) {

        static EbfToolStamp capture(ItemStack held) {
            NBTTagCompound tag = held == null ? null : held.getTagCompound();
            if (tag == null || tag.hasKey(HologramSessionKeys.DRAFT) || tag.getBoolean("gtitHologramDowngrade")
                || tag.hasKey("channels") && !tag.hasKey("channels", 10)) return null;
            var channels = tag.getCompoundTag("channels");
            int main = Math.max(1, tag.getInteger("gtitHologramMain"));
            if (HologramStockEbfProfile.coilMetadata(main, channels) < 0) return null;
            return new EbfToolStamp(tag, held.getItemDamage(), held.stackSize, main,
                tag.getBoolean("gtitHologramMainExplicit"), channels.getInteger("coil"));
        }

        boolean matches(ItemStack held) {
            var current = capture(held);
            return current != null && current.identity == identity && current.damage == damage && current.count == count
                && current.main == main && current.mainExplicit == mainExplicit && current.coil == coil;
        }
    }

    @Desugar
    record Ingress(Submission submission, String refusal) {
        private static Ingress refused(String detail) {
            return new Ingress(null, detail);
        }
    }

    /** The transport captures this immediately at authenticated ingress, before enqueuing server work. */
    static final class Submission {

        private final HologramWorkspace.ActionEndpoint endpoint;
        private final Intent intent;
        private final InventoryPlayer inventory;
        private final ItemStack[] slots;
        private final ItemStack projector;
        private final EbfToolStamp exactEbfTool;
        private final String toolId;
        private final HologramProjectedMainPolicy.Snapshot configured;
        private final boolean creative;

        private Submission(HologramWorkspace.ActionEndpoint endpoint, Intent intent, InventoryPlayer inventory,
            ItemStack[] slots, ItemStack projector, String toolId, HologramProjectedMainPolicy.Snapshot configured,
            EbfToolStamp exactEbfTool, boolean creative) {
            this.endpoint = endpoint;
            this.intent = intent;
            this.inventory = inventory;
            this.slots = slots;
            this.projector = projector;
            this.exactEbfTool = exactEbfTool;
            this.toolId = toolId;
            this.configured = configured;
            this.creative = creative;
        }

        private boolean sameInventory() {
            return endpoint.player.inventory == inventory && inventory.mainInventory == slots
                && inventory.currentItem == intent.slot && slots[intent.slot] == projector;
        }

        HologramMaterials.Settings sources() {
            return configured.selectedScope();
        }

        boolean current() {
            return refusal() == null;
        }

        String refusal() {
            EntityPlayerMP player = endpoint.player;
            if (!HologramWorkspace.currentActionEndpoint(endpoint)) return "施工连接或所在世界已变化，请重新确认。";
            if (player.isDead || !player.capabilities.allowEdit) return "当前玩家没有施工权限。";
            if (creative != player.capabilities.isCreativeMode) return "游戏模式已变化，请重新确认施工。";
            if (!sameInventory() || player.getHeldItem() != projector || !sameInventory()
                || !ItemNekoHologramProjector.isProjector(projector) || !toolId.equals(toolId(projector)))
                return "手持投影仪已变化，请重新确认施工。";
            if (exactEbfTool != null && !exactEbfTool.matches(projector))
                return "投影仪的高炉等级、线圈或草稿状态已变化，请重新确认施工。";
            if (!configured.configuredMatches(HologramMaterials.read(projector)))
                return "投影仪材料来源已变化，请重新确认施工。";
            return null;
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
    private final HologramStockNativeConstruction job;
    private final HologramStockEbfConstruction ebfJob;
    private final HologramDeclaredNativeConstruction declaredJob;
    private HologramStockNativeConstruction.State state = HologramStockNativeConstruction.State.ACTIVE;
    private boolean advancing, retired;

    private HologramStockNativeEntry(Submission submission, Prior prior, HologramSession session,
        HologramStockNativeConstruction job, HologramStockEbfConstruction ebfJob) {
        this(submission, prior, session, job, ebfJob, null);
    }

    private HologramStockNativeEntry(Submission submission, Prior prior, HologramSession session,
        HologramStockNativeConstruction job, HologramStockEbfConstruction ebfJob,
        HologramDeclaredNativeConstruction declaredJob) {
        this.submission = submission;
        this.prior = prior;
        this.session = session;
        this.job = job;
        this.ebfJob = ebfJob;
        this.declaredJob = declaredJob;
    }

    static Submission ingress(HologramWorkspace.ActionEndpoint endpoint, Intent intent, String confirmedToolId) {
        return inspect(endpoint, intent, confirmedToolId).submission;
    }

    static Submission ingress(HologramWorkspace.ActionEndpoint endpoint, Intent intent) {
        return inspect(endpoint, intent, null).submission;
    }

    static Ingress inspect(HologramWorkspace.ActionEndpoint endpoint, HologramStockNativeWire.Confirmation confirmed) {
        if (confirmed == null || confirmed.tool() == null) return Ingress.refused("缺少原生施工确认。");
        if (endpoint == null) return Ingress.refused("施工连接或所在世界已变化，请重新确认。");
        if (confirmed.dimension() != endpoint.player.dimension)
            return Ingress.refused("施工所属维度已变化，请重新确认。");
        return inspect(endpoint, confirmed.intent(), confirmed.tool().toString());
    }

    private static Ingress inspect(HologramWorkspace.ActionEndpoint endpoint, Intent intent, String confirmedToolId) {
        if (endpoint == null || endpoint.constructionScope == null || !HologramWorkspace.currentActionEndpoint(endpoint))
            return Ingress.refused("施工连接或所在世界已变化，请重新确认。");
        if (intent == null || !intent.valid() || intent.generation != endpoint.constructionScope.generation)
            return Ingress.refused("原生施工确认已失效，请重新确认。");
        EntityPlayerMP player = endpoint.player;
        if (player.isDead || !player.capabilities.allowEdit) return Ingress.refused("当前玩家没有施工权限。");
        boolean creative = player.capabilities.isCreativeMode;
        if (creative && !intent.allowsCreative())
            return Ingress.refused("创造模式的结构施工仅支持新建和补建；未开始施工。");
        var inventory = player.inventory;
        ItemStack[] slots = inventory.mainInventory;
        if (intent.slot >= slots.length || inventory.currentItem != intent.slot)
            return Ingress.refused("手持栏已变化，请重新确认施工。");
        ItemStack held = slots[intent.slot];
        if (!ItemNekoHologramProjector.isProjector(held)) return Ingress.refused("当前未手持已确认的投影仪。");
        String id = toolId(held);
        // Do not mint a new identity or adopt a different projector in response to a short intent.
        if (!HologramNativeCompactStart.canonical(id) || confirmedToolId != null && !id.equals(confirmedToolId))
            return Ingress.refused("投影仪身份未同步或已变化，请重新打开结构后确认。");
        var settings = HologramMaterials.read(held);
        var configured = HologramProjectedMainPolicy.captureSelected(settings);
        if (configured == null || HologramProjectedMainPolicy.settingsKey(settings) != intent.sources
            || settings.priority != intent.materialPriority)
            return Ingress.refused("投影仪材料来源设置与确认不一致，请重新确认。");
        EbfToolStamp exactEbfTool = intent.ebf == null ? null : EbfToolStamp.capture(held);
        if (intent.ebf != null && exactEbfTool == null)
            return Ingress.refused("投影仪有草稿或高炉等级和线圈设置无法核对。");
        return new Ingress(new Submission(endpoint, intent, inventory, slots, held, id, configured, exactEbfTool,
            creative), null);
    }

    private static String toolId(ItemStack tool) {
        return tool.hasTagCompound() ? tool.getTagCompound().getString(HologramSessionKeys.TOOL_ID) : "";
    }

    static HologramStockNativeEntry begin(Submission submission) throws IOException {
        if (submission == null) throw new IOException("没有当前原生补建的明确确认。");
        var endpoint = submission.endpoint;
        var intent = submission.intent;
        var gate = HologramWorkspace.constructionGate(endpoint);
        if (gate == null) throw new IOException("原生补建的连接已变化。");
        var player = endpoint.player;
        Prior prior = Prior.capture(endpoint);
        String refusal = submission.refusal();
        boolean current = refusal == null;
        boolean competing = !current || HologramConstructionAuthority.competingWork(player, intent.preservesIdleDraft())
            || prior.session != null && !HologramSessionPolicy.valid(prior.session);
        boolean recovery = current && HologramConstructionAuthority.hasKnownRecovery(player);
        // Consume the authenticated sequence even for refusal. A transport replay may not wait out missing stock,
        // stale tool, invalid anchor, busy work or recovery and then become a delayed start.
        var admission = gate.acquire(endpoint.constructionScope, intent.request, competing, recovery);
        if (admission.decision != HologramConstructionTaskGate.Decision.GRANTED)
            throw new IOException(!current ? refusal : "原生补建未获得许可：" + admission.decision);
        boolean taken = false;
        try {
            var entry = open(submission, gate, admission.lease, prior);
            taken = true;
            return entry;
        } finally {
            if (!taken) gate.finish(admission.lease, HologramConstructionTaskGate.End.FAILED);
        }
    }

    private static HologramStockNativeEntry open(Submission submission, HologramConstructionTaskGate gate,
        HologramConstructionTaskGate.Lease lease, Prior prior) throws IOException {
        var endpoint = submission.endpoint;
        var intent = submission.intent;
        var player = endpoint.player;
        if (!intent.preservesIdleDraft() && submission.projector.hasTagCompound()
            && submission.projector.getTagCompound().hasKey(HologramSessionKeys.DRAFT))
            throw new IOException("此投影仪有未移开的本地草稿；未启动原生补建。");
        if (intent.declaration != null) return openDeclared(submission, gate, lease, prior);
        if (intent.ebf != null) return openEbf(submission, gate, lease, prior);
        var profile = HologramStockCokeOvenProfile.inspectRegistered();
        if (profile == null) throw new IOException("当前服务器未识别受支持的原生焦炉声明。");
        World world = endpoint.world;
        if (!HologramWorldFlow.worldPosition(intent.x, intent.y, intent.z, world.getHeight())
            || !world.blockExists(intent.x, intent.y, intent.z)
            || player.getDistanceSq(intent.x + .5, intent.y + .5, intent.z + .5) > 64
            || world.getBlock(intent.x, intent.y, intent.z) != GregTechAPI.sBlockMachines
            || !(world.getTileEntity(intent.x, intent.y, intent.z) instanceof BaseMetaTileEntity tile)
            || tile.getClass() != BaseMetaTileEntity.class || tile.isInvalid()
            || !profile.acceptsController(tile) || !(tile.getMetaTileEntity() instanceof MTECokeOven machine)
            || machine.getExtendedFacing().ordinal() != intent.facing
            || !profile.shellAt(machine.getExtendedFacing(), intent.hatch.dx, intent.hatch.dy, intent.hatch.dz))
            throw new IOException("现场不是已审核的原生焦炉，或控制器朝向及所选仓室位置已更改。");
        ItemStack casing = profile.casingItem();
        if (casing == null) throw new IOException("无法确认原生焦炉砖。");
        if (intent.funding == Funding.REQUIRE_FULL_BOUND
            && !HologramStockNativeConstruction.hasMaximumStock(player, intent.slot, casing, submission.sources()))
            throw new IOException("所选材料来源须备齐 24 块焦炉砖；未开始施工。");
        if (HologramConstructionAuthority.competingWork(player) || !submission.current() || !prior.current(endpoint)
            || gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE)
            throw new IOException("等待期间投影仪或原工作台已变化。");

        // Physical open is client-local. No old server editor or scanned geometry is needed: reserve only this
        // narrow temporary session, without activating, publishing or saving anything over the old UI.
        var target = new HologramSession(player, intent.x, intent.y, intent.z, true, intent.side);
        target.stockNativePrivate = true;
        if (!submission.current() || HologramConstructionAuthority.competingWork(player)
            || !submission.toolId.equals(target.toolId) || !prior.current(endpoint)
            || target.boundTool != submission.projector || target.target != tile || target.machine != machine
            || target.facing != intent.facing
            || gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE
            || !HologramWorkspace.reserveStockNative(endpoint, prior.session, prior.epoch, prior.plan, target))
            throw new IOException("原生控制器或工作台在启动前已变化。");
        boolean taken = false;
        try {
            if (!reservedCurrent(submission, prior, target, gate, lease))
                throw new IOException("原工作台或投影仪在占用时已变化。");
            target.main = intent.main;
            target.mainExplicit = intent.mainExplicit;
            target.noHatches = intent.noHatches;
            // This route cannot silently discard tool channel/edited config. No fallback and no mutating settings.
            if (!target.channels.hasNoTags() || target.hatchDowngrade)
                throw new IOException("已设置的原生信道或仓室规则尚不支持逐格补建。");
            // Shared direct-source policy approves main without overriding a configured container-first choice.
            // Do not persist the private execution view to the actual projector.
            target.materialSettings = submission.sources();
            var job = HologramStockNativeConstruction.openOwned(
                endpoint, gate, lease, target, intent.main, intent.hatch, intent.funding);
            if (!reservedCurrent(submission, prior, target, gate, lease))
                throw new IOException("原工作台或投影仪在施工准入期间已变化。");
            var owned = new HologramStockNativeEntry(submission, prior, target, job, null);
            taken = true;
            return owned;
        } finally {
            if (!taken) HologramWorkspace.rollbackCompactNative(prior.restorable(endpoint), target);
        }
    }

    private static HologramStockNativeEntry openEbf(Submission submission, HologramConstructionTaskGate gate,
        HologramConstructionTaskGate.Lease lease, Prior prior) throws IOException {
        var endpoint = submission.endpoint;
        var intent = submission.intent;
        var player = endpoint.player;
        World world = endpoint.world;
        var profile = HologramStockEbfProfile.inspectRegistered();
        if (profile == null || !HologramWorldFlow.worldPosition(intent.x, intent.y, intent.z, world.getHeight())
            || !world.blockExists(intent.x, intent.y, intent.z)
            || player.getDistanceSq(intent.x + .5, intent.y + .5, intent.z + .5) > 64
            || world.getBlock(intent.x, intent.y, intent.z) != GregTechAPI.sBlockMachines
            || !(world.getTileEntity(intent.x, intent.y, intent.z) instanceof BaseMetaTileEntity tile)
            || tile.getClass() != BaseMetaTileEntity.class || tile.isInvalid()
            || !(tile.getMetaTileEntity() instanceof MTEElectricBlastFurnace machine)
            || machine.getClass() != MTEElectricBlastFurnace.class
            || machine.getExtendedFacing().ordinal() != intent.facing)
            throw new IOException("现场不是已审核的未成型电力高炉，或控制器已变更。");
        var channels = intent.ebf.channels();
        var site = profile.qualify(world, tile, machine.getExtendedFacing(), intent.main, channels, intent.ebf.installed);
        if (site == null) throw new IOException("未能核实电力高炉的线圈和五个已安装仓室。");
        var stock = submission.sources();
        if (!site.fullyFunded(player.inventory, intent.slot, stock))
            throw new IOException("普通高炉补建须在所选来源备齐 12 个耐热外壳和 16 个所选线圈；未开始施工。");
        if (HologramConstructionAuthority.competingWork(player) || !submission.current() || !prior.current(endpoint)
            || gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE)
            throw new IOException("等待期间投影仪或原工作台已变化。");

        var target = new HologramSession(player, intent.x, intent.y, intent.z, true, intent.side);
        target.stockNativePrivate = true;
        if (!submission.current() || HologramConstructionAuthority.competingWork(player)
            || !submission.toolId.equals(target.toolId) || !prior.current(endpoint) || target.boundTool != submission.projector
            || target.target != tile || target.machine != machine || target.facing != intent.facing
            || gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE
            || !HologramWorkspace.reserveStockNative(endpoint, prior.session, prior.epoch, prior.plan, target))
            throw new IOException("原生控制器或工作台在启动前已变化。");
        boolean taken = false;
        try {
            if (!reservedCurrent(submission, prior, target, gate, lease) || target.hatchDowngrade
                || HologramStockEbfProfile.coilMetadata(target.main, target.channels) < 0)
                throw new IOException("原工作台、投影仪或不受支持的信道配置已变化。");
            target.main = intent.main;
            target.mainExplicit = intent.mainExplicit;
            target.noHatches = intent.noHatches;
            target.channels = channels;
            target.materialSettings = stock;
            var job = new HologramStockEbfConstruction(endpoint, gate, lease, target, site);
            if (!job.admissionCurrent() || !reservedCurrent(submission, prior, target, gate, lease))
                throw new IOException("原工作台、仓室、线圈或投影仪在施工准入期间已变化。");
            var owned = new HologramStockNativeEntry(submission, prior, target, null, job);
            taken = true;
            return owned;
        } finally {
            if (!taken) HologramWorkspace.rollbackCompactNative(prior.restorable(endpoint), target);
        }
    }

    private static HologramStockNativeEntry openDeclared(Submission submission, HologramConstructionTaskGate gate,
        HologramConstructionTaskGate.Lease lease, Prior prior) throws IOException {
        var endpoint = submission.endpoint;
        var intent = submission.intent;
        var player = endpoint.player;
        World world = endpoint.world;
        if (!HologramWorldFlow.worldPosition(intent.x, intent.y, intent.z, world.getHeight())
            || !world.blockExists(intent.x, intent.y, intent.z)
            || player.getDistanceSq(intent.x + .5, intent.y + .5, intent.z + .5) > 64
            || world.getBlock(intent.x, intent.y, intent.z) != GregTechAPI.sBlockMachines
            || !(world.getTileEntity(intent.x, intent.y, intent.z) instanceof BaseMetaTileEntity tile)
            || tile.isInvalid() || tile.getMetaTileID() != intent.controller
            || intent.controller >= GregTechAPI.METATILEENTITIES.length
            || !(tile.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?> machine)
            || !(GregTechAPI.METATILEENTITIES[intent.controller] instanceof MTEEnhancedMultiBlockBase<?> profile)
            || machine.getClass() != profile.getClass() || machine.getExtendedFacing().ordinal() != intent.facing)
            throw new IOException("现场控制器、注册声明或朝向与确认不一致。");
        var target = new HologramSession(player, intent.x, intent.y, intent.z, true, intent.side, false);
        target.stockNativePrivate = true;
        if (!submission.current() || HologramConstructionAuthority.competingWork(player, intent.preservesIdleDraft())
            || !submission.toolId.equals(target.toolId) || !prior.current(endpoint) || target.boundTool != submission.projector
            || target.target != tile || target.machine != machine || target.facing != intent.facing
            || gate.query(lease.scope, lease.request).phase != HologramConstructionTaskGate.Phase.ACTIVE
            || !HologramWorkspace.reserveStockNative(endpoint, prior.session, prior.epoch, prior.plan, target))
            throw new IOException("控制器或已确认的工作台在准入前发生变化。");
        boolean taken = false;
        try {
            if (!reservedCurrent(submission, prior, target, gate, lease) || target.hatchDowngrade)
                throw new IOException("工作台、投影仪或仓室降级规则与当前确认不兼容。");
            target.main = intent.main;
            target.mainExplicit = intent.mainExplicit;
            target.noHatches = intent.noHatches;
            target.channels = intent.declaration.channelTag();
            target.mode = intent.declaration.operation();
            target.materialSettings = submission.sources();
            if (submission.creative) {
                // The private execution view is free; the held-tool settings remain confirmation authority.
                target.materialSettings.main = target.materialSettings.containers = target.materialSettings.me = false;
            }
            var job = new HologramDeclaredNativeConstruction(endpoint, gate, lease, target, intent.declaration,
                submission.creative, () -> submission.current() && prior.unchanged());
            var owned = new HologramStockNativeEntry(submission, prior, target, null, null, job);
            taken = true;
            return owned;
        } finally {
            if (!taken) HologramWorkspace.rollbackCompactNative(prior.restorable(endpoint), target);
        }
    }

    private static boolean reservedCurrent(Submission submission, Prior prior, HologramSession target,
        HologramConstructionTaskGate gate, HologramConstructionTaskGate.Lease lease) {
        return submission.current() && prior.unchanged()
            && HologramWorkspace.find(submission.endpoint.actor) == target
            && gate.query(lease.scope, lease.request).phase == HologramConstructionTaskGate.Phase.ACTIVE;
    }

    HologramStockNativeConstruction.State tick(long deadline) {
        return tick(deadline, HologramConstructionTaskGate::beginTransaction);
    }

    HologramStockNativeConstruction.State tick(long deadline,
        HologramStockNativeConstruction.PermitAdmission admission) {
        if (retired) return state;
        if (advancing) throw new IllegalStateException("Native stock entry cannot re-enter");
        advancing = true;
        try {
            state = declaredJob != null ? declaredJob.drain(deadline, admission) : ebfJob == null ? job.drain(deadline, admission)
                : ebfJob.drain(deadline, admission);
            return state;
        } finally {
            advancing = false;
            retireIfSettled();
        }
    }

    boolean stop(HologramWorkspace.ActionEndpoint endpoint, HologramConstructionTaskGate.Request request) {
        if (retired || state != HologramStockNativeConstruction.State.ACTIVE || endpoint != submission.endpoint
            || !submission.intent.request.equals(request) || !HologramWorkspace.currentActionEndpoint(endpoint)
            || !(declaredJob != null ? declaredJob.cancel() : ebfJob == null ? job.cancel() : ebfJob.cancel())) return false;
        state = HologramStockNativeConstruction.State.STOPPED;
        if (!advancing) retireIfSettled();
        return true;
    }

    private void retireIfSettled() {
        if (state == HologramStockNativeConstruction.State.ACTIVE || retired) return;
        retired = true;
        // An unresolved permit remains owned by this entry for exact journal resolution. Never invent rollback.
        if (state == HologramStockNativeConstruction.State.RECOVERY_PENDING) return;
        HologramWorkspace.rollbackCompactNative(prior.restorable(submission.endpoint), session);
    }

    boolean owns(HologramSession current) {
        return current == session && (state == HologramStockNativeConstruction.State.ACTIVE
            || state == HologramStockNativeConstruction.State.RECOVERY_PENDING);
    }

    int placed() {
        return declaredJob != null ? declaredJob.placed() : ebfJob == null ? job.placed() : ebfJob.placed();
    }

    String reason() {
        return declaredJob != null ? declaredJob.reason() : ebfJob == null ? job.reason() : ebfJob.reason();
    }

    HologramConstructionTaskGate.Permit unsettled() {
        return declaredJob != null ? declaredJob.unsettled() : ebfJob == null ? job.unsettled() : ebfJob.unsettled();
    }
}
