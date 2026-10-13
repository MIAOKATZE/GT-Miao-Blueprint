package com.miaokatze.gtmb.hologram;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChatComponentText;

import com.miaokatze.gtmb.util.ServerTaskScheduler;

/** /gtmblibrary [set uploadKiB downloadKiB quota], using the same persistent revision as the GUI. */
public final class HologramLibraryCommand extends CommandBase {

    public String getCommandName() {
        return "gtmblibrary";
    }

    public String getCommandUsage(ICommandSender sender) {
        return "/gtmblibrary [set <uploadKiB> <downloadKiB> <quota>]";
    }

    public int getRequiredPermissionLevel() {
        return 4;
    }

    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return HologramLibraryFacade.enabled()
            && (sender instanceof EntityPlayerMP ? HologramLibraryFacade.isAdmin((EntityPlayerMP) sender)
                : sender.canCommandSenderUseCommand(4, getCommandName()));
    }

    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender))
            throw new CommandException("Operator level 4 on a dedicated server required");
        HologramServerLibrary backend = HologramLibraryFacade.backend();
        if (backend != null && !backend.available()) throw new CommandException(backend.unavailableMessage());
        HologramLibraryConfig.View view = backend == null ? null : backend.config();
        if (view == null) throw new CommandException("Server blueprint library is still loading");
        if (args.length == 0) {
            sender.addChatMessage(new ChatComponentText(describe(view)));
            return;
        }
        if (args.length != 4 || !"set".equals(args[0])) throw new WrongUsageException(getCommandUsage(sender));
        int up = parseIntBounded(sender, args[1], 1, 1024);
        int down = parseIntBounded(sender, args[2], 1, 1024);
        int max = parseIntBounded(sender, args[3], 1, 1000);
        // Console is level 4; a player permission is checked again on the server thread before queuing.
        if (sender instanceof EntityPlayerMP && !HologramLibraryFacade.isAdmin((EntityPlayerMP) sender))
            throw new CommandException("Operator level 4 required");
        if (!backend.enqueue(() -> {
            try {
                backend.changeConfig(true, view.revision, up, down, max);
                respond(sender, describe(backend.config()));
            } catch (Exception failure) {
                respond(sender, failure.getMessage());
            }
        })) throw new CommandException("Server library worker is busy; retry");
    }

    private static String describe(HologramLibraryConfig.View view) {
        return "Server blueprint library: global upload " + view.uploadKiB
            + " KiB/s, download "
            + view.downloadKiB
            + " KiB/s, per-player quota "
            + view.maxUploads
            + ", revision "
            + view.revision;
    }

    private static void respond(ICommandSender sender, String message) {
        ServerTaskScheduler.scheduleServerTask(() -> sender.addChatMessage(new ChatComponentText(message)));
    }
}
