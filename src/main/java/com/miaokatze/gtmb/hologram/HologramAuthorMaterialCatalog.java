package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import gregtech.common.blocks.ItemMachines;

/** Explicit real-inventory read boundary. No author session, recovery flush, extraction, debit or world edit. */
public final class HologramAuthorMaterialCatalog {

    public static final String OPERATION = "requestAuthorMaterialCatalog";
    public static final String SETTINGS_OPERATION = "configureAuthorMaterialSources";
    private static final Map<EntityPlayerMP, Long> LAST_QUERY = new WeakHashMap<>();

    private HologramAuthorMaterialCatalog() {}

    public static String toolFingerprint(ItemStack tool) throws IOException {
        if (tool == null || tool.getItem() == null) throw new IOException("请手持投影工具");
        NBTTagCompound encoded = tool.writeToNBT(new NBTTagCompound());
        // Registry identity is stable across endpoint numeric registry assignments. All private NBT stays hashed.
        encoded.removeTag("id");
        encoded.setString("registry", String.valueOf(Item.itemRegistry.getNameForObject(tool.getItem())));
        HologramAeProtocol.bytes(encoded, 256 * 1024);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(
                    HologramBlueprintNbt.encodeCanonical(encoded)
                        .getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    static void query(EntityPlayerMP player, NBTTagCompound request) {
        NBTTagCompound result = correlation(request);
        result.setBoolean("success", false);
        try {
            validateRequest(request);
            if (!HologramLibraryService.connectedProjector(player) || !player.getUniqueID()
                .toString()
                .equals(request.getString("player"))
                || player.inventory.currentItem != request.getInteger("toolSlot")
                || !toolFingerprint(player.getHeldItem()).equals(request.getString("toolFingerprint")))
                throw new IOException("当前连接、玩家或手持投影工具已变化");
            long now = System.nanoTime();
            Long last = LAST_QUERY.get(player);
            if (last != null && now - last < 250_000_000L) throw new IOException("目录刷新过快，请稍后重试");
            LAST_QUERY.put(player, now);
            HologramMaterials.Settings settings = HologramMaterials.read(player.getHeldItem());
            if (SETTINGS_OPERATION.equals(request.getString("op"))) {
                applySettings(settings, request);
                HologramMaterials.write(player.getHeldItem(), settings);
                player.inventory.markDirty();
                if (player.inventoryContainer != null) player.inventoryContainer.detectAndSendChanges();
                result.setBoolean("settingsApplied", true);
            }
            result.setString("actualToolFingerprint", toolFingerprint(player.getHeldItem()));
            result.setBoolean("materialMain", settings.main);
            result.setBoolean("materialContainers", settings.containers);
            result.setBoolean("materialMe", settings.me);
            result.setInteger("materialPriority", settings.priority);
            // Context constructor inspects adapter source copies; catalog uses simulation only.
            List<ItemStack> samples = new HologramMaterials.Context(player, settings, player.inventory.currentItem)
                .catalog(96);
            result.setTag("materialChoices", publicSamples(samples));
            result.setBoolean("success", true);
            result.setString("status", "已读取最多 96 种公开物品样本；不含库存数量、私密 NBT 或施工授权");
        } catch (IOException | RuntimeException | LinkageError failure) {
            // Native provider exceptions can contain private network identifiers. Never forward their text.
            result.setString(
                "status",
                result.getBoolean("settingsApplied") ? "工具材料设置已保存，但样本读取失败；请检查来源权限或稍后刷新"
                    : "材料样本读取被拒绝；请检查连接、手持工具、来源权限或稍后刷新");
        }
        HologramNetwork.sendAuthorMaterialCatalog(player, result);
    }

    static void validateRequest(NBTTagCompound request) throws IOException {
        if (request == null || !request.getString("token")
            .matches("[0-9a-f-]{36}")
            || request.getString("document")
                .isEmpty()
            || request.getString("document")
                .length() > 128
            || !request.hasKey("revision", 4)
            || request.getLong("revision") < 0
            || !request.getString("player")
                .matches("[0-9a-f-]{36}")
            || !request.getString("toolFingerprint")
                .matches("[0-9a-f]{64}")
            || !request.hasKey("toolSlot", 3)
            || request.getInteger("toolSlot") < 0
            || request.getInteger("toolSlot") > 8) throw new IOException("材料样本请求标识无效");
        HologramAeProtocol.bytes(request, 4096);
    }

    static void applySettings(HologramMaterials.Settings settings, NBTTagCompound request) throws IOException {
        for (String key : new String[] { "materialMain", "materialContainers", "materialMe" })
            if (!request.hasKey(key, 1)) throw new IOException("材料来源设置缺少明确开关");
        if (!request.hasKey("materialPriority", 3) || request.getInteger("materialPriority") < 0
            || request.getInteger("materialPriority") > 2) throw new IOException("材料来源顺序无效");
        settings.main = request.getBoolean("materialMain");
        settings.containers = request.getBoolean("materialContainers");
        settings.me = request.getBoolean("materialMe");
        settings.priority = request.getInteger("materialPriority");
    }

    static NBTTagCompound correlation(NBTTagCompound request) {
        NBTTagCompound result = new NBTTagCompound();
        for (String key : new String[] { "token", "document", "player", "toolFingerprint" }) {
            String value = request.getString(key);
            result.setString(key, value.length() <= 128 ? value : "");
        }
        result.setLong("revision", request.getLong("revision"));
        result.setInteger("toolSlot", request.getInteger("toolSlot"));
        return result;
    }

    /** Plain type/subtype only: never copy seals, inventory payloads, terminal keys, owner fields or paid assets. */
    static NBTTagList publicSamples(List<ItemStack> samples) {
        NBTTagList result = new NBTTagList();
        Set<String> seen = new HashSet<>();
        for (ItemStack source : samples) {
            if (result.tagCount() >= 96) break;
            if (source == null || source.stackSize < 1
                || source.getItemDamage() < 0
                || !(source.getItem() instanceof ItemBlock || source.getItem() instanceof ItemMachines)
                || HologramRecovery.hasSeal(source)) continue;
            String item = String.valueOf(Item.itemRegistry.getNameForObject(source.getItem()));
            if (!seen.add(item + ":" + source.getItemDamage())) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setString("item", item);
            row.setInteger("damage", source.getItemDamage());
            result.appendTag(row);
        }
        return result;
    }
}
