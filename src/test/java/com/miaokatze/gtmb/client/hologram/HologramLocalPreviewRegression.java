package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Exercises detached display decoding: unknown construction identities stay outside validation and authority. */
public final class HologramLocalPreviewRegression {

    public static void run() throws Exception {
        admissionReceipts();
        compactDisplay();
        String json = "{\"blueprint\":{\"schema\":999,\"retainNbt\":false,\"retainCovers\":true,"
            + "\"retainedNbtKeys\":[\"configuration\"],\"cells\":["
            + "{\"x\":2,\"y\":-3,\"z\":4,\"block\":\"not_installed:display_only\","
            + "\"item\":\"not_installed:display_only\",\"facing\":5},"
            + "{\"x\":1,\"y\":1,\"z\":1,\"excluded\":true}]}}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8), original = bytes.clone();
        NBTTagCompound display = HologramBlueprintPreview.snapshot(bytes);
        SimpleAssert.that(Arrays.equals(original, bytes), "preview never mutates the original portable source");
        NBTTagList rows = display.getTagList("cells", 10);
        SimpleAssert.eq(1, rows.tagCount(), "excluded cells do not create display geometry");
        SimpleAssert.eq(
            "not_installed:display_only",
            rows.getCompoundTagAt(0)
                .getString("wantId"),
            "detached decode does not ask construction registry validation to approve the source");
        SimpleAssert.eq(
            -3,
            rows.getCompoundTagAt(0)
                .getInteger("dy"),
            "display preserves relative source offsets");
        SimpleAssert.eq(false, display.getBoolean("retainNbt"), "local demand receives source NBT selection");
        SimpleAssert.eq(true, display.getBoolean("retainCovers"), "local demand receives source cover selection");
        SimpleAssert.eq(
            "configuration",
            display.getTagList("retainedNbtKeys", 8)
                .getStringTagAt(0),
            "local demand receives selected NBT roots");
        SimpleAssert.eq(false, display.hasKey("planRevision"), "display decoding grants no reviewed plan revision");
        SimpleAssert
            .eq(false, display.hasKey("placementReviewNonce"), "display decoding grants no construction review");
        expectFailure(new String(new char[65]).replace('\0', '{') + new String(new char[65]).replace('\0', '}'));
        expectFailure("{\"blueprint\":{\"cells\":[{\"x\":60000001}]}}");
        expectFailure("{\"blueprint\":{\"cells\":[]}}");
        expectFailure("{\"blueprint\":{\"cells\":[{\"tileNbt\":\"not base64\"}]}}");
    }

    private static void compactDisplay() throws Exception {
        NBTTagCompound configuration = new NBTTagCompound();
        configuration.setString("configuration", "exact local display payload");
        String encoded = java.util.Base64.getEncoder()
            .encodeToString(com.miaokatze.gtmb.hologram.HologramSnapshotCodec.compress(configuration));
        String policy = "\"schema\":2,\"retainNbt\":true,\"retainCovers\":true,";
        String legacy = "{\"schema\":2,\"blueprint\":{" + policy
            + "\"cells\":[{\"x\":1,\"y\":2,\"z\":3,\"block\":\"missing:display\",\"itemNbt\":\""
            + encoded
            + "\"}],\"palette\":[]}}";
        String compact = "{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{" + policy
            + "\"cells\":[[0,{\"x\":1,\"y\":2,\"z\":3}]],\"palette\":[]},"
            + "\"materials\":[{\"block\":\"missing:display\",\"itemNbt\":0}],\"nbt\":[\""
            + encoded
            + "\"]}";
        SimpleAssert.eq(
            HologramBlueprintPreview.snapshot(legacy.getBytes(StandardCharsets.UTF_8)),
            HologramBlueprintPreview.snapshot(compact.getBytes(StandardCharsets.UTF_8)),
            "compact material and NBT references produce exactly the same detached display");
    }

    private static void admissionReceipts() {
        String first = new String(new char[64]).replace('\0', 'a');
        String second = new String(new char[64]).replace('\0', 'b');
        HologramContentAdmissionFence source = new HologramContentAdmissionFence(17, "first-source", first);
        SimpleAssert.eq(false, source.accept(18, 17, "first-source", first), "a newer task cannot inherit old ready");
        SimpleAssert.eq(
            false,
            source.accept(17, 0, "first-source", first),
            "a token-less control receipt cannot open world preview");
        SimpleAssert
            .eq(false, source.accept(17, 17, "other-source", first), "another selection cannot inherit admission");
        SimpleAssert.eq(
            false,
            source.accept(17, 17, "first-source", second),
            "a changed content digest cannot inherit admission");
        SimpleAssert.eq(
            true,
            source.accept(17, 17, "first-source", first),
            "the exact current UI selection may enter world preview");
        SimpleAssert.eq(false, source.accept(17, 17, "first-source", first), "a duplicate ready cannot reopen preview");
        HologramContentAdmissionFence cancelled = new HologramContentAdmissionFence(18, "second-source", second);
        cancelled.cancel();
        SimpleAssert.eq(
            false,
            cancelled.accept(18, 18, "second-source", second),
            "cancelled preparation cannot revive on late ready");
        HologramContentAdmissionFence serverHeld = new HologramContentAdmissionFence(19, "known-id", "");
        SimpleAssert.eq(
            false,
            serverHeld.accept(19, 19, "known-id", ""),
            "a known ID still needs a valid server content digest");
        SimpleAssert
            .eq(true, serverHeld.accept(19, 19, "known-id", first), "server-held source supplies its admission digest");
    }

    private static void expectFailure(String json) throws Exception {
        try {
            HologramBlueprintPreview.snapshot(json.getBytes(StandardCharsets.UTF_8));
            throw new AssertionError("Malformed/over-budget display data must be refused");
        } catch (IOException expected) {}
    }
}
