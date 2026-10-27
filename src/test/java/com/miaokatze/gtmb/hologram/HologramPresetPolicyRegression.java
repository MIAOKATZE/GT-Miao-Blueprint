package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Sliced policy discovery shares the existing complete identity-floor predicate and receipt precedence. */
public final class HologramPresetPolicyRegression {

    private HologramPresetPolicyRegression() {}

    public static void run() {
        try {
            parity();
        } catch (Exception failure) {
            throw new AssertionError("saved preset policy continuation", failure);
        }
    }

    private static void parity() throws Exception {
        for (String mode : new String[] { "plain", "boundary", "minimum", "receipt", "unknown" }) {
            HologramPresets.Preset source = fixture(mode, 129);
            net.minecraft.nbt.NBTTagCompound payload = new net.minecraft.nbt.NBTTagCompound();
            if ("boundary".equals(mode)) payload.setString(HologramNetworkBindings.GROUP, "native-network-identity");
            else payload.setString("unrecognizedMachineField", "must remain intact");
            source.cells.get(128).tileNbt = HologramBlueprintNbt.encodeCaptured(payload);
            if ("minimum".equals(mode)) source.deidentificationLevel = 4;
            if ("receipt".equals(mode)) {
                net.minecraft.nbt.NBTTagCompound receipt = new net.minecraft.nbt.NBTTagCompound();
                receipt.setTag(
                    "policy",
                    HologramBlueprintProcessing.defaults("share", 0)
                        .write());
                source.processingNbt = HologramBlueprintNbt.encodeCaptured(receipt);
            }
            String original = source.cells.get(128).tileNbt;
            HologramBlueprintProcessing.Policy expected;
            try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
                expected = HologramBlueprintProcessing.policy(source, facts);
            }
            try (HologramBlueprintProcessing.PolicyTask task = new HologramBlueprintProcessing.PolicyTask(source)) {
                SimpleAssert.that(
                    !task.step(0, Long.MAX_VALUE) && !task.step(10, 0),
                    "policy zero budget performs no obligation: " + mode);
                int slices = 0;
                while (true) {
                    long before = task.completed();
                    boolean done = task.step(3, Long.MAX_VALUE);
                    SimpleAssert
                        .that(task.completed() - before <= 3, "policy native identity checks obey operation cap");
                    if (done) break;
                    SimpleAssert.that(++slices < 1000, "policy discovery terminates");
                }
                SimpleAssert.eq(
                    expected.write(),
                    task.result()
                        .write(),
                    "sliced policy preserves receipt/minimum/native identity precedence: " + mode);
                SimpleAssert.eq(
                    original,
                    source.cells.get(128).tileNbt,
                    "identity discovery never edits unknown/native source payload");
                if ("boundary".equals(mode)) SimpleAssert
                    .eq(1, task.result().deidentificationLevel, "known native network boundary cannot be skipped");
                if ("plain".equals(mode))
                    SimpleAssert.that(slices > 30, "default policy no longer scans whole input before admission");
            }
        }
        HologramPresets.Preset malformed = fixture("malformed", 1);
        malformed.cells.get(0).tileNbt = "not valid base64 NBT";
        boolean syncRejected = false, stagedRejected = false;
        try (HologramAnalysisFacts facts = new HologramAnalysisFacts()) {
            HologramBlueprintProcessing.policy(malformed, facts);
        } catch (java.io.IOException expected) {
            syncRejected = true;
        }
        try (HologramBlueprintProcessing.PolicyTask task = new HologramBlueprintProcessing.PolicyTask(malformed)) {
            while (!task.step(1, Long.MAX_VALUE)) {}
        } catch (java.io.IOException expected) {
            stagedRejected = true;
        }
        SimpleAssert.that(
            syncRejected && stagedRejected,
            "malformed identity input is still rejected, not treated as ordinary/free");
    }

    private static HologramPresets.Preset fixture(String id, int cells) {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.id = id;
        source.schema = 2;
        for (int i = 0; i < cells; i++)
            source.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), i, 0, 0, -1, false));
        return source;
    }
}
