package com.miaokatze.gtmb.testlab;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentText;

/** No dimension/coordinates/config-enable arguments are accepted from commands. */
final class TestLabCommand extends CommandBase {

    private final TestLabRuntime runtime;

    TestLabCommand(TestLabRuntime runtime) {
        this.runtime = runtime;
    }

    public String getCommandName() {
        return "gtmbtestlab";
    }

    public String getCommandUsage(ICommandSender sender) {
        return "/gtmbtestlab status|reset|refresh|run minimal|run stress|run composite|stop|report";
    }

    public int getRequiredPermissionLevel() {
        return 4;
    }

    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return TestLab.isEnabled() && sender.canCommandSenderUseCommand(4, getCommandName());
    }

    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender))
            throw new CommandException("Local testlab config and operator level 4 required");
        if (args.length == 0 || args.length > 2) throw new WrongUsageException(getCommandUsage(sender));
        try {
            if (args.length == 1 && "status".equals(args[0])) {
                NBTTagCompound n = TestLab.status();
                say(
                    sender,
                    n.getString("state") + " enabled="
                        + n.getBoolean("enabled")
                        + " dim="
                        + n.getInteger("dimensionId")
                        + " world="
                        + n.getBoolean("worldLoaded")
                        + " ownedChunks="
                        + n.getInteger("ownedChunks")
                        + " run="
                        + n.getString("runId"));
            } else if (args.length == 1 && "stop".equals(args[0])) {
                runtime.stop("Operator cancelled");
                say(sender, "Lab run cancelled; owned tickets released");
            } else if (args.length == 1 && "report".equals(args[0])) {
                runtime.persist();
                say(sender, "Report: <save>/gtmb-testlab/latest-report.json and latest-report.nbt");
            } else {
                String kind = args.length == 1 && ("reset".equals(args[0]) || "refresh".equals(args[0])) ? args[0]
                    : args.length == 2 && "run".equals(args[0])
                        && ("minimal".equals(args[1]) || "stress".equals(args[1]) || "composite".equals(args[1]))
                            ? args[1]
                            : "";
                if (kind.isEmpty()) throw new WrongUsageException(getCommandUsage(sender));
                runtime.start(kind);
                say(
                    sender,
                    "Started bounded lab " + kind
                        + ": "
                        + runtime.status()
                            .getString("runId"));
            }
        } catch (CommandException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new CommandException("Lab refused: " + failure.getMessage());
        }
    }

    private static void say(ICommandSender sender, String value) {
        sender.addChatMessage(new ChatComponentText("[GTMB testlab] " + value));
    }
}
