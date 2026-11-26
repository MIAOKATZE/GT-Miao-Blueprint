// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureDefinition;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;
import com.miaokatze.gtmb.api.Declarations;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Hatch;
import com.miaokatze.gtmb.hologram.HologramDeclaredNativeSelection.Offset;
import com.miaokatze.gtmb.hologram.HologramStockNativeConstruction.PermitAdmission;
import com.miaokatze.gtmb.hologram.HologramStockNativeConstruction.State;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.GTStructureUtility;
import gregtech.common.blocks.ItemMachines;

/** The server resolves one named registered definition. Only this owner (not a client geometry row) makes permits. */
final class HologramDeclaredNativeConstruction {

    @Desugar
    private record DefinitionPiece(IStructureElement<?>[] elements, HologramCapture.Piece piece) {}

    @Desugar
    private record Member(DefinitionPiece definition, int index, IStructureElement<?> element, Offset position,
        int ordinal) {}

    /**
     * Small, world-independent topology memo. StructureLib exposes its backing arrays, so prove exact slot
     * identity on reuse instead of trusting array identity. This does not rerun navigation, hashing or grouping.
     */
    private static final class NativeTopology {

        private static final int MAX_WORDS = 65536;
        private static final int MAX_ENTRIES = 2;
        private static final List<NativeTopology> RECENT = new ArrayList<>();
        private static final ClassValue<Boolean> IMMUTABLE_NAVIGATION = new ClassValue<Boolean>() {

            @Override
            protected Boolean computeValue(Class<?> type) {
                try {
                    if (type.getMethod("isNavigating")
                        .getDeclaringClass() == IStructureElement.class) return true;
                    var factory = HologramDeclarationWrappers.factory(type);
                    return factory != null
                        && factory.getDeclaringClass() == com.gtnewhorizon.structurelib.structure.StructureUtility.class
                        && ("stepA".equals(factory.getName()) || "stepB".equals(factory.getName())
                            || "stepC".equals(factory.getName()));
                } catch (ReflectiveOperationException | RuntimeException | LinkageError unsupported) {
                    return false;
                }
            }
        };

        final Map<Offset, Member> members = new LinkedHashMap<>();
        final Map<Integer, List<Member>> layers = new LinkedHashMap<>();
        final List<List<Member>> groups = new ArrayList<>();
        final List<DefinitionPiece> pieces;
        private final IStructureElement<?>[][] snapshots;
        private final IStructureDefinition<?> definition;
        private final ExtendedFacing facing;
        private final long sourceRevision, adapterGeneration;
        private boolean reusable;

        NativeTopology(IStructureDefinition<?> definition, List<DefinitionPiece> pieces, ExtendedFacing facing,
            long sourceRevision, long adapterGeneration) {
            this.definition = definition;
            this.pieces = pieces;
            this.facing = facing;
            this.sourceRevision = sourceRevision;
            this.adapterGeneration = adapterGeneration;
            long words = 0;
            for (var piece : pieces) words += piece.elements.length;
            reusable = definition.getClass() == com.gtnewhorizon.structurelib.structure.StructureDefinition.class
                && words <= MAX_WORDS;
            snapshots = reusable ? new IStructureElement<?>[pieces.size()][] : null;
        }

        private boolean matches(IStructureDefinition<?> expected, List<DefinitionPiece> actual, ExtendedFacing pose,
            long source, long adapters) {
            if (expected != definition || facing != pose
                || source != sourceRevision
                || adapters != adapterGeneration
                || actual.size() != pieces.size()) return false;
            for (int i = 0; i < pieces.size(); i++) {
                var prior = pieces.get(i);
                var fresh = actual.get(i);
                if (prior.elements != fresh.elements || !prior.piece.name.equals(fresh.piece.name)
                    || prior.piece.a != fresh.piece.a
                    || prior.piece.b != fresh.piece.b
                    || prior.piece.c != fresh.piece.c
                    || !equalSignal(prior.piece.trigger, fresh.piece.trigger)) return false;
                var snapshot = snapshots[i];
                if (snapshot.length != fresh.elements.length) return false;
                for (int n = 0; n < snapshot.length; n++) if (snapshot[n] != fresh.elements[n]) return false;
            }
            return true;
        }

        static synchronized NativeTopology find(IStructureDefinition<?> definition, List<DefinitionPiece> pieces,
            ExtendedFacing facing, long sourceRevision, long adapterGeneration) {
            for (int i = RECENT.size() - 1; i >= 0; i--) {
                var candidate = RECENT.get(i);
                if (!candidate.matches(definition, pieces, facing, sourceRevision, adapterGeneration)) continue;
                RECENT.remove(i);
                RECENT.add(candidate);
                return candidate;
            }
            return null;
        }

        void offer() {
            if (!reusable || adapterGeneration != HologramDeclarationAdapters.admissionGeneration()
                || !matches(definition, pieces, facing, sourceRevision, adapterGeneration)) return;
            publish(this);
        }

        private static synchronized void publish(NativeTopology topology) {
            if (RECENT.size() >= MAX_ENTRIES) RECENT.remove(0);
            RECENT.add(topology);
        }

        IStructureElement<?>[] elements(int pieceIndex) {
            var elements = pieces.get(pieceIndex).elements;
            if (!reusable) return elements;
            return snapshots[pieceIndex] = elements.clone();
        }

        void observeNavigation(IStructureElement<?> element) {
            reusable &= element != null && IMMUTABLE_NAVIGATION.get(element.getClass());
        }
    }

    @Desugar
    private record Target(HologramCapture.Cell cell, ItemStack item, int facing, boolean hatch,
        HologramDeclaredNativeMutation.Proof original, boolean rotation,
        HologramDeclaredNativeMutation.CasingProof casing) {

        Target(HologramCapture.Cell cell, ItemStack item, int facing, boolean hatch,
            HologramDeclaredNativeMutation.Proof original, boolean rotation) {
            this(cell, item, facing, hatch, original, rotation, null);
        }
    }

    @Desugar
    private record PreparedWrite(Member member, Target target) {}

    /** One native element instance and effective signal; positions never repeat declaration resolution. */
    private static final class DeclarationBatch {

        final List<Member> positions;
        List<PreparedWrite> writes;
        ItemStack fixedMaterial;
        private long airGeneration = Long.MIN_VALUE;
        private boolean nativeAir, dynamicAir;

        DeclarationBatch(List<Member> positions) {
            this.positions = positions;
        }

