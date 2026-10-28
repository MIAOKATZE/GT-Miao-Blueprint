package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Base64;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.nbt.NBTTagCompound;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Source-fact admission, not native geometry or GL validation. */
public final class HologramAuthorStaticFactsRegression {

    private HologramAuthorStaticFactsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorStaticFactsRegression.class, args)) return;
        run();
        System.out.println("HologramAuthorStaticFactsRegression PASS");
    }

    public static void run() throws Exception {
        facts(row(Blocks.glass, 0, 0), "real vanilla transparent block");
        facts(row(Blocks.glass_pane, 0, 0), "real vanilla non-cube");
        facts(row(Blocks.wool, 15, 15), "real metadata ItemBlock");
        realGtClasses();
        payloadAndIdentityBoundaries();
        rendererSelectionIsIndependent();
    }

    private static void realGtClasses() throws Exception {
        // Match existing isolated GT regression practice: use the actual pinned classes, skip global GT
        // initialization and texture registration. This is not natural-construction or FML render evidence.
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
        Block casing = registered(
            "author_static_casing",
            (Block) unsafe.allocateInstance(gregtech.common.blocks.BlockCasings1.class),
            true);
        facts(row(casing, 1, 1), "real GT casing class and ItemCasings metadata");
        Block frame = registered(
            "author_static_frame",
            (Block) unsafe.allocateInstance(gregtech.common.blocks.BlockFrameBox.class),
            false);
        facts(row(frame, 17, 17), "real GT frame class and ItemFrames without MTE bit");
        facts(row(frame, 0xFFF, 0xFFF), "real GT frame maximum ordinary metadata");
        SimpleAssert.that(frame.hasTileEntity(0x1000), "real frame MTE bit retains tile semantics");
        deferred(row(frame, 0x1000, 0x1000), "GT frame with MTE bit is not static facts");
        deferred(row(casing, 1, 0), "GT casing metadata mismatch");
    }

    private static void payloadAndIdentityBoundaries() throws Exception {
        String empty = Base64.getEncoder()
            .encodeToString(HologramSnapshotCodec.compress(new NBTTagCompound()));
        for (String name : new String[] { "tileNbt", "itemNbt" }) {
            JsonObject row = row(Blocks.glass, 0, 0);
            row.addProperty(name, empty);
            deferred(row, "encoded empty compound is still opaque " + name);
            row.addProperty(name, "not-base64");
            deferred(row, "malformed payload is not silently discarded " + name);
            row.addProperty(name, "");
            facts(row, "empty payload string follows absent-payload semantics " + name);
        }
        deferred(row(Blocks.chest, 0, 0), "vanilla tile host");
        deferred(row(Blocks.wool, 15, 0), "item metadata must match source metadata");
        JsonObject mismatch = row(Blocks.glass, 0, 0);
        mismatch.addProperty("item", "minecraft:stone");
        deferred(mismatch, "different ItemBlock");
        mismatch.addProperty("item", "minecraft:stick");
        deferred(mismatch, "non-block item");
        mismatch.addProperty("item", "gtmb:absent_static_item");
        deferred(mismatch, "unknown item");
        mismatch = row(Blocks.glass, 0, 0);
        mismatch.addProperty("block", "gtmb:absent_static_block");
        deferred(mismatch, "unknown block");
        mismatch.addProperty("block", "minecraft:air");
        deferred(mismatch, "air cannot acquire a foreign block item");

        Block machine = new Block(Material.iron) {};
        String id = registerBlock("author_static_machine", machine);
        Item.itemRegistry
            .addObject(Block.getIdFromBlock(machine), id, new gregtech.common.blocks.ItemMachines(machine));
        // Even a no-TE synthetic host cannot make the actual GT machine item a static material.
        deferred(row(machine, 0, 0), "ItemMachines retains native validation");
    }

    private static void rendererSelectionIsIndependent() throws Exception {
        Block unknown = registered("author_static_unknown_renderer", new Block(Material.glass) {

            @Override
            public int getRenderType() {
                throw new AssertionError("source facts must not inspect render geometry");
            }

            @Override
            public boolean renderAsNormalBlock() {
                throw new AssertionError("source facts must not inspect cube geometry");
            }
        }, false);
        facts(row(unknown, 0, 0), "unknown render family still has safe source facts");
        SimpleAssert.that(
            HologramNativeRenderRegistry.adapter(unknown) == null,
            "typed source facts do not register an unknown ISBRH as native supported");
        int[] probes = { 0 };
        HologramNativeRenderRegistry.Adapter adapter = new HologramNativeRenderRegistry.Adapter() {

            public boolean supports(Block block) {
                probes[0]++;
                return block == unknown;
            }

            public boolean requiresTile(Block block, int metadata) {
                return true;
            }
        };
        HologramNativeRenderRegistry.register(adapter);
        try {
            facts(row(unknown, 0, 0), "external-adapter block facts");
            SimpleAssert.eq(0, probes[0], "source facts never probe external render adapters");
            SimpleAssert.that(
                HologramNativeRenderRegistry.adapter(unknown) == adapter,
                "external native adapter remains selected after facts preparation");
            SimpleAssert.that(adapter.requiresTile(unknown, 0), "facts do not override adapter tile requirements");
        } finally {
            HologramNativeRenderRegistry.unregister(adapter);
        }
    }

    private static void facts(JsonObject row, String reason) throws Exception {
        JsonArray rows = new JsonArray();
        rows.add(row);
        JsonObject other = new com.google.gson.JsonParser().parse(row.toString())
            .getAsJsonObject();
        other.addProperty("x", 1);
        rows.add(other);
        AuthorDocument document = document(rows);
        byte[] before = AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults());
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            HologramState state = projection.view();
            HologramState.Cell first = state.cells.get(0), second = state.cells.get(1);
            SimpleAssert.that(!first.modelPending(false) && !first.modelPending(true), reason + ": facts ready");
            SimpleAssert.that(first.actualStack != null && first.targetStack != null, reason + ": exact stacks");
            SimpleAssert.that(
                first.actualStack != first.targetStack && first.targetStack != second.targetStack,
                reason + ": independent mutable stacks");
            NBTTagCompound expected = first.tag();
            NBTTagCompound ordinary = new NBTTagCompound();
            ordinary.setString("wantId", first.wantId);
            ordinary.setInteger("wantMeta", first.wantMeta);
            ordinary.setInteger("targetFacing", first.targetFacing);
            ordinary.setString(
                "previewItem",
                row.get("item")
                    .getAsString());
            ordinary.setInteger(
                "previewDamage",
                row.get("damage")
                    .getAsInt());
            HologramBlueprintPreview.completeCell(ordinary);
            SimpleAssert
                .eq(ordinary.getTag("targetStack"), expected.getTag("targetStack"), reason + ": same stack facts");
            SimpleAssert.eq(
                ordinary.getTag("targetDisplay"),
                expected.getTag("targetDisplay"),
                reason + ": same display facts");
            projection.request(state, first);
            SimpleAssert.that(!projection.advance(() -> 0), reason + ": no opaque admission work");
            SimpleAssert.that(projection.view() == state, reason + ": no native row replacement");
            first.actualStack.stackSize = 7;
            first.targetStack.setStackDisplayName("consumer mutation");
            first.targetDisplay.setString("consumer", "mutation");
            NBTTagCompound exported = first.tag();
            exported.getCompoundTag("targetStack")
                .setString("consumer", "mutation");
            SimpleAssert.eq(expected, first.tag(), reason + ": export and display mutations cannot change facts");
            SimpleAssert.that(
                second.targetStack.getTagCompound() == null && second.actualStack.stackSize == 1,
                reason + ": no cross-cell mutation");
            SimpleAssert.that(
                Arrays.equals(before, AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults())),
                reason + ": source draft remains byte identical");
        }
    }

    private static void deferred(JsonObject row, String reason) throws Exception {
        JsonArray rows = new JsonArray();
        rows.add(row);
        AuthorDocument document = document(rows);
        byte[] before = AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults());
        try (HologramAuthorProjection projection = new HologramAuthorProjection(document)) {
            HologramState.Cell cell = projection.view().cells.get(0);
            SimpleAssert.that(cell.modelPending(false) && cell.modelPending(true), reason + ": stays deferred");
            SimpleAssert.that(cell.actualStack == null && cell.targetStack == null, reason + ": no fabricated facts");
            SimpleAssert.that(
                Arrays.equals(before, AuthorDocumentIO.writeDraft(document, AuthorDocumentIO.Limits.defaults())),
                reason + ": exact source remains retained");
        }
    }

    private static JsonObject row(Block block, int metadata, int damage) {
        JsonObject row = new JsonObject();
        row.addProperty("x", 0);
        row.addProperty("y", 0);
        row.addProperty("z", 0);
        row.addProperty("block", Block.blockRegistry.getNameForObject(block));
        row.addProperty("item", Block.blockRegistry.getNameForObject(block));
        row.addProperty("metadata", metadata);
        row.addProperty("damage", damage);
        row.addProperty("facing", 3);
        return row;
    }

    private static AuthorDocument document(JsonArray rows) throws Exception {
        JsonObject source = new JsonObject();
        source.addProperty("schema", 2);
        source.add("cells", rows);
        return AuthorDocumentIO.readExpanded(source, AuthorDocumentIO.Limits.defaults());
    }

    private static Block registered(String name, Block block, boolean casing) throws Exception {
        String id = registerBlock(name, block);
        ItemBlock item = casing ? new gregtech.common.blocks.ItemCasings(block)
            : block instanceof gregtech.common.blocks.BlockFrameBox ? new gregtech.common.blocks.ItemFrames(block)
                : new ItemBlock(block) {

                    @Override
                    public int getMetadata(int damage) {
                        return damage;
                    }
                };
        Item.itemRegistry.addObject(Block.getIdFromBlock(block), id, item);
        return block;
    }

    private static String registerBlock(String name, Block block) throws Exception {
        if (block.delegate == null) {
            Field delegate = Block.class.getDeclaredField("delegate");
            delegate.setAccessible(true);
            delegate.set(
                block,
                ((cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry) Block.blockRegistry)
                    .getDelegate(block, Block.class));
        }
        int free = 3899;
        while (free > 0 && (Item.getItemById(free) != null || Block.getBlockById(free) != Blocks.air)) free--;
        if (free == 0) throw new AssertionError("No isolated static facts registry slot");
        String id = "gtmb:" + name;
        Block.blockRegistry.addObject(free, id, block);
        return id;
    }
}
