// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.StructureLib;
import com.gtnewhorizon.structurelib.StructureEvent.StructureElementVisitedEvent;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.constructable.IMultiblockInfoContainer;
import com.gtnewhorizon.structurelib.alignment.constructable.ISurvivalConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.ISurvivalBuildEnvironment;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramPresets.Cell;
import com.miaokatze.gtmb.hologram.HologramPresets.HintArea;
import com.miaokatze.gtmb.hologram.HologramPresets.Preset;
import com.miaokatze.gtmb.hologram.HologramPresets.Structure;
import com.miaokatze.gtmb.testutil.SimpleAssert;

final class HologramLocalBlueprintStructureRegression {

    private HologramLocalBlueprintStructureRegression() {}

    static void run() {
        String type = TileEntityChest.class.getCanonicalName();
        IMultiblockInfoContainer<?> previous = IMultiblockInfoContainer.MULTIBLOCK_MAP.get(type);
        try {
            Field trigger = StructureLib.class.getDeclaredField("itemConstructableTrigger");
            trigger.setAccessible(true);
            Object item = trigger.get(null);
            try {
                trigger.set(null, Items.stick);
                IMultiblockInfoContainer.registerTileClass(TileEntityChest.class, new Source());
                checkBlueprint();
            } finally {
                trigger.set(null, item);
                if (previous == null) IMultiblockInfoContainer.MULTIBLOCK_MAP.remove(type);
                else IMultiblockInfoContainer.MULTIBLOCK_MAP.put(type, previous);
            }
        } catch (ReflectiveOperationException | IOException failure) {
            throw new AssertionError("file-owned StructureLib blueprint", failure);
        }
    }