        boolean isNativeAir(IStructureElement<?> element) {
            long generation = HologramDeclarationAdapters.admissionGeneration();
            if (airGeneration == generation) return dynamicAir ? HologramElementCatalog.nativeAir(element) : nativeAir;
            var stability = new HologramElementCatalog.AirStability();
            nativeAir = HologramElementCatalog.nativeAir(element, stability);
            dynamicAir = !stability.reusable;
            airGeneration = generation;
            return nativeAir;
        }
    }

    /** A scheduler may yield at a declaration-group or a position boundary without replaying the cursor. */
    @FunctionalInterface
    interface PlacementWindow {

        PlacementWindow ALL = (next, total) -> total;

        int endExclusive(int next, int total);

        default int groupEndExclusive(int group, int nextPosition, int totalPositions) {
            return totalPositions;
        }
    }

    private static final class Need {

        final ItemStack specimen;
        int count;

        Need(ItemStack specimen) {
            this.specimen = specimen;
        }
    }

    private final HologramWorkspace.ActionEndpoint endpoint;
    private final HologramConstructionTaskGate gate;
    private final HologramConstructionTaskGate.Lease lease;
    private final HologramSession session;
    private final HologramDeclaredNativeSelection selection;
    private final BooleanSupplier stillConfirmed;
    private final Timing timing = Boolean.getBoolean("gtmb.hologram.declaredNativeTiming") ? new Timing() : null;
    private final HologramPlacementTiming trace;
    private final boolean creative;
    private final BaseMetaTileEntity tile;
    private final MTEEnhancedMultiBlockBase<?> machine;
    private final IMetaTileEntity prototype;
    private final ExtendedFacing facing;
    private final int ax, ay, az;
    private final long epoch, revision, interpretations;
    private final ItemStack trigger;
    private final Map<Offset, Hatch> edits = new LinkedHashMap<>();
    private final Set<Offset> selectedOffsets;
    private final IdentityHashMap<DefinitionPiece, IdentityHashMap<IStructureElement<?>, HologramCasingFallback.Target>> constantBlockDefaults = new IdentityHashMap<>();
    private final List<DeclarationBatch> batches = new ArrayList<>();
    private final IdentityHashMap<DefinitionPiece, IdentityHashMap<IStructureElement<?>, List<Member>>> groupsByPiece = new IdentityHashMap<>();
    private final List<Target> moves = new ArrayList<>();
    private int batchIndex, batchOffset, plannedWrites, declaredMembers;
    private int prepareBatch, prepareOffset;
    private boolean placementFunded, traceEnded;
    private Target currentBatchTarget;
    private final Map<HologramBlueprintResources.ExactKey, Need> funding = new LinkedHashMap<>();
    private final HologramDeclarationAdapters.Bindings declarations = new HologramDeclarationAdapters.Bindings();
    private final HologramMaterials.Context stock, demolition;
    private final java.util.NavigableMap<Offset, Target> removalOrder = new java.util.TreeMap<>(
        java.util.Comparator.comparingInt(Offset::y)
            .reversed()
            .thenComparingInt(Offset::z)
            .thenComparingInt(Offset::x));
    private Iterator<Target> removals;
    private Target activeRemoval;
    private StructureSources.Resolution source;
    private MTEEnhancedMultiBlockBase<?> detached;
    private IStructureDefinition<?> definition;
    private List<DefinitionPiece> pieces;
    private NativeTopology topology;
    private HologramStructureCursor cursor;
    private IStructureElement<?>[] cursorElements;
    private Iterator<Member> memberIterator;
    private Iterator<Need> needs;
    private String family, reason = "";
    private int pieceIndex, targets, next, placed, examined;
    private State state = State.ACTIVE;
    private HologramConstructionTaskGate.Permit unsettled;

    HologramDeclaredNativeConstruction(HologramWorkspace.ActionEndpoint endpoint, HologramConstructionTaskGate gate,
        HologramConstructionTaskGate.Lease lease, HologramSession session, HologramDeclaredNativeSelection selection,
        boolean creative, BooleanSupplier stillConfirmed) {
        this.endpoint = endpoint;
        this.gate = gate;
        this.lease = lease;
        trace = HologramPlacementTiming.open("structure", lease.request.id);
        this.session = session;
        this.selection = selection;
        selectedOffsets = selection.scope() == 2 ? new HashSet<>(selection.selected()) : Collections.emptySet();
        this.stillConfirmed = stillConfirmed;
        if (creative && selection.operation() != 0)
            throw new IllegalArgumentException("Creative placement requires ordinary construction");
        this.creative = creative;
        tile = (BaseMetaTileEntity) session.target;
        machine = session.machine;
        facing = machine.getExtendedFacing();
        prototype = GregTechAPI.METATILEENTITIES[selection.controller()];
        ax = session.x;
        ay = session.y;
        az = session.z;
        epoch = session.editorWorkspaceEpoch;
        revision = session.planRevision;
        interpretations = HologramDeclarationAdapters.generation();
        trigger = HologramSessionPolicy.trigger(session);
        for (Hatch edit : selection.hatches()) edits.put(edit.position(), edit);
        stock = new HologramMaterials.Context(session.player, session.materialSettings, session.slot);
        // Retain the same source from funding to the scoped world transaction, not one discovery per cell.
        if (selection.operation() == 0) session.materialContext = stock;
        demolition = selection.operation() == 2
            ? HologramDemolition.recoveryContext(session.player, session.slot, selection.destination())
            : null;
    }

    private boolean bound(HologramCapture scratch) {
        var player = session.player;
        return state == State.ACTIVE && player.capabilities.isCreativeMode == creative
            && stillConfirmed.getAsBoolean()
            && HologramWorkspace.find(endpoint.actor) == session
            && session.capture == scratch
            && session.editorWorkspaceEpoch == epoch
            && session.planRevision == revision
            && session.x == ax
            && session.y == ay
            && session.z == az
            && session.mode == selection.operation()
            && !session.virtual
            && session.facing == facing.ordinal()
            && !tile.isInvalid()
            && tile.getWorldObj() == endpoint.world
            && tile.getMetaTileID() == selection.controller()
            && tile.getMetaTileEntity() == machine
            && machine.getBaseMetaTileEntity() == tile
            && machine.getExtendedFacing() == facing
            && GregTechAPI.METATILEENTITIES[selection.controller()] == prototype
            && player.getDistanceSq(ax + .5, ay + .5, az + .5) <= 64
            && interpretations == HologramDeclarationAdapters.generation();
    }

    private boolean current(HologramCapture scratch) {
        return bound(scratch) && (source == null || source.validityFailure() == null)
            && declarations.validityFailure() == null
            && endpoint.world.blockExists(ax, ay, az)
            && endpoint.world.getBlock(ax, ay, az) == GregTechAPI.sBlockMachines
            && endpoint.world.getTileEntity(ax, ay, az) == tile
            && bound(scratch);
    }

