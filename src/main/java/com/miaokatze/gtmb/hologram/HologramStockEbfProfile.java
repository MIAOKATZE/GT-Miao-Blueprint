// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.LongHashMap;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.ICustomBlockSetting;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.StructureSources;

import gregtech.api.GregTechAPI;
import gregtech.api.enums.HeatingCoilLevel;
import gregtech.api.enums.MetaTileEntityIDs;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEHatchEnergy;
import gregtech.api.metatileentity.implementations.MTEHatchInput;
import gregtech.api.metatileentity.implementations.MTEHatchInputBus;
import gregtech.api.metatileentity.implementations.MTEHatchMaintenance;
import gregtech.api.metatileentity.implementations.MTEHatchMuffler;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.metatileentity.implementations.MTEHatchOutputBus;
import gregtech.api.util.GTStructureUtility;
import gregtech.api.util.HatchElementBuilder;
import gregtech.common.blocks.BlockCasings1;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.blocks.ItemCasings;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** The pinned GT5-U 5.09.54.265 #1000 shell. Qualifies; does not admit, schedule, debit or mutate. */
final class HologramStockEbfProfile {

    static final int CONTROLLER = MetaTileEntityIDs.EBF_CONTROLLER.ID;
    static final String PIECE = "main";
    static final int A = 1, B = 3, C = 0;
    static final int CASING_META = 11, CASING_BOUND = 12, COIL_BOUND = 16;
    private static final int MAX_ENTRIES = 96, REQUIRED_HATCHES = 5;
    private static final String COIL_CHANNEL = "coil";

    enum Part {
        CONTROLLER, TOP, BOTTOM, MUFFLER, COIL, AIR
    }

    enum CellState { MATCH, EMPTY, BLOCKED }

    enum FinalSeal { EXACT, DEPENDENCY_CHANGED, CANDIDATE_UNCERTAIN }

    enum CandidateState { ORIGINAL_AIR, OWNED, UNCERTAIN }

    @Desugar
    private record Row(Part part, int a, int b, int c, IStructureElement<?> root) {}

    private enum Role {
        ENERGY, INPUT, OUTPUT, MAINTENANCE, MUFFLER
    }

    /** The first profile only preserves exact already-installed roles. This does not authorize installing a hatch. */
    @Desugar
    record Installed(int a, int b, int c, int id, int facing) {}

    @Desugar
    private record HatchType(Role role, Class<? extends MTEHatch> type, int tier) {

        boolean matches(IMetaTileEntity mte) {
            return mte != null && mte.getClass() == type && ((MTEHatch) mte).mTier == tier;
        }

        boolean admits(Part part) {
            if (role == Role.MUFFLER) return part == Part.MUFFLER;
            if (part == Part.BOTTOM) return true;
            return part == Part.TOP && type == MTEHatchOutput.class;
        }
    }

    @Desugar
    private record Chain(IStructureElement<?> root, IStructureElement<?>[] branches, IStructureElement<?> adder,
        IStructureElement<?> leaf, HatchElementBuilder<?> builder, int hint) {

        boolean current() {
            return branches.length == 2 && branches[0] == adder && branches[1] == leaf
                && builder.getCasingIndex() == CASING_META && builder.getHint() == hint && !builder.isExclusive();
        }
    }

    @Desugar
    private record HatchPin(Installed selection, HatchType type, int x, int y, int z, BaseMetaTileEntity tile,
        MTEHatch machine, IMetaTileEntity prototype) {

        boolean fields(World world) {
            return linked(tile, machine, selection.id, world, x, y, z) && type.matches(machine)
                && GregTechAPI.METATILEENTITIES[selection.id] == prototype && type.matches(prototype)
                && tile.getFrontFacing() == ForgeDirection.getOrientation(selection.facing);
        }
    }

    static final class Qualified {

        private final HologramStockEbfProfile profile;
        private final World world;
        private final BaseMetaTileEntity controller;
        private final MTEElectricBlastFurnace machine;
        private final ExtendedFacing facing;
        private final int x, y, z;
        private final HatchPin[] hatches;
        private final ItemStack casing, coil;
        private RawFence writer;

        private Qualified(HologramStockEbfProfile profile, World world, BaseMetaTileEntity controller,
            MTEElectricBlastFurnace machine, ExtendedFacing facing, int x, int y, int z, HatchPin[] hatches,
            ItemStack casing, ItemStack coil) {
            this.profile = profile;
            this.world = world;
            this.controller = controller;
            this.machine = machine;
            this.facing = facing;
            this.x = x;
            this.y = y;
            this.z = z;
            this.hatches = hatches;
            this.casing = casing;
            this.coil = coil;
        }

