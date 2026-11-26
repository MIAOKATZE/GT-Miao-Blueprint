// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.stats.StatBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.mojang.authlib.GameProfile;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.api.threads.RunnableMachineUpdate;
import gregtech.common.blocks.ItemMachines;

/** A native projector run on an owned controller/world. It never reads or edits the player's world. */
public final class HologramNativeTargetPlan {

    private static final ThreadLocal<HologramNativeTargetPlan> ACTIVE = new ThreadLocal<>();
    private static boolean listening;
    private static final int MAX_PASSES = 2000;
    private static final java.lang.reflect.Field NBT_ROWS = cpw.mods.fml.relauncher.ReflectionHelper
        .findField(NBTTagList.class, "tagList", "field_74747_a");

    @Desugar
    public record Offset(int x, int y, int z) {}

    @Desugar
    public record Cell(int dx, int dy, int dz, Block block, int metadata, ItemStack material, int facing,
        NBTTagCompound display, NBTTagCompound tileData, IStructureElement<?> element, ItemStack trigger,
        boolean requiredAir) {}

    private final BaseMetaTileEntity controller;
    private final Object context;
    private final IConstructable constructable;
    private final HologramNativePreviewWorld world;
    private final HologramNativePreviewItems items;
    private final ExtendedFacing facing;
    private final ItemStack trigger;
    private final Actor actor;
    private final Map<Offset, Visit> visits = new LinkedHashMap<>();
    private final List<Cell> cells = new ArrayList<>();
    private HologramNativeTargetPlan candidateBase;
    private HologramChannelTrace.Report channels;
    private boolean validating;
    private boolean limitExceeded;
    private ItemStack pieceTrigger;
    private IStructureDefinition<?> definition;
    private String failure = "";
    private final String inputDigest;

    private static final class Visit {

        final IStructureElement<?> element;
        final ItemStack signal;