    private void resolve() throws IOException {
        detached = HologramDetachedController.fromLive(machine);
        source = StructureSources.resolve(detached, trigger, StructureSources.CaptureIntent.NEW_TEMPLATE);
        var resolvedPieces = new ArrayList<HologramCapture.Piece>();
        if (source.status == StructureSources.Status.UNSUPPORTED || source.validityFailure() != null)
            throw refused("控制器未提供当前可读的原生声明。");
        if (source.status == StructureSources.Status.READY) {
            definition = source.plan.definition;
            for (var piece : source.plan.pieces)
                resolvedPieces.add(new HologramCapture.Piece(piece.name, piece.a, piece.b, piece.c, piece.trigger()));
        } else {
            var recorded = HologramCapture.declarationPieces((IConstructable) detached, trigger);
            if (recorded.incomplete || !recorded.failure.isEmpty()) throw refused("无法读取原生控制器的片段声明。");
            resolvedPieces.addAll(recorded.pieces);
            definition = recorded.definition == null ? detached.getStructureDefinition() : recorded.definition;
        }
        if (definition == null || !HologramStructureCursor.supports(definition)
            || resolvedPieces.isEmpty()
            || resolvedPieces.size() > 4096) throw refused("未取得原生片段或声明使用自定义遍历；未施工。");
        var accepted = new ArrayList<DefinitionPiece>();
        for (var piece : resolvedPieces) {
            var elements = definition.getStructureFor(piece.name);
            if (elements == null || !relative(piece.a, piece.b, piece.c)) throw refused("片段坐标不在服务器支持的范围。");
            accepted.add(new DefinitionPiece(elements, piece));
        }
        pieces = Collections.unmodifiableList(new ArrayList<>(accepted));
        long adapters = HologramDeclarationAdapters.admissionGeneration();
        topology = NativeTopology.find(definition, pieces, facing, source.sourceRevision, adapters);
        if (topology != null) {
            pieceIndex = pieces.size();
            declaredMembers = topology.members.size();
        } else topology = new NativeTopology(definition, pieces, facing, source.sourceRevision, adapters);
    }

    private enum Stage {
        RESOLVE,
        DECLARATION,
        WORLD_PREPARE,
        FUNDING,
        WORLD_WRITE
    }

    /** Opt-in slice accounting: timestamps at phase boundaries and first commit, never once per position. */
    private final class Timing {

        private final long started = System.nanoTime();
        private final long[] work = new long[Stage.values().length];
        private Stage stage;
        private long stageStart, firstCommit;
        private int slices;
        private boolean ended;

        void enter(Stage next, long now) {
            if (next == stage) return;
            leave(now);
            stage = next;
            stageStart = now;
        }

        void leave(long now) {
            if (stage == null) return;
            work[stage.ordinal()] += Math.max(0, now - stageStart);
            stage = null;
        }

        void committed() {
            if (firstCommit != 0) return;
            long now = System.nanoTime();
            firstCommit = now - started;
            report("first-commit", now);
        }

        void finish(long now) {
            leave(now);
            if (!ended && state != State.ACTIVE) {
                ended = true;
                report("terminal", now);
            }
        }

        private long stageNanos(Stage key, long now) {
            return work[key.ordinal()] + (stage == key ? Math.max(0, now - stageStart) : 0);
        }

        private void report(String event, long now) {
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.info(
                "GTMB declared-native timing event={} controller={} operation={} scope={} creative={} state={} slices={} examined={} unique={} targets={} plannedWrites={} placed={} fundingKinds={} elapsedNs={} firstCommitNs={} resolveNs={} declarationNs={} worldPrepareNs={} fundingNs={} writeNs={}",
                event,
                selection.controller(),
                selection.operation(),
                selection.scope(),
                creative,
                state,
                slices,
                examined,
                placementBuild() ? declaredMembers : topology == null ? 0 : topology.members.size(),
                targets,
                demolition == null ? (placementBuild() ? plannedWrites : moves.size()) : removalOrder.size(),
                placed,
                funding.size(),
                now - started,
                firstCommit,
                stageNanos(Stage.RESOLVE, now),
                stageNanos(Stage.DECLARATION, now),
                stageNanos(Stage.WORLD_PREPARE, now),
                stageNanos(Stage.FUNDING, now),
                stageNanos(Stage.WORLD_WRITE, now));
        }
    }

    private void recordCommitted() {
        placed++;
        if (timing != null) timing.committed();
        if (trace != null) trace.written();
    }

    private void finishTrace() {
        if (trace != null && !traceEnded && state != State.ACTIVE) {
            traceEnded = true;
            trace.finish(state.name(), placed);
        }
    }

    private boolean placementBuild() {
        return selection.operation() == 0;
    }

    /** Follow actual declaration batches; when a position needs a write, settle only that write's cost. */
    private boolean drainPlacement(PermitAdmission admission, long now) throws IOException {
        if (timing != null) timing.enter(Stage.WORLD_WRITE, now);
        if (batchIndex == batches.size()) {
            finishPlacement();
            return false;
        }
        var batch = batches.get(batchIndex);
        int count = creative ? batch.positions.size() : batch.writes == null ? 0 : batch.writes.size();
        if (batchOffset == count) {
            batchIndex++;
            batchOffset = 0;
            return true;
        }
        if (!creative) {
            PreparedWrite write = batch.writes.get(batchOffset);
            requireMemberCurrent(write.member);
            currentBatchTarget = write.target;
        } else if (currentBatchTarget == null) {
            currentBatchTarget = prepare(batch.positions.get(batchOffset), batch);
            if (currentBatchTarget != null) plannedWrites++;
        } else requireMemberCurrent(batch.positions.get(batchOffset));
        if (currentBatchTarget != null && !place(currentBatchTarget, admission)) return false;
        currentBatchTarget = null;
        batchOffset++;
        return true;
    }

    private void finishPlacement() {
        if (targets == 0) {
            stop("所选范围没有投影目标。");
        } else if (!current(null) || !gate.finish(lease, HologramConstructionTaskGate.End.COMPLETE)) {
            stop("施工授权已变化，无法确认完成。");
        } else state = State.COMPLETE;
    }

