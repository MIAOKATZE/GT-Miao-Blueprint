package com.miaokatze.gtmb.client.hologram;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramProcessGovernor;

/** Deterministic ReferenceQueue events prove COW consumers keep a baseline's real lease alive. */
public final class HologramSnapshotRetentionsRegression {

    public static void main(String[] args) throws Exception {
        long before = HologramProcessGovernor.retainedBytes();
        HologramState baseline = new HologramState(new NBTTagCompound());
        HologramState patch = new HologramState(new NBTTagCompound());
        HologramSnapshotRetentions
            .bind(baseline, null, HologramProcessGovernor.retain("fixture.baseline", 4096), false);
        HologramSnapshotRetentions.bind(patch, baseline, HologramProcessGovernor.retain("fixture.patch", 1024), true);
        retire(baseline);
        HologramSnapshotRetentions.reap();
        check(
            HologramProcessGovernor.retainedBytes() == before + 5120,
            "live COW publication keeps the retired baseline charge");
        retire(patch);
        HologramSnapshotRetentions.reap();
        check(
            HologramProcessGovernor.retainedBytes() == before,
            "last COW consumer releases both baseline and patch exactly once");
        System.out.println("HologramSnapshotRetentionsRegression passed");
    }

    private static void retire(HologramState state) throws Exception {
        Field field = HologramSnapshotRetentions.class.getDeclaredField("owners");
        field.setAccessible(true);
        Map<?, ?> owners = (Map<?, ?>) field.get(null);
        for (Object key : owners.keySet()) {
            Reference<?> reference = (Reference<?>) key;
            if (reference.get() == state) {
                reference.clear();
                reference.enqueue();
                return;
            }
        }
        throw new AssertionError("retained state has no owner");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
