package com.miaokatze.gtmb.client.hologram;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Standalone registry/DTO test. No World, live player, network, native AE container or graphical game launch. */
public final class HologramAeContainerRegression {

    private HologramAeContainerRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAeContainerRegression.class, args)) return;
        run();
        System.out.println("AE virtual container regression passed");
    }

    public static void run() {
        ItemStack source = new ItemStack(Items.paper, 64);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("encodedFixture", "recipe-coordinate/owner/UUID remains exact");
        source.setTagCompound(tag);
        NBTTagCompound snapshot = snapshot(source);
        NBTTagCompound original = (NBTTagCompound) snapshot.copy();
        HologramAePreviewModel model = new HologramAePreviewModel(snapshot);
        HologramAePreviewModel.MarkSlot marker = model.slots.get(0);
        ItemStack bounded = marker.candidate(source);
        SimpleAssert.eq(1, bounded.stackSize, "pattern/upgrade limit bounds marked count");
        SimpleAssert.eq(64, source.stackSize, "marking does not decrement the source stack");
        bounded.getTagCompound()
            .setString("encodedFixture", "mutated candidate");
        SimpleAssert.that(
            !source.getTagCompound()
                .getString("encodedFixture")
                .equals("mutated candidate"),
            "candidate NBT is independent of the source");
        ItemStack icon = marker.stack();
        icon.stackSize = 0;
        icon.getTagCompound()
            .setString("encodedFixture", "mutated display");
        SimpleAssert.eq(64, marker.stack().stackSize, "icon read returns a copy");
        SimpleAssert.that(original.equals(snapshot), "DTO parsing never changes its snapshot");
        SimpleAssert.that(marker.allowed.equals("pattern"), "allowed list uses the common DTO contract");

        HologramAeGhostContainer container = new HologramAeGhostContainer(model.slots);
        SimpleAssert.eq(-1, container.windowId, "virtual container has no real player window");
        SimpleAssert.eq(1, container.inventorySlots.size(), "no real backpack slots are installed");
        for (int mode = 0; mode <= 6; mode++) for (int button = 0; button <= 8; button++) {
            SimpleAssert.that(
                container.slotClick(0, button, mode, null) == null,
                "pickup/shift/hotbar/clone/drop/drag/pickup-all cannot extract an icon");
        }
        SimpleAssert.that(container.transferStackInSlot(null, 0) == null, "shift transfer is disabled");
        SimpleAssert.that(
            !container.getSlot(0)
                .canTakeStack(null),
            "display slots are not takeable");
        SimpleAssert.that(
            !container.getSlot(0)
                .isItemValid(source),
            "display slots are not real insertion slots");
        SimpleAssert.that(
            container.getSlot(0)
                .decrStackSize(1) == null,
            "decrement cannot yield an item");
        SimpleAssert.that(
            container.getSlot(0).inventory.getStackInSlotOnClosing(0) == null,
            "closing cannot yield or return an item");
        container.putStackInSlot(0, null);
        container.putStacksInSlots(new ItemStack[] { null });
        container.getSlot(0)
            .putStack(null);
        container.getSlot(0).inventory.setInventorySlotContents(0, null);
        container.onContainerClosed(null);
        SimpleAssert.eq(
            64,
            container.getSlot(0)
                .getStack().stackSize,
            "all vanilla mutation routes preserve canonical marker");
        SimpleAssert.eq(64, source.stackSize, "all container operations preserve the source item count");

        NBTTagCompound disabledTag = slot(source);
        disabledTag.setBoolean("enabled", false);
        SimpleAssert.that(
            new HologramAePreviewModel.MarkSlot(disabledTag).candidate(source) == null,
            "disabled slots reject local SET candidates");
        disabledTag.setBoolean("enabled", true);
        disabledTag.setString("kind", "readonly");
        SimpleAssert.that(
            new HologramAePreviewModel.MarkSlot(disabledTag).candidate(source) == null,
            "readonly roles remain immutable even when an enabled bit is supplied");
        disabledTag.setString("issueCode", "cell.unsupported.omitted");
        disabledTag.setString("issuePath", "parts/0/extra/up/inv/item0");
        disabledTag.setInteger("limit", 0);
        disabledTag.removeTag("stack");
        HologramAePreviewModel.MarkSlot tombstone = new HologramAePreviewModel.MarkSlot(disabledTag);
        SimpleAssert.that(
            tombstone.stack() == null && !tombstone.enabled && tombstone.candidate(source) == null,
            "review tombstones have no item pickup or SET candidate");
        SimpleAssert.that(
            tombstone.issueCode.equals("cell.unsupported.omitted")
                && tombstone.issuePath.equals("parts/0/extra/up/inv/item0"),
            "tombstone keeps complete server issue code and path for explicit discard guidance");
        NBTTagCompound request = model.action("aePreviewEdit");
        SimpleAssert.that(
            request.getString("token")
                .equals("server-token") && request.getLong("planRevision") == 7
                && request.getInteger("x") == 12
                && request.getString("structureId")
                    .equals("server-group"),
            "edit action is bound to the issued token, revision and coordinate");
        SimpleAssert.that(
            !request.hasKey("payload") && !request.hasKey("kind") && !request.hasKey("owner"),
            "client projection does not send native payload, role or inventory authority");
    }

    private static NBTTagCompound snapshot(ItemStack stack) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("session", "server-session");
        n.setString("token", "server-token");
        n.setString("structureId", "server-group");
        n.setString("block", "appliedenergistics2:tile.BlockInterface");
        n.setLong("planRevision", 7);
        n.setInteger("x", 12);
        NBTTagList slots = new NBTTagList();
        slots.appendTag(slot(stack));
        n.setTag("slots", slots);
        return n;
    }

    private static NBTTagCompound slot(ItemStack stack) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("id", "patterns/#0");
        n.setString("kind", "physical");
        n.setString("label", "Pattern 1");
        n.setInteger("limit", 1);
        n.setBoolean("enabled", true);
        NBTTagList allowed = new NBTTagList();
        allowed.appendTag(new NBTTagString("pattern"));
        n.setTag("allowed", allowed);
        n.setTag("stack", stack.writeToNBT(new NBTTagCompound()));
        return n;
    }
}
