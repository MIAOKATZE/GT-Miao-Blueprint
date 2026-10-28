package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;

/** Explicit owner-thread compatibility review of detached source; no session, world or asset authority. */
public final class HologramAuthorServerReview {

    private static final Gson GSON = new Gson();

    private HologramAuthorServerReview() {}

    public static final class Result {

        public final NBTTagCompound report;
        public final JsonObject source;
        public final byte[] compiled;

        private Result(NBTTagCompound report, JsonObject source, byte[] compiled) {
            this.report = report;
            this.source = source;
            this.compiled = compiled;
        }
    }

    public interface NativeTask extends AutoCloseable {

        boolean step() throws IOException;

        Result result() throws IOException;

        @Override
        void close();
    }

    /** A cooperative owner-thread declaration job; it never borrows a server author Session. */
    public static final class StructureTask implements NativeTask {

        private final HologramLocalAuthorStructures nativeTask;
        private final boolean inspection;
        private java.util.Iterator<JsonObject> remaining;
        private JsonArray cells;
        private Result result;
        private boolean closed, complete;

        private StructureTask(AuthorDocument frozen, NBTTagCompound policy, ItemStack seed, boolean inspection)
            throws IOException {
            this.inspection = inspection;
            nativeTask = new HologramLocalAuthorStructures(
                frozen,
                policy.getString("structureId"),
                policy.getInteger("main"),
                policy.getCompoundTag("channels"),
                policy.getBoolean("noHatches"),
                seed,
                inspection);
        }

        /** One existing native slice, followed by bounded result assembly; call again on a later owner tick. */
        public boolean step() throws IOException {
            try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
                if (closed) throw new IOException("Structure review was cancelled");
                if (complete) return true;
                if (!nativeTask.done()) {
                    nativeTask.tick();
                    return false;
                }
                if (result == null) {
                    NBTTagCompound report = new NBTTagCompound();
                    report.setTag("capabilities", nativeTask.capabilities());
                    report.setInteger("facing", nativeTask.facingOrdinal());
                    report.setBoolean("inspection", inspection);
                    if (inspection) {
                        result = new Result(report, null, null);
                        return complete = true;
                    }
                    JsonObject delta = new JsonObject();
                    delta.add("group", nativeTask.group());
                    delta.add("headerPatch", nativeTask.headerPatch());
                    cells = new JsonArray();
                    delta.add("cells", cells);
                    remaining = nativeTask.cells()
                        .iterator();
                    result = new Result(report, delta, null);
                }
                long deadline = System.nanoTime() + 2_000_000L;
                for (int work = 0; work < 256 && remaining.hasNext() && System.nanoTime() < deadline; work++)
                    cells.add(remaining.next());
                complete = !remaining.hasNext();
                return complete;
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Server structure review failed: " + failure.getMessage(), failure);
            }
        }

        public Result result() throws IOException {
            if (!complete || closed) throw new IOException("Structure review is not complete");
            return result;
        }

