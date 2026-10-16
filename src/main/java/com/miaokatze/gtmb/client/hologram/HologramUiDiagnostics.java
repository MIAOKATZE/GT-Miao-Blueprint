package com.miaokatze.gtmb.client.hologram;

import static com.miaokatze.gtmb.client.hologram.HologramUiCanvas.*;
import static net.minecraft.client.gui.Gui.*;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.lwjgl.input.Keyboard;

import com.miaokatze.gtmb.client.hologram.HologramUiLibrary.LibraryNavigation;
import com.miaokatze.gtmb.client.hologram.HologramUiMemory.UiMemory;

/** Internal diagnostics workflow; composed by the workstation, never part of the mod extension API. */
final class HologramUiDiagnostics {

    private final HologramUiModules ui;

    HologramUiDiagnostics(HologramUiModules ui) {
        this.ui = ui;
    }

    private boolean smokeCtrlSelection;

    private boolean smokeAltSelection;

    private boolean smokeSameTypeSelection;

    // Fixture windows own separate capture state; enable and inspect the window receiving the input.
    private boolean captureRenderSmokeAction;

    private NBTTagCompound capturedRenderSmokeAction;

    NBTTagCompound smokeAeUiState() {
        HologramUiDiagnostics.requireInteractionSmoke();
        if (this.captureRenderSmokeAction)
            throw new IllegalStateException("AE live observations require actual network dispatch");
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("modal", ui.ae.aeReview() != null);
        result.setBoolean("awaitingPreview", ui.ae.awaitingAePreview());
        result.setBoolean("awaitingNavigation", ui.ae.aeReviewNavigation() != null);
        if (ui.ae.aeReviewNavigation() != null) {
            result.setString("navigationStructure", ui.ae.aeReviewNavigation().structureId);
            result.setLong("navigationSequence", ui.ae.aeReviewNavigation().selectionSequence);
        }
        result.setString(
            "pinnedWarning",
            ui.annotations.pinnedAnnotation()
                .startsWith("ae:") ? ui.annotations.pinnedAnnotation() : "");
        result.setBoolean("rootBlueprintTab", ui.library.libraryDrawer());
        result.setLong("pendingSequence", ui.session.pendingSequence());
        result.setInteger("tab", ui.ae.aeReviewTab());
        result.setInteger("offset", ui.ae.aeReviewOffset());
        result.setInteger("group", ui.ae.aeReviewGroup());
        result.setInteger("selectedIssue", ui.ae.aeReviewSelected());
        result.setInteger("detailOffset", ui.ae.aeDetailOffset());
        if (ui.ae.aeReview() == null) return result;
        result.setBoolean("saveReview", ui.ae.aeReview().review);
        result.setBoolean("valid", ui.ae.aeReview().valid);
        result.setBoolean(
            "canConfirm",
            ui.ae.aeReview().canConfirm && ui.ae.aeReview()
                .matches(ui.session.state().session, ui.session.revision()) && ui.session.pendingSequence() < 0);
        result.setString("transactionId", ui.ae.aeReview().transactionId);
        result.setString("session", ui.ae.aeReview().session);
        result.setLong("planRevision", ui.ae.aeReview().revision);
        result.setInteger("issueCount", ui.ae.aeReview().issues.size());
        result.setInteger("groupCount", ui.ae.aeReview().groups.size());
        result.setInteger("warningCount", ui.ae.aeReview().warningCount);
        result.setInteger("blockerCount", ui.ae.aeReview().blockerCount);
        result.setInteger("materialCount", ui.ae.aeReview().materials.size());
        result.setInteger("filteredRowCount", ui.ae.aeRowCount());
        net.minecraft.nbt.NBTTagList visible = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < 5 && ui.ae.aeReviewOffset() + i < ui.ae.aeRowCount(); i++) {
            int index = ui.ae.aeReviewOffset() + i;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("index", index);
            if (ui.ae.aeReviewTab() == 0) {
                HologramAeReviewModel.Group group = ui.ae.aeReview().groups.get(index);
                row.setInteger("controlId", 40000 + index);
                row.setString("code", group.code);
                row.setString("severity", group.severity);
                row.setLong("count", group.count);
                row.setInteger("issueCount", group.rows.size());
            } else if (ui.ae.aeReviewTab() == 1) {
                HologramAeReviewModel.Issue issue = ui.ae.aeIssueRows()
                    .get(index);
                row.setInteger("controlId", 50000 + index);
                row.setString("code", issue.code);
                row.setString("severity", issue.severity);
                row.setString("path", issue.path);
                row.setString("slotId", issue.slotId);
                row.setString("structureId", issue.structureId);
                row.setString("resolution", issue.resolution);
                row.setInteger("x", issue.x);
                row.setInteger("y", issue.y);
                row.setInteger("z", issue.z);
                row.setLong("count", issue.count);
            } else {
                HologramAeReviewModel.Material material = ui.ae.aeReview().materials.get(index);
                row.setString("name", material.name);
                row.setString("key", material.key);
                row.setString("required", material.required);
                row.setString("available", material.available);
                row.setString("missing", material.missing);
            }
            visible.appendTag(row);
        }
        result.setTag("visibleRows", visible);
        return result;
    }

    net.minecraft.nbt.NBTTagList smokeAeWarningMarkers() {
        HologramUiDiagnostics.requireInteractionSmoke();
        if (this.captureRenderSmokeAction)
            throw new IllegalStateException("AE live observations require actual network dispatch");
        net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
        for (HologramAnnotationMarkers.Marker marker : ui.annotations.visibleMarkers()) {
            if (!marker.key.startsWith("ae:")) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setString("key", marker.key);
            row.setString("title", marker.title);
            row.setIntArray("screenPoint", ui.diagnostics.smokePreviewPoint(marker.x, marker.y));
            row.setBoolean("pinned", marker.key.equals(ui.annotations.pinnedAnnotation()));
            if (marker.bounds != null) row.setIntArray("bounds", marker.bounds.clone());
            rows.appendTag(row);
        }
        return rows;
    }

    String smokeAeReviewGui() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("UI smoke disabled");
        NBTTagCompound fixture = (NBTTagCompound) ui.session.state().data.copy();
        fixture.setString("session", ui.session.state().session + ":ae-review-fixture:" + System.nanoTime());
        fixture.setLong("planRevision", 77);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("blueprintPreview", false);
        fixture.setBoolean("presetBrowseForPlacement", false);
        fixture.setBoolean("capturePending", false);
        fixture.setInteger("job", 0);
        NBTTagCompound dto = new NBTTagCompound();
        dto.setString("session", fixture.getString("session"));
        dto.setString("transactionId", "gui-review-one");
        dto.setString("name", "AE GUI review fixture");
        dto.setLong("planRevision", 77);
        dto.setBoolean("canConfirm", true);
        net.minecraft.nbt.NBTTagList rows = new net.minecraft.nbt.NBTTagList();
        for (int i = 0; i < 7; i++) {
            NBTTagCompound issue = new NBTTagCompound();
            issue.setString("code", "ae.cell_contents_cleared");
            issue.setString("severity", "warning");
            issue.setString("path", "inv/item" + i);
            issue.setString("message", "Cell contents are omitted; original source unchanged.");
            issue.setLong("count", 1);
            issue.setInteger("x", i);
            issue.setInteger("y", 0);
            issue.setInteger("z", 0);
            rows.appendTag(issue);
        }
        dto.setTag("issues", rows);
        dto.setInteger("issueCount", 7);
        dto.setInteger("warningCount", 7);
        dto.setInteger("blockerCount", 0);
        fixture.setTag("aeSaveReview", dto);
        HologramScreen probe = new HologramScreen(new HologramState(fixture));
        probe.setWorldAndResolution(ui.host.minecraft(), ui.host.screenWidth(), ui.host.screenHeight());
        probe.ui.diagnostics.captureRenderSmokeAction = true;
        GuiScreen originalGui = ui.host.minecraft().currentScreen;
        try {
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(186);
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(189);
            if (probe.ui.ae.aeReviewOffset() != 2 || probe.ui.ae.aeIssueRows()
                .size() != 7) throw new IllegalStateException("AE issue paging lost rows");
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(181);
            if (probe.ui.diagnostics.capturedRenderSmokeAction == null
                || !"confirmAeSave".equals(probe.ui.diagnostics.capturedRenderSmokeAction.getString("op"))
                || !"gui-review-one".equals(probe.ui.diagnostics.capturedRenderSmokeAction.getString("transactionId"))
                || probe.ui.diagnostics.capturedRenderSmokeAction.getLong("planRevision") != 77)
                throw new IllegalStateException("AE save confirmation did not bind exact review");
            probe.updateState(new HologramState(fixture));
            if (probe.ui.ae.aeReview() != null) throw new IllegalStateException("Same AE review nonce opened twice");
            dto.setString("transactionId", "gui-review-blocked");
            rows.getCompoundTagAt(6)
                .setString("severity", "blocker");
            dto.setInteger("warningCount", 6);
            dto.setInteger("blockerCount", 1);
            fixture.setTag("aeSaveReview", dto);
            fixture.setLong("uiSequence", probe.ui.session.sequence());
            probe.updateState(new HologramState(fixture));
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.capturedRenderSmokeAction = null;
            probe.ui.diagnostics.smokeClickDrawnControl(181);
            if (probe.ui.diagnostics.capturedRenderSmokeAction != null || probe.ui.ae.aeReview() == null)
                throw new IllegalStateException("Blocked AE save emitted confirmation");
            probe.ui.router.keyTyped('\0', Keyboard.KEY_ESCAPE);
            if (probe.ui.ae.aeReview() != null
                || !"cancelAeSave".equals(probe.ui.diagnostics.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("AE modal Esc failed to cancel without closing root");
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeControlPoint(148);
            probe.ui.diagnostics.smokeControlPoint(149);
            if (ui.host.minecraft().currentScreen != originalGui)
                throw new IllegalStateException("AE modal Esc replaced the root GUI");
            probe.updateState(new HologramState(fixture));
            if (probe.ui.ae.aeReview() != null) throw new IllegalStateException("Cancelled AE review reopened");
            fixture.removeTag("aeSaveReview");
            if (fixture.getTagList("cells", 10)
                .tagCount() > 0) {
                NBTTagCompound member = fixture.getTagList("cells", 10)
                    .getCompoundTagAt(0);
                member.setString("structureId", "gui-ae-structure");
                for (int i = 0; i < rows.tagCount(); i++) {
                    NBTTagCompound issue = rows.getCompoundTagAt(i);
                    issue.setInteger("x", member.getInteger("x"));
                    issue.setInteger("y", member.getInteger("y"));
                    issue.setInteger("z", member.getInteger("z"));
                    issue.setString("structureId", "gui-ae-structure");
                }
            }
            fixture.setTag("aeIssues", rows);
            probe.updateState(new HologramState(fixture));
            probe.ui.diagnostics.capturedRenderSmokeAction = null;
            probe.drawScreen(0, 0, 0);
            HologramAnnotationMarkers.Marker reportMarker = null;
            int structureMarkers = 0;
            for (HologramAnnotationMarkers.Marker marker : probe.ui.annotations.annotationMarkers())
                if ("ae:structure:gui-ae-structure".equals(marker.key)) structureMarkers++;
            for (HologramAnnotationMarkers.Marker marker : probe.ui.annotations.visibleMarkers())
                if ("ae:report".equals(marker.key)) reportMarker = marker;
            if (reportMarker == null || fixture.getTagList("cells", 10)
                .tagCount() > 0 && structureMarkers != 1)
                throw new IllegalStateException("AE warnings lack a complete report or one structural aggregate");
            int[] point = probe.ui.diagnostics.smokePreviewPoint(reportMarker.x, reportMarker.y);
            probe.drawScreen(point[0], point[1], 0);
            if (probe.ui.canvas.tooltip() == null || !probe.ui.canvas.tooltip()
                .toString()
                .contains("存储元件内容清空"))
                throw new IllegalStateException("AE warning icon hover did not expand the summary");
            probe.ui.router.mouseClicked(point[0], point[1], 0);
            probe.ui.router.mouseMovedOrUp(point[0], point[1], 0);
            probe.drawScreen(0, 0, 0);
            if (!"ae:report".equals(probe.ui.annotations.pinnedAnnotation())
                || probe.ui.diagnostics.capturedRenderSmokeAction != null)
                throw new IllegalStateException("AE warning pin did not persist as a local interaction");
            probe.ui.diagnostics.smokeClickDrawnControl(184);
            if (probe.ui.ae.aeReview() == null || probe.ui.ae.aeReview().review
                || probe.ui.ae.aeReview().issues.size() != 7)
                throw new IllegalStateException("AE warning details failed to retain every report row");
            probe.ui.router.keyTyped('\0', Keyboard.KEY_ESCAPE);
            if (probe.ui.ae.aeReview() != null || probe.ui.diagnostics.capturedRenderSmokeAction != null)
                throw new IllegalStateException("AE preview details Esc sent a save or closed the root");
            probe.ui.library.libraryDrawer(true);
            probe.ui.library.presetNameField()
                .setText("A");
            probe.ui.library.presetNameField()
                .setFocused(true);
            probe.ui.router.keyTyped('e', Keyboard.KEY_E);
            if (!"Ae".equals(
                probe.ui.library.presetNameField()
                    .getText())
                || !probe.ui.library.libraryDrawer()
                || ui.host.minecraft().currentScreen != originalGui)
                throw new IllegalStateException("Focused blueprint name swallowed E after AE modal cancellation");
            return "paged=7 nonce/revision=bound blocker=disabled Esc=cancel/root-tabs E=typing nonce=once warning=hover/pin/one-structure";
        } finally {
            probe.ui.diagnostics.captureRenderSmokeAction = false;
        }
    }

    NBTTagCompound smokeLibraryUiState() {
        HologramLibraryClient library = HologramLibraryClient.INSTANCE;
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("dedicated", HologramLibraryClient.dedicated());
        result.setBoolean("library", ui.library.libraryDrawer());
        result.setBoolean("public", ui.library.publicLibrary());
        result.setBoolean("blueprintTab", ui.library.blueprintTab());
        result.setBoolean("processingFlow", ui.processing.processingFlow() != null);
        result.setInteger(
            "flowStage",
            ui.processing.processingFlow() == null ? -1 : ui.processing.processingFlow().stage);
        result.setBoolean(
            "flowChosen",
            ui.processing.processingFlow() != null && ui.processing.processingFlow()
                .chosen());
        result.setBoolean("flowQuantumGroupsConfirmed", ui.processing.quantumGroupsConfirmed());
        result.setTag(
            "quantumGroups",
            ui.processing.processingState()
                .getTagList("quantumGroups", 10)
                .copy());
        result.setBoolean(
            "flowFullReview",
            ui.processing.processingFlow() != null && ui.processing.processingFlow()
                .fullReview());
        result.setInteger(
            "flowRequiredStages",
            ui.processing.processingFlow() == null ? -1
                : ui.processing.processingFlow()
                    .requiredStages());
        result.setBoolean("processingComplete", ui.session.state().data.getBoolean("blueprintProcessingComplete"));
        result.setBoolean("processingRequired", ui.session.state().data.getBoolean("blueprintProcessingRequired"));
        result.setBoolean("blueprintDirty", ui.session.state().data.getBoolean("blueprintDirty"));
        result.setBoolean("blueprintSaved", ui.session.state().data.getBoolean("blueprintSaved"));
        result.setBoolean("discardReview", ui.library.discardReview());
        if (ui.library.sourceSwitchProposal() != null) result.setTag(
            "sourceSwitchProposal",
            ui.library.sourceSwitchProposal()
                .copy());
        result.setBoolean("blueprintSavePending", ui.session.state().data.getBoolean("blueprintSavePending"));
        result.setBoolean("overwriteReview", ui.library.overwriteReview());
        result.setBoolean("processingPresetSaveReview", ui.processing.processingPresetSaveReview());
        result.setBoolean("overwriteMode", ui.library.overwriteMode());
        result.setString("overwriteTarget", ui.library.overwriteTarget());
        result.setInteger("libraryOffset", ui.library.libraryOffset());
        result.setDouble("yaw", ui.preview.yaw());
        result.setDouble("pitch", ui.preview.pitch());
        result.setBoolean("nbtCellScope", ui.nbt.nbtCellScope());
        result.setInteger("nbtValueIndex", ui.nbt.nbtValueIndex());
        result.setTag(
            "visibleNbtEntries",
            ui.nbt.editorNbtEntries()
                .copy());
        result.setString("openPurpose", ui.session.state().data.getString("openPurpose"));
        result.setString("session", ui.session.state().session);
        result.setString("serverSelectedPresetId", ui.session.state().data.getString("selectedPresetId"));
        result.setString("selectedLocal", library.selected(false));
        result.setString("selectedPublic", library.selected(true));
        result.setLong("renderGeneration", ui.canvas.renderGeneration());
        result.setLong("planRevision", ui.session.revision());
        result.setLong("uiSequence", ui.session.state().data.getLong("uiSequence"));
        result.setBoolean("presetDrawer", ui.config.presetDrawer());
        result.setBoolean("hatchDrawer", ui.config.hatchDrawer());
        result.setBoolean("drawer", ui.config.drawer());
        result.setBoolean("confirmation", ui.review.confirmation());
        result.setIntArray(
            "confirmationBounds",
            ui.review.confirmation() ? ui.review.lastConfirmationBounds() : new int[0]);
        result.setBoolean("authorWorkspace", HologramGuidance.authorWorkspace(ui.session.state().data));
        result.setBoolean("selectedSavedBlueprint", ui.library.selectedPresetExists());
        result.setString("cacheReviewSummary", ui.review.lastCacheReviewSummary());
        result.setBoolean("emptyCacheReview", ui.review.lastEmptyCacheReview());
        result.setBoolean(
            "cacheQuoteReviewed",
            HologramPlacementConfirmation.cacheReviewMatches(ui.session.state().data, ui.review.reviewedCacheQuote()));
        result.setString("confirmationAction", ui.review.lastConfirmationAction());
        result.setString("blueprintEnergyNotice", ui.session.state().data.getString("blueprintEnergyNotice"));
        result.setBoolean(
            "deleteReview",
            !ui.library.deletePresetId()
                .isEmpty());
        result.setString("deletePresetId", ui.library.deletePresetId());
        result.setBoolean("promptDrawer", ui.annotations.promptDrawer());
        result.setBoolean("versionsDrawer", ui.annotations.versionsDrawer());
        result.setBoolean("nbtDrawer", ui.nbt.nbtDrawer());
        result.setBoolean("resetReview", ui.config.resetReview());
        result.setBoolean("shortcutsDrawer", ui.shortcuts.shortcutsDrawer());
        result.setBoolean("positionDrawer", ui.position.positionDrawer());
        result.setBoolean("sourceDrawer", ui.config.sourceDrawer());
        result.setBoolean("roleDropdown", ui.config.roleDropdown());
        result.setBoolean("networkDrawer", ui.bindings.networkDrawer());
        result.setInteger("networkGroupIndex", ui.bindings.networkGroupIndex());
        result.setInteger("networkGroupOffset", ui.bindings.networkGroupOffset());
        result.setBoolean(
            "pendingNetworkBinding",
            ui.bindings.networkBindingNavigation()
                .pending());
        result.setInteger("nextNetworkGroup", HologramNetworkBindingNavigation.firstPending(ui.session.state().data));
        result.setBoolean("networkGroupsReady", HologramPlacementConfirmation.networksReady(ui.session.state().data));
        result.setTag(
            "networkBindings",
            ui.session.state().data.getCompoundTag("networkBindings")
                .copy());
        result.setBoolean("aeReview", ui.ae.aeReview() != null);
        result.setBoolean("aeReviewNavigation", ui.ae.aeReviewNavigation() != null);
        result.setBoolean("adminDrawer", ui.library.libraryAdminDrawer());
        result.setBoolean("busy", library.busy());
        result.setString("selected", library.selected(ui.library.publicLibrary()));
        result.setString("status", library.status());
        result.setInteger(
            "rows",
            library.rows(ui.library.publicLibrary())
                .tagCount());
        result.setInteger("progressBytes", library.progressBytes());
        result.setInteger("totalBytes", library.totalBytes());
        result.setTag(
            "config",
            library.config()
                .copy());
        result.setTag(
            "entries",
            library.rows(ui.library.publicLibrary())
                .copy());
        NBTTagList drawn = new NBTTagList();
        for (Control control : ui.canvas.controls()) {
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("id", control.id);
            row.setInteger("x", control.x);
            row.setInteger("y", control.y);
            row.setInteger("w", control.w);
            row.setInteger("h", control.h);
            row.setBoolean("enabled", control.enabled);
            row.setString("label", control.label);
            row.setInteger("accent", control.accent);
            row.setIntArray(
                "screenPoint",
                new int[] { ui.canvas.originX() + Math.round((control.x + control.w / 2F) * ui.canvas.scale()),
                    ui.canvas.originY() + Math.round((control.y + control.h / 2F) * ui.canvas.scale()) });
            drawn.appendTag(row);
            if (control.id == 207) result.setBoolean("adminVisible", true);
            if (control.id == 205) result.setBoolean("deleteEnabled", control.enabled);
            if (control.id == 201) result.setBoolean("switchVisible", true);
        }
        result.setTag("drawnControls", drawn);
        return result;
    }

    int[] smokeControlPoint(int id) {
        for (Control c : ui.canvas.controls())
            if (c.id == id) return new int[] { ui.canvas.originX() + Math.round((c.x + c.w / 2F) * ui.canvas.scale()),
                ui.canvas.originY() + Math.round((c.y + c.h / 2F) * ui.canvas.scale()) };
        throw new IllegalArgumentException("No drawn control " + id);
    }

    void smokeClickControl(int id) {
        HologramUiDiagnostics.requireInteractionSmoke();
        int[] p = ui.diagnostics.smokeControlPoint(id);
        ui.router.mouseClicked(p[0], p[1], 0);
        ui.router.mouseMovedOrUp(p[0], p[1], 0);
    }

    void smokeClickAt(int x, int y, int button) {
        HologramUiDiagnostics.requireInteractionSmoke();
        if (this.captureRenderSmokeAction)
            throw new IllegalStateException("Live clicks require actual network dispatch");
        ui.router.mouseClicked(x, y, button);
        ui.router.mouseMovedOrUp(x, y, button);
    }

    NBTTagCompound smokeAreaImportUiState() {
        HologramUiDiagnostics.requireInteractionSmoke();
        if (this.captureRenderSmokeAction)
            throw new IllegalStateException("Live import observations require actual network dispatch");
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("areaImporting", ui.session.areaImporting());
        result.setInteger("areaImportProgress", ui.session.state().data.getInteger("areaImportProgress"));
        result.setString("areaImportStage", ui.session.state().data.getString("areaImportStage"));
        result.setBoolean("editable", ui.session.editable());
        result.setLong("renderGeneration", ui.canvas.renderGeneration());
        result.setIntArray(
            "visibleControlIds",
            ui.canvas.controls()
                .stream()
                .mapToInt(control -> control.id)
                .toArray());
        return result;
    }

    int[] smokeViewportBounds() {
        HologramUiDiagnostics.requireInteractionSmoke();
        return new int[] { HologramUiCanvas.viewportX, HologramUiCanvas.viewportY, HologramUiCanvas.viewportWidth,
            HologramUiCanvas.viewportHeight };
    }

    int[] smokePanelBounds() {
        HologramUiDiagnostics.requireInteractionSmoke();
        return new int[] { HologramScreenLayout.COMPACT_PANEL_X, HologramUiCanvas.panelY,
            HologramScreenLayout.COMPACT_PANEL_WIDTH, HologramScreenLayout.PANEL_HEIGHT };
    }

    boolean smokeKey(int key, int modifiers) {
        HologramUiDiagnostics.requireInteractionSmoke();
        return ui.preview.handleEditorShortcut(key, modifiers);
    }

    String smokeBinding(int index) {
        HologramUiDiagnostics.requireInteractionSmoke();
        return ui.shortcuts.keyBindings()
            .label(index);
    }

    NBTTagCompound smokeSnapshot() {
        HologramUiDiagnostics.requireInteractionSmoke();
        return (NBTTagCompound) ui.session.state().data.copy();
    }

    int[] smokeSelectedIndices() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")
            && !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke"))
            throw new IllegalStateException("Selection smoke is disabled");
        return ui.preview.selection()
            .indices();
    }

    int[] smokeCapturePreviewSelection(int logicalX, int logicalY, boolean sameType, boolean additive) {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        boolean previousCapture = this.captureRenderSmokeAction;
        this.capturedRenderSmokeAction = null;
        this.captureRenderSmokeAction = true;
        this.smokeSameTypeSelection = sameType;
        this.smokeCtrlSelection = additive;
        boolean previousHandles = ui.preview.moveHandles();
        ui.preview.moveHandles(false);
        try {
            int[] point = ui.diagnostics.smokePreviewPoint(logicalX, logicalY);
            ui.router.mouseClicked(point[0], point[1], 0);
            ui.router.mouseMovedOrUp(point[0], point[1], 0);
            if (this.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Local preview selection emitted a network operation");
            return ui.preview.selection()
                .indices();
        } finally {
            this.captureRenderSmokeAction = previousCapture;
            ui.preview.moveHandles(previousHandles);
            this.smokeSameTypeSelection = this.smokeCtrlSelection = false;
        }
    }

    boolean smokeHasControl(int id) {
        HologramUiDiagnostics.requireInteractionSmoke();
        return ui.diagnostics.drawnControlEnabled(id);
    }

    boolean drawnControlEnabled(int id) {
        for (Control control : ui.canvas.controls()) if (control.id == id && control.enabled) return true;
        return false;
    }

    long smokeRenderGeneration() {
        HologramUiDiagnostics.requireInteractionSmoke();
        return ui.canvas.renderGeneration();
    }

    static void requireInteractionSmoke() {
        if (!Boolean.getBoolean("gtmb.hologram.realInteractionSmoke")
            && !Boolean.getBoolean("gtmb.hologram.followupAreaClient"))
            throw new IllegalStateException("Real interaction smoke is disabled");
    }

    boolean smokePending() {
        return ui.session.pendingSequence() >= 0;
    }

    NBTTagCompound smokeCaptureMissingBuildClick() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        NBTTagCompound initial = (NBTTagCompound) ui.session.state().data.copy();
        long reviewed = ui.review.confirmedRevision();
        this.capturedRenderSmokeAction = null;
        this.captureRenderSmokeAction = true;
        try {
            int[] confirm = ui.diagnostics.smokeControlPoint(55);
            ui.router.mouseClicked(confirm[0], confirm[1], 0);
            ui.router.mouseMovedOrUp(confirm[0], confirm[1], 0);
            if (this.capturedRenderSmokeAction == null
                || !"build".equals(this.capturedRenderSmokeAction.getString("op"))
                || this.capturedRenderSmokeAction.getLong("planRevision") != reviewed)
                throw new IllegalStateException("Missing-material modal click did not emit revision-bound build");
            NBTTagCompound request = (NBTTagCompound) this.capturedRenderSmokeAction.copy();
            initial.setLong("uiSequence", request.getLong("uiSequence"));
            initial.setBoolean("forcePlacementReady", true);
            ui.session.updateState(new HologramState(initial));
            if (!ui.review.confirmation()) throw new IllegalStateException("Build force reply did not reopen review");
            int[] cancel = ui.diagnostics.smokeControlPoint(54);
            ui.router.mouseClicked(cancel[0], cancel[1], 0);
            ui.router.mouseMovedOrUp(cancel[0], cancel[1], 0);
            ui.session.updateState(new HologramState(initial));
            if (ui.review.confirmation())
                throw new IllegalStateException("Cancelled force review reopened on an unrelated snapshot");
            return request;
        } finally {
            this.captureRenderSmokeAction = false;
        }
    }

    void smokeRenderScenario(String scenario) {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        ui.review.confirmation(
            ui.annotations.promptDrawer(
                ui.annotations
                    .versionsDrawer(ui.library.libraryDrawer(ui.nbt.nbtDrawer(ui.review.resourceReview(false))))));
        if ("blueprint".equals(scenario)) ui.library.libraryDrawer(true);
        if ("materials".equals(scenario) || "resources".equals(scenario)
            || "force-ready".equals(scenario)
            || "missing-confirm".equals(scenario)) {
            ui.review.confirmation(true);
            ui.review.confirmedRevision(ui.session.revision());
            ui.review.resourceReview("resources".equals(scenario));
        }
        if ("prompts".equals(scenario) || "gizmo".equals(scenario)
            || scenario.startsWith("gizmo-hover-")
            || "missing".equals(scenario)) {
            ui.preview.selected(ui.session.state().cells.isEmpty() ? -1 : 0);
            ui.preview.selection()
                .click(ui.preview.selected(), false);
            if (("gizmo".equals(scenario) || scenario.startsWith("gizmo-hover-")) && ui.preview.selected() >= 0) {
                String id = ui.session.state().cells.get(ui.preview.selected()).structureId;
                if (!id.isEmpty()) ui.preview.selection()
                    .structure(ui.session.state(), id, false);
            }
            ui.preview.moveHandles(true);
            if (scenario.startsWith("gizmo-hover-")) {
                ui.preview.yaw(Math.PI / 4);
                ui.preview.pitch(Math.PI / 6);
                ui.preview.zoom(.45);
            }
            if ("prompts".equals(scenario)) {
                ui.annotations.promptDrawer(true);
                ui.annotations.promptScope(0);
                ui.annotations.promptField()
                    .setText(ui.session.state().data.getString("blueprintPrompt"));
            }
        }
        if ("versions".equals(scenario) || "missing-source".equals(scenario)) ui.annotations.versionsDrawer(true);
        if ("nbt-storage".equals(scenario)) ui.nbt.openNbtEditor(false);
        if ("nbt-placement".equals(scenario)) ui.nbt.openNbtEditor(true);
        if ("native-display-current".equals(scenario)) ui.preview.view(1);
        if ("native-display-target".equals(scenario)) ui.preview.view(0);
    }

    String smokeCaptureLocalSelection() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        if (!ui.preview.previewInteractive())
            throw new IllegalStateException("Selection smoke requires the plain structure surface");
        this.capturedRenderSmokeAction = null;
        this.captureRenderSmokeAction = true;
        ui.preview.moveHandles(false);
        try {
            for (int y = HologramUiCanvas.viewportY + 50; y
                < HologramUiCanvas.viewportY + HologramUiCanvas.viewportHeight - 80; y++) {
                for (int x = HologramUiCanvas.viewportX + 10; x
                    < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 10; x++) {
                    int target = HologramRenderer.pick(x, y);
                    if (target < 0 || !HologramRenderer.pickStructure(x, y)
                        .isEmpty()) continue;
                    ui.preview.selected(-1);
                    ui.preview.selection()
                        .clear();
                    int mouseX = ui.canvas.originX() + Math.round(x * ui.canvas.scale()),
                        mouseY = ui.canvas.originY() + Math.round(y * ui.canvas.scale());
                    ui.router.mouseClicked(mouseX, mouseY, 0);
                    ui.router.mouseMovedOrUp(mouseX, mouseY, 0);
                    if (ui.preview.selection()
                        .size() != 1
                        || !ui.preview.selection()
                            .contains(target))
                        continue;
                    if (this.capturedRenderSmokeAction != null)
                        throw new IllegalStateException("Plain preview click emitted a server operation");
                    this.smokeSameTypeSelection = true;
                    ui.preview.selection()
                        .clear();
                    ui.preview.selected(-1);
                    ui.router.mouseClicked(mouseX, mouseY, 0);
                    ui.router.mouseMovedOrUp(mouseX, mouseY, 0);
                    HologramState.Cell clicked = ui.session.state().cells.get(target);
                    String active = ui.session.state().data.getString("activeStructure");
                    String group = clicked.belongsToStructure(active) ? active : clicked.structureId;
                    int expected = 0;
                    for (HologramState.Cell cell : ui.session.state().cells)
                        if ((group.isEmpty() ? cell.structureIds.isEmpty() : cell.belongsToStructure(group))
                            && HologramSelection.sameType(clicked, cell)) expected++;
                    if (ui.preview.selection()
                        .size() != expected || this.capturedRenderSmokeAction != null)
                        throw new IllegalStateException(
                            "Held T did not select exact block variants in its editing scope");
                    this.smokeSameTypeSelection = false;
                    String outlineChecked = "none";
                    outlineSearch: for (int ey = HologramUiCanvas.viewportY + 50; ey
                        < HologramUiCanvas.viewportY + HologramUiCanvas.viewportHeight - 80; ey++) {
                        for (int ex = HologramUiCanvas.viewportX + 10; ex
                            < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 10; ex++) {
                            String outline = HologramRenderer.pickStructure(ex, ey);
                            if (outline.isEmpty()) continue;
                            int edgeX = ui.canvas.originX() + Math.round(ex * ui.canvas.scale()),
                                edgeY = ui.canvas.originY() + Math.round(ey * ui.canvas.scale());
                            int actualX = ui.canvas.localX(edgeX), actualY = ui.canvas.localY(edgeY);
                            outline = HologramRenderer.pickStructure(actualX, actualY);
                            if (outline.isEmpty()) continue;
                            ui.preview.selection()
                                .clear();
                            ui.preview.selected(-1);
                            ui.router.mouseClicked(edgeX, edgeY, 0);
                            ui.router.mouseMovedOrUp(edgeX, edgeY, 0);
                            int members = 0;
                            for (HologramState.Cell cell : ui.session.state().cells)
                                if (cell.belongsToStructure(outline)) members++;
                            if (ui.preview.selection()
                                .size() != members || this.capturedRenderSmokeAction != null)
                                throw new IllegalStateException(
                                    "Outline click did not select its exact structure locally");
                            outlineChecked = outline + ":" + members;
                            break outlineSearch;
                        }
                    }
                    if (!HologramRenderer.structureBounds(ui.session.state())
                        .isEmpty() && "none".equals(outlineChecked))
                        throw new IllegalStateException(
                            "No actual outline hit region exercised by native selection smoke");
                    ui.preview.clearSelection();
                    if (ui.preview.selected() != -1 || ui.preview.selection()
                        .size() != 0 || this.capturedRenderSmokeAction != null)
                        throw new IllegalStateException("Clearing preview selection emitted a server operation");
                    return "native face click=" + target
                        + " selected=1 T-selected="
                        + expected
                        + " outline="
                        + outlineChecked
                        + " cleared=0 packets=0";
                }
            }
            throw new IllegalStateException("No plain face hit region for local selection smoke");
        } finally {
            this.smokeSameTypeSelection = false;
            this.captureRenderSmokeAction = false;
        }
    }

    String smokeCaptureV004Editor() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        NBTTagCompound fixture = (NBTTagCompound) ui.session.state().data.copy();
        fixture.setBoolean("blueprintPreview", false);
        fixture.setBoolean("presetBrowseForPlacement", false);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("capturePending", false);
        fixture.setInteger("job", 0);
        fixture.setString("blueprintPrompt", "V004 icon hover and pin");
        HologramScreen probe = new HologramScreen(new HologramState(fixture));
        probe.setWorldAndResolution(ui.host.minecraft(), ui.host.screenWidth(), ui.host.screenHeight());
        probe.ui.diagnostics.captureRenderSmokeAction = true;
        try {
            probe.drawScreen(0, 0, 0);
            HologramAnnotationMarkers.Marker global = probe.ui.annotations.visibleMarkers()
                .get(0);
            int[] markerPoint = probe.ui.diagnostics.smokePreviewPoint(global.x, global.y);
            probe.drawScreen(markerPoint[0], markerPoint[1], 0);
            if (probe.ui.canvas.tooltip() == null || !probe.ui.canvas.tooltip()
                .toString()
                .contains("V004 icon hover and pin"))
                throw new IllegalStateException("Annotation icon hover did not expand the persisted prompt");
            probe.ui.router.mouseClicked(markerPoint[0], markerPoint[1], 0);
            probe.ui.router.mouseMovedOrUp(markerPoint[0], markerPoint[1], 0);
            if (!"blueprint".equals(probe.ui.annotations.pinnedAnnotation())
                || probe.ui.diagnostics.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Annotation pin was not a local mouse interaction");
            probe.drawScreen(0, 0, 0);
            if (!"blueprint".equals(probe.ui.annotations.pinnedAnnotation()))
                throw new IllegalStateException("Pinned prompt disappeared when the mouse left the icon");
            probe.ui.router.mouseClicked(markerPoint[0], markerPoint[1], 0);
            probe.ui.router.mouseMovedOrUp(markerPoint[0], markerPoint[1], 0);
            if (!probe.ui.annotations.pinnedAnnotation()
                .isEmpty()) throw new IllegalStateException("Second icon click did not unpin");

            int member = -1;
            for (HologramState.Cell cell : probe.ui.session.state().cells) if (!cell.structureId.isEmpty()) {
                int members = 0;
                for (HologramState.Cell other : probe.ui.session.state().cells)
                    if (other.belongsToStructure(cell.structureId)) members++;
                if (members < 2) continue;
                member = cell.index;
                break;
            }
            if (member < 0) throw new IllegalStateException(
                "V004 editor smoke requires a structure with at least two real members");
            probe.ui.preview.selection()
                .click(member, false);
            probe.ui.preview.selected(member);
            if (probe.ui.preview.selection()
                .movable(probe.ui.session.state()))
                throw new IllegalStateException("One member of the smoke structure incorrectly became movable");
            probe.ui.preview.selection()
                .structure(probe.ui.session.state(), probe.ui.session.state().cells.get(member).structureId, false);
            for (HologramState.Cell cell : probe.ui.session.state().cells) if (probe.ui.preview.selection()
                .contains(cell.index)
                != cell.belongsToStructure(probe.ui.session.state().cells.get(member).structureId))
                throw new IllegalStateException(
                    "Whole-structure movement selection included an outside cell or missed a member");
            probe.ui.preview.selected(
                probe.ui.preview.selection()
                    .first());
            probe.ui.preview.moveHandles(true);
            probe.drawScreen(0, 0, 0);
            if (!probe.ui.preview.selection()
                .movable(probe.ui.session.state()))
                throw new IllegalStateException("Complete structure selection did not expose legal movement");
            NBTTagCompound copy = probe.ui.diagnostics.smokeCaptureAxisDrag(true, false);
            if (!copy.getBoolean("copy"))
                throw new IllegalStateException("Ctrl released during drag lost the copy intent locked at mouse-down");
            probe.ui.session.pendingSequence(probe.ui.preview.pendingSelectionSequence(-1));
            probe.ui.diagnostics.capturedRenderSmokeAction = null;
            NBTTagCompound move = probe.ui.diagnostics.smokeCaptureAxisDrag(false, true);
            if (move.getBoolean("copy"))
                throw new IllegalStateException("Ctrl pressed after drag began changed move into copy");
            probe.ui.session.pendingSequence(probe.ui.preview.pendingSelectionSequence(-1));
            probe.ui.diagnostics.capturedRenderSmokeAction = null;
            probe.ui.diagnostics.smokeCtrlSelection = false;
            probe.ui.diagnostics.smokeAltSelection = true;
            probe.ui.preview.moveHandles(false);
            probe.ui.preview.selection()
                .clear();
            probe.ui.preview.selected(-1);
            probe.drawScreen(0, 0, 0);
            int[] air = null;
            airSearch: for (int y = HologramUiCanvas.viewportY + 50; y
                < HologramUiCanvas.viewportY + HologramUiCanvas.viewportHeight - 45; y += 2)
                for (int x = HologramUiCanvas.viewportX + 10; x
                    < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 10; x += 2) {
                        int[] point = probe.ui.diagnostics.smokePreviewPoint(x, y);
                        int[] voxel = probe.ui.preview
                            .emptyVoxelAt(probe.ui.canvas.localX(point[0]), probe.ui.canvas.localY(point[1]));
                        if (voxel == null) continue;
                        boolean exists = false;
                        for (HologramState.Cell cell : probe.ui.session.state().cells)
                            if (cell.x == voxel[0] && cell.y == voxel[1] && cell.z == voxel[2]) exists = true;
                        if (exists) continue;
                        probe.drawScreen(point[0], point[1], 0);
                        if (!java.util.Arrays.equals(voxel, probe.ui.preview.emptyVoxelPreview()))
                            throw new IllegalStateException(
                                "Alt air coordinates disagree with the projected voxel preview");
                        probe.ui.router.mouseClicked(point[0], point[1], 0);
                        probe.ui.router.mouseMovedOrUp(point[0], point[1], 0);
                        NBTTagCompound request = probe.ui.diagnostics.capturedRenderSmokeAction;
                        if (request == null || !"addPosition".equals(request.getString("op"))
                            || request.getInteger("dx") + fixture.getInteger("x") != voxel[0]
                            || request.getInteger("dy") + fixture.getInteger("y") != voxel[1]
                            || request.getInteger("dz") + fixture.getInteger("z") != voxel[2])
                            throw new IllegalStateException("Alt mouse pick did not submit its previewed air voxel");
                        air = voxel;
                        break airSearch;
                    }
            if (air == null) throw new IllegalStateException("No bounded projected air voxel exercised by mouse smoke");
            return "icon=hover,pin,unpin member-gizmo=hidden complete-gizmo=visible copy-down=true move-down=false air="
                + java.util.Arrays.toString(air);
        } finally {
            probe.ui.diagnostics.captureRenderSmokeAction = probe.ui.diagnostics.smokeCtrlSelection = probe.ui.diagnostics.smokeAltSelection = false;
        }
    }

    String smokeCaptureV004Tabs() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        NBTTagCompound fixture = (NBTTagCompound) ui.session.state().data.copy();
        String fixtureSession = java.util.UUID
            .nameUUIDFromBytes(
                ("gtmb-tabs:" + ui.session.state().session).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString();
        fixture.setString("session", fixtureSession);
        fixture.setBoolean("blueprintPreview", false);
        fixture.setBoolean("presetBrowseForPlacement", false);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("virtualPlacement", false);
        fixture.setString("openPurpose", "");
        fixture.setLong("placementReviewNonce", 0);
        fixture.setInteger("job", 0);
        UiMemory previousMemory = HologramUiMemory.UI_MEMORY()
            .remove(fixtureSession);
        GuiScreen caller = ui.host.minecraft().currentScreen;
        boolean previousCapture = this.captureRenderSmokeAction;
        this.captureRenderSmokeAction = true;
        try {
            HologramScreen probe = new HologramScreen(new HologramState(fixture));
            probe.ui.diagnostics.captureRenderSmokeAction = true;
            ui.host.minecraft()
                .displayGuiScreen(probe);
            probe.drawScreen(0, 0, 0);
            int[] structureTab = probe.ui.diagnostics.smokeControlPoint(148),
                blueprintTab = probe.ui.diagnostics.smokeControlPoint(149);
            if (structureTab[1] != blueprintTab[1] || structureTab[0] >= blueprintTab[0])
                throw new IllegalStateException("Structure and blueprint tabs are not horizontal peers");
            boolean clicked = false;
            for (int y = HologramUiCanvas.viewportY + 50; y
                < HologramUiCanvas.viewportY + HologramUiCanvas.viewportHeight - 80 && !clicked; y += 2)
                for (int x = HologramUiCanvas.viewportX + 10; x
                    < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 10; x += 2) {
                        int hit = HologramRenderer.pick(x, y);
                        if (hit < 0 || !HologramRenderer.pickStructure(x, y)
                            .isEmpty()) continue;
                        HologramState.Cell cell = probe.ui.session.state().cells.get(hit);
                        if (cell.missingBlock || cell.sourceMissing) continue;
                        probe.ui.diagnostics.smokeCapturePreviewSelection(x, y, false, false);
                        if (probe.ui.preview.selection()
                            .size() > 0) {
                            clicked = true;
                            break;
                        }
                    }
            if (!clicked) throw new IllegalStateException("Tab persistence smoke never selected a real preview face");
            int[] selected = probe.ui.preview.selection()
                .indices();
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(149);
            if (!probe.ui.library.libraryDrawer())
                throw new IllegalStateException("Peer blueprint tab did not open the library");
            probe.drawScreen(0, 0, 0);
            NBTTagList rows = fixture.getTagList("presets", 10);
            if (rows.tagCount() == 0)
                throw new IllegalStateException("Library row smoke requires a real saved blueprint");
            int[] rowPoint = probe.ui.diagnostics.smokeControlPoint(14000);
            probe.drawScreen(rowPoint[0], rowPoint[1], 0);
            if (probe.ui.canvas.tooltip() == null || !probe.ui.canvas.tooltip()
                .toString()
                .contains(
                    rows.getCompoundTagAt(0)
                        .getString("name")))
                throw new IllegalStateException("Flat library row hover lost its blueprint name");
            for (Control control : probe.ui.canvas.controls()) if (control.id == 14000 && control.h != 28)
                throw new IllegalStateException("Library entries reverted to action-button geometry");
            probe.ui.diagnostics.smokeClickDrawnControl(14000);
            if (probe.ui.diagnostics.capturedRenderSmokeAction == null
                || !"previewPreset".equals(probe.ui.diagnostics.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("Flat list mouse selection did not request its blueprint preview");
            fixture.setString(
                "selectedPresetId",
                rows.getCompoundTagAt(0)
                    .getString("id"));
            fixture.setLong("uiSequence", probe.ui.session.sequence());
            probe.updateState(new HologramState(fixture));
            int[] namePoint = probe.ui.diagnostics.smokePreviewPoint(HologramScreenLayout.COMPACT_PANEL_X + 18, 84);
            probe.ui.router.mouseClicked(namePoint[0], namePoint[1], 0);
            String before = probe.ui.library.presetNameField()
                .getText();
            probe.ui.router.keyTyped('e', Keyboard.KEY_E);
            if (ui.host.minecraft().currentScreen != probe || probe.ui.library.presetNameField()
                .getText()
                .equals(before))
                throw new IllegalStateException("Focused E closed the library instead of entering its name field");
            probe.ui.library.presetNameField()
                .setFocused(false);
            probe.ui.router.keyTyped('\0', Keyboard.KEY_ESCAPE);
            ui.diagnostics.smokeAssertRootClosed();
            HologramScreen reopened = new HologramScreen(new HologramState(fixture));
            reopened.ui.diagnostics.captureRenderSmokeAction = true;
            ui.host.minecraft()
                .displayGuiScreen(reopened);
            if (!reopened.ui.library.libraryDrawer() || !java.util.Arrays.equals(
                selected,
                reopened.ui.preview.selection()
                    .indices()))
                throw new IllegalStateException("Esc library exit lost the focused tab or local face selection");
            reopened.ui.router.keyTyped('e', Keyboard.KEY_E);
            ui.diagnostics.smokeAssertRootClosed();
            HologramScreen again = new HologramScreen(new HologramState(fixture));
            again.ui.diagnostics.captureRenderSmokeAction = true;
            ui.host.minecraft()
                .displayGuiScreen(again);
            fixture.setBoolean("blueprintPreview", false);
            again.updateState(new HologramState(fixture));
            if (!again.ui.library.libraryDrawer() || !java.util.Arrays.equals(
                selected,
                again.ui.preview.selection()
                    .indices()))
                throw new IllegalStateException("E exit or an ordinary snapshot changed the remembered library tab");
            fixture.setBoolean("virtualPlacement", true);
            fixture.setBoolean("virtualLocked", true);
            fixture.setBoolean("blueprintAwaitingConfirmation", true);
            fixture.setString("openPurpose", "placementReview");
            fixture.setLong("placementReviewNonce", 1);
            again.updateState(new HologramState(fixture));
            again.drawScreen(0, 0, 0);
            if (!again.ui.library.libraryDrawer() || !again.ui.review.confirmation())
                throw new IllegalStateException("Placement review changed the remembered tab or bypassed materials");
            NBTTagCompound beforeCancel = (NBTTagCompound) again.ui.session.state().data.copy();
            NBTTagCompound beforeCancelAction = again.ui.diagnostics.capturedRenderSmokeAction;
            if (again.ui.identity.deidentificationDrawer()) {
                again.ui.diagnostics.smokeControlPoint(212);
                again.ui.diagnostics.smokeControlPoint(213);
                again.ui.router.keyTyped('\0', Keyboard.KEY_ESCAPE);
                if (again.ui.identity.deidentificationDrawer() || !again.ui.review.confirmation()
                    || !again.ui.library.libraryDrawer()
                    || !beforeCancel.equals(again.ui.session.state().data)
                    || again.ui.diagnostics.capturedRenderSmokeAction != beforeCancelAction)
                    throw new IllegalStateException(
                        "Identity cancellation changed the source, review nonce, pending materials or operation");
                again.drawScreen(0, 0, 0);
                again.ui.diagnostics.smokeControlPoint(55);
            }
            again.ui.router.keyTyped('\0', Keyboard.KEY_ESCAPE);
            again.drawScreen(0, 0, 0);
            if (again.ui.review.confirmation() || again.ui.identity.deidentificationDrawer()
                || !again.ui.library.libraryDrawer()
                || !beforeCancel.equals(again.ui.session.state().data)
                || again.ui.diagnostics.capturedRenderSmokeAction != beforeCancelAction)
                throw new IllegalStateException("Material cancellation changed the source, review nonce or operation");
            again.ui.diagnostics.smokeClickDrawnControl(149);
            again.updateState(new HologramState(fixture));
            if (!again.ui.library.libraryDrawer())
                throw new IllegalStateException("The same review nonce forced a user-selected tab back to structure");
            return "peer-tabs=horizontal flat-row=hover,select focused-E=typed Esc/E=root-close tab+selection=preserved review=materials+tab-preserved";
        } finally {
            ui.host.minecraft()
                .displayGuiScreen(caller);
            this.captureRenderSmokeAction = previousCapture;
            if (previousMemory == null) HologramUiMemory.UI_MEMORY()
                .remove(fixtureSession);
            else HologramUiMemory.UI_MEMORY()
                .put(fixtureSession, previousMemory);
        }
    }

    void smokeAssertRootClosed() {
        if (ui.host.minecraft().currentScreen instanceof HologramScreen
            || ui.host.minecraft().theWorld != null && ui.host.minecraft().currentScreen != null)
            throw new IllegalStateException("Esc/E did not close the root hologram UI");
    }

    String smokePlacementReviewContext() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")
            && !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke"))
            throw new IllegalStateException("UI smoke disabled");
        if (!ui.review.placementReviewActive() || !ui.review.confirmation())
            throw new IllegalStateException("Repeated right-click did not open the placement precheck");
        ui.router.drawScreen(0, 0, 0);
        ui.diagnostics.smokeControlPoint(55);
        ui.diagnostics.smokeControlPoint(168);
        ui.diagnostics.smokeControlPoint(169);
        boolean force = HologramPlacementConfirmation
            .forceReview(ui.session.state().data, ui.review.confirmedRevision());
        if (!force && (!ui.review.lastConfirmationTitle()
            .contains("蓝图放置预检") || !"确认放置".equals(ui.review.lastConfirmationAction())))
            throw new IllegalStateException("Placement review lost its distinct title or direct confirmation action");
        long previousSequence = ui.session.sequence();
        ui.diagnostics.smokeClickDrawnControl(168);
        ui.router.drawScreen(0, 0, 0);
        ui.diagnostics.smokeClickDrawnControl(172);
        if (ui.nbt.nbtDrawer() || !ui.review.confirmation()
            || !ui.review.placementReviewActive()
            || ui.session.sequence() != previousSequence)
            throw new IllegalStateException(
                "Cancelling NBT adjustment changed the placement authority or fixed position");
        ui.router.drawScreen(0, 0, 0);
        ui.diagnostics.smokeAssertPlacementConfirmationDispatch();
        return "purpose=" + ui.session.state().data.getString("openPurpose")
            + " tab="
            + (ui.library.libraryDrawer() ? "blueprints" : "structure")
            + " title="
            + ui.review.lastConfirmationTitle()
            + " fixed="
            + ui.review.placementPosition()
            + " primary="
            + ui.review.lastConfirmationAction()
            + " NBT/materials=direct NBT-cancel=locked primary-mouse=confirmBlueprintPlacement,both-tabs";
    }

    void smokeAssertPlacementConfirmationDispatch() {
        for (boolean blueprints : new boolean[] { false, true }) {
            HologramScreen probe = new HologramScreen(
                new HologramState((NBTTagCompound) ui.session.state().data.copy()));
            probe.ui.diagnostics.captureRenderSmokeAction = true;
            probe.ui.library.libraryDrawer(blueprints);
            // These background surfaces can remain selected while the modal covers their controls.
            probe.ui.config.roleDropdown(probe.ui.position.positionDrawer(probe.ui.config.sourceDrawer(true)));
            probe.setWorldAndResolution(ui.host.minecraft(), ui.host.screenWidth(), ui.host.screenHeight());
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(55);
            NBTTagCompound request = probe.ui.diagnostics.capturedRenderSmokeAction;
            if (request == null || !"confirmBlueprintPlacement".equals(request.getString("op"))
                || !ui.session.state().session.equals(request.getString("session"))
                || request.getLong("planRevision") != ui.session.revision()
                || request.getBoolean("forceExplicit")
                    != HologramPlacementConfirmation.forceReview(ui.session.state().data, ui.review.confirmedRevision())
                || probe.ui.library.libraryDrawer() != blueprints
                || probe.ui.review.confirmation()
                || probe.ui.session.pendingSequence() < 0)
                throw new IllegalStateException(
                    "Drawn placement confirmation did not submit the reviewed plan from "
                        + (blueprints ? "blueprints" : "structure")
                        + " tab: "
                        + request);
        }
    }

    String smokeConstructionScopeContext() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")
            && !Boolean.getBoolean("gtmb.hologram.realInteractionSmoke"))
            throw new IllegalStateException("UI smoke disabled");
        ui.router.drawScreen(0, 0, 0);
        boolean wholePlacement = ui.session.state().data.getBoolean("wholeBlueprintPlacement")
            || ui.session.state().data.getBoolean("virtualPlacement");
        int expected = !wholePlacement && ui.preview.selection()
            .size() > 0 ? 2 : 0;
        if (ui.config.configuration()
            .getInteger("scope") != expected)
            throw new IllegalStateException("Construction scope differs from the visible selection guidance");
        if (wholePlacement && !ui.review.lastFooterScope()
            .contains("整份蓝图"))
            throw new IllegalStateException("Placement precheck presented a hidden partial blueprint scope");
        if (!wholePlacement && ui.preview.selection()
            .size() > 0
            && (!ui.review.lastFooterScope()
                .contains(
                    "已选 " + ui.preview.selection()
                        .size() + " 格")
                || !ui.review.constructionScopeHint()
                    .contains("Ctrl+A")))
            throw new IllegalStateException(
                "Selected construction scope did not disclose its count or whole-selection shortcut");
        if (306 + ui.host.font().FONT_HEIGHT >= 318
            || 321 + ui.host.font().FONT_HEIGHT + 1 > HologramUiCanvas.DESIGN_H - 7)
            throw new IllegalStateException("Footer text overlaps another row or the window border");
        return "preview=" + ui.review.lastFooterScope()
            + " reviewed="
            + ui.review.lastReviewedScope()
            + " scope="
            + expected
            + " footer=306/321 contained status="
            + ui.review.completionSummary();
    }

    int[] smokePreviewPoint(int x, int y) {
        return new int[] { ui.canvas.originX() + Math.round(x * ui.canvas.scale()),
            ui.canvas.originY() + Math.round(y * ui.canvas.scale()) };
    }

    NBTTagCompound smokeCaptureAxisDrag(boolean ctrlAtStart, boolean ctrlAtRelease) {
        this.smokeCtrlSelection = ctrlAtStart;
        ui.router.drawScreen(0, 0, 0);
        for (int axis = 0; axis < 3; axis++) {
            double[] center = ui.preview.gizmo()
                .start(),
                end = ui.preview.gizmo()
                    .end(axis);
            int sx = (int) Math.round(center[0] + (end[0] - center[0]) * .65);
            int sy = (int) Math.round(center[1] + (end[1] - center[1]) * .65);
            int[] start = ui.diagnostics.smokePreviewPoint(sx, sy);
            if (!ui.canvas.inViewport(ui.canvas.localX(start[0]), ui.canvas.localY(start[1]))
                || ui.preview.previewBlockedAt(ui.canvas.localX(start[0]), ui.canvas.localY(start[1]))
                || ui.preview.gizmo()
                    .pick(ui.canvas.localX(start[0]), ui.canvas.localY(start[1])) != axis)
                continue;
            ui.router.mouseClicked(start[0], start[1], 0);
            if (ui.preview.gizmo()
                .axis() != axis) continue;
            int[] finish = ui.diagnostics.smokePreviewPoint(
                (int) Math.round(sx + (end[0] - center[0]) * .7),
                (int) Math.round(sy + (end[1] - center[1]) * .7));
            this.smokeCtrlSelection = ctrlAtRelease;
            ui.router.mouseClickMove(finish[0], finish[1], 0, 100);
            ui.router.mouseMovedOrUp(finish[0], finish[1], 0);
            if (this.capturedRenderSmokeAction == null
                || !"editMove".equals(this.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("Actual RGB axis drag did not submit an editMove packet");
            return (NBTTagCompound) this.capturedRenderSmokeAction.copy();
        }
        throw new IllegalStateException("No legal actual RGB axis hit region exercised by v004 mouse smoke");
    }

    String smokeProcessingFlowContext() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        HologramUiDiagnostics.smokeLibraryNavigationFences();
        NBTTagCompound fixture = (NBTTagCompound) ui.session.state().data.copy();
        fixture.setString("session", "processing-ui:" + ui.session.state().session);
        fixture.setString("openPurpose", "blueprintEdit");
        fixture.setBoolean("virtual", true);
        fixture.setBoolean("blueprintSaved", false);
        fixture.setBoolean("blueprintDirty", true);
        fixture.setBoolean("blueprintProcessingRequired", true);
        fixture.setBoolean("blueprintProcessingComplete", false);
        fixture.setBoolean("processingResumeAllowed", false);
        fixture.setInteger("deidentificationMinimumLevel", 6);
        fixture.getCompoundTag("processing")
            .setInteger("minimumLevel", 0);
        if (!fixture.hasKey("processing")) {
            NBTTagCompound processing = new NBTTagCompound();
            processing.setInteger("minimumLevel", 0);
            fixture.setTag("processing", processing);
        }
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("virtualPlacement", false);
        fixture.setBoolean("virtualLocked", false);
        fixture.setBoolean("areaImporting", false);
        fixture.setBoolean("settingsOnly", false);
        fixture.setInteger("job", 0);
        fixture.setString("virtualPresetName", "Processing UI Blueprint");
        NBTTagList rows = new NBTTagList();
        for (int i = 0; i < 8; i++) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", "processing-row-" + i);
            row.setString("name", "Processing target " + i);
            rows.appendTag(row);
        }
        fixture.setTag("presets", rows);
        fixture.setString("selectedPresetId", "processing-row-0");
        HologramScreen probe = new HologramScreen(new HologramState(fixture));
        probe.setWorldAndResolution(ui.host.minecraft(), ui.host.screenWidth(), ui.host.screenHeight());
        probe.ui.diagnostics.captureRenderSmokeAction = true;
        try {
            probe.drawScreen(0, 0, 0);
            java.util.Map<Integer, Integer> visibleControlCounts = new java.util.HashMap<>();
            for (Control control : probe.ui.canvas.controls())
                visibleControlCounts.put(control.id, visibleControlCounts.getOrDefault(control.id, 0) + 1);
            if (visibleControlCounts.containsKey(130) || visibleControlCounts.containsKey(132)
                || visibleControlCounts.getOrDefault(140, 0) != 1
                || visibleControlCounts.getOrDefault(227, 0) != 1
                || visibleControlCounts.getOrDefault(166, 0) != 1)
                throw new IllegalStateException(
                    "Blueprint page retained a legacy shortcut or duplicate save/prompt/NBT control");
            if (probe.ui.diagnostics.drawnControlEnabled(140) || probe.ui.diagnostics.drawnControlEnabled(166))
                throw new IllegalStateException("Processing gate exposed saving or NBT early");
            probe.ui.diagnostics.smokeClickDrawnControl(220);
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(231);
            if (probe.ui.processing.processingFlow().stage != 0)
                throw new IllegalStateException("Next advanced an untouched stage");
            probe.ui.diagnostics.smokeClickDrawnControl(233);
            probe.drawScreen(0, 0, 0);
            if (!probe.ui.diagnostics.drawnControlEnabled(231) || probe.ui.processing.processingFlow()
                .complete()
                || probe.ui.processing.processingFlow()
                    .policy()
                    .getTagList("confirmedStages", 8)
                    .tagCount() != 0)
                throw new IllegalStateException(
                    "Sharing inclination did not offer defaults or implicitly confirmed stages");
            int[] choices = { 235, 240, 261, 270, 280, 291 };
            for (int stage = 0; stage < choices.length; stage++) {
                probe.drawScreen(0, 0, 0);
                if (probe.ui.processing.processingFlow().stage != stage || probe.ui.processing.processingFlow()
                    .policy()
                    .getTagList("confirmedStages", 8)
                    .tagCount() != stage)
                    throw new IllegalStateException("Stage bypassed its own Next confirmation: " + stage);
                if (stage == 1 && (!probe.ui.diagnostics.drawnControlEnabled(240)
                    || HologramDeidentificationMemory.minimum(probe.ui.session.state().data) != 6))
                    throw new IllegalStateException(
                        "Author raw floor zero did not permit level zero independently of the processed placement floor six");
                probe.ui.diagnostics.smokeClickDrawnControl(choices[stage]);
                probe.drawScreen(0, 0, 0);
                if (!probe.ui.diagnostics.drawnControlEnabled(231))
                    throw new IllegalStateException("Explicit stage choice did not open Next");
                probe.ui.diagnostics.smokeClickDrawnControl(231);
            }
            NBTTagCompound process = probe.ui.diagnostics.capturedRenderSmokeAction;
            if (probe.ui.processing.processingFlow() != null || process == null
                || !"processBlueprint".equals(process.getString("op"))
                || process.getCompoundTag("policy")
                    .getTagList("confirmedStages", 8)
                    .tagCount() != 6
                || process.getCompoundTag("policy")
                    .getInteger("deidentificationLevel") != 0)
                throw new IllegalStateException("Six actual choices did not submit a complete processing policy");
            probe.drawScreen(0, 0, 0);
            if (probe.ui.diagnostics.drawnControlEnabled(140))
                throw new IllegalStateException("Saving opened before the server receipt");
            fixture.setLong("uiSequence", probe.ui.session.sequence());
            fixture.setBoolean("blueprintProcessingComplete", true);
            fixture.setBoolean("blueprintDirty", false);
            probe.updateState(new HologramState(fixture));
            probe.drawScreen(0, 0, 0);
            if (!probe.ui.diagnostics.drawnControlEnabled(140) || !probe.ui.diagnostics.drawnControlEnabled(166))
                throw new IllegalStateException("Server success did not open saving and NBT");
            probe.ui.diagnostics.capturedRenderSmokeAction = null;
            double beforeYaw = probe.ui.preview.yaw();
            int beforeFacing = probe.ui.session.state().data.getInteger("facing");
            probe.ui.diagnostics.smokeClickDrawnControl(223);
            if (probe.ui.preview.yaw() == beforeYaw
                || probe.ui.session.state().data.getInteger("facing") != beforeFacing
                || probe.ui.diagnostics.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Viewing rotation changed the construction plan");
            fixture.setBoolean("blueprintDirty", true);
            probe.updateState(new HologramState(fixture));
            probe.ui.library.libraryOffset(4);
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(14004);
            if (!probe.ui.library.discardReview() || probe.ui.diagnostics.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Dirty row switch skipped its discard confirmation");
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(225);
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(222);
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(14004);
            if (!"processing-row-4".equals(probe.ui.library.overwriteTarget()) || probe.ui.library.libraryOffset() != 4
                || probe.ui.diagnostics.capturedRenderSmokeAction != null
                || probe.ui.library.libraryNavigation() != null)
                throw new IllegalStateException("Overwrite target selection loaded a blueprint or lost list offset");
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(140);
            if (!probe.ui.library.overwriteReview() || probe.ui.diagnostics.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Saving skipped the named overwrite confirmation");
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(224);
            if (!"saveBlueprintReplace".equals(probe.ui.diagnostics.capturedRenderSmokeAction.getString("op"))
                || !probe.ui.diagnostics.capturedRenderSmokeAction.getBoolean("confirmOverwrite")
                || !"processing-row-4".equals(probe.ui.diagnostics.capturedRenderSmokeAction.getString("presetId")))
                throw new IllegalStateException("Confirmed overwrite used the wrong target");
            fixture.setLong("uiSequence", probe.ui.session.sequence());
            fixture.setString("workspaceTab", "blueprint");
            fixture.setString("openPurpose", "");
            NBTTagCompound proposal = new NBTTagCompound();
            proposal.setString("op", "openController");
            proposal.setString("session", fixture.getString("session"));
            proposal.setLong("planRevision", fixture.getLong("planRevision"));
            proposal.setLong("sourceSwitchNonce", 701);
            proposal.setInteger("x", 17);
            proposal.setInteger("y", 72);
            proposal.setInteger("z", -9);
            proposal.setString("presetId", "server-held-controller");
            fixture.setTag("discardTargetAction", proposal);
            fixture.setBoolean("discardConfirmationRequired", true);
            probe.ui.diagnostics.capturedRenderSmokeAction = null;
            probe.updateState(new HologramState(fixture));
            probe.drawScreen(0, 0, 0);
            if (!probe.ui.library.discardReview() || !probe.ui.library.libraryDrawer()) throw new IllegalStateException(
                "A guarded world source did not preserve the authoritative blueprint workspace");
            probe.ui.diagnostics.smokeClickDrawnControl(225);
            if (!"cancelSourceSwitch".equals(probe.ui.diagnostics.capturedRenderSmokeAction.getString("op"))
                || probe.ui.diagnostics.capturedRenderSmokeAction.getLong("sourceSwitchNonce") != 701)
                throw new IllegalStateException("Cancel did not cancel the server-held source proposal");
            fixture.setLong("uiSequence", probe.ui.session.sequence());
            proposal.setString("op", "importArea");
            proposal.setLong("sourceSwitchNonce", 702);
            probe.updateState(new HologramState(fixture));
            probe.ui.diagnostics.capturedRenderSmokeAction = null;
            probe.drawScreen(0, 0, 0);
            probe.ui.diagnostics.smokeClickDrawnControl(224);
            NBTTagCompound sourceRequest = probe.ui.diagnostics.capturedRenderSmokeAction;
            if (sourceRequest == null || !"importArea".equals(sourceRequest.getString("op"))
                || !sourceRequest.getBoolean("confirmDiscard")
                || sourceRequest.getLong("sourceSwitchNonce") != 702
                || sourceRequest.getLong("planRevision") != proposal.getLong("planRevision")
                || sourceRequest.getInteger("x") != 17
                || sourceRequest.getInteger("y") != 72
                || sourceRequest.getInteger("z") != -9)
                throw new IllegalStateException("Confirm dropped the server-held source proposal context or nonce");
            return "six-next-confirmations=PASS author-raw-floor-zero-with-processed-six=PASS inclination-defaults-without-confirm=PASS save-NBT-server-gate=PASS view-only-rotation=PASS dirty-switch=PASS overwrite-target-only=PASS scroll-preserved=PASS world-source-confirm-cancel-context=PASS";
        } finally {
            probe.ui.diagnostics.captureRenderSmokeAction = false;
        }
    }

    static String smokeLibraryNavigationFences() {
        Object world = new Object();
        NBTTagCompound source = new NBTTagCompound();
        source.setString("session", "library-source");
        source.setString("generation", "library-source");
        source.setLong("planRevision", 17);
        source.setInteger("dimension", 0);
        HologramState original = new HologramState(source);
        String local = "local_00000000-0000-0000-0000-000000000001";
        LibraryNavigation navigation = new LibraryNavigation(original, world, local, true, "blueprintBrowse", 3);
        NBTTagCompound heartbeat = (NBTTagCompound) source.copy();
        heartbeat.setLong("uiSequence", 100);
        heartbeat.setBoolean("blueprintOperationSuccess", false);
        HologramState stillSource = new HologramState(heartbeat);
        if (!navigation.source(stillSource, world, false) || navigation.destination(stillSource)
            || navigation.offset != 3)
            throw new IllegalStateException(
                "An unrelated or failed same-session reply consumed the library navigation");
        NBTTagCompound target = (NBTTagCompound) source.copy();
        target.setString("session", "library-target");
        target.setString("generation", "library-target");
        target.setBoolean("virtual", true);
        target.setBoolean("open", true);
        target.setString("openPurpose", "blueprintBrowse");
        target.setString("libraryLocalId", local);
        HologramState loaded = new HologramState(target);
        if (!navigation.source(stillSource, world, false) || !navigation.destination(loaded))
            throw new IllegalStateException("The explicitly selected private target lost its cross-session handoff");
        for (String mismatch : new String[] { "target", "generation", "purpose", "placement", "dimension" }) {
            NBTTagCompound wrong = (NBTTagCompound) target.copy();
            if ("target".equals(mismatch)) wrong.setString("libraryLocalId", "local-other");
            else if ("generation".equals(mismatch)) wrong.setString("generation", "stale-generation");
            else if ("purpose".equals(mismatch)) wrong.setString("openPurpose", "blueprintEdit");
            else if ("placement".equals(mismatch)) wrong.setBoolean("virtualPlacement", true);
            else wrong.setInteger("dimension", 1);
            if (navigation.destination(new HologramState(wrong)))
                throw new IllegalStateException("Unrelated library destination accepted: " + mismatch);
        }
        NBTTagCompound changed = (NBTTagCompound) source.copy();
        changed.setLong("planRevision", 18);
        if (navigation.source(new HologramState(changed), world, false)
            || navigation.source(original, new Object(), false)
            || navigation.source(original, world, true))
            throw new IllegalStateException("A changed source, world or library view reused an old navigation");
        changed = (NBTTagCompound) source.copy();
        changed.setString("generation", "replaced-generation");
        if (navigation.source(new HologramState(changed), world, false))
            throw new IllegalStateException("A replaced source generation reused an old navigation");
        navigation.cancel();
        if (navigation.source(original, world, false) || navigation.destination(loaded))
            throw new IllegalStateException("A cancelled or consumed library handoff accepted a delayed target");
        String publicId = "public_00000000-0000-0000-0000-000000000002";
        LibraryNavigation publicNavigation = new LibraryNavigation(
            original,
            world,
            publicId,
            false,
            "blueprintEdit",
            3);
        target.setString("selectedPresetId", publicId);
        target.setString("openPurpose", "blueprintEdit");
        if (!publicNavigation.source(original, world, true) || !publicNavigation.destination(new HologramState(target)))
            throw new IllegalStateException("The explicitly selected public edit target lost its scoped handoff");
        target.setString("selectedPresetId", "public-other");
        if (publicNavigation.destination(new HologramState(target)))
            throw new IllegalStateException("A different public target reused the library navigation");
        return "library-explicit-target-handoff=PASS heartbeat-retained=PASS source-world-generation-fences=PASS cancel-consumed=PASS";
    }

    String smokeCaptureLibraryWorkflow() {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        NBTTagCompound fixture = (NBTTagCompound) ui.session.state().data.copy();
        fixture.setString("session", "smoke-library:" + ui.session.state().session);
        fixture.setString("openPurpose", "");
        fixture.setLong("placementReviewNonce", 0);
        fixture.setBoolean("virtual", true);
        fixture.setBoolean("settingsOnly", false);
        fixture.setBoolean("supported", true);
        fixture.setBoolean("blueprintPreview", true);
        fixture.setBoolean("presetBrowseForPlacement", true);
        fixture.setBoolean("blueprintAwaitingConfirmation", false);
        fixture.setBoolean("blueprintDirty", false);
        fixture.setBoolean("blueprintProcessingRequired", true);
        fixture.setBoolean("blueprintProcessingComplete", true);
        fixture.setString("virtualPresetId", "smoke-ui-entry");
        fixture.setString("virtualPresetName", "Smoke UI Blueprint");
        fixture.setString("selectedPresetId", "smoke-ui-entry");
        NBTTagCompound row = new NBTTagCompound();
        row.setString("id", "smoke-ui-entry");
        row.setString("name", "Smoke UI Blueprint");
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row);
        fixture.setTag("presets", rows);
        HologramScreen browse = new HologramScreen(new HologramState(fixture));
        browse.setWorldAndResolution(ui.host.minecraft(), ui.host.screenWidth(), ui.host.screenHeight());
        if (!browse.ui.library.libraryDrawer() || browse.ui.review.confirmation())
            throw new IllegalStateException("UI library entry incorrectly opened materials before choosing placement");
        browse.ui.diagnostics.captureRenderSmokeAction = true;
        try {
            browse.drawScreen(0, 0, 0);
            browse.ui.diagnostics.smokeClickDrawnControl(140);
            if (browse.ui.diagnostics.capturedRenderSmokeAction == null
                || !"saveBlueprintAs".equals(browse.ui.diagnostics.capturedRenderSmokeAction.getString("op"))
                || browse.ui.diagnostics.capturedRenderSmokeAction.hasKey("prompt"))
                throw new IllegalStateException(
                    "Save did not preserve server-authoritative prompt / original ID operation");
            fixture.setLong("uiSequence", browse.ui.session.sequence());
            fixture.setString("status", "保存失败：Smoke write failure");
            browse.updateState(new HologramState(fixture));
            if (!browse.ui.library.libraryDrawer() || !browse.ui.library.libraryResult()
                .contains("Smoke write failure"))
                throw new IllegalStateException("Save failure disappeared from the library");
            browse.drawScreen(0, 0, 0);
            browse.ui.diagnostics.smokeClickDrawnControl(140);
            if (!"saveBlueprintAs".equals(browse.ui.diagnostics.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("Save As did not request a distinct new library entry");
            fixture.setLong("uiSequence", browse.ui.session.sequence());
            fixture.setString("status", "Smoke saved as new blueprint");
            browse.updateState(new HologramState(fixture));
            browse.drawScreen(0, 0, 0);
            browse.ui.diagnostics.capturedRenderSmokeAction = null;
            browse.ui.diagnostics.smokeClickDrawnControl(175);
            browse.drawScreen(0, 0, 0);
            if (!"smoke-ui-entry".equals(browse.ui.library.deletePresetId())
                || browse.ui.diagnostics.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Delete bypassed the named confirmation");
            browse.ui.diagnostics.smokeClickDrawnControl(177);
            if (!browse.ui.library.deletePresetId()
                .isEmpty() || browse.ui.diagnostics.capturedRenderSmokeAction != null)
                throw new IllegalStateException("Delete cancellation emitted an operation");
            browse.drawScreen(0, 0, 0);
            browse.ui.diagnostics.smokeClickDrawnControl(175);
            browse.drawScreen(0, 0, 0);
            browse.ui.diagnostics.smokeClickDrawnControl(176);
            if (!"deletePreset".equals(browse.ui.diagnostics.capturedRenderSmokeAction.getString("op"))
                || !"smoke-ui-entry".equals(browse.ui.diagnostics.capturedRenderSmokeAction.getString("presetId")))
                throw new IllegalStateException("Confirmed delete did not target the reviewed library ID");
            fixture.setLong("uiSequence", browse.ui.session.sequence());
            fixture.setString("status", "删除失败：Smoke delete failure");
            browse.updateState(new HologramState(fixture));
            if (!browse.ui.library.libraryDrawer() || !browse.ui.library.libraryResult()
                .contains("Smoke delete failure"))
                throw new IllegalStateException("Delete failure disappeared from the library");
            browse.drawScreen(0, 0, 0);
            browse.ui.diagnostics.capturedRenderSmokeAction = null;
            browse.ui.diagnostics.smokeClickDrawnControl(141);
            if (browse.ui.diagnostics.capturedRenderSmokeAction == null
                || !"choosePlacementPreset".equals(browse.ui.diagnostics.capturedRenderSmokeAction.getString("op")))
                throw new IllegalStateException("Library Place did not choose the reviewed blueprint");
            fixture.setBoolean("blueprintPreview", false);
            fixture.setBoolean("presetBrowseForPlacement", false);
            fixture.setBoolean("virtualPlacement", true);
            fixture.setBoolean("virtualLocked", false);
            HologramScreen ghost = new HologramScreen(new HologramState(fixture));
            ghost.setWorldAndResolution(ui.host.minecraft(), ui.host.screenWidth(), ui.host.screenHeight());
            if (ghost.ui.review.confirmation())
                throw new IllegalStateException("Moving placement opened materials before its position was locked");
            fixture.setBoolean("virtualLocked", true);
            fixture.setBoolean("blueprintAwaitingConfirmation", true);
            HologramScreen locked = new HologramScreen(new HologramState(fixture));
            locked.setWorldAndResolution(ui.host.minecraft(), ui.host.screenWidth(), ui.host.screenHeight());
            if (!locked.ui.review.confirmation()
                || locked.ui.library.libraryDrawer() != ghost.ui.library.libraryDrawer())
                throw new IllegalStateException("Placement review did not retain its tab with mandatory materials");
            return "UI library Place=ghost locked=materials Save=original SaveAs=new Delete=reviewed-ID cancel=no-packet failures=visible";
        } finally {
            browse.ui.diagnostics.captureRenderSmokeAction = false;
        }
    }

    void smokeClickDrawnControl(int id) {
        int[] point = ui.diagnostics.smokeControlPoint(id);
        ui.router.mouseClicked(point[0], point[1], 0);
        ui.router.mouseMovedOrUp(point[0], point[1], 0);
    }

    String smokeRenderGizmoHover(String scenario) {
        if (!Boolean.getBoolean("gtmb.hologram.clientSmoke")) throw new IllegalStateException("Render smoke disabled");
        boolean structure = !"gizmo-hover-hint".equals(scenario);
        boolean hint = !"gizmo-hover-structure".equals(scenario);
        for (int y = HologramUiCanvas.viewportY + 20; y
            < HologramUiCanvas.viewportY + HologramUiCanvas.viewportHeight - 20; y++) {
            for (int x = HologramUiCanvas.viewportX + 20; x
                < HologramUiCanvas.viewportX + HologramUiCanvas.viewportWidth - 20; x++) {
                int mouseX = ui.canvas.originX() + Math.round(x * ui.canvas.scale()),
                    mouseY = ui.canvas.originY() + Math.round(y * ui.canvas.scale());
                int mx = ui.canvas.localX(mouseX), my = ui.canvas.localY(mouseY);
                if (ui.preview.gizmo()
                    .pick(mx, my) < 0) continue;
                String pickedStructure = HologramRenderer.pickStructure(mx, my);
                String pickedHint = HologramRenderer.hintAt(mx, my);
                if (structure != !pickedStructure.isEmpty() || hint != !pickedHint.isEmpty()) continue;
                ui.router.drawScreen(mouseX, mouseY, 0);
                List<String> expected = new ArrayList<>();
                expected.add("拖动轴移动；按住 Ctrl 开始拖动即复制；松开提交规划");
                if (hint) expected.addAll(
                    ui.host.font()
                        .listFormattedStringToWidth("提示区 " + pickedHint, 260));
                if (structure) expected.add("移动轴重叠结构 · 点击边缘整选");
                if (!expected.equals(ui.canvas.tooltip())) throw new IllegalStateException(
                    "Overlapping gizmo tooltip mismatch: " + ui.canvas.tooltip() + " expected " + expected);
                return "mouse=" + mx + "," + my + " tooltip=" + ui.canvas.tooltip();
            }
        }
        throw new IllegalStateException("No projected gizmo/overlay overlap for " + scenario);
    }

    String smokeLayout() {
        if (ui.canvas.controls()
            .isEmpty()) throw new IllegalStateException("Screen has not rendered controls");
        for (Control c : ui.canvas.controls())
            if (c.x < 0 || c.y < 0 || c.x + c.w > HologramUiCanvas.DESIGN_W || c.y + c.h > HologramUiCanvas.DESIGN_H)
                throw new IllegalStateException("Control outside design: " + c.id);
        return "gui=" + ui.host.screenWidth()
            + "x"
            + ui.host.screenHeight()
            + " design="
            + HologramUiCanvas.DESIGN_W
            + "x"
            + HologramUiCanvas.DESIGN_H
            + " scale="
            + ui.canvas.scale()
            + " viewport="
            + HologramUiCanvas.viewportWidth
            + "x"
            + HologramUiCanvas.viewportHeight
            + " cells="
            + ui.session.state().cells.size()
            + " controls="
            + ui.canvas.controls()
                .size();
    }

    boolean captureRenderSmokeAction() {
        return this.captureRenderSmokeAction;
    }

    NBTTagCompound capturedRenderSmokeAction(NBTTagCompound value) {
        this.capturedRenderSmokeAction = value;
        return value;
    }

    boolean smokeAltSelection() {
        return this.smokeAltSelection;
    }

    boolean smokeCtrlSelection() {
        return this.smokeCtrlSelection;
    }

    boolean smokeSameTypeSelection() {
        return this.smokeSameTypeSelection;
    }

    /** Native test locator: reflection follows the owning module after the screen was decomposed. */
    static Object diagnosticFieldOwner(Object target, String name) {
        if (!(target instanceof HologramScreen)) return target;
        HologramUiModules modules = ((HologramScreen) target).ui;
        switch (name) {
            case "AE_REVIEW_GATE":
            case "aeDetailOffset":
            case "aePreviewTransition":
            case "aeReview":
            case "aeReviewGroup":
            case "aeReviewNavigation":
            case "aeReviewOffset":
            case "aeReviewSelected":
            case "aeReviewTab":
            case "awaitingAePreview":
                return modules.ae;
            case "annotationMarkers":
            case "annotationState":
            case "pinnedAnnotation":
            case "pinnedAnnotationOffset":
            case "promptDrawer":
            case "promptField":
            case "promptSavedSummary":
            case "promptScope":
            case "promptScopeSummary":
            case "promptSummaryState":
            case "summaryScope":
            case "versionsDrawer":
            case "versionsOffset":
            case "visibleMarkers":
                return modules.annotations;
            case "networkBindingNavigation":
            case "networkDrawer":
            case "networkGroupIndex":
            case "networkGroupOffset":
            case "networkHintField":
            case "networkRebindSelected":
                return modules.bindings;
            case "DESIGN_H":
            case "DESIGN_W":
            case "compactControls":
            case "controls":
            case "itemRenderer":
            case "originX":
            case "originY":
            case "panelX":
            case "panelY":
            case "renderGeneration":
            case "scale":
            case "tooltip":
            case "viewportHeight":
            case "viewportWidth":
            case "viewportX":
            case "viewportY":
                return modules.canvas;
            case "basicConfigurationRevision":
            case "basicConfigurationSequence":
            case "channelNameField":
            case "channelValueField":
            case "drawer":
            case "drawerOffset":
            case "hatchDrawer":
            case "hatchFilterTier":
            case "hatchOffset":
            case "hatchRole":
            case "mainField":
            case "optionChannel":
            case "optionValueField":
            case "presetDowngrade":
            case "presetDrawer":
            case "presetTier":
            case "resetCalibration":
            case "resetReview":
            case "roleDropdown":
            case "roleOffset":
            case "sourceDrawer":
                return modules.config;
            case "captureRenderSmokeAction":
            case "capturedRenderSmokeAction":
            case "smokeAltSelection":
            case "smokeCtrlSelection":
            case "smokeSameTypeSelection":
                return modules.diagnostics;
            case "deidentificationDrawer":
            case "deidentificationLevel":
            case "deidentificationPlacement":
            case "offeredDeidentificationNonce":
                return modules.identity;
            case "blueprintTab":
            case "deferredLibraryAction":
            case "deletePresetId":
            case "deletePresetName":
            case "discardConfirmed":
            case "discardReview":
            case "keepLibraryOffset":
            case "libraryAdminDrawer":
            case "libraryConfigRevision":
            case "libraryDownloadField":
            case "libraryDrawer":
            case "libraryNavigation":
            case "libraryOffset":
            case "libraryQuotaField":
            case "libraryResult":
            case "libraryUploadField":
            case "overwriteMode":
            case "overwriteName":
            case "overwriteReview":
            case "overwriteTarget":
            case "presetNameField":
            case "presetSaveSequence":
            case "publicLibrary":
            case "sourceSwitchProposal":
                return modules.library;
            case "GUIDANCE_MEMORY":
            case "UI_MEMORY":
            case "basicConfigured":
            case "cellEditHint":
            case "initialEditHint":
            case "savedEditHint":
            case "structureVisited":
            case "tabMemory":
                return modules.memory;
            case "nbtCellScope":
            case "nbtDrawer":
            case "nbtIgnoredPaths":
            case "nbtOffset":
            case "nbtPathsView":
            case "nbtPlacement":
            case "nbtSelection":
            case "nbtValueField":
            case "nbtValueIndex":
                return modules.nbt;
            case "positionDrawer":
            case "positionXField":
            case "positionYField":
            case "positionZField":
                return modules.position;
            case "allLayers":
            case "boxAdditive":
            case "boxSelecting":
            case "boxStart":
            case "camera":
            case "cameraFrame":
            case "dragX":
            case "dragY":
            case "dragging":
            case "editQueue":
            case "emptyVoxelPreview":
            case "gizmo":
            case "gizmoCopy":
            case "hovered":
            case "inventory":
            case "inventoryDragging":
            case "inventorySlot":
            case "layer":
            case "moveHandles":
            case "moved":
            case "pendingSelectionSequence":
            case "pitch":
            case "queuedDispatchSequence":
            case "selected":
            case "selection":
            case "sourceChoice":
            case "sourceOffset":
            case "sourceTargets":
            case "view":
            case "yaw":
            case "zoom":
                return modules.preview;
            case "processingFlow":
            case "processingFullReview":
            case "processingPresetField":
            case "processingPresetLoadSequence":
            case "processingPresetSaveReview":
            case "retainCovers":
            case "retainNbt":
            case "selectedProcessingPreset":
                return modules.processing;
            case "confirmation":
            case "confirmedRevision":
            case "lastCacheReviewSummary":
            case "lastConfirmationAction":
            case "lastConfirmationBounds":
            case "lastConfirmationTitle":
            case "lastEmptyCacheReview":
            case "lastFooterScope":
            case "lastReviewedScope":
            case "materialOffset":
            case "pendingBuildReview":
            case "pendingBuildReviewRevision":
            case "placementReviewContext":
            case "resourceReview":
            case "reviewedCacheQuote":
            case "seenPlacementReviewNonce":
                return modules.review;
            case "awaitingScan":
            case "notice":
            case "openedPurpose":
            case "pendingSequence":
            case "sequence":
            case "state":
                return modules.session;
            case "captureBinding":
            case "keyBindings":
            case "shortcutsDrawer":
                return modules.shortcuts;
            default:
                return target;
        }
    }

    static Object diagnosticMethodOwner(Object target, String name) {
        if (!(target instanceof HologramScreen)) return target;
        HologramUiModules modules = ((HologramScreen) target).ui;
        switch (name) {
            case "activateAeReview":
            case "aeIssueRows":
            case "aePage":
            case "aePreviewPayload":
            case "aeRowCount":
            case "beginAeReviewNavigation":
            case "canOpenAePreview":
            case "cancelAeReviewNavigation":
            case "completeAeReviewNavigation":
            case "drawAeReview":
            case "drawAeReviewNavigation":
            case "finishAeReviewNavigation":
            case "hasPendingAeReviewNavigation":
            case "openAePreview":
            case "receiveAeReview":
                return modules.ae;
            case "annotationInteractive":
            case "drawAnnotations":
            case "drawPromptEditor":
            case "drawVersions":
            case "toggleAnnotationAt":
                return modules.annotations;
            case "activateNetworkBinding":
            case "drawNetworkBindings":
            case "loadNetworkHintField":
            case "networkGroups":
            case "revealPendingNetworkGroup":
            case "selectedNetworkGroup":
                return modules.bindings;
            case "button":
            case "compactMouseX":
            case "controlHelp":
            case "disabledReason":
            case "drawItem":
            case "inPanel":
            case "inViewport":
            case "localX":
            case "localY":
            case "skin":
            case "text":
                return modules.canvas;
            case "applyRawChannel":
            case "availableTiers":
            case "canStep":
            case "candidateNote":
            case "capabilities":
            case "capability":
            case "chooseOption":
            case "configuration":
            case "configure":
            case "directionName":
            case "drawChannels":
            case "drawConfig":
            case "drawFooter":
            case "drawGradeSelector":
            case "drawHatches":
            case "drawOptions":
            case "drawPreset":
            case "drawResetReview":
            case "drawRoleDropdown":
            case "drawSources":
            case "drawTierSelector":
            case "facingName":
            case "filteredHatches":
            case "hatchCandidateCapability":
            case "hatchPrototype":
            case "hatchRoles":
            case "optionIndex":
            case "optionLabel":
            case "primaryCapability":
            case "requestSelectedDetails":
            case "roleLabel":
            case "selectFilteredTarget":
            case "selectedStructureId":
            case "sourceEnabled":
            case "stableGradeCapability":
            case "structureEnabled":
            case "syncTargetSelectors":
            case "tierName":
                return modules.config;
            case "drawnControlEnabled":
            case "requireInteractionSmoke":
            case "smokeAeReviewGui":
            case "smokeAeUiState":
            case "smokeAeWarningMarkers":
            case "smokeAreaImportUiState":
            case "smokeAssertPlacementConfirmationDispatch":
            case "smokeAssertRootClosed":
            case "smokeBinding":
            case "smokeCaptureAxisDrag":
            case "smokeCaptureLibraryWorkflow":
            case "smokeCaptureLocalSelection":
            case "smokeCaptureMissingBuildClick":
            case "smokeCapturePreviewSelection":
            case "smokeCaptureV004Editor":
            case "smokeCaptureV004Tabs":
            case "smokeClickAt":
            case "smokeClickControl":
            case "smokeClickDrawnControl":
            case "smokeConstructionScopeContext":
            case "smokeControlPoint":
            case "smokeHasControl":
            case "smokeKey":
            case "smokeLayout":
            case "smokeLibraryNavigationFences":
            case "smokeLibraryUiState":
            case "smokePanelBounds":
            case "smokePending":
            case "smokePlacementReviewContext":
            case "smokePreviewPoint":
            case "smokeProcessingFlowContext":
            case "smokeRenderGeneration":
            case "smokeRenderGizmoHover":
            case "smokeRenderScenario":
            case "smokeSelectedIndices":
            case "smokeSnapshot":
            case "smokeViewportBounds":
                return modules.diagnostics;
            case "activateDeidentification":
            case "deidentificationChoice":
            case "deidentificationSaveOptions":
            case "drawDeidentification":
            case "drawDeidentificationLibraryControl":
            case "offerPlacementDeidentification":
            case "openDeidentification":
                return modules.identity;
            case "acceptSourceSwitchProposal":
            case "acceptsLibraryNavigation":
            case "activateClientLibrary":
            case "applyOpeningPurpose":
            case "blueprintSaveOptions":
            case "blueprintSaveState":
            case "blueprintSource":
            case "cancelLibraryNavigation":
            case "drawBlueprintSwitchReview":
            case "drawBlueprintWorkbench":
            case "drawClientServerLibrary":
            case "drawDeleteReview":
            case "drawLibrary":
            case "drawLibraryAdmin":
            case "libraryAdminField":
            case "openLibraryAdmin":
            case "rememberLibraryNavigation":
            case "revealSelectedPreset":
            case "selectedPresetExists":
                return modules.library;
            case "contextualAccent":
            case "initialStructureSetupRequired":
            case "rememberBlueprintPlacement":
            case "rememberGuidance":
            case "rememberUi":
            case "rememberedSelection":
            case "selectedPositions":
                return modules.memory;
            case "availableNbtKeys":
            case "cellNbtRow":
            case "drawNbtEditor":
            case "editorNbtEntries":
            case "openNbtEditor":
                return modules.nbt;
            case "addPosition":
            case "drawPosition":
            case "positionField":
                return modules.position;
            case "airSelectionHeld":
            case "canInventoryPin":
            case "canMoveSelection":
            case "cancelQueuedEdits":
            case "cellName":
            case "clearSelection":
            case "controlSelectionHeld":
            case "dispatchEdit":
            case "displayedStack":
            case "drainQueuedEdits":
            case "drawEmptyVoxelPreview":
            case "drawGizmo":
            case "drawInventory":
            case "drawPreview":
            case "editAction":
            case "emptyVoxelAt":
            case "frontCameraYaw":
            case "handleEditorShortcut":
            case "hasSelectedPin":
            case "inventorySlotAt":
            case "movePreviewCamera":
            case "pickedTarget":
            case "pinInventory":
            case "previewBlockedAt":
            case "previewInteractive":
            case "resetCamera":
            case "sameTypeSelectionHeld":
            case "selectEmptyVoxel":
            case "sourceStack":
            case "statusName":
                return modules.preview;
            case "activateProcessing":
            case "canEditBlueprintNbt":
            case "coordinateDisplayMode":
            case "drawProcessingFlow":
            case "processingChoiceReady":
            case "processingList":
            case "processingReady":
            case "processingState":
            case "quantumGroupsConfirmed":
            case "quantumGroupsSaved":
            case "saveQuantumGroups":
                return modules.processing;
            case "completionSummary":
            case "constructionScopeHint":
            case "constructionScopeLabel":
            case "drawConfirmation":
            case "placementPosition":
            case "placementReviewActive":
            case "retainsPlacementReview":
                return modules.review;
            case "activate":
            case "closeRoot":
            case "doesGuiPauseGame":
            case "drawImportBusy":
            case "drawScreen":
            case "drawTabs":
            case "handleMouseInput":
            case "initGui":
            case "keyTyped":
            case "mouseClickMove":
            case "mouseClicked":
            case "mouseMovedOrUp":
            case "onGuiClosed":
            case "textFieldFocused":
            case "updateScreen":
                return modules.router;
            case "areaImporting":
            case "cellTag":
            case "editable":
            case "editingReason":
            case "phaseName":
            case "revision":
            case "sameTarget":
            case "selectedCell":
            case "send":
            case "updateState":
                return modules.session;
            case "drawShortcuts":
                return modules.shortcuts;
            default:
                return target;
        }
    }

    static Object readDiagnosticField(Object target, String name) throws ReflectiveOperationException {
        if (target instanceof HologramScreen && "pendingSequence".equals(name))
            return ((HologramScreen) target).ui.session.pendingSequence();
        Object owner = diagnosticFieldOwner(target, name);
        java.lang.reflect.Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    static void writeDiagnosticField(Object target, String name, Object value) throws ReflectiveOperationException {
        if (target instanceof HologramScreen && "pendingSequence".equals(name)) {
            ((HologramScreen) target).ui.session.pendingSequence(((Number) value).longValue());
            return;
        }
        Object owner = diagnosticFieldOwner(target, name);
        java.lang.reflect.Field field = owner.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    static Object invokeDiagnostic(Object target, String name, Class<?>[] signature, Object... arguments)
        throws ReflectiveOperationException {
        Object owner = diagnosticMethodOwner(target, name);
        java.lang.reflect.Method method = owner.getClass()
            .getDeclaredMethod(name, signature);
        method.setAccessible(true);
        return method.invoke(owner, arguments);
    }
}