        Visit(IStructureElement<?> element, ItemStack signal) {
            this.element = element;
            this.signal = signal.copy();
        }
    }

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
        pieceTrigger = trigger;
        HologramResourceWorld initial = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        controller = new BaseMetaTileEntity();
        controller.setWorldObj(initial);
        controller.yCoord = 128;
        initial.tile = controller;
        controller.setInitialValuesAsNBT(null, (short) id);
        context = controller.getMetaTileEntity();
        if (context == null || context == GregTechAPI.METATILEENTITIES[id] || !(context instanceof IConstructable))
            throw new IOException("控制器未提供原生投影流程。");
        constructable = (IConstructable) context;
        definition = constructable.getStructureDefinition();
        if (!config.getBoolean("footprintOnly") && !(constructable instanceof ISurvivalConstructable))
            throw new IOException("控制器未提供原生生存建造流程。");
        if (context instanceof gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase<?>aligned)
            aligned.setExtendedFacing(facing);
        if (context instanceof MTEMultiBlockBase machine) machine.mMachine = false;
        world = HologramNativePreviewWorld.open(controller);
        controller.setWorldObj(world);
        actor = new Actor(world);
        items = new HologramNativePreviewItems(config, mandatory, () -> installedHatches());
        if ((config.getBoolean("presetActive") || config.getTagList("nativeHatchChoices", 10)
            .tagCount() > 0) && !ChannelDataAccessor.hasSubChannel(trigger, "gt_hatch"))
            ChannelDataAccessor.setChannelData(trigger, "gt_hatch", 1);
    }

    /** Config is copied. A configured target is replayed from empty space, independently of live formation state. */
    public static HologramNativeTargetPlan compile(int controllerId, ExtendedFacing facing, ItemStack trigger,
        NBTTagCompound config) throws IOException {
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

    /** The native visited footprint is sufficient for removal and observation; no target material is inferred. */
    public static HologramNativeTargetPlan compileFootprint(int controllerId, ExtendedFacing facing, ItemStack trigger)
        throws IOException {
        NBTTagCompound config = new NBTTagCompound();
        config.setBoolean("footprintOnly", true);
        try {
            HologramNativeTargetPlan plan = new HologramNativeTargetPlan(
                controllerId,
                facing,
                trigger,
                config,
                Collections.emptySet());
            plan.generateFootprint();
            return plan;
        } catch (RuntimeException | LinkageError invalid) {
            throw new IOException("原生流程无法读取当前结构范围。", invalid);
        }
    }

    private static synchronized void listen() {
        if (!listening) {
            MinecraftForge.EVENT_BUS.register(new Listener());
            listening = true;
        }
    }

    private void generateFootprint() throws IOException {
        listen();
        HologramNativeTargetPlan previous = ACTIVE.get();
        boolean instrument = !StructureLibAPI.isInstrumentEnabled();
        HologramChannelTrace.Scope trace = HologramChannelTrace.begin(context);
        try {
            ACTIVE.set(this);
            if (instrument) StructureLibAPI.enableInstrument(this);
            world.isRemote = true;
            constructable.construct(trigger.copy(), true);
            if (limitExceeded) throw new IOException("原生结构范围超过读取上限。");
            for (var entry : visits.entrySet()) {
                Offset p = entry.getKey();
                if (p.x() == 0 && p.y() == 0 && p.z() == 0) continue;
                cells.add(
                    new Cell(
                        p.x(),
                        p.y(),
                        p.z(),
                        Blocks.air,
                        0,
                        null,
                        -1,
                        null,
                        null,
                        entry.getValue().element,
                        entry.getValue().signal.copy(),
                        false));
            }
            if (cells.isEmpty()) throw new IOException("原生投影没有提供结构范围。");
        } finally {
            world.isRemote = false;
            if (instrument) StructureLibAPI.disableInstrument();
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            channels = HologramChannelTrace.end(trace);
        }
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
        listen();
        HologramNativeTargetPlan previous = ACTIVE.get();
        boolean instrument = !StructureLibAPI.isInstrumentEnabled();
        boolean updates = RunnableMachineUpdate.isCurrentThreadEnabled();
        HologramChannelTrace.Scope channelScope = HologramChannelTrace.begin(context);
        try {
            ACTIVE.set(this);
            if (instrument) StructureLibAPI.enableInstrument(this);
            if (updates) RunnableMachineUpdate.setCurrentThreadEnabled(false);
            ISurvivalBuildEnvironment environment = ISurvivalBuildEnvironment.create(items, actor);
            boolean stable = false;
            for (int pass = 0; pass < MAX_PASSES; pass++) {
                world.takeChanged();
                ((ISurvivalConstructable) constructable)
                    .survivalConstruct(trigger.copy(), Integer.MAX_VALUE, environment);
                if (!world.takeChanged()) {
                    stable = true;
                    break;
                }
            }
            if (!stable) throw new IOException("原生目标生成未收敛。");
            // Hints enumerate the whole native footprint, including cells whose survival pass stopped earlier.
            // They are consumed below and never become material/placement authority.
            world.isRemote = true;
            try {
                constructable.construct(trigger.copy(), true);
            } finally {
                world.isRemote = false;
            }
            if (limitExceeded) throw new IOException("原生结构范围超过读取上限。");
            if (context instanceof MTEMultiBlockBase machine) {
                validating = true;
                boolean formed;
                try {
                    formed = machine.checkStructure(true, controller);
                } finally {
                    validating = false;
                }
                if (config.getBoolean("presetActive") && !formed) throw new IOException("所选仓室配置未通过控制器的原生结构检查。");
            }
            validating = true;
            try {
                snapshot();
            } finally {
                validating = false;
            }
            if (cells.isEmpty()) throw new IOException("原生流程未提供可构建的完整结构足迹。");
            items.requireConfiguredChoices(cells);
        } catch (RuntimeException | LinkageError broken) {
            throw new IOException("原生投影流程无法生成完整目标。", broken);
        } finally {
            if (instrument) StructureLibAPI.disableInstrument();
            if (updates != RunnableMachineUpdate.isCurrentThreadEnabled())
                RunnableMachineUpdate.setCurrentThreadEnabled(updates);
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
            channels = HologramChannelTrace.end(channelScope);
        }
    }

    private Map<Integer, Integer> installedHatches() {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (Offset p : visits.keySet()) {
            TileEntity tile = world
                .getTileEntity(controller.xCoord + p.x(), controller.yCoord + p.y(), controller.zCoord + p.z());
            if (tile instanceof IGregTechTileEntity gt
                && gt.getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEHatch)
                result.merge(gt.getMetaTileID(), 1, Integer::sum);
        }
        return result;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void snapshot() throws IOException {
        for (var iterator = world.placements(); iterator.hasNext();) {
            var placement = iterator.next();
            Offset p = new Offset(
                placement.x() - controller.xCoord,
                placement.y() - controller.yCoord,
                placement.z() - controller.zCoord);
            if (!visits.containsKey(p)) throw new IOException("原生流程在未声明位置生成了目标。");
        }
        for (Map.Entry<Offset, Visit> entry : visits.entrySet()) {
            Offset p = entry.getKey();
            if (p.x() == 0 && p.y() == 0 && p.z() == 0) continue;
            int x = controller.xCoord + p.x(), y = controller.yCoord + p.y(), z = controller.zCoord + p.z();
            Block block = world.getBlock(x, y, z);
            int meta = world.getBlockMetadata(x, y, z);
            TileEntity tile = world.getTileEntity(x, y, z);
            Visit visit = entry.getValue();
            // The actual native check runs only against the owned empty cell/controller. This also handles
            // air declarations behind native context/channel wrappers, without class-name unwrapping rules.
            boolean air = block == Blocks.air && ((IStructureElement) visit.element).check(context, world, x, y, z);
            if (block == Blocks.air && !air) throw new IOException("原生流程未生成此位置的真实材料：" + p + "。");
            ItemStack item = air ? null : items.materialAt(position(p.x(), p.y(), p.z()));
            int direction = -1;
            NBTTagCompound display = null, data = null;
            if (tile != null) {
                data = new NBTTagCompound();
                tile.writeToNBT(data);
                display = HologramDisplaySnapshot.projection(tile);
                if (tile instanceof IGregTechTileEntity gt) {
                    item = new ItemStack(block, 1, gt.getMetaTileID());
                    direction = gt.getFrontFacing()
                        .ordinal();
                }
            }
            if (!air && item == null) item = HologramBlockIdentity.materialStack(block, meta);
            if (!air && (item == null || item.getItem() == null)) throw new IOException("原生目标没有可支付的物品身份。");
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
                    visit.element,
                    visit.signal.copy(),
                    air));
        }
    }

    /** The native definition callback records the exact piece signal; it never cancels the native invocation. */
    static void piece(IStructureDefinition<?> definition, Object context, ItemStack trigger, World world) {
        HologramNativeTargetPlan plan = ACTIVE.get();
        if (plan != null && plan.context == context && plan.world == world) {
            plan.definition = definition;
            plan.pieceTrigger = trigger.copy();
        }
    }

    static void piece(Object context, ItemStack signal) {
        HologramNativeTargetPlan plan = ACTIVE.get();
        if (plan != null && plan.context == context && signal != null) plan.pieceTrigger = signal.copy();
    }

    static boolean consumesHint(World world) {
        HologramNativeTargetPlan plan = ACTIVE.get();
        return plan != null && plan.world == world;
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
        try {
            items.position(position(cell.dx(), cell.dy(), cell.dz()));
            IStructureElement.BlocksToPlace blocks = ((IStructureElement) cell.element()).getBlocksToPlace(
                context,
                world,
                controller.xCoord + cell.dx(),
                controller.yCoord + cell.dy(),
                controller.zCoord + cell.dz(),
                cell.trigger()
                    .copy(),
                AutoPlaceEnvironment.fromLegacy(items, actor, ignored -> {}));
            if (blocks == null || blocks.getPredicate() == null) return Collections.emptyList();
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack candidate : items.catalog()) if (candidate.getItem() instanceof ItemMachines
                && HologramCellPolicy.hatchTier(candidate) != Integer.MAX_VALUE
                && blocks.getPredicate()
                    .test(candidate.copy())) {
                        result.add(candidate.copy());
                        if (result.size() >= 128) break;
                    }
            return result;
        } catch (RuntimeException | LinkageError unreadable) {
            return Collections.emptyList();
        }
    }

    public static final class Listener {

        @SubscribeEvent
        public void visit(StructureElementVisitedEvent event) {
            HologramNativeTargetPlan plan = ACTIVE.get();
            if (plan == null || plan.validating || event.getWorld() != plan.world) return;
            Offset p = new Offset(
                event.getX() - plan.controller.xCoord,
                event.getY() - plan.controller.yCoord,
                event.getZ() - plan.controller.zCoord);
            if (plan.visits.size() >= HologramBlueprintLimits.MAX_CELLS && !plan.visits.containsKey(p)) {
                // StructureLib logs and swallows event-listener Exceptions. Latch truncation explicitly instead.
                plan.limitExceeded = true;
                return;
            }
            plan.visits.put(p, new Visit(event.getElement(), plan.pieceTrigger));
            plan.items.position(position(p.x(), p.y(), p.z()));
        }
    }

    private static final class Actor extends EntityPlayer {

        Actor(World world) {
            super(
                world,
                new GameProfile(UUID.fromString("337d8843-5ccf-47ae-b7d6-8cb58448d32d"), "[GTMB Native Target]"));
        }

        @Override
        public void addChatMessage(IChatComponent message) {}

        @Override
        public void addStat(StatBase stat, int amount) {}

        @Override
        public void openGui(Object mod, int id, World world, int x, int y, int z) {}

        @Override
        public void travelToDimension(int dimension) {}

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return false;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }
    }
}
