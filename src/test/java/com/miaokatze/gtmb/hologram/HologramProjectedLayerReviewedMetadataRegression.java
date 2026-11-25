// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.entity.player.EntityPlayerMP;

import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.End;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Kind;
import com.miaokatze.gtmb.hologram.HologramConstructionTaskGate.Request;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Offer;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerPlan.Receiver;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerService.StockContract;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerVariantAdmission.Failure;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerVariantAdmission.Refused;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerVariantAdmission.Source;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Header;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Manifest;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Slice;
import com.miaokatze.gtmb.hologram.HologramProjectedLayerWire.Variant;
import com.miaokatze.gtmb.hologram.HologramProjectedVariantPricing.Row;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Exact mixed used dictionary, real profile/ownership boundaries, no fictional native provider or bulk placement. */
public final class HologramProjectedLayerReviewedMetadataRegression {

    private static final int CELLS = 123_369, SLICES = 253;
    private static final UUID REQUEST = new UUID(0x47544d21L, CELLS);

    private HologramProjectedLayerReviewedMetadataRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramProjectedLayerReviewedMetadataRegression.class, args)) return;
        run();
        System.out.println("reviewed_layer_metadata_scope_123369: passed");
    }

    public static void run() throws Exception {
        Manifest original = exactMetadataAndProfileSeparation();
        serverActorScope(original, false);
    }

    /** Call only after all five exact native registrations are genuinely loaded; no mock-provider substitution. */
    public static void runWithRegisteredProviders() throws Exception {
        serverActorScope(exactMetadataAndProfileSeparation(), true);
    }

    private static Manifest exactMetadataAndProfileSeparation() throws Exception {
        List<Row> rows = metadata();
        ArrayList<Variant> descriptors = new ArrayList<>();
        int count = 0;
        long occurrenceBytes = 0;
        for (Row row : rows) {
            var schema = HologramProjectedVariantPricing.inspect(row, true);
            count += row.count;
            occurrenceBytes += (long) schema.bytes * row.count;
            descriptors.add(Variant.reviewed(row));
        }
        require(rows.size() == 29 && count == CELLS && occurrenceBytes == 282_395L,
            "all actually used official dictionary rows/counts, including native fields, without cell geometry");

        Header header = header(descriptors);
        // Only two exact observed coordinates of the archive are exercised. There is no complete construction.
        byte[] first = HologramProjectedLayerWire.encodeSlice(
            new Slice(REQUEST, 0, 64, new int[] { 35, 0, 0, 36, 0, 0 }));
        Manifest original = new Manifest(header);
        byte[] serialized = HologramProjectedLayerWire.encodeReviewedManifest(original);
        require(serialized.length == 9_737 && serialized.length <= HologramProjectedLayerWire.MAX_REVIEWED_MANIFEST_BYTES,
            "only the exact bounded dictionary/envelope is serialized, not 123369 coordinates");
        Manifest decoded = HologramProjectedLayerWire.decodeReviewedManifest(serialized);
        require(decoded.header.profile == HologramProjectedLayerWire.REVIEWED_PROFILE && decoded.header.cells == CELLS
            && Arrays.equals(serialized, HologramProjectedLayerWire.encodeReviewedManifest(decoded))
            && new Receiver(decoded).offer(first) == Offer.ACCEPTED,
            "explicit profile binds the unchanged coordinates/variant-slice format to the authorized metadata");
        expect(() -> HologramProjectedLayerWire.decodeManifest(serialized));
        expect(() -> HologramProjectedLayerWire.encodeManifest(decoded));
        expect(() -> new Header(REQUEST, 0, 64, 0, 0, 64, 0, 132, 98, 132, CELLS, SLICES, descriptors));
        byte[] padded = Arrays.copyOf(serialized, HologramProjectedLayerWire.MAX_REVIEWED_MANIFEST_BYTES + 1);
        expect(() -> HologramProjectedLayerWire.decodeReviewedManifest(padded));

        var v1 = new Variant("minecraft:stone", 0, "minecraft:stone", 0, -1, false, "", "", 1);
        var v1Header = new Header(REQUEST, 0, 64, 0, 0, 64, 0, 0, 64, 0, 1, 1, Collections.singletonList(v1));
        byte[] plain = HologramProjectedLayerWire.encodeManifest(new Manifest(v1Header));
        expect(() -> HologramProjectedLayerWire.decodeReviewedManifest(plain));

        Row lava = rows.get(6), glaxx = rows.get(19);
        require("Ztones:tile.laveBlock".equals(lava.block) && "Ztones:tile.glaxx".equals(glaxx.block),
            "fixture preserves original variant ordinals");
        descriptors.set(6, Variant.reviewed(withCount(lava, lava.count - 1)));
        descriptors.set(19, Variant.reviewed(withCount(glaxx, glaxx.count + 1)));
        var changed = new Receiver(new Manifest(header(descriptors)));
        require(changed.offer(first) == Offer.ACCEPTED,
            "no fingerprint binds a future stream; each manifest retains independent quantities and server authority");
        return decoded;
    }

    private static void serverActorScope(Manifest reviewed, boolean registeredProviders) throws Exception {
        Field access = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        access.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) access.get(null);
        var world = HologramPlacementFixtureWorld.create(unsafe);
        EntityPlayerMP player = HologramLibraryServiceRegression.placementPlayer(unsafe, world, "ProfileTwoScope");
        player.posY = 64;
        player.capabilities.allowEdit = true;
        Path previous = HologramConstructionJournal.STORAGE_ROOT.get();
        Path scratch = Files.createTempDirectory("gtmb-reviewed-meta-");
        HologramConstructionJournal.STORAGE_ROOT.set(scratch);
        try {
            HologramWorkspace.publishActionEndpoint(player, false);
            var endpoint = HologramWorkspace.captureActionEndpoint(player.playerNetServerHandler);
            var gate = HologramWorkspace.constructionGate(endpoint);
            require(gate != null, "real server lifecycle and existing construction owner");
            var first = gate.acquire(endpoint.constructionScope, new Request(REQUEST, 1, Kind.PROJECTED_LAYER),
                false, false).lease;
            player.capabilities.isCreativeMode = true;
            var creative = HologramProjectedLayerService.reviewScopedVariants(endpoint, first, reviewed);
            require(creative.source() == Source.AUTHENTICATED_CREATIVE && creative.reviewedRows() == 0
                && creative.step(Long.MIN_VALUE) == null && !HologramProjectedLayerService.active(player)
                && world.writes().isEmpty(), "only actual server creative capability defines this metadata scope");
            if (registeredProviders) {
                for (int i = 1; i <= reviewed.header.palette.size(); i++) {
                    require(creative.step(Long.MAX_VALUE) == null && creative.reviewedRows() == i,
                        "validated at most one genuine native palette row per step");
                }
                var proof = creative.step(Long.MAX_VALUE);
                var other = HologramProjectedLayerWire.decodeReviewedManifest(
                    HologramProjectedLayerWire.encodeReviewedManifest(reviewed));
                require(proof != null && proof.currentFor(reviewed, first) && !proof.currentFor(other, first)
                    && proof.catalog.expandedByOccurrence == 282_395L
                    && !HologramProjectedLayerService.active(player) && world.writes().isEmpty(),
                    "even the accepted actual creative catalog is bound and cannot start decorated world mutation");
            }
            expect(() -> HologramProjectedLayerService.begin(
                player, reviewed, StockContract.OBSERVE_WHOLE_MAIN_THEN_DEBIT_PER_CELL));
            player.capabilities.isCreativeMode = false;
            reject(() -> creative.step(Long.MAX_VALUE), Failure.OWNER_CHANGED);
            player.capabilities.isCreativeMode = true;
            reject(() -> creative.step(Long.MAX_VALUE), Failure.OWNER_CHANGED);
            player.capabilities.isCreativeMode = false;
            require(gate.finish(first, End.FAILED), "test terminates first exact lease without world work");

            var second = gate.acquire(endpoint.constructionScope, new Request(REQUEST, 2, Kind.PROJECTED_LAYER),
                false, false).lease;
            var stock = new HologramMaterials.Settings();
            HologramMaterials.write(player.getHeldItem(), stock);
            reject(() -> HologramProjectedLayerService.reviewScopedVariants(endpoint, second, reviewed),
                Failure.UNSUPPORTED_STOCK_SOURCE);
            stock.containers = stock.me = false;
            HologramMaterials.write(player.getHeldItem(), stock);
            var survival = HologramProjectedLayerService.reviewScopedVariants(endpoint, second, reviewed);
            require(survival.source() == Source.OBSERVED_MAIN_ONLY && survival.reviewedRows() == 0,
                "explicit legitimate main storage selects dry proof; default wireless/container flags are not adopted");
            if (registeredProviders) {
                for (int i = 0; i < reviewed.header.palette.size(); i++)
                    require(survival.step(Long.MAX_VALUE) == null, "no source proof before full metadata");
                reject(() -> survival.step(Long.MAX_VALUE), Failure.MISSING_STOCK);
            }
            HologramWorkspace.publishActionDimension(player);
            reject(() -> survival.step(Long.MAX_VALUE), Failure.OWNER_CHANGED);
            require(!HologramProjectedLayerService.active(player) && world.writes().isEmpty(),
                "same-object endpoint rotation invalidates this metadata plan, not a hidden world lane");
        } finally {
            HologramWorkspace.revokeActionEndpoint(player);
            player.playerNetServerHandler.netManager.channel().close();
            if (previous == null) HologramConstructionJournal.STORAGE_ROOT.remove();
            else HologramConstructionJournal.STORAGE_ROOT.set(previous);
            try (Stream<Path> paths = Files.walk(scratch)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static Header header(List<Variant> descriptors) throws IOException {
        return Header.reviewed(REQUEST, 0, 64, 0, 0, 64, 0, 132, 98, 132, CELLS, SLICES, descriptors);
    }

    private static Row withCount(Row row, int count) {
        return new Row(row.block, row.item, row.metadata, row.damage, row.facing, row.controller,
            row.tileNbt, row.itemNbt, count);
    }

    private static List<Row> metadata() throws IOException {
        InputStream input = java.nio.file.Files.newInputStream(java.nio.file.Paths.get(
            System.getProperty("gtmb.reviewedMetadata.fixture")));
        if (input == null) throw new IOException("Missing exact saved metadata fixture");
        ArrayList<Row> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) continue;
                String[] fields = line.split("\t", -1);
                if (fields.length != 7) throw new IOException("Invalid exact saved metadata fixture");
                rows.add(new Row(fields[0], fields[1], Integer.parseInt(fields[2]), Integer.parseInt(fields[3]), -1,
                    false, field(fields[5]), field(fields[6]), Integer.parseInt(fields[4])));
            }
        }
        return rows;
    }

    private static String field(String value) {
        return "-".equals(value) ? "" : value;
    }

    private interface Checked {

        void run() throws IOException;
    }

    private static void reject(Checked action, Failure reason) throws IOException {
        try {
            action.run();
        } catch (Refused rejected) {
            if (rejected.reason == reason) return;
            throw rejected;
        }
        throw new AssertionError("Unexpected scoped metadata authority");
    }

    private static void expect(Checked action) throws IOException {
        try {
            action.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("Unexpected profile or geometry admission");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
