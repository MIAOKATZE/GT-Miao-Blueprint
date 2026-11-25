// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.entity.player.PlayerCapabilities;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Cursor;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Layout;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Plain;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerEncoder.Source;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Receiver;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Ack;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;
import com.mojang.authlib.GameProfile;

public final class HologramProjectedLayerEncoderRegression {

    private static final UUID ID = UUID.fromString("31415926-5358-4979-9323-846264338327");

    private HologramProjectedLayerEncoderRegression() {}

    public static void main(String[] args) throws Exception {
        if (MinecraftTestBootstrap.enter(HologramProjectedLayerEncoderRegression.class, args)) run();
    }

    public static void run() throws IOException {
        exactProof();
        packedLocalSyntaxProof();
        canonicalProjectedArea();
        deterministicStream();
        firstPrefixAddsFrame();
        rejectChangedTraversal();
        rejectFalseCapturedFacts();
    }

    private static void exactProof() throws IOException {
        byte[] exact = fixture("", "");
        var inspected = HologramProjectedLayerEncoder.inspect(exact, true);
        var builder = inspected.builder();
        SimpleAssert.that(builder != null, "plain compact archive is a candidate");
        SimpleAssert.eq(null, builder.finish(), "no certificate without visiting every real ordinal");
        builder.row(0, inspected.rows().materialIndex(0));
        var proof = builder.finish();
        SimpleAssert.that(proof != null && proof.sameCapture(exact) && !proof.sameCapture(exact.clone()),
            "certificate only applies to these exact capture bytes");
        SimpleAssert.eq(1, proof.count(Plain.STONE), "source has exactly one paid plain identity");
        SimpleAssert.eq(null, HologramProjectedLayerEncoder.inspect(exact, false).builder(), "not indexed private");
        for (String field : new String[] { ",\"excluded\":true", ",\"controller\":true", ",\"tileNbt\":\"x\"",
            ",\"itemNbt\":\"x\"", ",\"facing\":2", ",\"allowedTargets\":[]", ",\"neverDropMe\":0",
            ",\"structureIds\":[null]", ",\"structureIds\":\"[]\"", ",\"structureId\":123" })
            SimpleAssert.that(rejected(fixture("", field)),
                "unsupported descriptor must not be normalized away: " + field);
        for (String field : new String[] { ",\"fullyVoucherCovered\":true", ",\"controllerId\":55",
            ",\"structures\":[{}]", ",\"retainedNbtKeys\":[]", ",\"unexpectedSemanticExtension\":1" })
            SimpleAssert.eq(null, HologramProjectedLayerEncoder.inspect(fixture(field, ""), true).builder(),
                "unknown author policy cannot look like plain input: " + field);
        var badVisit = HologramProjectedLayerEncoder.inspect(exact, true).builder();
        badVisit.row(2, 0);
        badVisit.row(0, 0);
        SimpleAssert.eq(null, badVisit.finish(), "mismatched visitor cannot mint an exact proof");
        SimpleAssert.eq(null, certificate(fixture("", ",\"metadata\":0")),
            "duplicate fields fail the strict original syntax proof even if the display reader recovers");
    }

