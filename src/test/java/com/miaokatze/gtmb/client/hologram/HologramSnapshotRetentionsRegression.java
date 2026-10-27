package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramProcessGovernor;

/** Published immutable views are no longer pending work; old consumers keep ordinary Java ownership. */
public final class HologramSnapshotRetentionsRegression {

    public static void main(String[] args) {
        long before = HologramProcessGovernor.retainedBytes();
        NBTTagCompound source = new NBTTagCompound(), row = new NBTTagCompound();
        source.setString("session", "publication");
        source.setString("generation", "publication");
        source.setLong("planRevision", 0);
        source.setString("unchangedMetadata", "retained across partial publications");
        row.setInteger("x", 1);
        row.setInteger("nativeModelPending", 3);
        row.setLong("nativeModelId", 7);
        NBTTagList cells = new NBTTagList();
        cells.appendTag(row);
        source.setTag("cells", cells);
        final HologramState[] current = { null };
        publish(source, value -> current[0] = new HologramState(value));
        HologramState original = current[0], savedMiddle = null;
        for (int i = 0; i < 100; i++) {
            NBTTagCompound patch = delta();
            patch.setInteger("latest", i);
            patch.setByteArray("replaceableMetadata", new byte[1048576]);
            publish(patch, value -> current[0] = HologramState.apply(current[0], value));
            if (i == 31) savedMiddle = current[0];
            check(current[0].cells == original.cells, "metadata ACK shares its actual geometry");
            check(
                HologramProcessGovernor.retainedBytes() <= before,
                "published models must not consume work quota until the JVM next collects an old view");
        }
        check(current[0].data.getInteger("latest") == 99, "latest authoritative metadata is installed");
        check(savedMiddle.data.getInteger("latest") == 31, "an old UI consumer keeps its immutable metadata");
        check(
            current[0].data.getString("unchangedMetadata")
                .equals(original.data.getString("unchangedMetadata")),
            "a partial ACK preserves metadata omitted from the update");
        NBTTagCompound patch = delta(), changed = original.cells.get(0)
            .tag();
        changed.setInteger("index", 0);
        changed.setInteger("wantMeta", 4);
        NBTTagList replacements = new NBTTagList();
        replacements.appendTag(changed);
        patch.setTag("cells", replacements);
        publish(patch, value -> current[0] = HologramState.apply(current[0], value));
        check(current[0].cells.get(0).wantMeta == 4, "partial geometry ACK publishes its exact replacement");
        check(original.cells.get(0).wantMeta == 0, "retained old cells remain valid across COW publication");
        modelPublication(current[0]);
        publish(source, value -> current[0] = new HologramState(value));
        check(
            current[0].cells != original.cells,
            "a new full baseline replaces geometry without retiring old consumers");
        check(original.cells.get(0).x == 1 && savedMiddle.cells.get(0).x == 1, "old view consumers remain usable");
        HologramSnapshotInbox.Result<NBTTagCompound> abandoned = pending(source);
        abandoned.close();
        abandoned.close();
        try {
            pending(source).publish(value -> { throw new IllegalStateException("publication cancelled"); });
            throw new AssertionError("expected publication cancellation");
        } catch (IllegalStateException expected) {}
        check(
            HologramProcessGovernor.retainedBytes() <= before,
            "publish, abort and repeated close return work leases");
        System.out.println("publicationCount=100 liveMetadataBytes=1048576 retainedWorkDelta=0 noGc=true");
        System.out.println("HologramSnapshotRetentionsRegression passed");
    }

    private static NBTTagCompound delta() {
        NBTTagCompound patch = new NBTTagCompound();
        patch.setBoolean("partial", true);
        patch.setString("session", "publication");
        patch.setString("generation", "publication");
        patch.setLong("baseRevision", 0);
        patch.setLong("planRevision", 0);
        patch.setInteger("cellCount", 1);
        return patch;
    }

    private static void modelPublication(HologramState base) {
        long before = HologramProcessGovernor.retainedBytes();
        NBTTagCompound nativeTile = new NBTTagCompound();
        nativeTile.setString("id", "exact model payload");
        HologramState.ModelPreparation preparation = new HologramState.ModelPreparation(
            base,
            java.util.Collections.singletonList(new HologramState.ModelRow(0, 7, nativeTile, null, false)));
        try {
            check(preparation.step(16, Long.MAX_VALUE), "bounded model preparation completes");
            HologramState next = preparation.result(base);
            check(next != base && next.cells.get(0).actualDisplay.hasKey("nativeTile"), "model-only view is published");
            preparation.published();
            check(HologramProcessGovernor.retainedBytes() <= before, "published model cell/page/region work closes");
            check(!base.cells.get(0).actualDisplay.hasKey("nativeTile"), "old model consumer is unchanged");
        } finally {
            preparation.close();
        }
    }

    private static HologramSnapshotInbox.Result<NBTTagCompound> pending(NBTTagCompound value) {
        HologramSnapshotInbox.Result<NBTTagCompound> result = new HologramSnapshotInbox.Result<>(
            value,
            null,
            1,
            HologramProcessGovernor.retain("fixture.decoded", 1048576),
            1048576);
        result.materialized = HologramProcessGovernor.retain("fixture.preparing", 2097152);
        return result;
    }

    private static void publish(NBTTagCompound value, java.util.function.Consumer<NBTTagCompound> consumer) {
        HologramSnapshotInbox.Result<NBTTagCompound> result = pending(value);
        long charged = HologramProcessGovernor.retainedBytes();
        result.publish(consumer);
        check(
            HologramProcessGovernor.retainedBytes() == charged - 3145728,
            "publication explicitly returns both of its actual work leases");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
