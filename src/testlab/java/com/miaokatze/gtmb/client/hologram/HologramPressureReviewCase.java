// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.WorldServer;

import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramPlacementUpload;
import com.miaokatze.gtmb.hologram.HologramPressureReviewAuthority;
import com.miaokatze.gtmb.hologram.HologramPressureReviewAuthority.Sample;

/** One frozen archive in the existing owned PostFocus case. No final confirmation command is allowed. */
final class HologramPressureReviewCase {

    static final String FLAG = "gtmb.testlab.localPreviewStagesPressureReview";
    private static final int CELLS = 123_369, FRAME = 16_384, OVERHEAD = 57, TOTAL_LIMIT = 24 * 1024 * 1024;

    private static final String HISTORICAL = "施工工具、位置或会话已变化，请重新确认";
    private static final String LOCAL_PANEL = "gtmb_local_material_review", SERVER_PANEL = "gtmb_material_confirmation";

    final Map<String, Object> evidence = new LinkedHashMap<>();
    private final WorldClient world;
    private final EntityClientPlayerMP player;
    private final NetHandlerPlayClient connection;
    private final ItemStack held;
    private final int slot;
    private final int[] anchor;
    private final Field clientRequest, receiver, flowGeneration, flowConsumed;
    private final Field frameId, frameTotal, frameIndex, frameDigest, frameData;
    private final Consumer<NBTTagCompound> delegate, observer;
    private volatile Sample server;
    private volatile String request = "", failure = "", cancelSession = "";
    private volatile long cancelRevision = -1, cancelNonce = -1;
    private volatile boolean accepted, rejected, armed, finished;
    private int frames, declared, payload, maximumWire, flow, consumed, resync, cancels, uploadCancels;
    private byte[] digest;
    private String blocked = "";

    @SuppressWarnings("unchecked")
    HologramPressureReviewCase(Minecraft mc, int[] anchor) throws Exception {
        world = mc.theWorld;
        player = mc.thePlayer;
        connection = mc.getNetHandler();
        held = player.getHeldItem();
        slot = player.inventory.currentItem;
        this.anchor = anchor.clone();
        require(current() && HologramClient.state == null
            && HologramLocalPlacement.INSTANCE.display().cells.size() == CELLS
            && Arrays.equals(anchor, HologramLocalPlacement.INSTANCE.anchor()),
            "Expected the locked 123369-cell archive and no previous server workspace");
        clientRequest = field(HologramLibraryClient.class, "placementRequest");
        receiver = field(HologramNetwork.class, "receiver");
        flowGeneration = field(HologramClient.class, "snapshotFlowGeneration");
        flowConsumed = field(HologramClient.class, "snapshotFlowConsumed");
        frameId = field(HologramPlacementUpload.Fragment.class, "id");
        frameTotal = field(HologramPlacementUpload.Fragment.class, "total");
        frameIndex = field(HologramPlacementUpload.Fragment.class, "index");
        frameDigest = field(HologramPlacementUpload.Fragment.class, "digest");
        frameData = field(HologramPlacementUpload.Fragment.class, "data");
        delegate = (Consumer<NBTTagCompound>) receiver.get(null);
        require(delegate != null, "Production State receiver missing");
        observer = tag -> {
            try {
                if (!request.isEmpty() && request.equals(tag.getString("placementRequestId"))
                    && tag.hasKey("placementRequestAccepted", 1) && genuineStateReceipt()) {
                    boolean ok = tag.getBoolean("placementRequestAccepted");
                    if (ok) accepted = true;
                    else {
                        failure = tag.getString("errorMessage");
                        rejected = true;
                    }
                }
            } finally {
                delegate.accept(tag);
            }
        };
        HologramNetwork.setClientReceiver(observer);
        evidence.put("cellsDisplayed", CELLS);
        evidence.put("anchor", this.anchor);
    }

