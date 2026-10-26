package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.alignment.enumerable.Flip;
import com.gtnewhorizon.structurelib.alignment.enumerable.Rotation;

/** Versioned schemas inspected in GT5U .205, AE2 1080, EnderIO 2.10.48 and FMP 1.7.16 sources. */
final class HologramBlockTransformAdapters {

    private HologramBlockTransformAdapters() {}

    static void register() {
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return HologramBlockTransformStorageDrawers.supports(data.block, data.tile);
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) {
                HologramBlockTransformStorageDrawers.apply(
                    data.tile,
                    new com.miaokatze.gtmb.api.CutTransforms.Transform(vertical ? 0 : 1, vertical ? 1 : 0, 0, 0));
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.type("com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode")
                    || data.id("gtswn.network_quantum_node");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                if (data.tile == null) return;
                if (data.tile.hasKey("gtmb.quantumSides", 3)) data.tile
                    .setInteger("gtmb.quantumSides", mask(data.tile.getInteger("gtmb.quantumSides"), vertical));
                if (data.tile.hasKey("partContainer", 10)) ae(data.tile.getCompoundTag("partContainer"), vertical);
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.type("gregtech.") || data.id("BaseMetaTileEntity") || data.id("BaseMetaPipeEntity");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                gregtech(data.tile, vertical);
                // Item NBT uses CoverableTileEntity.writeCoverNBT(isDrop=true), the same cover/strong schema.
                gregtech(data.item, vertical);
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.type("appeng.");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                ae(data.tile, vertical);
                if (data.block != null) {
                    String type = data.block.getClass()
                        .getName();
                    data.metadata = HologramCutTransformDirections.aeMetadata(
                        type,
                        data.metadata,
                        new com.miaokatze.gtmb.api.CutTransforms.Transform(vertical ? 0 : 1, vertical ? 1 : 0, 0, 0));
                }
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.type("crazypants.enderio.conduit.");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                enderIo(data.tile, vertical);
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.id("savedMultipart") || data.type("codechicken.multipart.");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                multipart(data.tile, vertical);
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.type("gcewing.architecture.common.block.BlockSawbench") || data.id("gcewing.sawbench");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                // PropertyTurn packs NORTH,WEST,SOUTH,EAST in the low two bits;
                // Orient4WaysByState uses that exact index for Trans3.turn.
                if (vertical) throw new IOException("ArchitectureCraft sawbench supports horizontal rotation only");
                if (data.metadata < 0 || data.metadata > 3)
                    throw new IOException("Invalid ArchitectureCraft sawbench turn");
                data.metadata = (data.metadata + 3) & 3;
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.type("gcewing.architecture.common.block.BlockShape") || data.id("gcewing.shape");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                architecture(data.tile, vertical);
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.type("com.creativemd.littletiles.") || data.id("LittleTilesTileEntity");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                HologramBlockTransformLittleTiles.turn(data, vertical);
            }
        });
        HologramBlockTransforms.register(new HologramBlockTransforms.Adapter() {

            public boolean accepts(HologramBlockTransforms.Data data) {
                return data.type("com.carpentersblocks.") || data.id("TileEntityCarpentersBlock");
            }

            public void turn(HologramBlockTransforms.Data data, boolean vertical) throws IOException {
                HologramBlockTransformCarpenters.turn(data, vertical);
            }
        });
    }

    static void gregtech(NBTTagCompound tag, boolean vertical) {
        if (tag == null) return;
        if (tag.hasKey("mFacing")) {
            int front = tag.getShort("mFacing");
            if (front >= 0 && front < 6 && (tag.hasKey("eRotation") || tag.hasKey("eFlip"))) {
                ExtendedFacing facing = ExtendedFacing.of(
                    ForgeDirection.getOrientation(front),
                    Rotation.byIndex(tag.getByte("eRotation")),
                    Flip.byIndex(tag.getByte("eFlip")));
                ExtendedFacing rotated = HologramVirtualTransform.alignment(facing, vertical);
                tag.setByte(
                    "eRotation",
                    (byte) rotated.getRotation()
                        .getIndex());
                tag.setByte(
                    "eFlip",
                    (byte) rotated.getFlip()
                        .getIndex());
            }
            tag.setShort("mFacing", (short) HologramVirtualTransform.front(front, vertical));
        }
        for (String key : new String[] { "mConnections", "mStrongRedstone" })
            if (tag.hasKey(key)) tag.setByte(key, (byte) mask(tag.getByte(key) & 255, vertical));
        sidedBytes(tag, "mRedstoneSided", vertical);
        if (tag.hasKey("mID")) {
            int id = tag.getInteger("mID");
            Object prototype = id >= 0 && id < gregtech.api.GregTechAPI.METATILEENTITIES.length
                ? gregtech.api.GregTechAPI.METATILEENTITIES[id]
                : null;
            if (prototype instanceof gregtech.api.metatileentity.implementations.MTEFluidPipe) {
                for (String key : new String[] { "mLastReceivedFrom", "mDisableInput" })
                    if (tag.hasKey(key)) tag.setByte(key, (byte) mask(tag.getByte(key) & 255, vertical));
            } else if (prototype instanceof gregtech.api.metatileentity.implementations.MTEItemPipe
                && tag.hasKey("mLastReceivedFrom"))
                tag.setByte(
                    "mLastReceivedFrom",
                    (byte) HologramVirtualTransform.front(tag.getByte("mLastReceivedFrom"), vertical));
        }
        NBTTagList covers = tag.getTagList("gt.covers", 10);
        for (int i = 0; i < covers.tagCount(); i++) {
            NBTTagCompound cover = covers.getCompoundTagAt(i);
            if (cover.hasKey("s"))
                cover.setByte("s", (byte) HologramVirtualTransform.front(cover.getByte("s"), vertical));
        }
    }

    static int mask(int mask, boolean vertical) {
        int result = mask & ~63;
        for (int side = 0; side < 6; side++)
            if ((mask & (1 << side)) != 0) result |= 1 << HologramVirtualTransform.front(side, vertical);
        return result;
    }

    static void sidedBytes(NBTTagCompound tag, String key, boolean vertical) {
        if (!tag.hasKey(key, 7)) return;
        byte[] original = tag.getByteArray(key), rotated = original.clone();
        if (original.length != 6) return;
        for (int side = 0; side < 6; side++) rotated[HologramVirtualTransform.front(side, vertical)] = original[side];
        tag.setByteArray(key, rotated);
    }

    /** Moves exact, documented numbered/enum side suffixes, retaining each value's NBT type. */
    static void sideKeys(NBTTagCompound tag, String prefix, boolean names, boolean vertical) {
        NBTBase[] original = new NBTBase[6];
        for (int side = 0; side < 6; side++) {
            String key = prefix + (names ? ForgeDirection.getOrientation(side)
                .name() : side);
            original[side] = tag.getTag(key);
            tag.removeTag(key);
        }
        for (int side = 0; side < 6; side++) if (original[side] != null) {
            int next = HologramVirtualTransform.front(side, vertical);
            tag.setTag(
                prefix + (names ? ForgeDirection.getOrientation(next)
                    .name() : next),
                original[side]);
        }
    }

    static void ae(NBTTagCompound tag, boolean vertical) throws IOException {
        if (tag == null) return;
        for (String key : new String[] { "orientation_forward", "orientation_up" }) if (tag.hasKey(key, 8)) {
            try {
                int side = ForgeDirection.valueOf(tag.getString(key))
                    .ordinal();
                tag.setString(
                    key,
                    ForgeDirection.getOrientation(HologramVirtualTransform.front(side, vertical))
                        .name());
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid AE2 orientation: " + tag.getString(key), invalid);
            }
        }
        if ("BlockMolecularAssembler".equals(tag.getString("id")) && tag.hasKey("pushDirection", 3))
            tag.setInteger("pushDirection", HologramVirtualTransform.front(tag.getInteger("pushDirection"), vertical));
        for (int side = 0; side < 6; side++) {
            NBTTagCompound extra = tag.getCompoundTag("extra:" + side);
            if (extra.hasKey("spin")) extra.setByte("spin", (byte) aeSpin(side, extra.getByte("spin"), vertical));
        }
        for (String prefix : new String[] { "def:", "extra:", "facade:" }) sideKeys(tag, prefix, false, vertical);
        // def:6/extra:6 is the central cable, not a seventh side. Preserve it unchanged.
        if (tag.hasKey("gtmb.aeCableVisual", 10)) {
            NBTTagCompound visual = tag.getCompoundTag("gtmb.aeCableVisual");
            visual.setInteger("connections", mask(visual.getInteger("connections"), vertical));
            int channels = visual.getInteger("channels"), next = channels & 0xff000000;
            for (int side = 0; side < 6; side++)
                next |= ((channels >>> (side * 4)) & 15) << (HologramVirtualTransform.front(side, vertical) * 4);
            visual.setInteger("channels", next);
        }
    }

    static int aeSpin(int side, int spin, boolean vertical) {
        // CableRenderHelper.setSide defines the unspun local up; RenderBlocks stores quarter turns as 0,1,3,2.
        int[] localUp = { 2, 3, 1, 1, 1, 1 }, codes = { 0, 1, 3, 2 };
        if (spin < 0 || spin > 3) return spin;
        int up = localUp[side], quarters = 0;
        for (int i = 0; i < 4; i++) if (codes[i] == spin) quarters = i;
        for (int i = 0; i < quarters; i++) up = around(up, side);
        int wanted = HologramVirtualTransform.front(up, vertical),
            next = HologramVirtualTransform.front(side, vertical);
        up = localUp[next];
        for (int i = 0; i < 4; i++) {
            if (up == wanted) return codes[i];
            up = around(up, next);
        }
        return spin;
    }

    private static int around(int direction, int axis) {
        ForgeDirection d = ForgeDirection.getOrientation(direction), a = ForgeDirection.getOrientation(axis);
        return direction(
            a.offsetY * d.offsetZ - a.offsetZ * d.offsetY,
            a.offsetZ * d.offsetX - a.offsetX * d.offsetZ,
            a.offsetX * d.offsetY - a.offsetY * d.offsetX);
    }

    static int direction(int x, int y, int z) {
        for (ForgeDirection d : ForgeDirection.VALID_DIRECTIONS)
            if (d.offsetX == x && d.offsetY == y && d.offsetZ == z) return d.ordinal();
        throw new IllegalArgumentException("Not an axis direction");
    }

    static void enderIo(NBTTagCompound tag, boolean vertical) throws IOException {
        if (tag == null) return;
        NBTTagList conduits = tag.getTagList("conduits", 10);
        for (int i = 0; i < conduits.tagCount(); i++) {
            NBTTagCompound root = conduits.getCompoundTagAt(i), body = root.getCompoundTag("conduit");
            if (!root.getString("conduitType")
                .startsWith("crazypants.enderio.conduit."))
                throw new IOException("Unsupported EnderIO conduit: " + root.getString("conduitType"));
            for (String key : new String[] { "connections", "externalConnections" }) if (body.hasKey(key, 11)) {
                int[] dirs = body.getIntArray(key)
                    .clone();
                for (int j = 0; j < dirs.length; j++) dirs[j] = HologramVirtualTransform.front(dirs[j], vertical);
                body.setIntArray(key, dirs);
            }
            for (String key : new String[] { "conModes", "forcedConnections", "signalColors", "signalStrengths" })
                sidedBytes(body, key, vertical);
            for (String prefix : new String[] { "extRM.", "extSC.", "inSC.", "outSC.", "pRsMode.", "pRsCol.",
                "inFilts.", "outFilts.", "speedUpgrades.", "functionUpgrades.", "inputFilterUpgrades.",
                "outputFilterUpgrades.", "selfFeed.", "roundRobin.", "priority." })
                sideKeys(body, prefix, true, vertical);
        }
    }

    static void multipart(NBTTagCompound tag, boolean vertical) throws IOException {
        if (tag == null) return;
        NBTTagList parts = tag.getTagList("parts", 10);
        for (int i = 0; i < parts.tagCount(); i++) {
            NBTTagCompound part = parts.getCompoundTagAt(i);
            String id = part.getString("id");
            if ("ae2_cablebus".equals(id)) {
                // CableBusPart.save/load delegates its own root compound to CableBusContainer.
                ae(part, vertical);
                continue;
            }
            int shape = part.getByte("shape") & 255, slot = shape & 15, next;
            if ("mcr_face".equals(id) || "mcr_hllw".equals(id)) next = HologramVirtualTransform.front(slot, vertical);
            else if ("mcr_cnr".equals(id)) {
                int[] v = HologramVirtualTransform
                    .turn((slot & 4) == 0 ? -1 : 1, (slot & 1) == 0 ? -1 : 1, (slot & 2) == 0 ? -1 : 1, vertical);
                next = (v[0] > 0 ? 4 : 0) | (v[1] > 0 ? 1 : 0) | (v[2] > 0 ? 2 : 0);
            } else if ("mcr_edge".equals(id)) {
                if (slot >= 12) throw new IOException("Invalid FMP edge slot");
                int[] v = edge(slot), wanted = HologramVirtualTransform.turn(v[0], v[1], v[2], vertical);
                next = -1;
                for (int n = 0; n < 12; n++) if (java.util.Arrays.equals(edge(n), wanted)) next = n;
                if (next < 0) throw new IOException("FMP edge has no transformed slot");
            } else if ("mcr_post".equals(id)) {
                // PostMicroClass encodes Y=0, Z=1, X=2 (not Forge side-pair order).
                int[] axes = { 1, 3, 5 };
                if (slot > 2) throw new IOException("Invalid FMP post axis");
                int transformed = HologramVirtualTransform.front(axes[slot], vertical) >> 1;
                next = transformed == 0 ? 0 : transformed == 1 ? 1 : 2;
            } else throw new IOException("Unsupported FMP part transform: " + id);
            part.setByte("shape", (byte) ((shape & 240) | next));
        }
    }

    private static int[] edge(int slot) {
        // PartMap.unpackEdgeBits/edgeAxisMask: corner bits Y=1, Z=2, X=4.
        int group = slot >> 2,
            bits = group == 0 ? (slot & 3) << 1 : group == 1 ? ((slot & 2) >> 1) | ((slot & 1) << 2) : slot & 3;
        return new int[] { group == 2 ? 0 : (bits & 4) == 0 ? -1 : 1, group == 0 ? 0 : (bits & 1) == 0 ? -1 : 1,
            group == 1 ? 0 : (bits & 2) == 0 ? -1 : 1 };
    }

    static void architecture(NBTTagCompound tag, boolean vertical) throws IOException {
        if (tag == null) return;
        int side = tag.getByte("side"), turn = tag.getByte("turn");
        if (side < 0 || side > 5 || turn < 0 || turn > 3) throw new IOException("Invalid ArchitectureCraft side/turn");
        int[] original = architectureBasis(side, turn), wanted = original.clone();
        for (int i = 0; i < 3; i++) wanted[i] = HologramVirtualTransform.front(original[i], vertical);
        for (int s = 0; s < 6; s++)
            for (int t = 0; t < 4; t++) if (java.util.Arrays.equals(architectureBasis(s, t), wanted)) {
                tag.setByte("side", (byte) s);
                tag.setByte("turn", (byte) t);
                // TileShape.disabledConnections is LOCAL space; side/turn already moves that basis.
                return;
            }
        throw new IOException("ArchitectureCraft rotation has no side/turn representation");
    }

    static int[] architectureBasis(int side, int turn) {
        // Exact integer columns of ArchitectureCraft Matrix3.sideRotations * Matrix3.turnRotations.
        int[] x = { 5, 5, 5, 4, 2, 3 }, y = { 1, 0, 3, 2, 5, 4 }, z = { 3, 2, 0, 0, 0, 0 };
        int xx = x[side], zz = z[side];
        for (int i = 0; i < turn; i++) {
            int oldX = xx;
            xx = zz ^ 1;
            zz = oldX;
        }
        return new int[] { xx, y[side], zz };
    }
}
