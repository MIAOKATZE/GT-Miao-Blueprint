// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
public final class HologramNativeTargetPlan {

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
    private final IConstructable constructable;
    private HologramNativePreviewWorld world;
    private final HologramNativePreviewItems items;
    private final ExtendedFacing facing;
    private final ItemStack trigger;
    private final HologramNativeStructurePreview nativeRun;
    private final List<Cell> cells = new ArrayList<>();
    private HologramNativeTargetPlan candidateBase;
    private HologramChannelTrace.Report channels;
    private IStructureDefinition<?> definition;
    private String failure = "";
    private final String inputDigest;

    private HologramNativeTargetPlan(int id, ExtendedFacing orientation, ItemStack signal, NBTTagCompound config,
        java.util.Set<String> mandatory) throws IOException {
        if (signal == null || signal.getItem() == null
            || orientation == null
            || id < 0
            || id >= GregTechAPI.METATILEENTITIES.length
            || GregTechAPI.METATILEENTITIES[id] == null) throw new IOException("原生控制器或投影参数无效。");
        facing = orientation;
        NBTTagCompound channelValues = new NBTTagCompound();
        ChannelDataAccessor.iterateChannelData(signal)
            .forEach(entry -> channelValues.setInteger(entry.getKey(), entry.getValue()));
        trigger = canonicalTrigger(signal.stackSize, channelValues);
        NBTTagCompound identity = new NBTTagCompound();
        identity.setInteger("controller", id);
        identity.setInteger("facing", orientation.ordinal());
        identity.setTag("trigger", trigger.writeToNBT(new NBTTagCompound()));
        for (String key : new String[] {
            "presetActive", "hatchTier", "hatchDowngrade", "hatchProfile", "nativeHatchChoices" })
            if (config.hasKey(key)) identity.setTag(
                key,
                config.getTag(key)
                    .copy());
        inputDigest = digest(canonical(identity));
        HologramResourceWorld initial = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        controller = new BaseMetaTileEntity();
        controller.setWorldObj(initial);
        controller.yCoord = 128;
        initial.tile = controller;
        controller.setInitialValuesAsNBT(null, (short) id);
        context = controller.getMetaTileEntity();
        if (context == null || context == GregTechAPI.METATILEENTITIES[id]) throw new IOException("控制器未提供独立的原生投影实例。");
        constructable = HologramStructureDiscovery.localConstructable(controller, context, facing);
        if (constructable == null) throw new IOException("控制器未提供原生投影流程。");
        if (context instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>aligned)
            aligned.setExtendedFacing(facing);
        if (context instanceof MTEMultiBlockBase machine) machine.mMachine = false;
        items = new HologramNativePreviewItems(config, mandatory, () -> installedHatches());
        if ((config.getBoolean("presetActive") || config.getTagList("nativeHatchChoices", 10)
            .tagCount() > 0) && !ChannelDataAccessor.hasSubChannel(trigger, "gt_hatch"))
            ChannelDataAccessor.setChannelData(trigger, "gt_hatch", 1);
        nativeRun = new HologramNativeStructurePreview(
            controller,
            context,
            constructable,
            false,
            trigger,
            () -> freshController(id, facing),
            facing,
            items,
            true);
        world = nativeRun.targetWorld();
    }

    private static BaseMetaTileEntity freshController(int id, ExtendedFacing facing) throws IOException {
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
        if (context instanceof MTEMultiBlockBase machine) machine.mMachine = false;
        return fresh;
    }

    /** Config is copied. A configured target is replayed from empty space, independently of live formation state. */
    public static HologramNativeTargetPlan compile(int controllerId, ExtendedFacing facing, ItemStack trigger,
        NBTTagCompound config) throws IOException {
        if (trigger == null || trigger.getItem() == null) throw new IOException("原生投影参数无效。");
        NBTTagCompound owned = config == null ? new NBTTagCompound() : (NBTTagCompound) config.copy();
        java.util.Set<String> mandatory = Collections.emptySet();
        HologramNativeTargetPlan base = null;
        if (owned.getBoolean("presetActive") || owned.getTagList("nativeHatchChoices", 10)
            .tagCount() > 0 || ChannelDataAccessor.hasSubChannel(trigger, "gt_hatch")) {
            NBTTagCompound defaults = (NBTTagCompound) owned.copy();
            defaults.setBoolean("presetActive", false);
            defaults.removeTag("hatchProfile");
            defaults.removeTag("nativeHatchChoices");
            defaults.removeTag("nativeStock");
            ItemStack baselineTrigger = trigger.copy();
            ChannelDataAccessor.unsetChannelData(baselineTrigger, "gt_hatch");
            base = run(controllerId, facing, baselineTrigger, defaults, mandatory);
            mandatory = new java.util.HashSet<>();
            for (Cell cell : base.cells) if (HologramCellPolicy.hatchTier(cell.material()) != Integer.MAX_VALUE)
                mandatory.add(position(cell.dx(), cell.dy(), cell.dz()));
            NBTTagList forced = new NBTTagList();
            for (Cell cell : base.cells) if (HologramCellPolicy.hatchTier(cell.material()) != Integer.MAX_VALUE) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("position", position(cell.dx(), cell.dy(), cell.dz()));
                row.setInteger(
                    "id",
                    cell.material()
                        .getItemDamage());
                forced.appendTag(row);
            }
            owned.setTag("nativeMandatoryDefaults", forced);
        }
        HologramNativeTargetPlan result = run(controllerId, facing, trigger, owned, mandatory);
        result.candidateBase = base;
        return result;
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

