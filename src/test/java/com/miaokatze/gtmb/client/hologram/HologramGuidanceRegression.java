package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Client presentation policies only; actual rendering and network dispatch have separate native coverage. */
public final class HologramGuidanceRegression {

    private HologramGuidanceRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        savedLibraryAndAuthorWorkspace();
        exactBindingAcknowledgement();
        bindingFailuresAndUserSelection();
        completedAndPreservedNetworks();
        optionalHatchPresetsKeepCurrentPlanReviewable();
        committedSaveGuidanceLifecycle();
    }

    private static void committedSaveGuidanceLifecycle() {
        HologramSaveGuidance guidance = new HologramSaveGuidance();
        check(
            !guidance.pending("save-a") && !guidance.takeNotice("save-a", false),
            "opening, preparing and failed saves have no committed save guidance");
        guidance.committed("save-a");
        check(
            guidance.pending("save-a") && !guidance.pending("save-b"),
            "a committed save recommends structure setup only for its owning workspace");
        check(
            guidance.accent("save-a", 148) == HologramUiAccent.SUGGESTION
                && guidance.accent("save-a", 60) == HologramUiAccent.SUGGESTION
                && guidance.accent("save-a", 163) == HologramUiAccent.SUGGESTION,
            "saved structure entry points use the cyan suggestion semantic");
        check(
            guidance.takeNotice("save-a", false) && !guidance.takeNotice("save-a", false),
            "one committed save presents its explanation once across screen reopen");
        check(guidance.pending("save-a"), "knowing about the distinction does not dismiss the suggested next edit");
        guidance.visitedStructure("save-b");
        check(guidance.pending("save-a"), "entering another workspace cannot dismiss this save's suggestion");
        guidance.visitedStructure("save-a");
        check(
            !guidance.pending("save-a") && guidance.accent("save-a", 148) == 0,
            "actually entering structure adjustment clears the saved suggestion");
        guidance.committed("save-a");
        check(
            !guidance.takeNotice("save-a", true) && guidance.pending("save-a"),
            "do not show again suppresses only the explanatory dialog, preserving entry highlights");
        check(!guidance.takeNotice("save-a", false), "a suppressed notice does not reappear in the same save");
        guidance.committed("");
        check(!guidance.pending(""), "an ownerless save cannot create a global hint");
    }

    private static void optionalHatchPresetsKeepCurrentPlanReviewable() {
        NBTTagCompound state = state();
        state.setBoolean("target", true);
        state.setBoolean("presetAvailable", true);
        state.setBoolean("presetActive", false);
        state.setInteger("missing", 0);
        check(
            HologramGuidance.structureAccent(state, 94) == 0x80efaa
                && HologramGuidance.structureAccent(state, 50) == 0x80efaa,
            "a fresh controller offers both the optional preset shortcut and direct construction review");
        check(HologramPlacementConfirmation.canBuild(state, 5), "a reviewed default plan needs no preset selection");
        state.setInteger("missing", 1);
        check(
            !HologramPlacementConfirmation.canBuild(state, 5),
            "optional presets cannot bypass material requirements");
        state.setInteger("missing", 0);
        check(
            !HologramPlacementConfirmation.canBuild(state, 4),
            "optional presets cannot bypass a stale review revision");
        state.setBoolean("presetActive", true);
        check(
            HologramGuidance.structureAccent(state, 50) == 0x80efaa,
            "applying a preset retains direct construction guidance");
        state.setBoolean("virtual", true);
        check(
            HologramGuidance.structureAccent(state, 94) == 0 && HologramGuidance.structureAccent(state, 50) == 0,
            "controller shortcuts do not override the virtual blueprint placement lifecycle");
    }

    private static void savedLibraryAndAuthorWorkspace() {
        NBTTagCompound state = state();
        state.setBoolean("virtual", true);
        state.setBoolean("blueprintSaved", true);
        state.setBoolean("blueprintProcessingRequired", true);
        state.setString("openPurpose", "blueprintBrowse");
        state.setString("selectedPresetId", "legacy");
        NBTTagList presets = new NBTTagList();
        NBTTagCompound saved = new NBTTagCompound();
        saved.setString("id", "legacy");
        presets.appendTag(saved);
        state.setTag("presets", presets);
        check(
            !HologramGuidance.authorWorkspace(state),
            "a clean saved selection is browsing, despite the author processing gate");
        check(
            !HologramGuidance.next(new HologramState(state), -1, true, false)
                .contains("处理流程"),
            "browsing never directs a player into author processing");
        check(
            state.getBoolean("blueprintProcessingRequired"),
            "presentation preserves the server processing requirement");
        check(
            HologramGuidance.selectedSavedBlueprint(state, false, ""),
            "legacy selection resolves against legacy rows");
        check(
            !HologramGuidance.selectedSavedBlueprint(state, true, ""),
            "a stale server id cannot select a client/server library row");
        state.setString("selectedPresetId", "stale");
        boolean localSelected = HologramGuidance.selectedSavedBlueprint(state, true, "client-selected");
        check(
            localSelected && !HologramGuidance.selectedSavedBlueprint(state, false, "client-selected"),
            "client/server and legacy selection remain independent");
        check(
            HologramGuidance.accent(new HologramState(state), 141, -1, true, false, localSelected) == 0x80efaa,
            "the real selected saved row recommends placement in green");
        check(
            HologramGuidance.accent(new HologramState(state), 141, -1, true, false, false) == 0,
            "removing the real selection removes placement guidance");
        state.setString("openPurpose", "blueprintEdit");
        check(
            HologramGuidance.authorWorkspace(state),
            "explicit editing retains the processing workspace for saved blueprints");
        state.setString("openPurpose", "");
        state.setBoolean("blueprintSaved", false);
        state.setBoolean("presetBrowseForPlacement", true);
        check(!HologramGuidance.authorWorkspace(state), "placement browsing is not a new author draft");
        state.setBoolean("presetBrowseForPlacement", false);
        state.setBoolean("blueprintDirty", true);
        check(HologramGuidance.authorWorkspace(state), "an imported or edited draft retains author processing");
        state.setBoolean("virtualPlacement", true);
        check(!HologramGuidance.authorWorkspace(state), "placement does not inherit draft author accents");
    }

    private static void exactBindingAcknowledgement() {
        NBTTagCompound state = bindingState(false, false);
        HologramNetworkBindingNavigation navigation = new HologramNetworkBindingNavigation();
        navigation.begin(state, 11, "A", false);
        check(
            navigation.observe(state, "A") == -1 && navigation.pending(),
            "sending a request cannot advance selection");
        check(
            HologramNetworkBindingNavigation.accent(61001, state, 0, true, false) == 0,
            "pending bindings remove terminal action highlights");
        NBTTagCompound stale = copy(state);
        group(stale, 0).setBoolean("bound", true);
        stale.setLong("planRevision", 6);
        check(
            navigation.observe(stale, "A") == -1 && navigation.pending(),
            "an older snapshot cannot acknowledge a binding");
        NBTTagCompound acknowledged = copy(stale);
        acknowledged.setLong("uiSequence", 11);
        check(
            navigation.observe(acknowledged, "A") == 1 && !navigation.pending(),
            "the exact fresh server acknowledgement selects the next unbound group");
        check(navigation.observe(acknowledged, "A") == -1, "repeated acknowledgements never advance again");
        check(
            HologramNetworkBindingNavigation.accent(60001, acknowledged, 1, false, false) == 0x68dded,
            "only the current pending group is highlighted");
        check(
            HologramNetworkBindingNavigation.accent(60000, acknowledged, 1, false, false) == 0,
            "the previously bound group loses its pending highlight");
        check(
            HologramNetworkBindingNavigation.accent(61001, acknowledged, 1, false, false) == 0x80efaa
                && HologramNetworkBindingNavigation.accent(61002, acknowledged, 1, false, false) == 0,
            "only server-reported available inventory terminals are highlighted");
        NBTTagList groups = HologramNetworkBindingNavigation.groups(acknowledged);
        for (int i = 2; i <= 6; i++) groups.appendTag(networkGroup("extra-" + i, i != 6, true));
        group(acknowledged, 1).setBoolean("bound", true);
        check(
            HologramNetworkBindingNavigation.firstPending(acknowledged) == 6,
            "the next pending group remains discoverable across drawer pages");
    }

    private static void bindingFailuresAndUserSelection() {
        NBTTagCompound state = bindingState(false, false);
        HologramNetworkBindingNavigation navigation = new HologramNetworkBindingNavigation();
        navigation.begin(state, 11, "A", false);
        NBTTagCompound failed = copy(state);
        failed.setLong("uiSequence", 11);
        check(
            navigation.observe(failed, "A") == -1 && !navigation.pending(),
            "a failed exact acknowledgement cannot advance");
        group(failed, 0).setBoolean("bound", true);
        failed.setLong("planRevision", 6);
        check(navigation.observe(failed, "A") == -1, "late readiness after failure cannot revive a completed request");
        navigation.begin(state, 11, "A", false);
        check(
            navigation.observe(failed, "B") == -1 && !navigation.pending(),
            "manual selection prevents an old request stealing focus");
        navigation.begin(state, 11, "A", false);
        failed.setString("session", "different");
        check(
            navigation.observe(failed, "A") == -1 && !navigation.pending(),
            "session changes cancel pending navigation");
        failed.setString("session", "guidance");
        failed.setLong("uiSequence", 12);
        navigation.begin(state, 11, "A", false);
        check(
            navigation.observe(failed, "A") == -1 && !navigation.pending(),
            "a newer operation cannot impersonate the binding acknowledgement");
        state = bindingState(true, false);
        navigation.begin(state, 11, "A", true);
        failed = copy(state);
        failed.setLong("uiSequence", 11);
        check(navigation.observe(failed, "A") == -1, "an old bound flag cannot turn a failed rebind into success");
        navigation.begin(state, 11, "A", true);
        failed.setLong("planRevision", 6);
        check(navigation.observe(failed, "A") == -1, "a deliberate successful rebind stays on the user-selected group");
        check(
            HologramNetworkBindingNavigation.accent(61001, state, 0, false, true) == 0x80efaa,
            "a deliberately selected bound group supports terminal rebinding");
    }

    private static void completedAndPreservedNetworks() {
        NBTTagCompound state = bindingState(true, true);
        check(
            HologramNetworkBindingNavigation.firstPending(state) == -1
                && HologramPlacementConfirmation.networksReady(state),
            "all server-bound groups complete the drawer");
        check(
            HologramNetworkBindingNavigation.accent(197, state, 1, false, false) == 0x80efaa
                && HologramNetworkBindingNavigation.accent(61001, state, 1, false, false) == 0,
            "completion highlights confirmation and clears the terminal recommendation");
        check(
            !HologramPlacementConfirmation.needsTerminalBinding(state),
            "completion removes the modal binding recommendation");
        group(state, 1).setBoolean("bound", false);
        check(
            HologramPlacementConfirmation.needsTerminalBinding(state),
            "an unresolved shared group recommends the modal binding button");
        check(
            HologramGuidance.accent(new HologramState(state), 196, -1, false, true, false) == 0x80efaa,
            "placement review recommends resolving real pending shared groups");
        int[] bounds = HologramPlacementConfirmation.modalBounds(state);
        check(
            bounds[0] <= 50 && bounds[2] >= 365 && bounds[3] >= 253 + 17 + 8,
            "the confirmation background contains the entire network button plus padding");
        NBTTagCompound preserved = state();
        NBTTagCompound bindings = new NBTTagCompound();
        NBTTagList groups = new NBTTagList();
        groups.appendTag(networkGroup("original", false, false));
        bindings.setTag("groups", groups);
        preserved.setTag("networkBindings", bindings);
        check(
            !HologramPlacementConfirmation.needsTerminalBinding(preserved)
                && HologramNetworkBindingNavigation.firstPending(preserved) == -1,
            "an unavailable private original anchor never asks for a replacement terminal");
        check(
            !HologramPlacementConfirmation.networksReady(preserved),
            "private original anchors still retain their real readiness policy");
        check(
            HologramNetworkBindingNavigation.accent(61001, preserved, 0, false, true) == 0
                && HologramGuidance.accent(new HologramState(preserved), 196, -1, false, true, false) == 0,
            "preserved groups never recommend terminal rebinding");
        check(
            HologramPlacementConfirmation.modalBounds(state())[3] == 254,
            "reviews without networks retain their compact bounds");
    }

    private static NBTTagCompound state() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", "guidance");
        state.setLong("uiSequence", 10);
        state.setLong("planRevision", 5);
        state.setBoolean("networkBindingRequested", true);
        return state;
    }

    private static NBTTagCompound bindingState(boolean firstBound, boolean secondBound) {
        NBTTagCompound state = state();
        NBTTagCompound bindings = new NBTTagCompound();
        bindings.setBoolean("terminalBinding", true);
        NBTTagList groups = new NBTTagList();
        groups.appendTag(networkGroup("A", firstBound, true));
        groups.appendTag(networkGroup("B", secondBound, true));
        bindings.setTag("groups", groups);
        NBTTagList inventory = new NBTTagList();
        NBTTagCompound terminal = new NBTTagCompound();
        terminal.setInteger("slot", 1);
        terminal.setBoolean("available", true);
        inventory.appendTag(terminal);
        bindings.setTag("inventory", inventory);
        state.setTag("networkBindings", bindings);
        return state;
    }

    private static NBTTagCompound networkGroup(String id, boolean bound, boolean terminalBinding) {
        NBTTagCompound group = new NBTTagCompound();
        group.setString("id", id);
        group.setBoolean("bound", bound);
        group.setBoolean("terminalBinding", terminalBinding);
        return group;
    }

    private static NBTTagCompound group(NBTTagCompound state, int index) {
        return HologramNetworkBindingNavigation.groups(state)
            .getCompoundTagAt(index);
    }

    private static NBTTagCompound copy(NBTTagCompound state) {
        return (NBTTagCompound) state.copy();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
