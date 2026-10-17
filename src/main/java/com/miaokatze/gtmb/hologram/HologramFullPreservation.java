package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** Small, payload-local author decisions. The actual resource tree remains the sole source of payment identities. */
final class HologramFullPreservation {

    static final String TAG = "gtmbFullPreservation";
    private static final int LIMIT = 256;

    private HologramFullPreservation() {}

    static Set<String> roots(NBTTagCompound payload) throws IOException {
        Set<String> roots = new LinkedHashSet<>();
        if (payload == null || !payload.hasKey(TAG)) return roots;
        if (!payload.hasKey(TAG, 10)) throw new IOException("完全保留记录类型无效");
        NBTTagCompound receipt = payload.getCompoundTag(TAG);
        if (!receipt.hasKey("version", 3) || receipt.getInteger("version") != 1
            || !receipt.hasKey("roots", 9)
            || receipt.func_150296_c()
                .size() != 2)
            throw new IOException("完全保留记录格式无效");
        NBTTagList names = receipt.getTagList("roots", 8);
        if (names.tagCount() == 0 || names.tagCount() > LIMIT
            || ((NBTTagList) receipt.getTag("roots")).func_150303_d() != 8) throw new IOException("完全保留记录路径无效");
        for (int i = 0; i < names.tagCount(); i++) {
            String root = names.getStringTagAt(i);
            if (root.isEmpty() || root.length() > 256
                || root.equals(TAG)
                || !payload.hasKey(root)
                || HologramBlueprintNbt.protectedKey(payload, root)
                || !roots.add(root)) throw new IOException("完全保留路径无效或已移除：/" + escape(root));
        }
        return roots;
    }

    static void mark(NBTTagCompound payload, String root) throws IOException {
        Set<String> roots = roots(payload);
        roots.add(root);
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setInteger("version", 1);
        NBTTagList names = new NBTTagList();
        for (String name : roots) names.appendTag(new NBTTagString(name));
        receipt.setTag("roots", names);
        payload.setTag(TAG, receipt);
        roots(payload);
    }

    static void forget(NBTTagCompound payload, String root) {
        if (!payload.hasKey(TAG, 10)) return;
        NBTTagList previous = payload.getCompoundTag(TAG)
            .getTagList("roots", 8), names = new NBTTagList();
        for (int i = 0; i < previous.tagCount(); i++)
            if (!root.equals(previous.getStringTagAt(i))) names.appendTag(new NBTTagString(previous.getStringTagAt(i)));
        if (names.tagCount() == 0) payload.removeTag(TAG);
        else payload.getCompoundTag(TAG)
            .setTag("roots", names);
    }

    /** Used only by the actor rewriter; the planner separately validates the complete receipt and every resource. */
    static boolean preserves(NBTTagCompound payload, String root) {
        if (!payload.hasKey(TAG, 10)) return false;
        NBTTagCompound receipt = payload.getCompoundTag(TAG);
        if (receipt.getInteger("version") != 1) return false;
        NBTTagList names = receipt.getTagList("roots", 8);
        for (int i = 0; i < names.tagCount(); i++) if (root.equals(names.getStringTagAt(i))) return true;
        return false;
    }

    static void assertUnchanged(NBTTagCompound before, NBTTagCompound after) throws IOException {
        for (String root : roots(before)) if (after == null || !before.getTag(root)
            .equals(after.getTag(root))) throw new IOException("完全保留路径与其他处理选项冲突：/" + escape(root) + "；请取消冲突选项或移除此信息");
    }

    /** Restore only explicitly paid resource roots after the device identity/coordinate projection. */
    static String restore(String original, String projected) throws IOException {
        if (java.util.Objects.equals(original, projected)) return projected;
        return restore(HologramBlueprintNbt.decode(original), projected);
    }

    static String restore(NBTTagCompound before, String projected) throws IOException {
        Set<String> roots = roots(before);
        if (roots.isEmpty()) return projected;
        NBTTagCompound after = HologramBlueprintNbt.decode(projected);
        if (after == null) throw new IOException("完全保留的数据被其他处理选项移除");
        boolean changed = false;
        for (String root : roots) changed |= !before.getTag(root)
            .equals(after.getTag(root));
        if (!changed) return projected;
        for (String root : roots) after.setTag(
            root,
            before.getTag(root)
                .copy());
        after.setTag(
            TAG,
            before.getTag(TAG)
                .copy());
        return HologramBlueprintNbt.encodeCanonical(after);
    }

    static String root(String scopedPath) throws IOException {
        int colon = scopedPath.indexOf(':');
        String pointer = colon < 0 ? "" : scopedPath.substring(colon + 1);
        if (!pointer.startsWith("/") || pointer.length() == 1
            || pointer.substring(1)
                .contains("/"))
            throw new IOException("完全保留须选择完整资源根路径：" + scopedPath);
        String encoded = pointer.substring(1);
        if (encoded.matches(".*~(?![01]).*")) throw new IOException("完全保留路径转义无效");
        return encoded.replace("~1", "/")
            .replace("~0", "~");
    }

    private static String escape(String root) {
        return root.replace("~", "~0")
            .replace("/", "~1");
    }
}