    static boolean matchesFrozen(HologramBlueprintArtifact.Artifact value) {
        return "26cdd671942dbc5b1bfb4f15a84f892403d07900a0a789116d0f11bb2fca9108".equals(value.jsonSha256)
            && "c6cd94b55a1bdcfd52bad89f09563c24498e847a2ff7cab673c36c16a128e270".equals(value.pngSha256);
    }

    void observeServer(WorldServer exact, EntityPlayerMP actor, ItemStack tool, NBTTagList inventory) {
        require(player.getUniqueID().equals(actor.getUniqueID()), "The client and server fixture actors differ");
        server = HologramPressureReviewAuthority.sample(exact, actor, slot, tool, inventory, anchor);
    }

    boolean step(Minecraft mc) throws Exception {
        Sample s = server;
        require(current() && receiver.get(null) == observer, "Pressure connection, tool or receiver replaced");
        synchronized (this) {
            evidence.put("fragmentsSent", frames);
            evidence.put("compressedBytesSent", payload);
            evidence.put("compressedBytesDeclared", declared);
            evidence.put("largestC17PayloadBytes", maximumWire);
            evidence.put("blockedOutboundKind", blocked);
            evidence.put("resyncs", resync);
            require(blocked.isEmpty(), "Pressure no-pay fence blocked " + blocked);
        }
        if (s == null) return false;
        require(s.unchanged() && s.ready() && !s.aeActive() && s.job() == 0
            && (s.request().isEmpty() || s.request().equals(request)), "Unexpected server job, inventory or request");
        if (!armed) {
            require(s.session().isEmpty() && !s.preparing()
                && mc.currentScreen instanceof HologramLibraryMaterialReviewScreen,
                "The native local modal must own the explicit pressure submission");
            var local = (HologramLibraryMaterialReviewScreen) mc.currentScreen;
            var dialog = (HologramMaterialConfirmationDialog) field(HologramLibraryMaterialReviewScreen.class, "dialog").get(local);
            int[] where = dialog.controlPoint(55);
            if (where == null) return false;
            require(local.localSubmitAvailable(), "The local preflight button is disabled");
            armed = true;
            click(local, LOCAL_PANEL, where, 2);
            evidence.put("localPreflight55NativeHit", true);
            return false;
        }
        UUID pending = (UUID) clientRequest.get(HologramLibraryClient.INSTANCE);
        if (pending != null) require(bind(pending), "Unexpected queued replacement of the one pressure request");
        if (rejected) {
            require(!accepted && !failure.isEmpty(), "Conflicting or empty server refusal");
            if (s.preparing() || HologramLocalPlacement.INSTANCE.submitting()) return false;
            evidence.put("probeOutcome", HISTORICAL.equals(failure) ? "HISTORICAL_GUARD_TEXT" : "SERVER_REJECTED");
            evidence.put("serverError", failure);
            evidence.put("observedUiStatus", HologramLibraryClient.INSTANCE.status());
            return complete(s);
        }
        if (!accepted && pending == null && !HologramLocalPlacement.INSTANCE.submitting()) {
            String status = HologramLibraryClient.INSTANCE.status();
            if (status.startsWith("施工清单准备失败") || status.startsWith("施工上传失败")
                || status.startsWith("施工上传超时") || status.startsWith("服务器未确认施工上传")
                || status.startsWith("施工来源或投影仪已变化")) {
                if (s.preparing()) return false;
                evidence.put("probeOutcome", "CLIENT_PREPARATION_OR_TRANSPORT_REJECTED");
                evidence.put("observedUiStatus", status);
                return complete(s);
            }
        }
        if (!cancelSession.isEmpty()) {
            if (!(s.cancelled() && !s.quoted() && s.session().equals(cancelSession)
                && s.nonce() == cancelNonce + 1 && s.revision() == cancelRevision)) return false;
            synchronized (this) { require(cancels == 1, "Could not verify exactly one real Back54 command"); }
            evidence.put("probeOutcome", "QUOTE_THEN_CANCEL");
            evidence.put("cancelNonceBefore", cancelNonce);
            evidence.put("cancelNonceAfter", s.nonce());
            return complete(s);
        }
        if (!accepted || !s.quoted() || !(mc.currentScreen instanceof HologramScreen review)) return false;
        HologramState state = review.ui.session.state();
        require(request.equals(s.request()) && request.equals(state.data.getString("executionRequestId"))
            && s.session().equals(state.session) && s.revision() == state.data.getLong("planRevision")
            && s.nonce() == state.data.getLong("placementReviewNonce")
            && state.data.getBoolean("blueprintAwaitingConfirmation"), "Material review belongs to another request");
        int[] where = review.ui.review.materialControlPoint(54);
        if (where == null) return false;
        evidence.put("authoritativeQuoteCells", s.cells());
        evidence.put("authoritativeReviewSession", s.session());
        cancelRevision = s.revision();
        cancelNonce = s.nonce();
        cancelSession = s.session();
        click(review, SERVER_PANEL, where, -1);
        evidence.put("authoritativeBack54NativeHit", true);
        return false;
    }

