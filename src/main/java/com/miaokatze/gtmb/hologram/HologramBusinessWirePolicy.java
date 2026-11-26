// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/**
 * Positive wire policy. Call before action preparation or queue admission, then authenticate and bind the real owner
 * in the receiver. An allowed message shape is not authorization, and a wire field never certifies a UI gesture.
 */
public final class HologramBusinessWirePolicy {

    public enum Intent {
        BLOCK,
        TRANSPORT_CONTROL,
        NATIVE_START,
        CONSTRUCTION_CONTROL,
        CONSTRUCTION_STATUS,
        AE_START,
        AE_CONTROL,
        AE_STATUS,
        LIBRARY_UPLOAD,
        LIBRARY_DOWNLOAD,
        LIBRARY_CHUNK,
        LIBRARY_CONTROL,
        LIBRARY_REPLY
    }

    /** This classification comes from the live authenticated lease, not from the packet being classified. */
    public enum Owner {
        NONE,
        NATIVE_CONSTRUCTION,
        ORDINARY_CONSTRUCTION,
        AE_ORDER,
        LIBRARY_UPLOAD,
        LIBRARY_DOWNLOAD
    }

    public enum Origin {
        NONE,
        EXPLICIT_LIBRARY_UPLOAD,
        EXPLICIT_LIBRARY_DOWNLOAD,
        NETWORK_INGRESS
    }

    public static final String DOWNLOAD_INTENT_KEY = "downloadIntent";
    public static final String PUBLIC_COPY = "publicCopy";

    private static final int STRING_LIMIT = 512;

    private HologramBusinessWirePolicy() {}

    public static Intent action(NBTTagCompound tag, Owner owner) {
        if (tag == null) return Intent.BLOCK;
        String action = tag.getString("action");
        if (HologramCompactNativeRequest.ordinary(tag)
            || "useBlock".equals(action) && tag.hasKey(HologramCompactNativeRequest.FLAG)) {
            return HologramCompactNativeRequest.valid(tag) && uuid(tag.getString("placementRequestId"))
                && HologramSessionPolicy.validChannels(tag.getCompoundTag("channels")) ? Intent.NATIVE_START
                    : Intent.BLOCK;
        }
        if ("cancelProjectedPlacement".equals(action))
            return construction(owner) && actionShape(tag, "action:8", "placementRequestId:8")
                && uuid(tag.getString("placementRequestId")) ? Intent.CONSTRUCTION_CONTROL : Intent.BLOCK;
        // No generic useBlock, projectedPlacement, checkPlacementMaterials, attachment or Cut action.
        if (tag.hasKey("action")) return Intent.BLOCK;
        return switch (tag.getString("op")) {
            case "snapshotFlow" -> actionShape(tag, "op:8", "snapshotFlowGeneration:4", "ready:1")
                ? Intent.TRANSPORT_CONTROL
                : Intent.BLOCK;
            case "snapshotConsumed" -> actionShape(
                tag,
                "op:8",
                "snapshotFlowGeneration:4",
                "transportSequence:4",
                "success:1") ? Intent.TRANSPORT_CONTROL : Intent.BLOCK;
            case "cancel" -> construction(owner)
                && actionShape(
                    tag,
                    "op:8",
                    "session:8",
                    "uiSequence:4",
                    "?planRevision:4",
                    "?editorWorkspaceEpoch:4",
                    "?sourceRevision:4",
                    "?semanticPlanRevision:4",
                    "?selected:3",
                    "?structureId:8")
                && !tag.getString("session")
                    .isEmpty() ? Intent.CONSTRUCTION_CONTROL : Intent.BLOCK;
            case "orderMissingMaterials" -> orderShape(tag, false) ? Intent.AE_START : Intent.BLOCK;
            case "cancelMaterialOrder" -> owner == Owner.AE_ORDER && orderShape(tag, true) ? Intent.AE_CONTROL
                : Intent.BLOCK;
            default -> Intent.BLOCK;
        };
    }

