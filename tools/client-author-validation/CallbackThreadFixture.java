package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.api.BlueprintSnapshots;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Controlled caller-thread evidence only. Does not create a game, player, Session or ACK. */
public final class CallbackThreadFixture {
    private static final Gson GSON = new Gson();
    private static String action;
    private static int snapshotCalls, codecCalls, createCalls, materialCalls, captureCalls, sourceCalls;
    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(CallbackThreadFixture.class, args)) return;
        HologramResourceWorld.init();
        Thread.currentThread().setName("controlled-client-owner");
        snapshots();
        resources();
        placement();
        capture();
        System.out.println("FIXTURE_COMPLETE productionModified=false gameStarted=false");
    }

    private static void record(String callback) {
        System.out.println("CALL action=" + action + " callback=" + callback + " thread=" + Thread.currentThread().getName());
    }

    private static JsonObject source(NBTTagCompound tile) throws IOException {
        HologramPresets.Preset preset = new HologramPresets.Preset();
        preset.schema = 2;
        preset.id = "compat_fixture";
        preset.name = "Controlled external callback fixture";
        preset.controllerType = "area";
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false);
        cell.block = "minecraft:stone";
        if (tile != null) cell.tileNbt = HologramBlueprintNbt.encodeCaptured(tile);
        preset.cells.add(cell);
        return GSON.toJsonTree(preset).getAsJsonObject();
    }

    private static HologramBlueprintProcessing.Policy policy() throws IOException {
        HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
        policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
        return policy;
    }

    private static void snapshots() throws Exception {
        JsonObject source = source(null);
        String before = source.toString();
        AuthorDocument document = AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
        String documentBefore = document.sourceCopy().toString();
        // A dedicated server can install a validator which was absent from the client's registry.
        byte[] portableWithoutServerProcessor = HologramAuthorPortableCompiler.compile(document, policy().write(), 0, 0, 64, 0);
        try (GTMBExtensions.Registration handle = BlueprintSnapshots.register(
            "compat_fixture:server_processor", BlueprintSnapshots.Stage.AUTHORING_COMPLETE, snapshot -> {
                snapshotCalls++;
                record("AUTHORING_COMPLETE");
                throw new IOException("controlled extension refuses completion");
            })) {
            action = "local-process";
            reject(() -> HologramLocalAuthorSemantics.process(source, policy().write(), 0, 0, 64, 0));
            require(snapshotCalls == 1 && before.equals(source.toString()), "process rejection preserves source");
            action = "local-portable-compile";
            reject(() -> HologramAuthorPortableCompiler.compile(document, policy().write(), 0, 0, 64, 0));
            require(snapshotCalls == 2 && documentBefore.equals(document.sourceCopy().toString()), "compile rejection preserves source");
            action = "server-library-canonical";
            HologramServerLibrary.canonical(HologramBlueprintSharing.decodeUnchecked(new String(portableWithoutServerProcessor, java.nio.charset.StandardCharsets.UTF_8)));
            require(snapshotCalls == 2, "canonical does not re-run AUTHORING_COMPLETE");
            System.out.println("OBSERVE server-library-canonical processorCalls=0 (not a live admission test)");
        }
    }

    private static void resources() throws Exception {
        NBTTagCompound tile = new NBTTagCompound(), container = new NBTTagCompound();
        tile.setString("id", "compat_fixture:fluid_host");
        container.setTag("nativeSlots", new NBTTagCompound());
        tile.setTag("CustomFluidState", container);
        JsonObject source = source(tile);
        String before = source.toString();
        try (GTMBExtensions.Registration handle = ResourceContainers.register("compat_fixture:server_codec", context -> {
            codecCalls++;
            record("ResourceContainers.decode:" + context.path);
            if (context.path.equals("CustomFluidState")) return new ArrayList<ResourceContainers.FluidAmount>();
            return null;
        })) {
            action = "local-processing-state";
            HologramLocalAuthorSemantics.processingState(source, policy().write(), 0, 0, 64, 0);
            require(codecCalls > 0, "local state invokes external codec");
            require(before.equals(source.toString()), "local state preserves source");
        }
    }

    private static void placement() throws Exception {
        NBTTagCompound tile = new NBTTagCompound();
        tile.setString("id", "compat_fixture:native_host");
        JsonObject row = source(tile).getAsJsonArray("cells").get(0).getAsJsonObject();
        JsonArray targets = new JsonArray();
        JsonObject target = new JsonObject();
        target.addProperty("item", "minecraft:stone");
        target.addProperty("damage", 0);
        targets.add(target);
        row.add("allowedTargets", targets);
        try (GTMBExtensions.Registration handle = NativePlacement.register("compat_fixture:server_placement", new NativePlacement.Adapter() {
            public boolean supports(Block block) { record("NativePlacement.supports"); return block == Blocks.stone; }
            public TileEntity create(Block block, int metadata, NBTTagCompound tag, World world) {
                createCalls++; record("NativePlacement.create"); return new TileEntity();
            }
            public List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
                materialCalls++; record("NativePlacement.materials"); return new ArrayList<ItemStack>();
            }
        })) {
            action = "local-candidate-list";
            List<ItemStack> result = HologramLocalAuthorConfigurations.candidates(row);
            require(createCalls > 0 && materialCalls > 0 && result.size() == 1, "local candidate invokes external native callbacks");
        }
    }

    private static void capture() throws Exception {
        try (GTMBExtensions.Registration processor = BlueprintSnapshots.register("compat_fixture:server_capture", BlueprintSnapshots.Stage.CAPTURE_COMPLETE, snapshot -> {
            captureCalls++; record("CAPTURE_COMPLETE"); return null;
        }); GTMBExtensions.Registration source = StructureSources.register("compat_fixture:server_source", HologramDynamicDefinitionRegression.DynamicController.class, (controller, signal) -> {
            sourceCalls++; record("StructureSources.describe"); return null;
        })) {
            action = "local-regeneration-existing-regression";
            HologramLocalAuthorStructuresRegression.run();
            require(captureCalls > 0 && sourceCalls > 0, "local regeneration invokes external capture/source callbacks");
        }
    }

    private interface Checked { Object run() throws Exception; }
    private static void reject(Checked operation) throws Exception {
        try { operation.run(); throw new AssertionError("controlled refusal was ignored"); }
        catch (IOException expected) {
            require(expected.getMessage().contains("compat_fixture:server_processor"), "refusal identifies extension");
            System.out.println("REFUSAL action=" + action + " message=" + expected.getMessage());
        }
    }
    private static void require(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        System.out.println("ASSERT_PASS " + message);
    }
}