    private static void checkBlueprint() throws IOException {
        Preset preset = new Preset();
        preset.name = "Detached structure";
        preset.controllerType = "area";
        Structure source = new Structure();
        source.id = "native";
        source.sourceClass = TileEntityChest.class.getName();
        preset.structures.add(source);
        Structure other = new Structure();
        other.id = "other";
        preset.structures.add(other);
        HintArea note = new HintArea();
        note.prompt = "region markup";
        preset.hintAreas.add(note);

        Cell anchor = cell(Blocks.chest, 0, "native");
        anchor.controller = true;
        anchor.facing = ForgeDirection.NORTH.ordinal();
        Cell annotated = cell(Blocks.stone, 2, "native");
        annotated.prompt = "keep this markup";
        annotated.excluded = true;
        Cell shared = cell(Blocks.stone, 3, "native");
        shared.structureIds = Arrays.asList("native", "other");
        Cell custom = cell(Blocks.dirt, 4, "native");
        Cell ownOnly = cell(Blocks.stone, 5, "native");
        Cell free = cell(Blocks.obsidian, 7, null);
        Cell sourced = cell(Blocks.stone, 1, "native");
        sourced.allowedTargets = HologramBlueprintData.savedTargets(Collections.singletonList(new ItemStack(Blocks.stone)));
        preset.cells.addAll(Arrays.asList(anchor, sourced, annotated, shared, custom, ownOnly, free));

        JsonObject originalFile = expanded(HologramLocalBlueprintDocument.fromCapture(preset).artifact());
        JsonObject markup = new JsonObject();
        markup.addProperty("note", "portable extension");
        jsonCell(originalFile, 1).add("futureMarkup", markup);
        HologramLocalBlueprintDocument document = HologramLocalBlueprintDocument.open(
            HologramBlueprintCompactCodec.encodeLocalDocument(originalFile));
        NBTTagCompound select = new NBTTagCompound();
        select.setString("structureId", "native");
        apply(document, "selectStructure", select);
        NBTTagCompound header = document.snapshotMetadata();
        SimpleAssert.eq("native", header.getString("activeStructure"), "native group is selected");
        SimpleAssert.eq(1, header.getInteger("main"), "the saved main signal is published");
        SimpleAssert.eq(3, capability(header, "span").getTagList("options", 10).tagCount(),
            "the source definition provides two options and reset");
        SimpleAssert.eq(0L, document.revision(), "selecting a structure does not change the file");

        NBTTagCompound configure = new NBTTagCompound(), channels = new NBTTagCompound();
        channels.setInteger("span", 2);
        configure.setString("structureId", "native");
        configure.setInteger("main", 2);
        configure.setTag("channels", channels);
        apply(document, "configure", configure);
        NBTTagCompound changed = document.snapshot();
        SimpleAssert.eq(2, changed.getCompoundTag("channels").getInteger("span"), "saved named signal was changed");
        SimpleAssert.eq(2, capability(changed, "span").getInteger("current"), "effective signal was refreshed");
        SimpleAssert.eq(1L, document.revision(), "native refresh is one undoable edit");
        SimpleAssert.eq("minecraft:sandstone", row(changed, 1).getString("wantId"), "default material follows the source");
        SimpleAssert.eq("keep this markup", row(changed, 2).getString("prompt"), "retired position keeps markup");
        SimpleAssert.that(row(changed, 2).getBoolean("excluded") && row(changed, 2).getString("structureId").isEmpty(),
            "annotated retired row is preserved as a free row");
        SimpleAssert.eq("other", row(changed, 3).getString("structureId"), "retired shared cell keeps its other owner");
        SimpleAssert.eq("minecraft:dirt", row(changed, 4).getString("wantId"), "explicit material survives a channel edit");
        SimpleAssert.that(row(changed, 5) == null, "only untouched obsolete geometry is removed");
        SimpleAssert.eq("minecraft:glass", row(changed, 6).getString("wantId"), "native expansion adds its new cell");
        SimpleAssert.eq("minecraft:obsidian", row(changed, 7).getString("wantId"), "free occupied position protects its edit");
        SimpleAssert.eq(1, changed.getTagList("hintAreas", 10).tagCount(), "region markup remains intact");

        byte[] refreshedFile = document.artifact();
        HologramLocalBlueprintDocument reopened = HologramLocalBlueprintDocument.open(refreshedFile);
        reopened.apply("selectStructure", select);
        NBTTagCompound saved = reopened.snapshot();
        SimpleAssert.eq(2, saved.getInteger("main"), "the reopened file retains the new main signal");
        SimpleAssert.eq(2, saved.getCompoundTag("channels").getInteger("span"), "the reopened file retains the named signal");
        NBTTagList candidates = row(saved, 1).getTagList("candidates", 10);
        SimpleAssert.eq(1, candidates.tagCount(), "the reopened row has its refreshed target list");
        SimpleAssert.that(new ItemStack(Blocks.sandstone).isItemEqual(
            ItemStack.loadItemStackFromNBT(candidates.getCompoundTagAt(0))),
            "old opaque target data cannot shadow the new semantic target");
        SimpleAssert.eq(markup, jsonCell(expanded(refreshedFile), 1).get("futureMarkup"),
            "native target replacement retains other opaque row markup");

        channels.setInteger("span", 3);
        try {
            apply(document, "configure", configure);
            throw new AssertionError("Expected native failure");
        } catch (IOException expected) {
            SimpleAssert.eq(1L, document.revision(), "failed upstream configure leaves the file unchanged");
            SimpleAssert.eq(2, document.snapshotMetadata().getCompoundTag("channels").getInteger("span"),
                "failed upstream configure retains original settings");
        }
        document.apply("editUndo", new NBTTagCompound());
        SimpleAssert.eq("minecraft:stone", row(document.snapshot(), 1).getString("wantId"), "undo restores the old geometry");
        SimpleAssert.eq(0, document.snapshotMetadata().getCompoundTag("channels").getInteger("span"),
            "undo restores the original named signal");
        SimpleAssert.eq(3, capability(document.snapshotMetadata(), "span").getTagList("options", 10).tagCount(),
            "undo retains channel options for the restored source");
    }

    private static void apply(HologramLocalBlueprintDocument document, String operation, NBTTagCompound request)
        throws IOException {
        try (var task = document.beginStructureCommand(operation, request)) {
            SimpleAssert.that(task != null, "operation starts its native client task");
            for (int step = 0; step < 32; step++) {
                if (!task.step(2, 5_000_000L)) continue;
                task.commit(() -> true);
                return;
            }
            throw new AssertionError("Native preview did not finish");
        }
    }

    private static Cell cell(Block block, int x, String structure) {
        Cell cell = HologramPresets.cell(new ItemStack(block), x, 0, 0, -1, false);
        cell.block = Block.blockRegistry.getNameForObject(block);
        cell.structureId = structure;
        return cell;
    }

