package com.miaokatze.gtmb.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

/** Alternative declaration sources for standard GT controllers. This API never initiates a world operation. */
public final class StructureSources {

    private static final Map<Class<?>, Entry<?>> SOURCES = new ConcurrentHashMap<>();
    private static final List<BoundaryEntry> BOUNDARIES = new CopyOnWriteArrayList<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private StructureSources() {}

    public interface Source<C> {

        /** Read declarations only. Null delegates to the native construct/buildPiece capture path. */
        Plan describe(C controller, ItemStack detachedTrigger);
    }

    public enum CaptureIntent {
        LIVE_SNAPSHOT,
        NEW_TEMPLATE
    }

    /** A matched source must explicitly reject unsupported requests; null never delegates from this contract. */
    public interface ContextualSource<C> {

        Resolution describe(C controller, ItemStack detachedTrigger, CaptureIntent intent);
    }

    /**
     * Explicit, reviewed source rules. Inputs are disposable declaration data, never a live controller, session or
     * world. Implementations must not call native checks, factories, placement or controller callbacks.
     */
    public interface Policy {

        String validate(ValidationPlan plan);

        /** Finite, reviewed parameter domain used for area matching without native constructor probes. */
        default List<ItemStack> inferenceTriggers(ItemStack detachedSeed) {
            return Collections.singletonList(detachedSeed);
        }

        /** Additional rows in the existing channel-options format. Rows are presentation, never acceptance rules. */
        default NBTTagList channels(ItemStack detachedTrigger) {
            return new NBTTagList();
        }

        /** Do not load physical controller NBT when a fresh declaration instance is sufficient. */
        default boolean freshControllerOnly() {
            return false;
        }
    }

    /** A detached plan input. Cell elements retain only the immutable declaration identities of this capture. */
    public static final class ValidationPlan {

        public final String sourceId, sourceClass, structureId;
        public final int controllerId;
        public final CaptureIntent intent;
        public final boolean saved, fresh;
        public final List<ValidationCell> cells;
        private final NBTTagCompound metadata;
        private final NBTTagCompound savedMetadata;

        public ValidationPlan(String sourceId, String sourceClass, String structureId, int controllerId,
            CaptureIntent intent, boolean saved, boolean fresh, NBTTagCompound metadata, NBTTagCompound savedMetadata,
            List<ValidationCell> cells) {
            if (sourceId == null || sourceClass == null
                || structureId == null
                || intent == null
                || metadata == null
                || savedMetadata == null
                || cells == null
                || cells.contains(null)) throw new IllegalArgumentException("validation plan");
            this.sourceId = sourceId;
            this.sourceClass = sourceClass;
            this.structureId = structureId;
            this.controllerId = controllerId;
            this.intent = intent;
            this.saved = saved;
            this.fresh = fresh;
            this.metadata = (NBTTagCompound) metadata.copy();
            this.savedMetadata = (NBTTagCompound) savedMetadata.copy();
            this.cells = Collections.unmodifiableList(new ArrayList<>(cells));
        }

        public NBTTagCompound metadata() {
            return (NBTTagCompound) metadata.copy();
        }

        public NBTTagCompound savedMetadata() {
            return (NBTTagCompound) savedMetadata.copy();
        }
    }

    /** Read-only chosen target and observations needed by whole-structure rules; no world access is supplied. */
    public static final class ValidationCell {

        public final IStructureElement<?> element;
        public final int x, y, z, facing;
        public final boolean anchor, canonicalAir, templateController, automaticTarget, targetMatchesWorld;
        private final ItemStack target;

        public ValidationCell(IStructureElement<?> element, int x, int y, int z, int facing, boolean anchor,
            boolean canonicalAir, boolean templateController, boolean automaticTarget, boolean targetMatchesWorld,
            ItemStack target) {
            this.element = element;
            this.x = x;
            this.y = y;
            this.z = z;
            this.facing = facing;
            this.anchor = anchor;
            this.canonicalAir = canonicalAir;
            this.templateController = templateController;
            this.automaticTarget = automaticTarget;
            this.targetMatchesWorld = targetMatchesWorld;
            this.target = target == null ? null : target.copy();
        }

