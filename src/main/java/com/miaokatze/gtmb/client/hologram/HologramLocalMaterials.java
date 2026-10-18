package com.miaokatze.gtmb.client.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramStaticMaterials;

/** Cooperative game-thread estimation; native providers never run on the library IO executor. */
final class HologramLocalMaterials {

    private final HologramLocalPlacement placement;
    private final HologramState source;
    private final HologramStaticMaterials.Builder builder = new HologramStaticMaterials.Builder();
    private final Thread owner = Thread.currentThread();
    private final long started = System.nanoTime();
    private int cursor;
    private NBTTagCompound result;

    HologramLocalMaterials(HologramLocalPlacement placement) {
        this.placement = placement;
        source = placement.display();
        NBTTagCompound cached = placement.materialSummary();
        if (placement.artifact() != null && cached != null
            && cached.hasKey("localEstimateCache", 10)
            && !cached.getBoolean("loading")
            && cached.getInteger("version") == 1
            && cached.hasKey("rows", 9)) {
            NBTTagCompound key = cached.getCompoundTag("localEstimateCache");
            if (key.getString("hash")
                .equals(placement.hash())
                && key.getString("revision")
                    .equals(placement.revision())
                && key.getCompoundTag("policy")
                    .equals(policy()))
                result = (NBTTagCompound) cached.copy();
        }
    }

    boolean step() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Local material thread changed");
        if (result != null) return true;
        if (placement.artifact() == null) {
            NBTTagCompound publicSummary = placement.materialSummary();
            if (publicSummary != null && !publicSummary.getBoolean("loading")) {
                result = (NBTTagCompound) publicSummary.copy();
                return true;
            }
            if (System.nanoTime() - started > 15_000_000_000L) {
                builder.unaccounted("尚未取得完整公开材料摘要；开始建设后核对实际清单");
                result = builder.snapshot();
                return true;
            }
            return false;
        }
        long deadline = System.nanoTime() + 2_000_000L;
        int work = 0;
        while (cursor < source.cells.size() && work++ < 64 && System.nanoTime() < deadline) {
            HologramState.Cell cell = source.cells.get(cursor++);
            if (cell.excluded) continue;
            try {
                builder.add(
                    cell.block(false),
                    cell.wantMeta,
                    cell.targetStack,
                    cell.localMaterialTile,
                    cell.targetStack == null ? null : cell.targetStack.getTagCompound());
            } catch (IOException | RuntimeException | LinkageError unavailable) {
                builder.unaccounted("格 #" + (cell.index + 1) + "：" + unavailable.getMessage());
            }
        }
        if (cursor == source.cells.size()) {
            result = builder.snapshot();
            // Nondefault source retention needs the final selected-payload projection. Never claim the full
            // retained-source estimate is an authoritative bill for a different NBT/cover selection.
            if (!source.data.getBoolean("localMaterialPolicyPresent") || !source.data.getBoolean("retainNbt")
                || !source.data.getBoolean("retainCovers")
                || source.data.hasKey("retainedNbtKeys", 9)) {
                result.setBoolean("complete", false);
                result.setString("detail", "本图使用自定义 NBT/覆盖板保留选项；最终材料需再核对");
            }
            // Keep both complete and explicitly partial estimates for this immutable owned source. Pose and
            // anchor do not change this base-source estimate; the final server quote still owns all payment.
            if (current()) {
                NBTTagCompound key = new NBTTagCompound();
                key.setString("hash", placement.hash());
                key.setString("revision", placement.revision());
                key.setTag("policy", policy());
                result.setTag("localEstimateCache", key);
                placement.materialSummary((NBTTagCompound) result.copy());
            }
        }
        return result != null;
    }

    NBTTagCompound result() {
        return result;
    }

    String progress() {
        return placement.artifact() == null ? "正在获取公开材料摘要" : cursor + " / " + source.cells.size() + " 格";
    }

    boolean current() {
        return placement.active() && placement.display() == source;
    }

    private NBTTagCompound policy() {
        NBTTagCompound policy = new NBTTagCompound();
        for (String key : new String[] { "localMaterialPolicyPresent", "retainNbt", "retainCovers", "retainedNbtKeys" })
            if (source.data.hasKey(key)) policy.setTag(
                key,
                source.data.getTag(key)
                    .copy());
        return policy;
    }
}
