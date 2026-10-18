package com.miaokatze.gtmb.hologram;

import java.util.Collections;
import java.util.List;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.util.ChatComponentText;

import com.miaokatze.gtmb.util.ServerTaskScheduler;

/** Administrator runtime recovery; no world rollback, clipboard deletion or whole-server restart. */
public final class HologramCommand extends CommandBase {

    @Override
    public String getCommandName() {
        return "gtmb";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/gtmb restart";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 4;
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender) || args.length != 1) return Collections.emptyList();
        return getListOfStringsMatchingLastWord(args, "restart");
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender)) throw new CommandException("需要管理员权限（等级 4）");
        if (args.length != 1 || !"restart".equals(args[0])) throw new WrongUsageException(getCommandUsage(sender));
        String reason = "GTMB 服务已安全重启，未执行任务已取消；已施工方块、回收暂存和剪切记录保留，可重新打开投影仪";
        // Commands and world transactions share the server thread. A cell is fully committed/refunded
        // before this boundary, so resetting queues cannot strand a half-reserved payment.
        ServerTaskScheduler.clear();
        HologramLibraryFacade.restartRuntime();
        HologramAeCraftingOrders.clearAll(reason);
        HologramCutService.clear();
        int sessions = HologramWorkspace.restartRuntime(reason);
        sender.addChatMessage(new ChatComponentText(reason + "（已关闭会话 " + sessions + "）"));
    }
}
