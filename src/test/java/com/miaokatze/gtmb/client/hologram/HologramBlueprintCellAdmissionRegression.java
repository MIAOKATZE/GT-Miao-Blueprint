package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Count-only snapshot fixtures reject before allocating or parsing host rows. */
public final class HologramBlueprintCellAdmissionRegression {

    private HologramBlueprintCellAdmissionRegression() {}

    public static void run() {
        new HologramState.Preparation(counted(999999));
        try {
            new HologramState.Preparation(counted(1000000));
            throw new AssertionError("cooperative decoder accepted one million hosts");
        } catch (IllegalArgumentException expected) {}
        try {
            new HologramState(counted(1000000));
            throw new AssertionError("full decoder accepted one million hosts");
        } catch (IllegalArgumentException expected) {}
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "count-boundary");
        source.setString("generation", "count-boundary");
        source.setBoolean("virtual", true);
        source.setLong("planRevision", 1);
        HologramState base = new HologramState(source);
        NBTTagCompound patch = counted(1000000);
        patch.setBoolean("partial", true);
        patch.setBoolean("topology", true);
        patch.setInteger("baseCellCount", 0);
        patch.setInteger("cellCount", 1000000);
        patch.setLong("baseRevision", 1);
        patch.setLong("planRevision", 2);
        patch.setString("session", "count-boundary");
        patch.setString("generation", "count-boundary");
        try {
            HologramState.apply(base, patch);
            throw new AssertionError("topology decoder accepted one million hosts");
        } catch (IllegalArgumentException expected) {
            SimpleAssert.that(base.cells.isEmpty(), "rejected topology leaves the previous publication intact");
        }
    }

    private static NBTTagCompound counted(int count) {
        return new NBTTagCompound() {

            @Override
            public NBTTagList getTagList(String name, int type) {
                if (!name.equals("cells")) return super.getTagList(name, type);
                return new NBTTagList() {

                    @Override
                    public int tagCount() {
                        return count;
                    }

                    @Override
                    public NBTTagCompound getCompoundTagAt(int index) {
                        throw new AssertionError("count admission must finish before decoding or allocating hosts");
                    }
                };
            }
        };
    }
}
