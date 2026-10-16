package com.miaokatze.gtmb.hologram;

import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntityChest;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;
import com.miaokatze.gtmb.api.CutTransforms.Transform;

import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace;

/** Real server factories and disk transactions, including raw queue invariance and original-pose undo. */
final class HologramCutTransformNativeSmoke {

    private HologramCutTransformNativeSmoke() {}

    static void run(WorldServer world, EntityPlayerMP player) throws Exception {
        BaseMetaTileEntity gt = HologramInteractionSmoke.createFixture(world, player, 900, 200, 720);
        MTEElectricBlastFurnace machine = (MTEElectricBlastFurnace) gt.getMetaTileEntity();
        machine.setExtendedFacing(ExtendedFacing.of(ForgeDirection.NORTH, Rotation.CLOCKWISE, Flip.HORIZONTAL));
        gt.setOwnerName("RawPoseOwner");
        gt.setInventorySlotContents(1, new ItemStack(Items.diamond, 11));
        world.setBlock(902, 200, 720, Blocks.chest, 2, 2);
        world.setBlockMetadataWithNotify(902, 200, 720, 2, 2);
        ((TileEntityChest) world.getTileEntity(902, 200, 720))
            .setInventorySlotContents(4, new ItemStack(Items.emerald, 9));
        NBTTagCompound cut = action("cut", "");
        cut.setInteger("x1", 900);
        cut.setInteger("x2", 903);
        cut.setInteger("y1", 200);
        cut.setInteger("y2", 200);
        cut.setInteger("z1", 720);
        cut.setInteger("z2", 721);
        NBTTagCompound reply = HologramCutService.execute(player, cut);
        require(reply.getBoolean("success"), "pose fixture cut " + reply);
        String id = reply.getString("id");
        HologramCutStorage storage = new HologramCutStorage(HologramCutService.saveRoot(), player.getUniqueID());
        NBTTagCompound before = storage.load();
        Transform pose = new Transform(1, 0, 0, 1);
        NBTTagCompound preview = action("preview", id);
        pose.write(preview);
        NBTTagCompound display = HologramCutService.execute(player, preview);
        require(display.getBoolean("success"), "pose preview");
        require(
            storage.load()
                .equals(before),
            "preview leaves original raw queue bytes unchanged");
        NBTTagCompound invalid = action("paste", id);
        pose.write(invalid);
        invalid.setInteger("originY", 256);
        require(
            !HologramCutService.execute(player, invalid)
                .getBoolean("success"),
            "pose invalid world bounds");
        require(
            storage.load()
                .equals(before),
            "failed pose placement retains original snapshot");
        NBTTagCompound paste = action("paste", id);
        pose.write(paste);
        paste.setInteger("originX", 920);
        paste.setInteger("originY", 200);
        paste.setInteger("originZ", 720);
        require(
            HologramCutService.execute(player, paste)
                .getBoolean("success"),
            "pose native placement");
        int[] at = pose.point(0, 0, 0, 4, 1, 2);
        BaseMetaTileEntity placed = (BaseMetaTileEntity) world.getTileEntity(920 + at[0], 200 + at[1], 720 + at[2]);
        NBTTagCompound expected = display.getCompoundTag("preview")
            .getTagList("cells", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("tile");
        NBTTagCompound actual = new NBTTagCompound();
        placed.writeToNBT(actual);
        require(
            actual.getShort("mFacing") == expected.getShort("mFacing")
                && actual.getByte("eRotation") == expected.getByte("eRotation")
                && actual.getByte("eFlip") == expected.getByte("eFlip"),
            "native GT extended facing obeys signed pose");
        require(
            placed.getStackInSlot(1).stackSize == 11 && "RawPoseOwner".equals(placed.getOwnerName()),
            "pose keeps owner and inventory");
        int[] chestAt = pose.point(2, 0, 0, 4, 1, 2);
        require(
            world.getBlockMetadata(920 + chestAt[0], 200, 720 + chestAt[2]) == pose.direction(2),
            "native chest metadata follows pose");
        require(
            ((TileEntityChest) world.getTileEntity(920 + chestAt[0], 200, 720 + chestAt[2])).getStackInSlot(4).stackSize
                == 9,
            "native chest inventory survives pose");
        HologramCutNativeReceipt.record("transformedGT", expected, actual);
        NBTTagCompound recut = action("cut", "");
        recut.setInteger("x1", 920);
        recut.setInteger("x2", 921);
        recut.setInteger("y1", 200);
        recut.setInteger("y2", 200);
        recut.setInteger("z1", 720);
        recut.setInteger("z2", 723);
        NBTTagCompound next = HologramCutService.execute(player, recut);
        require(next.getBoolean("success"), "recut transformed native result");
        NBTTagCompound undo = action("undo", next.getString("id"));
        new Transform(3, 2, 1, 7).write(undo);
        require(
            HologramCutService.execute(player, undo)
                .getBoolean("success"),
            "undo ignores preview pose");
        BaseMetaTileEntity restored = (BaseMetaTileEntity) world.getTileEntity(920 + at[0], 200, 720 + at[2]);
        require(
            restored.getFrontFacing() == placed.getFrontFacing() && restored.getStackInSlot(1).stackSize == 11,
            "undo restores exact source pose and inventory");
        System.out.println(
            "[GTMB-CUT-TRANSFORM-NATIVE] PASS gtExtendedFacing=true chestMeta=true rawQueueImmutable=true undoOriginalPose=true");
    }

    private static NBTTagCompound action(String operation, String id) {
        NBTTagCompound n = new NBTTagCompound();
        n.setString("action", operation);
        n.setString("id", id);
        n.setString(
            "requestId",
            UUID.randomUUID()
                .toString());
        return n;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
