// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramBlueprintCompactCodec;
import com.miaokatze.gtmb.hologram.HologramGtMeOutputDisplay;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.ItemMachines;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputBusME;
import gregtech.common.tileentities.machines.outputme.MTEHatchOutputME;

/** Exact native owner, real compact decode and detached properties. No live GT/AE network or world. */
public final class HologramMeOutputDisplayRegression {

    private static final int BUS = 2710, FLUID = 2713;
    private static final String HOST = "gtmb:me_output_display_fixture";

    private HologramMeOutputDisplayRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramMeOutputDisplayRegression.class, args)) return;
        Block previousHost = GregTechAPI.sBlockMachines;
        IMetaTileEntity previousBus = GregTechAPI.METATILEENTITIES[BUS];
        IMetaTileEntity previousHatch = GregTechAPI.METATILEENTITIES[FLUID];
        Block host = new Block(Material.iron) {};
        register(Block.blockRegistry, host);
        ItemMachines shell = new ItemMachines(host);
        register(Item.itemRegistry, shell);
        SimpleAssert.that(
            Block.getBlockFromName(HOST) == host && Item.itemRegistry.getObject(HOST) == shell,
            "headless fixture must resolve exact native block and item names");
        GregTechAPI.sBlockMachines = host;
        try {
            // Real registered types; no recipes, tick, network, tile loading or inventory callback.
            GregTechAPI.METATILEENTITIES[BUS] = prototype(MTEHatchOutputBusME.class);
            GregTechAPI.METATILEENTITIES[FLUID] = prototype(MTEHatchOutputME.class);
            reopened(host);
            editableAuthorRetention();
            live(BUS, MTEHatchOutputBusME.class, 8192L, MTEHatchOutputBusME.DEFAULT_CAPACITY, false);
            live(FLUID, MTEHatchOutputME.class, 2097152L, MTEHatchOutputME.DEFAULT_CAPACITY, true);
            staysBlocked(host);
            failClosed(host);
        } finally {
            GregTechAPI.sBlockMachines = previousHost;
            GregTechAPI.METATILEENTITIES[BUS] = previousBus;
            GregTechAPI.METATILEENTITIES[FLUID] = previousHatch;
        }
        System.out.println("PASS ME output recipe capacity remains display-only through compact preview");
    }

    private static void reopened(Block host) throws Exception {
        JsonObject root = new JsonObject();
        JsonObject blueprint = new JsonObject();
        root.add("blueprint", blueprint);
        JsonArray cells = new JsonArray();
        blueprint.add("cells", cells);
        NBTTagCompound bus = saved(BUS, 8192L);
        NBTTagCompound externalState = new NBTTagCompound();
        externalState.setInteger("unrelatedObservation", 93);
        bus.setTag("Inventory", new NBTTagList());
        bus.setTag("extraObservation", externalState);
        cells.add(row(0, BUS, bus, null));
        cells.add(row(1, FLUID, saved(FLUID, 2097152L), null));
        cells.add(row(2, BUS, saved(BUS, Long.MAX_VALUE), null));
        cells.add(row(3, BUS, saved(BUS, MTEHatchOutputBusME.DEFAULT_CAPACITY), null));
        cells.add(row(4, FLUID, saved(FLUID, MTEHatchOutputME.DEFAULT_CAPACITY), null));
        NBTTagCompound filtered = saved(BUS, 8192L);
        filtered.removeTag("baseCapacity");
        cells.add(row(5, BUS, filtered, null));
        cells.add(row(6, BUS, null, itemTag(32768L)));
        byte[] compact = encode(root);
        byte[] frozen = compact.clone();
        HologramState result = displayed(compact);
        SimpleAssert.eq(7, result.cells.size(), "full target display has all original cells");
        expect(result, 0, 8192L, false, "8,192 items");
        expect(result, 1, 2097152L, true, "2,097,152 mB");
        expect(result, 2, Long.MAX_VALUE, false, "∞ items");
        expect(result, 3, 0, false, "");
        expect(result, 4, 0, true, "");
        expect(result, 5, 0, false, "");
        expect(result, 6, 32768L, false, "32,768 items");
        for (int i = 0; i < 6; i++) {
            ItemStack target = result.cells.get(i).targetStack;
            SimpleAssert.that(
                target != null && Item.getItemFromBlock(host) == target.getItem(),
                "physical item remains the same registered shell");
            SimpleAssert.that(!target.hasTagCompound(), "tile capacity never joins target or payment identity");
        }
        SimpleAssert.eq(
            itemTag(32768L),
            result.cells.get(6).targetStack.getTagCompound(),
            "original explicit item tag remains exactly as supplied");
        SimpleAssert.that(Arrays.equals(frozen, compact), "opening never rewrites the stored blueprint bytes");
        SimpleAssert.that(
            !result.cells.get(0)
                .display(false)
                .hasKey("Inventory")
                && !result.cells.get(0)
                    .display(false)
                    .hasKey("extraObservation"),
            "display marker is never cargo");
        // The bytes are still physically present in the source; only author policy turns the extra visual line off.
        blueprint.addProperty("retainNbt", false);
        HologramState filteredAll = displayed(encode(root));
        expect(filteredAll, 0, 0, false, "");
        expect(filteredAll, 6, 0, false, "");
        blueprint.addProperty("retainNbt", true);
        JsonArray allow = new JsonArray();
        allow.add(new com.google.gson.JsonPrimitive("unrelatedObservation"));
        blueprint.add("retainedNbtKeys", allow);
        HologramState selective = displayed(encode(root));
        expect(selective, 1, 0, true, "");
        expect(selective, 6, 0, false, "");
        allow.add(new com.google.gson.JsonPrimitive("baseCapacity"));
        HologramState allowed = displayed(encode(root));
        expect(allowed, 1, 2097152L, true, "2,097,152 mB");
    }

    private static void editableAuthorRetention() throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 2);
        JsonObject blueprint = new JsonObject();
        root.add("blueprint", blueprint);
        blueprint.addProperty("schema", 2);
        blueprint.add("palette", new JsonArray());
        JsonArray cells = new JsonArray();
        blueprint.add("cells", cells);
        cells.add(row(0, BUS, saved(BUS, 8192L), null));
        cells.add(row(1, FLUID, null, itemTag(2097152L)));

        expectEditableAuthorRetention(root, true); // document default: retain all
        blueprint.addProperty("retainNbt", false);
        expectEditableAuthorRetention(root, false);
        blueprint.addProperty("retainNbt", true);
        JsonArray selected = new JsonArray();
        blueprint.add("retainedNbtKeys", selected);
        selected.add(new com.google.gson.JsonPrimitive("unrelatedObservation"));
        expectEditableAuthorRetention(root, false);
        selected.add(new com.google.gson.JsonPrimitive("baseCapacity"));
        expectEditableAuthorRetention(root, true);

        NBTTagCompound malformed = HologramLocalBlueprintDocument.open(encode(root), "")
            .snapshotMetadata();
        malformed.setInteger("blueprintRetainNbt", 1);
        SimpleAssert.that(
            !HologramBlueprintPreview.editorAllowsMeOutputCapacity(malformed),
            "malformed editor authoring policy cannot enable the display marker");
    }

    private static void expectEditableAuthorRetention(JsonObject root, boolean visible) throws Exception {
        byte[] exact = encode(root);
        var opened = HologramLocalBlueprintDocument.openForEditor(exact, "", () -> true);
        var editor = opened.editor();
        SimpleAssert.that(editor != null, "expected real file-backed compact authoring rows");
        assertEditableCapacity(
            new HologramState.Preparation(editor.metadata(), true, new HologramBlueprintPreview.CompactCells(editor)),
            visible);

        var document = opened.document();
        NBTTagCompound ordinary = document.snapshot();
        NBTTagCompound captured = ordinary.getTagList("cells", 10)
            .getCompoundTagAt(0);
        SimpleAssert.that(
            captured.getCompoundTag("previewTile")
                .hasKey("baseCapacity", 4) && !captured.hasKey("targetDisplay")
                && !captured.hasKey(HologramGtMeOutputDisplay.ITEMS)
                && !captured.getCompoundTag("previewTile")
                    .hasKey(HologramGtMeOutputDisplay.ITEMS),
            "authoring snapshot keeps native NBT, not the derived display scalar");
        // Even contrary browse keys must not override a genuine editable document's authoring policy.
        ordinary.setBoolean("localMaterialPolicyPresent", true);
        ordinary.setBoolean("retainNbt", !visible);
        assertEditableCapacity(new HologramState.Preparation(ordinary, true, null), visible);
        var rows = document.detachedEditorRows(() -> true);
        rows.metadata()
            .setInteger("dimension", 7);
        HologramState detached = assertEditableCapacity(
            new HologramState.Preparation(rows.metadata(), true, new HologramBlueprintPreview.CompactCells(rows)),
            visible);
        Object world = new Object(), connection = new Object(), player = new Object();
        var reuse = HologramLocalPresentation.capture(document, detached, world, connection, player, 7, 3, true);
        SimpleAssert.that(reuse != null, "exact same-owner authoring view is reusable");
        HologramState rebased = reuse.rebase(document.resumePresentationMetadata(() -> true));
        SimpleAssert.that(
            rebased != null && rebased.cells == detached.cells,
            "policy-correct detached geometry is reused under fresh authoring metadata");
        assertEditableCaptions(rebased, visible);

        NBTTagCompound changed = new NBTTagCompound();
        changed.setBoolean("all", true);
        changed.setBoolean("retainNbt", !visible);
        document.apply("configureBlueprintNbt", changed);
        SimpleAssert.that(
            !reuse.current(document, world, connection, player, 7, 3)
                && reuse.rebase(document.resumePresentationMetadata(() -> true)) == null,
            "a policy revision cannot reuse the previous retained token");
        HologramState after = rebased.withMetadata(document.snapshotMetadata());
        SimpleAssert.that(
            rebased.cells == after.cells && rebased.regions == after.regions,
            "policy toggles keep the original view geometry");
        assertEditableCaptions(after, !visible);
        document.apply("editUndo", new NBTTagCompound());
        HologramState undone = after.withMetadata(document.snapshotMetadata());
        SimpleAssert.that(rebased.cells == undone.cells, "restoring author policy does not replace the captured cells");
        assertEditableCaptions(undone, visible);
    }

    private static HologramState assertEditableCapacity(HologramState.Preparation preparation, boolean visible) {
        while (!preparation.step(3, Long.MAX_VALUE)) {}
        HologramState view = preparation.result();
        assertEditableCaptions(view, visible);
        SimpleAssert.that(
            !view.cells.get(0).targetStack.hasTagCompound(),
            "tile-derived display capacity remains outside the editable target stack");
        SimpleAssert.eq(
            itemTag(2097152L),
            view.cells.get(1).targetStack.getTagCompound(),
            "authoring display policy does not rewrite the original item tag");
        return view;
    }

    private static void assertEditableCaptions(HologramState view, boolean visible) {
        expect(view, 0, 8192L, false, visible ? "8,192 items" : "");
        expect(view, 1, 2097152L, true, visible ? "2,097,152 mB" : "");
    }

    private static HologramState displayed(byte[] compact) throws Exception {
        HologramBlueprintPreview.DisplayInput input = HologramBlueprintPreview.displayInput(compact, null);
        SimpleAssert.that(input.cells != null, "expected production packed compact path");
        HologramState.Preparation step = new HologramState.Preparation(input.snapshot, true, input.cells);
        while (!step.step(3, Long.MAX_VALUE)) {}
        return step.result();
    }

    private static byte[] encode(JsonObject root) throws Exception {
        return HologramBlueprintCompactCodec.recompress(
            root.toString()
                .getBytes(StandardCharsets.UTF_8),
            HologramBlueprintArtifact.MAX_JSON_BYTES);
    }

    private static void expect(HologramState view, int index, long value, boolean fluid, String suffix) {
        HologramState.Cell cell = view.cells.get(index);
        SimpleAssert.eq(value, HologramGtMeOutputDisplay.read(cell.display(false), fluid), "typed output capacity");
        SimpleAssert.eq(0L, HologramGtMeOutputDisplay.read(cell.display(false), !fluid), "opposite family absent");
        String caption = HologramEditorPropertiesPane.meOutputCapacityText(view, cell, false);
        SimpleAssert.eq(suffix.isEmpty() ? "" : "Target base buffer: " + suffix, caption, "visible subtitle");
    }

    private static void live(int id, Class<?> type, long upgraded, long ordinary, boolean fluid) throws Exception {
        // Constructor-free fixtures call the exact native setItemNBT, not a mock of the writer or a live tile.
        IMetaTileEntity machine = prototype(type);
        Field provider = type.getDeclaredField("provider");
        provider.setAccessible(true);
        Object value = unsafe().allocateInstance(Class.forName(type.getName() + "$1"));
        provider.set(machine, value);
        Field environment = provider.getType()
            .getDeclaredField("env");
        environment.setAccessible(true);
        environment.set(value, machine);
        Field capacity = provider.getType()
            .getDeclaredField("baseCapacity");
        capacity.setAccessible(true);
        IGregTechTileEntity observed = (IGregTechTileEntity) Proxy.newProxyInstance(
            IGregTechTileEntity.class.getClassLoader(),
            new Class<?>[] { IGregTechTileEntity.class },
            (instance, method, arguments) -> {
                if (method.getName()
                    .equals("getMetaTileID")) return id;
                if (method.getName()
                    .equals("getMetaTileEntity")) return machine;
                throw new AssertionError("display may not call another tile hook: " + method.getName());
            });
        Method writer = HologramGtMeOutputDisplay.class
            .getDeclaredMethod("observeLive", IGregTechTileEntity.class, NBTTagCompound.class);
        writer.setAccessible(true);
        capacity.setLong(value, upgraded);
        NBTTagCompound actual = new NBTTagCompound();
        writer.invoke(null, observed, actual);
        SimpleAssert.eq(upgraded, HologramGtMeOutputDisplay.read(actual, fluid), "exact native base-capacity writer");
        String amount = fluid ? "2,097,152 mB" : "8,192 items";
        NBTTagCompound cell = new NBTTagCompound();
        cell.setTag("actualDisplay", actual);
        SimpleAssert.eq(
            "Current base buffer: " + amount,
            HologramEditorPropertiesPane
                .meOutputCapacityText(new HologramState(new NBTTagCompound()), new HologramState.Cell(0, cell), true),
            "native installed output shows its base capacity separately");
        SimpleAssert.eq(upgraded, capacity.getLong(value), "observed provider stays unchanged");
        capacity.setLong(value, ordinary);
        NBTTagCompound defaultDisplay = new NBTTagCompound();
        writer.invoke(null, observed, defaultDisplay);
        SimpleAssert.that(defaultDisplay.hasNoTags(), "native default remains ordinary");
    }

    private static void staysBlocked(Block host) throws Exception {
        Method gtState = Class.forName("com.miaokatze.gtmb.hologram.HologramExecutionEligibility")
            .getDeclaredMethod("gtState", NBTTagCompound.class, ItemStack.class, boolean.class, boolean.class);
        gtState.setAccessible(true);
        for (boolean tile : new boolean[] { false, true }) {
            try {
                gtState
                    .invoke(null, tile ? saved(BUS, 8192L) : itemTag(8192L), new ItemStack(host, 1, BUS), tile, false);
                throw new AssertionError("display data cannot make a base capacity eligible for execution");
            } catch (InvocationTargetException expected) {
                SimpleAssert.that(
                    expected.getCause() instanceof IOException && expected.getCause()
                        .getMessage()
                        .contains("baseCapacity"),
                    "execution still rejects baseCapacity itself");
            }
        }
    }

    private static void failClosed(Block host) {
        ItemStack input = new ItemStack(host, 1, BUS);
        NBTTagCompound good = saved(BUS, 8192L);
        NBTTagCompound untouched = (NBTTagCompound) good.copy();
        NBTTagCompound display = new NBTTagCompound();
        HologramGtMeOutputDisplay.observeStored(host, input, good, display);
        SimpleAssert.eq(8192L, HologramGtMeOutputDisplay.read(display, false), "correct saved identity");
        SimpleAssert.eq(
            1,
            display.func_150296_c()
                .size(),
            "only the typed scalar is written");
        SimpleAssert.eq(untouched, good, "saved payload is unmodified");
        SimpleAssert.that(!input.hasTagCompound(), "stack unmodified");
        reject(host, input, saved(FLUID, 2097152L));
        reject(Blocks.stone, input, good);
        reject(host, new ItemStack(host, 1, 2711), good);
        NBTTagCompound wrongOwner = saved(BUS, 8192L);
        wrongOwner.setString("id", "BaseMetaPipeEntity");
        reject(host, input, wrongOwner);
        NBTTagCompound wrongType = saved(BUS, 8192L);
        wrongType.setInteger("baseCapacity", 8192);
        reject(host, input, wrongType);
        reject(host, input, saved(BUS, -8192L));
        reject(host, input, saved(BUS, 9000L));
        NBTTagCompound wrongItem = new NBTTagCompound();
        wrongItem.setString("baseCapacity", "8192");
        input.setTagCompound(wrongItem);
        reject(host, input, good);
        input.setTagCompound(itemTag(32768L));
        reject(host, input, good);
        input.setTagCompound(null);
        GregTechAPI.METATILEENTITIES[BUS] = GregTechAPI.METATILEENTITIES[FLUID];
        reject(host, input, good);
    }

    private static void reject(Block host, ItemStack input, NBTTagCompound tag) {
        NBTTagCompound visible = new NBTTagCompound();
        HologramGtMeOutputDisplay.observeStored(host, input, tag, visible);
        SimpleAssert.that(visible.hasNoTags(), "unknown or mismatched recipe does not write any display hint");
    }

    private static NBTTagCompound saved(int id, long capacity) {
        NBTTagCompound tag = itemTag(capacity);
        tag.setString("id", "BaseMetaTileEntity");
        tag.setInteger("mID", id);
        return tag;
    }

    private static NBTTagCompound itemTag(long capacity) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("baseCapacity", capacity);
        return tag;
    }

    private static JsonObject row(int x, int machine, NBTTagCompound tile, NBTTagCompound item) throws Exception {
        JsonObject row = new JsonObject();
        row.addProperty("x", x);
        row.addProperty("y", 0);
        row.addProperty("z", 0);
        row.addProperty("block", HOST);
        row.addProperty("item", HOST);
        row.addProperty("damage", machine);
        if (tile != null) row.addProperty(
            "tileNbt",
            Base64.getEncoder()
                .encodeToString(HologramSnapshotCodec.compress(tile)));
        if (item != null) row.addProperty(
            "itemNbt",
            Base64.getEncoder()
                .encodeToString(HologramSnapshotCodec.compress(item)));
        return row;
    }

    private static void register(Object registry, Object value) throws ReflectiveOperationException {
        Method add = cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry.class
            .getDeclaredMethod("add", int.class, String.class, Object.class, java.util.BitSet.class);
        add.setAccessible(true);
        add.invoke(registry, 4067, HOST, value, new java.util.BitSet());
    }

    private static IMetaTileEntity prototype(Class<?> type) throws Exception {
        return (IMetaTileEntity) unsafe().allocateInstance(type);
    }

    private static sun.misc.Unsafe unsafe() throws Exception {
        Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        return (sun.misc.Unsafe) access.get(null);
    }
}
