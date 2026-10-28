package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativeCapture;
import com.miaokatze.gtmb.api.NativePlacement;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.blueprint.editor.AuthorEditor;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Real LittleTiles host, public extension callbacks and detached transactions; no game or author Session. */
final class HologramAuthorAttachmentReviewRegression {

    private HologramAuthorAttachmentReviewRegression() {}

    static void run() throws Exception {
        Block host = host();
        AuthorDocument frozen = empty();
        NBTTagCompound request = request(false);
        AtomicInteger calls = new AtomicInteger();
        AuthorEditor editor = new AuthorEditor(frozen, 4);
        try (HologramLocalAuthorAttachments local = new HologramLocalAuthorAttachments();
            GTMBExtensions.Registration legacy = NativePlacement
                .register("gtmb_test:attachment-preflight", adapter(host, calls, null, false))) {
            long retained = HologramProcessGovernor.retainedBytes();
            try {
                local.start(editor, request);
                throw new AssertionError("constructor bypassed local scope");
            } catch (HologramExtensionExecution.ReviewRequired expected) {}
            SimpleAssert
                .eq(0, calls.get(), "constructor rejects before any legacy applicability/factory/material call");
            SimpleAssert.that(!local.busy(), "refused constructor starts no attachment job");
            SimpleAssert
                .eq(retained, HologramProcessGovernor.retainedBytes(), "refused preflight retains no worker input");
            SimpleAssert.that(editor.current() == frozen && !editor.canUndo(), "constructor refusal is atomic");
        }
        partialPayloadAndLifecycle(host, frozen);
        ownerFallback(host, frozen);
    }

    private static void partialPayloadAndLifecycle(Block host, AuthorDocument frozen) throws Exception {
        AtomicInteger nativeCalls = new AtomicInteger(), captures = new AtomicInteger();
        try (
            GTMBExtensions.Registration safe = NativePlacement
                .register("gtmb_test:attachment-client-safe", adapter(host, nativeCalls, null, true), true);
            GTMBExtensions.Registration capture = NativeCapture
                .register("gtmb_test:attachment-no-recapture", new NativeCapture.Adapter() {

                    public boolean supports(TileEntity tile) {
                        captures.incrementAndGet();
                        throw new AssertionError("attachment payload must not probe live capture adapters");
                    }

                    public NBTTagCompound capture(TileEntity tile) {
                        throw new AssertionError("attachment must use generated owned NBT");
                    }
                });
            HologramLocalAuthorAttachments local = new HologramLocalAuthorAttachments()) {
            AuthorEditor editor = new AuthorEditor(frozen, 4);
            local.start(editor, request(false));
            HologramLocalAuthorAttachments.Result accepted = await(local);
            SimpleAssert.that(accepted.accepted && editor.canUndo(), "partial-cell attachment commits once locally");
            SimpleAssert.eq(0, captures.get(), "NBT overload bypasses NativeCapture even for legacy registrations");
            SimpleAssert.that(nativeCalls.get() >= 3, "safe native applicability/factory/material callbacks ran");
            SimpleAssert.eq(
                "littletiles:BlockLittleTiles",
                editor.current()
                    .cells()
                    .iterator()
                    .next()
                    .block(),
                "one-pixel fixture actually exercises partial LittleTiles cell");

            AuthorEditor cancelled = new AuthorEditor(frozen, 4);
            local.start(cancelled, request(false));
            SimpleAssert
                .that(!local.cancel("cancelled").accepted && !local.busy(), "cancel retires worker transaction");
            SimpleAssert.eq(null, local.tick(), "cancelled task cannot publish later");
            SimpleAssert
                .that(cancelled.current() == frozen && !cancelled.canUndo(), "cancel preserves original editor");

            local.start(cancelled, request(false));
            cancelled.rename("changed while generating");
            AuthorDocument changed = cancelled.current();
            SimpleAssert.that(!local.tick().accepted && !local.busy(), "stale editor retires generation");
            SimpleAssert.that(cancelled.current() == changed, "stale result never overwrites newer source");
        }
        // A provider registered after local start must cause a typed refusal, not partial publication.
        try (HologramLocalAuthorAttachments local = new HologramLocalAuthorAttachments()) {
            AuthorEditor editor = new AuthorEditor(frozen, 4);
            local.start(editor, request(false));
            AtomicInteger calls = new AtomicInteger();
            try (GTMBExtensions.Registration late = NativePlacement
                .register("gtmb_test:attachment-late-owner", adapter(host, calls, null, true))) {
                HologramLocalAuthorAttachments.Result refused = await(local);
                SimpleAssert
                    .that(!refused.accepted && refused.reviewRequired, "stage refusal requests explicit owner review");
                SimpleAssert.eq(0, calls.get(), "late provider is not probed locally");
                SimpleAssert.that(editor.current() == frozen && !editor.canUndo(), "stage refusal publishes no edit");
            }
        }
    }