        public ItemStack target() {
            return target == null ? null : target.copy();
        }
    }

    /** A policy bound to one exact active registration. Saved NBT cannot manufacture this binding. */
    public static final class PolicyBinding {

        public final String sourceId;
        public final Class<?> controllerType;
        private final Entry<?> entry;
        private final long revision;

        private PolicyBinding(Entry<?> entry, long revision) {
            this.sourceId = entry.id;
            this.controllerType = entry.type;
            this.entry = entry;
            this.revision = revision;
        }

        public String validityFailure() {
            return revision != REVISION.get() || SOURCES.get(controllerType) != entry ? "SOURCE_REVISION_CHANGED"
                : null;
        }

        public String validate(ValidationPlan plan) {
            String failure = validityFailure();
            if (failure != null) return failure;
            if (!sourceId.equals(plan.sourceId) || !controllerType.getName()
                .equals(plan.sourceClass)) return "SOURCE_IDENTITY_MISMATCH";
            try {
                failure = entry.policy.validate(plan);
                return failure == null || failure.isEmpty() ? validityFailure() : failure;
            } catch (RuntimeException | LinkageError invalid) {
                return "SOURCE_POLICY_FAILED";
            }
        }

        public List<ItemStack> inferenceTriggers(ItemStack seed) {
            if (validityFailure() != null) return Collections.emptyList();
            List<ItemStack> described = entry.policy.inferenceTriggers(seed.copy());
            if (described == null) throw new IllegalStateException("Missing inference domain");
            List<ItemStack> result = new ArrayList<>(described.size());
            for (ItemStack trigger : described) {
                if (trigger == null) throw new IllegalStateException("Missing inference trigger");
                result.add(trigger.copy());
            }
            return Collections.unmodifiableList(result);
        }

        public NBTTagList channels(ItemStack trigger) {
            NBTTagList rows = entry.policy.channels(trigger.copy());
            if (rows == null) throw new IllegalStateException("Missing source channels");
            return (NBTTagList) rows.copy();
        }

        public boolean freshControllerOnly() {
            return entry.policy.freshControllerOnly();
        }
    }

    /** Re-read only the state used to produce a plan. Null means unchanged; otherwise return a stable failure code. */
    public interface ReadSet {

        String invalidReason();
    }

    public enum Status {
        ABSENT,
        READY,
        UNSUPPORTED
    }

    public static final class Resolution {

        public final Status status;
        public final Plan plan;
        public final String failureCode;
        public final String sourceId;
        public final long sourceRevision;
        public final PolicyBinding policy;
        private final ReadSet readSet;

        private Resolution(Status status, Plan plan, String failureCode, ReadSet readSet, String sourceId,
            long sourceRevision, PolicyBinding policy) {
            this.status = status;
            this.plan = plan;
            this.failureCode = failureCode;
            this.readSet = readSet;
            this.sourceId = sourceId;
            this.sourceRevision = sourceRevision;
            this.policy = policy;
        }

        public static Resolution absent() {
            return new Resolution(Status.ABSENT, null, "", null, "", -1, null);
        }

        public static Resolution ready(Plan plan, ReadSet readSet) {
            if (plan == null) throw new IllegalArgumentException("structure declaration");
            return new Resolution(Status.READY, plan, "", readSet, "", -1, null);
        }

        public static Resolution unsupported(String failureCode) {
            if (failureCode == null || failureCode.isEmpty()) throw new IllegalArgumentException("failure code");
            return new Resolution(Status.UNSUPPORTED, null, failureCode, null, "", -1, null);
        }

        private Resolution bind(String id, long revision, PolicyBinding policy) {
            return new Resolution(status, plan, failureCode, readSet, id, revision, policy);
        }

        /** Does not describe again, rebuild a definition, or invoke a machine construct/check callback. */
        public String validityFailure() {
            return validityFailure(sourceRevision, status == Status.UNSUPPORTED ? failureCode : null, readSet);
        }

        /** A lineage guard can retain this proof without retaining the declaration graph in this resolution. */
        public ReadSet validityProof() {
            long revision = sourceRevision;
            String rejection = status == Status.UNSUPPORTED ? failureCode : null;
            ReadSet observed = readSet;
            return () -> validityFailure(revision, rejection, observed);
        }

