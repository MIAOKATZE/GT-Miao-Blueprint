package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

import com.miaokatze.gtmb.util.ServerTaskScheduler;

/** Administrator runtime reset and explicit construction-journal adjudication; no whole-server restart. */
public final class HologramCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "gtmb";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtmb restart | /gtmb voucher | /gtmb recovery <player> [status|rollback|accept|extraction] [receipt-id] [actual-amount]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender) || args.length != 1) return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, "restart", "voucher", "recovery");
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender)) throw new CommandException("需要管理员权限（等级 4）");
        if (args.length >= 2 && "recovery".equals(args[0])) {
            net.minecraft.entity.player.EntityPlayerMP player = getPlayer(sender, args[1]);
            HologramConstructionJournal.pending(player);
            net.minecraft.nbt.NBTTagCompound journal = HologramConstructionJournal.owner(player)
                .getCompoundTag(HologramConstructionJournal.KEY);
            try {
                String action = args.length >= 3 ? args[2] : "status";
                if ("status".equals(action)) {
                    sender.addChatMessage(
                        new ChatComponentText(
                            !HologramConstructionJournal.pending(player) ? "没有待核定施工凭据"
                                : "施工凭据 " + journal.getString("id")
                                    + " phase="
                                    + journal.getString("phase")
                                    + " dim="
                                    + journal.getInteger("dimension")
                                    + " @"
                                    + journal.getInteger("x")
                                    + ","
                                    + journal.getInteger("y")
                                    + ","
                                    + journal.getInteger("z")
                                    + " operation="
                                    + journal.getCompoundTag("operation")
                                        .getString("kind")
                                    + " requested="
                                    + journal.getCompoundTag("operation")
                                        .getLong("requested")
                                    + " revision="
                                    + journal.getLong("revision")
                                    + " settlementStarted="
                                    + journal.getBoolean("settlementStarted")));
                } else {
                    if (args.length < 4 || !args[3].equals(journal.getString("id"))
                        || journal.getInteger("version") != 1)
                        throw new IllegalStateException("请使用 status 返回的完整凭据编号；不支持的版本保留原件");
                    String result;
                    if ("extraction".equals(action)) {
                        if (args.length != 5) throw new IllegalStateException("extraction 必须提供已核查的实际抽取数量（long）");
                        HologramMaterials.Transaction.adjudicateExtraction(journal, Long.parseLong(args[4]));
                        HologramConstructionJournal.persist(player, journal);
                        result = "已登记实际抽取量，未交付或重放；可继续 rollback 或 accept 核定";
                    } else result = HologramConstructionJournal.resolve(player, args[3], action);
                    sender.addChatMessage(new ChatComponentText(result));
                }
            } catch (RuntimeException failure) {
                throw new CommandException("恢复核定未完成：" + failure.getMessage());
            }
            return;
        }
        if (args.length == 1 && "voucher".equals(args[0])) {
            HologramVoucherEditor.open(getCommandSenderAsPlayer(sender));
            return;
        }
        if (args.length != 1 || !"restart".equals(args[0])) throw new WrongUsageException(getCommandUsage(sender));
        String reason = "GTMB 服务已安全重启，未执行任务已取消；已施工方块、回收暂存和剪切记录保留，可重新打开投影仪";
        // Commands and world transactions share the server thread. Cells are committed/refunded or
        // durably suspended before this boundary; resetting runtime queues preserves recovery journals.
        ServerTaskScheduler.clear();
        HologramLibraryFacade.restartRuntime();
        HologramAeCraftingOrders.clearAll(reason);
        HologramCutService.clear();
        int sessions = HologramWorkspace.restartRuntime(reason);
        sender.addChatMessage(new ChatComponentText(reason + "（已关闭会话 " + sessions + "）"));
    }
}