        int coilMeta() {
            return coil.getItemDamage();
        }

        ItemStack component(Part part) {
            return switch (part) {
                case TOP, BOTTOM -> casing.copy();
                case COIL -> coil.copy();
                default -> null;
            };
        }

        boolean boundSession(HologramSession session) {
            return session.target == controller && session.machine == machine && session.libraryWorld == world
                && session.player.worldObj == world && session.x == x && session.y == y && session.z == z
                && session.facing == facing.ordinal()
                && coilMetadata(session.main, session.channels) == coilMeta();
        }

        /** Owner-thread only: fail before any write if the genuine loaded backing cannot be proven. */
        boolean armWriter() {
            if (writer != null || !(world instanceof WorldServer server) || !linksCurrent()) return false;
            writer = RawFence.capture(this, server);
            return writer != null;
        }

        HologramStructureCursor cursor() {
            return new HologramStructureCursor(profile.elements, A, B, C);
        }

        IStructureElement<?> element(int index) {
            return profile.sealed[index];
        }

        private Row row(int index, int cx, int cy, int cz) {
            if (index < 0 || index >= profile.rows.length) return null;
            Row row = profile.rows[index];
            if (row == null) return null;
            int[] offset = new int[3];
            facing.getWorldOffset(new int[] { row.a, row.b, row.c }, offset);
            return cx == x + offset[0] && cy == y + offset[1] && cz == z + offset[2] ? row : null;
        }

        CellState classify(int index, int cx, int cy, int cz) {
            Row row = row(index, cx, cy, cz);
            if (row == null || !linksCurrent() || !HologramWorldFlow.worldPosition(cx, cy, cz, world.getHeight())
                || !world.blockExists(cx, cy, cz)) return CellState.BLOCKED;
            Block block = world.getBlock(cx, cy, cz);
            var tile = world.getTileEntity(cx, cy, cz);
            for (HatchPin hatch : hatches) {
                if (cx == hatch.x && cy == hatch.y && cz == hatch.z)
                    return block == profile.machines && tile == hatch.tile && hatch.fields(world)
                        ? CellState.MATCH : CellState.BLOCKED;
            }
            if (tile != null || row.part == Part.MUFFLER) return CellState.BLOCKED;
            if (row.part == Part.AIR) return block == Blocks.air ? CellState.MATCH : CellState.BLOCKED;
            Block wanted = row.part == Part.COIL ? profile.coils : profile.casing;
            int metadata = row.part == Part.COIL ? coilMeta() : CASING_META;
            if (block == wanted && world.getBlockMetadata(cx, cy, cz) == metadata) return CellState.MATCH;
            return block == Blocks.air ? CellState.EMPTY : CellState.BLOCKED;
        }

        PreparedCell prepare(int index, int cx, int cy, int cz, ItemStack trigger) {
            Row row = row(index, cx, cy, cz);
            if (row == null || trigger == null || writer == null || !linksCurrent()) return null;
            // Never mint a payable cell for any installed hatch, controller, exclusive muffler or native air.
            for (HatchPin hatch : hatches) if (cx == hatch.x && cy == hatch.y && cz == hatch.z) return null;
            ItemStack material = component(row.part);
            if (material == null) return null;
            IStructureElement<?> leaf = switch (row.part) {
                case TOP -> profile.top.leaf;
                case BOTTOM -> profile.bottom.leaf;
                default -> row.root;
            };
            Block expected = row.part == Part.COIL ? profile.coils : profile.casing;
            var raw = writer.position(cx, cy, cz);
            var original = raw == null ? null : raw.read(writer);
            if (original == null || original.block != 0 || original.tile != null) return null;
            var cell = new HologramCapture.Cell(leaf, cx, cy, cz, trigger);
            cell.block = expected;
            cell.meta = material.getItemDamage();
            cell.stableIndex = 0;
            return new PreparedCell(this, index, row, cell, material, raw);
        }

        /** Repeat this pure half after any callback-capable world reads; a task must also recheck its own lease. */
        boolean linksCurrent() {
            if (!profile.declarationCurrent() || !linked(controller, machine, CONTROLLER, world, x, y, z)
                || machine.mMachine || machine.getExtendedFacing() != facing) return false;
            for (HatchPin hatch : hatches) if (!hatch.fields(world)) return false;
            return true;
        }

