package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.miaokatzecore.api.io.AtomicFilePublisher;

/** A suspended cell owns both world and payment evidence. Only Transaction may settle its payment. */
final class HologramConstructionJournal {

    static final String KEY = "gtmbConstructionRecovery";
    private static final Set<EntityPlayer> LOADED = Collections.newSetFromMap(new WeakHashMap<EntityPlayer, Boolean>());
    // Package-private storage seam for isolated contract fixtures; production always uses the world's save root.
    static final ThreadLocal<Path> STORAGE_ROOT = new ThreadLocal<>();
    static long publications, publishNanos;

    private HologramConstructionJournal() {}

    static NBTTagCompound owner(EntityPlayer player) {
        NBTTagCompound data = player.getEntityData();
        if (!data.hasKey(EntityPlayer.PERSISTED_NBT_TAG, 10))
            data.setTag(EntityPlayer.PERSISTED_NBT_TAG, new NBTTagCompound());
        return data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
    }

    static boolean pending(EntityPlayer player) {
        if (!LOADED.contains(player)) {
            try {
                Path file = path(player);
                if (Files.exists(file)) {
                    if (Files.isSymbolicLink(file) || Files.size(file) > 32L * 1024 * 1024)
                        throw new IOException("Invalid construction recovery file");
                    NBTTagCompound saved = HologramSnapshotCodec.decode(Files.readAllBytes(file));
                    if (!player.getUniqueID()
                        .toString()
                        .equals(saved.getString("player")))
                        throw new IOException("Construction recovery owner mismatch");
                    if ("settled".equals(saved.getString("phase"))) owner(player).removeTag(KEY);
                    else owner(player).setTag(KEY, saved);
                }
                LOADED.add(player);
            } catch (IOException | RuntimeException failure) {
                NBTTagCompound blocked = new NBTTagCompound();
                blocked.setString("phase", "unreadable");
                blocked.setString("failure", failure.toString());
                owner(player).setTag(KEY, blocked);
                return true;
            }
        }
        return owner(player).hasKey(KEY);
    }

    private static Path path(EntityPlayer player) throws IOException {
        Path root = STORAGE_ROOT.get() == null ? player.worldObj.getSaveHandler()
            .getWorldDirectory()
            .toPath()
            .toRealPath()
            : STORAGE_ROOT.get()
                .toRealPath();
        Path directory = root.resolve("gtmb-construction-recovery");
        if (Files.isSymbolicLink(directory)) throw new IOException("Recovery directory may not be a symbolic link");
        Files.createDirectories(directory);
        return directory.resolve(
            player.getUniqueID()
                .toString() + ".nbt");
    }

