package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.Gson;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Field-complete copy and byte-count oracles for the load/capture optimizations. */
public final class HologramBlueprintCopyEncodingRegression {

    private static final Gson GSON = new Gson();

    private HologramBlueprintCopyEncodingRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramBlueprintCopyEncodingRegression.class, args)) return;
        run();
        System.out.println("Hologram blueprint copy/encoding regressions passed");
    }

    public static void run() throws Exception {
        typedCopy();
        encodedSize();
    }

    private static void typedCopy() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        samePersistentFields(source, HologramBlueprintRotation.copyPreset(source));
        fillScalars(source);
        HologramPresets.Cell cell = new HologramPresets.Cell();
        fillScalars(cell);
        cell.structureIds = new ArrayList<>(Arrays.asList("first", "second"));
        cell.allowedTargets = new ArrayList<>();
        cell.allowedTargets.add(new HologramPresets.Target(new ItemStack(Blocks.stone, 1, 3)));
        cell.frozenBlockResolved = true;
        cell.frozenBlock = Blocks.stone;
        cell.metadataIdentityBoundary = true;
        cell.frozenSensorSlots = new HashSet<>(Arrays.asList(1, 3));
        source.cells.add(cell);
        source.palette.add(cell);
        source.nativeBlueprint = new HologramBlueprintVersions.Metadata();
        fillScalars(source.nativeBlueprint);
        HologramPresets.Structure structure = new HologramPresets.Structure();
        fillScalars(structure);
        structure.channels.put("channel", 7);
        source.structures.add(structure);
        HologramAePolicy.Issue issue = new HologramAePolicy.Issue();
        fillScalars(issue);
        source.aeIssues.add(issue);
        HologramPresets.HintArea hint = new HologramPresets.HintArea();
        fillScalars(hint);
        source.hintAreas.add(hint);
        source.modVersions.put("unknown-provider", "1.2.3");
        source.modVersions.put("z-last-name", "2");
        source.modVersions.put("a-first-name", "3");
        source.retainedNbtKeys = new ArrayList<>(Arrays.asList("unknown-key"));
        String original = GSON.toJson(source);
        HologramPresets.Preset copy = HologramBlueprintRotation.copyPreset(source);
        samePersistentFields(source, copy);
        HologramPresets.Cell copied = copy.cells.get(0);
        SimpleAssert.that(
            cell.tileNbt == copied.tileNbt && cell.itemNbt == copied.itemNbt
                && cell.tileNbt == copy.palette.get(0).tileNbt,
            "immutable payload strings retain their original shared identity");
        SimpleAssert.that(
            !copied.frozenBlockResolved && copied.frozenBlock == null
                && copied.metadataIdentityBoundary == null
                && copied.frozenSensorSlots == null,
            "load copy clears all runtime-only registry/sensor state like Gson");
        copied.structureIds.add("copy-only");
        copied.allowedTargets.get(0).damage++;
        copy.palette.get(0).prompt = "copy palette";
        copy.structures.get(0).channels.put("copy-only", 9);
        copy.structures.get(0).name = "copy structure";
        copy.nativeBlueprint.gtmbVersion = "copy metadata";
        copy.aeIssues.get(0).message = "copy issue";
        copy.hintAreas.get(0).prompt = "copy hint";
        copy.modVersions.put("copy-only", "4");
        copy.retainedNbtKeys.clear();
        SimpleAssert.eq(original, GSON.toJson(source), "all mutable descriptors remain detached from the load source");
        source.retainedNbtKeys = null;
        source.nativeBlueprint = null;
        source.controllerFacing = null;
        source.fullyVoucherCovered = null;
        cell.structureIds = null;
        cell.allowedTargets = null;
        samePersistentFields(source, HologramBlueprintRotation.copyPreset(source));
    }

    private static void samePersistentFields(HologramPresets.Preset source, HologramPresets.Preset copy)
        throws IOException {
        HologramPresets.Preset gson = HologramPresets.copy(source);
        SimpleAssert.eq(
            GSON.toJsonTree(gson),
            GSON.toJsonTree(copy),
            "typed load copy matches every serialized field, default and admitted nullable value");
        SimpleAssert.eq(GSON.toJson(gson), GSON.toJson(copy), "typed copying preserves serialized map and field order");
        SimpleAssert.eq(
            HologramBlueprintContentHash.of(gson),
            HologramBlueprintContentHash.of(copy),
            "typed copying preserves the old content identity");
        SimpleAssert.that(
            Arrays.equals(
                HologramBlueprintCompactCodec.encode(gson, (done, total) -> {}),
                HologramBlueprintCompactCodec.encode(copy, (done, total) -> {})),
            "typed copying preserves exact compact dictionary JSON bytes");
    }

    private static void fillScalars(Object source) throws IllegalAccessException {
        int value = 11;
        for (Field field : source.getClass()
            .getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
            field.setAccessible(true);
            if (field.getType() == String.class) field.set(source, new String("field:" + field.getName()));
            else if (field.getType() == int.class || field.getType() == Integer.class) field.set(source, value++);
            else if (field.getType() == long.class) field.set(source, 12345L);
            else if (field.getType() == boolean.class) field.set(source, !field.getBoolean(source));
            else if (field.getType() == Boolean.class) field.set(source, Boolean.TRUE);
        }
    }

    private static void encodedSize() throws Exception {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("unknown-field", "猫猫\u0000");
        tag.setByte("byte", (byte) -7);
        tag.setShort("short", (short) -321);
        tag.setInteger("x", 456);
        tag.setLong("unknown-long", Long.MIN_VALUE);
        tag.setFloat("unknown-float", -0.0f);
        tag.setDouble("unknown-double", -0.0d);
        tag.setByteArray("unknown-bytes", new byte[] { -1, 0, 1 });
        tag.setIntArray("unknown-integers", new int[] { Integer.MIN_VALUE, 19 });
        NBTTagList nested = new NBTTagList();
        NBTTagCompound item = new NBTTagCompound();
        new ItemStack(Blocks.stone, 3, 1).writeToNBT(item);
        nested.appendTag(item);
        tag.setTag("nested", nested);
        NBTTagCompound original = (NBTTagCompound) tag.copy();
        HologramBlueprintNbt.Encoded captured = HologramBlueprintNbt.encodeCapturedWithSize(Blocks.stone, tag);
        assertEncoding(HologramBlueprintNbt.encodeCaptured(Blocks.stone, tag), captured);
        assertEncoding(
            HologramBlueprintNbt.encodeCanonical(Blocks.stone, tag),
            HologramBlueprintNbt.encodeCanonicalWithSize(Blocks.stone, tag));
        SimpleAssert.eq(original, tag, "counted transport never changes the native source tree");
        SimpleAssert.eq(
            "猫猫\u0000",
            HologramBlueprintNbt.decodeData(captured.value)
                .getString("unknown-field"),
            "unknown typed payload survives counted encoding");
        assertEncoding(null, HologramBlueprintNbt.encodeCapturedWithSize(Blocks.stone, null));
        assertEncoding(null, HologramBlueprintNbt.encodeCanonicalWithSize(Blocks.stone, null));
        // Root header (3), named byte-array header/length (8), final end tag (1).
        NBTTagCompound boundary = new NBTTagCompound();
        boundary.setByteArray("a", new byte[HologramBlueprintNbt.MAX_EXPANDED - 12]);
        HologramBlueprintNbt.Encoded full = HologramBlueprintNbt.encodeCapturedWithSize(Blocks.stone, boundary);
        SimpleAssert.eq(HologramBlueprintNbt.MAX_EXPANDED, full.expandedBytes, "exact expanded limit remains accepted");
        SimpleAssert
            .eq(full.expandedBytes, HologramBlueprintNbt.expandedBytes(full.value), "boundary count includes framing");
        boundary.setByteArray("a", new byte[HologramBlueprintNbt.MAX_EXPANDED - 11]);
        try {
            HologramBlueprintNbt.encodeCapturedWithSize(Blocks.stone, boundary);
            throw new AssertionError("counted encoding accepted an expanded byte over the limit");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("字节上限"),
                "single-payload failure keeps the bounded writer error");
        }
    }

    private static void assertEncoding(String expected, HologramBlueprintNbt.Encoded encoded) throws IOException {
        SimpleAssert.eq(expected, encoded.value, "counted encoding keeps exact legacy Base64/GZIP bytes");
        SimpleAssert.eq(
            HologramBlueprintNbt.expandedBytes(expected),
            encoded.expandedBytes,
            "writer byte count equals independent GZIP expansion length");
    }
}