    // Resolve each projected write once. The stock check visits material kinds, not geometry.
    private void preparePlacementStock() throws IOException {
        if (prepareBatch < batches.size()) {
            var group = batches.get(prepareBatch);
            if (prepareOffset == 0 && selection.scope() == 3 && !family.equals(family(group.positions.get(0)))) {
                prepareBatch++;
                return;
            }
            Member member = group.positions.get(prepareOffset++);
            Target target = prepare(member, group);
            if (target != null) {
                if (group.writes == null) group.writes = new ArrayList<>();
                group.writes.add(new PreparedWrite(member, target));
                funding.computeIfAbsent(HologramValues.stackKey(target.item), ignored -> new Need(target.item)).count++;
                plannedWrites++;
            }
            if (prepareOffset == group.positions.size()) {
                prepareBatch++;
                prepareOffset = 0;
            }
            return;
        }
        if (needs == null) needs = funding.values()
            .iterator();
        if (needs.hasNext()) {
            Need required = needs.next();
            if (stock.count(required.specimen, required.count) != required.count)
                throw refused("材料不足：" + required.specimen.getDisplayName() + " × " + required.count + "。未开始施工。");
            return;
        }
        placementFunded = true;
    }

    State drain(long deadline, PermitAdmission admission) {
        return drain(deadline, admission, PlacementWindow.ALL);
    }

    State drain(long deadline, PermitAdmission admission, PlacementWindow window) {
        int placementEnd = -1, windowGroup = -1, windowPositionEnd = -1;
        if (!gate.onOwnerThread()) throw new IllegalStateException("Native construction owner thread");
        if (state != State.ACTIVE || System.nanoTime() >= deadline) return state;
        if (timing != null) timing.slices++;
        try (var observed = HologramDeclarationAdapters.observe(declarations)) {
            if (!current(null) || HologramConstructionAuthority.hasKnownRecovery(session.player))
                return stop("控制器、材料来源或原工作台已变化。");
            if (pieces == null) {
                if (timing != null) timing.enter(Stage.RESOLVE, System.nanoTime());
                resolve();
            }
            long now;
            while ((now = System.nanoTime()) < deadline) {
                if (memberIterator == null) {
                    if (timing != null) timing.enter(Stage.DECLARATION, now);
                    if (pieceIndex < pieces.size()) {
                        recordMember();
                        continue;
                    }
                    memberIterator = bindSelection();
                    if (timing != null) now = System.nanoTime();
                }
                if (placementBuild()) {
                    if (!creative && !placementFunded) {
                        if (timing != null) timing.enter(Stage.WORLD_PREPARE, now);
                        preparePlacementStock();
                        continue;
                    }
                    if (placementEnd < 0) {
                        placementEnd = window.endExclusive(batchIndex, batches.size());
                        if (placementEnd < batchIndex || placementEnd > batches.size())
                            throw new IllegalArgumentException("Invalid native placement window");
                    }
                    if (batchIndex < batches.size() && batchIndex >= placementEnd) return state;
                    if (batchIndex < batches.size()) {
                        var batch = batches.get(batchIndex);
                        int groupSize = creative ? batch.positions.size()
                            : batch.writes == null ? 0 : batch.writes.size();
                        if (windowGroup != batchIndex) {
                            windowGroup = batchIndex;
                            windowPositionEnd = window.groupEndExclusive(batchIndex, batchOffset, groupSize);
                            if (windowPositionEnd < batchOffset || windowPositionEnd > groupSize)
                                throw new IllegalArgumentException("Invalid native placement group window");
                            if (creative && selection.scope() == 3
                                && batchOffset < groupSize
                                && !family.equals(family(batch.positions.get(0)))) {
                                if (batchOffset != 0 || currentBatchTarget != null) return stop("当前声明材料类型已变化，未继续放置。");
                                batchIndex++;
                                continue;
                            }
                        }
                        if (batchOffset < groupSize && batchOffset >= windowPositionEnd) return state;
                    }
                    if (!drainPlacement(admission, now)) return state;
                    continue;
                }
                if (memberIterator.hasNext()) {
                    if (timing != null) timing.enter(Stage.WORLD_PREPARE, now);
                    prepare(memberIterator.next(), null);
                    continue;
                }
                if (needs == null) {
                    if (targets == 0) return stop("所选范围没有可施工目标。");
                    needs = funding.values()
                        .iterator();
                }
                if (needs.hasNext()) {
                    if (timing != null) timing.enter(Stage.FUNDING, now);
                    Need required = needs.next();
                    if (stock.count(required.specimen, required.count) != required.count)
                        return stop("所选材料来源不足，未开始放置。");
                    if (!current(null)) return stop("材料检查期间施工条件已变化。");
                    continue;
                }
                if (timing != null) timing.enter(Stage.WORLD_WRITE, now);
                if (demolition != null && removals == null) removals = removalOrder.values()
                    .iterator();
                if (demolition != null ? activeRemoval == null && !removals.hasNext() : next == moves.size()) {
                    if (!current(null) || !gate.finish(lease, HologramConstructionTaskGate.End.COMPLETE))
                        return stop("施工许可已变化；无法确认完成。");
                    state = State.COMPLETE;
                    return state;
                }
                if (demolition != null) {
                    if (activeRemoval == null) activeRemoval = removals.next();
                    if (!mutate(activeRemoval, admission)) return state;
                    activeRemoval = null;
                } else {
                    Target target = moves.get(next);
                    if (!(target.rotation ? mutate(target, admission) : place(target, admission))) return state;
                    next++;
                }
            }
        } catch (IOException failure) {
            return stop(failure.getMessage());
        } catch (RuntimeException | LinkageError failure) {
            return stop(
                "原生片段或其材料声明不可用，未继续施工（" + failure.getClass()
                    .getSimpleName() + "）。");
        } finally {
            if (timing != null) timing.finish(System.nanoTime());
            finishTrace();
        }
        return state;
    }

    private void recordMember() throws IOException {
        DefinitionPiece piece = pieces.get(pieceIndex);
        if (cursor == null) {
            cursorElements = topology.elements(pieceIndex);
            cursor = new HologramStructureCursor(cursorElements, piece.piece.a, piece.piece.b, piece.piece.c);
        }
        if (cursor.done() == cursor.total()) {
            cursor = null;
            cursorElements = null;
            pieceIndex++;
            if (pieceIndex == pieces.size()) topology.offer();
            return;
        }
        int ordinal = examined++;
        if (ordinal >= HologramBlueprintLimits.MAX_CELLS) throw refused("原生片段超过声明复杂度边界。");
        int index = cursor.done();
        var element = cursorElements[index];
        topology.observeNavigation(element);
        var point = cursor.declarationOffset(facing);
        if (point == null) return; // StructureLib navigation.
        if (!point.valid()) throw refused("原生目标超出相对坐标范围。");
        Member next = new Member(piece, index, element, point, ordinal);
        Member previous = topology.members.putIfAbsent(point, next);
        if (previous != null
            && (previous.element != element || !equalSignal(previous.definition.piece.trigger, piece.piece.trigger)))
            throw refused("原生片段在同一坐标上有冲突声明；未放置任何格。");
        if (previous == null && !anchor(point)) {
            topology.layers.computeIfAbsent(point.y(), ignored -> new ArrayList<>())
                .add(next);
            group(next, topology.groups);
        }
    }