        /** Physical half for scoped precommit, not the callback-free final payment predicate. No chunk loads. */
        boolean physicalCurrent() {
            if (!linksCurrent() || liveTile(world, x, y, z, profile.machines) != controller) return false;
            for (HatchPin hatch : hatches) {
                if (liveTile(world, hatch.x, hatch.y, hatch.z, profile.machines) != hatch.tile) return false;
            }
            // Check exact selected heat after the controller/hatch reads: a protected-read callback can change a coil.
            if (!coilsCompatible()) return false;
            return linksCurrent();
        }

        /** Loaded coil sites may be air or the exact selected stock block and metadata; no chunk loads. */
        private boolean coilsCompatible() {
            int[] delta = new int[3];
            for (int a = -1; a <= 1; a++) for (int b = -2; b <= -1; b++) for (int c = 0; c <= 2; c++) {
                if (part(a, b, c) != Part.COIL) continue;
                facing.getWorldOffset(new int[] { a, b, c }, delta);
                int cx = x + delta[0], cy = y + delta[1], cz = z + delta[2];
                if (!HologramWorldFlow.worldPosition(cx, cy, cz, world.getHeight()) || !world.blockExists(cx, cy, cz)
                    || world.getTileEntity(cx, cy, cz) != null) return false;
                Block existing = world.getBlock(cx, cy, cz);
                if (existing != Blocks.air && (existing != profile.coils
                    || world.getBlockMetadata(cx, cy, cz) != coilMeta())) return false;
            }
            return true;
        }

        /** One capped backpack pass. Stock is an upper-bound admission proof, never a reservation or a debit. */
        boolean fullyFunded(InventoryPlayer inventory, int toolSlot, HologramMaterials.Settings settings) {
            if (inventory == null || inventory.player == null || inventory.player.inventory != inventory
                || inventory.currentItem != toolSlot
                || toolSlot < 0 || toolSlot >= 9 || settings == null || settings.priority != 0
                || !HologramProjectedMainPolicy.eligible(settings) || !linksCurrent()) return false;
            var main = inventory.mainInventory;
            if (main == null || main.length != 36) return false;
            int casingLeft = CASING_BOUND, coilLeft = COIL_BOUND;
            for (int i = 0; i < main.length; i++) {
                var stack = main[i];
                if (i == toolSlot || stack == null || stack.stackSize <= 0 || stack.hasTagCompound()) continue;
                if (HologramValues.same(stack, casing)) casingLeft -= Math.min(casingLeft, stack.stackSize);
                else if (HologramValues.same(stack, coil)) coilLeft -= Math.min(coilLeft, stack.stackSize);
            }
            return casingLeft == 0 && coilLeft == 0 && inventory.player.inventory == inventory
                && inventory.mainInventory == main && inventory.currentItem == toolSlot && linksCurrent();
        }
    }

    /** Read-only loaded backing proof. It cannot load a chunk or dispatch through a World/Block/tile callback. */
    private static final class RawFence {

        private final Qualified site;
        private final WorldServer world;
        private final WorldProvider worldProvider;
        private final ChunkProviderServer provider;
        private final LongHashMap loaded;
        private final int machineId, casingId, coilId;
        private final Map<Long, RawChunk> chunks = new HashMap<>();
        private final RawPosition[] tiles = new RawPosition[1 + REQUIRED_HATCHES], coils = new RawPosition[COIL_BOUND];

        private RawFence(Qualified site, WorldServer world, ChunkProviderServer provider) {
            this.site = site;
            this.world = world;
            worldProvider = world.provider;
            this.provider = provider;
            loaded = provider.loadedChunkHashMap;
            machineId = Block.getIdFromBlock(site.profile.machines);
            casingId = Block.getIdFromBlock(site.profile.casing);
            coilId = Block.getIdFromBlock(site.profile.coils);
        }

        static RawFence capture(Qualified site, WorldServer world) {
            var provider = world.theChunkProviderServer;
            if (provider == null || provider.getClass() != ChunkProviderServer.class
                || provider.loadedChunkHashMap == null || provider.loadedChunkHashMap.getClass() != LongHashMap.class)
                return null;
            var fence = new RawFence(site, world, provider);
            fence.tiles[0] = fence.position(site.x, site.y, site.z);
            for (int i = 0; i < site.hatches.length; i++) {
                var hatch = site.hatches[i];
                fence.tiles[i + 1] = fence.position(hatch.x, hatch.y, hatch.z);
            }
            int i = 0;
            int[] delta = new int[3];
            for (int a = -1; a <= 1; a++) for (int b = -2; b <= -1; b++) for (int c = 0; c <= 2; c++) {
                if (part(a, b, c) != Part.COIL) continue;
                site.facing.getWorldOffset(new int[] { a, b, c }, delta);
                fence.coils[i++] = fence.position(site.x + delta[0], site.y + delta[1], site.z + delta[2]);
            }
            return i == COIL_BOUND && fence.dependenciesCurrent() ? fence : null;
        }

