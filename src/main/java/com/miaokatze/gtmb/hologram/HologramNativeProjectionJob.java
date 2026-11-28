// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Operation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Projection;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.ServerObservation;
import com.miaokatze.gtmb.hologram.HologramStockNativeWire.Stage;

import gregtech.api.GregTechAPI;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;

/** Native construct output is the sole structure definition. No factory/class/profile recognition is used here. */
final class HologramNativeProjectionJob implements HologramConstructionJobs.Source {

    enum State {
        ACTIVE,
        COMPLETE,
        STOPPED,
        RECOVERY_PENDING
    }

    private static final class Baseline {

        final java.lang.ref.WeakReference<World> world;
        final int x, y, z, facing, id;
        final List<HologramNativeTargetPlan.Offset> offsets;
        NBTTagCompound siteSettings = new NBTTagCompound();

        Baseline(BaseMetaTileEntity controller, List<HologramNativeTargetPlan.Offset> offsets) {
            world = new java.lang.ref.WeakReference<>(controller.getWorldObj());
            x = controller.xCoord;
            y = controller.yCoord;
            z = controller.zCoord;
            id = controller.getMetaTileID();
            facing = ((MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity()).getExtendedFacing()
                .ordinal();
            this.offsets = java.util.Collections.unmodifiableList(new ArrayList<>(offsets));
        }

        boolean matches(BaseMetaTileEntity controller) {
            return world.get() == controller.getWorldObj() && x == controller.xCoord
                && y == controller.yCoord
                && z == controller.zCoord
                && id == controller.getMetaTileID()
                && controller.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>machine
                && machine.getExtendedFacing()
                    .ordinal() == facing;
        }
    }

    private static final Map<BaseMetaTileEntity, Baseline> BASELINES = new java.util.WeakHashMap<>();
    private static final int BASELINE_CELLS = 1_000_000;

    static synchronized void clearBaselines() {
        BASELINES.clear();
    }

    private final HologramWorkspace.ActionEndpoint endpoint;
    private final HologramSession session;
    private final Projection intent;
    private final HologramStockNativeEntry.Submission submission;
    private final BaseMetaTileEntity controller;
    private final MTEEnhancedMultiBlockBase<?> machine;
    private final ExtendedFacing facing;
    private HologramNativeTargetPlan target;
    private NBTTagCompound completedSettings;
    private final int main;
    private final long started = System.nanoTime();
    static final String LIMITED_SCOPE_DETAIL = "按本次原生已知范围处理；更早参数丢失的范围外残留无法识别。";
    private boolean initialized, scopeFallback;
    private boolean synchronizesHistory;
    private Baseline previous;
    private List<HologramNativeTargetPlan.Offset> baselineOffsets, operationOffsets;
    private List<HologramNativeTargetPlan.Offset> targetOffsets = new ArrayList<>();
    private final java.util.TreeMap<HologramNativeTargetPlan.Offset, HologramNativeTargetPlan.Cell> union = new java.util.TreeMap<>(
        Comparator.comparingInt(HologramNativeTargetPlan.Offset::y)
            .reversed()
            .thenComparingInt(HologramNativeTargetPlan.Offset::z)
            .thenComparingInt(HologramNativeTargetPlan.Offset::x));
    private java.util.Iterator<HologramNativeTargetPlan.Cell> unionRows;
    private int preparePhase, prepareCursor;
    private boolean occupied;
    private long preparedTotal;
    private HologramMaterials.Context stock, recovery;
    private List<HologramNativeTargetPlan.Cell> work = java.util.Collections.emptyList();
    private final Map<HologramBlueprintResources.ExactKey, ItemStack> funding = new LinkedHashMap<>();
    private HologramNativeConstruction.ProjectionCache placements = new HologramNativeConstruction.ProjectionCache();
    private final boolean creative;
    private int next, fundCursor;
    private boolean anchorRemoved;
    private java.util.Iterator<ItemStack> fundRows;

