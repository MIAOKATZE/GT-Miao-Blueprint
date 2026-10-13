package com.miaokatze.gtmb.hologram;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/**
 * Session-local protocol state. All calls run on the server scheduler; no native container or inventory exists here.
 */
final class HologramAeProtocol {

    static final int MAX_CANDIDATE_BYTES = 64 * 1024;
    static final int MAX_EDIT_BYTES = 128 * 1024;
    static final int MAX_STACK_AGGREGATE = 8 * 1024 * 1024;
    private static final long EXPIRY_NANOS = 5L * 60 * 1000000000;

    private HologramAeProtocol() {}

    static final class State {

        Preview preview;
        Review review;
        NBTTagCompound ack;
        long lastTick = Long.MIN_VALUE, rateWindow = Long.MIN_VALUE;
        int inWindow;

        void invalidate() {
            review = null;
            preview = null;
        }

        void invalidateReviews() {
            review = null;
        }

        void result(long seq, boolean accepted, String code, long revision) {
            ack = new NBTTagCompound();
            ack.setLong("editSeq", seq);
            ack.setBoolean("accepted", accepted);
            ack.setString("code", code);
            ack.setLong("newRevision", revision);
        }
    }

    static final class Preview {

        final String session, tool, token, structure, block;
        final int x, y, z, metadata;
        long revision, expires;
        byte[] payloadFingerprint;
        long lastSeq;
        byte[] lastRequest;
        NBTTagCompound lastAck;

        Preview(String session, String tool, String structure, long revision, int x, int y, int z, Block block,
            int metadata, String payload) {
            this.session = session;
            this.tool = tool;
            this.structure = structure;
            this.revision = revision;
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = registry(block);
            this.metadata = metadata;
            token = UUID.randomUUID()
                .toString();
            payloadFingerprint = fingerprint(payload);
            expires = System.nanoTime() + EXPIRY_NANOS;
        }

        boolean current(String session, String tool, String structure, long revision, int x, int y, int z, Block block,
            int metadata, String payload) {
            return System.nanoTime() - expires < 0 && this.session.equals(session)
                && this.tool.equals(tool)
                && this.structure.equals(structure)
                && this.revision == revision
                && this.x == x
                && this.y == y
                && this.z == z
                && this.block.equals(registry(block))
                && this.metadata == metadata
                && Arrays.equals(payloadFingerprint, fingerprint(payload));
        }

        boolean replay(NBTTagCompound request) throws IOException {
            return lastAck != null && lastSeq == request.getLong("editSeq")
                && token.equals(request.getString("token"))
                && Arrays.equals(lastRequest, requestFingerprint(request));
        }

        void remember(NBTTagCompound request, NBTTagCompound ack, long revision, String payload) throws IOException {
            lastSeq = request.getLong("editSeq");
            lastRequest = requestFingerprint(request);
            lastAck = (NBTTagCompound) ack.copy();
            this.revision = revision;
            payloadFingerprint = fingerprint(payload);
            expires = System.nanoTime() + EXPIRY_NANOS;
        }

        NBTTagCompound snapshot(HologramAeBlueprint.Analysis analysis) throws IOException {
            NBTTagCompound dto = new NBTTagCompound();
            dto.setString("session", session);
            dto.setString("token", token);
            dto.setLong("planRevision", revision);
            dto.setInteger("x", x);
            dto.setInteger("y", y);
            dto.setInteger("z", z);
            dto.setString("block", block);
            dto.setInteger("metadata", metadata);
            dto.setString("structureId", structure);
            NBTTagList slots = new NBTTagList();
            long stackBytes = 0;
            for (HologramAeBlueprint.Slot slot : analysis.slots) {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("id", slot.id);
                row.setString("kind", slot.kind);
                row.setString("label", HologramAePolicy.fallback(slot.label));
                row.setInteger("limit", slot.limit);
                NBTTagList allowed = new NBTTagList();
                for (String rule : slot.allowed) allowed.appendTag(new NBTTagString(rule));
                row.setTag("allowed", allowed);
                row.setBoolean("enabled", slot.enabled);
                row.setString("issueCode", slot.issueCode);
                row.setString("issuePath", slot.issuePath);
                ItemStack stack = slot.stack();
                if (stack != null) {
                    NBTTagCompound tag = HologramBlueprintNbt.nativePayload(stack.writeToNBT(new NBTTagCompound()));
                    stackBytes += bytes(tag, MAX_CANDIDATE_BYTES);
                    if (stackBytes > MAX_STACK_AGGREGATE) throw new IOException("AE preview stack budget");
                    row.setTag("stack", tag);
                }
                slots.appendTag(row);
            }
            dto.setTag("slots", slots);
            NBTTagList issues = new NBTTagList();
            if (analysis.issues.size() > HologramAePolicy.MAX_ISSUES)
                throw new IOException("AE complete report budget");
            for (HologramAeBlueprint.Issue issue : analysis.issues) {
                HologramAePolicy.Issue projected = new HologramAePolicy.Issue(
                    issue.code,
                    HologramAePolicy.severity(issue.severity),
                    issue.path,
                    HologramAePolicy.fallback(issue.message),
                    x,
                    y,
                    z);
                NBTTagCompound row = projected.dto(0, 0, 0);
                HologramAePolicy.projectResolution(row, analysis, structure);
                issues.appendTag(row);
            }
            dto.setTag("issues", issues);
            return dto;
        }
    }

