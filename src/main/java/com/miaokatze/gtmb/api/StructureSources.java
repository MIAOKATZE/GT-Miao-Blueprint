package com.miaokatze.gtmb.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.gtnewhorizon.structurelib.structure.IStructureDefinition;

/** Alternative declaration sources for standard GT controllers. This API never initiates a world operation. */
public final class StructureSources {

    private static final Map<Class<?>, Entry<?>> SOURCES = new ConcurrentHashMap<>();
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
        private final ReadSet readSet;

        private Resolution(Status status, Plan plan, String failureCode, ReadSet readSet, String sourceId,
            long sourceRevision) {
            this.status = status;
            this.plan = plan;
            this.failureCode = failureCode;
            this.readSet = readSet;
            this.sourceId = sourceId;
            this.sourceRevision = sourceRevision;
        }

        public static Resolution absent() {
            return new Resolution(Status.ABSENT, null, "", null, "", -1);
        }

        public static Resolution ready(Plan plan, ReadSet readSet) {
            if (plan == null) throw new IllegalArgumentException("structure declaration");
            return new Resolution(Status.READY, plan, "", readSet, "", -1);
        }

        public static Resolution unsupported(String failureCode) {
            if (failureCode == null || failureCode.isEmpty()) throw new IllegalArgumentException("failure code");
            return new Resolution(Status.UNSUPPORTED, null, failureCode, null, "", -1);
        }

        private Resolution bind(String id, long revision) {
            return new Resolution(status, plan, failureCode, readSet, id, revision);
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
        return install(id, type, new Entry<>(id, source, null));
    }

    public static <C> GTMBExtensions.Registration registerContextual(String id, Class<C> type,
        ContextualSource<C> source) {
        if (type == null || source == null) throw new IllegalArgumentException("source");
        return install(id, type, new Entry<>(id, null, source));
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
        if (entry == null) return Resolution.absent()
            .bind("", revision);
        if (trigger == null)
            return (entry.contextual == null ? Resolution.absent() : Resolution.unsupported("SOURCE_TRIGGER_MISSING"))
                .bind(entry.id, revision);
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
        return result.bind(entry.id, revision);
    }

    private static final class Entry<C> {

        final String id;
        final Source<C> legacy;
        final ContextualSource<C> contextual;

        Entry(String id, Source<C> legacy, ContextualSource<C> contextual) {
            this.id = id;
            this.legacy = legacy;
            this.contextual = contextual;
        }
    }
}