    private static NBTTagCompound capability(NBTTagCompound state, String id) {
        NBTTagList options = state.getTagList("capabilities", 10);
        for (int index = 0; index < options.tagCount(); index++) {
            NBTTagCompound row = options.getCompoundTagAt(index);
            if (id.equals(row.getString("id"))) return row;
        }
        throw new AssertionError("Missing channel " + id);
    }

    private static NBTTagCompound row(NBTTagCompound state, int x) {
        NBTTagList cells = state.getTagList("cells", 10);
        for (int index = 0; index < cells.tagCount(); index++) {
            NBTTagCompound row = cells.getCompoundTagAt(index);
            if (row.getInteger("dx") == x) return row;
        }
        return null;
    }

    private static JsonObject expanded(byte[] bytes) throws IOException {
        return HologramBlueprintCompactCodec.parse(
            new String(bytes, StandardCharsets.UTF_8), HologramBlueprintArtifact.MAX_JSON_BYTES);
    }

    private static JsonObject jsonCell(JsonObject root, int x) throws IOException {
        for (var candidate : HologramBlueprintCompactCodec.documentBlueprint(root).getAsJsonArray("cells")) {
            JsonObject row = candidate.getAsJsonObject();
            if (row.get("x").getAsInt() == x) return row;
        }
        throw new AssertionError("Missing file cell " + x);
    }

    private static final class Source implements IMultiblockInfoContainer<TileEntityChest> {

        @Override
        public void construct(ItemStack trigger, boolean hints, TileEntityChest tile, ExtendedFacing facing) {
            SimpleAssert.that(!hints, "file structure uses the actual StructureCompat fallback");
            int tier = ChannelDataAccessor.hasSubChannel(trigger, "span") ? ChannelDataAccessor.getChannelData(trigger, "span")
                : 1;
            if (tier == 3) throw new IllegalStateException("fixture upstream could not construct");
            World world = tile.getWorldObj();
            Block casing = tier == 1 ? Blocks.stone : Blocks.sandstone;
            visit(world, StructureUtility.ofBlock(casing, 0));
            world.setBlock(1, 128, 0, casing, 0, 2);
            world.setBlock(4, 128, 0, tier == 1 ? Blocks.stone : Blocks.sandstone, 0, 2);
            if (tier == 1) {
                for (int x : new int[] { 2, 3, 5 }) world.setBlock(x, 128, 0, Blocks.stone, 0, 2);
            } else {
                for (int x : new int[] { 6, 7 }) world.setBlock(x, 128, 0, Blocks.glass, 0, 2);
            }
        }

        private static void visit(World world, IStructureElement<?> element) {
            try {
                Constructor<StructureElementVisitedEvent> constructor = StructureElementVisitedEvent.class
                    .getDeclaredConstructor(World.class, int.class, int.class, int.class, int.class, int.class,
                        int.class, IStructureElement.class);
                constructor.setAccessible(true);
                new HologramNativeStructurePreview.VisitedListener()
                    .elementVisited(constructor.newInstance(world, 1, 128, 0, 1, 0, 0, element));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Fixture StructureLib visited event", failure);
            }
        }

        @Override
        public int survivalConstruct(ItemStack trigger, int budget, ISurvivalBuildEnvironment environment,
            TileEntityChest tile, ExtendedFacing facing) {
            return -2;
        }

        @Override
        public String[] getDescription(ItemStack trigger) {
            return new String[] { "file structure fixture" };
        }

        @Override
        public ISurvivalConstructable toConstructable(TileEntityChest tile, ExtendedFacing facing) {
            return new ISurvivalConstructable() {

                @Override
                public int survivalConstruct(ItemStack trigger, int budget, ISurvivalBuildEnvironment environment) {
                    return Source.this.survivalConstruct(trigger, budget, environment, tile, facing);
                }

                @Override
                public void construct(ItemStack trigger, boolean hintsOnly) {
                    Source.this.construct(trigger, hintsOnly, tile, facing);
                }

                @Override
                public String[] getStructureDescription(ItemStack trigger) {
                    return getDescription(trigger);
                }

                @Override
                public IStructureDefinition<?> getStructureDefinition() {
                    return StructureDefinition.<TileEntityChest>builder().addChannel("span", 1, 2).build();
                }
            };
        }
    }
}
