// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/** A native projector run on an owned controller/world. It never reads or edits the player's world. */
public final class HologramNativeTargetPlan implements AutoCloseable {

    private static final java.lang.reflect.Field NBT_ROWS = cpw.mods.fml.relauncher.ReflectionHelper
        .findField(NBTTagList.class, "tagList", "field_74747_a");

    @Desugar
    public record Offset(int x, int y, int z) {}

    @Desugar
    public record Cell(int dx, int dy, int dz, Block block, int metadata, ItemStack material, int facing,
        NBTTagCompound display, NBTTagCompound tileData, IStructureElement<?> element, ItemStack trigger,
        boolean requiredAir) {}

    private BaseMetaTileEntity controller;
    private Object context;
    private HologramNativePreviewWorld world;
    private final ExtendedFacing facing;
    private final ItemStack trigger;
    private HologramNativeStructurePreview nativeRun;
    private final List<Cell> cells = new ArrayList<>();
    private HologramChannelTrace.Report channels;
    private IStructureDefinition<?> definition;
    private String failure = "";
    private String targetDigest;
    private final NBTTagCompound configuration;
    private HologramNativePreviewItems validation;
    private final java.util.Map<Offset, NBTTagCompound> siteChoices = new java.util.HashMap<>();
    private int phase;
    private boolean closed;
    private java.util.Iterator<HologramNativePreviewWorld.Placement> placementCursor;
    private java.util.Iterator<HologramProjectorSimulation.Position> visitedCursor;
    private final java.util.NavigableMap<Offset, Cell> digestCells = new java.util.TreeMap<>(
        java.util.Comparator.comparingInt(Offset::x)
            .thenComparingInt(Offset::y)
            .thenComparingInt(Offset::z));
    private java.util.Iterator<Cell> digestCursor;
    private java.security.MessageDigest digest;
    private int siteBlockCursor;
    private java.util.Iterator<Cell> frozenCursor;
    private final int controllerId;
    private List<Cell> inheritedSite = Collections.emptyList();
    private NBTTagCompound inheritedSettings;
    private java.util.Iterator<Cell> inheritedCursor;
    private final java.util.Set<String> editedSiteChannels = new java.util.HashSet<>();
    private final java.util.IdentityHashMap<IStructureElement<?>, Boolean> editedSiteDomains = new java.util.IdentityHashMap<>();
    private boolean editedSiteMain;
    private final java.util.Map<Offset, Cell> retainedHatches = new java.util.HashMap<>();
    private final java.util.Map<Offset, Cell> retainedChoiceCells = new java.util.HashMap<>();
    private final java.util.Map<Offset, Cell> editedHatches = new java.util.HashMap<>();
    private java.util.Iterator<NBTTagCompound> hatchEditCursor;
    private HologramProjectorSimulation retainedSimulation;
    private HologramNativeTargetPlan declarationPlan;
    private final java.util.Set<String> retainedMaterialChannels = new java.util.HashSet<>();
    private java.util.Iterator<Cell> materialEditCursor;
    private boolean requireSameSiteShape;
    private final java.util.Set<Offset> remainingSiteShape = new java.util.HashSet<>();

