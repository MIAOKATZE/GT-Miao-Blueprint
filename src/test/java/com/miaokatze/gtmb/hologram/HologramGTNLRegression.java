package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.miaokatze.gtmb.testutil.TestRunner;

import bartworks.system.material.BWItemMetaGeneratedBlock;
import bartworks.system.material.BWMetaGeneratedBlocksCasing;
import bartworks.system.material.BWTileEntityMetaGeneratedBlocksCasing;
import gregtech.api.GregTechAPI;
import gregtech.api.enums.OrePrefixes;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.MultiblockTooltipBuilder;
import gregtech.common.blocks.ItemMachines;

/** Geometry and declaration contracts run without loading GTNL or invoking any upstream callback. */
public final class HologramGTNLRegression {

    private HologramGTNLRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramGTNLRegression.class, args)) return;
        Map<String, Runnable> tests = new LinkedHashMap<>();
        tests.put("nuclear-geometry", HologramGTNLRegression::nuclearGeometry);
        tests.put("independent-channels", HologramGTNLRegression::channels);
        tests.put("fixed-leaf-purity-and-damage", HologramGTNLRegression::fixedLeaf);
        tests.put("bartworks-planned-and-actual-material-identity", HologramGTNLRegression::bartWorksMaterial);
        tests.put("saved-template-reread-preserves-physical-choices", HologramGTNLRegression::savedTemplateReread);
        tests.put("bounded-mbs-decoder", HologramGTNLRegression::codec);
        tests.put("real-sphere-resource", HologramGTNLRegression::sphere);
        TestRunner.run(HologramGTNLRegression.class, tests);
    }

    static void run() {
        nuclearGeometry();
        channels();
        fixedLeaf();
        bartWorksMaterial();
        savedTemplateReread();
        codec();
        sphere();
    }

    private static void nuclearGeometry() {
        int[] expected = { 9, 21, 37 };
        for (int tier = 1; tier <= 3; tier++) {
            String[][] shape = HologramGTNLShapes.nuclear(tier);
            int width = 2 * tier + 3;
            SimpleAssert.eq(width, shape.length, "reactor depth at tier " + tier);
            int cores = 0, anchors = 0;
            for (int depth = 0; depth < shape.length; depth++) {
                SimpleAssert.eq(5, shape[depth].length, "reactor has five height rows");
                for (int row = 0; row < 5; row++) {
                    SimpleAssert.eq(width, shape[depth][row].length(), "reactor row width");
                    for (int col = 0; col < width; col++) {
                        char symbol = shape[depth][row].charAt(col);
                        if (symbol == '~') anchors++;
                        if (symbol == 'X' || symbol == 'A'
                            || symbol == 'B'
                            || symbol == 'C'
                            || symbol == ' '
                            || symbol == '~') continue;
                        cores++;
                        SimpleAssert.eq(0, row, "core chambers only appear in the top layer");
                        SimpleAssert.that(
                            HologramGTNLShapes.coreCell(tier, depth - 1, col - 1),
                            "core chamber avoids cut corners");
                        SimpleAssert.eq(
                            HologramGTNLShapes.coreChar(depth - 1, col - 1),
                            symbol,
                            "core symbols preserve row and column identity");
                    }
                }
            }
            SimpleAssert.eq(expected[tier - 1], cores, "reactor 9/21/37 chamber oracle");
            SimpleAssert.eq(1, anchors, "one controller anchor");
            SimpleAssert.eq('~', shape[0][2].charAt(width / 2), "controller at depth zero with signed offsets");
        }
        SimpleAssert.that(!HologramGTNLShapes.coreCell(3, 0, 0), "large reactor corner is a seal, not a chamber");
        SimpleAssert.eq(1, HologramGTNLShapes.nuclearTier(-4), "low tier clamps to one");
        SimpleAssert.eq(3, HologramGTNLShapes.nuclearTier(64), "large tier clamps to three");
    }

    private static void channels() {
        ItemStack trigger = new ItemStack(Items.feather, 3);
        SimpleAssert.eq(3, HologramGTNLSemantics.channel(trigger, "casing", 3), "unselected channel uses stack");
        ChannelDataAccessor.setChannelData(trigger, "casing", 1);
        SimpleAssert.eq(1, HologramGTNLSemantics.channel(trigger, "casing", 3), "explicit nuclear channel beats stack");
        ChannelDataAccessor.setChannelData(trigger, "casing", 64);
        SimpleAssert.eq(3, HologramGTNLSemantics.channel(trigger, "casing", 3), "nuclear channel upper bound");
        for (int machine = 1; machine <= 10; machine++) for (int unit = 1; unit <= 5; unit++) {
            ChannelDataAccessor.setChannelData(trigger, "machine_casing", machine);
            ChannelDataAccessor.setChannelData(trigger, "unit_casing", unit);
            SimpleAssert.eq(
                machine,
                HologramGTNLSemantics.channel(trigger, "machine_casing", 10),
                "all ten machine levels retain their independent channel");
            SimpleAssert.eq(
                unit,
                HologramGTNLSemantics.channel(trigger, "unit_casing", 5),
                "all five unit levels retain their independent channel");
        }
        SimpleAssert.eq(3, trigger.stackSize, "queries never mutate the trigger");
        SimpleAssert.that(!HologramGTNLSemantics.supports(new Object()), "no class-name approximation for other mods");
    }

    private static void fixedLeaf() {
        ItemStack core = new ItemStack(Blocks.stone, 1, 18);
        ItemStack alternative = new ItemStack(Blocks.stone, 1, 9);
        HologramGTNLElement element = new HologramGTNLElement(
            "test.core",
            HologramGTNLElement.Kind.BLOCK,
            Arrays.asList(core, alternative),
            Arrays.asList(core),
            true,
            "",
            -1,
            -1,
            -1);
        core.setItemDamage(2);
        SimpleAssert.eq(
            18,
            element.primary()
                .getItemDamage(),
            "material damage remains eighteen after caller edit");
        element.candidates()
            .get(0)
            .setItemDamage(2);
        SimpleAssert.eq(
            18,
            element.primary()
                .getItemDamage(),
            "returned candidates do not expose mutable backing");
        SimpleAssert.that(element.accepts(alternative), "structurally accepted alternative");
        SimpleAssert.that(!element.placementAllows(alternative), "placement subset cannot enlarge structural rules");
        SimpleAssert.that(!element.accepts(core), "damage two cannot stand in for damage eighteen");
        ItemStack sealed = element.primary();
        sealed.setTagCompound(new NBTTagCompound());
        sealed.getTagCompound()
            .setString("unrelated-preserved-data", "retained");
        SimpleAssert.that(element.accepts(sealed), "material acceptance does not destroy preserved NBT");
        HologramCapture.Cell cell = new HologramCapture.Cell(element, 0, 0, 0);
        element.describeCell(cell);
        SimpleAssert.eq(18, cell.blueprintDamage, "saved item damage is independent of world metadata");
        SimpleAssert.eq("block", element.role(), "ordinary material is not a hatch");
        SimpleAssert.that(HologramDeclarationPurity.contextFree(element), "frozen pure leaf may share candidates");
        HologramDeclarationCandidates candidates = new HologramDeclarationCandidates();
        candidates.collect(element, new Object(), null, 0, 0, 0, new ItemStack(Items.feather), null, 0);
        SimpleAssert.eq(2, candidates.stacks.size(), "production collector consumes fixed declarations directly");
        SimpleAssert.that(candidates.complete, "fixed candidate set is complete");
        boolean rejected = false;
        try {
            element.placeBlock(new Object(), null, 0, 0, 0, null);
        } catch (UnsupportedOperationException expected) {
            rejected = true;
        }
        SimpleAssert.that(rejected, "a declaration never executes native world placement");
    }

    private static void codec() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(0x4d425331);
            output.writeInt(2);
            output.writeInt(3);
            output.writeBytes(" J ");
            output.writeInt(3);
            output.writeBytes("KKK");
            output.writeInt(1);
            output.writeInt(2);
            output.writeInt(0);
            output.writeInt(1);
            byte[] resource = bytes.toByteArray();
            String[][] decoded = HologramGTNLShapes.decode(resource, 1, 2, 3);
            SimpleAssert.eq(" J ", decoded[0][0], "spaces stay skip cells in the decoded shape");
            SimpleAssert.eq("KKK", decoded[0][1], "table references preserve resource letters");
            byte[] invalidIndex = resource.clone();
            invalidIndex[invalidIndex.length - 1] = 2;
            reject(invalidIndex, 1, 2, 3);
            byte[] invalidLength = resource.clone();
            invalidLength[11] = 4;
            reject(invalidLength, 1, 2, 3);
            reject(Arrays.copyOf(resource, resource.length - 1), 1, 2, 3);
            reject(Arrays.copyOf(resource, resource.length + 1), 1, 2, 3);
            reject(resource, 33, 2, 3);
            reject(new byte[8193], 1, 2, 3);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void bartWorksMaterial() {
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            Block casing = registeredBartWorksBlock(
                "gtnl_bw_casing",
                (Block) unsafe.allocateInstance(BWMetaGeneratedBlocksCasing.class),
                false,
                unsafe);
            ItemStack material = new ItemStack(casing, 1, 31895);
            HologramResourceWorld world = HologramResourceWorld.create(casing, 31895);
            SimpleAssert.eq(
                0,
                casing.getDamageValue(world, 0, 64, 0),
                "native BW pick has no material identity without its tile");
            SimpleAssert.eq(
                31895,
                HologramPresets.materialDamage(casing, 31895),
                "reviewed native BW item plans the full positive-short material without a tile factory");
            HologramPresets.Cell saved = HologramPresets.cell(material, 0, 0, 0, -1, false);
            saved.metadata = 31895;
            HologramPresets.validateBlueprintCell(saved, true, null);
            SimpleAssert.eq(31895, saved.damage, "saved BW material is never truncated to a nibble");
            expectMaterialFailure(
                () -> HologramPresets.validateMaterialVariant(casing, 31895, new ItemStack(casing, 1, 0)));
            expectMaterialFailure(() -> HologramPresets.materialDamage(casing, 0));
            expectMaterialFailure(() -> HologramPresets.materialDamage(casing, 32768));

            HologramCapture.Cell placed = new HologramCapture.Cell(null, 0, 64, 0);
            placed.block = casing;
            placed.meta = 31895;
            expectMaterialFailure(() -> HologramCellPolicy.validateOrdinaryPlacedVariant(world, placed, material));
            BWTileEntityMetaGeneratedBlocksCasing tile = new BWTileEntityMetaGeneratedBlocksCasing();
            tile.setWorldObj(world);
            tile.xCoord = placed.x;
            tile.yCoord = placed.y;
            tile.zCoord = placed.z;
            world.tile = tile;
            SimpleAssert.that(
                world.getBlock(placed.x, placed.y, placed.z) == casing
                    && Block.getBlockFromItem(material.getItem()) == casing,
                "actual-world fixture and paid ItemBlock bind the same casing at the placed coordinate");
            tile.mMetaData = 31895;
            HologramCellPolicy.validateOrdinaryPlacedVariant(world, placed, material);
            tile.mMetaData = 31766;
            expectMaterialFailure(() -> HologramCellPolicy.validateOrdinaryPlacedVariant(world, placed, material));
            SimpleAssert.eq(
                31766,
                casing.getDamageValue(world, 0, 64, 0),
                "planned identity never replaces the actual world's native TE-aware material read");

            Block unknownBlock = registeredBartWorksBlock(
                "gtnl_bw_unknown_casing",
                (Block) unsafe.allocateInstance(UnreviewedBartWorksCasing.class),
                false,
                unsafe);
            SimpleAssert.eq(
                0,
                HologramMaterialDescriptor.kind(unknownBlock),
                "unreviewed casing subclass does not inherit the planned identity proof");
            expectMaterialFailure(
                () -> HologramPresets
                    .validateMaterialVariant(unknownBlock, 31895, new ItemStack(unknownBlock, 1, 31895)));
            Block unknownItem = registeredBartWorksBlock(
                "gtnl_bw_unknown_item",
                (Block) unsafe.allocateInstance(BWMetaGeneratedBlocksCasing.class),
                true,
                unsafe);
            SimpleAssert.eq(
                0,
                HologramMaterialDescriptor.kind(unknownItem),
                "unreviewed native-item subclass cannot acquire planned identity by inheritance");
            expectMaterialFailure(
                () -> HologramPresets
                    .validateMaterialVariant(unknownItem, 31895, new ItemStack(unknownItem, 1, 31895)));
        } catch (Exception failure) {
            throw new AssertionError("BartWorks planned and actual material boundary", failure);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void savedTemplateReread() {
        String thread = Thread.currentThread()
            .getName();
        Thread.currentThread()
            .setName("Server thread");
        Block previousBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity previousController = GregTechAPI.METATILEENTITIES[RereadController.ID];
        Map<String, Class<?>> loaded = null;
        Class<?> previousClass = null, type = null;
        String name = "com.science.gtnl.common.machine.multiblock.structuralReconstructionPlan.PrecisionAssembler";
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            type = new RereadClasses().define(name);
            // sourceWarning uses the native LaunchClassLoader's class lookup. Restore its one entry afterwards.
            Field classes = net.minecraft.launchwrapper.LaunchClassLoader.class.getDeclaredField("cachedClasses");
            classes.setAccessible(true);
            loaded = (Map<String, Class<?>>) classes.get(net.minecraft.launchwrapper.Launch.classLoader);
            previousClass = loaded.put(name, type);
            GregTechAPI.sBlockMachines = rereadMachineBlock();
            GregTechAPI.METATILEENTITIES[RereadController.ID] = (IMetaTileEntity) type.getConstructor()
                .newInstance();
            HologramGTNLElement shell = new HologramGTNLElement(
                "precision.unit",
                Arrays.asList(
                    new ItemStack(Blocks.stone),
                    new ItemStack(GregTechAPI.sBlockMachines, 1, RereadController.ID)),
                true);
            HologramGTNLElement glass = new HologramGTNLElement(
                "precision.glass",
                Arrays.asList(new ItemStack(Blocks.wool, 1, 0), new ItemStack(Blocks.wool, 1, 14)),
                true);
            IStructureDefinition<Object> definition = StructureDefinition.<Object>builder()
                .addShape("main", new String[][] { { "AB" } })
                .addElement('A', shell)
                .addElement('B', glass)
                .build();
            int[] descriptions = { 0 };
            try (GTMBExtensions.Registration source = StructureSources
                .registerContextual("gtmb:gtnl/precision_assembler", (Class) type, (machine, trigger, intent) -> {
                    descriptions[0]++;
                    NBTTagCompound metadata = new NBTTagCompound();
                    metadata.setInteger("gtnlSchema", 1);
                    metadata.setString("family", "precision_assembler");
                    metadata.setString("sourceCommit", HologramGTNLSemantics.COMMIT);
                    metadata.setString("purpose", intent.name());
                    return StructureSources.Resolution.ready(
                        new StructureSources.Plan(
                            definition,
                            Arrays.asList(new StructureSources.Piece("main", 0, 0, 1, trigger)),
                            metadata),
                        () -> null);
                })) {
                MTEEnhancedMultiBlockBase<?> machine = HologramDetachedController
                    .create(RereadController.ID, ExtendedFacing.NORTH_NORMAL_NONE);
                HologramCapture capture = null;
                for (int batch = 0; batch < 8 && (capture == null || capture.pending); batch++)
                    capture = HologramCapture.collect(
                        machine,
                        new ItemStack(Blocks.stone),
                        ExtendedFacing.NORTH_NORMAL_NONE,
                        StructureSources.CaptureIntent.NEW_TEMPLATE,
                        capture);
                SimpleAssert.that(
                    capture != null && !capture.pending && !capture.incomplete,
                    "the reviewed fixture source is actually captured before saving");
                HologramPresets.Preset preset = rereadPreset(capture, type);
                rereadCase(unsafe, preset, 1, 2, 3, false, "NEW_TEMPLATE", false, false, true);
                rereadCase(unsafe, preset, 2, 2, 3, false, "NEW_TEMPLATE", false, false, false);
                rereadCase(unsafe, preset, 1, 4, 3, false, "NEW_TEMPLATE", false, false, false);
                rereadCase(unsafe, preset, 1, 2, 5, false, "NEW_TEMPLATE", false, false, false);
                rereadCase(unsafe, preset, 1, 2, 3, true, "NEW_TEMPLATE", false, false, false);
                rereadCase(unsafe, preset, 1, 2, 3, false, "LIVE_SNAPSHOT", false, true, false);
                rereadCase(unsafe, preset, 1, 2, 3, false, "NEW_TEMPLATE", true, false, true);
                SimpleAssert.eq(8, descriptions[0], "every saved re-read resolves a new actual source declaration");
            }
        } catch (Exception failure) {
            throw new AssertionError("saved template material re-admission", failure);
        } finally {
            GregTechAPI.sBlockMachines = previousBlock;
            GregTechAPI.METATILEENTITIES[RereadController.ID] = previousController;
            if (loaded != null) {
                if (previousClass == null) loaded.remove(name, type);
                else loaded.put(name, previousClass);
            }
            Thread.currentThread()
                .setName(thread);
        }
    }

    private static HologramPresets.Preset rereadPreset(HologramCapture capture, Class<?> type) throws IOException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.id = "gtnl_reread_fixture";
        preset.name = "Saved physical choices";
        preset.schema = 2;
        preset.sourceClass = type.getName();
        preset.controllerId = RereadController.ID;
        preset.controllerFacing = ExtendedFacing.NORTH_NORMAL_NONE.ordinal();
        preset.semanticNbt = HologramBlueprintNbt.encodeCanonical(capture.sourceMetadata);
        HologramPresets.Structure member = new HologramPresets.Structure();
        member.id = "saved-member";
        member.sourceClass = type.getName();
        member.controllerId = RereadController.ID;
        member.semanticNbt = preset.semanticNbt;
        member.channels.put("machine_casing", 2);
        member.channels.put("unit_casing", 3);
        preset.structures.add(member);
        for (HologramCapture.Cell cell : capture.cells) {
            HologramGTNLElement leaf = HologramGTNLSemantics.reviewedElement(cell.element);
            ItemStack target = cell.anchor || "precision.unit".equals(leaf.id)
                ? new ItemStack(GregTechAPI.sBlockMachines, 1, RereadController.ID)
                : new ItemStack(Blocks.wool, 1, 14);
            HologramPresets.Cell saved = HologramPresets.cell(
                target,
                cell.x,
                cell.y - 128,
                cell.z,
                target.getItem() instanceof ItemMachines ? ForgeDirection.NORTH.ordinal() : -1,
                cell.anchor);
            saved.metadata = target.getItem()
                .getMetadata(target.getItemDamage());
            saved.structureId = member.id;
            saved.structureIds = new java.util.ArrayList<>(Arrays.asList(member.id));
            if (!cell.anchor) saved.allowedTargets = HologramBlueprintData.savedTargets(leaf.candidates());
            if (!cell.anchor && target.getItem() instanceof ItemMachines) {
                NBTTagCompound nativeTile = new NBTTagCompound();
                nativeTile.setString("id", "BaseMetaTileEntity");
                nativeTile.setShort("mID", (short) RereadController.ID);
                nativeTile.setInteger("fixtureConfiguration", 7);
                saved.tileNbt = HologramBlueprintNbt.encodeCanonical(nativeTile);
                saved.prompt = "saved hatch configuration";
            }
            preset.cells.add(saved);
        }
        return preset;
    }

    private static void rereadCase(sun.misc.Unsafe unsafe, HologramPresets.Preset original, int main, int casing,
        int unit, boolean noHatches, String purpose, boolean invalid, boolean oldAir, boolean keep) throws Exception {
        HologramPresets.Preset disk = HologramSnapshotMetadataSource.copyMetadata(original);
        for (HologramPresets.Cell row : original.cells) disk.cells.add(HologramSnapshotMetadataSource.copyCell(row));
        HologramPresets.Cell selected = null;
        for (HologramPresets.Cell row : disk.cells) if ("minecraft:wool".equals(row.item)) selected = row;
        SimpleAssert.that(selected != null, "saved fixture contains a material choice");
        if (invalid) selected.damage = selected.metadata = 12;
        if (oldAir) {
            selected.item = null;
            selected.block = "minecraft:air";
            selected.damage = selected.metadata = 0;
        }
        NBTTagCompound oldMetadata = HologramBlueprintNbt.decode(disk.semanticNbt);
        oldMetadata.setString("purpose", purpose);
        disk.semanticNbt = HologramBlueprintNbt.encodeCanonical(oldMetadata);
        disk.structures.get(0).semanticNbt = disk.semanticNbt;
        byte[] encoded = HologramBlueprintCompactCodec.encode(disk, (done, total) -> {});
        disk = new com.google.gson.Gson().fromJson(
            HologramBlueprintCompactCodec.parse(encoded)
                .getAsJsonObject("blueprint"),
            HologramPresets.Preset.class);
        HologramPresets.validate(disk);
        EntityPlayerMP player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
        player.worldObj = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        player.inventory = new InventoryPlayer(player);
        player.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
        player.capabilities = new PlayerCapabilities();
        player.capabilities.allowEdit = true;
        HologramSession session = new HologramSession(player, 20, 64, 0, false, 2);
        session.virtual = true;
        session.virtualPreset = disk;
        HologramBlueprintProjection.Population population = new HologramBlueprintProjection.Population(session);
        SimpleAssert.that(
            population.step(32, Long.MAX_VALUE) && !session.capture.incomplete,
            "the real saved blueprint population retains its physical targets");
        HologramPresets.Structure source = disk.structures.get(0);
        session.blueprintActiveStructure = source.id;
        HologramBlueprintData.applyStructureConfiguration(session, source);
        NBTTagCompound channels = new NBTTagCompound();
        channels.setInteger("machine_casing", casing);
        channels.setInteger("unit_casing", unit);
        HologramVirtualStructureEditing.Task task = new HologramVirtualStructureEditing.Task(
            session,
            source,
            main,
            channels,
            noHatches);
        session.main = main;
        session.channels = channels;
        session.noHatches = noHatches;
        HologramBlueprintData.storeStructureConfiguration(session, source);
        int batches = 0;
        while (!task.step() && batches++ < 8) {}
        SimpleAssert.that(batches < 8, "saved re-declaration finishes within eight bounded slices");
        task.publish();
        SimpleAssert.that(session.capture.sourceCurrent(), "re-read retains the newly captured source validity proof");
        HologramCapture.Cell cell = session.capture.positions
            .get((session.x + selected.x) + "," + (session.y + selected.y) + "," + (session.z + selected.z));
        int index = HologramSessionPolicy.cellIndex(session, cell);
        ItemStack target = HologramCellPolicy.draftTarget(session, cell, index);
        SimpleAssert.eq(
            keep ? invalid ? 12 : 14 : 0,
            target.getItemDamage(),
            "only same-purpose and same-parameter re-reading retains the frozen material choice");
        SimpleAssert.that(
            cell.element instanceof HologramGTNLElement,
            "a retained saved target receives the fresh reviewed leaf");
        SimpleAssert.eq(
            !invalid,
            HologramCellPolicy.acceptableSample(session, cell, target),
            "an invalid saved material stays visible and is rejected by the fresh declaration");
        if (keep) {
            HologramPresets.Cell retained = session.virtualPreset.cells.stream()
                .filter(row -> !row.controller && row.tileNbt != null && !row.tileNbt.isEmpty())
                .findFirst()
                .get();
            SimpleAssert.eq(
                7,
                HologramBlueprintNbt.decode(retained.tileNbt)
                    .getInteger("fixtureConfiguration"),
                "same-template re-reading retains the saved native configuration");
            SimpleAssert.eq(
                ForgeDirection.NORTH.ordinal(),
                retained.facing,
                "same-template re-reading retains the saved hatch-facing row");
        }
    }

    private static Block rereadMachineBlock() {
        String registry = "gtmb:gtnl_reread_machines";
        Object existing = Block.blockRegistry.getObject(registry);
        if (existing instanceof Block && existing != Blocks.air) return (Block) existing;
        Block block = new Block(Material.iron) {};
        int slot = 3970;
        while (Item.getItemById(slot) != null || Block.getBlockById(slot) != Blocks.air) slot--;
        Block.blockRegistry.addObject(slot, registry, block);
        Item.itemRegistry.addObject(slot, registry, new ItemMachines(block));
        return block;
    }

    public static class RereadController extends MTEEnhancedMultiBlockBase<RereadController> {

        static final int ID = 31960;

        public RereadController() {
            super("gtnl_reread_fixture");
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity tile) {
            try {
                return (IMetaTileEntity) getClass().getConstructor()
                    .newInstance();
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError(failure);
            }
        }

        @Override
        public ItemStack getStackForm(long count) {
            return new ItemStack(GregTechAPI.sBlockMachines, (int) count, ID);
        }

        @Override
        public IStructureDefinition<RereadController> getStructureDefinition() {
            throw new AssertionError("registered pure source must bypass native definitions");
        }

        @Override
        public void construct(ItemStack trigger, boolean hints) {
            throw new AssertionError("registered pure source must bypass native construct");
        }

        @Override
        protected MultiblockTooltipBuilder createTooltip() {
            throw new AssertionError("no native tooltip");
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity tile, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }
    }

    private static final class RereadClasses extends ClassLoader {

        RereadClasses() {
            super(HologramGTNLRegression.class.getClassLoader());
        }

        Class<?> define(String name) {
            String parent = RereadController.class.getName()
                .replace('.', '/');
            ClassWriter writer = new ClassWriter(0);
            writer.visit(
                Opcodes.V1_8,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                name.replace('.', '/'),
                null,
                parent,
                null);
            MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(1, 1);
            constructor.visitEnd();
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static Block registeredBartWorksBlock(String name, Block block, boolean unknownItem, sun.misc.Unsafe unsafe)
        throws Exception {
        String registry = "gtmb:" + name;
        Object existing = Block.blockRegistry.getObject(registry);
        if (existing instanceof Block && existing != Blocks.air) return (Block) existing;
        Field delegate = Block.class.getDeclaredField("delegate");
        delegate.setAccessible(true);
        delegate.set(
            block,
            ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Block.blockRegistry)
                .getDelegate(block, Block.class));
        int slot = 3980;
        while (slot > 0 && (Item.getItemById(slot) != null || Block.getBlockById(slot) != Blocks.air)) slot--;
        if (slot == 0) throw new AssertionError("No isolated BartWorks fixture registry slot");
        Block.blockRegistry.addObject(slot, registry, block);
        // Skip creative-tab/logger initialization; only these audited native identity methods are under test.
        Item material = (Item) unsafe
            .allocateInstance(unknownItem ? UnreviewedBartWorksItem.class : BWItemMetaGeneratedBlock.class);
        Field blockField = ItemBlock.class.getDeclaredField("field_150939_a");
        blockField.setAccessible(true);
        blockField.set(material, block);
        Field itemDelegate = Item.class.getDeclaredField("delegate");
        itemDelegate.setAccessible(true);
        itemDelegate.set(
            material,
            ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Item.itemRegistry)
                .getDelegate(material, Item.class));
        material.setHasSubtypes(true);
        material.setMaxStackSize(64);
        Item.itemRegistry.addObject(slot, registry, material);
        return block;
    }

    private static void expectMaterialFailure(MaterialCheck check) throws Exception {
        try {
            check.run();
            throw new AssertionError("unpaid or unreviewed BW material identity was accepted");
        } catch (IOException expected) {}
    }

    private interface MaterialCheck {

        void run() throws Exception;
    }

    private static final class UnreviewedBartWorksCasing extends BWMetaGeneratedBlocksCasing {

        private UnreviewedBartWorksCasing() {
            super(Material.iron, BWTileEntityMetaGeneratedBlocksCasing.class, "unused", OrePrefixes.blockCasing);
        }
    }

    private static final class UnreviewedBartWorksItem extends BWItemMetaGeneratedBlock {

        private UnreviewedBartWorksItem(Block block) {
            super(block);
        }
    }

    private static void reject(byte[] input, int height, int depth, int width) {
        try {
            HologramGTNLShapes.decode(input, height, depth, width);
            throw new AssertionError("unsafe resource was accepted");
        } catch (IOException expected) {}
    }

    private static void sphere() {
        try (InputStream input = HologramGTNLRegression.class.getResourceAsStream("/hologram/gtnl-rc21-sphere.mbs")) {
            if (input == null) throw new AssertionError("Missing pinned sphere fixture");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            for (int n; (n = input.read(buffer)) != -1;) bytes.write(buffer, 0, n);
            SimpleAssert.eq(632, bytes.size(), "pinned source resource byte count");
            String[][] shape = HologramGTNLShapes.decode(bytes.toByteArray(), 9, 9, 9);
            int j = 0, k = 0, skip = 0;
            for (String[] row : shape) for (String line : row) for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == 'J') j++;
                else if (line.charAt(i) == 'K') k++;
                else if (line.charAt(i) == ' ') skip++;
                else throw new AssertionError("Unexpected sphere symbol");
            }
            SimpleAssert.eq(52, j, "sphere J material count");
            SimpleAssert.eq(135, k, "sphere K material count");
            SimpleAssert.eq(542, skip, "empty resource cells remain skip, not air constraints");
            SimpleAssert.eq('J', shape[0][2].charAt(4), "fixed asymmetric sphere coordinate");
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }
}