    static void persist(EntityPlayer player, NBTTagCompound journal) {
        try {
            Path file = path(player);
            if (Files.exists(file)) {
                if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > 32L * 1024 * 1024)
                    throw new IOException("Invalid existing recovery file; no evidence overwritten");
                NBTTagCompound current = HologramSnapshotCodec.decode(Files.readAllBytes(file));
                if (!player.getUniqueID()
                    .toString()
                    .equals(current.getString("player"))
                    || (!journal.getString("id")
                        .equals(current.getString("id")) && !"settled".equals(current.getString("phase")))
                    || (journal.getString("id")
                        .equals(current.getString("id")) && journal.getLong("revision") != current.getLong("revision")))
                    throw new IOException("Construction recovery identity/revision conflict; no evidence overwritten");
            }
            journal.setBoolean("durable", true);
            NBTTagCompound published = (NBTTagCompound) journal.copy();
            published.setLong("revision", journal.getLong("revision") + 1);
            byte[] bytes = HologramSnapshotCodec.compress(published);
            if (bytes.length > 32L * 1024 * 1024) throw new IOException("Construction journal exceeds byte budget");
            long started = System.nanoTime();
            AtomicFilePublisher.publishAtomic(file, bytes);
            journal.setLong("revision", published.getLong("revision"));
            publishNanos += System.nanoTime() - started;
            publications++;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist construction recovery; external mutation blocked", failure);
        }
    }

    static void reload(EntityPlayer player) {
        LOADED.remove(player);
        pending(player);
    }

    static NBTTagCompound begin(EntityPlayer player, int x, int y, int z, Block block, int meta, NBTTagCompound tile,
        ItemStack[] inventory) {
        if (pending(player)) throw new IllegalStateException("存在待核定施工凭据，施工已暂停；请联系管理员 /gtmb recovery");
        NBTTagCompound journal = new NBTTagCompound();
        journal.setInteger("version", 1);
        journal.setString(
            "id",
            UUID.randomUUID()
                .toString());
        journal.setString(
            "player",
            player.getUniqueID()
                .toString());
        journal.setInteger("dimension", player.worldObj.provider.dimensionId);
        journal.setInteger("x", x);
        journal.setInteger("y", y);
        journal.setInteger("z", z);
        journal.setString("block", String.valueOf(Block.blockRegistry.getNameForObject(block)));
        journal.setInteger("meta", meta);
        if (tile != null) journal.setTag("tile", tile.copy());
        journal.setTag("beforeInventory", inventory(inventory));
        journal.setString("phase", "reserved");
        owner(player).setTag(KEY, journal);
        return journal;
    }

    static NBTTagList inventory(ItemStack[] inventory) {
        NBTTagList result = new NBTTagList();
        for (int i = 0; i < inventory.length; i++) {
            NBTTagCompound slot = new NBTTagCompound();
            slot.setInteger("slot", i);
            if (inventory[i] != null) slot.setTag("stack", HologramMaterials.itemReceipt(inventory[i]));
            result.appendTag(slot);
        }
        return result;
    }

    static ItemStack[] inventory(NBTTagList encoded) {
        ItemStack[] result = new ItemStack[encoded.tagCount()];
        for (int i = 0; i < result.length; i++) {
            NBTTagCompound slot = encoded.getCompoundTagAt(i);
            if (slot.getInteger("slot") != i) throw new IllegalStateException("无效库存凭据");
            result[i] = slot.hasKey("stack", 10) ? HologramMaterials.recoveryItem(slot.getCompoundTag("stack")) : null;
            if (slot.hasKey("stack", 10) && result[i] == null) throw new IllegalStateException("库存凭据中的原物品身份已无法解析，保留原件");
        }
        return result;
    }

    static void suspend(HologramSession session, HologramMaterials.Transaction transaction, Throwable failure) {
        transaction.snapshot();
        NBTTagCompound journal = owner(session.player).getCompoundTag(KEY);
        journal.setString("phase", "suspended");
        journal.setString(
            "failure",
            failure.getClass()
                .getName() + ": "
                + String.valueOf(failure.getMessage()));
        journal.setTag("heldInventory", inventory(session.player.inventory.mainInventory));
        NBTTagCompound recovery = new NBTTagCompound();
        for (Object key : owner(session.player).func_150296_c()) {
            String name = String.valueOf(key);
            if (name.startsWith("gtmbHologram")) recovery.setTag(
                name,
                owner(session.player).getTag(name)
                    .copy());
        }
        journal.setTag("recoveryAtSuspend", recovery);
        session.job = 4;
        session.stopTick = true;
        session.defer = false;
        session.phase = "STOPPED";
        session.status = "施工结果待核定，已暂停并保留世界/付款凭据 " + journal.getString("id") + "；请联系管理员 /gtmb recovery";
        persist(session.player, journal);
    }

    static void clear(EntityPlayer player) {
        NBTTagCompound journal = (NBTTagCompound) owner(player).getCompoundTag(KEY)
            .copy();
        journal.setString("phase", "settled");
        journal.setString(
            "player",
            player.getUniqueID()
                .toString());
        if (journal.getBoolean("durable")) persist(player, journal);
        owner(player).removeTag(KEY);
    }

    /** Explicit OP4 adjudication. No replay of unknown external calls; inventory must still match the frozen state. */
    static String resolve(EntityPlayerMP player, String id, String action) {
        boolean pending = pending(player);
        NBTTagCompound journal = owner(player).getCompoundTag(KEY);
        if (!pending || journal.getInteger("version") != 1
            || !id.equals(journal.getString("id"))
            || !player.getUniqueID()
                .toString()
                .equals(journal.getString("player")))
            throw new IllegalStateException("凭据版本、玩家或编号不匹配");
        if ("accept".equals(action)) {
            // The administrator accepts the live world and all existing deductions; no resource is issued.
            journal.setString("resolution", "accept-live-world-and-deductions");
            clear(player);
            return "已核定保留现场与已扣材料；未退款，请重新规划";
        }
        if (!"rollback".equals(action)) throw new IllegalStateException("核定操作仅支持 accept / rollback");
        if (journal.hasKey("operation") || journal.getBoolean("settlementStarted")
            || journal.getBoolean("commitStarted"))
            throw new IllegalStateException("原生结果未知或补偿已开始，禁止重放；先核对 operation 并使用 extraction 核定实际量，或人工处理后 accept");
        if (player.worldObj.provider.dimensionId != journal.getInteger("dimension")
            || !player.worldObj.blockExists(journal.getInteger("x"), journal.getInteger("y"), journal.getInteger("z")))
            throw new IllegalStateException("请进入凭据原维度并加载原区块");
        if (!inventory(player.inventory.mainInventory).equals(journal.getTagList("heldInventory", 10)))
            throw new IllegalStateException("库存已变化；请恢复暂停时完整库存后再核定，禁止覆盖当前物品");
        Object block = Block.blockRegistry.getObject(journal.getString("block"));
        if (!(block instanceof Block)) throw new IllegalStateException("原方块注册已不存在，保留凭据");
        HologramRecovery.restore(
            player.worldObj,
            journal.getInteger("x"),
            journal.getInteger("y"),
            journal.getInteger("z"),
            (Block) block,
            journal.getInteger("meta"),
            journal.hasKey("tile", 10) ? journal.getCompoundTag("tile") : null);
        HologramMaterials.Transaction transaction = HologramMaterials.Transaction.resume(player, journal);
        journal.setTag(
            "settlementPayment",
            journal.getCompoundTag("payment")
                .copy());
        journal.setBoolean("settlementStarted", true);
        persist(player, journal);
        transaction.rollbackExternal();
        ItemStack[] before = inventory(journal.getTagList("beforeInventory", 10));
        if (before.length != player.inventory.mainInventory.length) throw new IllegalStateException("库存长度不匹配");
        for (int i = 0; i < before.length; i++) player.inventory.mainInventory[i] = before[i];
        transaction.afterInventoryRestore();
        player.inventory.markDirty();
        journal.setString("resolution", "rollback-confirmed-world-and-payment");
        clear(player);
        return "原世界已恢复，确定付款已补偿；未交付余量保留既有恢复队列";
    }
}
