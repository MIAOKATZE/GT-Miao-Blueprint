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
    private final HologramNativePreviewItems validation;
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

    private HologramNativeTargetPlan(int id, ExtendedFacing orientation, ItemStack signal, NBTTagCompound config)
        throws IOException {
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
                            phase++;
                        }
                    }
                    case 4 -> {
                        if (cells.isEmpty()) throw new IOException("原生流程未提供可构建的完整结构足迹。");
                        validation.requireConfiguredChoices(Collections.emptyList());
                        try {
                            digest = java.security.MessageDigest.getInstance("SHA-256");
                        } catch (java.security.NoSuchAlgorithmException impossible) {
                            throw new IllegalStateException(impossible);
                        }
                        updateDigest(controller.getMetaTileID() + ":" + facing.ordinal() + "\n");
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
                            nativeRun.close();
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
        nativeRun = null;
        world = null;
        controller = null;
        context = null;
        placementCursor = null;
        visitedCursor = null;
        digestCursor = null;
        digestCells.clear();
        digest = null;
    }

    private void updateDigest(String value) {
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        validation.recordConfiguredChoice(cell);
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
        if (nativeRun == null || controller == null || HologramCellPolicy.hatchTier(sample) == Integer.MAX_VALUE)
            return 0;
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
