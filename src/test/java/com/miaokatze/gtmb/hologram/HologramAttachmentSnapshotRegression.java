// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Exercise owner capture, cancellation and the actual snapshot encoder/decoder, including the editor wire shape. */
public final class HologramAttachmentSnapshotRegression {

    private HologramAttachmentSnapshotRegression() {}

    public static void main(String[] args) throws Exception {
        NBTTagCompound raw = new NBTTagCompound();
        raw.setString("text", "draft");
        NBTTagCompound picture = new NBTTagCompound();
        int[] pixels = new int[1024 * 1024];
        java.util.Arrays.fill(pixels, 0xff12983c);
        picture.setIntArray("source", pixels);
        raw.setTag("picture", picture);

        HologramPresets.Structure s = new HologramPresets.Structure();
        s.id = "sample";
        s.x = -7;
        s.y = 3;
        s.z = 14;
        s.textBoxNbt = HologramBlueprintNbt.encodeCaptured(raw);
        NBTTagCompound expected = HologramTextWorkflow.definition(s);
        NBTTagCompound label = new NBTTagCompound();
        label.setString("id", s.id);
        label.setInteger("x", 83);
        HologramAttachmentSnapshot.capture(label, s);
        NBTTagCompound broken = new NBTTagCompound();
        s.textBoxNbt = "invalid~";
        HologramAttachmentSnapshot.capture(broken, s);
        s.x++;
        NBTTagList labels = new NBTTagList();
        labels.appendTag(label);
        labels.appendTag(broken);
        NBTTagCompound owned = new NBTTagCompound();
        owned.setTag("structures", labels);
        List<Runnable> queue = new ArrayList<>();
        HologramSnapshotEncoding.Job job = HologramSnapshotEncoding.submit(owned, queue::add);
        if (job.done || label.hasKey("textBox") || label.hasKey("textBoxError") || broken.hasKey("textBoxError"))
            throw new AssertionError("Owner inflated attachment data before the encoder ran");
        Thread worker = new Thread(queue.remove(0), "detached snapshot regression");
        worker.start();
        worker.join(10000);
        if (!job.done || job.failure != null) throw new AssertionError("Encoder did not finish", job.failure);
        NBTTagList wire = HologramSnapshotCodec.decode(job.bytes)
            .getTagList("structures", 10);
        NBTTagCompound row = wire.getCompoundTagAt(0);
        if (!expected.equals(row.getCompoundTag("textBox")) || row.getInteger("x") != 83
            || row.func_150296_c()
                .size() != 3)
            throw new AssertionError("Bitmap, frozen local pose, world label or wire keys differ");
        if (!"文字定义损坏".equals(
            wire.getCompoundTagAt(1)
                .getString("textBoxError"))
            || wire.getCompoundTagAt(1)
                .func_150296_c()
                .size() != 1)
            throw new AssertionError("Damaged editor data must keep its old per-label error");
        HologramAttachmentSnapshot.capture(label, s);
        HologramSnapshotEncoding.Job cancelled = HologramSnapshotEncoding.submit(owned, queue::add);
        cancelled.cancel();
        queue.remove(0)
            .run();
        if (cancelled.bytes != null || cancelled.hash != null) throw new AssertionError("Cancelled snapshot published");
        System.out.println("PASS: 1024x1024 detached snapshot, stable wire payload, damaged row and cancellation");
    }
}
