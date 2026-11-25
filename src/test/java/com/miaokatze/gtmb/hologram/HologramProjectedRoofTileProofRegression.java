// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramProjectedRoofTileProof.Declared;
import com.miaokatze.gtmb.hologram.HologramProjectedVariantPricing.Row;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Schema/canonicalization is offline. Genuine ArchitectureCraft/Postea support is a separate read-only fixture. */
public final class HologramProjectedRoofTileProofRegression {

    private HologramProjectedRoofTileProofRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramProjectedRoofTileProofRegression.class, args)) return;
        run();
    }

    public static void run() throws Exception {
        List<Row> inventory = rows();
        var task = new FutureTask<List<Declared>>(() -> declarations(inventory));
        new Thread(task, "GTMB Roof Tile bounded declaration fixture").start();
        List<Declared> declarations = task.get(5, TimeUnit.SECONDS);
        SimpleAssert.eq(15, declarations.size(), "only the fifteen archived shape-zero tuples");
        SimpleAssert.eq(1156, declarations.stream().filter(d -> "Ztones:tile.laveBlock".equals(d.base))
            .mapToInt(d -> d.occurrences).sum(), "actual sample Roof Tile Lave12 occurrences");
        SimpleAssert.eq(3, declarations.stream().filter(d -> "chisel:hempcrete".equals(d.base))
            .mapToInt(d -> d.occurrences).sum(), "actual sample Roof Tile Hempcrete15 occurrences");

        Row roof = inventory.get(7);
        Declared exact = HologramProjectedRoofTileProof.declare(roof);
        SimpleAssert.eq(260, exact.occurrences, "unchanged supplied archive count");
        SimpleAssert.eq(1, exact.side, "exact source side");
        SimpleAssert.eq(3, exact.turn, "exact source turn");
        SimpleAssert.that(decoded(exact, exact.base, exact.metadata, 0, exact.side, exact.turn),
            "decoded observation may equal the independent declaration");
        SimpleAssert.that(!decoded(exact, "minecraft:planks", 0, 0, exact.side, exact.turn)
            && !decoded(exact, exact.base, 11, 0, exact.side, exact.turn)
            && !decoded(exact, exact.base, exact.metadata, 18, exact.side, exact.turn)
            && !decoded(exact, exact.base, exact.metadata, 0, 0, exact.turn)
            && !decoded(exact, exact.base, exact.metadata, 0, exact.side, 0),
            "decoded provider fallback or changed metadata/shape/orientation cannot define its own expectation");
        SimpleAssert.that(!exact.matchesDecoded(exact.base, exact.metadata, 0, exact.side, exact.turn,
            true, 0, 0, false)
            && !exact.matchesDecoded(exact.base, exact.metadata, 0, exact.side, exact.turn, false, 4, 0, false)
            && !exact.matchesDecoded(exact.base, exact.metadata, 0, exact.side, exact.turn, false, 0, 1.0, false)
            && !exact.matchesDecoded(exact.base, exact.metadata, 0, exact.side, exact.turn, false, 0, 0, true),
            "never validate unreviewed live tile state");

        var parsed = HologramProjectedVariantPricing.inspect(roof, true).tile;
        parsed.setInteger("side", 1);
        reject(() -> HologramProjectedRoofTileProof.declare(withTile(roof, parsed)));
        parsed.setByte("side", (byte) 6);
        reject(() -> HologramProjectedRoofTileProof.declare(withTile(roof, parsed)));
        parsed.setByte("side", (byte) 4); // valid native byte, but side 4/turn 3 is not an archived Lave tuple
        reject(() -> HologramProjectedRoofTileProof.declare(withTile(roof, parsed)));
        parsed.setByte("side", (byte) 1);
        parsed.setByte("turn", (byte) 4);
        reject(() -> HologramProjectedRoofTileProof.declare(withTile(roof, parsed)));
        parsed.setByte("turn", (byte) 3);
        parsed.setByte("offsetX", (byte) 1);
        reject(() -> HologramProjectedRoofTileProof.declare(withTile(roof, parsed)));
        parsed.removeTag("offsetX");
        parsed.setInteger("x", 1);
        reject(() -> HologramProjectedRoofTileProof.declare(withTile(roof, parsed)));
        parsed.removeTag("x");
        parsed.setInteger("Shape", 18);
        reject(() -> HologramProjectedRoofTileProof.declare(withTile(roof, parsed)));
        reject(() -> HologramProjectedRoofTileProof.declare(row(roof, roof.tileNbt, inventory.get(1).itemNbt)));
        reject(() -> HologramProjectedRoofTileProof.declare(inventory.get(10)));
        reject(() -> HologramProjectedRoofTileProof.declare(inventory.get(11)));
        reject(() -> HologramProjectedRoofTileProof.declare(inventory.get(12)));
        reject(() -> HologramProjectedRoofTileProof.declare(inventory.get(18)));
        reject(() -> HologramProjectedRoofTileProof.validateRegistered(null, exact));
        SimpleAssert.that(decoded(exact, "Ztones:tile.laveBlock", 12, 0, 1, 3),
            "separately decoded mutable tags never leak into the immutable cross-thread declaration");
    }

    /**
     * Optional only after the real mod registrations and Postea hooks have initialized on an isolated test server.
     * Use an existing authenticated test actor and owner thread; do not register or substitute providers.
     */
    public static void runWithRegisteredProviders(EntityPlayerMP isolatedActor) throws Exception {
        var endpoint = HologramWorkspace.captureActionEndpoint(isolatedActor.playerNetServerHandler);
        for (Declared declaration : declarations(rows())) {
            var proof = HologramProjectedRoofTileProof.validateRegistered(endpoint, declaration);
            SimpleAssert.that(proof.declared == declaration && proof.posteaActive,
                "actual registered native detached Roof Tile reads the exact archived tuple with Postea active");
        }
        Declared expected = HologramProjectedRoofTileProof.declare(rows().get(7));
        var worker = new FutureTask<Boolean>(() -> {
            try {
                HologramProjectedRoofTileProof.validateRegistered(endpoint, expected);
                return false;
            } catch (IOException refused) {
                return true;
            }
        });
        new Thread(worker, "GTMB Roof Tile off-owner refusal fixture").start();
        SimpleAssert.that(worker.get(5, TimeUnit.SECONDS), "the genuine provider is fenced off the server owner thread");
    }

    private static List<Declared> declarations(List<Row> rows) throws IOException {
        var declarations = new ArrayList<Declared>();
        for (Row row : rows) {
            if (!"ArchitectureCraft:shape".equals(row.block)
                || HologramProjectedVariantPricing.inspect(row, true).tile.getInteger("Shape") != 0) continue;
            declarations.add(HologramProjectedRoofTileProof.declare(row));
        }
        return declarations;
    }

    private static boolean decoded(Declared proof, String base, int meta, int shape, int side, int turn) {
        return proof.matchesDecoded(base, meta, shape, side, turn, false, 0, 0, false);
    }

    private static Row row(Row previous, String tile, String item) {
        return new Row(previous.block, previous.item, previous.metadata, previous.damage, previous.facing,
            previous.controller, tile, item, previous.count);
    }

    private static Row withTile(Row previous, NBTTagCompound tile) throws IOException {
        return row(previous, Base64.getEncoder().encodeToString(HologramSnapshotCodec.compress(tile)), previous.itemNbt);
    }

    private static List<Row> rows() throws IOException {
        var rows = new ArrayList<Row>();
        var resource = java.nio.file.Files.newInputStream(java.nio.file.Paths.get(
            System.getProperty("gtmb.reviewedMetadata.fixture")));
        if (resource == null) throw new IOException("Missing exact source metadata fixture");
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\t", -1);
                rows.add(new Row(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), -1, false,
                    "-".equals(f[5]) ? "" : f[5], "-".equals(f[6]) ? "" : f[6], Integer.parseInt(f[4])));
            }
        }
        return rows;
    }

    private interface Checked {
        void run() throws IOException;
    }

    private static void reject(Checked attempt) throws IOException {
        try {
            attempt.run();
            throw new AssertionError("unsupported tile declaration or provider context was accepted");
        } catch (IOException expected) {
            // Closed wire/admission semantics, with no fallback to a native installer.
        }
    }
}
