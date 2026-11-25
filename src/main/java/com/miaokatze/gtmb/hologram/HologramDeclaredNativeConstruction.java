// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.github.bsideup.jabel.Desugar;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
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
import gregtech.common.blocks.ItemMachines;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.util.GTStructureUtility;

/** The server resolves one named registered definition. Only this owner (not a client geometry row) makes permits. */
final class HologramDeclaredNativeConstruction {

    @Desugar
    private record DefinitionPiece(IStructureElement<?>[] elements, HologramCapture.Piece piece) {}

    @Desugar
    private record Member(DefinitionPiece definition, int index, IStructureElement<?> element, Offset position) {}

    @Desugar
    private record Target(HologramCapture.Cell cell, ItemStack item, int facing, boolean hatch,
        HologramDeclaredNativeMutation.Proof original, boolean rotation) {
        Target(HologramCapture.Cell cell, ItemStack item, int facing, boolean hatch) {
            this(cell, item, facing, hatch, null, false);
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
    private final BaseMetaTileEntity tile;
    private final MTEEnhancedMultiBlockBase<?> machine;
    private final IMetaTileEntity prototype;
    private final ExtendedFacing facing;
    private final int ax, ay, az;
    private final long epoch, revision, interpretations;
    private final ItemStack trigger;
    private final Map<Offset, Hatch> edits = new LinkedHashMap<>();
    private final Map<Offset, Member> members = new LinkedHashMap<>();
    private final List<Target> moves = new ArrayList<>();
    private final Map<HologramBlueprintResources.ExactKey, Need> funding = new LinkedHashMap<>();
    private final HologramDeclarationAdapters.Bindings declarations = new HologramDeclarationAdapters.Bindings();
    private final HologramMaterials.Context stock, demolition;
    private final java.util.NavigableMap<Offset, Target> removalOrder = new java.util.TreeMap<>(
        java.util.Comparator.comparingInt(Offset::y).reversed().thenComparingInt(Offset::z).thenComparingInt(Offset::x));
    private Iterator<Target> removals;
    private Target activeRemoval;
    private StructureSources.Resolution source;
    private MTEEnhancedMultiBlockBase<?> detached;
    private IStructureDefinition<?> definition;
    private List<DefinitionPiece> pieces;
    private HologramStructureCursor cursor;
    private Iterator<Member> memberIterator;
    private Iterator<Need> needs;
    private String family, reason = "";
    private int pieceIndex, targets, next, placed, examined;
    private State state = State.ACTIVE;
    private HologramConstructionTaskGate.Permit unsettled;

    HologramDeclaredNativeConstruction(HologramWorkspace.ActionEndpoint endpoint, HologramConstructionTaskGate gate,
        HologramConstructionTaskGate.Lease lease, HologramSession session, HologramDeclaredNativeSelection selection,
        BooleanSupplier stillConfirmed) {
        this.endpoint = endpoint;
        this.gate = gate;
        this.lease = lease;
        this.session = session;
        this.selection = selection;
        this.stillConfirmed = stillConfirmed;
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
        demolition = selection.operation() == 2
            ? HologramDemolition.recoveryContext(session.player, session.slot, selection.destination()) : null;
    }

    private boolean bound(HologramCapture scratch) {
        var player = session.player;
        return state == State.ACTIVE && stillConfirmed.getAsBoolean() && HologramWorkspace.find(endpoint.actor) == session
            && session.capture == scratch && session.editorWorkspaceEpoch == epoch && session.planRevision == revision
            && session.x == ax && session.y == ay && session.z == az && session.mode == selection.operation() && !session.virtual
            && session.facing == facing.ordinal() && !tile.isInvalid() && tile.getWorldObj() == endpoint.world
            && tile.getMetaTileID() == selection.controller() && tile.getMetaTileEntity() == machine
            && machine.getBaseMetaTileEntity() == tile && machine.getExtendedFacing() == facing
            && GregTechAPI.METATILEENTITIES[selection.controller()] == prototype
            && player.getDistanceSq(ax + .5, ay + .5, az + .5) <= 64
            && interpretations == HologramDeclarationAdapters.generation();
    }

    private boolean current(HologramCapture scratch) {
        return bound(scratch) && (source == null || source.validityFailure() == null)
            && declarations.validityFailure() == null
            && endpoint.world.blockExists(ax, ay, az)
            && endpoint.world.getBlock(ax, ay, az) == GregTechAPI.sBlockMachines
            && endpoint.world.getTileEntity(ax, ay, az) == tile && bound(scratch);
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
            || resolvedPieces.isEmpty() || resolvedPieces.size() > 4096)
            throw refused("未取得原生片段或声明使用自定义遍历；未施工。");
        var accepted = new ArrayList<DefinitionPiece>();
        for (var piece : resolvedPieces) {
            var elements = definition.getStructureFor(piece.name);
            if (elements == null || !relative(piece.a, piece.b, piece.c))
                throw refused("片段坐标不在服务器支持的范围。");
            accepted.add(new DefinitionPiece(elements, piece));
        }
        pieces = Collections.unmodifiableList(new ArrayList<>(accepted));
    }

    State drain(long deadline, PermitAdmission admission) {
        if (!gate.onOwnerThread()) throw new IllegalStateException("Native construction owner thread");
        if (state != State.ACTIVE || System.nanoTime() >= deadline) return state;
        try (var observed = HologramDeclarationAdapters.observe(declarations)) {
            if (!current(null) || HologramConstructionAuthority.hasKnownRecovery(session.player))
                return stop("控制器、材料来源或原工作台已变化。");
            if (pieces == null) resolve();
            while (System.nanoTime() < deadline) {
                if (memberIterator == null) {
                    if (pieceIndex < pieces.size()) {
                        recordMember();
                        continue;
                    }
                    bindSelection();
                    memberIterator = members.values().iterator();
                }
                if (memberIterator.hasNext()) {
                    prepare(memberIterator.next());
                    continue;
                }
                if (needs == null) {
                    if (targets == 0) return stop("所选范围没有可施工的目标。");
                    needs = funding.values().iterator();
                }
                if (needs.hasNext()) {
                    Need required = needs.next();
                    if (stock.count(required.specimen, required.count) != required.count)
                        return stop("所选材料来源不足，未开始放置。");
                    if (!current(null)) return stop("材料检查期间施工条件已变化。");
                    continue;
                }
                if (demolition != null && removals == null) removals = removalOrder.values().iterator();
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
            return stop("原生片段或其材料声明不可用，未继续施工（" + failure.getClass().getSimpleName() + "）。");
        }
        return state;
    }

    private void recordMember() throws IOException {
        DefinitionPiece piece = pieces.get(pieceIndex);
        if (cursor == null) cursor = new HologramStructureCursor(piece.elements, piece.piece.a, piece.piece.b, piece.piece.c);
        if (cursor.done() == cursor.total()) {
            cursor = null;
            pieceIndex++;
            return;
        }
        if (examined++ >= HologramBlueprintLimits.MAX_CELLS) throw refused("原生片段超过声明复杂度边界。");
        int index = cursor.done();
        var point = cursor.declarationOffset(facing);
        if (point == null) return; // StructureLib navigation.
        if (!point.valid()) throw refused("原生目标超出相对坐标范围。");
        var element = piece.elements[index];
        Member next = new Member(piece, index, element, point);
        Member previous = members.putIfAbsent(point, next);
        if (previous != null && (previous.element != element
            || !equalSignal(previous.definition.piece.trigger, piece.piece.trigger)))
            throw refused("原生片段在同一坐标上有冲突声明；未放置任何格。");
    }

    private static boolean equalSignal(ItemStack a, ItemStack b) {
        return a.stackSize == b.stackSize && HologramValues.sameMaterial(a, b);
    }

    private void bindSelection() throws IOException {
        for (Offset position : selection.selected())
            if (!members.containsKey(position) || anchor(position)) throw refused("选择不属于当前声明的可放置格。");
        if (selection.scope() == 3) {
            Member member = members.get(selection.selected().get(0));
            family = family(member);
            if (family.isEmpty()) throw refused("所选位置不属于可证明的声明材料族。");
        }
        if (selection.operation() == 1 && !edits.keySet().equals(new java.util.HashSet<>(selection.selected())))
            throw refused("换装只处理已逐格指定类型或方向的所选仓室。");
        for (Offset position : edits.keySet()) {
            Member member = members.get(position);
            if (member == null || anchor(position) || !includes(member))
                throw refused("手选仓室不在已确认的声明范围。");
        }
    }

    private boolean includes(Member member) {
        if (anchor(member.position)) return false;
        return switch (selection.scope()) {
            case 0 -> true;
            case 1 -> member.position.y() == selection.layer();
            case 2 -> selection.selected().contains(member.position);
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

    private String family(Member member) {
        var fallback = HologramCasingFallback.resolve(member.element, member.definition.piece.trigger);
        var group = HologramReplacementFamily.resolve(member.element,
            fallback == null ? null : fallback.block, fallback == null ? 0 : fallback.meta);
        return group == null ? "" : group.id;
    }

    private void prepare(Member member) throws IOException {
        if (!includes(member)) return;
        Offset p = member.position;
        var cell = new HologramCapture.Cell(member.element, Math.addExact(ax, p.x()), Math.addExact(ay, p.y()),
            Math.addExact(az, p.z()), member.definition.piece.trigger);
        cell.stableIndex = 0;
        World world = endpoint.world;
        if (!HologramWorldFlow.worldPosition(cell.x, cell.y, cell.z, world.getHeight())
            || !world.blockExists(cell.x, cell.y, cell.z)) throw refused("施工范围包含未加载或无效的坐标。");
        Block existing = world.getBlock(cell.x, cell.y, cell.z);
        int metadata = world.getBlockMetadata(cell.x, cell.y, cell.z);
        var present = world.getTileEntity(cell.x, cell.y, cell.z);
        Hatch edit = edits.get(p);
        if (selection.operation() != 0) {
            prepareMutation(p, cell, edit);
            return;
        }
        if (HologramElementCatalog.nativeAir(member.element)) {
            if (edit != null || existing != Blocks.air || present != null) throw refused("原生声明空位已被占用或误选。");
            if (selection.scope() == 2) throw refused("所选位置是原生留空，不是可放置格。");
            return;
        }
        boolean stateful = false;
        ItemStack item;
        int requestedFacing = -1;
        if (edit != null) {
            if (session.noHatches) throw refused("当前不放仓室的配置与手选仓室冲突。");
            item = hatch(edit.id());
            int[] legal = HologramCellPolicy.previewHatchFacings(cell, item, detached, facing);
            // The handshake may leave direction unspecified, not permit a different requested exact facing.
            if (legal.length == 0 || edit.facing() >= 0 && Arrays.stream(legal).noneMatch(d -> d == edit.facing()))
                throw refused("所选仓室的类型、等级或方向不符合该格的原生声明。");
            requestedFacing = edit.facing() >= 0 ? edit.facing() : legal[0];
            stateful = true;
        } else {
            if (existing == GregTechAPI.sBlockMachines && present instanceof BaseMetaTileEntity base
                && base.getMetaTileEntity() != null) {
                int installedFacing = base.getFrontFacing() == null ? -1 : base.getFrontFacing().ordinal();
                if (installedFacing >= 0 && Arrays.stream(HologramCellPolicy.previewHatchFacings(
                    cell, hatch(base.getMetaTileID()), detached, facing)).anyMatch(d -> d == installedFacing)) {
                    targets++;
                    return; // Compatible installed device is not touched or charged.
                }
            }
            item = defaultMaterial(member, cell);
        }
        if (item == null || !(item.getItem() instanceof ItemBlock) || item.hasTagCompound())
            throw refused("此格没有可执行的原生材料声明；未使用旧路径。");
        cell.block = Block.getBlockFromItem(item.getItem());
        cell.meta = stateful ? ItemMachines.getMetaTileEntity(item).getTileEntityBaseType()
            : item.getItem().getMetadata(item.getItemDamage());
        if (cell.block == Blocks.air || (stateful ? cell.block != GregTechAPI.sBlockMachines
            : cell.block.hasTileEntity(cell.meta) && !HologramFrameSupport.inertFrameBlock(cell.block, cell.meta)))
            throw refused("原生声明的设备需要明确支持的放置方式。");
        targets++;
        if (existing == cell.block && (stateful ? present instanceof BaseMetaTileEntity base
            && base.getMetaTileEntity() != null && base.getMetaTileID() == item.getItemDamage()
            && base.getFrontFacing() != null && (edit.facing() < 0
                ? Arrays.stream(HologramCellPolicy.previewHatchFacings(cell, item, detached, facing))
                    .anyMatch(d -> d == base.getFrontFacing().ordinal())
                : base.getFrontFacing().ordinal() == requestedFacing) : metadata == cell.meta)) return;
        if (existing != Blocks.air || present != null) throw refused("施工格已有不匹配的方块；补建不会替换。");
        moves.add(new Target(cell, item, requestedFacing, cell.block.hasTileEntity(cell.meta)));
        funding.computeIfAbsent(HologramValues.stackKey(item), ignored -> new Need(item)).count++;
    }

    private void prepareMutation(Offset p, HologramCapture.Cell cell, Hatch edit) throws IOException {
        var original = HologramDeclaredNativeMutation.Proof.read(session.player, endpoint.world, machine,
            cell.x, cell.y, cell.z);
        if (original == null || HologramElementCatalog.nativeAir(cell.element))
            throw refused("所选位置不是原机当前登记且归你所有的仓室；外壳或未证明的方块保留。");
        if (!endpoint.world.canMineBlock(session.player, cell.x, cell.y, cell.z)
            || !session.player.canPlayerEdit(cell.x, cell.y, cell.z, 1, session.player.getHeldItem())
            || GregTechAPI.sBlockMachines.getBlockHardness(endpoint.world, cell.x, cell.y, cell.z) < 0)
            throw refused("所选设备不可编辑，未修改任何格。");
        ItemStack before = hatch(original.id());
        if (before == null || Arrays.stream(HologramCellPolicy.previewHatchFacings(
            cell, before, detached, facing)).noneMatch(side -> side == original.front().ordinal()))
            throw refused("现场仓室已不符合所选级别/信道的可核验原生声明；原设备保留。");
        targets++;
        if (demolition != null) {
            demolition.requireDemolitionDestination(selection.destination());
            removalOrder.put(p, new Target(cell, null, -1, true, original, false));
            return;
        }
        ItemStack item = hatch(edit.id());
        int wanted = edit.facing() < 0 ? original.front().ordinal() : edit.facing();
        if (item == null || item.hasTagCompound()
            || Arrays.stream(HologramCellPolicy.previewHatchFacings(cell, item, detached, facing))
                .noneMatch(side -> side == wanted))
            throw refused("目标仓室或朝向不符合此位置的原生声明。");
        if (edit.id() == original.id() && wanted == original.front().ordinal()) return;

        // Swap only like-for-like GT hatch types. A different role can consume another quota, even at this site.
        if (ItemMachines.getMetaTileEntity(item).getClass() != original.hatch().getClass())
            throw refused("无法证明换装后仍占用同一原机仓室角色；原仓室保留。");
        cell.block = GregTechAPI.sBlockMachines;
        cell.meta = ItemMachines.getMetaTileEntity(item).getTileEntityBaseType();
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
                    net.minecraftforge.common.util.ForgeDirection wanted =
                        net.minecraftforge.common.util.ForgeDirection.getOrientation(target.facing);
                    rotated = original.exact(wanted);
                    settled = gate.settle(permit, rotated ? HologramConstructionTaskGate.Settlement.COMMITTED
                        : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
                    if (settled && rotated) placed++;
                    stop("原生朝向结束核对失败，未继续；没有扣料（"
                        + failure.getClass().getSimpleName() + "：" + HologramAePolicy.fallback(failure.getMessage()) + "）。");
                    return false;
                }
                settled = gate.settle(permit, rotated ? HologramConstructionTaskGate.Settlement.COMMITTED
                    : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
                if (!settled) return false;
                if (rotated) {
                    placed++;
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
            var result = HologramDemolition.removeBlock(session.player, endpoint.world, original.x(), original.y(),
                original.z(), () -> authority.getAsBoolean() && original.current());
            if (result.failure != null) {
                HologramPlacementDiagnostics.failure(session, target.cell, 0, "declared-native-harvest", result.failure);
                if (Boolean.getBoolean("gtmb.v0111.nativeContractsOnly")) result.failure.printStackTrace(System.err);
            }
            demolition.recoverWorldAtPlayer(result.drops, selection.destination());
            settled = gate.settle(permit, result.removed || !result.drops.isEmpty()
                ? HologramConstructionTaskGate.Settlement.COMMITTED : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
            if (!settled) return false;
            boolean removed = result.removed && endpoint.world.blockExists(original.x(), original.y(), original.z())
                && endpoint.world.getBlock(original.x(), original.y(), original.z()) == Blocks.air
                && endpoint.world.getTileEntity(original.x(), original.y(), original.z()) == null;
            if (removed) placed++;
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
                if (item != null && item.getItem() instanceof ItemMachines && item.getItemDamage() == id
                    && HologramCellPolicy.hatchTier(item) != Integer.MAX_VALUE) return item;
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ItemStack defaultMaterial(Member member, HologramCapture.Cell cell) {
        ItemStack signal = member.definition.piece.trigger;
        var casing = HologramCasingFallback.resolve(member.element, signal);
        if (casing != null) return HologramBlockIdentity.materialStack(casing.block, casing.meta);
        var frame = HologramFrameSupport.target(member.element);
        if (frame != null) return frame;
        return declaredDefault(member.element, cell, signal, new IdentityHashMap<>(), new int[1], 0);
    }

    /** Walk proofs, not the flattened browse list: every authorized wrapper transforms its own signal. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ItemStack declaredDefault(IStructureElement<?> element, HologramCapture.Cell cell, ItemStack signal,
        IdentityHashMap<IStructureElement<?>, Boolean> path, int[] visits, int depth) {
        if (element == null || depth > 12 || visits[0]++ >= 128 || path.put(element, Boolean.TRUE) != null)
            return null;
        try {
            if (element instanceof Declarations.PureElement<?> pure) {
                for (ItemStack candidate : pure.candidates())
                    if (candidate != null && !(candidate.getItem() instanceof ItemMachines)
                        && pure.placementAllows(candidate)) return candidate.copy();
            }
            var adapter = HologramDeclarationAdapters.declaration(element);
            if (adapter == null || adapter.backingAuthority) {
                var backing = HologramDeclarationWrappers.backing(element);
                if (backing != null) {
                    ItemStack candidate = declaredDefault(backing, cell,
                        HologramDeclarationWrappers.trigger(element, signal), path, visits, depth + 1);
                    if (candidate != null) return candidate;
                }
            }
            if (element instanceof IStructureElementChain<?> chain) {
                var children = HologramDeclarationWrappers.nativeChainChildren(chain);
                if (children != null && children.length <= 64)
                    for (var child : children) {
                        ItemStack candidate = declaredDefault(child, cell, signal, path, visits, depth + 1);
                        if (candidate != null) return candidate;
                    }
                return null;
            }
            var factory = HologramDeclarationWrappers.factory(element.getClass());
            if (factory == null || factory.getDeclaringClass() != GTStructureUtility.class
                || !"ofCoil".equals(factory.getName())) return null;
            // Read only the exact GT leaf on a detached machine, not a context-dependent or advisory wrapper.
            var blocks = ((IStructureElement) element).getBlocksToPlace(detached,
                detached.getBaseMetaTileEntity().getWorld(), cell.x, cell.y, cell.z, signal.copy(),
                AutoPlaceEnvironment.fromLegacy((predicate, simulate, count) -> Collections.emptyMap(), session.player,
                    ignored -> {}));
            if (blocks != null && blocks.getStacks() != null) {
                var output = blocks.getStacks().iterator();
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

    private boolean place(Target target, PermitAdmission admission) {
        if (!current(null)) {
            stop("原生结构或施工权限已变化。");
            return false;
        }
        var cell = target.cell;
        var permit = admission.acquire(gate, lease);
        if (permit == null) return false; // Existing scheduler keeps a queued STOP ahead of the next permit.
        var scratch = new HologramCapture(detached, trigger);
        scratch.cells.add(cell);
        boolean settled = false;
        try {
            session.capture = scratch;
            session.pins.put(0, target.item);
            session.planTargets.add(target.item);
            if (target.facing >= 0) session.planFacings.put(0, target.facing);
            BooleanSupplier owns = () -> bound(scratch) && session.pins.get(0) == target.item
                && session.planTargets.size() == 1 && session.planTargets.get(0) == target.item
                && gate.transactionCurrent(permit);
            boolean wrote = target.original == null
                ? HologramWorldTransactions.operateDeclaredNative(session, cell, 0, target.hatch,
                    target.facing, owns, () -> current(scratch))
                : HologramWorldTransactions.replaceDeclaredNative(session, cell, 0, target.facing,
                    target.original, owns, () -> current(scratch));
            if (session.job == 4 || HologramConstructionJournal.pending(session.player)) return false;
            boolean committed = wrote && (target.original == null ? "placed" : "replaced").equals(cell.status);
            settled = gate.settle(permit, committed ? HologramConstructionTaskGate.Settlement.COMMITTED
                : HologramConstructionTaskGate.Settlement.ROLLED_BACK);
            if (!settled) return false;
            if (committed) {
                placed++;
                if (target.original != null) {
                    try { stock.flushRecovery(); }
                    catch (RuntimeException | LinkageError failure) {
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
            stop("本格未提交（" + cell.status + "）；已确认放置和扣料不重复。");
            return false;
        } finally {
            if (session.capture == scratch) {
                session.capture = null;
                session.pins.remove(0, target.item);
                if (session.planTargets.size() == 1 && session.planTargets.get(0) == target.item) session.planTargets.clear();
                if (target.facing >= 0) session.planFacings.remove(0, target.facing);
            }
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
        return true;
    }

    int placed() { return placed; }
    String reason() { return reason; }
    HologramConstructionTaskGate.Permit unsettled() { return unsettled; }
}
