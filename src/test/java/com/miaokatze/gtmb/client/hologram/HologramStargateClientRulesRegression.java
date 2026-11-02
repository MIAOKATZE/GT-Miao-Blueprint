package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;

import com.cleanroommc.modularui.api.widget.IWidget;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.widgets.TextWidget;
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
        cachedPolicyAndDemolitionWarning();
    }

    private static void connectionAuthority() {
        HologramStargateClientRules rules = new HologramStargateClientRules();
        Object first = new Object(), second = new Object();
        SimpleAssert.that(!rules.requestNeeded(null, true), "disconnected client sends no capability request");
        SimpleAssert.that(!rules.requestNeeded(first, false), "world startup does not consume the recovery query");
        SimpleAssert.that(rules.requestNeeded(first, true), "a dropped login State is recovered once the world exists");
        SimpleAssert.that(!rules.requestNeeded(first, true), "one ready connection sends only one recovery query");
        rules.receive(first, flag(true));
        SimpleAssert.that(rules.enabled(first), "server gameplay policy is authoritative");
        rules.receive(first, new NBTTagCompound());
        SimpleAssert.that(rules.enabled(first), "unrelated legacy metadata cannot revoke a known policy");
        SimpleAssert.that(!rules.enabled(null), "disconnect clears the previous server policy");
        SimpleAssert.that(!rules.enabled(second), "another server starts with compatible ordinary defaults");
        SimpleAssert.that(rules.requestNeeded(second, true), "a new connection receives its own query");
        rules.receive(second, flag(false));
        SimpleAssert.that(!rules.enabled(second), "an ordinary server explicitly disables Stargate rules");

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
        SimpleAssert.that(rules.enabled(connection), "ordinary full State carries the policy");
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
            SimpleAssert.that(rules.enabled(connection), "decoded fragmented State carries the same policy");
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void selectorAvailability() {
        HologramStargateClientRules rules = new HologramStargateClientRules();
        Object connection = new Object();
        for (boolean enabled : new boolean[] { true, false }) {
            rules.receive(connection, flag(enabled));
            for (HologramCutInput.Mode initial : HologramCutInput.Mode.values()) {
                HologramCutInput.Switcher choice = new HologramCutInput.Switcher(initial);
                SimpleAssert.eq(initial, choice.mode(), "server policy preserves the remembered mode");
                for (int i = 1; i <= HologramCutInput.Mode.values().length; i++) {
                    choice.cycle();
                    SimpleAssert.eq(
                        HologramCutInput.Mode.values()[(initial.ordinal() + i) % HologramCutInput.Mode.values().length],
                        choice.mode(),
                        "all three modes remain selectable with Stargate rules=" + rules.enabled());
                }
                SimpleAssert.that(!choice.release(true) && choice.release(false), "Shift release commits once");
                SimpleAssert.eq(initial, choice.mode(), "release preserves the chosen world operation");
                SimpleAssert.that(!choice.release(false), "release cannot commit twice");
            }
        }
        HologramCutInput.Switcher pending = new HologramCutInput.Switcher(HologramCutInput.Mode.CUT);
        rules.receive(connection, flag(true));
        SimpleAssert.that(pending.release(false), "late server policy still allows the pending release");
        SimpleAssert.eq(HologramCutInput.Mode.CUT, pending.mode(), "late authority retains a valid cut choice");
    }

    private static void constructionCompatibility() {
        NBTTagCompound state = new NBTTagCompound();
        state.setLong("planRevision", 38);
        SimpleAssert
            .that(HologramPlacementConfirmation.canBuild(state, 38), "older snapshots keep their build semantics");
        state.setBoolean("stargateRules", true);
        SimpleAssert.that(
            HologramPlacementConfirmation.canBuild(state, 38),
            "Stargate policy allows normal material-paid construction");
        SimpleAssert.that(HologramPlacementConfirmation.networksReady(state), "network review remains available");
        SimpleAssert
            .that(HologramPlacementConfirmation.voucherPaymentReady(state), "material review remains available");
        state.setInteger("missing", 1);
        SimpleAssert
            .that(!HologramPlacementConfirmation.canBuild(state, 38), "missing materials still block construction");
        state.setInteger("missing", 0);
        SimpleAssert
            .that(!HologramPlacementConfirmation.canBuild(state, 37), "outdated review still blocks construction");
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

    private static void cachedPolicyAndDemolitionWarning() {
        try {
            java.lang.reflect.Field field = HologramClient.class.getDeclaredField("serverRules");
            field.setAccessible(true);
            HologramStargateClientRules cache = (HologramStargateClientRules) field.get(null);
            Object connection = new Object();
            try {
                for (boolean enabled : new boolean[] { true, false }) {
                    cache.receive(connection, flag(enabled));
                    SimpleAssert.eq(
                        enabled,
                        HologramClient.stargateRulesEnabled(),
                        "context-free policy queries read the cache without a Minecraft world");
                    ModularPanel panel = new ModularPanel("stargate_demolition_warning");
                    HologramCutDialogContent.demolitionConfirmation(
                        panel,
                        () -> "demolition",
                        () -> "bounds",
                        new IntValue(0),
                        (accepted, skip) -> {});
                    TextWidget<?> protection = null;
                    for (IWidget widget : panel.getChildren())
                        if ("demolition_protection".equals(widget.getName())) protection = (TextWidget<?>) widget;
                    SimpleAssert.that(protection != null, "existing demolition protection area remains present");
                    String warning = HologramClient
                        .text("stargateRules.oreDrops", "Stargate rules: ores are destroyed without drops or XP");
                    SimpleAssert.eq(
                        enabled,
                        protection.getKey()
                            .getFormatted()
                            .contains(warning),
                        "only Stargate demolition warns that ores are destroyed without drops or XP");
                }
            } finally {
                cache.observeConnection(null);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cached Stargate policy and demolition warning", failure);
        }
    }
}