    private HologramNativeTargetPlan(int id, ExtendedFacing orientation, ItemStack signal, NBTTagCompound config)
        throws IOException {
        controllerId = id;
        if (signal == null || signal.getItem() == null
            || orientation == null
            || id < 0
            || id >= GregTechAPI.METATILEENTITIES.length
            || GregTechAPI.METATILEENTITIES[id] == null) throw new IOException("原生控制器或投影参数无效。");
        configuration = (NBTTagCompound) config.copy();
        NBTTagList choices = configuration.getTagList("nativeHatchChoices", 10);
        for (int index = 0; index < choices.tagCount(); index++) {
            NBTTagCompound row = choices.getCompoundTagAt(index);
            siteChoices.put(new Offset(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")), row);
        }
        validation = new HologramNativePreviewItems(configuration, Collections.emptySet(), Collections::emptyMap);
        facing = orientation;
        NBTTagCompound channelValues = new NBTTagCompound();
        ChannelDataAccessor.iterateChannelData(signal)
            .forEach(entry -> channelValues.setInteger(entry.getKey(), entry.getValue()));
        trigger = canonicalTrigger(signal.stackSize, channelValues);
        controller = freshController(id, facing, configuration.getCompoundTag("siteControllerConfiguration"));
        context = controller.getMetaTileEntity();
        if (context == null || context == GregTechAPI.METATILEENTITIES[id]) throw new IOException("控制器未提供独立的原生投影实例。");
        IConstructable constructable = HologramStructureDiscovery.localConstructable(controller, context, facing);
        if (constructable == null) throw new IOException("控制器未提供原生投影流程。");
        if (context instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>aligned)
            aligned.setExtendedFacing(facing);
        if (context instanceof MTEMultiBlockBase machine) machine.mMachine = false;
        nativeRun = new HologramNativeStructurePreview(
            controller,
            context,
            constructable,
            false,
            trigger,
            () -> freshController(id, facing, configuration.getCompoundTag("siteControllerConfiguration")),
            facing,
            true);
        nativeRun.configure(config);
        world = nativeRun.targetWorld();
    }

    /** Server-owned, already proved actual cells. This path never constructs an empty machine. */
    private HologramNativeTargetPlan(int id, ExtendedFacing orientation, List<Cell> actual, NBTTagCompound config) {
        controllerId = id;
        facing = orientation;
        configuration = (NBTTagCompound) config.copy();
        trigger = canonicalTrigger(Math.max(1, config.getInteger("main")), config.getCompoundTag("channels"));
        validation = null;
        frozenCursor = actual.iterator();
        phase = 7;
    }

    static HologramNativeTargetPlan fromSite(int id, ExtendedFacing facing, List<Cell> cells,
        NBTTagCompound configuration) {
        return new HologramNativeTargetPlan(id, facing, cells, configuration);
    }

    static BaseMetaTileEntity freshController(int id, ExtendedFacing facing) throws IOException {
        return freshController(id, facing, null);
    }

    static BaseMetaTileEntity freshController(int id, ExtendedFacing facing, NBTTagCompound portable)
        throws IOException {
        HologramResourceWorld initial = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        BaseMetaTileEntity fresh = new BaseMetaTileEntity();
        fresh.setWorldObj(initial);
        fresh.yCoord = 128;
        initial.tile = fresh;
        fresh.setInitialValuesAsNBT(null, (short) id);
        Object context = fresh.getMetaTileEntity();
        if (context == null || context == GregTechAPI.METATILEENTITIES[id]) throw new IOException("原生控制器未提供独立实例。");
        if (context instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>aligned)
            aligned.setExtendedFacing(facing);
        if (portable != null && !portable.hasNoTags()) HologramNativeConfiguration.apply(fresh, null, portable);
        if (context instanceof MTEMultiBlockBase machine) machine.mMachine = false;
        return fresh;
    }

    /** Config is copied. A configured target is replayed from empty space, independently of live formation state. */
    public static HologramNativeTargetPlan compile(int controllerId, ExtendedFacing facing, ItemStack trigger,
        NBTTagCompound config) throws IOException {
        HologramNativeTargetPlan plan = begin(controllerId, facing, trigger, config);
        try {
            while (!plan.advance(Long.MAX_VALUE)) {}
            return plan;
        } catch (IOException | RuntimeException | LinkageError failure) {
            plan.close();
            throw failure;
        }
    }

    public static ItemStack canonicalTrigger(int main, NBTTagCompound channels) {
        net.minecraft.item.Item item = StructureLibAPI.getDefaultHologramItem();
        // The offline contract harness does not run mod item registration. Production uses StructureLib's item.
        ItemStack result = new ItemStack(item == null ? net.minecraft.init.Items.feather : item, Math.max(1, main));
        for (Object entry : channels.func_150296_c()) {
            String name = (String) entry;
            if (channels.hasKey(name, 3) && channels.getInteger(name) > 0)
                ChannelDataAccessor.setChannelData(result, name, channels.getInteger(name));
        }
        return result;
    }

    /** Shared client/server merge. Only requested positions/models inherit the trusted site payload. */
    public static NBTTagCompound retainSiteChoices(NBTTagCompound targetSettings, NBTTagCompound siteSettings) {
        NBTTagCompound result = (NBTTagCompound) targetSettings.copy();
        NBTTagList requested = result.getTagList("nativeHatchChoices", 10);
        NBTTagList installed = siteSettings == null ? new NBTTagList()
            : siteSettings.getTagList("nativeHatchChoices", 10);
        boolean sameParameters = siteSettings != null && result.getInteger("main") == siteSettings.getInteger("main")
            && result.getCompoundTag("channels")
                .equals(siteSettings.getCompoundTag("channels"));
        for (int index = 0; index < requested.tagCount(); index++) {
            NBTTagCompound row = requested.getCompoundTagAt(index);
            row.removeTag("item");
            row.removeTag("display");
            row.removeTag("siteNativeAccepted");
            for (int siteIndex = 0; siteIndex < installed.tagCount(); siteIndex++) {
                NBTTagCompound saved = installed.getCompoundTagAt(siteIndex);
                if (saved.getInteger("x") != row.getInteger("x") || saved.getInteger("y") != row.getInteger("y")
                    || saved.getInteger("z") != row.getInteger("z")
                    || saved.getInteger("id") != row.getInteger("id")) continue;
                if (saved.hasKey("item", 10)) row.setTag(
                    "item",
                    saved.getCompoundTag("item")
                        .copy());
                if (sameParameters && saved.hasKey("display", 10)) row.setTag(
                    "display",
                    saved.getCompoundTag("display")
                        .copy());
                if (saved.getBoolean("siteNativeAccepted")) row.setBoolean("siteNativeAccepted", true);
                break;
            }
        }
        result.setTag("nativeHatchChoices", requested);
        if (siteSettings != null && siteSettings.hasKey("siteControllerConfiguration", 10)) result.setTag(
            "siteControllerConfiguration",
            siteSettings.getCompoundTag("siteControllerConfiguration")
                .copy());
        // Only the server's proved baseline can supply inherited concrete site blocks. A numeric
        // signal change invalidates unscoped inherited material choices; explicit target wins.
        result.removeTag("siteActualBlocks");
        if (sameParameters && siteSettings.hasKey("siteActualBlocks", 9)) result.setTag(
            "siteActualBlocks",
            siteSettings.getTagList("siteActualBlocks", 10)
                .copy());
        return result;
    }

    /** Removal/observation use the same completed native footprint as target generation. */
    public static HologramNativeTargetPlan compileFootprint(int controllerId, ExtendedFacing facing, ItemStack trigger)
        throws IOException {
        NBTTagCompound config = new NBTTagCompound();
        config.setBoolean("footprintOnly", true);
        return compile(controllerId, facing, trigger, config);
    }

    public static HologramNativeTargetPlan begin(int controllerId, ExtendedFacing facing, ItemStack trigger,
        NBTTagCompound config) throws IOException {
        try {
            return new HologramNativeTargetPlan(
                controllerId,
                facing,
                trigger,
                config == null ? new NBTTagCompound() : config);
        } catch (RuntimeException | LinkageError failure) {
            throw new IOException("原生目标初始化或配置无效。", failure);
        }
    }

    public static HologramNativeTargetPlan begin(int controllerId, ExtendedFacing facing, ItemStack trigger,
        NBTTagCompound config, List<Cell> actual, NBTTagCompound siteSettings) throws IOException {
        if (config == null) config = new NBTTagCompound();
        if (actual != null && !actual.isEmpty()
            && siteSettings != null
            && siteSettings.getBoolean("siteKnown")
            && !siteSettings.getBoolean("siteMainRecovered")
            && config.getInteger("main") == siteSettings.getInteger("main")) {
            if (config.getBoolean("presetActive") != siteSettings.getBoolean("presetActive")
                || !config.getCompoundTag("hatchProfile")
                    .equals(siteSettings.getCompoundTag("hatchProfile"))) {
                HologramNativeTargetPlan plan = begin(controllerId, facing, trigger, config);
                plan.adoptSite(actual, siteSettings);
                plan.requireSameSiteShape = true;
                return plan;
            }
            HologramNativeTargetPlan plan = new HologramNativeTargetPlan(controllerId, facing, actual, config);
            try {
                plan.controller = freshController(
                    controllerId,
                    facing,
                    config.getCompoundTag("siteControllerConfiguration"));
                plan.context = plan.controller.getMetaTileEntity();
                IConstructable constructable = HologramStructureDiscovery
                    .localConstructable(plan.controller, plan.context, facing);
                if (constructable == null) throw new IOException("控制器未提供原生投影流程。");
                plan.retainedSimulation = new HologramProjectorSimulation(
                    plan.controller,
                    plan.context,
                    constructable,
                    plan.trigger,
                    null,
                    facing,
                    true);
                plan.retainedSimulation.configure(config);
                plan.world = plan.retainedSimulation.world;
                plan.controller.setWorldObj(plan.world);
                plan.adoptSite(actual, siteSettings);
                NBTTagList choices = config.getTagList("nativeHatchChoices", 10);
                if (choices.tagCount() > 128) throw new IOException("现场仓室采用项超过限制。");
                for (int i = 0; i < choices.tagCount(); i++) {
                    NBTTagCompound row = choices.getCompoundTagAt(i);
                    plan.siteChoices
                        .put(new Offset(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")), row);
                }
                // A client snapshot deliberately contains no executable elements. Resolve declarations only
                // after the user requests an edit; this ordinary runner never publishes its template cells.
                if (cpw.mods.fml.common.FMLCommonHandler.instance()
                    .getEffectiveSide()
                    .isClient()) {
                    NBTTagCompound declarations = (NBTTagCompound) config.copy();
                    declarations.removeTag("nativeHatchChoices");
                    declarations.removeTag("hatchProfile");
                    declarations.removeTag("siteActualBlocks");
                    declarations.setBoolean("presetActive", false);
                    declarations.setBoolean("rangeOnly", true);
                    plan.declarationPlan = begin(controllerId, facing, trigger, declarations);
                    plan.phase = 10;
                } else plan.phase = 8;
                return plan;
            } catch (IOException | RuntimeException | LinkageError broken) {
                plan.close();
                throw broken;
            }
        }
        HologramNativeTargetPlan plan = begin(controllerId, facing, trigger, config);
        plan.adoptSite(actual, siteSettings);
        return plan;
    }

    private void adoptSite(List<Cell> actual, NBTTagCompound siteSettings) {
        inheritedSite = actual == null ? Collections.emptyList() : actual;
        inheritedSettings = siteSettings == null ? null : (NBTTagCompound) siteSettings.copy();
        if (inheritedSettings == null || !inheritedSettings.getBoolean("siteKnown"))
            inheritedSite = Collections.emptyList();
        inheritedCursor = inheritedSite.iterator();
        if (inheritedSettings != null) {
            editedSiteMain = configuration.getInteger("main") != inheritedSettings.getInteger("main");
            NBTTagCompound before = inheritedSettings.getCompoundTag("channels"),
                after = configuration.getCompoundTag("channels");
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Object name : before.func_150296_c()) names.add((String) name);
            for (Object name : after.func_150296_c()) names.add((String) name);
            for (String name : names)
                if (!java.util.Objects.equals(before.getTag(name), after.getTag(name))) editedSiteChannels.add(name);
        }
    }

    public boolean ready() {
        return phase == 6;
    }

    /** Advances only the current request, on its original logical thread, using the caller's deadline. */
    public boolean advance(long deadline) throws IOException {
        if (ready()) return true;
        if (closed) throw new IOException("原生目标已取消。");
        try {
            while (System.nanoTime() < deadline) {
                switch (phase) {
                    case 10 -> {
                        if (!declarationPlan.advance(deadline)) return false;
                        phase = 8;
                    }
                    case 8 -> {
                        if (inheritedCursor.hasNext()) seedRetainedCell(inheritedCursor.next());
                        else {
                            inheritedCursor = null;
                            if (declarationPlan != null) {
                                declarationPlan.close();
                                declarationPlan = null;
                            }
                            if (!retainedMaterialChannels.containsAll(editedSiteChannels)) {
                                beginGuardedTemplate();
                                continue;
                            }
                            materialEditCursor = inheritedSite.iterator();
                            phase = 11;
                        }
                    }
                    case 11 -> {
                        if (materialEditCursor.hasNext()) editRetainedMaterial(materialEditCursor.next());
                        else {
                            materialEditCursor = null;
                            hatchEditCursor = siteChoices.values()
                                .iterator();
                            phase = 9;
                        }
                    }
                    case 9 -> {
                        if (hatchEditCursor.hasNext()) editRetainedHatch(hatchEditCursor.next());
                        else {
                            hatchEditCursor = null;
                            retainedSimulation.checkStructure();
                            inheritedSite = Collections.emptyList();
                            phase = 7;
                        }
                    }
                    case 7 -> {
                        if (frozenCursor.hasNext()) {
                            Cell cell = frozenCursor.next();
                            if (cell.dx() == 0 && cell.dy() == 0 && cell.dz() == 0) continue;
                            cell = editedHatches.getOrDefault(new Offset(cell.dx(), cell.dy(), cell.dz()), cell);
                            cells.add(cell);
                            digestCells.put(new Offset(cell.dx(), cell.dy(), cell.dz()), cell);
                        } else {
                            frozenCursor = null;
                            phase = 4;
                        }
                    }
                    case 0 -> {
                        if (!nativeRun.advance(deadline)) return false;
                        channels = nativeRun.channels();
                        definition = nativeRun.definition();
                        controller = (BaseMetaTileEntity) nativeRun.targetController();
                        context = nativeRun.targetContext();
                        world = nativeRun.targetWorld();
                        phase++;
                    }
                    case 1 -> {
                        if (!applySiteBlocks(deadline)) return false;
                        if (!configuration.getBoolean("footprintOnly")) nativeRun.checkTargetStructure();
                        placementCursor = nativeRun.placements();
                        phase++;
                    }
                    case 2 -> {
                        if (placementCursor.hasNext()) {
                            var cell = placementCursor.next();
                            snapshot(cell.x(), cell.y(), cell.z());
                        } else {
                            placementCursor = null;
                            visitedCursor = nativeRun.visitedPositions();
                            phase++;
                        }
                    }
                    case 3 -> {
                        if (visitedCursor.hasNext()) {
                            var p = visitedCursor.next();
                            if (!world.hasPlacement(p.x(), p.y(), p.z())) snapshot(p.x(), p.y(), p.z());
                        } else {
                            visitedCursor = null;
                            if (requireSameSiteShape && !remainingSiteShape.isEmpty())
                                throw new IOException("当前主尺寸尚未恢复，新目标会改变现场形状；请先明确主尺寸。");
                            phase++;
                        }
                    }
                    case 4 -> {
                        if (cells.isEmpty()) throw new IOException("原生流程未提供可构建的完整结构足迹。");
                        if (validation != null) validation.requireConfiguredChoices(Collections.emptyList());
                        try {
                            digest = java.security.MessageDigest.getInstance("SHA-256");
                        } catch (java.security.NoSuchAlgorithmException impossible) {
                            throw new IllegalStateException(impossible);
                        }
                        updateDigest(controllerId + ":" + facing.ordinal() + "\n");
                        digestCursor = digestCells.values()
                            .iterator();
                        phase++;
                    }
                    case 5 -> {
                        if (digestCursor.hasNext()) updateDigest(digestCell(digestCursor.next()));
                        else {
                            StringBuilder hex = new StringBuilder();
                            for (byte b : digest.digest())
                                hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
                            targetDigest = hex.toString();
                            digestCursor = null;
                            digestCells.clear();
                            digest = null;
                            if (nativeRun != null) nativeRun.close();
                            phase++;
                            return true;
                        }
                    }
                    default -> throw new IllegalStateException("LOCAL_NATIVE_TARGET_STAGE");
                }
            }
            return false;
        } catch (RuntimeException | LinkageError broken) {
            close();
            throw new IOException("原生投影流程无法生成完整目标。", broken);
        } catch (IOException broken) {
            close();
            throw broken;
        }
    }

    @Override
    public void close() {
        closed = true;
        if (nativeRun != null) nativeRun.close();
        if (retainedSimulation != null) retainedSimulation.close();
        retainedSimulation = null;
        if (declarationPlan != null) declarationPlan.close();
        declarationPlan = null;
        nativeRun = null;
        world = null;
        controller = null;
        context = null;
        placementCursor = null;
        visitedCursor = null;
        digestCursor = null;
        frozenCursor = null;
        inheritedCursor = null;
        inheritedSite = Collections.emptyList();
        inheritedSettings = null;
        editedSiteDomains.clear();
        retainedHatches.clear();
        retainedChoiceCells.clear();
        editedHatches.clear();
        hatchEditCursor = null;
        materialEditCursor = null;
        retainedMaterialChannels.clear();
        remainingSiteShape.clear();
        digestCells.clear();
        digest = null;
    }

    private void updateDigest(String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private boolean applySiteBlocks(long deadline) throws IOException {
        var inherited = configuration.getTagList("siteActualBlocks", 10);
        if (configuration.getBoolean("footprintOnly")) return true;
        boolean recording = world.edit(true);
        boolean updates = gregtech.api.threads.RunnableMachineUpdate.isCurrentThreadEnabled();
        try {
            gregtech.api.threads.RunnableMachineUpdate.setCurrentThreadEnabled(false);
            while (siteBlockCursor < inherited.tagCount() && System.nanoTime() < deadline) {
                var row = inherited.getCompoundTagAt(siteBlockCursor++);
                int x = Math.addExact(controller.xCoord, row.getInteger("x"));
                int y = Math.addExact(controller.yCoord, row.getInteger("y"));
                int z = Math.addExact(controller.zCoord, row.getInteger("z"));
                Block block = Block.getBlockFromName(row.getString("block"));
                int meta = row.getInteger("meta");
                if (block == null || block == Blocks.air
                    || meta < 0
                    || meta > 15
                    || block.hasTileEntity(meta)
                    || world.isAirBlock(x, y, z)
                    || world.getTileEntity(x, y, z) != null
                    || nativeRun.elementAt(x, y, z) == null) throw new IOException("现场方块采用项不属于已证明的普通原生位置。");
                world.setBlock(x, y, z, block, meta, 0);
                @SuppressWarnings("unchecked")
                IStructureElement<Object> element = (IStructureElement<Object>) nativeRun.elementAt(x, y, z);
                if (!element.check(context, world, x, y, z)) throw new IOException("现场方块未被原生位置接受。");
            }
            if (siteBlockCursor != inherited.tagCount()) return false;
            while (inheritedCursor != null && inheritedCursor.hasNext() && System.nanoTime() < deadline) {
                Cell actual = inheritedCursor.next();
                if (requireSameSiteShape && (actual.dx() != 0 || actual.dy() != 0 || actual.dz() != 0))
                    remainingSiteShape.add(new Offset(actual.dx(), actual.dy(), actual.dz()));
                if (actual.dx() == 0 && actual.dy() == 0 && actual.dz() == 0 || actual.block() == Blocks.air
                    || actual.block()
                        .hasTileEntity(actual.metadata()))
                    continue;
                int x = Math.addExact(controller.xCoord, actual.dx()),
                    y = Math.addExact(controller.yCoord, actual.dy()),
                    z = Math.addExact(controller.zCoord, actual.dz());
                if (world.isAirBlock(x, y, z) || world.getTileEntity(x, y, z) != null) continue;
                IStructureElement<?> element = nativeRun.elementAt(x, y, z);
                if (element != null && editedSiteDomain(element)) continue;
                if (world.getBlock(x, y, z) == actual.block() && world.getBlockMetadata(x, y, z) == actual.metadata())
                    continue;
                if (element == null) throw new IOException("原生声明无法证明未编辑现场材料的位置，已保留原目标。");
                if (actual.material() != null) world.materialSelected(actual.material(), true);
                world.setBlock(x, y, z, actual.block(), actual.metadata(), 0);
                if (actual.material() != null) world.materialSelected(actual.material(), false);
                @SuppressWarnings("unchecked")
                IStructureElement<Object> checked = (IStructureElement<Object>) element;
                if (!checked.check(context, world, x, y, z)) throw new IOException("未编辑的现场材料未被新原生位置接受。");
            }
            if (inheritedCursor != null && inheritedCursor.hasNext()) return false;
            inheritedCursor = null;
            inheritedSite = Collections.emptyList();
            return true;
        } finally {
            gregtech.api.threads.RunnableMachineUpdate.setCurrentThreadEnabled(updates);
            world.edit(recording);
        }
    }

    private boolean editedSiteDomain(IStructureElement<?> element) {
        Boolean cached = editedSiteDomains.get(element);
        if (cached != null) return cached;
        java.util.Collection<String> channels = element.getChannels();
        boolean edited = false;
        if (channels != null) for (String channel : channels) {
            if (editedSiteChannels.contains(channel) || editedSiteMain && !configuration.getCompoundTag("channels")
                .hasKey(channel, 3)) {
                edited = true;
                break;
            }
        }
        if (editedSiteMain && (channels == null || channels.isEmpty())) edited = true;
        editedSiteDomains.put(element, edited);
        return edited;
    }

    /** Explicit edits may use one ordinary native generation, but an unknown main must not shrink its site. */
    private void beginGuardedTemplate() throws IOException {
        HologramNativeTargetPlan generated = begin(controllerId, facing, trigger, configuration);
        retainedSimulation.close();
        retainedSimulation = null;
        controller = generated.controller;
        context = generated.context;
        world = generated.world;
        nativeRun = generated.nativeRun;
        validation = generated.validation;
        generated.nativeRun = null;
        inheritedCursor = inheritedSite.iterator();
        frozenCursor = null;
        retainedHatches.clear();
        retainedChoiceCells.clear();
        editedHatches.clear();
        retainedMaterialChannels.clear();
        requireSameSiteShape = true;
        phase = 0;
    }

    /** The proved footprint is seeded once, within advance's existing per-cell deadline. */
    private void seedRetainedCell(Cell cell) throws IOException {
        if (cell.dx() == 0 && cell.dy() == 0 && cell.dz() == 0 || cell.block() == Blocks.air) return;
        int x = Math.addExact(controller.xCoord, cell.dx()), y = Math.addExact(controller.yCoord, cell.dy()),
            z = Math.addExact(controller.zCoord, cell.dz());
        if (cell.material() != null) world.materialSelected(cell.material(), true);
        try {
            world.setBlock(x, y, z, cell.block(), cell.metadata(), 0);
        } finally {
            if (cell.material() != null) world.materialSelected(cell.material(), false);
        }
        IStructureElement<?> element = cell.element();
        if (element == null && declarationPlan != null && declarationPlan.nativeRun != null)
            element = declarationPlan.nativeRun.elementAt(x, y, z);
        if (element != null) {
            var p = new HologramProjectorSimulation.Position(x, y, z);
            retainedSimulation.elements.put(p, element);
            retainedSimulation.signals.put(p, trigger);
            if (element.getChannels() != null) retainedMaterialChannels.addAll(element.getChannels());
        }
        Offset at = new Offset(cell.dx(), cell.dy(), cell.dz());
        if (HologramCellPolicy.hatchTier(cell.material()) == Integer.MAX_VALUE) {
            if (siteChoices.containsKey(at)) retainedChoiceCells.put(at, cell);
            return;
        }
        if (retainedHatches.size() >= 128) throw new IOException("现场仓室超过限制。");
        BaseMetaTileEntity hatch = new BaseMetaTileEntity();
        hatch.setWorldObj(world);
        hatch.xCoord = x;
        hatch.yCoord = y;
        hatch.zCoord = z;
        hatch.setInitialValuesAsNBT(
            null,
            (short) cell.material()
                .getItemDamage());
        hatch.setFrontFacing(net.minecraftforge.common.util.ForgeDirection.getOrientation(cell.facing()));
        if (hatch.getMetaTileEntity() == null || hatch.getFrontFacing()
            .ordinal() != cell.facing()) throw new IOException("现场仓室无法建立独立的原生方向实例。");
        world.setTileEntity(x, y, z, hatch);
        retainedHatches.put(
            new Offset(cell.dx(), cell.dy(), cell.dz()),
            new Cell(
                cell.dx(),
                cell.dy(),
                cell.dz(),
                cell.block(),
                cell.metadata(),
                cell.material(),
                cell.facing(),
                cell.display(),
                cell.tileData(),
                element,
                trigger,
                cell.requiredAir()));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void editRetainedMaterial(Cell previous) throws IOException {
        if (previous.dx() == 0 && previous.dy() == 0 && previous.dz() == 0 || previous.block() == Blocks.air
            || previous.block()
                .hasTileEntity(previous.metadata()))
            return;
        var p = new HologramProjectorSimulation.Position(
            controller.xCoord + previous.dx(),
            controller.yCoord + previous.dy(),
            controller.zCoord + previous.dz());
        IStructureElement element = retainedSimulation.elements.get(p);
        if (element == null || !editedSiteDomain(element)) return;
        var offered = retainedSimulation.probe.query(element, p, trigger);
        if (offered == null || offered.getStacks() == null) throw new IOException("原生材料域没有提供确定的替换材料。");
        ItemStack material = null;
        for (ItemStack stack : offered.getStacks())
            if (stack != null && HologramCellPolicy.hatchTier(stack) == Integer.MAX_VALUE) {
                material = stack;
                break;
            }
        if (material == null || !retainedSimulation.probe.place(material, p)
            || !element.check(context, world, p.x(), p.y(), p.z())) throw new IOException("新材料未被现场原生位置接受。");
        Offset at = new Offset(previous.dx(), previous.dy(), previous.dz());
        editedHatches.put(
            at,
            new Cell(
                previous.dx(),
                previous.dy(),
                previous.dz(),
                world.getBlock(p.x(), p.y(), p.z()),
                world.getBlockMetadata(p.x(), p.y(), p.z()),
                material.copy(),
                -1,
                null,
                null,
                element,
                trigger,
                false));
    }

    @SuppressWarnings("unchecked")
    private void editRetainedHatch(NBTTagCompound row) throws IOException {
        Offset at = new Offset(row.getInteger("x"), row.getInteger("y"), row.getInteger("z"));
        Cell previous = retainedHatches.get(at);
        if (previous == null) previous = retainedChoiceCells.get(at);
        if (previous == null) throw new IOException("所选仓室不属于完整现场足迹。");
        boolean existingHatch = HologramCellPolicy.hatchTier(previous.material()) != Integer.MAX_VALUE;
        boolean changedModel = !existingHatch || previous.material()
            .getItemDamage() != row.getInteger("id");
        int direction = row.hasKey("facing", 3) ? row.getInteger("facing") : previous.facing();
        if (direction < 0) direction = previous.facing();
        if (direction == previous.facing() && !changedModel) return;
        if (direction > 5) throw new IOException("仓室方向无效。");
        int x = controller.xCoord + at.x(), y = controller.yCoord + at.y(), z = controller.zCoord + at.z();
        var p = new HologramProjectorSimulation.Position(x, y, z);
        IStructureElement<?> element = retainedSimulation.elements.get(p);
        if (element == null && changedModel) throw new IOException("此仓室位置缺少新型号的原生声明证明。");
        if (!existingHatch && retainedHatches.size() >= 128) throw new IOException("现场仓室超过限制。");
        ItemStack material = previous.material();
        if (changedModel) {
            material = new ItemStack(GregTechAPI.sBlockMachines, 1, row.getInteger("id"));
            if (HologramCellPolicy.hatchTier(material) == Integer.MAX_VALUE
                || !retainedSimulation.probe.place(material, new HologramProjectorSimulation.Position(x, y, z)))
                throw new IOException("所选仓室型号无法原生放置。");
        }
        BaseMetaTileEntity hatch = (BaseMetaTileEntity) world.getTileEntity(x, y, z);
        if (direction < 0) direction = hatch.getFrontFacing()
            .ordinal();
        hatch.setFrontFacing(net.minecraftforge.common.util.ForgeDirection.getOrientation(direction));
        if (hatch.getFrontFacing()
            .ordinal() != direction) throw new IOException("仓室原生接口拒绝此方向。");
        if (element == null) {
            if (cpw.mods.fml.common.FMLCommonHandler.instance()
                .getEffectiveSide()
                .isServer()) throw new IOException("服务端缺少此仓室的原生位置证明。");
        } else if (!((IStructureElement<Object>) element).check(context, world, x, y, z))
            throw new IOException("仓室新方向未被现场原生位置接受。");
        NBTTagCompound display = changedModel ? HologramDisplaySnapshot.projection(hatch)
            : previous.display() == null ? null
                : (NBTTagCompound) previous.display()
                    .copy();
        if (display != null) display.setInteger("facing", direction);
        NBTTagCompound data = changedModel || previous.tileData() == null ? null
            : (NBTTagCompound) previous.tileData()
                .copy();
        if (data != null && data.hasKey("mFacing")) data.setByte("mFacing", (byte) direction);
        Cell edited = new Cell(
            previous.dx(),
            previous.dy(),
            previous.dz(),
            world.getBlock(x, y, z),
            world.getBlockMetadata(x, y, z),
            material,
            direction,
            display,
            data,
            element,
            previous.trigger(),
            previous.requiredAir());
        editedHatches.put(at, edited);
        retainedHatches.put(at, edited);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void snapshot(int x, int y, int z) throws IOException {
        Offset p = new Offset(x - controller.xCoord, y - controller.yCoord, z - controller.zCoord);
        if (p.x() == 0 && p.y() == 0 && p.z() == 0) return;
        boolean footprintOnly = configuration.getBoolean("footprintOnly");
        Block block = world.getBlock(x, y, z);
        int meta = world.getBlockMetadata(x, y, z);
        if (nativeRun.optionalWaterAt(x, y, z)) return;
        TileEntity tile = world.getTileEntity(x, y, z);
        IStructureElement element = nativeRun.elementAt(x, y, z);
        boolean air = block == Blocks.air && element != null
            && element.check(context, world, x, y, z)
            && HologramElementCatalog.nativeAir(element);
        if (block == Blocks.air && !air) return;
        if (requireSameSiteShape && !remainingSiteShape.remove(p))
            throw new IOException("当前主尺寸尚未恢复，新目标会改变现场形状；请先明确主尺寸。");
        ItemStack item = air ? null : world.materialAt(x, y, z);
        int direction = -1;
        NBTTagCompound display = null, data = null;
        if (tile != null) {
            if (!footprintOnly) {
                data = new NBTTagCompound();
                tile.writeToNBT(data);
                display = HologramDisplaySnapshot.projection(tile);
            }
            if (tile instanceof IGregTechTileEntity gt) {
                if (item == null) item = new ItemStack(block, 1, gt.getMetaTileID());
                direction = gt.getFrontFacing()
                    .ordinal();
            }
        }
        if (!air && item == null) {
            item = HologramBlockIdentity.materialStack(block, meta);
            if (item != null
                && item.getItem() instanceof com.gtnewhorizon.structurelib.structure.ISpecialItemBlock special)
                item.setItemDamage(special.getItemMetaFromBlockMeta(block, meta));
        }
        NBTTagCompound site = siteChoices.get(p);
        if (!footprintOnly && site != null
            && site.hasKey("display", 10)
            && tile instanceof IGregTechTileEntity gt
            && gt.getMetaTileID() == site.getInteger("id")) {
            display = (NBTTagCompound) site.getCompoundTag("display")
                .copy();
            display.setInteger("facing", direction);
            if (data != null) for (String name : new String[] { "texturePage", "textureIndex", "color" })
                if (display.hasKey(name, 3)) data.setInteger(name, display.getInteger(name));
        }
        Cell cell = new Cell(
            p.x(),
            p.y(),
            p.z(),
            block,
            meta,
            item,
            direction,
            display,
            data,
            element,
            nativeRun.triggerAt(x, y, z),
            air);
        if (validation != null) validation.recordConfiguredChoice(cell);
        cells.add(cell);
        digestCells.put(p, cell);
        HologramBlueprintLimits.require(1L + cells.size());
    }

    /** Existing mixin seams delegate into the shared native runner. */
    static void piece(IStructureDefinition<?> definition, Object context, ItemStack trigger, World world) {
        HologramNativeStructurePreview.piece(definition, context, trigger, world);
    }

    static void piece(Object context, ItemStack signal) {
        HologramNativeStructurePreview.piece(context, signal);
    }

    static boolean consumesHint(World world) {
        return HologramNativeStructurePreview.consumesHint(world);
    }

    /** Native placement attempts are evidence for the active read-only site check, never a live-world policy. */
    public static void sitePlacementAttempt(World world) {
        HologramNativeSitePlan.placementAttempt(world);
    }

    static String position(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    public List<Cell> cells() {
        return Collections.unmodifiableList(cells);
    }

    public java.util.Set<Offset> footprint() {
        java.util.Set<Offset> result = new java.util.LinkedHashSet<>();
        for (Cell cell : cells) result.add(new Offset(cell.dx(), cell.dy(), cell.dz()));
        return Collections.unmodifiableSet(result);
    }

    public boolean nativeFormed() {
        return context instanceof MTEMultiBlockBase machine && machine.mMachine;
    }

    public boolean complete() {
        return ready() && failure.isEmpty();
    }

    public String failure() {
        return failure;
    }

    public String targetDigest() {
        return targetDigest;
    }

    NBTTagCompound configuration() {
        return (NBTTagCompound) configuration.copy();
    }

    private String digestCell(Cell cell) {
        StringBuilder value = new StringBuilder();
        value.append(cell.dx())
            .append(',')
            .append(cell.dy())
            .append(',')
            .append(cell.dz())
            .append('|')
            .append(Block.blockRegistry.getNameForObject(cell.block()))
            .append('|')
            .append(cell.metadata())
            .append('|')
            .append(
                cell.material() == null ? "air"
                    : canonical(
                        cell.material()
                            .writeToNBT(new NBTTagCompound())))
            .append('|')
            .append(cell.facing())
            .append('|')
            .append(cell.requiredAir());
        if (cell.display() != null)
            for (String key : new String[] { "texturePage", "textureIndex", "color" }) value.append('|')
                .append(key)
                .append('=')
                .append(
                    cell.display()
                        .getInteger(key));
        value.append('\n');
        return value.toString();
    }

    private static String canonical(net.minecraft.nbt.NBTBase tag) {
        if (tag instanceof NBTTagCompound compound) {
            List<String> keys = new ArrayList<>();
            for (Object key : compound.func_150296_c()) keys.add((String) key);
            Collections.sort(keys);
            StringBuilder value = new StringBuilder("{");
            for (String key : keys) value.append(key.length())
                .append(':')
                .append(key)
                .append('=')
                .append(canonical(compound.getTag(key)))
                .append(';');
            return value.append('}')
                .toString();
        }
        if (tag instanceof NBTTagList list) {
            StringBuilder value = new StringBuilder("[");
            try {
                for (Object entry : (List<?>) NBT_ROWS.get(list))
                    value.append(canonical((net.minecraft.nbt.NBTBase) entry))
                        .append(';');
            } catch (IllegalAccessException inaccessible) {
                throw new IllegalStateException(inaccessible);
            }
            return value.append(']')
                .toString();
        }
        return tag.getId() + ":" + tag;
    }

    IStructureDefinition<?> definition() {
        return definition;
    }

    int facingMask(int dx, int dy, int dz, ItemStack sample) throws IOException {
        if (controller == null || HologramCellPolicy.hatchTier(sample) == Integer.MAX_VALUE) return 0;
        if (nativeRun == null) {
            var p = new HologramProjectorSimulation.Position(
                controller.xCoord + dx,
                controller.yCoord + dy,
                controller.zCoord + dz);
            if (retainedSimulation.elements.containsKey(p)) return retainedSimulation.probe.facingMask(p, sample);
            Cell previous = retainedHatches.get(new Offset(dx, dy, dz));
            if (previous == null || !HologramValues.sameMaterial(previous.material(), sample)) return 0;
            BaseMetaTileEntity hatch = (BaseMetaTileEntity) world
                .getTileEntity(controller.xCoord + dx, controller.yCoord + dy, controller.zCoord + dz);
            int mask = 0;
            for (net.minecraftforge.common.util.ForgeDirection direction : net.minecraftforge.common.util.ForgeDirection.VALID_DIRECTIONS)
                if (hatch.getMetaTileEntity()
                    .isFacingValid(direction)) mask |= 1 << direction.ordinal();
            return mask;
        }
        return nativeRun.facingMask(controller.xCoord + dx, controller.yCoord + dy, controller.zCoord + dz, sample);
    }

    Object context() {
        return context;
    }

    TileEntity controller() {
        return controller;
    }

    HologramChannelTrace.Report channels() {
        return channels;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    List<ItemStack> offers(Cell cell) {
        if (nativeRun == null) {
            var p = new HologramProjectorSimulation.Position(
                controller.xCoord + cell.dx(),
                controller.yCoord + cell.dy(),
                controller.zCoord + cell.dz());
            IStructureElement<?> element = retainedSimulation.elements.get(p);
            if (element != null) return retainedSimulation.probe.offers(element, p, trigger);
            Cell installed = retainedHatches.get(new Offset(cell.dx(), cell.dy(), cell.dz()));
            if (installed == null || installed.material() == null) return Collections.emptyList();
            return Collections.singletonList(
                installed.material()
                    .copy());
        }
        return nativeRun.nativeOffers(
            cell.element(),
            controller.xCoord + cell.dx(),
            controller.yCoord + cell.dy(),
            controller.zCoord + cell.dz(),
            cell.trigger());
    }

    /** Compatibility event seam for detached contracts; production has one shared listener. */
    public static final class Listener {

        @SubscribeEvent
        public void visit(StructureElementVisitedEvent event) {
            new HologramNativeStructurePreview.VisitedListener().elementVisited(event);
        }
    }
}