    private static boolean orderShape(NBTTagCompound tag, boolean cancel) {
        return actionShape(
            tag,
            "op:8",
            "session:8",
            "uiSequence:4",
            "planRevision:4",
            "placementReviewNonce:4",
            "?editorWorkspaceEpoch:4",
            "?sourceRevision:4",
            "?semanticPlanRevision:4",
            "?selected:3",
            "?structureId:8",
            cancel ? "orderId:8" : "?orderId:8")
            && !tag.getString("session")
                .isEmpty()
            && (cancel ? uuid(tag.getString("orderId")) : !tag.hasKey("orderId"));
    }

    /** Initial wire requests need a positive local origin, or the declared protocol shape on network ingress. */
    public static Intent libraryRequest(NBTTagCompound tag, Owner owner, Origin origin) {
        if (tag == null || !tag.hasKey("clientTaskToken", 4) || tag.getLong("clientTaskToken") <= 0)
            return Intent.BLOCK;
        return switch (tag.getString("op")) {
            case "uploadBegin" -> (origin == Origin.EXPLICIT_LIBRARY_UPLOAD || origin == Origin.NETWORK_INGRESS)
                && shape(
                    tag,
                    false,
                    "op:8",
                    "clientTaskToken:4",
                    "transfer:8",
                    "total:3",
                    "hash:8",
                    "artifactVersion:3",
                    "?replaceId:8",
                    "?sourceHash:8",
                    "?sourceRevision:4")
                && uploadHeader(tag)
                && (!tag.hasKey("replaceId") || publicId(tag.getString("replaceId")))
                && pairedRevision(tag)
                && nativeUploadRevision(tag) ? Intent.LIBRARY_UPLOAD : Intent.BLOCK;
            case "download" -> (origin == Origin.EXPLICIT_LIBRARY_DOWNLOAD || origin == Origin.NETWORK_INGRESS)
                && shape(
                    tag,
                    false,
                    "op:8",
                    "clientTaskToken:4",
                    "id:8",
                    "downloadIntent:8",
                    "?sourceHash:8",
                    "?sourceRevision:4",
                    "?acceptNative:1")
                && (!tag.hasKey("acceptNative") || tag.getByte("acceptNative") == 1)
                && PUBLIC_COPY.equals(tag.getString(DOWNLOAD_INTENT_KEY))
                && publicId(tag.getString("id"))
                && pairedRevision(tag) ? Intent.LIBRARY_DOWNLOAD : Intent.BLOCK;
            case "uploadChunk" -> owner == Owner.LIBRARY_UPLOAD && libraryChunk(tag) ? Intent.LIBRARY_CHUNK
                : Intent.BLOCK;
            case "cancel", "cancelTransfer" -> library(owner)
                && shape(tag, false, "op:8", "clientTaskToken:4", "transfer:8")
                && (tag.getString("transfer")
                    .isEmpty() || uuid(tag.getString("transfer"))) ? Intent.LIBRARY_CONTROL : Intent.BLOCK;
            default -> Intent.BLOCK;
        };
    }

    private static boolean uploadHeader(NBTTagCompound tag) {
        try {
            HologramLibraryProtocol.header(tag.getString("transfer"), tag.getInteger("total"), tag.getString("hash"));
            return HologramNativePublicArtifact.supported(tag.getInteger("artifactVersion"));
        } catch (IOException invalid) {
            return false;
        }
    }

    private static boolean nativeUploadRevision(NBTTagCompound tag) {
        return tag.hasKey("sourceHash")
            == (tag.getInteger("artifactVersion") == HologramNativePublicArtifact.VERSION && tag.hasKey("replaceId"));
    }