    static final class Review {

        final String transactionId = UUID.randomUUID()
            .toString();
        final String session, tool, operation;
        final long revision, expires;
        final boolean update;
        private final HologramAePolicy.Draft draft;
        private final NBTTagList materials;

        Review(String session, String tool, long revision, String operation, boolean update,
            HologramAePolicy.Draft draft, NBTTagList materials) {
            this.session = session;
            this.tool = tool;
            this.revision = revision;
            this.operation = operation;
            this.update = update;
            // Both copies are detached; subsequent edits cannot change what this transaction reviews.
            HologramPresets.Preset copy = HologramPresets.copy(draft.preset);
            this.draft = new HologramAePolicy.Draft(copy, copy.aeIssues);
            this.materials = (NBTTagList) materials.copy();
            expires = System.nanoTime() + EXPIRY_NANOS;
        }

        HologramPresets.Preset approve(NBTTagCompound request, String session, String tool, long revision)
            throws IOException {
            if (System.nanoTime() - expires >= 0 || !this.session.equals(session)
                || !this.tool.equals(tool)
                || this.revision != revision
                || !request.hasKey("planRevision", 4)
                || request.getLong("planRevision") != revision
                || !request.hasKey("transactionId", 8)
                || !transactionId.equals(request.getString("transactionId")))
                throw new IOException("AE save review expired; review the current draft again");
            if (draft.blocking) throw new IOException(
                "AE unresolved issues block saving; remove or replace unsupported marks explicitly");
            return HologramPresets.copy(draft.preset);
        }

        NBTTagCompound snapshot(int x, int y, int z) throws IOException {
            return snapshot(x, y, z, "");
        }

        NBTTagCompound snapshot(int x, int y, int z, String activeStructure) throws IOException {
            NBTTagCompound tag = new NBTTagCompound();
            tag.setString("transactionId", transactionId);
            tag.setString("session", session);
            tag.setLong("planRevision", revision);
            tag.setString("operation", operation);
            tag.setString("name", draft.preset.name);
            tag.setBoolean("update", update);
            tag.setBoolean("canConfirm", !draft.blocking);
            NBTTagList issues = HologramAePolicy.issues(draft.issues, x, y, z);
            Map<String, HologramPresets.Cell> cells = new LinkedHashMap<>();
            Map<String, HologramAeBlueprint.Analysis> analyses = new LinkedHashMap<>();
            for (HologramPresets.Cell cell : draft.preset.cells) cells.put(cell.x + "," + cell.y + "," + cell.z, cell);
            for (int index = 0; index < issues.tagCount(); index++) {
                NBTTagCompound issue = issues.getCompoundTagAt(index);
                String key = (issue.getInteger("x") - x) + ","
                    + (issue.getInteger("y") - y)
                    + ","
                    + (issue.getInteger("z") - z);
                HologramPresets.Cell cell = cells.get(key);
                if (cell == null) continue;
                if (!analyses.containsKey(key)) {
                    NBTTagCompound payload = HologramBlueprintNbt.decode(cell.tileNbt);
                    analyses.put(
                        key,
                        HologramAeBlueprint.supports(cell.block(), payload)
                            ? HologramAeBlueprint.analyze(cell.block(), cell.metadata, payload)
                            : null);
                }
                HologramAeBlueprint.Analysis analysis = analyses.get(key);
                if (analysis == null) continue;
                String structure = cell.belongsTo(activeStructure) ? activeStructure
                    : cell.memberships()
                        .isEmpty() ? ""
                            : cell.memberships()
                                .get(0);
                HologramAePolicy.projectResolution(issue, analysis, structure);
            }
            tag.setTag("issues", issues);
            tag.setInteger("issueCount", draft.issues.size());
            tag.setInteger(
                "warningCount",
                (int) draft.issues.stream()
                    .filter(issue -> "warning".equals(issue.severity))
                    .count());
            tag.setInteger(
                "blockerCount",
                (int) draft.issues.stream()
                    .filter(issue -> "blocker".equals(issue.severity))
                    .count());
            tag.setTag("materials", materials.copy());
            return tag;
        }
    }