    private static void ownerFallback(Block host, AuthorDocument frozen) throws Exception {
        HologramWorkBudget.startServerTick();
        HologramWorkBudget.endServerTick();
        Thread owner = Thread.currentThread();
        AtomicInteger calls = new AtomicInteger();
        try (GTMBExtensions.Registration legacy = NativePlacement
            .register("gtmb_test:attachment-owner", adapter(host, calls, owner, true))) {
            AtomicReference<Throwable> rejected = new AtomicReference<>();
            Thread other = new Thread(() -> {
                try {
                    HologramAuthorAttachmentReview.start(frozen, request(false))
                        .close();
                } catch (Throwable expected) {
                    rejected.set(expected);
                }
            }, "attachment-unverified-owner");
            other.start();
            other.join();
            SimpleAssert.that(rejected.get() instanceof IOException, "unverified owner cannot start native fallback");
            SimpleAssert.eq(0, calls.get(), "wrong owner invokes no legacy callbacks");
            for (boolean picture : new boolean[] { false, true }) {
                AuthorEditor original = new AuthorEditor(frozen, 4);
                NBTTagCompound request = request(picture);
                try (HologramAuthorAttachmentReview review = HologramAuthorAttachmentReview.start(frozen, request)) {
                    long deadline = System.nanoTime() + 10_000_000_000L;
                    while (!review.step()) {
                        if (System.nanoTime() > deadline) throw new AssertionError("attachment review did not settle");
                        Thread.sleep(1);
                    }
                    HologramAuthorServerReview.Result result = review.result();
                    SimpleAssert.that(
                        original.current() == frozen && !original.canUndo(),
                        "server review changes only its private editor");
                    SimpleAssert
                        .eq(31L, result.report.getLong("textRequest"), "typed result retains request correlation");
                    List<JsonObject> cells = new ArrayList<>();
                    for (JsonElement row : result.source.getAsJsonArray("cells")) cells.add(row.getAsJsonObject());
                    original.replaceAttachment(frozen.revision(), result.source.getAsJsonObject("group"), cells);
                    SimpleAssert.that(original.canUndo() && !cells.isEmpty(), "owner result applies one atomic delta");
                    original.undo();
                    SimpleAssert.that(!original.canUndo(), "one undo removes the entire attachment");
                }
            }
            HologramAuthorAttachmentReview cancelled = HologramAuthorAttachmentReview.start(frozen, request(false));
            cancelled.close();
            cancelled.close();
            try {
                cancelled.step();
                throw new AssertionError("cancelled server attachment resumed");
            } catch (IOException expected) {}
        }
        SimpleAssert.that(calls.get() >= 6, "legacy callbacks execute successfully on the verified owner");
    }

    private static HologramLocalAuthorAttachments.Result await(HologramLocalAuthorAttachments local) throws Exception {
        long deadline = System.nanoTime() + 10_000_000_000L;
        HologramLocalAuthorAttachments.Result result;
        while ((result = local.tick()) == null) {
            if (System.nanoTime() > deadline) throw new AssertionError("attachment geometry did not settle");
            Thread.sleep(1);
        }
        return result;
    }