    private static boolean pairedRevision(NBTTagCompound tag) {
        if (!tag.hasKey("sourceHash")) return !tag.hasKey("sourceRevision");
        return tag.getString("sourceHash")
            .matches("[a-f0-9]{64}") && tag.getLong("sourceRevision") > 0;
    }

    private static boolean libraryChunk(NBTTagCompound tag) {
        if (!shape(tag, false, "op:8", "clientTaskToken:4", "transfer:8", "index:3", "data:7", "?library:1"))
            return false;
        int bytes = tag.getByteArray("data").length;
        return uuid(tag.getString("transfer")) && tag.getInteger("index") >= 0
            && bytes > 0
            && bytes <= HologramLibraryProtocol.CHUNK_BYTES;
    }

    /** A library packet is not exempt merely because it uses the library discriminator. */
    public static Intent libraryReply(NBTTagCompound tag, Owner owner) {
        if (tag == null || !library(owner)
            || !tag.getBoolean("library")
            || !tag.hasKey("clientTaskToken", 4)
            || tag.getLong("clientTaskToken") <= 0) return Intent.BLOCK;
        boolean allowed = switch (tag.getString("op")) {
            case "uploadReady" -> owner == Owner.LIBRARY_UPLOAD && transferReply(
                tag,
                "op:8",
                "library:1",
                "clientTaskToken:4",
                "transfer:8",
                "nextIndex:3",
                "?uploadWindow:3");
            case "chunkAck" -> owner == Owner.LIBRARY_UPLOAD
                && transferReply(tag, "op:8", "library:1", "clientTaskToken:4", "transfer:8", "index:3", "nextIndex:3");
            case "uploaded" -> owner == Owner.LIBRARY_UPLOAD
                && transferReply(
                    tag,
                    "op:8",
                    "library:1",
                    "clientTaskToken:4",
                    "transfer:8",
                    "id:8",
                    "?cleanupWarning:8")
                && publicId(tag.getString("id"));
            case "downloadBegin" -> owner == Owner.LIBRARY_DOWNLOAD && publicDownloadHeader(tag);
            case "downloadChunk" -> owner == Owner.LIBRARY_DOWNLOAD && libraryChunk(tag);
            case "downloadDone" -> owner == Owner.LIBRARY_DOWNLOAD && transferReply(
                tag,
                "op:8",
                "library:1",
                "clientTaskToken:4",
                "transfer:8",
                "?transferNanos:4",
                "?chunks:3",
                "?wireBytes:3",
                "?decodedBytes:3",
                "?budgetPolls:3");
            case "error" -> shape(tag, false, "op:8", "library:1", "clientTaskToken:4", "message:8");
            default -> false;
        };
        return allowed ? Intent.LIBRARY_REPLY : Intent.BLOCK;
    }

    private static boolean transferReply(NBTTagCompound tag, String... fields) {
        return shape(tag, false, fields) && uuid(tag.getString("transfer"));
    }

    private static boolean publicDownloadHeader(NBTTagCompound tag) {
        if (!transferReply(
            tag,
            "op:8",
            "library:1",
            "clientTaskToken:4",
            "transfer:8",
            "hash:8",
            "total:3",
            "encoding:8",
            "decodedTotal:3",
            "id:8",
            "name:8",
            "purpose:8",
            "artifactVersion:3",
            "sourceHash:8",
            "sourceRevision:4",
            "?capturePreview:1",
            "?localSaveReceipt:1",
            "?localId:8",
            "?session:8",
            "?planRevision:4") || !"public".equals(tag.getString("purpose"))
            || !publicId(tag.getString("id"))
            || !pairedRevision(tag)
            || tag.getBoolean("capturePreview")
            || tag.getBoolean("localSaveReceipt")
            || !tag.getString("localId")
                .isEmpty()
            || !tag.getString("session")
                .isEmpty()
            || tag.getLong("planRevision") != 0
            || !HologramNativePublicArtifact.supported(tag.getInteger("artifactVersion"))) return false;
        try {
            HologramLibraryDownload.header(
                tag.getString("transfer"),
                tag.getInteger("total"),
                tag.getString("encoding"),
                tag.getInteger("decodedTotal"),
                tag.getString("hash"));
            return true;
        } catch (IOException invalid) {
            return false;
        }
    }