    private void group(Member member, List<List<Member>> groups) {
        var byElement = groupsByPiece.computeIfAbsent(member.definition, ignored -> new IdentityHashMap<>());
        byElement.computeIfAbsent(member.element, ignored -> {
            var created = new ArrayList<Member>();
            groups.add(created);
            return created;
        })
            .add(member);
    }

    private static boolean equalSignal(ItemStack a, ItemStack b) {
        return a.stackSize == b.stackSize && HologramValues.sameMaterial(a, b);
    }

    private Iterator<Member> bindSelection() throws IOException {
        var selected = new ArrayList<Member>(
            selection.selected()
                .size());
        for (Offset position : selection.selected()) {
            Member member = topology.members.get(position);
            if (member == null || anchor(position)) throw refused("选择不属于当前声明的可放置格。");
            selected.add(member);
        }
        if (selection.scope() == 3) {
            family = family(selected.get(0));
            if (family.isEmpty()) throw refused("所选位置不属于可证明的声明材料族。");
        }
        if (selection.operation() == 1 && !edits.keySet()
            .equals(selectedOffsets)) throw refused("换装只处理已逐格指定类型或方向的所选仓室。");
        for (Offset position : edits.keySet()) {
            Member member = topology.members.get(position);
            if (member == null || !includes(member)) throw refused("手选仓室不在已确认的声明范围。");
        }
        var layerMembers = topology.layers.getOrDefault(selection.layer(), Collections.emptyList());
        if (selection.scope() == 2) selected.sort(Comparator.comparingInt(Member::ordinal));
        if (placementBuild()) {
            declaredMembers = topology.members.size();
            if (selection.scope() == 0 || selection.scope() == 3) {
                for (var group : topology.groups) batches.add(new DeclarationBatch(group));
            } else {
                groupsByPiece.clear();
                List<List<Member>> scoped = new ArrayList<>();
                for (var member : selection.scope() == 1 ? layerMembers : selected) group(member, scoped);
                for (var group : scoped) batches.add(new DeclarationBatch(group));
            }
            topology = null;
            groupsByPiece.clear();
            return Collections.emptyIterator();
        }
        // Mutating installed devices keeps exact existing scope guarantees.
        if (selection.scope() == 2) return selected.iterator();
        if (selection.scope() == 1) return layerMembers.iterator();
        return topology.members.values()
            .iterator();
    }

    private boolean includes(Member member) {
        if (anchor(member.position)) return false;
        return switch (selection.scope()) {
            case 0 -> true;
            case 1 -> member.position.y() == selection.layer();
            case 2 -> selectedOffsets.contains(member.position);
            case 3 -> family.equals(family(member));
            default -> false;
        };
    }

    private static boolean anchor(Offset value) {
        return value.x() == 0 && value.y() == 0 && value.z() == 0;
    }

    private static boolean relative(int a, int b, int c) {
        return HologramDeclaredNativeSelection.coordinate(a) && HologramDeclaredNativeSelection.coordinate(b)
            && HologramDeclaredNativeSelection.coordinate(c);
    }

    private static String coordinates(Offset p) {
        return "(" + p.x() + ", " + p.y() + ", " + p.z() + ")";
    }

    private static void requireMemberCurrent(Member member) throws IOException {
        if (member.definition.elements[member.index] != member.element)
            throw refused("原生声明在施工期间已变化：" + coordinates(member.position) + "。");
    }

    private int[] requireHatchFacing(HologramCapture.Cell cell, Hatch edit, ItemStack item) throws IOException {
        int[] legal = HologramCellPolicy.previewHatchFacings(cell, item, detached, facing);
        if (legal.length == 0 || edit.facing() >= 0 && Arrays.stream(legal)
            .noneMatch(d -> d == edit.facing()))
            throw refused("所选仓室的类型、等级或方向不符合该格的原生声明：" + coordinates(edit.position()) + "。");
        return legal;
    }

    private boolean compatibleHatch(HologramCapture.Cell cell, Block block, net.minecraft.tileentity.TileEntity tile) {
        if (block != GregTechAPI.sBlockMachines || !(tile instanceof BaseMetaTileEntity base)
            || base.getMetaTileEntity() == null
            || base.getFrontFacing() == null) return false;
        int installedFacing = base.getFrontFacing()
            .ordinal();
        for (int legal : HologramCellPolicy.previewHatchFacings(cell, hatch(base.getMetaTileID()), detached, facing))
            if (legal == installedFacing) return true;
        return false;
    }

    private String family(Member member) {
        var fallback = HologramCasingFallback.resolve(member.element, member.definition.piece.trigger);
        var group = HologramReplacementFamily
            .resolve(member.element, fallback == null ? null : fallback.block, fallback == null ? 0 : fallback.meta);
        return group == null ? "" : group.id;
    }

