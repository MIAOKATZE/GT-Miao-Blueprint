// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Pure NBT target-geometry checks; no FML mod registration, native readers or live world. */
public final class HologramAeTerminalCapabilitiesRegression {

    private static final String EX = "appeng.parts.reporting.PartPatternTerminalEx";
    private static int checks;

    private HologramAeTerminalCapabilitiesRegression() {}

    public static void main(String[] arguments) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAeTerminalCapabilitiesRegression.class, arguments)) return;
        run();
    }

    public static void run() throws IOException {
        checks = 0;
        for (int allocated : new int[] { 32, 64 }) {
            HologramAeTerminalCapabilities target = HologramAeTerminalCapabilities.measured(allocated, allocated, EX);
            require(
                target.inputs == allocated && target.outputs == allocated && target.pages == allocated / 16,
                "allocated capacity/pages follow the measured target");
            for (boolean inverted : new boolean[] { false, true }) {
                int inputs = inverted ? allocated / 4 : allocated;
                int outputs = inverted ? allocated : allocated / 4;
                require(
                    target.effectiveInputs(inverted) == inputs && target.effectiveOutputs(inverted) == outputs,
                    "normal/inverted effective capacity");
                NBTTagCompound valid = payload(inverted, target.pages - 1, inputs - 1, outputs - 1);
                NBTTagCompound original = (NBTTagCompound) valid.copy();
                target.requireRepresentable(valid);
                require(valid.equals(original), "legal high slots and final page remain byte-value identical");
                denied(target, payload(inverted, target.pages, inputs - 1, outputs - 1), "one page beyond target");
                denied(target, payload(inverted, -1, inputs - 1, outputs - 1), "negative page");
                denied(target, payload(inverted, 0, inputs, outputs - 1), "input first unrepresentable slot");
                denied(target, payload(inverted, 0, inputs - 1, outputs), "output first unrepresentable slot");
            }
        }
        HologramAeTerminalCapabilities old = HologramAeTerminalCapabilities.measured(32, 32, EX);
        HologramAeTerminalCapabilities modern = HologramAeTerminalCapabilities.measured(64, 64, EX);
        NBTTagCompound highInput = payload(false, 3, 63, 15);
        modern.requireRepresentable(highInput);
        denied(old, highInput, "modern normal payload cannot enter old target");
        NBTTagCompound highOutput = payload(true, 3, 15, 63);
        modern.requireRepresentable(highOutput);
        denied(old, highOutput, "modern inverted payload cannot enter old target");
        NBTTagCompound wrongBoolean = payload(false, 0, 0, 0);
        wrongBoolean.setInteger("inverted", 1);
        denied(modern, wrongBoolean, "inverted has exact native byte type");
        NBTTagCompound wrongPage = payload(false, 0, 0, 0);
        wrongPage.setLong("activePage", 1);
        denied(modern, wrongPage, "activePage has exact native int type");
        for (int[] shape : new int[][] { { 16, 16 }, { 64, 32 }, { 128, 128 } }) {
            boolean refused = false;
            try {
                HologramAeTerminalCapabilities.measured(shape[0], shape[1], EX);
            } catch (IOException expected) {
                refused = true;
            }
            require(refused, "unknown geometry remains outside audited schema");
        }
        HologramAeTerminalCapabilities standard = HologramAeTerminalCapabilities
            .measured(9, 3, "appeng.parts.reporting.PartPatternTerminal");
        require(
            standard.inputs == 9 && standard.outputs == 3 && !standard.extended,
            "standard terminal retains its own geometry");
        System.out.println("[GTMB-AE-CAPABILITY] PASS " + checks + " pure NBT assertions; native/FML NOT_RUN");
    }

    private static NBTTagCompound payload(boolean inverted, int page, int input, int output) {
        NBTTagCompound data = new NBTTagCompound();
        data.setBoolean("inverted", inverted);
        data.setInteger("activePage", page);
        for (String root : new String[] { "craftingGrid", "outputList" }) {
            NBTTagCompound slots = new NBTTagCompound(), marker = new NBTTagCompound();
            marker.setString("fixture", "preserved ghost configuration");
            slots.setTag("#" + (root.equals("craftingGrid") ? input : output), marker);
            data.setTag(root, slots);
        }
        return data;
    }

    private static void denied(HologramAeTerminalCapabilities target, NBTTagCompound data, String reason)
        throws IOException {
        NBTTagCompound original = (NBTTagCompound) data.copy();
        boolean refused = false;
        try {
            target.requireRepresentable(data);
        } catch (IOException expected) {
            refused = true;
        }
        require(refused, reason);
        require(data.equals(original), "rejected source remains unchanged: " + reason);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
