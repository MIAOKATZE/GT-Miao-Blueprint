package com.miaokatze.gtmb.client.hologram;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Preferences cannot turn a server capability into authority or claim that discarded data was restored. */
public final class HologramDeidentificationMemoryRegression {

    private HologramDeidentificationMemoryRegression() {}

    public static void run() {
        placementRestoreDoesNotReplayAcrossSnapshots();
        Path directory = null;
        try {
            directory = Files.createTempDirectory("gtmb-deidentification-");
            Path file = directory.resolve("choices.properties");
            HologramDeidentificationMemory memory = new HologramDeidentificationMemory(file.toFile());
            NBTTagCompound snapshot = new NBTTagCompound();
            snapshot.setBoolean("virtual", true);
            snapshot.setString("virtualPresetId", "blueprint-a");
            snapshot.setInteger("blueprintDeidentificationLevel", 0);
            snapshot.setInteger("placementDeidentificationLevel", 0);
            snapshot.setInteger("deidentificationRecommendedLevel", 1);
            String source = HologramDeidentificationMemory.source(snapshot, "server-a");
            SimpleAssert.that(memory.choice(source) == null, "unchosen recommendation is distinct from level zero");
            SimpleAssert.eq(
                1,
                HologramDeidentificationMemory.level(snapshot, false, null),
                "first save proposes recommended level one while the unprocessed source remains actual zero");
            SimpleAssert.eq(
                1,
                HologramDeidentificationMemory.level(snapshot, true, null),
                "first placement also proposes recommendation instead of treating raw zero as a user choice");
            memory.remember(source, 0);
            memory.remember("other", 7);
            memory = new HologramDeidentificationMemory(file.toFile());
            SimpleAssert.eq(Integer.valueOf(0), memory.choice(source), "explicit zero survives client restart");
            SimpleAssert.eq(
                0,
                HologramDeidentificationMemory.level(snapshot, false, memory.choice(source)),
                "a remembered zero is never replaced by the default recommendation");
            SimpleAssert.eq(
                0,
                HologramDeidentificationMemory.level(snapshot, true, memory.choice(source)),
                "next placement and Alt preview restore the user's explicit zero");
            snapshot.setBoolean("deidentificationChosen", true);
            SimpleAssert.eq(
                1,
                HologramDeidentificationMemory.level(snapshot, true, null),
                "the blueprint author's raw zero choice cannot suppress another placing player's recommendation");
            snapshot.setBoolean("placementDeidentificationChosen", true);
            SimpleAssert.eq(
                0,
                HologramDeidentificationMemory.level(snapshot, true, null),
                "the placing player's own explicit session choice stays zero");
            String actorA = HologramDeidentificationMemory.source(snapshot, "server-a", "actor-a");
            String actorB = HologramDeidentificationMemory.source(snapshot, "server-a", "actor-b");
            memory.remember(actorA, 0);
            SimpleAssert
                .that(memory.choice(actorB) == null, "another account using this client has separate preferences");
            snapshot.setInteger("deidentificationMinimumLevel", 3);
            SimpleAssert.eq(
                3,
                HologramDeidentificationMemory.level(snapshot, true, memory.choice(source)),
                "irreversibly processed blueprint cannot pretend it restored raw level zero");
            SimpleAssert.that(
                memory.choice(HologramDeidentificationMemory.source(snapshot, "server-b")) == null,
                "different servers do not inherit a public blueprint preference by display name");
            snapshot.setString("libraryLocalId", "local-uuid");
            SimpleAssert.eq(
                HologramDeidentificationMemory.source(snapshot, "server-a"),
                HologramDeidentificationMemory.source(snapshot, "server-b"),
                "the same carried client blueprint keeps its preference across servers");
            memory.remember(source, 11);
            SimpleAssert.eq(Integer.valueOf(0), memory.choice(source), "out-of-range values do not overwrite a choice");
            SimpleAssert.that(
                HologramUiText.reason("unsupported network binding")
                    .contains("不支持"),
                "English diagnostics are accompanied by an actionable Chinese explanation");
            SimpleAssert.eq(
                "该量子终端尚未绑定网络，请先在世界中完成绑定",
                HologramUiText.reason("Quantum terminal is not bound"),
                "unbound terminal gets a concrete Chinese action without inline English");
            SimpleAssert.that(
                HologramUiText.reason("No permission to bind the selected quantum network")
                    .contains("没有权限"),
                "permission failure is distinct from a missing network");
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        } finally {
            if (directory != null) try {
                Files.deleteIfExists(directory.resolve("choices.properties"));
                Files.deleteIfExists(directory);
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static void placementRestoreDoesNotReplayAcrossSnapshots() {
        HologramPlacementDeidentificationSync sync = new HologramPlacementDeidentificationSync();
        NBTTagCompound snapshot = new NBTTagCompound();
        snapshot.setString("session", "placement-a");
        snapshot.setBoolean("virtualPlacement", true);
        snapshot.setLong("planRevision", 10);
        snapshot.setLong("placementReviewNonce", 4);
        SimpleAssert.that(sync.shouldRestore(snapshot, 1), "remembered choice requests one authoritative application");
        for (int i = 0; i < 100; i++) {
            snapshot.setLong("uiSequence", i);
            SimpleAssert.that(
                !sync.shouldRestore(snapshot, 1),
                "background ACK snapshots cannot replay the same revision/level");
        }
        // A manual click owns its existing UI ticket; an older snapshot must not add an automatic command.
        snapshot.setLong("planRevision", 11);
        sync.requested(snapshot, 3);
        SimpleAssert
            .that(!sync.shouldRestore(snapshot, 3), "manual application fences automatic restore before its ACK");
        snapshot.setLong("planRevision", 12);
        snapshot.setLong("placementReviewNonce", 5);
        snapshot.setBoolean("placementDeidentificationChosen", true);
        snapshot.setInteger("placementDeidentificationLevel", 3);
        for (int job : new int[] { 0, 1, 2, 3, 5 }) {
            snapshot.setInteger("job", job);
            SimpleAssert.that(
                !sync.shouldRestore(snapshot, 3),
                "applied policy and job snapshots never request another round trip");
        }
        snapshot.setBoolean("placementDeidentificationChosen", false);
        snapshot.setLong("planRevision", 13);
        for (int job : new int[] { 1, 2, 3, 5 }) {
            snapshot.setInteger("job", job);
            SimpleAssert
                .that(!sync.shouldRestore(snapshot, 4), "restoring preferences must not reset an active/completed job");
        }
        snapshot.setInteger("job", 0);
        SimpleAssert.that(
            sync.shouldRestore(snapshot, 4),
            "a new editable revision can explicitly restore its new choice once");
        SimpleAssert.that(!sync.shouldRestore(snapshot, 4), "new revision is also fenced after its first request");
        snapshot.setString("session", "placement-b");
        SimpleAssert.that(
            sync.shouldRestore(snapshot, 4),
            "another placement session has independent preference synchronization");
        sync.clear();
        SimpleAssert.that(sync.shouldRestore(snapshot, 4), "disconnect/reset retires the old network lifetime");
    }
}
