package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual legacy callbacks on the owner, returned deltas and original-source atomicity; no Session/world setup. */
final class HologramAuthorNativeReviewRegression {

    private static final Gson GSON = new Gson();

    private HologramAuthorNativeReviewRegression() {}

    static void run() throws Exception {
        HologramWorkBudget.startServerTick();
        HologramWorkBudget.endServerTick();
        structures();
        candidatesAndConfigurations();
    }

    private static void structures() throws Exception {
        int id = 32751;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[id];
        String name = Thread.currentThread()
            .getName();
        HologramDynamicDefinitionRegression.DynamicController prototype;
        try {
            Thread.currentThread()
                .setName("Server thread");
            prototype = new HologramDynamicDefinitionRegression.DynamicController();
        } finally {
            Thread.currentThread()
                .setName(name);
        }
        gregtech.api.GregTechAPI.METATILEENTITIES[id] = prototype;
        AtomicInteger sources = new AtomicInteger(), captures = new AtomicInteger();
        Thread owner = Thread.currentThread();
        try (GTMBExtensions.Registration source = StructureSources.register(
            "gtmb_test:review-native-source",
            HologramDynamicDefinitionRegression.DynamicController.class,
            (controller, trigger) -> {
                SimpleAssert.that(Thread.currentThread() == owner, "legacy describe runs on verified owner");
                SimpleAssert.that(controller != prototype, "source gets a genuine independent native controller");
                sources.incrementAndGet();
                if (trigger.stackSize == 3) throw new IllegalStateException("fixture declaration rejection");
                String shape = trigger.stackSize == 1 ? "AA" : "AAAA";
                return new StructureSources.Plan(
                    StructureDefinition.<HologramDynamicDefinitionRegression.DynamicController>builder()
                        .addShape("review", new String[][] { { shape } })
                        .addElement('A', StructureUtility.ofBlock(Blocks.stone, 0))
                        .build(),
                    Collections.singletonList(new StructureSources.Piece("review", 0, 0, 0, trigger)));
            });
            GTMBExtensions.Registration capture = BlueprintSnapshots
                .register("gtmb_test:review-not-capture", BlueprintSnapshots.Stage.CAPTURE_COMPLETE, snapshot -> {
                    captures.incrementAndGet();
                    return null;
                })) {
            AuthorDocument frozen = structureDocument(id)
                .withSourceFrame(AuthorDocument.SourceFrame.captured(7, 100, 64, 300));
            String exact = frozen.sourceCopy()
                .toString();
            NBTTagCompound request = structureRequest(2);
            try (HologramLocalAuthorStructures local = new HologramLocalAuthorStructures(
                frozen,
                "selected",
                2,
                new NBTTagCompound(),
                false,
                new ItemStack(Blocks.stone))) {
                try {
                    for (int i = 0; i < 20 && !local.done(); i++) local.tick();
                    throw new AssertionError("local legacy source was not deferred");
                } catch (HologramExtensionExecution.ReviewRequired expected) {}
            }
            SimpleAssert.eq(0, sources.get(), "client attempt calls no legacy source");
            HologramAuthorServerReview.Result accepted;
            int slices = 0;
            try (HologramAuthorServerReview.StructureTask task = HologramAuthorServerReview
                .startStructure(frozen, "structure", request, new ItemStack(Blocks.stone))) {
                while (!task.step() && slices++ < 10000) {}
                SimpleAssert.that(slices > 1 && slices < 10000, "server declaration reuses cooperative slices");
                accepted = task.result();
            }
            SimpleAssert.eq(2, sources.get(), "one baseline and one requested declaration callback");
            SimpleAssert.eq(0, captures.get(), "server synthetic review still never masquerades as capture");
            SimpleAssert.eq(
                exact,
                frozen.sourceCopy()
                    .toString(),
                "server structure result does not mutate original");
            SimpleAssert.eq(
                "opaque-group",
                accepted.source.getAsJsonObject("group")
                    .get("futureGroup")
                    .getAsString(),
                "group extension remains lossless");
            AuthorEditor editor = new AuthorEditor(frozen, 8);
            List<JsonObject> cells = new ArrayList<>();
            for (JsonElement cell : accepted.source.getAsJsonArray("cells")) cells.add(cell.getAsJsonObject());
            editor.replaceStructure(
                frozen.revision(),
                accepted.source.getAsJsonObject("group"),
                cells,
                accepted.source.getAsJsonObject("headerPatch"));
            SimpleAssert.eq(
                frozen.sourceFrame(),
                editor.current()
                    .sourceFrame(),
                "structure delta preserves captured frame");
            SimpleAssert.eq(
                "opaque-neighbor",
                editor.current()
                    .cellAt(10000, 0, 0)
                    .sourceCopy()
                    .get("futureCell")
                    .getAsString(),
                "unrelated source survives reviewed structure replacement");
            SimpleAssert.that(
                editor.current()
                    .cellCount() > frozen.cellCount(),
                "reviewed declared additions apply");
            try (HologramAuthorServerReview.StructureTask task = HologramAuthorServerReview
                .startStructure(frozen, "structureInspect", request, new ItemStack(Blocks.stone))) {
                int work = 0;
                while (!task.step() && work++ < 10000) {}
                SimpleAssert.that(task.result().source == null, "capability inspection has no source edit delta");
            }
            int beforeCancelled = sources.get();
            HologramAuthorServerReview.StructureTask cancelled = HologramAuthorServerReview
                .startStructure(frozen, "structure", request, new ItemStack(Blocks.stone));
            cancelled.close();
            try {
                cancelled.step();
                throw new AssertionError("cancelled declaration resumed");
            } catch (IOException expected) {}
            SimpleAssert.eq(beforeCancelled, sources.get(), "cancel before continuation invokes no callback");
            try (HologramAuthorServerReview.StructureTask rejected = HologramAuthorServerReview
                .startStructure(frozen, "structure", structureRequest(3), new ItemStack(Blocks.stone))) {
                try {
                    int work = 0;
                    while (!rejected.step() && work++ < 10000) {}
                    throw new AssertionError("legacy declaration rejection ignored");
                } catch (IOException expected) {}
            }
            SimpleAssert.eq(
                exact,
                frozen.sourceCopy()
                    .toString(),
                "rejected rebuild retains exact original source");
            SimpleAssert.eq(0, prototype.constructions, "review never mutates registered prototype");
            SimpleAssert.eq(0, captures.get(), "no synthetic completion callback even after review/rejection");
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[id] = previous;
        }
    }