    static NativePlacement.Adapter adapter(Block host, AtomicInteger calls, Thread owner, boolean supports) {
        return new NativePlacement.Adapter() {

            private void called() {
                if (owner != null)
                    SimpleAssert.that(Thread.currentThread() == owner, "native callback runs on real owner");
                calls.incrementAndGet();
            }

            public boolean supports(Block block) {
                called();
                return supports && block == host;
            }

            public TileEntity create(Block block, int metadata, NBTTagCompound tag, World detachedWorld) {
                called();
                SimpleAssert.eq("LittleTilesTileEntity", tag.getString("id"), "generated NBT reaches native factory");
                return new TileEntity();
            }

            public List<ItemStack> materials(Block block, int metadata, TileEntity tile) {
                called();
                return Collections.singletonList(new ItemStack(Blocks.stone));
            }
        };
    }

    static Block host() throws Exception {
        Block host = Block.getBlockFromName("littletiles:BlockLittleTiles");
        if (host == null || host == Blocks.air) {
            host = (Block) Class.forName("com.creativemd.littletiles.common.blocks.BlockTile")
                .getConstructor(Material.class)
                .newInstance(Material.rock);
            // Forge prefixes even qualified names with the active mod id. Use its normal registration
            // API under an isolated real FML container, then restore the loader's exact prior context.
            cpw.mods.fml.common.Loader loader = cpw.mods.fml.common.Loader.instance();
            java.lang.reflect.Field controllerField = cpw.mods.fml.common.Loader.class
                .getDeclaredField("modController");
            controllerField.setAccessible(true);
            Object previousController = controllerField.get(loader);
            cpw.mods.fml.common.LoadController controller = new cpw.mods.fml.common.LoadController(loader);
            cpw.mods.fml.common.ModMetadata metadata = new cpw.mods.fml.common.ModMetadata();
            metadata.modId = "littletiles";
            metadata.name = "LittleTiles native attachment fixture";
            java.lang.reflect.Field active = cpw.mods.fml.common.LoadController.class
                .getDeclaredField("activeContainer");
            active.setAccessible(true);
            active.set(controller, new cpw.mods.fml.common.DummyModContainer(metadata));
            try {
                controllerField.set(loader, controller);
                cpw.mods.fml.common.registry.GameRegistry
                    .registerBlock(host, net.minecraft.item.ItemBlock.class, "BlockLittleTiles");
            } finally {
                controllerField.set(loader, previousController);
            }
            SimpleAssert.that(
                controllerField.get(loader) == previousController,
                "fixture restores the original FML loader context");
            SimpleAssert.that(
                Block.getBlockFromName("littletiles:BlockLittleTiles") == host,
                "normal Forge registration exposes the exact native host identity");
        }
        SimpleAssert.eq(
            "littletiles:BlockLittleTiles",
            Block.blockRegistry.getNameForObject(host),
            "native host registry name round-trips without a minecraft prefix");
        SimpleAssert.eq(
            "com.creativemd.littletiles.common.blocks.BlockTile",
            host.getClass()
                .getName(),
            "fixture uses the actual LittleTiles host class, not air or a fake missing host");
        return host;
    }

    static AuthorDocument empty() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        root.add("structures", new JsonArray());
        root.add("cells", new JsonArray());
        return AuthorDocumentIO.readExpanded(root, AuthorDocumentIO.Limits.defaults());
    }

    static NBTTagCompound request(boolean picture) {
        HologramTextBox text = new HologramTextBox();
        text.id = (picture ? HologramPictureBox.PREFIX : HologramTextBox.PREFIX)
            + "33333333-3333-3333-3333-333333333333";
        text.material = "minecraft:stone";
        text.width = text.height = 1;
        text.bitmap = new byte[] { 1 };
        if (picture) {
            text.picture = new HologramPictureBox();
            text.picture.filename = "owner.png";
            text.picture.palette = 1;
            text.picture.originalWidth = text.picture.originalHeight = 1;
            text.picture.sourceWidth = text.picture.sourceHeight = 1;
            text.picture.width = text.picture.height = text.picture.shortSide = 1;
            text.picture.source = new int[] { 0xff123456 };
        }
        NBTTagCompound request = new NBTTagCompound();
        request.setLong("textRequest", 31);
        request.setTag("textBox", text.write());
        return request;
    }
}