    HologramNativeProjectionJob(HologramWorkspace.ActionEndpoint endpoint, HologramSession session, Projection intent,
        int main, int encodedFacing, boolean creative, HologramStockNativeEntry.Submission submission)
        throws IOException {
        this.endpoint = endpoint;
        this.session = session;
        this.intent = intent;
        this.submission = submission;
        this.creative = creative;
        this.main = main;
        controller = controller(endpoint, intent, session.x, session.y, session.z, encodedFacing);
        machine = (MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity();
        facing = machine.getExtendedFacing();
        requirePortableConfiguration();
        if (!current()) throw new IOException("原生目标准备期间控制器、访问权限或工具已变化。");
    }

    /** Preparation uses the existing job budget; native checks remain indivisible server callbacks. */
    private boolean initialize(long deadline) throws IOException {
        while (System.nanoTime() < deadline) {
            requirePortableConfiguration();
            if (!current()) throw new IOException("施工上下文已变化。");
            switch (preparePhase) {
                case 0 -> {
                    previous = cachedBaseline(controller);
                    synchronizesHistory = intent.operation() == Operation.REPLACE
                        || intent.operation() == Operation.DEMOLISH
                        || intent.operation() == Operation.REPAIR && previous != null
                            && previous.siteSettings.getBoolean("siteKnown");
                    if ((intent.operation() == Operation.REPLACE || intent.operation() == Operation.DEMOLISH)
                        && (previous == null || !previous.siteSettings.hasKey("siteKnown")))
                        throw new IOException("请先完整读取现场配置，再替换或拆除。");
                    if (previous != null && previous.siteSettings.hasKey("siteKnown")
                        && !previous.siteSettings.getBoolean("siteKnown")
                        && (intent.operation() == Operation.REPLACE || intent.operation() == Operation.DEMOLISH))
                        throw new IOException("现场范围尚未被原生流程完整证明，不能替换或拆除。");
                    scopeFallback = (previous == null || !previous.siteSettings.getBoolean("siteKnown"))
                        && intent.operation() != Operation.BUILD;
                    target = intent.operation() == Operation.DEMOLISH
                        ? previous == null ? beginFootprint(intent, main, facing) : null
                        : beginTarget(intent, main, facing, previous == null ? null : previous.siteSettings);
                    preparePhase++;
                }
                case 1 -> {
                    if (target != null && !target.advance(deadline)) return false;
                    if (target != null && !target.complete()) throw new IOException(target.failure());
                    if (target != null && intent.operation() != Operation.DEMOLISH) {
                        completedSettings = target.configuration();
                        completedSettings.setInteger("main", main);
                        var acceptedChannels = new NBTTagCompound();
                        intent.channels()
                            .forEach(acceptedChannels::setInteger);
                        completedSettings.setTag("channels", acceptedChannels);
                        completedSettings.setBoolean("siteKnown", true);
                    }
                    if (intent.operation() != Operation.DEMOLISH && !target.targetDigest()
                        .equals(intent.nativeTargetDigest())) throw new IOException("客户端投影与服务器原生目标不同，请重新读取后确认。");
                    baselineOffsets = previous == null || previous.offsets.isEmpty() ? targetOffsets : previous.offsets;
                    prepareCursor = 0;
                    preparePhase++;
                }
                case 2 -> {
                    if (target != null) {
                        var cells = target.cells();
                        while (prepareCursor < cells.size() && System.nanoTime() < deadline) {
                            var cell = cells.get(prepareCursor++);
                            var offset = offset(cell);
                            targetOffsets.add(offset);
                            union.put(
                                offset,
                                intent.operation() == Operation.DEMOLISH ? air(offset) : constructionCell(cell));
                        }
                        if (prepareCursor < cells.size()) return false;
                        target.close();
                        target = null;
                        if (completedSettings != null)
                            completedSettings.setInteger("siteFootprintCells", targetOffsets.size());
                    }
                    prepareCursor = 0;
                    preparePhase++;
                }
                case 3 -> {
                    while (prepareCursor < baselineOffsets.size() && System.nanoTime() < deadline) {
                        var offset = baselineOffsets.get(prepareCursor++);
                        // A proved old scope participates in native target synchronization. Unknown repair
                        // can only touch the explicitly confirmed new target, never infer removal authority.
                        if (synchronizesHistory) union.computeIfAbsent(offset, HologramNativeProjectionJob::air);
                        if (offset.x() == 0 && offset.y() == 0 && offset.z() == 0) continue;
                        int x = controller.xCoord + offset.x(), y = controller.yCoord + offset.y(),
                            z = controller.zCoord + offset.z();
                        if (!endpoint.world.blockExists(x, y, z)) throw new IOException("当前结构范围包含未加载区块。");
                        occupied |= endpoint.world.getBlock(x, y, z) != Blocks.air
                            || endpoint.world.getTileEntity(x, y, z) != null;
                    }
                    if (prepareCursor < baselineOffsets.size()) return false;
                    preparePhase++;
                }
                case 4 -> {
                    // GT/StructureLib world callbacks cannot safely be moved across threads or interrupted.
                    checkNativeStructure(machine, controller);
                    if (!current()) throw new IOException("施工上下文已变化。");
                    var stage = machine.mMachine ? Stage.FORMED : occupied ? Stage.UNFORMED : Stage.CONTROLLER_ONLY;
                    if (!allows(intent.operation(), stage)) throw new IOException("当前结构状态不能执行此操作：" + stage);
                    baseline(controller, baselineOffsets);
                    stock = new HologramMaterials.Context(endpoint.player, session.materialSettings, session.slot);
                    session.materialContext = stock;
                    recovery = HologramDemolition.recoveryContext(endpoint.player, session.slot, intent.destination());
                    work = new ArrayList<>();
                    operationOffsets = previous == null ? targetOffsets : new ArrayList<>();
                    unionRows = union.values()
                        .iterator();
                    preparePhase++;
                }
                case 5 -> {
                    while (unionRows.hasNext() && System.nanoTime() < deadline) {
                        var cell = unionRows.next();
                        if (operationOffsets != targetOffsets) operationOffsets.add(offset(cell));
                        if (cell.dx() != 0 || cell.dy() != 0 || cell.dz() != 0) work.add(cell);
                        unionRows.remove();
                    }
                    if (unionRows.hasNext()) return false;
                    unionRows = null;
                    // An interrupted replacement may have touched new-only positions. Keep their union until success.
                    synchronized (HologramNativeProjectionJob.class) {
                        BASELINES.remove(controller);
                        java.util.Set<HologramNativeTargetPlan.Offset> restoration = new java.util.LinkedHashSet<>(
                            operationOffsets);
                        if (previous != null && !synchronizesHistory) restoration.addAll(previous.offsets);
                        Baseline retained = baseline(controller, new ArrayList<>(restoration));
                        if (previous != null) retained.siteSettings = (NBTTagCompound) previous.siteSettings.copy();
                    }
                    operationOffsets = null;
                    previous = null;
                    baselineOffsets = null;
                    preparedTotal = work.size() + (intent.operation() == Operation.DEMOLISH ? 1 : 0);
                    initialized = true;
                    return true;
                }
                default -> throw new IOException("原生准备阶段无效。");
            }
        }
        return false;
    }

    private static HologramNativeTargetPlan.Offset offset(HologramNativeTargetPlan.Cell cell) {
        return new HologramNativeTargetPlan.Offset(cell.dx(), cell.dy(), cell.dz());
    }

    private static HologramNativeTargetPlan.Cell constructionCell(HologramNativeTargetPlan.Cell cell) {
        // Native placement consumes the owned material/NBT snapshot, never a simulation element or its world.
        return new HologramNativeTargetPlan.Cell(
            cell.dx(),
            cell.dy(),
            cell.dz(),
            cell.block(),
            cell.metadata(),
            cell.material(),
            cell.facing(),
            null,
            cell.tileData(),
            null,
            null,
            cell.requiredAir());
    }

    private static HologramNativeTargetPlan.Cell air(HologramNativeTargetPlan.Offset offset) {
        return new HologramNativeTargetPlan.Cell(
            offset.x(),
            offset.y(),
            offset.z(),
            Blocks.air,
            0,
            null,
            -1,
            null,
            null,
            null,
            null,
            true);
    }

    /** A PROBE owns only its footprint run, and releases it at every terminal boundary. */
    static final class Observation implements AutoCloseable {

        private final HologramStockNativeEntry.Submission submission;
        private final BaseMetaTileEntity controller;
        private final Baseline cached;
        private HologramNativeTargetPlan pending;
        private HologramNativeSitePlan site;
        private NBTTagCompound siteSettings = new NBTTagCompound();
        private boolean siteRead;
        private final List<HologramNativeTargetPlan.Offset> offsets = new ArrayList<>();
        private int cursor;
        private boolean occupied, closed;
        private ServerObservation result;

        Observation(HologramStockNativeEntry.Submission submission) throws IOException {
            this.submission = submission;
            var intent = submission.intent;
            controller = controller(
                submission.endpoint,
                intent.projection(),
                intent.x(),
                intent.y(),
                intent.z(),
                intent.facing());
            cached = cachedBaseline(controller);
            siteRead = intent.projection()
                .configuration()
                .getBoolean("readSiteSettings");
            if (siteRead) {
                var requested = intent.projection()
                    .configuration();
                requested.setInteger("main", intent.main());
                var channels = new NBTTagCompound();
                intent.projection()
                    .channels()
                    .forEach(channels::setInteger);
                requested.setTag("channels", channels);
                site = HologramNativeSitePlan.begin(controller, requested);
            }
        }

        boolean advance(long deadline) throws IOException {
            if (closed) throw new IOException("原生观察请求已关闭。");
            requireCurrent();
            if (result != null) return true;
            if (System.nanoTime() >= deadline) return false;
            var intent = submission.intent;
            if (site != null) {
                if (!site.advance(deadline)) return false;
                siteSettings = retainProvedSettings(controller, site.settings());
                offsets.addAll(site.footprint());
                if (siteSettings.getBoolean("siteDamaged") && cached != null) {
                    // Damage does not erase a previously proved configuration or its restoration boundary.
                    offsets.clear();
                    offsets.addAll(cached.offsets);
                }
                site.close();
                site = null;
            }
            boolean useCachedScope = cached != null && !cached.offsets.isEmpty();
            if (!siteRead && !useCachedScope) {
                if (pending == null) pending = beginFootprint(
                    intent.projection(),
                    intent.main(),
                    ((MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity()).getExtendedFacing());
                if (!pending.advance(deadline)) return false;
                if (!pending.complete()) throw new IOException(pending.failure());
            }
            var cells = !siteRead && !useCachedScope ? pending.cells() : null;
            int size = siteRead ? offsets.size() : !useCachedScope ? cells.size() : cached.offsets.size();
            while (cursor < size && System.nanoTime() < deadline) {
                var offset = siteRead ? offsets.get(cursor++)
                    : !useCachedScope ? offset(cells.get(cursor++)) : cached.offsets.get(cursor++);
                if (!siteRead && !useCachedScope) offsets.add(offset);
                if (offset.x() == 0 && offset.y() == 0 && offset.z() == 0) continue;
                int x = controller.xCoord + offset.x(), y = controller.yCoord + offset.y(),
                    z = controller.zCoord + offset.z();
                if (!submission.endpoint.world.blockExists(x, y, z)) throw new IOException("当前结构范围包含未加载区块。");
                occupied |= submission.endpoint.world.getBlock(x, y, z) != Blocks.air
                    || submission.endpoint.world.getTileEntity(x, y, z) != null;
            }
            if (cursor < size || System.nanoTime() >= deadline) return false;
            requireCurrent();
            var machine = (MTEEnhancedMultiBlockBase<?>) controller.getMetaTileEntity();
            checkNativeStructure(machine, controller);
            requireCurrent();
            var stage = machine.mMachine ? Stage.FORMED : occupied ? Stage.UNFORMED : Stage.CONTROLLER_ONLY;
            if (siteRead) rememberSite(controller, offsets, siteSettings);
            // A state-only observation may read a requested target, but cannot turn it into old removal scope.
            result = new ServerObservation(
                intent.projection()
                    .controller(),
                intent.x(),
                intent.y(),
                intent.z(),
                stage,
                intent.projection()
                    .targetRevision(),
                "",
                siteSettings);
            if (pending != null) {
                pending.close();
                pending = null;
            }
            if (site != null) {
                site.close();
                site = null;
            }
            return true;
        }

        private void requireCurrent() throws IOException {
            if (closed || !submission.current()) throw new IOException("原生观察请求已变化。");
            var intent = submission.intent;
            if (controller(
                submission.endpoint,
                intent.projection(),
                intent.x(),
                intent.y(),
                intent.z(),
                intent.facing()) != controller) throw new IOException("原生观察期间控制器或访问权限已变化。");
        }

        ServerObservation result() {
            return result;
        }

        public void close() {
            if (closed) return;
            closed = true;
            if (pending != null) {
                pending.close();
                pending = null;
            }
            if (site != null) {
                site.close();
                site = null;
            }
            // Successful observation transfers this compact list to the baseline.
            if (result == null) offsets.clear();
        }
    }

    private static HologramNativeTargetPlan beginFootprint(Projection intent, int main, ExtendedFacing facing)
        throws IOException {
        var channels = new NBTTagCompound();
        intent.channels()
            .forEach(channels::setInteger);
        var config = new NBTTagCompound();
        config.setBoolean("footprintOnly", true);
        return HologramNativeTargetPlan
            .begin(intent.controller(), facing, HologramNativeTargetPlan.canonicalTrigger(main, channels), config);
    }

    private static HologramNativeTargetPlan beginTarget(Projection intent, int main, ExtendedFacing facing,
        NBTTagCompound site) throws IOException {
        var channels = new NBTTagCompound();
        intent.channels()
            .forEach(channels::setInteger);
        var trigger = HologramNativeTargetPlan.canonicalTrigger(main, channels);
        var config = intent.configuration();
        config.setInteger("main", main);
        config.setTag("channels", channels.copy());
        var choices = new NBTTagList();
        for (var choice : intent.hatches()) {
            var row = new NBTTagCompound();
            row.setInteger("x", choice.dx());
            row.setInteger("y", choice.dy());
            row.setInteger("z", choice.dz());
            row.setInteger("id", choice.id());
            row.setInteger("facing", choice.facing());
            choices.appendTag(row);
        }
        config.setTag("nativeHatchChoices", choices);
        return HologramNativeTargetPlan
            .begin(intent.controller(), facing, trigger, HologramNativeTargetPlan.retainSiteChoices(config, site));
    }

    private static BaseMetaTileEntity controller(HologramWorkspace.ActionEndpoint endpoint, Projection intent, int x,
        int y, int z, int facing) throws IOException {
        if (!HologramWorkspace.currentActionEndpoint(endpoint) || !endpoint.world.blockExists(x, y, z)
            || endpoint.world.getBlock(x, y, z) != GregTechAPI.sBlockMachines
            || !(endpoint.world.getTileEntity(x, y, z) instanceof BaseMetaTileEntity tile)
            || tile.isInvalid()
            || tile.getMetaTileID() != intent.controller()
            || !(tile.getMetaTileEntity() instanceof MTEEnhancedMultiBlockBase<?>machine)
            || machine.getExtendedFacing()
                .ordinal() != facing)
            throw new IOException("原生控制器、位置或朝向已变化。");
        if (!controllerAccessAllowed(endpoint.player, tile, machine))
            throw new IOException("你没有访问此控制器的权限，请由机主解除锁定或授权后重试。");
        if (endpoint.world.getTileEntity(x, y, z) != tile || tile.isInvalid()
            || tile.getMetaTileEntity() != machine
            || tile.getMetaTileID() != intent.controller()
            || machine.getExtendedFacing()
                .ordinal() != facing)
            throw new IOException("权限核对期间控制器已变化，请重新读取。");
        return tile;
    }

    /** The same native access rules used by the preset quote, independent of operator/creative privileges. */
    static boolean controllerAccessAllowed(EntityPlayerMP player, BaseMetaTileEntity tile,
        MTEEnhancedMultiBlockBase<?> machine) {
        if (player == null || player.isDead
            || player.capabilities == null
            || !player.capabilities.allowEdit
            || tile == null
            || tile.isInvalid()
            || machine == null
            || tile.getMetaTileEntity() != machine
            || machine.getBaseMetaTileEntity() != tile
            || !machine.isAccessAllowed(player)) return false;
        if (tile.privateAccess()) {
            var actor = player.getUniqueID();
            boolean ownerId = actor != null && actor.equals(tile.getOwnerUuid());
            boolean ownerName = player.getCommandSenderName() != null && player.getCommandSenderName()
                .equals(tile.getOwnerName());
            if (!ownerId && !ownerName) return false;
        }
        return !tile.isInvalid() && tile.getMetaTileEntity() == machine && machine.getBaseMetaTileEntity() == tile;
    }

    private static synchronized Baseline baseline(BaseMetaTileEntity controller,
        List<HologramNativeTargetPlan.Offset> offsets) {
        BASELINES.entrySet()
            .removeIf(
                entry -> entry.getKey()
                    .isInvalid());
        var old = cachedBaseline(controller);
        if (old != null) return old;
        int retained = BASELINES.values()
            .stream()
            .mapToInt(saved -> saved.offsets.size())
            .sum();
        while (!BASELINES.isEmpty() && (BASELINES.size() >= 128 || retained + offsets.size() > BASELINE_CELLS)) {
            var entry = BASELINES.entrySet()
                .iterator()
                .next();
            retained -= entry.getValue().offsets.size();
            BASELINES.remove(entry.getKey());
        }
        var saved = new Baseline(controller, offsets);
        if (offsets.size() <= BASELINE_CELLS) BASELINES.put(controller, saved);
        return saved;
    }

    private static synchronized Baseline cachedBaseline(BaseMetaTileEntity controller) {
        var saved = BASELINES.get(controller);
        if (saved == null) return null;
        if (controller.isInvalid() || !saved.matches(controller)) {
            BASELINES.remove(controller);
            return null;
        }
        return saved;
    }

    static synchronized void rememberSite(BaseMetaTileEntity controller, List<HologramNativeTargetPlan.Offset> offsets,
        NBTTagCompound settings) {
        Baseline old = cachedBaseline(controller);
        java.util.Set<HologramNativeTargetPlan.Offset> history = new java.util.LinkedHashSet<>();
        if (old != null) history.addAll(old.offsets);
        if (settings.getBoolean("siteKnown")) history.addAll(offsets);
        BASELINES.remove(controller);
        Baseline saved = baseline(controller, new ArrayList<>(history));
        saved.siteSettings = (NBTTagCompound) settings.copy();
    }

    static synchronized NBTTagCompound retainProvedSettings(BaseMetaTileEntity controller, NBTTagCompound observed)
        throws IOException {
        Baseline cached = cachedBaseline(controller);
        if (observed.getBoolean("siteKnown") || cached == null || !cached.siteSettings.getBoolean("siteKnown"))
            return observed;
        if (!HologramNativeConfiguration
            .matches(controller, null, cached.siteSettings.getCompoundTag("siteControllerConfiguration")))
            return observed;
        NBTTagCompound retained = (NBTTagCompound) cached.siteSettings.copy();
        retained.setBoolean("siteDamaged", true);
        retained.setInteger("siteUnknown", 0);
        for (String key : new String[] {
            "siteNativeCalls", "siteUniqueCells", "siteCellReads", "siteCandidateFailures" })
            if (observed.hasKey(key)) retained.setTag(
                key,
                observed.getTag(key)
                    .copy());
        return retained;
    }

    static synchronized NBTTagCompound provedSettings(BaseMetaTileEntity controller) throws IOException {
        Baseline cached = cachedBaseline(controller);
        if (cached == null || !cached.siteSettings.getBoolean("siteKnown")) return null;
        NBTTagCompound configuration = cached.siteSettings.getCompoundTag("siteControllerConfiguration");
        if (!HologramNativeConfiguration.matches(controller, null, configuration)) return null;
        return (NBTTagCompound) cached.siteSettings.copy();
    }

    static synchronized List<HologramNativeTargetPlan.Offset> provedHistory(BaseMetaTileEntity controller) {
        Baseline cached = cachedBaseline(controller);
        return cached == null ? java.util.Collections.emptyList() : cached.offsets;
    }

    static synchronized int provedTargetSize(BaseMetaTileEntity controller) {
        Baseline cached = cachedBaseline(controller);
        if (cached == null) return 0;
        if (!cached.siteSettings.getBoolean("siteKnown")) return cached.offsets.size();
        int cells = cached.siteSettings.getInteger("siteFootprintCells");
        return cells > 0 ? cells : cached.offsets.size();
    }

    static synchronized boolean portableConfigurationChanged(BaseMetaTileEntity controller) throws IOException {
        Baseline cached = cachedBaseline(controller);
        return cached != null && cached.siteSettings.hasKey("siteControllerConfiguration", 10)
            && !HologramNativeConfiguration
                .matches(controller, null, cached.siteSettings.getCompoundTag("siteControllerConfiguration"));
    }

    static synchronized void rememberConstructedTarget(BaseMetaTileEntity controller,
        List<HologramNativeTargetPlan.Offset> target, NBTTagCompound settings, boolean synchronizesHistory) {
        Baseline history = cachedBaseline(controller);
        java.util.Set<HologramNativeTargetPlan.Offset> restored = new java.util.LinkedHashSet<>(target);
        if (history != null && !synchronizesHistory) restored.addAll(history.offsets);
        BASELINES.remove(controller);
        baseline(controller, new ArrayList<>(restored)).siteSettings = settings == null ? new NBTTagCompound()
            : (NBTTagCompound) settings.copy();
    }

    /** Only the native callback is timed; a slow third-party check cannot be preempted safely. */
    private static void checkNativeStructure(MTEEnhancedMultiBlockBase<?> machine, BaseMetaTileEntity controller) {
        long started = System.nanoTime();
        try {
            machine.checkStructure(true, controller);
        } finally {
            long elapsed = System.nanoTime() - started;
            if (elapsed >= 50_000_000L) com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info(
                "Native structure check boundary controller={} at={},{},{} elapsedMs={}",
                controller.getMetaTileID(),
                controller.xCoord,
                controller.yCoord,
                controller.zCoord,
                elapsed / 1_000_000L);
        }
    }

    private static boolean allows(Operation operation, Stage stage) {
        return operation == Operation.BUILD ? stage == Stage.CONTROLLER_ONLY
            : operation == Operation.REPAIR ? stage == Stage.UNFORMED
                : operation == Operation.REPLACE ? stage == Stage.FORMED
                    : stage == Stage.UNFORMED || stage == Stage.FORMED;
    }

    private boolean executionCurrent() {
        return submission.current();
    }

    private void requirePortableConfiguration() throws IOException {
        if (portableConfigurationChanged(controller)) throw new IOException("现场控制器的原生便携配置已变化，请重新读取后确认。");
    }

    private boolean portableConfigurationCurrent() {
        try {
            return !portableConfigurationChanged(controller);
        } catch (IOException invalid) {
            return false;
        }
    }

    public boolean current() {
        if (anchorRemoved && intent.operation() == Operation.DEMOLISH) return executionCurrent();
        return executionCurrent() && !controller.isInvalid()
            && portableConfigurationCurrent()
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == controller
            && endpoint.world.getBlock(session.x, session.y, session.z) == GregTechAPI.sBlockMachines
            && controller.getMetaTileID() == intent.controller()
            && controller.getMetaTileEntity() == machine
            && machine.getExtendedFacing() == facing
            && controllerAccessAllowed(endpoint.player, controller, machine)
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == controller
            && controller.getMetaTileEntity() == machine
            && controller.getMetaTileID() == intent.controller()
            && machine.getExtendedFacing() == facing
            && endpoint.player.worldObj == endpoint.world
            && endpoint.player.dimension == session.dimension
            && submission.contextRefusal() == null;
    }

    /** Used only after the successful native removal of the final whole-structure controller. */
    private boolean removalCurrent(boolean anchor) {
        return current() || anchor && intent.operation() == Operation.DEMOLISH
            && next >= work.size()
            && executionCurrent()
            && endpoint.world.blockExists(session.x, session.y, session.z)
            && endpoint.world.getBlock(session.x, session.y, session.z) == Blocks.air
            && endpoint.world.getTileEntity(session.x, session.y, session.z) == null;
    }

    public HologramSession session() {
        return session;
    }

    public Operation operation() {
        return intent.operation();
    }

    public int destination() {
        return intent.destination();
    }

    public HologramMaterials.Context recoveryContext() {
        return recovery;
    }

    public long startedAt() {
        return started;
    }

    public long total() {
        return preparedTotal;
    }

    public boolean exhausted() {
        return next >= total();
    }

    public boolean removalCurrent(HologramConstructionJobs.Target cell) {
        return removalCurrent(cell.x == session.x && cell.y == session.y && cell.z == session.z);
    }

    public boolean prepare(long deadline) throws IOException {
        if (!initialized && !initialize(deadline)) return false;
        while (fundCursor < work.size() && System.nanoTime() < deadline) {
            if (!current()) throw new IOException("施工上下文已变化。");
            var cell = work.get(fundCursor++);
            int x = session.x + cell.dx(), y = session.y + cell.dy(), z = session.z + cell.dz();
            if (!endpoint.world.blockExists(x, y, z)) throw new IOException("施工范围包含未加载区块。");
            if (intent.operation() == Operation.DEMOLISH || cell.requiredAir()
                || cell.material() == null
                || HologramNativeConstruction.matches(session, placements.get(cell), x, y, z)) continue;
            ItemStack item = cell.material();
            var id = HologramValues.stackKey(item);
            var aggregate = funding.get(id);
            if (aggregate == null) {
                aggregate = item.copy();
                aggregate.stackSize = 0;
                funding.put(id, aggregate);
            }
            aggregate.stackSize = Math.addExact(aggregate.stackSize, 1);
        }
        if (fundCursor < work.size()) return false;
        if (fundRows == null) fundRows = funding.values()
            .iterator();
        while (fundRows.hasNext() && System.nanoTime() < deadline) {
            var item = fundRows.next();
            if (!creative && stock.count(item, item.stackSize) < item.stackSize)
                throw new IOException("所选材料来源不足，未开始修改结构。");
            if (!current()) throw new IOException("施工上下文已变化。");
        }
        if (fundRows.hasNext()) return false;
        if (!creative && intent.operation() != Operation.BUILD)
            recovery.requireDemolitionDestination(intent.destination());
        return true;
    }

    public HologramConstructionJobs.Target next(long deadline) throws IOException {
        if (next >= work.size()) {
            if (intent.operation() != Operation.DEMOLISH || next++ > work.size()) return null;
            return new HologramConstructionJobs.Target(session.x, session.y, session.z, null, true, true);
        }
        var cell = work.get(next++);
        var prepared = cell.requiredAir() || intent.operation() == Operation.DEMOLISH ? null : placements.get(cell);
        boolean controllerCell = prepared != null
            && prepared.sample.getItem() instanceof gregtech.common.blocks.ItemMachines
            && gregtech.common.blocks.ItemMachines
                .getMetaTileEntity(prepared.sample) instanceof MTEEnhancedMultiBlockBase<?>;
        return new HologramConstructionJobs.Target(
            session.x + cell.dx(),
            session.y + cell.dy(),
            session.z + cell.dz(),
            prepared,
            cell.requiredAir(),
            controllerCell);
    }

    public void committed(HologramConstructionJobs.Target cell, boolean changed) {
        if (intent.operation() == Operation.DEMOLISH && cell.x == session.x
            && cell.y == session.y
            && cell.z == session.z) anchorRemoved = true;
    }

    public void finished(HologramConstructionJobs.Job job) {
        try {
            if (scopeFallback) job.reason = job.reason.isEmpty() || "施工完成。".equals(job.reason) ? LIMITED_SCOPE_DETAIL
                : job.reason + " " + LIMITED_SCOPE_DETAIL;
            if (job.end != HologramConstructionJobs.End.COMPLETE) return;
            if (intent.operation() != Operation.DEMOLISH && !controller.isInvalid())
                checkNativeStructure(machine, controller);
            synchronized (HologramNativeProjectionJob.class) {
                if (intent.operation() != Operation.DEMOLISH && !controller.isInvalid()) {
                    rememberConstructedTarget(controller, targetOffsets, completedSettings, synchronizesHistory);
                } else BASELINES.remove(controller);
            }
        } finally {
            if (target != null) {
                target.close();
                target = null;
            }
            work = java.util.Collections.emptyList();
            union.clear();
            unionRows = null;
            baselineOffsets = null;
            operationOffsets = null;
            previous = null;
            targetOffsets = java.util.Collections.emptyList();
            placements = null;
            funding.clear();
            fundRows = null;
        }
    }
}
