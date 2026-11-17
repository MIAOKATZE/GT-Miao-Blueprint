// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.StructureUtility;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual packet dispatcher and editor against the world fixture that forbids block and TE writes. */
public final class HologramBatchEditingRegression {

    private HologramBatchEditingRegression() {}

    @SuppressWarnings("unchecked")
    public static void run() {
        EntityPlayerMP player = null;
        Map<UUID, HologramSession> sessions = null;
        HologramSession previous = null;
        try {
            Field unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) unsafeField.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            // Reuse the established native player/network fixture, including its real projector identity.
            Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
                "player",
                sun.misc.Unsafe.class,
                HologramServiceEditRegression.ReadOnlyWorld.class,
                UUID.class,
                String.class);
            factory.setAccessible(true);
            player = (EntityPlayerMP) factory.invoke(null, unsafe, world, UUID.randomUUID(), "BatchEditorFixture");
            player.capabilities.allowEdit = true;
            player.inventory.mainInventory[1] = new ItemStack(Blocks.dirt, 32);
            Field registry = HologramWorkspace.class.getDeclaredField("SESSIONS");
            registry.setAccessible(true);
            sessions = (Map<UUID, HologramSession>) registry.get(null);
            HologramSession s = fixture(player);
            previous = sessions.put(player.getUniqueID(), s);
            NBTTagCompound request = request(s, "customPin");
            request.setInteger("inventorySlot", 1);
            HologramActionDispatcher.handle(player, request);
            target(s, 0, Blocks.dirt);
            target(s, 1, Blocks.dirt);
            target(s, 2, Blocks.stone);
            SimpleAssert
                .eq(32, player.inventory.mainInventory[1].stackSize, "batch inventory sample never consumes stock");
            SimpleAssert.eq(1, s.undoEdits.size(), "a batch inventory replacement is one undo operation");
            SimpleAssert
                .eq(Arrays.asList(0, 1), new java.util.ArrayList<>(s.selectedIndices), "server retains both targets");
            HologramActionDispatcher.handle(player, request(s, "editUndo"));
            target(s, 0, Blocks.stone);
            target(s, 1, Blocks.stone);
            HologramActionDispatcher.handle(player, request(s, "editRedo"));
            target(s, 0, Blocks.dirt);
            target(s, 1, Blocks.dirt);

            request = request(s, "sourcePin");
            s.materialChoices.clear();
            s.materialChoices.add(new ItemStack(Blocks.stone));
            player.inventory.mainInventory[2] = new ItemStack(Blocks.stone, 32);
            request.setInteger("choice", 0);
            HologramActionDispatcher.handle(player, request);
            target(s, 0, Blocks.stone);
            target(s, 1, Blocks.stone);
            request = request(s, "customPin");
            request.setInteger("inventorySlot", 1);
            HologramActionDispatcher.handle(player, request);
            target(s, 0, Blocks.dirt);
            target(s, 1, Blocks.dirt);
            SimpleAssert
                .eq(32, player.inventory.mainInventory[1].stackSize, "source sampling only simulates availability");

            // The current client sends both arrays; selectedIndices-only older senders must also restore the batch.
            request = request(s, "restoreCell");
            HologramActionDispatcher.handle(player, request);
            target(s, 0, Blocks.stone);
            target(s, 1, Blocks.stone);
            target(s, 2, Blocks.stone);
            HologramActionDispatcher.handle(player, request(s, "editUndo"));
            target(s, 0, Blocks.dirt);
            target(s, 1, Blocks.dirt);

