// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.UUID;

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
            lease.current(world, link, player, tile, -1, 100) && lease.show(true, true, 100),
            "first outside frame with the matching held tool");
        SimpleAssert
            .that(!lease.show(false, true, 100) && !lease.show(true, false, 100), "off toggle and editor cannot draw");
        SimpleAssert.that(
            !lease.current(new Object(), link, player, tile, -1, 100)
                && !lease.current(world, new Object(), player, tile, -1, 100)
                && !lease.current(world, link, new Object(), tile, -1, 100)
                && !lease.current(world, link, player, new Object(), -1, 100)
                && !lease.current(world, link, player, tile, 0, 100),
            "disconnects, controller replacement and world changes are not reused");
        long idle = HologramLocalControllerGhost.IDLE_VISIBILITY_NANOS;
        SimpleAssert.that(
            lease.observeHeld(true, 3, "tool", stack, 100 + 20 * idle)
                && lease.current(world, link, player, tile, -1, 100 + 20 * idle),
            "holding the original tool preserves an unfinished projection indefinitely");
        long switched = 100 + 20 * idle;
        SimpleAssert.that(
            lease.observeHeld(false, 4, "", null, switched) && lease.show(true, true, switched)
                && lease.observeHeld(true, 3, "tool", stack, switched + idle - 1),
            "switching away keeps the projection and returning before five seconds renews it");
        switched += 2 * idle;
        SimpleAssert.that(
            lease.observeHeld(true, 4, "other", stack, switched)
                && lease.current(world, link, player, tile, -1, switched + idle - 1)
                && !lease.observeHeld(true, 3, "tool", stack, switched + idle)
                && !lease.show(true, true, 100)
                && !lease.observeHeld(true, 3, "tool", stack, switched + idle + 1),
            "five seconds away retires the lease permanently, even if that tick first observes the returned tool");
        UUID request = new UUID(5, 6);
        HologramLocalControllerGhost.Lease running = new HologramLocalControllerGhost.Lease(
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
            100,
            request);
        SimpleAssert.that(
            running.current(world, link, player, tile, -1, 100 + 20 * idle)
                && !running.current(world, link, player, new Object(), -1, 100 + 20 * idle)
                && running.ownedBy(request)
                && !running.ownedBy(new UUID(5, 7))
                && !lease.ownedBy(request)
                && running.show(true, true, 100),
            "construction retains only the exact controller and terminal request");
        SimpleAssert.that(
            HologramLocalControllerGhost.afterTerminal(running, new UUID(5, 7)) == running
                && HologramLocalControllerGhost.afterTerminal(running, request) == running
                && !running.ownedBy(request)
                && HologramLocalControllerGhost.afterTerminal(lease, request) == lease,
            "an unfinished terminal releases only its active owner and keeps the exact idle projection");
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
            unlinked.observeHeld(true, 3, "", stack, 100) && unlinked.observeHeld(true, 3, "", new Object(), 101)
                && !unlinked.current(world, link, player, tile, -1, 101 + idle),
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