        boolean backingCurrent() {
            return worldProvider != null && world.theChunkProviderServer == provider && world.provider == worldProvider
                && provider.worldObj == world && provider.loadedChunkHashMap == loaded;
        }

        boolean identityCurrent() {
            return machineId != 0 && casingId != 0 && coilId != 0 && Block.getBlockById(0) == Blocks.air
                && Block.getBlockById(machineId) == site.profile.machines
                && Block.getBlockById(casingId) == site.profile.casing && Block.getBlockById(coilId) == site.profile.coils;
        }

        RawPosition position(int x, int y, int z) {
            if (!backingCurrent() || y < 0 || y >= 256) return null;
            int cx = x >> 4, cz = z >> 4;
            long key = ChunkCoordIntPair.chunkXZ2Int(cx, cz);
            RawChunk bound = chunks.get(key);
            if (bound == null) {
                Object value = loaded.getValueByKey(key);
                if (!(value instanceof Chunk chunk) || chunk.getClass() != Chunk.class || !chunk.isChunkLoaded
                    || chunk.worldObj != world || chunk.xPosition != cx || chunk.zPosition != cz
                    || chunk.chunkTileEntityMap == null || chunk.chunkTileEntityMap.getClass() != HashMap.class) return null;
                var sections = chunk.getBlockStorageArray();
                if (sections == null || sections.length != 16) return null;
                bound = new RawChunk(key, chunk, sections, chunk.chunkTileEntityMap);
                chunks.put(key, bound);
            }
            return new RawPosition(bound, x & 15, y, z & 15);
        }

        boolean dependenciesCurrent() {
            if (!backingCurrent() || !identityCurrent()) return false;
            for (int i = 0; i < tiles.length; i++) {
                RawCell raw = tiles[i] == null ? null : tiles[i].read(this);
                if (raw == null || raw.block != machineId
                    || raw.tile != (i == 0 ? site.controller : site.hatches[i - 1].tile)) return false;
            }
            for (RawPosition coil : coils) {
                RawCell raw = coil == null ? null : coil.read(this);
                if (raw == null || raw.tile != null
                    || (raw.block != 0 && (raw.block != coilId || raw.meta != site.coilMeta()))) return false;
            }
            return true;
        }
    }

    @Desugar
    private record RawChunk(long key, Chunk chunk, ExtendedBlockStorage[] sections, Map<ChunkPosition,
        net.minecraft.tileentity.TileEntity> tiles) {}

    @Desugar
    private record RawCell(int block, int meta, net.minecraft.tileentity.TileEntity tile) {}

    @Desugar
    private record RawPosition(RawChunk bound, int x, int y, int z) {

        RawCell read(RawFence fence) {
            var chunk = bound.chunk;
            if (!fence.backingCurrent() || fence.loaded.getValueByKey(bound.key) != chunk || !chunk.isChunkLoaded
                || chunk.worldObj != fence.world || chunk.getBlockStorageArray() != bound.sections
                || chunk.chunkTileEntityMap != bound.tiles) return null;
            var section = bound.sections[y >> 4];
            var tile = bound.tiles.get(new ChunkPosition(x, y, z));
            if (section == null) return new RawCell(0, 0, tile);
            if (section.getClass() != ExtendedBlockStorage.class || section.getYLocation() != (y & ~15)) return null;
            var low = section.getBlockLSBArray();
            var high = section.getBlockMSBArray();
            var metadata = section.getMetadataArray();
            if (low == null || low.length != 4096 || !sectionNibbles(metadata)
                || high != null && !sectionNibbles(high)) return null;
            int offset = ((y & 15) << 8) | (z << 4) | x;
            int id = low[offset] & 255;
            if (high != null) id |= nibble(high, offset) << 8;
            return new RawCell(id, nibble(metadata, offset), tile);
        }

        private static boolean sectionNibbles(NibbleArray data) {
            return data != null && data.getClass() == NibbleArray.class && data.data != null && data.data.length == 2048;
        }