    private Target prepare(Member member, DeclarationBatch batch) throws IOException {
        if (batch == null && !includes(member)) return null;
        requireMemberCurrent(member);
        Offset p = member.position;
        var cell = new HologramCapture.Cell(
            member.element,
            Math.addExact(ax, p.x()),
            Math.addExact(ay, p.y()),
            Math.addExact(az, p.z()),
            member.definition.piece.trigger);
        cell.stableIndex = 0;
        World world = endpoint.world;
        if (!HologramWorldFlow.worldPosition(cell.x, cell.y, cell.z, world.getHeight())
            || !world.blockExists(cell.x, cell.y, cell.z)) throw refused("施工范围包含未加载或无效的坐标。");
        Hatch edit = edits.get(p);
        if (selection.operation() != 0) {
            prepareMutation(p, cell, edit);
            return null;
        }
        Block existing = world.getBlock(cell.x, cell.y, cell.z);
        var present = world.getTileEntity(cell.x, cell.y, cell.z);
        if (batch == null ? HologramElementCatalog.nativeAir(member.element) : batch.isNativeAir(member.element)) {
            if (edit != null || existing != Blocks.air || present != null) throw refused("原生声明空位已被占用或误选。");
            if (selection.scope() == 2) throw refused("所选位置是原生留空，不是可放置格。");
            return null;
        }
        boolean stateful = false;
        ItemStack item;
        int requestedFacing = -1;
        int[] legal = null;
        if (edit != null) {
            if (session.noHatches) throw refused("当前不放仓室的配置与手选仓室冲突。");
            item = hatch(edit.id());
            legal = requireHatchFacing(cell, edit, item);
            requestedFacing = edit.facing() >= 0 ? edit.facing() : legal[0];
            stateful = true;
        } else {
            if (compatibleHatch(cell, existing, present)) {
                targets++;
                return null; // Compatible installed device is not touched or charged.
            }
            item = defaultMaterial(member, cell, batch);
            // An unconfigured marker contributes no projected block.
            if (item == null && mandatoryHatch(member.element)) return null;
        }
        if (item == null || !(item.getItem() instanceof ItemBlock) || item.hasTagCompound()) {
            String kind = item == null ? "no-default" : item.hasTagCompound() ? "tagged" : "not-block-item";
            var factory = HologramDeclarationWrappers.factory(member.element.getClass());
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn(
                "GTMB native material refused controller={} piece={} index={} offset=({},{},{}) element={} factory={} kind={} item={} damage={} selectedHatch={}",
                selection.controller(),
                member.definition.piece.name,
                member.index,
                p.x(),
                p.y(),
                p.z(),
                member.element.getClass()
                    .getName(),
                factory == null ? "-" : factory.getName(),
                kind,
                item == null ? "-" : net.minecraft.item.Item.itemRegistry.getNameForObject(item.getItem()),
                item == null ? -1 : item.getItemDamage(),
                edit != null);
            throw refused("无法确定施工材料（" + p.x() + ", " + p.y() + ", " + p.z() + "），未放置。");
        }
        cell.block = Block.getBlockFromItem(item.getItem());
        cell.meta = stateful ? ItemMachines.getMetaTileEntity(item)
            .getTileEntityBaseType()
            : item.getItem()
                .getMetadata(item.getItemDamage());
        if (cell.block == Blocks.air || (stateful ? cell.block != GregTechAPI.sBlockMachines
            : cell.block.hasTileEntity(cell.meta) && !HologramFrameSupport.inertFrameBlock(cell.block, cell.meta)))
            throw refused("原生声明的设备需要明确支持的放置方式。");
        targets++;
        if (existing == cell.block
            && (stateful
                ? present instanceof BaseMetaTileEntity base && base.getMetaTileEntity() != null
                    && base.getMetaTileID() == item.getItemDamage()
                    && base.getFrontFacing() != null
                    && (edit.facing() < 0 ? Arrays.stream(legal)
                        .anyMatch(
                            d -> d == base.getFrontFacing()
                                .ordinal())
                        : base.getFrontFacing()
                            .ordinal() == requestedFacing)
                : world.getBlockMetadata(cell.x, cell.y, cell.z) == cell.meta))
            return null;
        HologramDeclaredNativeMutation.CasingProof casing = null;
        if (existing != Blocks.air || present != null) {
            if (stateful && present == null)
                casing = HologramDeclaredNativeMutation.CasingProof.read(session.player, world, cell);
            if (casing == null) throw refused("施工位已有不匹配的方块，未替换。");
            if (tile.isActive()) throw refused("机器正在运行，未替换机壳。");
        }
        return new Target(cell, item, requestedFacing, stateful, null, false, casing);
    }