    static void validateEdit(NBTTagCompound request, State state, long tick) throws IOException {
        bytes(request, MAX_EDIT_BYTES);
        if (!request.hasKey("editSeq", 4) || request.getLong("editSeq") < 1
            || !request.hasKey("planRevision", 4)
            || !request.hasKey("token", 8)
            || !request.hasKey("slotId", 8)
            || request.getString("slotId")
                .length() > 128
            || !request.hasKey("mutation", 8)) throw new IOException("AE edit shape invalid");
        String mutation = request.getString("mutation");
        if (!("SET".equals(mutation) || "CLEAR".equals(mutation))) throw new IOException("AE edit operation invalid");
        if ("SET".equals(mutation) != request.hasKey("stack", 10)) throw new IOException("AE edit stack shape invalid");
        if ("CLEAR".equals(mutation) && request.hasKey("stack")) throw new IOException("AE clear has candidate data");
        if (request.hasKey("sourceInventory") || request.hasKey("owner")
            || request.hasKey("path")
            || request.hasKey("slotRole")
            || request.hasKey("cost")
            || request.hasKey("hostClass")) throw new IOException("AE edit contains unauthorized fields");
        if (state.rateWindow == Long.MIN_VALUE || tick < state.rateWindow || tick - state.rateWindow >= 20) {
            state.rateWindow = tick;
            state.inWindow = 0;
        }
        if (state.lastTick == tick || state.inWindow >= 10) throw new IOException("AE edit rate exceeded");
        state.lastTick = tick;
        state.inWindow++;
    }

    static ItemStack candidate(NBTTagCompound request, HologramAeBlueprint.Slot slot) throws IOException {
        if ("CLEAR".equals(request.getString("mutation"))) return null;
        NBTTagCompound encoded = request.getCompoundTag("stack");
        bytes(encoded, MAX_CANDIDATE_BYTES);
        if (!encoded.hasKey("id", 2) || !encoded.hasKey("Count", 1)
            || !encoded.hasKey("Damage", 2)
            || encoded.getByte("Count") < 1
            || encoded.getByte("Count") > slot.limit
            || encoded.getShort("Damage") < 0
            || encoded.hasKey(HologramBlueprintNbt.ITEM_REGISTRY))
            throw new IOException("AE candidate item/count/metadata invalid");
        ItemStack stack = ItemStack.loadItemStackFromNBT((NBTTagCompound) encoded.copy());
        if (stack == null || stack.getItem() == null || stack.stackSize > stack.getMaxStackSize())
            throw new IOException("AE candidate item invalid");
        return stack.copy();
    }

    static int bytes(NBTTagCompound tag, int maximum) throws IOException {
        CountingOutput counter = new CountingOutput(maximum);
        try (DataOutputStream output = new DataOutputStream(counter)) {
            CompressedStreamTools.write(tag, output);
        }
        return counter.count;
    }

    private static final class CountingOutput extends OutputStream {

        final int maximum;
        int count;

        CountingOutput(int maximum) {
            this.maximum = maximum;
        }

        public void write(int value) throws IOException {
            reserve(1);
        }

        public void write(byte[] bytes, int offset, int length) throws IOException {
            reserve(length);
        }

        void reserve(int length) throws IOException {
            if (length < 0 || length > maximum - count) throw new IOException("AE decoded NBT byte budget");
            count += length;
        }
    }

    private static String registry(Block block) {
        return block == null ? "" : String.valueOf(Block.blockRegistry.getNameForObject(block));
    }

    private static byte[] fingerprint(String payload) {
        return digest((payload == null ? "" : payload).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] requestFingerprint(NBTTagCompound request) throws IOException {
        NBTTagCompound copy = (NBTTagCompound) request.copy();
        // UI transport counters are not part of the logical edit identity.
        copy.removeTag("uiSequence");
        return digest(CompressedStreamTools.compress(copy));
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256")
                .digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