    /** Removal/observation use the same completed native footprint as target generation. */
    public static HologramNativeTargetPlan compileFootprint(int controllerId, ExtendedFacing facing, ItemStack trigger)
        throws IOException {
        NBTTagCompound config = new NBTTagCompound();
        config.setBoolean("footprintOnly", true);
        return run(controllerId, facing, trigger, config, Collections.emptySet());
    }

    private static HologramNativeTargetPlan run(int controllerId, ExtendedFacing facing, ItemStack trigger,
        NBTTagCompound config, java.util.Set<String> mandatory) throws IOException {
        try {
            HologramNativeTargetPlan plan = new HologramNativeTargetPlan(
                controllerId,
                facing,
                trigger,
                config,
                mandatory);
            plan.generate(config);
            return plan;
        } catch (RuntimeException | LinkageError invalid) {
            throw new IOException("原生目标初始化或配置无效。", invalid);
        }
    }

    private void generate(NBTTagCompound config) throws IOException {
        try (HologramNativeStructurePreview run = nativeRun) {
            while (!run.advance(Long.MAX_VALUE)) {
                // The shared runner owns the bounded pass count and all native isolation scopes.
            }
            channels = run.channels();
            definition = run.definition();
            controller = (BaseMetaTileEntity) run.targetController();
            context = run.targetContext();
            world = run.targetWorld();
            controller.setWorldObj(world);
            if (!config.getBoolean("footprintOnly") && context instanceof MTEMultiBlockBase machine) {
                boolean formed = run.checkTargetStructure();
                if (config.getBoolean("presetActive") && !formed) throw new IOException("所选仓室配置未通过控制器的原生结构检查。");
            }
            snapshot(run, config.getBoolean("footprintOnly"));
            if (cells.isEmpty()) throw new IOException("原生流程未提供可构建的完整结构足迹。");
            items.requireConfiguredChoices(cells);
        } catch (RuntimeException | LinkageError broken) {
            throw new IOException("原生投影流程无法生成完整目标。", broken);
        }
    }

    private Map<Integer, Integer> installedHatches() {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        if (world == null) return result;
        for (var iterator = world.currentPlacements(); iterator.hasNext();) {
            TileEntity tile = iterator.next()
                .tile();
            if (tile instanceof IGregTechTileEntity gt
                && gt.getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEHatch)
                result.merge(gt.getMetaTileID(), 1, Integer::sum);
        }
        return result;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void snapshot(HologramNativeStructurePreview run, boolean footprintOnly) throws IOException {
        HologramNativePreviewWorld actual = run.targetWorld();
        Map<Offset, HologramNativePreviewWorld.Placement> targets = new LinkedHashMap<>();
        for (var iterator = actual.placements(); iterator.hasNext();) {
            var placement = iterator.next();
            Offset offset = new Offset(
                placement.x() - controller.xCoord,
                placement.y() - controller.yCoord,
                placement.z() - controller.zCoord);
            targets.put(offset, placement);
        }
        // Visited cells are supplemental. Unknown blank space never expands the destruction footprint.
        for (Offset p : run.visitedOffsets()) targets.putIfAbsent(p, null);
        HologramBlueprintLimits.require(1L + targets.size());
        for (var entry : targets.entrySet()) {
            Offset p = entry.getKey();
            if (p.x() == 0 && p.y() == 0 && p.z() == 0) continue;
            int x = controller.xCoord + p.x(), y = controller.yCoord + p.y(), z = controller.zCoord + p.z();
            Block block = actual.getBlock(x, y, z);
            int meta = actual.getBlockMetadata(x, y, z);
            TileEntity tile = actual.getTileEntity(x, y, z);
            IStructureElement element = run.elementAt(x, y, z);
            boolean air = block == Blocks.air && element != null
                && element.check(context, actual, x, y, z)
                && HologramElementCatalog.nativeAir(element);
            if (block == Blocks.air && !air) continue;
            ItemStack item = air ? null : actual.materialAt(x, y, z);
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
            if (!footprintOnly && !air && (item == null || item.getItem() == null))
                throw new IOException("原生目标没有可支付的物品身份：" + p);
            cells.add(
                new Cell(
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
                    run.triggerAt(x, y, z),
                    air));
        }
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
        return failure.isEmpty();
    }

    public String failure() {
        return failure;
    }

    public String inputDigest() {
        return inputDigest;
    }

    public String footprintDigest() {
        List<Offset> sorted = new ArrayList<>(footprint());
        sorted.sort(
            java.util.Comparator.comparingInt(Offset::x)
                .thenComparingInt(Offset::y)
                .thenComparingInt(Offset::z));
        StringBuilder value = new StringBuilder();
        value.append(controller.getMetaTileID())
            .append(':')
            .append(facing.ordinal())
            .append('\n');
        for (Offset p : sorted) value.append(p.x())
            .append(',')
            .append(p.y())
            .append(',')
            .append(p.z())
            .append('\n');
        return digest(value.toString());
    }

    public String targetDigest() {
        List<Cell> sorted = new ArrayList<>(cells);
        sorted.sort(
            java.util.Comparator.comparingInt(Cell::dx)
                .thenComparingInt(Cell::dy)
                .thenComparingInt(Cell::dz));
        StringBuilder value = new StringBuilder();
        value.append(controller.getMetaTileID())
            .append(':')
            .append(facing.ordinal())
            .append('\n');
        for (Cell cell : sorted) {
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
        }
        return digest(value.toString());
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

    private static String digest(String value) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder();
            for (byte b : bytes) text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return text.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    IStructureDefinition<?> definition() {
        return definition;
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
        if (candidateBase != null) {
            for (Cell base : candidateBase.cells)
                if (base.dx() == cell.dx() && base.dy() == cell.dy() && base.dz() == cell.dz())
                    return candidateBase.offers(base);
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