        private static int nibble(NibbleArray data, int offset) {
            return (data.data[offset >> 1] >> ((offset & 1) * 4)) & 15;
        }
    }

    /** Only a Qualified site can mint this immutable owner/coordinate/role/payment certificate. */
    static final class PreparedCell {

        final HologramCapture.Cell cell;
        final ItemStack material;
        private final Qualified site;
        private final int rowIndex;
        private final Row row;
        private final Block block;
        private final int meta;
        private final RawPosition raw;

        private PreparedCell(Qualified site, int rowIndex, Row row, HologramCapture.Cell cell, ItemStack material,
            RawPosition raw) {
            this.site = site;
            this.rowIndex = rowIndex;
            this.row = row;
            this.cell = cell;
            this.material = material;
            this.raw = raw;
            block = cell.block;
            meta = cell.meta;
        }

        CandidateState candidateState(int originalMetadata) {
            RawFence fence = site.writer;
            if (fence == null || !fence.identityCurrent()) return CandidateState.UNCERTAIN;
            RawCell actual = raw.read(fence);
            if (actual == null || actual.tile != null) return CandidateState.UNCERTAIN;
            if (actual.block == 0 && actual.meta == originalMetadata) return CandidateState.ORIGINAL_AIR;
            int expected = row.part == Part.COIL ? fence.coilId : fence.casingId;
            return actual.block == expected && actual.meta == meta ? CandidateState.OWNED : CandidateState.UNCERTAIN;
        }

        boolean beforePlace(int originalMetadata) {
            return candidateState(originalMetadata) == CandidateState.ORIGINAL_AIR && site.writer.dependenciesCurrent();
        }

        FinalSeal finalSeal() {
            if (candidateState(-1) != CandidateState.OWNED) return FinalSeal.CANDIDATE_UNCERTAIN;
            return site.writer.dependenciesCurrent() ? FinalSeal.EXACT : FinalSeal.DEPENDENCY_CHANGED;
        }

        boolean matches(HologramSession s, HologramCapture.Cell candidate, int index, ItemStack selected) {
            return index == 0 && candidate == cell && candidate.block == block && candidate.meta == meta
                && candidate.itemNbt.isEmpty() && candidate.tileNbt.isEmpty() && !candidate.userPosition
                && !candidate.unknown && !candidate.anchor && s.stockNativePrivate && s.mode == 0 && s.scope == 0
                && !s.virtual && s.target == site.controller && s.machine == site.machine && s.libraryWorld == site.world
                && s.player.worldObj == site.world && s.x == site.x && s.y == site.y && s.z == site.z
                && s.facing == site.facing.ordinal() && s.capture != null && s.capture.cells.size() == 1
                && s.capture.cells.get(0) == cell && s.pins.get(0) == material && s.planTargets.size() == 1
                && s.planTargets.get(0) == material && s.facingPins.isEmpty()
                && site.row(rowIndex, cell.x, cell.y, cell.z) == row
                && site.profile.sealed[rowIndex] == row.root && site.linksCurrent()
                && selected != null && !selected.hasTagCompound() && selected.stackSize == 1
                && selected.getItem() == (row.part == Part.COIL ? site.profile.coilItem : site.profile.casingItem)
                && selected.getItemDamage() == meta && material.stackSize == 1 && HologramValues.same(material, selected);
        }
    }

    private final IStructureDefinition<MTEElectricBlastFurnace> definition;
    private final MTEElectricBlastFurnace prototype;
    private final Block casing, coils, machines;
    private final Item casingItem, coilItem;
    private final IStructureElement<?>[] elements, sealed;
    private final Row[] rows;
    private final Chain top, bottom;
    private final HatchElementBuilder<?> muffler;
    private final long sourceRevision;

    private HologramStockEbfProfile(IStructureDefinition<MTEElectricBlastFurnace> definition,
        MTEElectricBlastFurnace prototype, Block casing, Block coils, Block machines, Item casingItem, Item coilItem,
        IStructureElement<?>[] elements, IStructureElement<?>[] sealed, Row[] rows, Chain top, Chain bottom,
        HatchElementBuilder<?> muffler, long sourceRevision) {
        this.definition = definition;
        this.prototype = prototype;
        this.casing = casing;
        this.coils = coils;
        this.machines = machines;
        this.casingItem = casingItem;
        this.coilItem = coilItem;
        this.elements = elements;
        this.sealed = sealed;
        this.rows = rows;
        this.top = top;
        this.bottom = bottom;
        this.muffler = muffler;
        this.sourceRevision = sourceRevision;
    }

