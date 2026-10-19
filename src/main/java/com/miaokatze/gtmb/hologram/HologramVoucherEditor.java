package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

/** Admin-only configuration lane, independent of a held projector or workspace. */
public final class HologramVoucherEditor {

    private HologramVoucherEditor() {}

    public static void open(EntityPlayerMP player) {
        handle(player, new NBTTagCompound());
    }

    static void handle(EntityPlayerMP player, NBTTagCompound request) {
        if (!player.canCommandSenderUseCommand(4, "gtmb")) return;
        String status = "服务器配置 · 黑名单优先 · 只抵扣放置的方块";
        if (request.getBoolean("save")) {
            try {
                DecorationVoucherConfig.View value = request.getBoolean("defaults") ? DecorationVoucherConfig.defaults()
                    : new DecorationVoucherConfig.View(
                        request.getBoolean("enabled"),
                        rows(request, "allowedModIds"),
                        rows(request, "allowedBlocks"),
                        rows(request, "allowedOres"),
                        rows(request, "deniedModIds"),
                        rows(request, "deniedBlocks"),
                        rows(request, "deniedOres"));
                status = DecorationVoucherConfig.save(value, request.getLong("revision")) ? "已保存并立即生效"
                    : "其他管理员已修改配置；已载入最新列表，请重新编辑";
                // Retire cached quotations; the next review/transaction must use these server rules.
                HologramWorkspace.forEachConnected(session -> {
                    session.materialQuoteCache = null;
                    session.forcePlacementReady = false;
                    session.placementReviewIssued = false;
                });
            } catch (RuntimeException failure) {
                status = "配置保存失败：" + failure.getMessage();
            }
        }
        NBTTagCompound reply = DecorationVoucherConfig.snapshot();
        reply.setBoolean("voucherEditor", true);
        reply.setString("status", status);
        NBTTagList mods = new NBTTagList();
        for (ModContainer mod : Loader.instance()
            .getModList()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", mod.getModId());
            row.setString("name", mod.getName());
            row.setString("version", mod.getVersion());
            row.setString(
                "description",
                mod.getMetadata() == null || mod.getMetadata().description == null ? ""
                    : mod.getMetadata().description);
            mods.appendTag(row);
        }
        reply.setTag("mods", mods);
        HologramNetwork.sendState(player, reply);
    }

    private static List<String> rows(NBTTagCompound request, String key) {
        NBTTagList list = request.getTagList(key, 8);
        if (list.tagCount() > 4096) throw new IllegalArgumentException("规则数量超过上限");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < list.tagCount(); i++) {
            String text = list.getStringTagAt(i);
            if (text.length() > 192 || !text.matches("[A-Za-z0-9_.:|@*+/-]+"))
                throw new IllegalArgumentException("无效规则：" + key);
            result.add(text);
        }
        return result;
    }
}
