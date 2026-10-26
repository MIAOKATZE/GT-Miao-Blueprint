package com.miaokatze.gtmb.client.hologram;

import java.lang.ref.Reference;
import java.lang.reflect.Field;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramProcessGovernor;

/** Deterministic ReferenceQueue events prove COW consumers keep a baseline's real lease alive. */
public final class HologramSnapshotRetentionsRegression {

    public static void main(String[] args) throws Exception {
        HologramState baseline = new HologramState(new NBTTagCompound());
        HologramState patch = new HologramState(new NBTTagCompound());
        HologramSnapshotRetentions
            .bind(baseline, null, HologramProcessGovernor.retain("fixture.baseline", 4096), false);
        HologramSnapshotRetentions.bind(patch, baseline, HologramProcessGovernor.retain("fixture.patch", 1024), true);
        // Admission may reap unrelated generic caches. Measure after both actual test leases exist.
        long charged = HologramProcessGovernor.retainedBytes();
        check(charged >= 5120, "both test consumers retain their admitted byte charges");
        retire(baseline);
        drainRetired();
        check(
            HologramProcessGovernor.retainedBytes() == charged,
            "live COW publication keeps the retired baseline charge");
        retire(patch);
        drainRetired();
        check(
            HologramProcessGovernor.retainedBytes() == charged - 5120,
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

    private static void drainRetired() throws Exception {
        Field field = HologramSnapshotRetentions.class.getDeclaredField("releases");
        field.setAccessible(true);
        for (int attempt = 0; attempt < 1024; attempt++) {
            HologramSnapshotRetentions.reap();
            if (((java.util.Deque<?>) field.get(null)).isEmpty()) {
                Field ownersField = HologramSnapshotRetentions.class.getDeclaredField("owners");
                ownersField.setAccessible(true);
                boolean queued = false;
                for (Object key : ((Map<?, ?>) ownersField.get(null)).keySet())
                    if (((Reference<?>) key).get() == null) queued = true;
                if (!queued) return;
            }
        }
        throw new AssertionError("bounded retirement callbacks did not drain the explicit fixture events");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
