package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Base64;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.hologram.HologramNativeCompatibilityFixture;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Ordinary descriptor memory and real LittleTiles payload preparation; no GL/geometry claim. */
public final class HologramAuthorPlainBlockRegression {

    private HologramAuthorPlainBlockRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorPlainBlockRegression.class, args)) return;
        run();
        System.out.println("HologramAuthorPlainBlockRegression PASS");
    }

    public static void run() throws Exception {
        JsonArray rows = new JsonArray();
        for (int index = 0; index < 5000; index++) rows.add(row(index, "minecraft:stone", "minecraft:stone"));
        AuthorDocument document = document(rows);
        byte[] before = AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults());
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            for (int first = 0; first < 5000; first += 512) {
                HologramState state = projection.view();
                for (int index = first; index < Math.min(5000, first + 512); index++)
                    projection.request(state, state.cells.get(index));
                projection.advance(() -> 0);
            }
            int budget = 0;
            for (HologramState.Cell cell : projection.view().cells) {
                if ("localBudget".equals(cell.renderKind)) budget++;
            }
            System.out.println("plain block fixture: cells=5000 budgetShells=" + budget);
            SimpleAssert.eq(0, budget, "ordinary block items do not exhaust the opaque native-payload quota");
            HologramState.Cell first = projection.view().cells.get(0), second = projection.view().cells.get(1);
            SimpleAssert.that(!first.modelPending(false) && !first.modelPending(true), "plain metadata is ready");
            SimpleAssert.that(
                first.actualStack != first.targetStack && first.targetStack != second.targetStack,
                "mutable item stacks remain independent per view and per cell");
            NBTTagCompound expected = first.tag();
            first.actualStack.stackSize = 7;
            first.targetStack.setStackDisplayName("consumer mutation");
            SimpleAssert.eq(expected, first.tag(), "consumer stack edits cannot mutate exported descriptor facts");
            SimpleAssert.that(
                Arrays.equals(before, AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults())),
                "render preparation keeps the exact source draft");
        }
        HologramAuthorStaticFactsRegression.run();
        realNativeDecorRemainsLazy();
    }

    private static void realNativeDecorRemainsLazy() throws Exception {
        // Reuse the existing actual pinned LittleTiles host registration, never a cube adapter or mock block.
        Method host = Class.forName("com.miaokatze.gtmb.hologram.HologramAuthorAttachmentReviewRegression")
            .getDeclaredMethod("host");
        host.setAccessible(true);
        Block block = (Block) host.invoke(null);
        String id = Block.blockRegistry.getNameForObject(block);
        JsonObject row = row(0, id, id);
        NBTTagCompound payload = HologramNativeCompatibilityFixture.seed(block, 0, 0, 0);
        row.addProperty(
            "tileNbt",
            Base64.getEncoder()
                .encodeToString(HologramSnapshotCodec.compress(payload)));
        JsonArray rows = new JsonArray();
        rows.add(row);
        AuthorDocument document = document(rows);
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            HologramState state = projection.view();
            SimpleAssert.eq(
                "com.creativemd.littletiles.common.blocks.BlockTile",
                state.cells.get(0)
                    .block(false)
                    .getClass()
                    .getName(),
                "real decoration host resolves");
            SimpleAssert.that(
                state.cells.get(0)
                    .modelPending(false),
                "native decoration keeps deferred opaque data");
            projection.request(state, state.cells.get(0));
            projection.advance(() -> 0);
            HologramState.Cell prepared = projection.view().cells.get(0);
            SimpleAssert.eq("", prepared.sourceWarning, "real decoration payload preparation succeeds");
            SimpleAssert.eq(
                payload,
                prepared.targetDisplay.getCompoundTag("nativeTile"),
                "all native child boxes/materials survive author preparation");
            SimpleAssert.that(prepared.targetDisplay.hasKey("nativeTile", 10), "native decoration is not air");
        }
    }

    private static JsonObject row(int index, String block, String item) {
        JsonObject row = new JsonObject();
        row.addProperty("x", index % 100);
        row.addProperty("y", 0);
        row.addProperty("z", index / 100);
        row.addProperty("block", block);
        row.addProperty("item", item);
        return row;
    }

    private static AuthorDocument document(JsonArray rows) throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        source.add("cells", rows);
        return AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
    }
}
