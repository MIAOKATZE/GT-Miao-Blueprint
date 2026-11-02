package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramSnapshotAssembly;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Server authority survives world startup and both snapshot paths, without leaking across connections. */
public final class HologramStargateClientRulesRegression {

    private HologramStargateClientRulesRegression() {}

    public static void run() {
        connectionAuthority();
        snapshotTransport();
        selectorAvailability();
        constructionCompatibility();
    }

    private static void connectionAuthority() {
        HologramStargateClientRules rules = new HologramStargateClientRules();
        Object first = new Object(), second = new Object();
        SimpleAssert.that(!rules.requestNeeded(null, true), "disconnected client sends no capability request");
        SimpleAssert.that(!rules.requestNeeded(first, false), "world startup does not consume the recovery query");
        SimpleAssert.that(rules.requestNeeded(first, true), "a dropped login State is recovered once the world exists");
        SimpleAssert.that(!rules.requestNeeded(first, true), "one ready connection sends only one recovery query");
        rules.receive(first, flag(true));
        SimpleAssert.that(rules.enabled(first), "server restriction is authoritative");
        rules.receive(first, new NBTTagCompound());
        SimpleAssert.that(rules.enabled(first), "unrelated legacy metadata cannot revoke a known restriction");
        SimpleAssert.that(!rules.enabled(null), "disconnect clears the previous server restriction");
        SimpleAssert.that(!rules.enabled(second), "another server starts with compatible unrestricted defaults");
        SimpleAssert.that(rules.requestNeeded(second, true), "a new connection receives its own query");
        rules.receive(second, flag(false));
        SimpleAssert.that(!rules.enabled(second), "an ordinary server explicitly disables the restriction");

        HologramStargateClientRules early = new HologramStargateClientRules();
        early.receive(first, flag(true));
        SimpleAssert.that(early.enabled(first), "login authority is accepted before a world is available");
        SimpleAssert.that(!early.requestNeeded(first, true), "received login authority needs no recovery query");
    }

    private static void snapshotTransport() {
        Object connection = new Object();
        HologramStargateClientRules rules = new HologramStargateClientRules();
        NBTTagCompound state = flag(true);
        state.setString("session", "snapshot-authority");
        rules.receive(connection, state);
        SimpleAssert.that(rules.enabled(connection), "ordinary full State carries the restriction");
        rules.receive(connection, flag(false));
        byte[] payload = new byte[HologramSnapshotAssembly.CHUNK_BYTES + 64];
        new Random(38).nextBytes(payload);
        state.setByteArray("payload", payload);
        try {
            byte[] encoded = HologramSnapshotCodec.compress(state);
            SimpleAssert
                .that(encoded.length > HologramSnapshotAssembly.CHUNK_BYTES, "fixture requires multiple fragments");
            HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();
            byte[] complete = null;
            for (int offset = 0, index = 0; offset
                < encoded.length; offset += HologramSnapshotAssembly.CHUNK_BYTES, index++) {
                complete = assembly.accept(
                    38,
                    encoded.length,
                    index,
                    Arrays.copyOfRange(
                        encoded,
                        offset,
                        Math.min(encoded.length, offset + HologramSnapshotAssembly.CHUNK_BYTES)),
                    index);
                if (offset + HologramSnapshotAssembly.CHUNK_BYTES < encoded.length) SimpleAssert
                    .that(complete == null && !rules.enabled(connection), "partial bytes never publish authority");
            }
            SimpleAssert.that(complete != null, "all fragments reconstruct the complete snapshot");
            rules.receive(connection, HologramSnapshotCodec.decode(complete));
            SimpleAssert.that(rules.enabled(connection), "decoded fragmented State carries the same restriction");
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void selectorAvailability() {
        for (HologramCutInput.Mode initial : HologramCutInput.Mode.values()) {
            HologramCutInput.Switcher restricted = new HologramCutInput.Switcher(initial, () -> false);
            SimpleAssert
                .eq(HologramCutInput.Mode.BLUEPRINT, restricted.mode(), "restricted selector normalizes " + initial);
            for (int i = 0; i < 4; i++) {
                restricted.cycle();
                SimpleAssert
                    .eq(HologramCutInput.Mode.BLUEPRINT, restricted.mode(), "right-click skips both restricted modes");
            }
            SimpleAssert
                .that(!restricted.release(true) && restricted.release(false), "Shift release still commits once");
            SimpleAssert
                .that(!restricted.cut() && !restricted.release(false), "restricted selector cannot commit a cut");
        }
        HologramStargateClientRules rules = new HologramStargateClientRules();
        Object connection = new Object();
        HologramCutInput.Switcher pending = new HologramCutInput.Switcher(
            HologramCutInput.Mode.CUT,
            () -> !rules.enabled(connection));
        rules.receive(connection, flag(true));
        SimpleAssert.that(pending.release(false), "late server authority still allows the pending release");
        SimpleAssert.eq(
            HologramCutInput.Mode.BLUEPRINT,
            pending.mode(),
            "late authority prevents committing the old cut choice");

        HologramCutInput.Switcher ordinary = new HologramCutInput.Switcher();
        ordinary.cycle();
        SimpleAssert.eq(HologramCutInput.Mode.CUT, ordinary.mode(), "ordinary servers retain cut selection");
        ordinary.cycle();
        SimpleAssert
            .eq(HologramCutInput.Mode.DEMOLITION, ordinary.mode(), "ordinary servers retain demolition selection");
        ordinary.cycle();
        SimpleAssert.eq(HologramCutInput.Mode.BLUEPRINT, ordinary.mode(), "ordinary three-mode cycle remains intact");
    }

    private static void constructionCompatibility() {
        NBTTagCompound state = new NBTTagCompound();
        state.setLong("planRevision", 38);
        SimpleAssert
            .that(HologramPlacementConfirmation.canBuild(state, 38), "older snapshots keep their build semantics");
        state.setBoolean("stargateRules", true);
        SimpleAssert.that(
            !HologramPlacementConfirmation.canBuild(state, 38),
            "server rules deny the actual build confirmation");
        SimpleAssert.that(HologramPlacementConfirmation.networksReady(state), "network review remains available");
        SimpleAssert
            .that(HologramPlacementConfirmation.voucherPaymentReady(state), "material review remains available");
        state.setBoolean("stargateRules", false);
        SimpleAssert.that(
            HologramPlacementConfirmation.canBuild(state, 38),
            "explicit false keeps ordinary servers compatible");
    }

    private static NBTTagCompound flag(boolean enabled) {
        NBTTagCompound state = new NBTTagCompound();
        state.setBoolean("stargateRules", enabled);
        return state;
    }
}