        private static String validityFailure(long revision, String rejection, ReadSet observed) {
            if (rejection != null) return rejection;
            if (revision != REVISION.get()) return "SOURCE_REVISION_CHANGED";
            if (observed == null) return null;
            try {
                String reason = observed.invalidReason();
                return reason == null || reason.isEmpty() ? null : reason;
            } catch (RuntimeException | LinkageError failure) {
                return "SOURCE_READ_SET_FAILED";
            }
        }
    }

    /** Keeps explicit source rejection explicit for callers of the original Plan-returning API. */
    public static final class UnsupportedSourceException extends IllegalStateException {

        private static final long serialVersionUID = 1L;
        public final String failureCode;

        private UnsupportedSourceException(String failureCode) {
            super(failureCode);
            this.failureCode = failureCode;
        }
    }

    public static final class Piece {

        public final String name;
        public final int a, b, c;
        private final ItemStack trigger;

        public Piece(String name, int a, int b, int c, ItemStack trigger) {
            if (name == null || name.isEmpty() || trigger == null) throw new IllegalArgumentException("piece");
            this.name = name;
            this.a = a;
            this.b = b;
            this.c = c;
            this.trigger = trigger.copy();
        }

        public ItemStack trigger() {
            return trigger.copy();
        }
    }

    public static final class Plan {

        /** Treat the complete StructureLib declaration graph as immutable for this capture. */
        public final IStructureDefinition<?> definition;
        public final List<Piece> pieces;
        private final NBTTagCompound metadata;

        public Plan(IStructureDefinition<?> definition, List<Piece> pieces) {
            this(definition, pieces, new NBTTagCompound());
        }

        public Plan(IStructureDefinition<?> definition, List<Piece> pieces, NBTTagCompound metadata) {
            if (definition == null || pieces == null || pieces.isEmpty() || pieces.contains(null))
                throw new IllegalArgumentException("structure declaration");
            if (metadata == null) throw new IllegalArgumentException("source metadata");
            this.definition = definition;
            this.pieces = Collections.unmodifiableList(new ArrayList<>(pieces));
            this.metadata = (NBTTagCompound) metadata.copy();
        }

        public NBTTagCompound metadata() {
            return (NBTTagCompound) metadata.copy();
        }
    }

    /** Exact classes only: subclasses may have different acceptance rules. No global class-name heuristics. */
    public static <C> GTMBExtensions.Registration register(String id, Class<C> type, Source<C> source) {
        if (type == null || source == null) throw new IllegalArgumentException("source");
        return install(id, type, new Entry<>(id, type, source, null, null));
    }

    public static <C> GTMBExtensions.Registration registerContextual(String id, Class<C> type,
        ContextualSource<C> source) {
        if (type == null || source == null) throw new IllegalArgumentException("source");
        return registerContextual(id, type, source, null);
    }

    public static <C> GTMBExtensions.Registration registerContextual(String id, Class<C> type,
        ContextualSource<C> source, Policy policy) {
        if (type == null || source == null) throw new IllegalArgumentException("source");
        return install(id, type, new Entry<>(id, type, null, source, policy));
    }

    /**
     * A type-name-only family boundary. Return null for unrelated types, otherwise a stable rejection when no
     * reviewed policy is registered. Keep this handle independent of optional source handles.
     */
    public static GTMBExtensions.Registration registerBoundary(String id, Function<String, String> boundary) {
        if (boundary == null) throw new IllegalArgumentException("source boundary");
        return GTMBExtensions.install(id, "structure-source-boundary", () -> {
            BoundaryEntry entry = new BoundaryEntry(boundary);
            BOUNDARIES.add(entry);
            REVISION.incrementAndGet();
            return () -> { if (BOUNDARIES.remove(entry)) REVISION.incrementAndGet(); };
        });
    }

    public static PolicyBinding policy(Class<?> type) {
        Entry<?> entry = type == null ? null : SOURCES.get(type);
        return entry == null || entry.policy == null ? null : new PolicyBinding(entry, REVISION.get());
    }

    public static PolicyBinding policy(String typeName) {
        if (typeName == null) return null;
        for (Entry<?> entry : SOURCES.values()) if (entry.policy != null && entry.type.getName()
            .equals(typeName)) return new PolicyBinding(entry, REVISION.get());
        return null;
    }

