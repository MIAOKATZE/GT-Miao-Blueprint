package com.miaokatze.gtmb.client.hologram;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Boundary regressions for the actual composed workstation input and request lifecycle. */
public final class HologramUiModuleRegression {

    private HologramUiModuleRegression() {}

    public static void run() {
        List<String> visited = new ArrayList<>();
        boolean handled = HologramUiInputChain.consume(() -> {
            visited.add("hidden overlay");
            return false;
        }, () -> {
            visited.add("visible modal: disabled control");
            return true;
        }, () -> {
            visited.add("underlying build action");
            return true;
        });
        SimpleAssert.that(handled, "a visible modal owns an inert click");
        SimpleAssert.eq(
            Arrays.asList("hidden overlay", "visible modal: disabled control"),
            visited,
            "modal consumption prevents the underlying world/editor action from running");
        visited.clear();
        SimpleAssert.that(!HologramUiInputChain.consume(() -> {
            visited.add("no modal");
            return false;
        }), "unclaimed input remains available to the viewport");

        HologramUiTicket ticket = new HologramUiTicket();
        ticket.begin("workspace-a", 12);
        SimpleAssert.that(!ticket.acknowledge("workspace-a", 11), "older ACK retains the pending command");
        SimpleAssert.eq(12L, ticket.sequence(), "pending sequence survives an older snapshot");
        SimpleAssert.that(
            !ticket.acknowledge("workspace-b", 99),
            "a different session cannot complete a command even with a higher sequence");
        SimpleAssert.that(ticket.acknowledge("workspace-a", 12), "the matching ACK clears its ticket");
        SimpleAssert.eq(-1L, ticket.sequence(), "completed command is no longer pending");
        ticket.begin("workspace-b", 2);
        SimpleAssert.that(
            !ticket.acknowledge("workspace-a", 1000),
            "a late response cannot complete the next workspace's reused sequence");
        ticket.clear();
        SimpleAssert.that(!ticket.acknowledge("workspace-b", 2), "explicit close/reset retires the ticket");

        NBTTagCompound opening = new NBTTagCompound();
        opening.setString("workspaceTab", "structure");
        SimpleAssert.eq(
            Boolean.FALSE,
            HologramUiTabMemory.openingTab(opening, false),
            "a new workspace uses its server default");
        SimpleAssert.eq(
            null,
            HologramUiTabMemory.openingTab(opening, true),
            "air reopen retains the user's blueprint tab over a stale structure default");
        opening.setString("workspaceTab", "blueprint");
        SimpleAssert.eq(
            Boolean.TRUE,
            HologramUiTabMemory.openingTab(opening, false),
            "a fresh blueprint workspace opens its default tab");
        SimpleAssert.eq(
            null,
            HologramUiTabMemory.openingTab(opening, true),
            "a heartbeat or reopen also retains the user's structure tab over a stale blueprint default");
        opening.setString("openPurpose", "controllerStructure");
        SimpleAssert.eq(
            Boolean.FALSE,
            HologramUiTabMemory.openingTab(opening, true),
            "an actual controller click overrides remembered blueprint navigation");
        opening.setString("workspaceTab", "structure");
        for (String purpose : new String[] { "blueprintBrowse", "blueprintEdit", "blueprintPlacement" }) {
            opening.setString("openPurpose", purpose);
            SimpleAssert.eq(
                Boolean.TRUE,
                HologramUiTabMemory.openingTab(opening, true),
                "the explicit " + purpose + " opening overrides remembered structure navigation");
        }
    }
}
