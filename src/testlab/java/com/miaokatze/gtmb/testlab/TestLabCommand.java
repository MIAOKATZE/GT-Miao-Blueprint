// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

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
        return "/gtmbtestlab status|reset|refresh|run minimal|run stress|run composite|run native-build|run native-test|run recovery-stage|run recovery-resume|run construction-performance|stop|report";
    }

    public int getRequiredPermissionLevel() {
        return 2;
    }

    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return TestLab.isEnabled() && sender.canCommandSenderUseCommand(2, getCommandName());
    }

    public void processCommand(ICommandSender sender, String[] args) {
        if (!canCommandSenderUseCommand(sender))
            throw new CommandException("Local testlab config and operator level 2 required");
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
                say(
                    sender,
                    runtime.status()
                        .getBoolean("cleanupPending")
                            ? "Lab stopped; cleanup remains pending; owned resources are retained for retry"
                            : "Lab run cancelled; owned tickets released");
            } else if (args.length == 1 && "report".equals(args[0])) {
                runtime.persist();
                say(sender, "Report: <save>/gtmb-testlab/latest-report.json and latest-report.nbt");
            } else {
                String kind = args.length == 1 && ("reset".equals(args[0]) || "refresh".equals(args[0])) ? args[0]
                    : args.length == 2 && "run".equals(args[0])
                        && ("minimal".equals(args[1]) || "stress".equals(args[1])
                            || "composite".equals(args[1])
                            || "native-build".equals(args[1])
                            || "native-test".equals(args[1])
                            || "recovery-stage".equals(args[1])
                            || "recovery-resume".equals(args[1])
                            || "construction-performance".equals(args[1])) ? args[1] : "";
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