    /** Checked before native capture, controller NBT loading and saved-template construction admission. */
    public static String sourceFailure(String typeName) {
        if (typeName == null || policy(typeName) != null) return null;
        return boundaryFailure(typeName);
    }

    public static String sourceFailure(Class<?> type) {
        return type == null || policy(type) != null ? null : boundaryFailure(type.getName());
    }

    private static String boundaryFailure(String typeName) {
        for (BoundaryEntry boundary : BOUNDARIES) {
            String failure;
            try {
                failure = boundary.reader.apply(typeName);
            } catch (RuntimeException | LinkageError invalid) {
                return "SOURCE_BOUNDARY_FAILED";
            }
            if (failure != null && !failure.isEmpty()) return failure;
        }
        return null;
    }

    private static <C> GTMBExtensions.Registration install(String id, Class<C> type, Entry<C> entry) {
        return GTMBExtensions.install(id, "structure-source", () -> {
            if (SOURCES.putIfAbsent(type, entry) != null)
                throw new IllegalArgumentException("Source already registered");
            REVISION.incrementAndGet();
            return () -> { if (SOURCES.remove(type, entry)) REVISION.incrementAndGet(); };
        });
    }

    public static long revision() {
        return REVISION.get();
    }

    public static boolean hasSource(Class<?> type) {
        return type != null && SOURCES.containsKey(type);
    }

    /** Declaration query used by capture; result is scoped to that invocation and never cached across captures. */
    public static Plan resolve(Object controller, ItemStack trigger) {
        if (controller == null || trigger == null) return null;
        Resolution result = resolve(controller, trigger, CaptureIntent.NEW_TEMPLATE);
        if (result.status == Status.UNSUPPORTED) throw new UnsupportedSourceException(result.failureCode);
        return result.plan;
    }

    @SuppressWarnings("unchecked")
    public static Resolution resolve(Object controller, ItemStack trigger, CaptureIntent intent) {
        if (intent == null) throw new IllegalArgumentException("capture intent");
        long revision = REVISION.get();
        Entry<Object> entry = controller == null ? null : (Entry<Object>) SOURCES.get(controller.getClass());
        String boundary = controller == null ? null : sourceFailure(controller.getClass());
        if (boundary != null) return Resolution.unsupported(boundary)
            .bind("", revision, null);
        if (entry == null) return Resolution.absent()
            .bind("", revision, null);
        PolicyBinding policy = entry.policy == null ? null : new PolicyBinding(entry, revision);
        if (trigger == null)
            return (entry.contextual == null ? Resolution.absent() : Resolution.unsupported("SOURCE_TRIGGER_MISSING"))
                .bind(entry.id, revision, policy);
        Resolution result;
        if (entry.contextual != null) {
            try {
                result = entry.contextual.describe(controller, trigger.copy(), intent);
            } catch (RuntimeException | LinkageError failure) {
                result = Resolution.unsupported("SOURCE_DESCRIPTION_FAILED");
            }
            if (result == null || result.status == Status.ABSENT)
                result = Resolution.unsupported("SOURCE_NO_DECLARATION");
        } else {
            Plan plan = entry.legacy.describe(controller, trigger.copy());
            result = plan == null ? Resolution.absent() : Resolution.ready(plan, null);
        }
        if (REVISION.get() != revision || SOURCES.get(controller.getClass()) != entry)
            result = Resolution.unsupported("SOURCE_REVISION_CHANGED");
        return result.bind(entry.id, revision, policy);
    }

    private static final class Entry<C> {

        final String id;
        final Class<C> type;
        final Source<C> legacy;
        final ContextualSource<C> contextual;
        final Policy policy;

        Entry(String id, Class<C> type, Source<C> legacy, ContextualSource<C> contextual, Policy policy) {
            this.id = id;
            this.type = type;
            this.legacy = legacy;
            this.contextual = contextual;
            this.policy = policy;
        }
    }

    private static final class BoundaryEntry {

        final Function<String, String> reader;

        BoundaryEntry(Function<String, String> reader) {
            this.reader = reader;
        }
    }
}
