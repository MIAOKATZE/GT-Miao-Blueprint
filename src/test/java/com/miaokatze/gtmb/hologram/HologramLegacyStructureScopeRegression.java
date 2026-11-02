package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;

/** An old file has the full physical body but membership only in its first native stage. */
final class HologramLegacyStructureScopeRegression {

    static void run(sun.misc.Unsafe unsafe) throws Exception {
        int id = 32758;
        IMetaTileEntity previous = GregTechAPI.METATILEENTITIES[id];
        LegacyController prototype = new LegacyController();
        GregTechAPI.METATILEENTITIES[id] = prototype;
        try (AutoCloseable declaration = HologramDeclarationAdapters.registerScopedReader(
            HintedBlock.class,
            leaf -> new HologramDeclarationAdapters.Declaration(
                new HologramPureElementsRegression.Leaf(true, leaf.block, null)))) {
            EntityPlayerMP actor = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            actor.worldObj = (World) unsafe.allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            actor.inventory = new net.minecraft.entity.player.InventoryPlayer(actor);
            actor.capabilities = new net.minecraft.entity.player.PlayerCapabilities();
            actor.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
            HologramSession session = new HologramSession(actor, 0, 64, 0, false, 2);
            session.virtual = true;
            session.virtualPreset = new HologramPresets.Preset();
            session.virtualPreset.schema = 2;
            session.virtualPreset.controllerFacing = ExtendedFacing.DEFAULT.ordinal();
            HologramPresets.Structure source = new HologramPresets.Structure();
            source.id = "legacy";
            source.controllerId = id;
            source.sourceClass = LegacyController.class.getName();
            session.virtualPreset.structures.add(source);
            session.blueprintActiveStructure = source.id;
            LegacyController machine = (LegacyController) HologramDetachedController.create(id, ExtendedFacing.DEFAULT);
            HologramCapture low = complete(machine, 1), high = complete(machine, 4);
            session.capture = new HologramCapture(null, signal(1));
            Set<String> protectedKeys = new HashSet<>();
            int protectedKind = 0;
            for (HologramCapture.Cell nativeCell : high.cells) {
                HologramCapture.Cell cell = HologramBlueprintData
                    .blueprintCell(nativeCell, nativeCell.x, nativeCell.y - 64, nativeCell.z);
                cell.anchor = false;
                cell.userPosition = true;
                cell.role = nativeCell.anchor ? "templateController" : "prefab";
                ItemStack material;
                if (nativeCell.anchor) {
                    // The headless registry has no GT machine block; this fixture's native shell is stone.
                    material = machine.getStackForm(1);
                    cell.block = Block.getBlockFromItem(material.getItem());
                    cell.meta = 0;
                } else {
                    SimpleAssert.that(
                        !cell.unknown && (cell.block == Blocks.stone || cell.block == Blocks.dirt),
                        "every physical legacy body/cap cell has its explicit native hint");
                    material = HologramBlockIdentity.materialStack(cell.block, cell.meta);
                }
                SimpleAssert.that(material != null, "known legacy fixture cell resolves its real native material");
                cell.blueprintBlock = String.valueOf(Block.blockRegistry.getNameForObject(cell.block));
                cell.blueprintItem = String
                    .valueOf(net.minecraft.item.Item.itemRegistry.getNameForObject(material.getItem()));
                cell.blueprintDamage = material.getItemDamage();
                if (low.positions.containsKey(HologramValues.position(nativeCell)))
                    HologramBlueprintData.setMemberships(cell, Collections.singletonList(source.id));
                else if (protectedKind < 4) {
                    String key = HologramValues.position(cell);
                    if (protectedKind == 0)
                        HologramBlueprintData.setMemberships(cell, Collections.singletonList("other"));
                    else if (protectedKind == 1) session.virtualEdited.add(key);
                    else if (protectedKind == 2) cell.tileNbt = sentinel("tile");
                    else cell.itemNbt = sentinel("item");
                    protectedKeys.add(key);
                    protectedKind++;
                }
                append(session, cell, material);
            }
            SimpleAssert.eq(4, protectedKind, "old upper-body protection fixtures exist");
            HologramCapture.Cell outside = new HologramCapture.Cell(null, 1000, 64, 1000);
            outside.block = Blocks.gold_block;
            outside.userPosition = true;
            outside.blueprintBlock = outside.blueprintItem = "minecraft:gold_block";
            append(session, outside, new ItemStack(Blocks.gold_block));
            protectedKeys.add(HologramValues.position(outside));
            Map<String, HologramCapture.Cell> before = new HashMap<>(session.capture.positions);
            Map<String, String> payloads = new HashMap<>();
            for (String key : protectedKeys) {
                HologramCapture.Cell cell = before.get(key);
                payloads.put(key, cell.tileNbt + ":" + cell.itemNbt);
            }
            HologramVirtualStructureEditing.Task first = edit(session, 1);
            SimpleAssert.that(
                first.legacyScope != null && !first.legacyScope.recovered.isEmpty(),
                "first shrink actually verifies and recovers forgotten membership");
            int removed = 0, changedCap = 0;
            for (Map.Entry<String, HologramCapture.Cell> entry : before.entrySet()) {
                HologramCapture.Cell old = entry.getValue();
                HologramCapture.Cell expected = low.positions.get(old.x + "," + (old.y + 64) + "," + old.z);
                if (protectedKeys.contains(entry.getKey())) {
                    SimpleAssert.that(
                        session.capture.positions.get(entry.getKey()) == old,
                        "foreign/edited/opaque/outside exact cell survives");
                    SimpleAssert.that(
                        payloads.get(entry.getKey())
                            .equals(old.tileNbt + ":" + old.itemNbt),
                        "protected native payload retains its full original encoded bytes");
                } else if (expected == null) {
                    SimpleAssert.that(
                        !session.capture.positions.containsKey(entry.getKey()),
                        "first shrink removes unassigned old upper/cap literal");
                    removed++;
                } else if (!expected.anchor && expected.block != old.block) {
                    HologramCapture.Cell current = session.capture.positions.get(entry.getKey());
                    SimpleAssert.that(current.block == expected.block, "new cap replaces old body at the lower height");
                    SimpleAssert.that(
                        HologramValues.sameMaterial(
                            session.pins.get(session.capture.cells.indexOf(current)),
                            HologramBlockIdentity.materialStack(expected.block, expected.meta)),
                        "new cap keeps its requested payment target");
                    changedCap++;
                }
            }
            SimpleAssert
                .that(removed > 0 && changedCap > 0, "first shrink checks actual upper removal and changed cap target");
            HologramVirtualStructureEditing.Task grow = edit(session, 4);
            SimpleAssert
                .that(grow.legacyScope == null, "published complete ownership avoids repeated full preset inference");
            HologramVirtualStructureEditing.Task shrink = edit(session, 1);
            SimpleAssert.that(shrink.legacyScope == null, "subsequent shrink uses the current owned difference");
            SimpleAssert.eq(0, prototype.constructions, "legacy recovery never constructs the prototype");

            // Unverifiable old body is a failed staged transaction, not a successful partial rewrite.
            HologramCapture savedCapture = session.capture;
            source = session.virtualPreset.structures.get(0);
            source.channels.clear();
            source.mainExplicit = false;
            HologramCapture.Cell damaged = null;
            for (HologramCapture.Cell cell : savedCapture.cells)
                if (cell.belongsTo(source.id) && "prefab".equals(cell.role)) {
                    damaged = cell;
                    break;
                }
            damaged.block = Blocks.glass;
            boolean refused = false;
            try {
                edit(session, 2);
            } catch (java.io.IOException expected) {
                refused = true;
            }
            SimpleAssert.that(
                refused && session.capture == savedCapture,
                "no complete baseline match preserves the entire original capture");
        } finally {
            GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    private static HologramVirtualStructureEditing.Task edit(HologramSession session, int height) throws Exception {
        NBTTagCompound channels = new NBTTagCompound();
        channels.setInteger("height", height);
        HologramPresets.Structure source = session.virtualPreset.structures.get(0);
        HologramVirtualStructureEditing.Task task = new HologramVirtualStructureEditing.Task(
            session,
            source,
            1,
            channels);
        session.channels = channels;
        HologramBlueprintData.storeStructureConfiguration(session, source);
        int polls = 0;
        while (!task.step() && ++polls < 2048) {}
        SimpleAssert.that(polls < 2048, "legacy scope recovery and generation have bounded continuations");
        task.publish();
        return task;
    }

    private static void append(HologramSession session, HologramCapture.Cell cell, ItemStack material) {
        HologramPresets.Cell saved = HologramPresets
            .cell(material, cell.x, cell.y - 64, cell.z, 2, "templateController".equals(cell.role));
        saved.block = cell.blueprintBlock;
        saved.metadata = cell.meta;
        saved.tileNbt = cell.tileNbt;
        saved.itemNbt = cell.itemNbt;
        saved.structureId = cell.structureId;
        saved.structureIds = new java.util.ArrayList<>(cell.memberships());
        session.virtualPreset.cells.add(saved);
        session.pins.put(session.capture.cells.size(), material.copy());
        session.capture.cells.add(cell);
        session.capture.positions.put(HologramValues.position(cell), cell);
    }

    private static ItemStack signal(int height) {
        ItemStack signal = new ItemStack(Blocks.stone);
        ChannelDataAccessor.setChannelData(signal, "height", height);
        return signal;
    }

    private static String sentinel(String type) throws java.io.IOException {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("legacyScopeFixture" + type, "preserved unknown " + type + " leaf");
        return HologramBlueprintNbt.encodeCaptured(tag);
    }

    private static HologramCapture complete(LegacyController machine, int height) {
        ItemStack signal = signal(height);
        HologramCapture capture = HologramCapture.collect(machine, signal, machine.getExtendedFacing());
        while (capture.pending)
            capture = HologramCapture.collect(machine, signal, machine.getExtendedFacing(), capture);
        SimpleAssert.that(!capture.incomplete, "legacy physical shape declaration is complete");
        return capture;
    }

    static final class LegacyController extends MTEEnhancedMultiBlockBase<LegacyController> {

        int height = 1, constructions;

        LegacyController() {
            super("legacy_scope_fixture");
        }

        @Override
        public IStructureDefinition<LegacyController> getStructureDefinition() {
            String[] rows = new String[height + 1];
            java.util.Arrays.fill(rows, "SSSS");
            rows[height] = "CCCC";
            return StructureDefinition.<LegacyController>builder()
                .addShape("body", new String[][] { rows })
                .addElement('S', new HintedBlock(Blocks.stone))
                .addElement('C', new HintedBlock(Blocks.dirt))
                .build();
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            constructions++;
            height = Math.max(1, Math.min(4, ChannelDataAccessor.getChannelData(trigger, "height")));
            HologramChannelTrace.record(trigger, "height", height, 2);
            HologramCapture.piece(this, "body", trigger, hints, -3, 0, 0);
        }

        @Override
        public ItemStack getStackForm(long count) {
            return new ItemStack(Blocks.stone, (int) count);
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            return null;
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            return new LegacyController();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }

    /** Explicit test hook replaces only StructureLib's runtime hint mixin in the headless harness. */
    static final class HintedBlock implements IStructureElement<LegacyController> {

        final Block block;
        final IStructureElement<LegacyController> factory;

        HintedBlock(Block block) {
            this.block = block;
            factory = StructureUtility.ofBlock(block, 0);
        }

        @Override
        public boolean check(LegacyController context, World world, int x, int y, int z) {
            throw new AssertionError("no native checkMachine");
        }

        @Override
        public boolean spawnHint(LegacyController context, World world, int x, int y, int z, ItemStack signal) {
            return HologramCapture.hint(world, x, y, z, block, 0);
        }

        @Override
        public boolean placeBlock(LegacyController context, World world, int x, int y, int z, ItemStack signal) {
            throw new AssertionError("read-only declarations");
        }

        @Override
        public BlocksToPlace getBlocksToPlace(LegacyController context, World world, int x, int y, int z,
            ItemStack signal, com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment environment) {
            return factory.getBlocksToPlace(context, world, x, y, z, signal, environment);
        }
    }
}