    private static void packedLocalSyntaxProof() throws IOException {
        StringBuilder json = new StringBuilder("{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\","
            + "\"blueprint\":{\"schema\":2,\"cells\":[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) json.append(',');
            json.append("[0,{\"x\":").append(i % 40).append(",\"z\":").append(i / 40).append("}]");
        }
        String source = json.append("],\"palette\":[]},\"materials\":[{\"item\":\"minecraft:stone\","
            + "\"block\":\"minecraft:stone\",\"metadata\":0}],\"nbt\":[]}").toString();
        byte[] exact = source.getBytes(StandardCharsets.UTF_8);
        SimpleAssert.that(exact.length > 16_384, "a large canonical literal geometry fixture");
        var canonical = HologramProjectedLayerEncoder.inspectLocal(exact, true);
        SimpleAssert.eq(HologramBlueprintCompactCodec.StoredSyntax.VALID, canonical.rows().storedSyntax,
            "literal grammar plus retained syntax prove the original without reparsing the removed geometry");
        SimpleAssert.eq(1000, certificate(canonical).cells(), "exact original proof retains every displayed row");
        SimpleAssert.eq(HologramBlueprintCompactCodec.StoredSyntax.UNCHECKED,
            HologramProjectedLayerEncoder.inspectLocal(exact, false).rows().storedSyntax,
            "ordinary display does not run construction-only retained syntax proof");

        byte[] duplicate = source.replace("\"metadata\":0", "\"metadata\":0,\"metadata\":0")
            .getBytes(StandardCharsets.UTF_8);
        var malformed = HologramProjectedLayerEncoder.inspectLocal(duplicate, true);
        SimpleAssert.eq(HologramBlueprintCompactCodec.StoredSyntax.INVALID, malformed.rows().storedSyntax,
            "duplicate retained dictionary key cannot mint exact proof");
        SimpleAssert.eq(1000, malformed.rows().cells(), "historical preview remains intact");
        SimpleAssert.eq(null, certificate(malformed), "invalid original key syntax fails closed for construction");
        SimpleAssert.eq(null, localCertificate(("\uFEFF" + source).getBytes(StandardCharsets.UTF_8)),
            "a BOM is still not the canonical local opening");
        SimpleAssert.eq(null, localCertificate(source.replace("\"x\":0,", "\"x\":0,\"x\":0,")
            .getBytes(StandardCharsets.UTF_8)), "duplicate coordinate fields cannot use the literal shortcut");

        var small = HologramProjectedLayerEncoder.inspectLocal(fixture("", ""), true);
        SimpleAssert.eq(HologramBlueprintCompactCodec.StoredSyntax.VALID, small.rows().storedSyntax,
            "the same exact proof covers small canonical local blueprints");
        SimpleAssert.that(certificate(small) != null, "small canonical input remains eligible");
    }

    /** Use the real projection and stamped compact save writer, not handcrafted positive JSON. */
    private static void canonicalProjectedArea() throws IOException {
        HologramSession session = areaSession();
        HologramPresets.Preset saved = HologramBlueprintProjection.blueprint(session, "One charged area cell");
        HologramBlueprintVersions.stampSaved(saved);
        SimpleAssert.eq("area", saved.controllerType, "ordinary area retains its descriptive source type");
        SimpleAssert.eq(-1, saved.controllerId, "no controller authorization");
        SimpleAssert.eq(1, saved.cells.size(), "one geometry row");
        HologramPresets.Cell row = saved.cells.get(0);
        SimpleAssert.eq(Collections.emptyList(), row.structureIds, "projection writes empty membership");
        SimpleAssert.eq("minecraft:stone", row.block, "geometry retains the actual world block identity");
        SimpleAssert.eq(1, saved.palette.size(), "paid materials appear in the actual writer's descriptive palette");
        HologramPresets.Cell declaration = saved.palette.get(0);
        SimpleAssert.eq("minecraft:stone", declaration.item, "palette entry is item-only");
        SimpleAssert.eq(null, declaration.block, "projection does not turn a palette declaration into geometry");

        byte[] exact = archive(saved);
        var inspected = HologramProjectedLayerEncoder.inspect(exact, true);
        SimpleAssert.eq(2, inspected.rows().materials(), "compact writer interns placement and palette separately");
        var proof = certificate(exact);
        SimpleAssert.that(proof != null && proof.sameCapture(exact), "actual private compact save is eligible");
        SimpleAssert.eq(1, proof.cells(), "descriptive palette is never counted as geometry");
        SimpleAssert.eq(1, proof.count(Plain.STONE), "real stored area pays for one block, not its palette reference");

        String privateId = "local_" + ID;
        var frozen = HologramLocalBlueprintDocument.fromCapture(saved)
            .prepareSaveDraft(new NBTTagCompound(), "plain-private-area", ID, privateId);
        byte[] normalSaved = frozen.artifact();
        SimpleAssert.eq(privateId, HologramBlueprintCompactCodec.displayGeometry(normalSaved).blueprint.get("id")
            .getAsString(), "default private save writes the historical selected-file ID");
        var localProof = certificate(normalSaved);
        SimpleAssert.that(localProof != null && localProof.sameCapture(normalSaved),
            "normal private local Save prepares eligible exact compact bytes");
        SimpleAssert.eq(1, localProof.cells(), "local save reuses the same true one-cell geometry");

        row.structureId = "controller";
        SimpleAssert.eq(null, certificate(archive(saved)), "active singular memberships remain ineligible");
        row.structureId = "";
        row.structureIds = Collections.singletonList("controller");
        SimpleAssert.eq(null, certificate(archive(saved)), "active plural memberships remain ineligible");
        row.structureIds = null;
        SimpleAssert.that(certificate(archive(saved)) != null, "absent membership and an empty name are inert");
        saved.controllerFacing = 1;
        SimpleAssert.eq(null, certificate(archive(saved)), "area name cannot authorize an active facing");
        saved.controllerFacing = null;
        saved.controllerType = "controller";
        SimpleAssert.eq(null, certificate(archive(saved)), "unrecognized controller type is not inert");
        saved.controllerType = "area";

        declaration.x = 1;
        SimpleAssert.eq(null, certificate(archive(saved)), "palette declarations never carry positions away from origin");
        declaration.x = 0;
        declaration.controller = true;
        SimpleAssert.eq(null, certificate(archive(saved)), "palette cannot carry controller semantics");
        declaration.controller = false;
        declaration.item = "minecraft:glass";
        SimpleAssert.eq(null, certificate(archive(saved)), "palette must describe a variant actually used in geometry");
        declaration.item = "minecraft:stone";
        saved.palette.add(declaration);
        SimpleAssert.eq(null, certificate(archive(saved)), "duplicate variant declarations are not canonical");
        saved.palette.remove(1);
        saved.cells.set(0, declaration);
        SimpleAssert.eq(null, certificate(archive(saved)), "item-only descriptive reference cannot serve as geometry");
    }