    static HologramStockEbfProfile inspectRegistered() {
        var registered = GregTechAPI.METATILEENTITIES[CONTROLLER];
        var casing = GregTechAPI.sBlockCasings1;
        var coils = GregTechAPI.sBlockCasings5;
        var machines = GregTechAPI.sBlockMachines;
        long revision = StructureSources.revision();
        if (registered == null || registered.getClass() != MTEElectricBlastFurnace.class || machines == null
            || casing == null || casing.getClass() != BlockCasings1.class
            || coils == null || coils.getClass() != BlockCasings5.class
            || StructureSources.hasSource(MTEElectricBlastFurnace.class)
            || BlueprintSnapshots.hasProcessors(BlueprintSnapshots.Stage.CAPTURE_COMPLETE)) return null;
        var prototype = (MTEElectricBlastFurnace) registered;
        var definition = prototype.getStructureDefinition();
        if (!HologramStructureCursor.supports(definition)) return null;
        IStructureElement<?>[] elements = definition.getStructureFor(PIECE);
        if (elements == null || elements.length == 0 || elements.length > MAX_ENTRIES) return null;
        IStructureElement<?>[] sealed = elements.clone();
        IStructureElement<?>[] roles = new IStructureElement<?>[Part.values().length];
        Row[] rows = new Row[sealed.length];
        long seen = 0;
        int a = -A, b = -B, c = -C;
        for (int i = 0; i < sealed.length; i++) {
            IStructureElement<?> element = sealed[i];
            if (element == null) return null;
            if (HologramStockCokeOvenProfile.isNativeNavigation(element)) {
                a = (element.resetA() ? -A : a) + element.getStepA();
                b = (element.resetB() ? -B : b) + element.getStepB();
                c = (element.resetC() ? -C : c) + element.getStepC();
                continue;
            }
            Part part = part(a, b, c);
            if (part == null || part == Part.CONTROLLER) return null;
            long point = bit(a, b, c);
            if ((seen & point) != 0) return null;
            seen |= point;
            if (roles[part.ordinal()] != null && roles[part.ordinal()] != element) return null;
            roles[part.ordinal()] = element;
            rows[i] = new Row(part, a, b, c, element);
            a++;
        }
        if (Long.bitCount(seen) != 35 || roles[Part.AIR.ordinal()] != StructureUtility.isAir()) return null;
        try {
            Chain top = chain(roles[Part.TOP.ordinal()], casing, 3);
            Chain bottom = chain(roles[Part.BOTTOM.ordinal()], casing, 1);
            HatchElementBuilder<?> muffler = hatchBuilder(roles[Part.MUFFLER.ordinal()], 2, true);
            if (top == null || bottom == null || top.root == bottom.root || muffler == null
                || !coilElement(roles[Part.COIL.ordinal()])) return null;
            Item casingItem = exactItem(casing, CASING_META), coilItem = exactItem(coils, 0);
            if (casingItem == null || coilItem == null || casingItem == coilItem) return null;
            var result = new HologramStockEbfProfile(definition, prototype, casing, coils, machines, casingItem, coilItem,
                elements, sealed, rows, top, bottom, muffler, revision);
            return result.declarationCurrent() ? result : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
            return null;
        }
    }

