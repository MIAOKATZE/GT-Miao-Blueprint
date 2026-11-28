// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;

import net.minecraft.client.Minecraft;

import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramTurbineStockWorldProbe;

/** TestLab observer only. Production Action encode/decode, endpoint, scheduler and reply paths remain untouched. */
final class HologramTurbineWireWitness {

    private final Object connection;
    private final HologramNetwork.StockNativeReplies original, observer;
    private HologramTurbineStockWorldProbe.Outbound sent;
    private boolean closed;

    HologramTurbineWireWitness(Minecraft mc, HologramTurbineStockWorldProbe fixture) throws Exception {
        connection = mc.getNetHandler();
        if (connection == null || !mc.isIntegratedServerRunning()) throw new IllegalStateException("No owned client");
        original = receiver();
        observer = (reply, inbound) -> {
            if (inbound == connection) fixture.nativeReply(reply);
            if (original != null) original.accept(reply, inbound);
        };
        HologramNetwork.setStockNativeReplies(observer);
    }

    void step(Minecraft mc, HologramTurbineStockWorldProbe fixture) {
        if (closed || mc.getNetHandler() != connection) throw new IllegalStateException("Turbine connection changed");
        var next = fixture.outbound();
        if (next == null || sent == next) return;
        if (next.afterProbe()) fixture.beginSent(next);
        com.miaokatze.gtmb.hologram.HologramPlacementTiming.clientPhase(
            "structure",
            next.confirmation()
                .key()
                .request(),
            next.afterProbe() ? "testlab_begin" : "testlab_probe");
        boolean ok = next.afterProbe() ? HologramNetwork.sendStockNativeConfirmation(next.confirmation())
            : HologramNetwork.sendStockNativeProbe(next.confirmation());
        if (!ok) throw new IllegalStateException("Production native request send refused");
        sent = next;
    }

    void close() throws Exception {
        if (closed) return;
        closed = true;
        if (receiver() == observer) HologramNetwork.setStockNativeReplies(original);
    }

    private static HologramNetwork.StockNativeReplies receiver() throws Exception {
        Field field = HologramNetwork.class.getDeclaredField("stockNativeReplies");
        field.setAccessible(true);
        return (HologramNetwork.StockNativeReplies) field.get(null);
    }
}