    /** Only narrow operation receipts/counters, never the old generic reconstructed workspace snapshot. */
    public static Intent status(NBTTagCompound tag, Owner owner) {
        if (tag == null) return Intent.BLOCK;
        if (owner == Owner.AE_ORDER) return aeProgress(tag) ? Intent.AE_STATUS : Intent.BLOCK;
        if (!construction(owner)) return Intent.BLOCK;
        if (tag.getBoolean("progressOnly")) {
            NBTTagCompound progress = tag.getCompoundTag("progress");
            if (!shape(tag, false, "progressOnly:1", "progress:10") || !uuid(progress.getString("placementRequestId")))
                return Intent.BLOCK;
            boolean nativeProgress = owner == Owner.NATIVE_CONSTRUCTION
                && "nativePlacement".equals(progress.getString("scope"))
                && shape(progress, false, "scope:8", "placementRequestId:8", "phase:8", "done:4", "?sequence:4")
                && progress.getLong("done") >= 0;
            boolean ordinaryProgress = owner == Owner.ORDINARY_CONSTRUCTION
                && "projectedJob".equals(progress.getString("scope"))
                && shape(
                    progress,
                    false,
                    "scope:8",
                    "placementRequestId:8",
                    "phase:8",
                    "done:4",
                    "?total:4",
                    "?sequence:4",
                    "?detail:8");
            return nativeProgress || ordinaryProgress ? Intent.CONSTRUCTION_STATUS : Intent.BLOCK;
        }
        if (!uuid(tag.getString("placementRequestId"))) return Intent.BLOCK;
        if (shape(tag, false, "placementRequestId:8", "placementRequestAccepted:1", "?errorMessage:8"))
            return Intent.CONSTRUCTION_STATUS;
        if (owner == Owner.NATIVE_CONSTRUCTION && tag.getBoolean("nativeCompactStarted")
            && shape(
                tag,
                false,
                "placementRequestId:8",
                "placementRequestAccepted:1",
                "nativeCompactStarted:1",
                "slot:3",
                "toolId:8")
            && tag.getBoolean("placementRequestAccepted")
            && tag.getInteger("slot") >= 0
            && tag.getInteger("slot") < 9) return Intent.CONSTRUCTION_STATUS;
        String flag = owner == Owner.NATIVE_CONSTRUCTION ? "nativeCompactJob" : "projectedJob";
        return tag.getBoolean(flag) && shape(
            tag,
            false,
            "placementRequestId:8",
            "placementRequestAccepted:1",
            flag + ":1",
            "job:3",
            "status:8",
            "total:3",
            "completed:3") ? Intent.CONSTRUCTION_STATUS : Intent.BLOCK;
    }