    /** No position/target DTO is consumed, and nothing outside the four roles plus exclusive muffler is opened. */
    Qualified qualify(World world, BaseMetaTileEntity anchor, ExtendedFacing facing, int master, NBTTagCompound channels,
        List<Installed> declaredHatches) {
        if (world == null || anchor == null || anchor.getClass() != BaseMetaTileEntity.class || facing == null
            || declaredHatches == null || declaredHatches.size() != REQUIRED_HATCHES || !declarationCurrent()
            || !(anchor.getMetaTileEntity() instanceof MTEElectricBlastFurnace machine)
            || machine.getClass() != MTEElectricBlastFurnace.class || machine.mMachine
            || machine.getExtendedFacing() != facing) return null;
        int meta = coilMetadata(master, channels);
        if (meta < 0 || exactItem(coils, meta) != coilItem || exactItem(casing, CASING_META) != casingItem) return null;
        int x = anchor.xCoord, y = anchor.yCoord, z = anchor.zCoord;
        if (liveTile(world, x, y, z, machines) != anchor) return null;
        var pinned = new HatchPin[REQUIRED_HATCHES];
        long seen = 0;
        int[] delta = new int[3];
        for (int i = 0; i < REQUIRED_HATCHES; i++) {
            Installed choice = declaredHatches.get(i);
            if (choice == null || choice.facing < 0 || choice.facing >= 6) return null;
            Part part = part(choice.a, choice.b, choice.c);
            HatchType type = hatchType(choice.id);
            if (type == null || !type.admits(part) || pinned[type.role.ordinal()] != null) return null;
            long point = bit(choice.a, choice.b, choice.c);
            if ((seen & point) != 0) return null;
            seen |= point;
            facing.getWorldOffset(new int[] { choice.a, choice.b, choice.c }, delta);
            int hx = x + delta[0], hy = y + delta[1], hz = z + delta[2];
            BaseMetaTileEntity base = liveTile(world, hx, hy, hz, machines);
            IMetaTileEntity installed = base == null ? null : base.getMetaTileEntity();
            IMetaTileEntity registered = GregTechAPI.METATILEENTITIES[choice.id];
            if (!type.matches(installed) || !type.matches(registered)) return null;
            var hatch = new HatchPin(choice, type, hx, hy, hz, base, (MTEHatch) installed, registered);
            if (!hatch.fields(world)) return null;
            pinned[type.role.ordinal()] = hatch;
        }
        var result = new Qualified(this, world, anchor, machine, facing, x, y, z, pinned,
            new ItemStack(casingItem, 1, CASING_META), new ItemStack(coilItem, 1, meta));
        return result.physicalCurrent() ? result : null;
    }

    boolean declarationCurrent() {
        if (sourceRevision != StructureSources.revision()
            || BlueprintSnapshots.hasProcessors(BlueprintSnapshots.Stage.CAPTURE_COMPLETE)
            || GregTechAPI.METATILEENTITIES[CONTROLLER] != prototype || GregTechAPI.sBlockCasings1 != casing
            || GregTechAPI.sBlockCasings5 != coils || GregTechAPI.sBlockMachines != machines
            || definition.getStructureFor(PIECE) != elements) return false;
        // Identity only: a substituted StructureLib element must not get equals() or any other callback.
        for (int i = 0; i < sealed.length; i++) if (elements[i] != sealed[i]) return false;
        return top.current() && bottom.current() && muffler.getCasingIndex() == CASING_META
            && muffler.getHint() == 2 && muffler.isExclusive();
    }

    static int coilMetadata(int master, NBTTagCompound channels) {
        if (master < 1 || master > 64 || channels == null) return -1;
        int signal = master;
        if (!channels.hasNoTags()) {
            if (channels.func_150296_c().size() != 1 || !channels.hasKey(COIL_CHANNEL, 3)) return -1;
            signal = channels.getInteger(COIL_CHANNEL);
            if (signal < 1 || signal > 64) return -1;
        }
        return BlockCasings5.getMetaFromCoilHeat(
            HeatingCoilLevel.getFromTier((byte) Math.min(HeatingCoilLevel.getMaxTier(), signal - 1)));
    }

    static Part part(int a, int b, int c) {
        if (a < -1 || a > 1 || b < -3 || b > 0 || c < 0 || c > 2) return null;
        if (b == 0) return a == 0 && c == 0 ? Part.CONTROLLER : Part.BOTTOM;
        if (b == -3) return a == 0 && c == 1 ? Part.MUFFLER : Part.TOP;
        return a == 0 && c == 1 ? Part.AIR : Part.COIL;
    }

    private static long bit(int a, int b, int c) {
        return 1L << (a + 1 + (b + 3) * 3 + c * 12);
    }

    private static HatchType hatchType(int id) {
        if (id >= 40 && id <= 49) return new HatchType(Role.ENERGY, MTEHatchEnergy.class, id - 40);
        if (id >= 50 && id <= 59) return new HatchType(Role.INPUT, MTEHatchInput.class, id - 50);
        if (id >= 60 && id <= 69) return new HatchType(Role.OUTPUT, MTEHatchOutput.class, id - 60);
        if (id >= 70 && id <= 79) return new HatchType(Role.INPUT, MTEHatchInputBus.class, id - 70);
        if (id >= 80 && id <= 89) return new HatchType(Role.OUTPUT, MTEHatchOutputBus.class, id - 80);
        if (id == 90) return new HatchType(Role.MAINTENANCE, MTEHatchMaintenance.class, 1);
        if (id >= 91 && id <= 99) return new HatchType(Role.MUFFLER, MTEHatchMuffler.class, id - 90);
        return null;
    }