    private synchronized boolean complete(Sample s) {
        finished = true;
        evidence.put("requestId", request);
        evidence.put("serverJob", s.job());
        evidence.put("serverInventoryUnchanged", s.unchanged());
        evidence.put("aeOrderActive", s.aeActive());
        return true;
    }

    synchronized boolean forward(Object msg, NBTTagCompound action) {
        if (!armed || finished || !current()) return false;
        try {
            if (msg instanceof HologramPlacementUpload.Fragment fragment) {
                UUID id = (UUID) frameId.get(fragment);
                UUID pending = (UUID) clientRequest.get(HologramLibraryClient.INSTANCE);
                if (pending == null || !id.equals(pending) || !bind(id) || accepted || rejected) return deny("foreignFragment");
                int total = frameTotal.getInt(fragment), index = frameIndex.getInt(fragment);
                byte[] hash = (byte[]) frameDigest.get(fragment), data = (byte[]) frameData.get(fragment);
                if (frames == 0) { declared = total; digest = hash.clone(); }
                if (total != declared || total <= 0 || total > TOTAL_LIMIT || index != frames || payload >= total
                    || hash.length != 32 || !Arrays.equals(digest, hash)
                    || data.length != Math.min(FRAME, total - payload)) return deny("invalidFragment");
                frames++;
                payload += data.length;
                maximumWire = Math.max(maximumWire, data.length + OVERHEAD);
                return true;
            }
            if (action == null) return deny("otherMessage");
            String op = action.getString("op");
            if ("cancelProjectedPlacement".equals(action.getString("action"))
                && onlyKeys(action, "action", "placementRequestId")
                && !request.isEmpty() && request.equals(action.getString("placementRequestId"))
                && !accepted && uploadCancels == 0) {
                uploadCancels++;
                return true;
            }
            if (action.hasKey("action") || request.isEmpty() || payload != declared || frames == 0)
                return deny("unapprovedAction");
            if ("snapshotFlow".equals(op) && action.hasKey("ready", 1)
                && onlyKeys(action, "op", "snapshotFlowGeneration", "ready")
                && sameFlowGeneration(action) && flow < 16) { flow++; return true; }
            if ("snapshotConsumed".equals(op) && action.hasKey("success", 1)
                && onlyKeys(action, "op", "snapshotFlowGeneration", "transportSequence", "success")
                && sameFlowGeneration(action) && action.hasKey("transportSequence", 4)
                && action.getLong("transportSequence") >= 0 && action.getLong("transportSequence") == flowConsumed.getLong(null)
                && consumed < 16) { consumed++; return true; }
            if ("requestFullSnapshot".equals(op) && resync == 0 && cancelSession.isEmpty()) {
                HologramState applied = HologramClient.state;
                if (applied != null && request.equals(applied.data.getString("executionRequestId"))
                    && applied.data.getBoolean("virtualLocked") && applied.data.getInteger("job") == 0
                    && applied.session.equals(action.getString("session")) && action.hasKey("planRevision", 4)
                    && action.getLong("planRevision") == applied.data.getLong("planRevision")
                    && onlyKeys(action, "op", "session", "planRevision", "sourceRevision", "semanticPlanRevision")) {
                    resync++;
                    return true;
                }
            }
            if ("cancelBlueprintPlacement".equals(op) && !cancelSession.isEmpty() && cancels == 0
                && cancelSession.equals(action.getString("session")) && action.hasKey("planRevision", 4)
                && action.getLong("planRevision") == cancelRevision) { cancels++; return true; }
            // Any build, AE, payment, second submission or other edit is categorically absent from the allowlist.
            return deny("unapprovedAction");
        } catch (ReflectiveOperationException | RuntimeException invalid) {
            return deny("unreadableEnvelope");
        }
    }