    /** The current AE order producer sends only this actual order, never the initial view quote. */
    private static boolean aeProgress(NBTTagCompound tag) {
        if (!shape(tag, false, "progressOnly:1", "progress:10") || !tag.getBoolean("progressOnly")) return false;
        NBTTagCompound p = tag.getCompoundTag("progress");
        if (!shape(
            p,
            false,
            "scope:8",
            "phase:8",
            "materialOrderOnly:1",
            "session:8",
            "planRevision:4",
            "uiSequence:4",
            "placementReviewNonce:4",
            "orderId:8",
            "orderSequence:4",
            "outcome:8",
            "complete:1",
            "status:8",
            "done:4",
            "total:4",
            "unit:8",
            "materialOrder:10",
            "?sequence:4") || !p.getBoolean("materialOrderOnly")
            || !"server".equals(p.getString("scope"))
            || !"material.order".equals(p.getString("phase"))
            || !"materials".equals(p.getString("unit"))
            || p.getString("session")
                .isEmpty()
            || !uuid(p.getString("orderId"))
            || p.getLong("orderSequence") <= 0
            || !counts(p.getLong("done"), p.getLong("total"))) return false;
        NBTTagCompound order = p.getCompoundTag("materialOrder");
        if (!shape(
            order,
            false,
            "active:1",
            "state:8",
            "status:8",
            "orderId:8",
            "orderSequence:4",
            "orderUiSequence:4",
            "planRevision:4",
            "placementReviewNonce:4",
            "done:4",
            "total:4",
            "completedRows:3",
            "totalRows:3",
            "received:4",
            "pendingDelivery:4",
            "?standalone:1",
            "?craftingId:8",
            "?cpuTotal:4",
            "?cpuDone:4")) return false;
        return p.getString("orderId")
            .equals(order.getString("orderId")) && p.getLong("orderSequence") == order.getLong("orderSequence")
            && p.getLong("uiSequence") == order.getLong("orderUiSequence")
            && p.getLong("planRevision") == order.getLong("planRevision")
            && p.getLong("placementReviewNonce") == order.getLong("placementReviewNonce")
            && p.getLong("done") == order.getLong("done")
            && p.getLong("total") == order.getLong("total")
            && p.getBoolean("complete") != order.getBoolean("active")
            && counts(order.getInteger("completedRows"), order.getInteger("totalRows"))
            && order.getLong("received") >= 0
            && order.getLong("pendingDelivery") >= 0
            && order.hasKey("standalone") == order.hasKey("craftingId")
            && order.hasKey("cpuDone") == order.hasKey("cpuTotal")
            && counts(order.getLong("cpuDone"), order.getLong("cpuTotal"));
    }

    private static boolean counts(long done, long total) {
        return done >= 0 && total >= done;
    }

    private static boolean construction(Owner owner) {
        return owner == Owner.NATIVE_CONSTRUCTION || owner == Owner.ORDINARY_CONSTRUCTION;
    }

    private static boolean library(Owner owner) {
        return owner == Owner.LIBRARY_UPLOAD || owner == Owner.LIBRARY_DOWNLOAD;
    }

    private static boolean uuid(String value) {
        if (value.length() != 36) return false;
        try {
            return UUID.fromString(value)
                .toString()
                .equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean publicId(String value) {
        return value.startsWith("public_") && uuid(value.substring(7));
    }

    private static boolean actionShape(NBTTagCompound tag, String... fields) {
        return shape(tag, true, fields);
    }

    /** Required/optional members of exact NBT types. Any field not named here is rejected, including geometry. */
    private static boolean shape(NBTTagCompound tag, boolean action, String... fields) {
        int recognized = 0;
        for (String field : fields) {
            int colon = field.lastIndexOf(':');
            boolean optional = field.charAt(0) == '?';
            String name = field.substring(optional ? 1 : 0, colon);
            if (!tag.hasKey(name)) {
                if (optional) continue;
                return false;
            }
            int type = Integer.parseInt(field.substring(colon + 1));
            if (!tag.hasKey(name, type) || type == 8 && tag.getString(name)
                .length() > STRING_LIMIT || type == 1 && (tag.getByte(name) & 255) > 1) return false;
            recognized++;
        }
        if (action) {
            String[] keys = { "clientSnapshotProtocol", "clientSnapshotPalette", "clientSnapshotFlow" };
            int[] versions = { 1, HologramSnapshotPalette.VERSION, HologramSnapshotFlowControl.VERSION };
            for (int i = 0; i < keys.length; i++) {
                if (!tag.hasKey(keys[i])) continue;
                if (!tag.hasKey(keys[i], 3) || tag.getInteger(keys[i]) != versions[i]) return false;
                recognized++;
            }
        }
        return recognized == tag.func_150296_c()
            .size();
    }
}