    private static AuthorDocument structureDocument(int controllerId) throws IOException {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.schema = 2;
        source.id = "native_review";
        source.name = "native review";
        source.controllerType = "area";
        HologramPresets.Structure group = new HologramPresets.Structure();
        group.id = "selected";
        group.main = 1;
        group.controllerId = controllerId;
        group.sourceClass = HologramDynamicDefinitionRegression.DynamicController.class.getName();
        source.structures.add(group);
        HologramPresets.Cell controller = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, 2, true);
        controller.block = "minecraft:stone";
        controller.structureId = group.id;
        controller.structureIds = Collections.singletonList(group.id);
        source.cells.add(controller);
        HologramPresets.Cell neighbor = HologramPresets.cell(new ItemStack(Blocks.stone), 10000, 0, 0, -1, false);
        neighbor.block = "minecraft:stone";
        source.cells.add(neighbor);
        JsonObject raw = GSON.toJsonTree(source)
            .getAsJsonObject();
        raw.getAsJsonArray("structures")
            .get(0)
            .getAsJsonObject()
            .addProperty("futureGroup", "opaque-group");
        raw.getAsJsonArray("cells")
            .get(1)
            .getAsJsonObject()
            .addProperty("futureCell", "opaque-neighbor");
        return AuthorDocumentIO.readExpanded(raw, AuthorDocumentIO.Limits.defaults());
    }

    private static NBTTagCompound structureRequest(int main) {
        NBTTagCompound request = new NBTTagCompound();
        request.setString("structureId", "selected");
        request.setInteger("main", main);
        request.setTag("channels", new NBTTagCompound());
        return request;
    }

    private static HologramAuthorServerReview.Result configure(AuthorDocument frozen, NBTTagCompound request)
        throws IOException {
        try (HologramAuthorServerReview.ConfigurationTask task = HologramAuthorServerReview
            .startConfiguration(frozen, request)) {
            int slices = 0;
            while (!task.step() && slices++ < 10000) {}
            SimpleAssert
                .that(slices > 1 && slices < 10000, "configuration review yields between validation and native work");
            return task.result();
        }
    }

    private static void candidatesAndConfigurations() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        Thread owner = Thread.currentThread();
        try (GTMBExtensions.Registration nativeAdapter = NativePlacement
            .register("gtmb_test:review-native-config", new NativePlacement.Adapter() {

                public boolean supports(Block block) {
                    SimpleAssert.that(Thread.currentThread() == owner, "candidate applicability stays on owner");
                    callbacks.incrementAndGet();
                    return block == Blocks.stone;
                }

                public TileEntity create(Block block, int metadata, NBTTagCompound tag, World world) {
                    callbacks.incrementAndGet();
                    return new TileEntity();
                }

                public List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
                    callbacks.incrementAndGet();
                    return Arrays.asList(new ItemStack(Blocks.stone), new ItemStack(Blocks.dirt));
                }
            })) {
            JsonObject source = new JsonObject();
            source.addProperty("schema", 2);
            source.addProperty("name", "config review");
            JsonArray rows = new JsonArray();
            JsonObject cell = new JsonObject();
            cell.addProperty("x", 0);
            cell.addProperty("y", 0);
            cell.addProperty("z", 0);
            cell.addProperty("block", "minecraft:stone");
            cell.addProperty("item", "minecraft:stone");
            cell.addProperty("futureCell", "opaque");
            NBTTagCompound tile = new NBTTagCompound();
            tile.setString("id", "review_fixture");
            cell.addProperty("tileNbt", HologramBlueprintNbt.encodeCaptured(tile));
            rows.add(cell);
            source.add("cells", rows);
            AuthorDocument frozen = AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
            String exact = frozen.sourceCopy()
                .toString();
            try {
                HologramLocalAuthorConfigurations.candidates(cell);
                throw new AssertionError("client native query ran");
            } catch (HologramExtensionExecution.ReviewRequired expected) {}
            SimpleAssert.eq(0, callbacks.get(), "deferred native query invokes zero callbacks locally");
            NBTTagCompound candidates = HologramAuthorServerReview.reviewCandidates(cell);
            SimpleAssert.that(
                candidates.getTagList("candidates", 10)
                    .tagCount() > 0 && callbacks.get() > 0,
                "server candidate query actually executes native applicability/create/materials");
            NBTTagCompound request = new NBTTagCompound();
            request.setString("operation", "customPin");
            NBTTagList selected = new NBTTagList();
            NBTTagCompound identity = new NBTTagCompound();
            identity.setLong("cellId", 1L);
            selected.appendTag(identity);
            request.setTag("selectedCells", selected);
            request.setTag("sample", new ItemStack(Blocks.dirt).writeToNBT(new NBTTagCompound()));
            HologramAuthorServerReview.Result result = configure(frozen, request);
            JsonArray replacements = result.source.getAsJsonArray("replacements");
            SimpleAssert.eq(1, replacements.size(), "selected native target change returns one actual delta");
            JsonObject changed = replacements.get(0)
                .getAsJsonObject()
                .getAsJsonObject("cell");
            SimpleAssert.eq(
                "minecraft:dirt",
                changed.get("item")
                    .getAsString(),
                "native accepted target returns requested sample");
            SimpleAssert.eq(
                "opaque",
                changed.get("futureCell")
                    .getAsString(),
                "native config preserves unknown fields");
            Map<Long, JsonObject> patch = new LinkedHashMap<>();
            patch.put(1L, changed);
            AuthorEditor editor = new AuthorEditor(frozen, 8);
            editor.replaceCells(patch, result.source.getAsJsonObject("headerPatch"));
            SimpleAssert.eq(
                "minecraft:dirt",
                editor.current()
                    .cell(1L)
                    .item(),
                "returned native delta applies through existing editor");
            JsonObject manySource = new com.google.gson.JsonParser().parse(source.toString())
                .getAsJsonObject();
            JsonArray manyRows = new JsonArray();
            NBTTagList manySelected = new NBTTagList();
            for (int i = 0; i < 17; i++) {
                JsonObject next = new com.google.gson.JsonParser().parse(cell.toString())
                    .getAsJsonObject();
                next.addProperty("x", i);
                manyRows.add(next);
                NBTTagCompound chosen = new NBTTagCompound();
                chosen.setLong("cellId", i + 1L);
                manySelected.appendTag(chosen);
            }
            manySource.add("cells", manyRows);
            AuthorDocument many = AuthorDocumentIO.readExpanded(manySource, AuthorDocumentIO.Limits.defaults());
            String beforeMany = many.sourceCopy()
                .toString();
            NBTTagCompound multi = (NBTTagCompound) request.copy();
            multi.setTag("selectedCells", manySelected);
            HologramAuthorServerReview.ConfigurationTask cancelled = HologramAuthorServerReview
                .startConfiguration(many, multi);
            int beforeNativeSlice = callbacks.get(), pendingSlices = 0;
            while (callbacks.get() == beforeNativeSlice && pendingSlices++ < 10000) SimpleAssert
                .that(!cancelled.step(), "more than eight native configurations cannot finish in first native slice");
            SimpleAssert
                .that(callbacks.get() > beforeNativeSlice, "cancellation fixture reaches actual partial native work");
            try {
                cancelled.result();
                throw new AssertionError("partial selected configuration published");
            } catch (IOException expected) {}
            cancelled.close();
            int afterCancellation = callbacks.get();
            try {
                cancelled.step();
                throw new AssertionError("cancelled native config resumed");
            } catch (IOException expected) {}
            SimpleAssert
                .eq(afterCancellation, callbacks.get(), "cancelled config cannot invoke another native callback");
            SimpleAssert.eq(
                beforeMany,
                many.sourceCopy()
                    .toString(),
                "partial config cancellation retains every source record");
            NBTTagCompound staleChoice = (NBTTagCompound) request.copy();
            staleChoice.setString("operation", "pin");
            staleChoice.setLong("primaryCellId", 1L);
            staleChoice.setInteger("choice", 0);
            staleChoice.setTag("expectedCandidate", new ItemStack(Blocks.glass).writeToNBT(new NBTTagCompound()));
            try {
                configure(frozen, staleChoice);
                throw new AssertionError("stale candidate identity accepted");
            } catch (IOException expected) {}
            request.setTag("sample", new ItemStack(Blocks.glass).writeToNBT(new NBTTagCompound()));
            try {
                configure(frozen, request);
                throw new AssertionError("invalid native sample admitted");
            } catch (IOException expected) {}
            SimpleAssert.eq(
                exact,
                frozen.sourceCopy()
                    .toString(),
                "rejected native target retains exact source");
        }
    }
}