    private static boolean mandatoryHatch(IStructureElement<?> element) {
        try {
            return HologramHatchPolicy.completeMarker(element);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private void prepareMutation(Offset p, HologramCapture.Cell cell, Hatch edit) throws IOException {
        var original = HologramDeclaredNativeMutation.Proof
            .read(session.player, endpoint.world, machine, cell.x, cell.y, cell.z);
        if (original == null || HologramElementCatalog.nativeAir(cell.element))
            throw refused("所选位置不是原机当前登记且归你所有的仓室；外壳或未证明的方块保留。");
        if (!endpoint.world.canMineBlock(session.player, cell.x, cell.y, cell.z)
            || !session.player.canPlayerEdit(cell.x, cell.y, cell.z, 1, session.player.getHeldItem())
            || GregTechAPI.sBlockMachines.getBlockHardness(endpoint.world, cell.x, cell.y, cell.z) < 0)
            throw refused("所选设备不可编辑，未修改任何格。");
        ItemStack before = hatch(original.id());
        if (before == null || Arrays.stream(HologramCellPolicy.previewHatchFacings(cell, before, detached, facing))
            .noneMatch(
                side -> side == original.front()
                    .ordinal()))
            throw refused("现场仓室已不符合所选级别/信道的可核验原生声明；原设备保留。");
        targets++;
        if (demolition != null) {
            demolition.requireDemolitionDestination(selection.destination());
            removalOrder.put(p, new Target(cell, null, -1, true, original, false));
            return;
        }
        ItemStack item = hatch(edit.id());
        int wanted = edit.facing() < 0 ? original.front()
            .ordinal() : edit.facing();
        if (item == null || item.hasTagCompound()
            || Arrays.stream(HologramCellPolicy.previewHatchFacings(cell, item, detached, facing))
                .noneMatch(side -> side == wanted))
            throw refused("目标仓室或朝向不符合此位置的原生声明。");
        if (edit.id() == original.id() && wanted == original.front()
            .ordinal()) return;

        // Swap only like-for-like GT hatch types. A different role can consume another quota, even at this site.
        if (ItemMachines.getMetaTileEntity(item)
            .getClass()
            != original.hatch()
                .getClass())
            throw refused("无法证明换装后仍占用同一原机仓室角色；原仓室保留。");
        cell.block = GregTechAPI.sBlockMachines;
        cell.meta = ItemMachines.getMetaTileEntity(item)
            .getTileEntityBaseType();
        boolean rotation = edit.id() == original.id();
        moves.add(new Target(cell, item, wanted, true, original, rotation));
        if (!rotation) funding.computeIfAbsent(HologramValues.stackKey(item), ignored -> new Need(item)).count++;
    }

    private boolean mutate(Target target, PermitAdmission admission) throws IOException {
        var original = target.original;
        if (!current(null) || !original.current()
            || demolition != null && HologramConstructionAuthority.hasKnownRecovery(session.player)) {
            stop("原机、仓室身份或回收状态已变化，未继续操作。");
            return false;
        }
        var permit = admission.acquire(gate, lease);
        if (permit == null) return false;
        boolean settled = false;
        BooleanSupplier authority = () -> gate.transactionCurrent(permit) && current(null);
        try {
            if (target.rotation) {
                boolean rotated;
                try {
                    rotated = HologramDeclaredNativeMutation.rotate(original, target.facing, authority);
                } catch (IOException | RuntimeException | LinkageError failure) {
                    // There is no stock or recovery output in this branch. Stop on an exception and report only
                    // a direction that can still be observed on the exact same tile; never replay the callback.
                    net.minecraftforge.common.util.ForgeDirection wanted = net.minecraftforge.common.util.ForgeDirection
                        .getOrientation(target.facing);
                    rotated = original.exact(wanted);
                    settled = gate.settle(
                        permit,
                        rotated ? HologramConstructionTaskGate.Settlement.COMMITTED
                            : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
                    if (settled && rotated) recordCommitted();
                    stop(
                        "原生朝向结束核对失败，未继续；没有扣料（" + failure.getClass()
                            .getSimpleName() + "：" + HologramAePolicy.fallback(failure.getMessage()) + "）。");
                    return false;
                }
                settled = gate.settle(
                    permit,
                    rotated ? HologramConstructionTaskGate.Settlement.COMMITTED
                        : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
                if (!settled) return false;
                if (rotated) {
                    recordCommitted();
                    return true;
                }
                stop("原仓室朝向未提交，背包未扣料。");
                return false;
            }
            try {
                demolition.requireDemolitionDestination(selection.destination());
            } catch (RuntimeException | LinkageError unavailable) {
                settled = gate.settle(permit, HologramConstructionTaskGate.Settlement.ROLLED_BACK);
                stop("所选回收目的地不可用，未拆除。");
                return false;
            }
            if (demolition.recoveryBacklogged() || !authority.getAsBoolean() || !original.current()) {
                settled = gate.settle(permit, HologramConstructionTaskGate.Settlement.ROLLED_BACK);
                stop("回收目的地或原仓室已变化，未拆除。");
                return false;
            }
            var result = HologramDemolition.removeBlock(
                session.player,
                endpoint.world,
                original.x(),
                original.y(),
                original.z(),
                () -> authority.getAsBoolean() && original.current());
            if (result.failure != null) {
                HologramPlacementDiagnostics
                    .failure(session, target.cell, 0, "declared-native-harvest", result.failure);
                if (Boolean.getBoolean("gtmb.v0111.nativeContractsOnly")) result.failure.printStackTrace(System.err);
            }
            demolition.recoverWorldAtPlayer(result.drops, selection.destination());
            settled = gate.settle(
                permit,
                result.removed || !result.drops.isEmpty() ? HologramConstructionTaskGate.Settlement.COMMITTED
                    : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
            if (!settled) return false;
            boolean removed = result.removed && endpoint.world.blockExists(original.x(), original.y(), original.z())
                && endpoint.world.getBlock(original.x(), original.y(), original.z()) == Blocks.air
                && endpoint.world.getTileEntity(original.x(), original.y(), original.z()) == null;
            if (removed) recordCommitted();
            if (!result.drops.isEmpty()) {
                try {
                    demolition.flushRecovery();
                } catch (RuntimeException | LinkageError failure) {
                    HologramDemolition.resumeRecovery(session.player);
                    stop("实际拆除已提交；回收交付失败，保留原目的地凭据并停止。");
                    return false;
                }
            }
            if (!removed || result.failure != null || demolition.pendingWorldRecovery()) {
                stop("原生拆除停止；已实际生成的掉落按所选去向保留，不恢复源格或重复投递。");
                if (demolition.pendingWorldRecovery()) HologramDemolition.resumeRecovery(session.player);
                return false;
            }
            return true;
        } finally {
            if (!settled) {
                gate.settle(permit, HologramConstructionTaskGate.Settlement.RECOVERY_PENDING);
                unsettled = permit;
                state = State.RECOVERY_PENDING;
                reason = "原生变更的结果需核查，未继续执行或重放。";
            }
        }
    }

    private static ItemStack hatch(int id) {
        if (id > 0 && id < GregTechAPI.METATILEENTITIES.length) {
            var prototype = GregTechAPI.METATILEENTITIES[id];
            if (prototype != null) {
                var item = prototype.getStackForm(1);
                if (item != null && item.getItem() instanceof ItemMachines
                    && item.getItemDamage() == id
                    && HologramCellPolicy.hatchTier(item) != Integer.MAX_VALUE) return item;
            }
        }
        return null;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private ItemStack defaultMaterial(Member member, HologramCapture.Cell cell, DeclarationBatch batch) {
        if (batch != null && batch.fixedMaterial != null) return batch.fixedMaterial.copy();
        ItemStack signal = member.definition.piece.trigger;
        var byElement = constantBlockDefaults.computeIfAbsent(member.definition, ignored -> new IdentityHashMap<>());
        var fixed = byElement.get(member.element);
        var casing = fixed != null ? fixed : HologramCasingFallback.resolve(member.element, signal);
        if (casing != null && fixed == null) {
            var factory = HologramDeclarationWrappers.factory(member.element.getClass());
            // Do not memoize mutable tier lists, dynamic wrappers or a result read through an adapter.
            if (factory != null
                && factory.getDeclaringClass() == com.gtnewhorizon.structurelib.structure.StructureUtility.class
                && "ofBlock".equals(factory.getName())
                && HologramDeclarationAdapters.declaration(member.element) == null) {
                fixed = casing;
                byElement.put(member.element, casing);
            }
        }
        if (casing != null) {
            ItemStack exact = HologramBlockIdentity.materialStack(casing.block, casing.meta);
            if (batch != null && fixed != null && exact != null) batch.fixedMaterial = exact.copy();
            return exact;
        }
        var frame = HologramFrameSupport.target(member.element);
        if (frame != null) return frame;
        return declaredDefault(member.element, cell, signal, new IdentityHashMap<>(), new int[1], 0);
    }

    /** Walk proofs, not the flattened browse list: every authorized wrapper transforms its own signal. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private ItemStack declaredDefault(IStructureElement<?> element, HologramCapture.Cell cell, ItemStack signal,
        IdentityHashMap<IStructureElement<?>, Boolean> path, int[] visits, int depth) {
        if (element == null || depth > 12 || visits[0]++ >= 128 || path.put(element, Boolean.TRUE) != null) return null;
        try {
            if (element instanceof Declarations.PureElement<?>pure) {
                for (ItemStack candidate : pure.candidates())
                    if (candidate != null && !(candidate.getItem() instanceof ItemMachines)
                        && pure.placementAllows(candidate)) return candidate.copy();
            }
            var adapter = HologramDeclarationAdapters.declaration(element);
            if (adapter == null || adapter.backingAuthority) {
                var backing = HologramDeclarationWrappers.backing(element);
                if (backing != null) {
                    ItemStack candidate = declaredDefault(
                        backing,
                        cell,
                        HologramDeclarationWrappers.trigger(element, signal),
                        path,
                        visits,
                        depth + 1);
                    if (candidate != null) return candidate;
                }
            }
            if (element instanceof IStructureElementChain<?>chain) {
                var children = HologramDeclarationWrappers.nativeChainChildren(chain);
                if (children != null && children.length <= 64) for (var child : children) {
                    ItemStack candidate = declaredDefault(child, cell, signal, path, visits, depth + 1);
                    if (candidate != null) return candidate;
                }
                return null;
            }
            var factory = HologramDeclarationWrappers.factory(element.getClass());
            var ordinary = nativeBlockDefault(element, factory);
            if (ordinary != null) return ordinary;
            if (factory == null || factory.getDeclaringClass() != GTStructureUtility.class
                || !"ofCoil".equals(factory.getName())) return null;
            // Read only the exact GT leaf on a detached machine, not a context-dependent or advisory wrapper.
            var blocks = ((IStructureElement) element).getBlocksToPlace(
                detached,
                detached.getBaseMetaTileEntity()
                    .getWorld(),
                cell.x,
                cell.y,
                cell.z,
                signal.copy(),
                AutoPlaceEnvironment
                    .fromLegacy((predicate, simulate, count) -> Collections.emptyMap(), session.player, ignored -> {}));
            if (blocks != null && blocks.getStacks() != null) {
                var output = blocks.getStacks()
                    .iterator();
                if (output.hasNext()) {
                    ItemStack candidate = output.next();
                    if (candidate != null && !output.hasNext()) return candidate.copy();
                }
            }
        } catch (ReflectiveOperationException unknownDeclaration) {
            return null;
        } finally {
            path.remove(element);
        }
        return null;
    }

    /**
     * Use the actual default selected by these StructureLib placement factories, not the first allowed block.
     * Hint-only declarations, custom setters, device blocks and item/world metadata mismatches stay out.
     */
    private static ItemStack nativeBlockDefault(IStructureElement<?> element, java.lang.reflect.Method factory)
        throws ReflectiveOperationException {
        if (factory == null
            || factory.getDeclaringClass() != com.gtnewhorizon.structurelib.structure.StructureUtility.class)
            return null;
        String name = factory.getName();
        if (!"ofBlockAnyMeta".equals(name) && !"ofBlocksFlat".equals(name) && !"ofBlocksMap".equals(name)) return null;
        Object declared = HologramDeclarationWrappers.captured(element, "val$defaultBlock");
        Object value = HologramDeclarationWrappers.captured(element, "val$defaultMeta");
        if (!(declared instanceof Block block) || !(value instanceof Integer meta)
            || meta < 0
            || block == Blocks.air
            || block.hasTileEntity(meta)
            || block instanceof com.gtnewhorizon.structurelib.structure.ICustomBlockSetting) return null;
        if ("ofBlockAnyMeta".equals(name)) {
            if (HologramDeclarationWrappers.captured(element, "val$block") != block) return null;
        } else {
            Object raw = HologramDeclarationWrappers.captured(element, "val$blocsMap");
            if (!(raw instanceof Map<?, ?>declaredBlocks)) return null;
            Object variants = declaredBlocks.get(block);
            if (!(meta.equals(variants) || variants instanceof java.util.Collection<?>values && values.contains(meta)))
                return null;
        }
        ItemStack stack = HologramBlockIdentity.materialStack(block, meta);
        return stack != null && stack.getItem() instanceof ItemBlock
            && Block.getBlockFromItem(stack.getItem()) == block
            && stack.getItem()
                .getMetadata(stack.getItemDamage()) == meta ? stack : null;
    }

    private boolean place(Target target, PermitAdmission admission) {
        if (!current(null)) {
            stop("原生结构或施工权限已变化。");
            return false;
        }
        var cell = target.cell;
        var permit = admission.acquire(gate, lease);
        if (permit == null) return false; // Existing scheduler keeps a queued STOP ahead of the next permit.
        boolean settled = false;
        try {
            cell.declaredPlacementTarget = target.item;
            BooleanSupplier owns = () -> bound(null) && (target.casing == null || !tile.isActive())
                && cell.declaredPlacementTarget == target.item
                && gate.transactionCurrent(permit);
            session.status = ""; // Only the transaction's current failure, not a prior cell's message.
            boolean wrote = target.original == null
                ? HologramWorldTransactions.operateDeclaredNative(
                    session,
                    cell,
                    0,
                    target.hatch,
                    target.facing,
                    target.casing,
                    owns,
                    () -> current(null))
                : HologramWorldTransactions
                    .replaceDeclaredNative(session, cell, 0, target.facing, target.original, owns, () -> current(null));
            if (session.job == 4 || HologramConstructionJournal.pending(session.player)) return false;
            boolean replaced = target.original != null || target.casing != null;
            boolean committed = wrote && (replaced ? "replaced" : "placed").equals(cell.status);
            settled = gate.settle(
                permit,
                committed ? HologramConstructionTaskGate.Settlement.COMMITTED
                    : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
            if (!settled) return false;
            if (committed) {
                recordCommitted();
                if (replaced) {
                    try {
                        stock.flushRecovery();
                    } catch (RuntimeException | LinkageError failure) {
                        HologramDemolition.resumeRecovery(session.player);
                        stop("换装已提交，旧仓室留在回收凭据中；未继续施工。");
                        return false;
                    }
                    if (stock.pendingWorldRecovery()) {
                        HologramDemolition.resumeRecovery(session.player);
                        stop("换装已提交，旧仓室仍待交付；未继续施工。");
                        return false;
                    }
                }
                return true;
            }
            String detail = session.status;
            if (placementBuild() && (detail == null || detail.isEmpty()) && "missing".equals(cell.status))
                detail = "所选材料来源不足，这格未放置";
            stop((detail == null || detail.isEmpty() ? "本格未提交（" + cell.status + "）。" : detail + "。") + "已确认的放置不重复。");
            return false;
        } finally {
            cell.declaredPlacementTarget = null;
            if (!settled) {
                gate.settle(permit, HologramConstructionTaskGate.Settlement.RECOVERY_PENDING);
                unsettled = permit;
                state = State.RECOVERY_PENDING;
                reason = "施工结果待核定；未继续放置。可请管理员使用 /gtmb recovery。";
            }
        }
    }

    private static IOException refused(String text) {
        return new IOException(text);
    }

    private State stop(String detail) {
        if (state == State.ACTIVE) {
            gate.finish(lease, HologramConstructionTaskGate.End.FAILED);
            state = State.STOPPED;
            reason = detail;
        }
        return state;
    }

    boolean cancel() {
        if (state != State.ACTIVE || !gate.cancel(lease.scope, lease.request)) return false;
        reason = "已停止原生操作，已确认的世界与材料变更保留。";
        state = State.STOPPED;
        if (timing != null) timing.finish(System.nanoTime());
        finishTrace();
        return true;
    }

    int placed() {
        return placed;
    }

    String reason() {
        return reason;
    }

    HologramConstructionTaskGate.Permit unsettled() {
        return unsettled;
    }
}