            HologramSnapshotPreparation.cancel(s);
            s = fixture(player);
            sessions.put(player.getUniqueID(), s);
            s.capture.cells.get(1).anchor = true;
            request = request(s, "customPin");
            request.setInteger("inventorySlot", 1);
            HologramActionDispatcher.handle(player, request);
            SimpleAssert.that(
                s.pins.isEmpty() && s.coordinatePins.isEmpty(),
                "one protected member rejects the complete replacement");
            SimpleAssert.that(s.undoEdits.isEmpty(), "rejected batch has no target history");
            s.capture.cells.get(1).anchor = false;
            s.capture.cells.get(1).role = "templateController";
            HologramBlueprintEditor.edit(s, request(s, "restoreCell"), "restoreCell");
            SimpleAssert.that(s.pins.isEmpty(), "one protected template member rejects the complete restore");
            s.capture.cells.get(1).role = "";
            request = request(s, "customPin");
            request.setInteger("inventorySlot", 1);
            request.setIntArray("selectedIndices", new int[] { 0, 9000 });
            HologramActionDispatcher.handle(player, request);
            SimpleAssert.that(s.pins.isEmpty(), "stale batch index cannot partially update a valid target");
            request = request(s, "customPin");
            request.setInteger("inventorySlot", 1);
            ItemStack changedNbt = new ItemStack(Blocks.dirt);
            changedNbt.setTagCompound(new NBTTagCompound());
            changedNbt.getTagCompound()
                .setString("identity", "previous sample");
            request.setTag("expectedSample", changedNbt.writeToNBT(new NBTTagCompound()));
            HologramActionDispatcher.handle(player, request);
            SimpleAssert.that(s.pins.isEmpty(), "changed inventory sample NBT rejects the entire selection");
            request = request(s, "sourcePin");
            request.setInteger("choice", 0);
            s.materialChoices.clear();
            s.materialChoices.add(new ItemStack(Blocks.stone));
            request.setTag("expectedSample", new ItemStack(Blocks.dirt).writeToNBT(new NBTTagCompound()));
            HologramActionDispatcher.handle(player, request);
            SimpleAssert.that(
                s.pins.isEmpty(),
                "reordered source directory cannot replace the batch with a different material");
            HologramSnapshotPreparation.cancel(s);
        } catch (Exception failure) {
            throw new AssertionError("batch planning production paths", failure);
        } finally {
            if (player != null) {
                if (sessions != null) {
                    HologramSession current = sessions.get(player.getUniqueID());
                    if (current != null) HologramSnapshotPreparation.cancel(current);
                    if (previous == null) sessions.remove(player.getUniqueID());
                    else sessions.put(player.getUniqueID(), previous);
                }
                if (player.playerNetServerHandler != null) player.playerNetServerHandler.netManager.channel()
                    .close();
            }
        }
    }

    private static HologramSession fixture(EntityPlayerMP player) {
        HologramSession s = new HologramSession(player, 0, 64, 0, false, 2);
        s.materialSettings.main = true;
        s.materialSettings.containers = s.materialSettings.me = false;
        s.materialContext = new HologramMaterials.Context(player, s.materialSettings);
        s.capture = new HologramCapture(null, new ItemStack(Blocks.stone));
        for (int x = 1; x <= 3; x++) {
            HologramCapture.Cell cell = new HologramCapture.Cell(
                StructureUtility
                    .ofChain(StructureUtility.ofBlock(Blocks.stone, 0), StructureUtility.ofBlock(Blocks.dirt, 0)),
                x,
                64,
                0);
            cell.block = Blocks.stone;
            s.capture.cells.add(cell);
        }
        HologramResourcePlanner.refreshPlan(s);
        return s;
    }

    private static NBTTagCompound request(HologramSession s, String op) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("op", op);
        n.setString("session", s.id);
        n.setLong("planRevision", s.planRevision);
        n.setInteger("selected", 0);
        n.setIntArray("selectedIndices", new int[] { 0, 1 });
        return n;
    }

    private static void target(HologramSession s, int index, net.minecraft.block.Block expected) {
        SimpleAssert.that(
            HologramValues.sameMaterial(new ItemStack(expected), s.planTargets.get(index)),
            "planned target #" + index + " remains independently correct");
    }
}
