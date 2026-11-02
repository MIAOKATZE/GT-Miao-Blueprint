package com.miaokatze.gtmb.hologram;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Atomic write-ahead record, scoped to this save and the authenticated player UUID. */
final class HologramCutStorage {

    private final UUID owner;
    private final Path file;

    HologramCutStorage(Path root, UUID owner) {
        this.owner = owner;
        file = root.resolve("gtmb-cut")
            .resolve(owner.toString() + ".nbt");
    }

    /** Only the server writes this receipt, after admission or before a reversible placement check. */
    void markPending(NBTTagCompound pending, String action, String requestId, boolean approved) {
        NBTTagCompound authorization = new NBTTagCompound();
        authorization.setInteger("version", 1);
        authorization.setString("actor", owner.toString());
        authorization.setString("action", action);
        authorization.setString("requestId", requestId);
        authorization.setString("phase", approved ? "approved" : "rollback");
        pending.setTag("authorization", authorization);
    }

    /** Legacy pending records have no evidence of admission and must never become automatic redo transactions. */
    boolean approvedPending(NBTTagCompound pending) throws IOException {
        if (!pending.hasKey("authorization", 10)) throw new IOException("旧剪切日志缺少权限记录，已保留原记录并阻止自动恢复；请管理员核对存档");
        NBTTagCompound authorization = pending.getCompoundTag("authorization");
        if (!authorization.hasKey("version", 3) || authorization.getInteger("version") != 1
            || !owner.toString()
                .equals(authorization.getString("actor")))
            throw new IOException("剪切恢复权限记录与存档所有者不匹配，原记录已保留");
        String action = authorization.getString("action"), phase = authorization.getString("phase");
        if (!("cut".equals(action) || "paste".equals(action) || "undo".equals(action))
            || !("approved".equals(phase) || "rollback".equals(phase))) throw new IOException("剪切恢复权限记录无效，原记录已保留");
        String requestId = authorization.getString("requestId");
        try {
            if (!UUID.fromString(requestId)
                .toString()
                .equals(requestId)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new IOException("剪切恢复请求编号无效，原记录已保留", invalid);
        }
        boolean approved = "approved".equals(phase);
        boolean cut = "cut".equals(action);
        if (pending.getBoolean("air") != (approved && cut)
            || !approved && (cut || pending.hasKey("consumeId") || !pending.hasKey("candidate", 10))
            || approved && !cut && !pending.hasKey("before", 10)
            || approved && cut
                && (!requestId.equals(
                    pending.getCompoundTag("entry")
                        .getString("id"))
                    || pending.hasKey("consumeId"))
            || approved && !cut
                && !pending.getCompoundTag("entry")
                    .getString("id")
                    .equals(pending.getString("consumeId")))
            throw new IOException("剪切恢复操作与权限记录不一致，原记录已保留");
        return approved;
    }

    NBTTagCompound load() throws IOException {
        if (!Files.exists(file)) {
            NBTTagCompound result = new NBTTagCompound();
            result.setInteger("version", HologramCutProtocol.VERSION);
            result.setTag("entries", new NBTTagList());
            result.setTag("requests", new NBTTagList());
            return result;
        }
        long length = Files.size(file);
        if (length <= 0 || length > HologramSnapshotAssembly.MAX_BYTES) throw new IOException("剪切存储大小无效");
        NBTTagCompound result = HologramSnapshotCodec.decode(Files.readAllBytes(file));
        if (result.getInteger("version") != HologramCutProtocol.VERSION) throw new IOException("剪切存储版本无效");
        return result;
    }

    void save(NBTTagCompound data) throws IOException {
        byte[] bytes = HologramSnapshotCodec.compress(data);
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
            output.write(bytes);
            output.getFD()
                .sync();
        }
        // No non-atomic fallback: failure leaves the previous authoritative record intact.
        Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