    synchronized boolean bind(UUID id) {
        if (request.isEmpty()) {
            request = id.toString();
            return true;
        }
        return request.equals(id.toString());
    }

    private boolean sameFlowGeneration(NBTTagCompound frame) throws ReflectiveOperationException {
        return frame.hasKey("snapshotFlowGeneration", 4) && frame.getLong("snapshotFlowGeneration") >= 0
            && frame.getLong("snapshotFlowGeneration") == flowGeneration.getLong(null);
    }

    private static boolean onlyKeys(NBTTagCompound message, String... own) {
        for (Object key : message.func_150296_c()) {
            if ("clientSnapshotProtocol".equals(key) || "clientSnapshotPalette".equals(key)
                || "clientSnapshotFlow".equals(key)) {
                if (!message.hasKey((String) key, 3)) return false;
            } else if (!Arrays.asList(own).contains(key)) return false;
        }
        return true;
    }

    private boolean deny(String kind) {
        if (blocked.isEmpty()) blocked = kind;
        return false;
    }

    void close() throws Exception {
        require(receiver.get(null) == observer, "Refusing to replace a different State receiver");
        HologramNetwork.setClientReceiver(delegate);
        evidence.put("normalStateReceiverRestored", receiver.get(null) == delegate);
    }

    private boolean current() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.theWorld == world && mc.thePlayer == player && connection != null && mc.getNetHandler() == connection
            && connection.getNetworkManager().isChannelOpen() && player.inventory.currentItem == slot
            && player.getHeldItem() == held && !player.isDead && !player.capabilities.isCreativeMode;
    }

    private static void click(HologramMuiScreen screen, String panelName, int[] at, int localIndex) {
        var mui = screen.getScreen();
        mui.getContext().updateState(at[0], at[1], 0);
        mui.onFrameUpdate();
        ModularPanel panel = mui.getPanelManager().getOpenPanels().stream()
            .filter(p -> panelName.equals(p.getName())).findFirst()
            .orElseThrow(() -> new IllegalStateException("Expected the attached native material panel"));
        if (localIndex >= 0) {
            var buttons = panel.getChildren().stream().filter(ButtonWidget.class::isInstance)
                .collect(java.util.stream.Collectors.toList());
            require(buttons.size() == 3 && panel.getHovering().stream().anyMatch(h -> h.getElement() == buttons.get(localIndex)),
                "The exact local Submit control did not own native input");
        } else require(panel.getHovering().stream().filter(h -> h.getElement() instanceof ButtonWidget<?>).count() == 1,
            "The authoritative Back button did not own native input");
        HologramMuiProbeInput.click(screen, at[0], at[1], 0);
    }

    private static boolean genuineStateReceipt() {
        for (StackTraceElement caller : Thread.currentThread().getStackTrace())
            if (HologramNetwork.ClientHandler.class.getName().equals(caller.getClassName())
                && "onMessage".equals(caller.getMethodName())) return true;
        return false;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void require(boolean condition, String cause) {
        if (!condition) throw new IllegalStateException(cause);
    }
}
