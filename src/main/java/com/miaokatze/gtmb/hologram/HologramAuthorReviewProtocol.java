package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;

/** Ephemeral read-only author review, not a workspace/session or a placement capability. */
public final class HologramAuthorReviewProtocol {

    public static final int MAX_BYTES = 32 * 1024 * 1024;
    public static final long LIFETIME_NANOS = HologramAuthorReviewDeadline.HARD_NANOS;
    public static final int RAW_BYTES = 40 * 1024 * 1024;
    public static final long RETAINED_BYTES = 128L * 1024 * 1024;

    private HologramAuthorReviewProtocol() {}

    public static AuthorDocumentIO.Limits limits() {
        return new AuthorDocumentIO.Limits(MAX_BYTES, 131072, 32, 1000000, 12 * 1024 * 1024, 30000000, 4096);
    }

    /** Raw inspector context only: no neighboring cells, obligations or authoring-completion claim. */
    public static byte[] selectedCellSource(com.miaokatze.gtmb.blueprint.editor.AuthorDocument frozen, long cellId)
        throws IOException {
        com.miaokatze.gtmb.blueprint.editor.AuthorDocument.Cell cell = frozen.cell(cellId);
        if (cell == null) throw new IOException("Selected author cell no longer exists");
        com.google.gson.JsonObject selected = frozen.headerCopy();
        com.google.gson.JsonArray cells = new com.google.gson.JsonArray();
        cells.add(cell.sourceCopy());
        selected.add("cells", cells);
        com.google.gson.JsonArray structures = new com.google.gson.JsonArray();
        for (com.google.gson.JsonObject structure : frozen.structures()) if (structure.has("id") && cell.memberships()
            .contains(
                structure.get("id")
                    .getAsString()))
            structures.add(structure);
        selected.add("structures", structures);
        return HologramAuthorSourceJson.encode(selected, MAX_BYTES);
    }

    public static boolean operation(String op) {
        return op != null && op.startsWith("authorReview");
    }

    public static boolean intent(String op) {
        return "inspect".equals(op) || "process".equals(op)
            || "compile".equals(op)
            || "configureQuantumGroups".equals(op)
            || "rotate".equals(op)
            || "globalNbt".equals(op)
            || "cellNbt".equals(op)
            || "candidates".equals(op)
            || "configureCells".equals(op)
            || "attachment".equals(op)
            || "structure".equals(op)
            || "structureInspect".equals(op);
    }

    public static byte[] encode(NBTTagCompound value) throws IOException {
        return encode(value, () -> true);
    }

    public static byte[] encode(NBTTagCompound value, java.util.function.BooleanSupplier current) throws IOException {
        if (HologramSnapshotCodec.retainedEstimate(value) > RETAINED_BYTES)
            throw new IOException("Author review retained payload budget exceeded; recovery draft retained");
        byte[] bytes = HologramSnapshotCodec.compress(value, current, ignored -> {});
        if (bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IOException("Author review transfer exceeds 32 MiB; recovery draft retained");
        // Also enforce expansion for locally-generated output, before advertising its header.
        HologramSnapshotCodec.Decoded measured = HologramSnapshotCodec
            .measure(bytes, 0, bytes.length, RAW_BYTES, current);
        if (measured.retainedEstimate > RETAINED_BYTES)
            throw new IOException("Author review expanded memory budget exceeded");
        return bytes;
    }

    public static NBTTagCompound decode(byte[] bytes) throws IOException {
        return decode(bytes, () -> true);
    }

    public static NBTTagCompound decode(byte[] bytes, java.util.function.BooleanSupplier current) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES)
            throw new IOException("Invalid author review byte budget");
        return HologramSnapshotCodec.decodeMeasured(bytes, RAW_BYTES, RETAINED_BYTES, current).tag;
    }

    public static boolean matches(NBTTagCompound proof, NBTTagCompound packet) {
        return proof != null && packet != null
            && packet.hasKey("transfer", 8)
            && packet.hasKey("documentId", 8)
            && packet.hasKey("sourceHash", 8)
            && packet.hasKey("intent", 8)
            && packet.hasKey("clientTaskToken", 4)
            && packet.hasKey("planRevision", 4)
            && proof.getString("transfer")
                .equals(packet.getString("transfer"))
            && proof.getLong("clientTaskToken") > 0
            && proof.getLong("clientTaskToken") == packet.getLong("clientTaskToken")
            && proof.getString("documentId")
                .equals(packet.getString("documentId"))
            && proof.getLong("planRevision") == packet.getLong("planRevision")
            && proof.getString("sourceHash")
                .equals(packet.getString("sourceHash"))
            && proof.getString("intent")
                .equals(packet.getString("intent"));
    }

    public static NBTTagCompound packet(NBTTagCompound proof, String operation) {
        NBTTagCompound result = new NBTTagCompound();
        result.setString("op", operation);
        for (String key : new String[] { "transfer", "documentId", "sourceHash", "intent" })
            result.setString(key, proof.getString(key));
        result.setLong("clientTaskToken", proof.getLong("clientTaskToken"));
        result.setLong("planRevision", proof.getLong("planRevision"));
        return result;
    }
}