    private static HologramSession areaSession() throws IOException {
        EntityPlayerMP player;
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field.get(null);
            player = (EntityPlayerMP) unsafe.allocateInstance(EntityPlayerMP.class);
            player.worldObj = (HologramLocalEditRegression.CountingWorld) unsafe
                .allocateInstance(HologramLocalEditRegression.CountingWorld.class);
            for (Field profile : EntityPlayer.class.getDeclaredFields()) if (profile.getType() == GameProfile.class) {
                profile.setAccessible(true);
                profile.set(player, new GameProfile(ID, "plain-private-area"));
            }
        } catch (ReflectiveOperationException unavailable) {
            throw new IOException("Cannot build offline area projection fixture", unavailable);
        }
        player.inventory = new InventoryPlayer(player);
        player.capabilities = new PlayerCapabilities();
        HologramSession session = new HologramSession(player, 0, 64, 0, false, 2);
        session.virtual = true;
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.schema = 2;
        session.virtualPreset.controllerType = "area";
        ItemStack stone = new ItemStack(Blocks.stone);
        session.capture = new HologramCapture(null, stone);
        HologramCapture.Cell cell = new HologramCapture.Cell(null, 0, 64, 0);
        cell.block = Blocks.stone;
        cell.blueprintBlock = "minecraft:stone";
        cell.blueprintItem = "minecraft:stone";
        cell.userPosition = true;
        session.capture.cells.add(cell);
        session.pins.put(0, stone);
        return session;
    }

    private static byte[] archive(HologramPresets.Preset saved) throws IOException {
        return HologramDraftEncoding.encode(saved, (done, total) -> {});
    }

    private static HologramProjectedLayerEncoder.Evidence certificate(byte[] exact) {
        try {
            return certificate(HologramProjectedLayerEncoder.inspect(exact, true));
        } catch (IOException unsupported) {
            return null;
        }
    }

    private static HologramProjectedLayerEncoder.Evidence localCertificate(byte[] exact) {
        try {
            return certificate(HologramProjectedLayerEncoder.inspectLocal(exact, true));
        } catch (IOException unsupported) {
            return null;
        }
    }

    private static HologramProjectedLayerEncoder.Evidence certificate(HologramProjectedLayerEncoder.Inspection inspected) {
        var builder = inspected.builder();
        if (builder == null) return null;
        for (int i = 0; i < inspected.rows().cells(); i++) builder.row(i, inspected.rows().materialIndex(i));
        return builder.finish();
    }

    private static boolean rejected(byte[] candidate) {
        try {
            return HologramProjectedLayerEncoder.inspect(candidate, true).builder() == null;
        } catch (IOException invalid) {
            return true;
        }
    }

    private static byte[] fixture(String header, String material) {
        return ("{\"schema\":2,\"encoding\":\"gtmb-dictionary-1\",\"blueprint\":{\"schema\":2,"
            + "\"cells\":[[0,{\"x\":0,\"y\":0,\"z\":0}]],\"palette\":[]"
            + header + "},\"materials\":[{\"item\":\"minecraft:stone\",\"block\":\"minecraft:stone\","
            + "\"metadata\":0" + material + "}],\"nbt\":[]}").getBytes(StandardCharsets.UTF_8);
    }

    private static void deterministicStream() throws IOException {
        AtomicInteger passes = new AtomicInteger(), visited = new AtomicInteger();
        Source source = new Fixture(passes, false);
        var prepared = HologramProjectedLayerEncoder.prepare(ID, 0, 64, 0, source, () -> true,
            value -> visited.set((int) value));
        SimpleAssert.eq(0, visited.get(), "preparation never visits any source geometry");
        SimpleAssert.eq(0, passes.get(), "manifest preparation opens no source cursor");
        byte[] first = HologramProjectedLayerWire.encodeAck(new Ack(ID, 0, 0, 1, false, false));
        SimpleAssert.that(!prepared.onAck(first, () -> true).accepted(), "no credit before handing off manifest");
        byte[] manifestBytes = prepared.takeManifest();
        SimpleAssert.eq(null, prepared.takeManifest(), "only one manifest handoff");
        var manifest = HologramProjectedLayerWire.decodeManifest(manifestBytes);
        SimpleAssert.eq(3, manifest.header.slices, "short first slice, followed by this layer and next layer tails");
        SimpleAssert.eq(2, manifest.header.palette.size(), "manifest aggregates material quantities only");
        Receiver receiver = new Receiver(manifest);
        int sum = 0;
        for (int i = 0; i < 3; i++) {
            byte[] ack = HologramProjectedLayerWire.encodeAck(receiver.ack());
            var reply = prepared.onAck(ack, () -> true);
            SimpleAssert.that(reply.accepted() && !reply.transferComplete() && reply.nextFrame().length <= 6208,
                "credit emits exactly one bounded frame");
            var slice = HologramProjectedLayerWire.decodeSlice(reply.nextFrame());
            SimpleAssert.eq(i, slice.sequence, "ordered sequences");
            SimpleAssert.eq(i < 2 ? 64 : 65, slice.y, "no cross-layer frames");
            SimpleAssert.eq(i == 0 ? 32 : i == 1 ? 481 : 9, slice.size(), "exact prefix then tails");
            sum += slice.size();
            SimpleAssert.eq(Offer.ACCEPTED, receiver.offer(reply.nextFrame()), "receiver verifies bounds and ordered counts");
            SimpleAssert.that(receiver.settle(receiver.pending()), "settle exact frame");
        }
        SimpleAssert.eq(522, sum, "all declared operations appear once");
        SimpleAssert.eq(1, passes.get(), "only one forward sending cursor opens, after credit");
        SimpleAssert.eq(522, visited.get(), "progress follows the only forward stream");
        var end = prepared.onAck(HologramProjectedLayerWire.encodeAck(receiver.ack()), () -> true);
        SimpleAssert.that(end.accepted() && end.transferComplete() && end.nextFrame() == null,
            "final ACK ends only transfer and sends no gameplay receipt");
    }

    private static void firstPrefixAddsFrame() throws IOException {
        var prepared = HologramProjectedLayerEncoder.prepare(ID, 0, 64, 0,
            new Fixture(new AtomicInteger(), false, 512, 0), () -> true, null);
        var manifest = HologramProjectedLayerWire.decodeManifest(prepared.takeManifest());
        SimpleAssert.eq(2, manifest.header.slices, "one full old frame must now split in manifest too");
        Receiver receiver = new Receiver(manifest);
        for (int size : new int[] { 32, 480 }) {
            var reply = prepared.onAck(HologramProjectedLayerWire.encodeAck(receiver.ack()), () -> true);
            var slice = HologramProjectedLayerWire.decodeSlice(reply.nextFrame());
            SimpleAssert.eq(size, slice.size(), "initial frame remains a short latency prefix, not a placement quota");
            SimpleAssert.eq(Offer.ACCEPTED, receiver.offer(reply.nextFrame()), "same bounded forward partition");
            SimpleAssert.that(receiver.settle(receiver.pending()), "small initial frame settles normally");
        }
        var end = prepared.onAck(HologramProjectedLayerWire.encodeAck(receiver.ack()), () -> true);
        SimpleAssert.that(end.transferComplete(), "repartitioned manifest has the same complete cell count");
    }

    private static void rejectChangedTraversal() throws IOException {
        AtomicInteger opened = new AtomicInteger();
        Source forwardOnly = new Fixture(opened, false) {
            @Override
            public Cursor open(boolean reverse) {
                if (reverse) throw new AssertionError("no reverse or whole-source commitment pass");
                return super.open(false);
            }
        };
        var changed = HologramProjectedLayerEncoder.prepare(ID, 0, 64, 0, forwardOnly, () -> true, null);
        SimpleAssert.eq(0, opened.get(), "even first source read waits until frame credit");
        changed.takeManifest();
        byte[] first = HologramProjectedLayerWire.encodeAck(new Ack(ID, 0, 0, 1, false, false));
        SimpleAssert.that(changed.onAck(first, () -> true).nextFrame() != null,
            "first frame needs no traversal of later geometry");
        SimpleAssert.eq(1, opened.get(), "first frame reads a single forward cursor");
        changed.close();
        try {
            HologramProjectedLayerEncoder.prepare(ID, 0, 64, 0, new Fixture(new AtomicInteger(), false),
                () -> false, null);
            throw new AssertionError("retired owner cannot prepare a manifest");
        } catch (InterruptedIOException accepted) {}
    }

    private static void rejectFalseCapturedFacts() throws IOException {
        var fixture = new Fixture(new AtomicInteger(), false);
        for (boolean wrongMaterials : new boolean[] { false, true }) {
            var mismatch = new Source() {

                @Override
                public Layout layout() {
                    int[] counts = new int[Plain.values().length];
                    counts[Plain.STONE.ordinal()] = wrongMaterials ? 260 : 261;
                    counts[Plain.GLASS.ordinal()] = 522 - counts[Plain.STONE.ordinal()];
                    return new Layout(new int[] { wrongMaterials ? -8 : -9, 64, 0 }, new int[] { 8, 65, 30 },
                        new int[] { 513, 9 }, counts);
                }

                @Override
                public Cursor open(boolean reverse) throws IOException {
                    if (reverse) throw new AssertionError("no backward verification pass");
                    return fixture.open(false);
                }
            };
            try {
                var prepared = HologramProjectedLayerEncoder.prepare(ID, 0, 64, 0, mismatch, () -> true, null);
                var receiver = new Receiver(HologramProjectedLayerWire.decodeManifest(prepared.takeManifest()));
                while (!receiver.finished()) {
                    var reply = prepared.onAck(HologramProjectedLayerWire.encodeAck(receiver.ack()), () -> true);
                    receiver.offer(reply.nextFrame());
                    receiver.settle(receiver.pending());
                }
                throw new AssertionError("false captured facts fail during the sole forward stream");
            } catch (IOException accepted) {}
        }
    }

    /** Synthetic regenerable traversal: deliberately no complete geometry array in the producer or fixture. */
    private static class Fixture implements Source {

        private final AtomicInteger passes;
        private final boolean poison;
        private final int firstLevel, secondLevel;

        Fixture(AtomicInteger passes, boolean poison) {
            this(passes, poison, 513, 9);
        }

        Fixture(AtomicInteger passes, boolean poison, int firstLevel, int secondLevel) {
            this.passes = passes;
            this.poison = poison;
            this.firstLevel = firstLevel;
            this.secondLevel = secondLevel;
        }

        private int cells() {
            return firstLevel + secondLevel;
        }

        @Override
        public Layout layout() {
            int[] counts = new int[Plain.values().length];
            counts[Plain.STONE.ordinal()] = (cells() + 1) / 2;
            counts[Plain.GLASS.ordinal()] = cells() / 2;
            return new Layout(new int[] { -8, 64, 0 },
                new int[] { 8, secondLevel == 0 ? 64 : 65, (Math.max(firstLevel, secondLevel) - 1) / 17 },
                secondLevel == 0 ? new int[] { firstLevel } : new int[] { firstLevel, secondLevel }, counts);
        }

        @Override
        public Cursor open(boolean reverse) {
            boolean changed = passes.incrementAndGet() == 2 && poison;
            return new Cursor() {

                int at = reverse ? cells() : -1;

                @Override
                public boolean next() {
                    at += reverse ? -1 : 1;
                    return at >= 0 && at < cells();
                }

                @Override
                public int x() {
                    return (at < firstLevel ? at : at - firstLevel) % 17 - 8;
                }

                @Override
                public int y() {
                    return at < firstLevel ? 64 : 65;
                }

                @Override
                public int z() {
                    return (at < firstLevel ? at : at - firstLevel) / 17;
                }

                @Override
                public Plain kind() {
                    return ((at + (changed && at == 0 ? 1 : 0)) & 1) == 0 ? Plain.STONE : Plain.GLASS;
                }
            };
        }
    }
}