    private static boolean linked(BaseMetaTileEntity tile, IMetaTileEntity machine, int id, World world, int x, int y, int z) {
        return !tile.isInvalid() && tile.getWorldObj() == world && tile.xCoord == x && tile.yCoord == y && tile.zCoord == z
            && tile.getMetaTileID() == id && tile.getMetaTileEntity() == machine && machine.getBaseMetaTileEntity() == tile;
    }

    private static BaseMetaTileEntity liveTile(World world, int x, int y, int z, Block machines) {
        if (!HologramWorldFlow.worldPosition(x, y, z, world.getHeight()) || !world.blockExists(x, y, z)
            || world.getBlock(x, y, z) != machines) return null;
        var tile = world.getTileEntity(x, y, z);
        return tile != null && tile.getClass() == BaseMetaTileEntity.class ? (BaseMetaTileEntity) tile : null;
    }

    private static Item exactItem(Block block, int meta) {
        // Exact built-in shell classes/registry identities are the evidence. Do not ask the public adapter registry
        // whether it supports them: unrelated extensions may run code or mutate authority during that lookup.
        if (block == null || block.getClass() != BlockCasings1.class && block.getClass() != BlockCasings5.class
            || block instanceof ICustomBlockSetting || block.hasTileEntity(meta)) return null;
        var item = Item.getItemFromBlock(block);
        String name = Block.blockRegistry.getNameForObject(block);
        if (item == null || item.getClass() != ItemCasings.class || ((ItemCasings) item).blockCasings != block
            || name == null || Block.blockRegistry.getObject(name) != block || Item.itemRegistry.getObject(name) != item
            || Block.getBlockFromItem(item) != block || item.getMetadata(meta) != meta
            || HologramMaterialDescriptor.hasExact(block) || HologramDecorMaterials.supports(block)) return null;
        var sample = new ItemStack(item, 1, meta);
        return HologramGtswnPlacementMaterials.supplyKind(sample) < 0
            && !HologramGtswnPlacementMaterials.freeShell(block, sample) ? item : null;
    }

    private static Chain chain(IStructureElement<?> root, Block casing, int hint) throws ReflectiveOperationException {
        if (!(root instanceof IStructureElementChain<?> stock)) return null;
        var branches = HologramDeclarationWrappers.nativeChainChildren(stock);
        if (branches == null || branches.length != 2) return null;
        var builder = hatchBuilder(branches[0], hint, false);
        var leaf = branches[1];
        if (builder == null || leaf == null || leaf.getClass() != ((Object) StructureUtility.ofBlock(casing, CASING_META)).getClass()
            || HologramDeclarationWrappers.captured(leaf, "val$block") != casing
            || HologramDeclarationWrappers.captured(leaf, "val$defaultBlock") != casing
            || !Integer.valueOf(CASING_META).equals(HologramDeclarationWrappers.captured(leaf, "val$meta"))
            || !Integer.valueOf(CASING_META).equals(HologramDeclarationWrappers.captured(leaf, "val$defaultMeta"))) return null;
        return new Chain(root, branches, branches[0], leaf, builder, hint);
    }

    private static HatchElementBuilder<?> hatchBuilder(IStructureElement<?> element, int hint, boolean exclusive)
        throws ReflectiveOperationException {
        if (!from(element, HatchElementBuilder.class, "build")) return null;
        Object enclosing = HologramDeclarationWrappers.captured(element, "this$0");
        if (!(enclosing instanceof HatchElementBuilder<?> builder) || enclosing.getClass() != HatchElementBuilder.class
            || builder.getHint() != hint || builder.getCasingIndex() != CASING_META || builder.isExclusive() != exclusive)
            return null;
        return builder;
    }

    private static boolean coilElement(IStructureElement<?> element) throws ReflectiveOperationException {
        if (!from(element, StructureUtility.class, "withChannel")
            || !COIL_CHANNEL.equals(HologramDeclarationWrappers.captured(element, "val$channel"))) return false;
        var active = HologramDeclarationWrappers.nativeBacking(element);
        return from(active, GTStructureUtility.class, "activeCoils")
            && active instanceof GTStructureUtility.ProxyStructureElement<?, ?> stock
            && from(stock.proxiedElement, GTStructureUtility.class, "ofCoil");
    }

    private static boolean from(IStructureElement<?> element, Class<?> owner, String name) {
        if (element == null) return false;
        var method = HologramDeclarationWrappers.factory(element.getClass());
        return method != null && method.getDeclaringClass() == owner && name.equals(method.getName());
    }
}
