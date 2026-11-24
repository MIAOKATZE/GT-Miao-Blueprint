// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.item.ItemStack;

/** Server-only item observations from a single completed native plan; never construction or debit authority. */
final class HologramNativeItemBill {

    static final class Row {

        private final ItemStack identity;
        final int required, available;

        Row(ItemStack stack, int required, int available) {
            identity = stack.copy();
            identity.stackSize = 1;
            this.required = required;
            this.available = Math.max(0, Math.min(required, available));
        }

        ItemStack item() {
            return identity.copy();
        }
    }

    private final HologramSession owner;
    private final HologramCapture capture;
    private final long revision, workspaceEpoch, uiSequence;
    private final int materialOptions, scope, layer, selected;
    private final List<Row> pending = new ArrayList<>();
    private final List<Row> rows = Collections.unmodifiableList(pending);
    private long missing;
    private boolean unsupported, finished;

    HologramNativeItemBill(HologramSession owner) {
        this.owner = owner;
        capture = owner.capture;
        revision = owner.planRevision;
        workspaceEpoch = owner.editorWorkspaceEpoch;
        uiSequence = owner.uiSequence;
        scope = owner.scope;
        layer = owner.layer;
        selected = owner.selected;
        materialOptions = options(owner);
        unsupported = !knownNativeItems(owner);
    }

    private static boolean knownNativeItems(HologramSession s) {
        return !s.virtual && s.mode == 0 && s.job == 0 && !s.cancelled
            && HologramEditorWorkspaceRegistry.physicalProjection(s)
            && s.capture != null && !s.capture.projectionOnly && !s.capture.pending && !s.capture.incomplete
            && s.player.capabilities.allowEdit && !s.player.capabilities.isCreativeMode && !s.jobVoucherRequested
            && !s.materialSettings.me;
    }

    private static int options(HologramSession s) {
        HologramMaterials.Settings settings = s.materialSettings;
        return (settings.main ? 1 : 0) | (settings.containers ? 2 : 0) | (settings.me ? 4 : 0)
            | settings.priority << 3 | settings.recoveryDestination << 5;
    }

    void rejectUnsupported() {
        if (!unsupported) {
            unsupported = true;
            pending.clear();
        }
    }

    void record(ItemStack sample, int required, int available) {
        if (unsupported) return;
        if (!registeredExactItem(sample) || required <= 0) {
            rejectUnsupported();
            return;
        }
        Row row = new Row(sample, required, available);
        pending.add(row);
        missing += row.required - row.available;
    }

    private static boolean registeredExactItem(ItemStack sample) {
        if (sample == null || sample.getItem() == null || HologramRecovery.hasSeal(sample)
            || HologramMaterialReservations.priority(sample) > 0) return false;
        String name = net.minecraft.item.Item.itemRegistry.getNameForObject(sample.getItem());
        return name != null && net.minecraft.item.Item.itemRegistry.getObject(name) == sample.getItem();
    }

    void finish() {
        finished = true;
    }

    boolean current(HologramSession s) {
        return finished && !unsupported && s == owner && s.capture == capture && s.planRevision == revision
            && s.editorWorkspaceEpoch == workspaceEpoch && s.uiSequence == uiSequence
            && s.scope == scope && s.layer == layer && s.selected == selected
            && materialOptions == options(s) && knownNativeItems(s) && s.planRecoveryFits
            && HologramWorkspace.find(s.player.getUniqueID()) == s && HologramSessionPolicy.valid(s);
    }

    List<Row> rows() {
        return rows;
    }

    /** The observed bill can refuse a shortage; it cannot prove the stock will still be present at debit. */
    boolean observedStockSufficient(HologramSession s) {
        return current(s) && missing == 0 && s.planMissing == 0;
    }

    long missing() {
        return missing;
    }
}
