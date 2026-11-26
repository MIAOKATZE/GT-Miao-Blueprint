// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.world.WorldServer;

import com.github.bsideup.jabel.Desugar;

/**
 * Explicit retirement boundary for historical turbine runners. Historical artifacts retain their original identity;
 * this scenario cannot execute against the replacement native target engine and never returns a PASS receipt.
 */
public final class HologramTurbineStockWorldProbe {

    public static final String FLAG = "gtmb.testlab.ownedTurbineStockWorld";
    public static final boolean BASIC = Boolean.getBoolean(FLAG + "BasicPreset");

    @Desugar
    public record Coordinate(int x, int y, int z) {}

    @Desugar
    public record BasicSite(int phase, UUID actor, UUID tool, int x, int y, int z, int lowChunkX, int lowChunkZ,
        int highChunkX, int highChunkZ, List<Coordinate> verifiedMissing) {}

    @Desugar
    public record Outbound(HologramStockNativeWire.Confirmation confirmation, boolean afterProbe) {}

    public HologramTurbineStockWorldProbe(UUID actorId) {
        throw retired();
    }

    public Outbound outbound() {
        throw retired();
    }

    public void beginSent(Outbound current) {
        throw retired();
    }

    public void nativeReply(HologramStockNativeWire.Reply reply) {
        throw retired();
    }

    public BasicSite basicSite() {
        throw retired();
    }

    public void basicPrepared(BasicSite site, HologramControllerTarget.Projection projection,
        HologramNativePresetIndex.Prepared plan) {
        throw retired();
    }

    public boolean done() {
        throw retired();
    }

    public Map<String, Object> receipt() {
        throw retired();
    }

    public void step(WorldServer owned) {
        throw retired();
    }

    private static UnsupportedOperationException retired() {
        return new UnsupportedOperationException(
            "UNSUPPORTED/RETIRED: declared-native turbine scenario was removed; use an explicitly implemented native target action scenario");
    }
}
