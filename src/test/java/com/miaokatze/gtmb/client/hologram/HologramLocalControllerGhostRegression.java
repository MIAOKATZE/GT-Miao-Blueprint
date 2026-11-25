// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

final class HologramLocalControllerGhostRegression {

    private HologramLocalControllerGhostRegression() {}

    static void run() {
        NBTTagCompound nbt = new NBTTagCompound();
        nbt.setBoolean("localStandardController", true);
        nbt.setBoolean("target", true);
        nbt.setString("session", "local-controller:workshop");
        nbt.setString("generation", "local-controller:revision");
        nbt.setInteger("siteMissing", 1);
        SimpleAssert.that(!HologramLocalControllerGhost.eligible(new HologramState(nbt)), "opening shell has no ghost");
        NBTTagList cells = new NBTTagList();
        NBTTagCompound missing = new NBTTagCompound();
        missing.setInteger("x", 391);
        missing.setInteger("y", 65);
        missing.setInteger("z", -107);
        missing.setString("id", "minecraft:air");
        missing.setString("wantId", "minecraft:stone");
        cells.appendTag(missing);
        nbt.setTag("cells", cells);
        HologramState target = new HologramState(nbt);
        SimpleAssert.that(HologramLocalControllerGhost.eligible(target), "local controller geometry is eligible");
        Object world = new Object(), link = new Object(), player = new Object(), tile = new Object(),
            stack = new Object();
        HologramLocalControllerGhost.Lease lease = new HologramLocalControllerGhost.Lease(
            target,
            world,
            link,
            player,
            tile,
            -1,
            3,
            "tool",
            stack,
            "",
            100);
        SimpleAssert.that(
            lease.target == target && lease.target.cells.get(0).x == 391,
            "world draws the exact captured target and preserves real-world coordinates");
        SimpleAssert.that(
            lease.current(world, link, player, tile, -1, 100) && lease.show(true, true, true, 3, "tool", new Object()),
            "first outside frame with the matching held tool");
        SimpleAssert.that(
            !lease.show(false, true, true, 3, "tool", stack) && !lease.show(true, false, true, 3, "tool", stack)
                && !lease.show(true, true, false, 3, "tool", stack)
                && !lease.show(true, true, true, 4, "tool", stack)
                && !lease.show(true, true, true, 3, "other", stack),
            "off toggle, editor, empty hand, changed tool or slot cannot draw");
        SimpleAssert
            .that(
                !lease.current(new Object(), link, player, tile, -1, 100)
                    && !lease.current(world, new Object(), player, tile, -1, 100)
                    && !lease.current(world, link, new Object(), tile, -1, 100)
                    && !lease.current(world, link, player, new Object(), -1, 100)
                    && !lease.current(world, link, player, tile, 0, 100)
                    && !lease
                        .current(world, link, player, tile, -1, 101 + HologramProjectionLifetime.SNAPSHOT_TTL_NANOS),
                "disconnects, controller replacement, world change and stale captures are not reused");
        alternateOwner(lease);
        HologramLocalControllerGhost.Lease unlinked = new HologramLocalControllerGhost.Lease(
            target,
            world,
            link,
            player,
            tile,
            -1,
            3,
            "",
            stack,
            "",
            100);
        SimpleAssert.that(
            unlinked.show(true, true, true, 3, "", stack) && !unlinked.show(true, true, true, 3, "", new Object()),
            "unlinked tools are confined to the exact captured stack");
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setBoolean("nativeBuildSatisfied", true);
        SimpleAssert.that(
            !HologramLocalControllerGhost.eligible(target.withMetadata(receipt)),
            "a completed native order retires the projection before the captured missing counts refresh");

        nbt.setInteger("siteMissing", 0);
        SimpleAssert.that(!HologramLocalControllerGhost.eligible(new HologramState(nbt)), "no ghost after completion");
        nbt.setInteger("siteUnknown", 1);
        SimpleAssert.that(
            HologramLocalControllerGhost.eligible(new HologramState(nbt)),
            "an unknown site does not masquerade as a completed structure");
        nbt.setInteger("siteUnknown", 0);
        nbt.setInteger("siteDifferent", 1);
        SimpleAssert
            .that(HologramLocalControllerGhost.eligible(new HologramState(nbt)), "show outstanding replacements");
        nbt.setBoolean("virtual", true);
        SimpleAssert
            .that(!HologramLocalControllerGhost.eligible(new HologramState(nbt)), "blueprints have their own owner");
    }

    private static void alternateOwner(HologramLocalControllerGhost.Lease lease) {
        SimpleAssert.that(
            !lease.superseded(false, false, null, lease.target.session),
            "the same editor retains its generation until it hands off");
        SimpleAssert.that(
            lease.superseded(true, false, null, null) && lease.superseded(false, true, null, null),
            "area selection, local blueprint placement and Cut cannot share a controller frame");
        SimpleAssert.that(
            lease.superseded(false, false, null, "new-workspace"),
            "discarding for another workspace cannot let the ghost reappear");
        NBTTagCompound server = new NBTTagCompound();
        server.setString("session", lease.serverSession);
        server.setBoolean("target", true);
        SimpleAssert.that(
            !lease.superseded(false, false, new HologramState(server), null),
            "a preexisting idle snapshot cannot steal a freshly captured local controller frame");
        server.setInteger("job", 1);
        SimpleAssert.that(
            lease.superseded(false, false, new HologramState(server), null),
            "an active server construction takes priority");
        server.setInteger("job", 0);
        server.setString("session", "preset-server-projection");
        SimpleAssert.that(
            lease.superseded(false, false, new HologramState(server), null),
            "authority reply from generating a preset replaces the local ghost");
    }
}
