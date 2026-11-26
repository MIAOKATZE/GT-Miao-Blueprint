// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Exact-name and malformed-payload checks; initialized native roundtrips have a separate fixture. */
public final class HologramEnderIoCapabilitiesRegression {

    private static int checks;

    private HologramEnderIoCapabilitiesRegression() {}

    public static void main(String[] arguments) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramEnderIoCapabilitiesRegression.class, arguments)) return;
        run();
    }

    public static void run() throws IOException {
        checks = 0;
        String prefix = "crazypants.enderio.conduit.liquid.";
        int metadata = 7;
        for (String name : new String[] { "PrismaticNaquadah", "Fluxed", "Transcendent", "Cosmic" }) {
            String exact = prefix + name + "EnderLiquidConduit";
            require(HologramEnderIoCapabilities.modernMetadata(exact) == metadata++, "exact modern metadata identity");
            require(
                HologramEnderIoCapabilities.modernMetadata(exact + "Subclass") == -1,
                "unreviewed subtype cannot acquire modern provider admission");
        }
        require(
            HologramEnderIoCapabilities.modernMetadata(prefix + "StellarEnderLiquidConduit") == -1,
            "legacy classes retain their existing provider authority");
        require(HologramEnderIoCapabilities.modernMetadata(null) == -1, "missing type is not a modern identity");
        require(
            !HologramEnderIoCapabilities.modernLiquidAdapter()
                .supports(null)
                && !HologramEnderIoCapabilities.modernLiquidAdapter()
                    .supports(new Object()),
            "modern provider does not broaden unknown object types");
        NBTTagCompound badRoot = new NBTTagCompound();
        badRoot.setString("conduits", "not a list");
        denied(badRoot, "wrong list type");
        NBTTagCompound badRows = new NBTTagCompound();
        NBTTagList rows = new NBTTagList();
        rows.appendTag(new NBTTagString("not a compound"));
        badRows.setTag("conduits", rows);
        denied(badRows, "wrong row type");
        for (String name : new String[] { "PrismaticNaquadah", "Fluxed", "Transcendent", "Cosmic" }) {
            NBTTagCompound bundle = new NBTTagCompound(), row = new NBTTagCompound();
            row.setString("conduitType", prefix + name + "EnderLiquidConduit");
            NBTTagList list = new NBTTagList();
            list.appendTag(row);
            bundle.setTag("conduits", list);
            denied(bundle, "modern row needs its complete body");
        }
        NBTTagCompound empty = new NBTTagCompound();
        empty.setTag("conduits", new NBTTagList());
        HologramEnderIoCapabilities.requireRepresentable(empty);
        require(empty.hasKey("conduits", 9), "empty native bundle remains unchanged");
        System.out
            .println("[GTMB-EIO-CAPABILITY] PASS " + checks + " exact-name/pure NBT assertions; native/FML NOT_RUN");
    }

    private static void denied(NBTTagCompound data, String reason) throws IOException {
        NBTTagCompound original = (NBTTagCompound) data.copy();
        boolean refused = false;
        try {
            HologramEnderIoCapabilities.requireRepresentable(data);
        } catch (IOException expected) {
            refused = true;
        }
        require(refused, reason);
        require(data.equals(original), "failed preflight preserves source and rows: " + reason);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
