// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramBusinessWirePolicy.Intent;
import com.miaokatze.gtmb.hologram.HologramBusinessWirePolicy.Origin;
import com.miaokatze.gtmb.hologram.HologramBusinessWirePolicy.Owner;
import com.miaokatze.gtmb.testutil.SimpleAssert;

public final class HologramBusinessWirePolicyRegression {

    private static final String ID = "11111111-1111-1111-1111-111111111111", PUBLIC_ID = "public_" + ID;
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private HologramBusinessWirePolicyRegression() {}

    public static void run() {
        whitelistCannotBeBypassedByLegacyNames();
        explicitLibraryTransferNeedsOriginAndOwner();
        operationStatusIsNotAWorkspaceSnapshot();
    }

    private static void whitelistCannotBeBypassedByLegacyNames() {
        for (String op : new String[] { "choosePlacementPreset", "previewPreset", "editPreset", "placeCurrentBlueprint",
            "exportDraft", "saveBlueprint", "saveBlueprintAs", "saveBlueprintReplace", "requestFullSnapshot",
            "refreshPresetLibrary", "refreshMaterialQuote", "refreshMaterialChoices", "setDecorativeVoucher",
            "configure", "scan", "lockVirtual", "rotateVirtual", "configurePlacementDeidentification", "placeTextBox",
            "aePreviewOpen", "switchEditorWorkspace", "close", "start", "build" }) {
            for (Owner owner : Owner.values())
                expect(Intent.BLOCK, HologramBusinessWirePolicy.action(op(op), owner), "legacy action " + op);
        }
        for (String action : new String[] { "projectedPlacement", "checkPlacementMaterials", "useBlock", "useAir",
            "beginAreaSelection", "quickPlaceLastBlueprint", "confirmVirtualPosition", "nudgeVirtualPosition",
            "openSettings", "voucherEditor", "cut", "paste", "undo", "demolish", "preview", "openWorkspace" }) {
            NBTTagCompound packet = new NBTTagCompound();
            packet.setString("action", action);
            expect(Intent.BLOCK, HologramBusinessWirePolicy.action(packet, Owner.NONE), action);
        }
        NBTTagCompound nativeStart = new NBTTagCompound();
        nativeStart.setString("action", "nativeOrdinaryPlace");
        nativeStart.setString("placementRequestId", ID);
        for (String key : new String[] { "x", "y", "z", "side", "slot", "controller", "facing", "main",
            "materialPriority" }) nativeStart.setInteger(key, 0);
        nativeStart.setInteger("main", 1);
        for (String key : new String[] { "mainExplicit", "noHatches", "materialMain", "materialContainers",
            "materialMe" }) nativeStart.setBoolean(key, true);
        NBTTagCompound channels = new NBTTagCompound();
        channels.setInteger("fixture", 1);
        nativeStart.setTag("channels", channels);
        expect(Intent.NATIVE_START, HologramBusinessWirePolicy.action(nativeStart, Owner.NONE), "short native intent");
        channels.setIntArray("fixture", new int[] { 0, 1 });
        expect(Intent.BLOCK, HologramBusinessWirePolicy.action(nativeStart, Owner.NONE), "nested geometry camouflage");

        NBTTagCompound transport = op("snapshotFlow");
        transport.setLong("snapshotFlowGeneration", 1);
        transport.setBoolean("ready", true);
        expect(
            Intent.TRANSPORT_CONTROL,
            HologramBusinessWirePolicy.action(transport, Owner.NONE),
            "transport capability is not business");
        transport.setIntArray("placementOperations", new int[] { 0, 1, 2 });
        expect(Intent.BLOCK, HologramBusinessWirePolicy.action(transport, Owner.NONE), "no control smuggling");

        NBTTagCompound control = op("cancel");
        control.setString("session", ID);
        control.setLong("uiSequence", 2);
        expect(Intent.BLOCK, HologramBusinessWirePolicy.action(control, Owner.NONE), "unowned cancel");
        expect(
            Intent.CONSTRUCTION_CONTROL,
            HologramBusinessWirePolicy.action(control, Owner.ORDINARY_CONSTRUCTION),
            "admitted ordinary execution can cancel");

        control.setString("op", "pause");
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.action(control, Owner.ORDINARY_CONSTRUCTION),
            "manual pause is retired");
        control.setString("op", "resume");
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.action(control, Owner.ORDINARY_CONSTRUCTION),
            "manual resume is retired");

        NBTTagCompound order = op("orderMissingMaterials");
        order.setString("session", ID);
        order.setLong("uiSequence", 3);
        order.setLong("planRevision", 5);
        order.setLong("placementReviewNonce", 7);
        expect(Intent.AE_START, HologramBusinessWirePolicy.action(order, Owner.NONE), "explicit order request");
        order.setString("op", "cancelMaterialOrder");
        order.setString("orderId", ID);
        expect(Intent.AE_CONTROL, HologramBusinessWirePolicy.action(order, Owner.AE_ORDER), "owned AE cancel");
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.action(order, Owner.NATIVE_CONSTRUCTION),
            "native lease cannot cancel AE order");
    }

    private static void explicitLibraryTransferNeedsOriginAndOwner() {
        NBTTagCompound request = transfer("download");
        request.setString("id", PUBLIC_ID);
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.libraryRequest(request, Owner.NONE, Origin.NETWORK_INGRESS),
            "legacy browse, place and edit share this unmarked request");
        request.setString(HologramBusinessWirePolicy.DOWNLOAD_INTENT_KEY, HologramBusinessWirePolicy.PUBLIC_COPY);
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.libraryRequest(request, Owner.NONE, Origin.NONE),
            "a local packet field alone cannot claim a user click");
        expect(
            Intent.LIBRARY_DOWNLOAD,
            HologramBusinessWirePolicy.libraryRequest(request, Owner.NONE, Origin.EXPLICIT_LIBRARY_DOWNLOAD),
            "explicit public library copy can request the exact pair");
        expect(
            Intent.LIBRARY_DOWNLOAD,
            HologramBusinessWirePolicy.libraryRequest(request, Owner.NONE, Origin.NETWORK_INGRESS),
            "new explicit-copy protocol reaches authenticated server admission");

        NBTTagCompound upload = transfer("uploadBegin");
        upload.setString("transfer", ID);
        upload.setString("hash", HASH);
        upload.setInteger("total", 32768);
        upload.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.libraryRequest(upload, Owner.NONE, Origin.NONE),
            "a file payload cannot enter by automatic reuse");
        expect(
            Intent.LIBRARY_UPLOAD,
            HologramBusinessWirePolicy.libraryRequest(upload, Owner.NONE, Origin.EXPLICIT_LIBRARY_UPLOAD),
            "explicit public publication retains its real transfer");

        NBTTagCompound chunk = transfer("uploadChunk");
        chunk.setString("transfer", ID);
        chunk.setInteger("index", 0);
        chunk.setByteArray("data", new byte[] { 1 });
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.libraryRequest(chunk, Owner.NONE, Origin.NETWORK_INGRESS),
            "no orphan chunks");
        expect(
            Intent.LIBRARY_CHUNK,
            HologramBusinessWirePolicy.libraryRequest(chunk, Owner.LIBRARY_UPLOAD, Origin.NETWORK_INGRESS),
            "an admitted library upload retains chunks");
        chunk.setString("op", "previewChunk");
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.libraryRequest(chunk, Owner.LIBRARY_UPLOAD, Origin.NETWORK_INGRESS),
            "preview writeback cannot reuse upload owner");

        for (String op : new String[] { "list", "configGet", "configSet", "upgradeLibrary", "delete", "exportDraft",
            "previewBegin", "privateBegin", "privateChunk", "quickBootstrap", "activate", "admission", "saveStatus" })
            expect(
                Intent.BLOCK,
                HologramBusinessWirePolicy.libraryRequest(transfer(op), Owner.LIBRARY_UPLOAD, Origin.NETWORK_INGRESS),
                "not an explicit file operation: " + op);

        NBTTagCompound header = transfer("downloadBegin");
        header.setBoolean("library", true);
        header.setString("transfer", ID);
        header.setString("hash", HASH);
        header.setInteger("total", 4096);
        header.setString("encoding", "identity");
        header.setInteger("decodedTotal", 4096);
        header.setString("id", PUBLIC_ID);
        header.setString("name", "Fixture");
        header.setString("purpose", "public");
        header.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
        header.setString("sourceHash", HASH);
        header.setLong("sourceRevision", 1);
        expect(
            Intent.LIBRARY_REPLY,
            HologramBusinessWirePolicy.libraryReply(header, Owner.LIBRARY_DOWNLOAD),
            "the exact public pair may be downloaded");
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.libraryReply(header, Owner.LIBRARY_UPLOAD),
            "upload cannot receive a full download");
        header.setString("purpose", "draft");
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.libraryReply(header, Owner.LIBRARY_DOWNLOAD),
            "server save/export is not a library download");
        header.setString("purpose", "public");
        header.setBoolean("capturePreview", true);
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.libraryReply(header, Owner.LIBRARY_DOWNLOAD),
            "relabelled authoring preview cannot reserve a download assembly");
    }

    private static void operationStatusIsNotAWorkspaceSnapshot() {
        NBTTagCompound receipt = new NBTTagCompound();
        receipt.setString("placementRequestId", ID);
        receipt.setBoolean("placementRequestAccepted", true);
        expect(
            Intent.CONSTRUCTION_STATUS,
            HologramBusinessWirePolicy.status(receipt, Owner.NATIVE_CONSTRUCTION),
            "native action acknowledgment survives");
        receipt.setBoolean("nativeCompactStarted", true);
        receipt.setInteger("slot", 0);
        receipt.setString("toolId", ID);
        expect(
            Intent.CONSTRUCTION_STATUS,
            HologramBusinessWirePolicy.status(receipt, Owner.NATIVE_CONSTRUCTION),
            "owner-bound NativeCompactStart accepts with real held-slot correlation");
        expect(Intent.BLOCK, HologramBusinessWirePolicy.status(receipt, Owner.NONE), "unowned native acceptance");
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.status(receipt, Owner.ORDINARY_CONSTRUCTION),
            "native acceptance may not complete an ordinary stream");
        receipt.setTag("cells", new net.minecraft.nbt.NBTTagList());
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.status(receipt, Owner.NATIVE_CONSTRUCTION),
            "construction cannot carry a full geometry view by adding a cell list");

        NBTTagCompound envelope = new NBTTagCompound(), progress = new NBTTagCompound();
        envelope.setBoolean("progressOnly", true);
        envelope.setTag("progress", progress);
        progress.setString("scope", "nativePlacement");
        progress.setString("placementRequestId", ID);
        progress.setString("phase", "placement.capture");
        progress.setLong("done", 800);
        progress.setLong("sequence", 3); // added by the real State transport
        expect(
            Intent.CONSTRUCTION_STATUS,
            HologramBusinessWirePolicy.status(envelope, Owner.NATIVE_CONSTRUCTION),
            "real native progress");
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.status(envelope, Owner.ORDINARY_CONSTRUCTION),
            "native progress does not attach to ordinary request");
        progress.setString("scope", "projectedJob");
        progress.setLong("total", 5000);
        expect(
            Intent.CONSTRUCTION_STATUS,
            HologramBusinessWirePolicy.status(envelope, Owner.ORDINARY_CONSTRUCTION),
            "real ordinary progress counters");
        progress.setTag("geometry", new NBTTagCompound());
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.status(envelope, Owner.ORDINARY_CONSTRUCTION),
            "nested progress is not a side channel for geometry");

        // Mirrors AeCraftingOrders.Order.publish and Network.sendState, without starting an AE worker.
        NBTTagCompound orderProgress = new NBTTagCompound(), order = new NBTTagCompound();
        envelope.setTag("progress", orderProgress);
        orderProgress.setString("scope", "server");
        orderProgress.setString("phase", "material.order");
        orderProgress.setBoolean("materialOrderOnly", true);
        orderProgress.setString("session", ID);
        orderProgress.setLong("planRevision", 8);
        orderProgress.setLong("uiSequence", 9);
        orderProgress.setLong("placementReviewNonce", 10);
        orderProgress.setString("orderId", ID);
        orderProgress.setLong("orderSequence", 3);
        orderProgress.setString("outcome", "running");
        orderProgress.setBoolean("complete", false);
        orderProgress.setString("status", "crafting");
        orderProgress.setLong("done", 13);
        orderProgress.setLong("total", 100);
        orderProgress.setString("unit", "materials");
        orderProgress.setLong("sequence", 27);
        order.setBoolean("active", true);
        order.setString("state", "crafting");
        order.setString("status", "crafting");
        order.setString("orderId", ID);
        order.setLong("orderSequence", 3);
        order.setLong("orderUiSequence", 9);
        order.setLong("planRevision", 8);
        order.setLong("placementReviewNonce", 10);
        order.setLong("done", 13);
        order.setLong("total", 100);
        order.setInteger("completedRows", 2);
        order.setInteger("totalRows", 8);
        order.setLong("received", 10);
        order.setLong("pendingDelivery", 3);
        order.setBoolean("standalone", false);
        order.setString("craftingId", "test-link");
        order.setLong("cpuTotal", 15);
        order.setLong("cpuDone", 3);
        orderProgress.setTag("materialOrder", order);
        expect(
            Intent.AE_STATUS,
            HologramBusinessWirePolicy.status(envelope, Owner.AE_ORDER),
            "owned real AE progress can reach the existing UI");
        expect(Intent.BLOCK, HologramBusinessWirePolicy.status(envelope, Owner.NONE), "unowned AE progress");
        order.setLong("orderSequence", 4);
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.status(envelope, Owner.AE_ORDER),
            "mixed old and new AE order receipts are not legitimate progress");
        order.setLong("orderSequence", 3);
        order.setTag("materialQuote", new NBTTagCompound());
        expect(
            Intent.BLOCK,
            HologramBusinessWirePolicy.status(envelope, Owner.AE_ORDER),
            "a material quote is not AE order progress");
        order.removeTag("materialQuote");
        order.setBoolean("active", false);
        order.setString("state", "complete");
        orderProgress.setBoolean("complete", true);
        orderProgress.setString("outcome", "completed");
        expect(
            Intent.AE_STATUS,
            HologramBusinessWirePolicy.status(envelope, Owner.AE_ORDER),
            "owned AE terminal can retire the existing progress indicator");
    }

    private static NBTTagCompound op(String op) {
        return HologramLibraryProtocol.request(op);
    }

    private static NBTTagCompound transfer(String op) {
        NBTTagCompound tag = op(op);
        tag.setLong("clientTaskToken", 1);
        return tag;
    }

    private static void expect(Intent a, Intent b, String description) {
        SimpleAssert.eq(a, b, description);
    }
}