        @Override
        public void close() {
            if (closed) return;
            nativeTask.close();
            closed = true;
            remaining = null;
            cells = null;
            result = null;
        }
    }

    /** Seed is the connected owner's held projector supplied by the server transport, not an uploaded item. */
    public static StructureTask startStructure(AuthorDocument frozen, String operation, NBTTagCompound policy,
        ItemStack seed) throws IOException {
        if (frozen == null || policy == null
            || !("structure".equals(operation) || "structureInspect".equals(operation)))
            throw new IOException("Invalid structure review request");
        try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
            return new StructureTask(frozen, policy, seed, "structureInspect".equals(operation));
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot start server structure review: " + failure.getMessage(), failure);
        }
    }

    /** Selected-cell diagnostic; caller supplies bounded structural input and retains original document fences. */
    public static NBTTagCompound reviewCell(JsonObject cell, JsonObject header, int index, int offset)
        throws IOException {
        if (cell == null || header == null || index < 0 || offset < 0 || offset > 1_000_000)
            throw new IOException("Invalid selected NBT review page");
        try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
            return HologramLocalAuthorSemantics.inspect(cell, header, index, offset, 64);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Server selected NBT review failed: " + failure.getMessage(), failure);
        }
    }

    /** Read-only candidate query for one bounded source record; applicability and samples stay on the owner. */
    public static NBTTagCompound reviewCandidates(JsonObject cell) throws IOException {
        if (cell == null) throw new IOException("Missing selected author cell");
        try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
            java.util.List<ItemStack> candidates = HologramLocalAuthorConfigurations.candidates(cell);
            if (candidates.size() > 4096) throw new IOException("Candidate review limit exceeded");
            NBTTagCompound report = new NBTTagCompound();
            NBTTagList stacks = new NBTTagList();
            for (ItemStack candidate : candidates) stacks.appendTag(candidate.writeToNBT(new NBTTagCompound()));
            report.setTag("candidates", stacks);
            report.setIntArray("hatchFacings", HologramLocalAuthorConfigurations.legalFacings(cell));
            report.setInteger(
                "hatchFacing",
                cell.has("facing") ? cell.get("facing")
                    .getAsInt() : -1);
            boolean complete = cell.has("authorDeclarationComplete") && cell.get("authorDeclarationComplete")
                .getAsBoolean();
            report.setBoolean("candidateComplete", complete);
            if (!complete) report.setString("candidateBoundary", "仅列出已保存并通过本格验证的声明候选；未声称原生枚举完整");
            return report;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Server candidate review failed: " + failure.getMessage(), failure);
        }
    }

    /** Selected source configuration preserves the existing 8192-cell product limit and yields between cells. */
    public static final class ConfigurationTask implements NativeTask {

        private final Thread owner = Thread.currentThread();
        private final AuthorDocument frozen;
        private final NBTTagCompound policy;
        private final NBTTagList rows;
        private final String active;
        private final boolean pin, custom, sourcePin, restore, cycle;
        private final java.util.LinkedHashMap<Long, AuthorDocument.Cell> selected = new java.util.LinkedHashMap<>();
        private final JsonArray replacements = new JsonArray();
        private java.util.Iterator<java.util.Map.Entry<Long, AuthorDocument.Cell>> remaining;
        private ItemStack sample;
        private int cursor, phase;
        private Result result;
        private boolean closed;

        private ConfigurationTask(AuthorDocument frozen, NBTTagCompound policy) throws IOException {
            if (frozen == null || !frozen.editable() || policy == null)
                throw new IOException("Missing editable selected configuration source");
            this.frozen = frozen;
            // The transport transfers exclusive ownership of its decoded request to this continuation.
            this.policy = policy;
            String operation = policy.getString("operation");
            pin = "pin".equals(operation);
            custom = "customPin".equals(operation);
            sourcePin = "sourcePin".equals(operation);
            restore = "clearPin".equals(operation) || "restoreCell".equals(operation);
            cycle = "rotateCell".equals(operation);
            if (!(pin || custom || sourcePin || restore || cycle || "hatchFacing".equals(operation)))
                throw new IOException("Unknown selected configuration operation");
            rows = policy.getTagList("selectedCells", 10);
            if (rows.tagCount() < 1 || rows.tagCount() > 8192)
                throw new IOException("Invalid selected configuration size");
            active = policy.getString("activeStructure");
        }

        @Override
        public boolean step() throws IOException {
            try (AutoCloseable context = HologramExtensionExecution.serverReview()) {
                requireOwner();
                if (closed) throw new IOException("Selected configuration review was cancelled");
                if (result != null) return true;
                long deadline = System.nanoTime() + 2_000_000L;
                if (phase == 0) {
                    for (int work = 0; cursor < rows.tagCount() && work < 256 && System.nanoTime() < deadline; work++) {
                        NBTTagCompound row = rows.getCompoundTagAt(cursor++);
                        if (!row.hasKey("cellId", 4)) throw new IOException("Missing selected source identity");
                        long id = row.getLong("cellId");
                        AuthorDocument.Cell cell = frozen.cell(id);
                        if (cell == null || selected.put(id, cell) != null)
                            throw new IOException("Invalid selected source identity");
                        if (!active.isEmpty() && !cell.memberships()
                            .isEmpty()
                            && !cell.memberships()
                                .contains(active))
                            throw new IOException("Selected cell belongs to a different source structure");
                    }
                    if (cursor == rows.tagCount()) phase = 1;
                    return false;
                }
                if (phase == 1) {
                    if (pin) {
                        AuthorDocument.Cell primary = selected.get(policy.getLong("primaryCellId"));
                        if (primary == null) throw new IOException("Candidate primary source is not selected");
                        java.util.List<ItemStack> candidates = HologramLocalAuthorConfigurations
                            .candidates(primary.sourceCopy());
                        int choice = policy.getInteger("choice");
                        if (choice < 0 || choice >= candidates.size())
                            throw new IOException("Candidate selection changed");
                        sample = candidates.get(choice);
                        if (policy.hasKey("expectedCandidate", 10)) {
                            ItemStack expected = ItemStack
                                .loadItemStackFromNBT(policy.getCompoundTag("expectedCandidate"));
                            if (expected == null || !sample.isItemEqual(expected)
                                || !ItemStack.areItemStackTagsEqual(sample, expected))
                                throw new IOException("Candidate identity changed; reopen the selected source choices");
                        }
                    } else if (custom || sourcePin) {
                        sample = ItemStack.loadItemStackFromNBT(policy.getCompoundTag("sample"));
                        if (sample == null || sample.getItem() == null || sample.stackSize < 1)
                            throw new IOException("Selected sample is no longer available");
                    }
                    remaining = selected.entrySet()
                        .iterator();
                    phase = 2;
                    return false;
                }
                for (int work = 0; remaining.hasNext() && work < 8 && System.nanoTime() < deadline; work++) {
                    java.util.Map.Entry<Long, AuthorDocument.Cell> entry = remaining.next();
                    JsonObject original = entry.getValue()
                        .sourceCopy();
                    JsonObject changed = custom || sourcePin
                        ? HologramLocalAuthorConfigurations.targetExternal(
                            original,
                            sample,
                            entry.getValue()
                                .payloadPositionPending())
                        : pin ? HologramLocalAuthorConfigurations.target(original, sample)
                            : restore ? HologramLocalAuthorConfigurations.restore(original)
                                : HologramLocalAuthorConfigurations
                                    .facing(original, policy.getInteger("facing"), cycle);
                    if (!changed.equals(original)) {
                        JsonObject replacement = new JsonObject();
                        replacement.addProperty("id", entry.getKey());
                        replacement.add("cell", changed);
                        replacements.add(replacement);
                    }
                }
                if (remaining.hasNext()) return false;
                JsonObject delta = new JsonObject();
                delta.add("replacements", replacements);
                delta.add(
                    "headerPatch",
                    replacements.size() == 0 ? new JsonObject()
                        : HologramLocalAuthorConfigurations.invalidateHeader(frozen.headerCopy(), "geometry"));
                result = new Result(new NBTTagCompound(), delta, null);
                return true;
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("Server configuration review failed: " + failure.getMessage(), failure);
            }
        }

        @Override
        public Result result() throws IOException {
            requireOwner();
            if (closed || result == null) throw new IOException("Selected configuration review is not complete");
            return result;
        }

        private void requireOwner() {
            if (Thread.currentThread() != owner)
                throw new IllegalStateException("Configuration review left owner thread");
        }

        @Override
        public void close() {
            requireOwner();
            closed = true;
            remaining = null;
            selected.clear();
            result = null;
        }
    }

    /** Request is the transport's frozen decoded policy; the client document is immutable across the job. */
    public static ConfigurationTask startConfiguration(AuthorDocument frozen, NBTTagCompound policy)
        throws IOException {
        try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
            return new ConfigurationTask(frozen, policy);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Cannot start selected configuration review: " + failure.getMessage(), failure);
        }
    }

    /** The transport owns source/revision/connection fences. The frozen document retains payload origins. */
    public static Result review(AuthorDocument frozen, String operation, NBTTagCompound policy) throws IOException {
        if (frozen == null || !frozen.editable()) throw new IOException("No editable author source");
        try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
            if ("globalNbt".equals(operation))
                return new Result(HologramLocalAuthorSemantics.globalNbtDetails(frozen), null, null);
            if ("cellNbt".equals(operation)) {
                if (policy == null || !policy.hasKey("cellId", 4)
                    || policy.getInteger("offset") < 0
                    || policy.getInteger("offset") > 1_000_000
                    || policy.getInteger("index") < 0) throw new IOException("Invalid selected NBT review page");
                AuthorDocument.Cell cell = frozen.cell(policy.getLong("cellId"));
                if (cell == null) throw new IOException("Selected author cell no longer exists");
                return new Result(
                    HologramLocalAuthorSemantics.inspect(
                        cell.sourceCopy(),
                        frozen.headerCopy(),
                        policy.getInteger("index"),
                        policy.getInteger("offset"),
                        64),
                    null,
                    null);
            }
            if ("inspect".equals(operation)) {
                NBTTagCompound selected = policy == null
                    ? HologramLocalAuthorSemantics.policyChoices(frozen.headerCopy())
                    : policy;
                JsonObject settled = blueprint(HologramAuthorPortableCompiler.settle(frozen, selected));
                return new Result(
                    HologramLocalAuthorSemantics.processingState(settled, selected, frozen.sourceFrame()),
                    null,
                    null);
            }
            if ("configureQuantumGroups".equals(operation)) {
                if (policy == null || !policy.hasKey("groups", 9)) throw new IOException("Missing quantum groups");
                JsonObject configured = HologramLocalAuthorSemantics
                    .configureQuantumGroups(blueprint(frozen.sourceCopy()), policy.getTagList("groups", 10));
                return new Result(
                    HologramLocalAuthorSemantics.processingState(configured, null, frozen.sourceFrame()),
                    configured,
                    null);
            }
            if ("rotate".equals(operation)) {
                HologramAuthorPortableCompiler.requireKnownTransformExtensions(frozen);
                NBTTagCompound selected = policy == null
                    ? HologramLocalAuthorSemantics.policyChoices(frozen.headerCopy())
                    : policy;
                JsonObject settled = blueprint(HologramAuthorPortableCompiler.settle(frozen, selected));
                JsonObject rotated = HologramLocalAuthorSemantics.rotateBlueprint(settled, frozen.sourceFrame());
                return new Result(
                    HologramLocalAuthorSemantics.processingState(rotated, null, frozen.sourceFrame()),
                    rotated,
                    null);
            }
            if (!"process".equals(operation) && !"compile".equals(operation))
                throw new IOException("Unknown extension review operation");
            // Local compilation never invokes AUTHORING_COMPLETE. Run it once, after final sanitization and
            // validation below, against precisely the candidate returned to the client.
            byte[] provisional = HologramAuthorPortableCompiler.compile(frozen, policy);
            JsonObject root = new JsonParser().parse(new String(provisional, StandardCharsets.UTF_8))
                .getAsJsonObject();
            JsonObject raw = blueprint(root);
            HologramPresets.Preset before = HologramLocalAuthorSemantics.preset(raw);
            HologramPresets.Preset admitted = admit(before);
            JsonObject source = HologramLocalAuthorSemantics
                .delta(raw, GSON.toJsonTree(before), GSON.toJsonTree(admitted))
                .getAsJsonObject();
            root.add("blueprint", source);
            byte[] compiled = "compile".equals(operation)
                ? HologramBlueprintSharing.encodeBounded(GSON, root, HologramBlueprintArtifact.MAX_JSON_BYTES)
                : null;
            NBTTagCompound report = HologramLocalAuthorSemantics.processingState(source, policy, frozen.sourceFrame());
            return new Result(report, source, compiled);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Server extension review failed: " + failure.getMessage(), failure);
        }
    }

    /** No callbacks are used to determine whether the compatibility boundary is needed. */
    static boolean hasExternalCallbacks() {
        return HologramNativePlacement.hasExternalAdapters() || com.miaokatze.gtmb.api.ResourceContainers.hasCodecs()
            || com.miaokatze.gtmb.api.BlueprintSnapshots
                .hasProcessors(com.miaokatze.gtmb.api.BlueprintSnapshots.Stage.AUTHORING_COMPLETE);
    }

    /**
     * Only for an exclusively owned candidate AFTER authoritative canonical validation. With no external
     * callbacks, preserve the existing validation path and change receipt metadata only: no duplicate cell copy,
     * validation or native-resource scan. This method is not a replacement for the caller's validation.
     */
    static HologramPresets.Preset admitValidatedOwned(HologramPresets.Preset source) throws IOException {
        if (hasExternalCallbacks()) return admit(source);
        if (!HologramWorkBudget.isServerOwnerThread())
            throw new IOException("Admission requires the server owner thread");
        return clearUntrustedReportsOwned(source);
    }

    /** Pure receipt-only operation on an already validated, exclusively owned candidate; safe on the IO owner. */
    static HologramPresets.Preset clearUntrustedReportsOwned(HologramPresets.Preset source) throws IOException {
        NBTTagCompound receipt = HologramBlueprintNbt.decode(source.processingNbt);
        if (receipt != null && (receipt.hasKey("extensions") || receipt.hasKey("extensionReviewPending"))) {
            receipt.removeTag("extensions");
            receipt.removeTag("extensionReviewPending");
            String cleaned = HologramBlueprintNbt.encodeCanonical(receipt);
            source.processingNbt = cleaned;
        }
        return source;
    }

    /**
     * Final admission of an independently sanitized candidate. Never trust incoming report fields. Incomplete
     * legacy sources may still open for editing, but are not a completed authoring event or a placement approval.
     */
    static HologramPresets.Preset admit(HologramPresets.Preset source) throws IOException {
        try (AutoCloseable owner = HologramExtensionExecution.serverReview()) {
            HologramPresets.Preset result = HologramPresets.copy(source);
            NBTTagCompound receipt = HologramBlueprintNbt.decode(result.processingNbt);
            if (receipt != null) {
                receipt.removeTag("extensions");
                receipt.removeTag("extensionReviewPending");
                result.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
            }
            if (!HologramBlueprintProcessing.complete(result)) return result;
            HologramPresets.validate(result);
            HologramStaticMaterials.Builder resources = new HologramStaticMaterials.Builder();
            for (HologramPresets.Cell cell : result.cells) if (!cell.excluded) resources.add(
                cell.block(),
                cell.metadata,
                cell.stack(),
                HologramBlueprintNbt.decode(cell.tileNbt),
                HologramBlueprintNbt.decode(cell.itemNbt));
            NBTTagCompound reports = HologramExtensionSnapshots.authoring(result);
            if (!reports.hasNoTags()) receipt.setTag("extensions", reports);
            result.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
            return result;
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Server extension admission failed: " + failure.getMessage(), failure);
        }
    }

    private static JsonObject blueprint(JsonObject root) throws IOException {
        JsonElement value = root.has("blueprint") ? root.get("blueprint") : root;
        if (!value.isJsonObject()) throw new IOException("Missing author blueprint source");
        return value.getAsJsonObject();
    }
}
