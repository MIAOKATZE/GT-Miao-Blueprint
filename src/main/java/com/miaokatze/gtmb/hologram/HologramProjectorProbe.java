// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
// Independent native API implementation; flow references GTNL ProjectorHatchProbe/ProjectorPlanApplier at
// ea917993f634158bc65945b7a58d69257936712c.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.alignment.constructable.IConstructable;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;

import gregtech.api.interfaces.INEIPreviewModifier;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;

/** Per-position native query, place/check, default casing restoration and requested-choice application. */
final class HologramProjectorProbe {

    private final HologramProjectorSimulation simulation;
    private boolean discovered;
    private java.util.Iterator<HologramProjectorSimulation.Position> discovery;
    private final Set<HologramProjectorSimulation.Position> validated = new LinkedHashSet<>();
    private final Map<IStructureElement<?>, Map<String, ItemStack>> casingProposals = new IdentityHashMap<>();
    private int applyStage, pinCursor;
    private java.util.Iterator<Map.Entry<HologramProjectorSimulation.Position, ItemStack>> casingCursor;
    private java.util.Iterator<HologramProjectorSimulation.Position> slotCursor, rawCursor;
    private java.util.Iterator<HologramNativePreviewWorld.Placement> mandatoryCursor;
    private final Set<String> mandatory = new LinkedHashSet<>();
    private final Set<HologramProjectorSimulation.Position> pinnedPositions = new LinkedHashSet<>();
    private final Set<HologramProjectorSimulation.Position> rawPinned = new LinkedHashSet<>();
    private boolean siteOnlyRawPins;
    private HologramNativePreviewItems selectedSource;
    private java.util.Iterator<HologramProjectorSimulation.Position> requiredCursor;
    private boolean requiredFilled;
    private long requiredBefore;
    private final Map<HologramProjectorSimulation.Position, ItemStack> casings = new LinkedHashMap<>();
    private final Map<HologramProjectorSimulation.Position, List<ItemStack>> slots = new LinkedHashMap<>();
    private final Map<HologramProjectorSimulation.Position, ItemStack> rawSeeds = new LinkedHashMap<>();
    private final Map<HologramProjectorSimulation.Position, ForgeDirection> rawFacings = new LinkedHashMap<>();
    // Per-run native declaration proposals only; every coordinate is still checked against its actual context.
    private final Map<IStructureElement<?>, Map<String, List<ItemStack>>> proposals = new IdentityHashMap<>();

    HologramProjectorProbe(HologramProjectorSimulation simulation) {
        this.simulation = simulation;
    }

    private static boolean hatch(ItemStack stack) {
        return HologramCellPolicy.hatchTier(stack) != Integer.MAX_VALUE;
    }

    private ItemStack material(HologramProjectorSimulation.Position p) {
        ItemStack paid = simulation.world.materialAt(p.x(), p.y(), p.z());
        if (paid != null) return paid;
        TileEntity tile = simulation.world.getTileEntity(p.x(), p.y(), p.z());
        if (tile instanceof IGregTechTileEntity gt)
            return new ItemStack(simulation.world.getBlock(p.x(), p.y(), p.z()), 1, gt.getMetaTileID());
        return HologramBlockIdentity.materialStack(
            simulation.world.getBlock(p.x(), p.y(), p.z()),
            simulation.world.getBlockMetadata(p.x(), p.y(), p.z()));
    }

    boolean fillRequiredSpots(long deadline) {
        if (requiredCursor == null) {
            requiredCursor = new ArrayList<>(simulation.elements.keySet()).iterator();
            requiredFilled = false;
            requiredBefore = simulation.world.contentSignature();
        }
        while (requiredCursor.hasNext() && System.nanoTime() < deadline) {
            var p = requiredCursor.next();
            var element = simulation.elements.get(p);
            if (!simulation.world.isAirBlock(p.x(), p.y(), p.z()) || acceptsAir(element, p)) continue;
            List<ItemStack> candidates = offers(element, p, simulation.signalAt(p));
            if (!candidates.isEmpty() && place(candidates.get(0), p)) requiredFilled = true;
        }
        if (requiredCursor.hasNext()) return false;
        requiredFilled = simulation.world.contentSignature() != requiredBefore;
        requiredCursor = null;
        return true;
    }

    boolean requiredFilled() {
        return requiredFilled;
    }

    ItemStack casingAt(HologramProjectorSimulation.Position p) {
        return casings.get(p);
    }

    boolean hatchSlot(HologramProjectorSimulation.Position p) {
        return slots.containsKey(p);
    }

    boolean discover(long deadline) throws IOException {
        if (discovery == null) discovery = new ArrayList<>(simulation.elements.keySet()).iterator();
        while (discovery.hasNext() && System.nanoTime() < deadline) {
            var p = discovery.next();
            IStructureElement<?> element = simulation.elements.get(p);
            Block block = simulation.world.getBlock(p.x(), p.y(), p.z());
            if (block.getMaterial() == net.minecraft.block.material.Material.water
                && HologramNativeSourceWater.optionalWater(block)
                && acceptsAir(element, p)) {
                simulation.optionalWater.add(p);
                simulation.world.setBlockToAir(p.x(), p.y(), p.z());
                continue;
            }
            if (simulation.option("footprintOnly")) continue;
            ItemStack actual = material(p);
            ItemStack signal = simulation.signalAt(p);
            String signature = signature(signal);
            Map<String, List<ItemStack>> bySignal = proposals.computeIfAbsent(element, key -> new LinkedHashMap<>());
            List<ItemStack> candidates = bySignal.get(signature);
            if (candidates == null) {
                var offered = query(element, p, signal);
                ItemStack casing = null;
                if (offered != null && offered.getStacks() != null) for (ItemStack stack : offered.getStacks()) {
                    if (stack != null && stack.getItem() != null
                        && !hatch(stack)
                        && stack.getItem() instanceof ItemBlock) {
                        casing = stack.copy();
                        break;
                    }
                }
                casingProposals.computeIfAbsent(element, key -> new LinkedHashMap<>())
                    .put(signature, casing);
                candidates = proposals(element, p, offered);
                bySignal.put(signature, candidates);
            }
            if (!candidates.isEmpty()) {
                slots.put(p, candidates);
                ItemStack casing = casingProposals.getOrDefault(element, Collections.emptyMap())
                    .get(signature);
                if (actual != null && !hatch(actual)) casings.put(p, actual);
                else if (hatch(actual)) {
                    // A shared declaration can choose its casing from this exact coordinate/context.
                    // Only positions that actually need restoration pay for a fresh native query.
                    var offered = query(element, p, signal);
                    if (offered != null && offered.getStacks() != null) for (ItemStack stack : offered.getStacks()) {
                        if (stack != null && !hatch(stack)
                            && stack.getItem() instanceof ItemBlock
                            && accepts(element, p, stack)) {
                            casings.put(p, stack.copy());
                            break;
                        }
                    }
                } else if (casing != null) casings.put(p, casing.copy());
            }
        }
        if (discovery.hasNext()) return false;
        if (simulation.construction && !simulation.option("footprintOnly")) discoverRawSeeds();
        discovery = null;
        discovered = true;
        return true;
    }

    private String signature(ItemStack signal) {
        return signal.stackSize + ":" + String.valueOf(signal.getTagCompound());
    }

    private List<ItemStack> proposals(IStructureElement<?> element, HologramProjectorSimulation.Position p,
        IStructureElement.BlocksToPlace offered) {
        List<ItemStack> result = new ArrayList<>();
        if (offered != null) {
            if (offered.getStacks() != null)
                for (ItemStack stack : offered.getStacks()) if (stack != null && hatch(stack)) add(result, stack);
            if (offered.getPredicate() != null) for (ItemStack candidate : simulation.items.hatches()) {
                if (result.size() >= 128) break;
                try {
                    ItemStack sample = HologramNativePreviewItems.match(offered.getPredicate(), candidate);
                    if (sample != null) add(result, sample);
                } catch (RuntimeException | LinkageError ignored) {}
            }
        }
        ItemStack actual = material(p);
        if (result.isEmpty() && hatch(actual)) add(result, actual);
        // Legacy no-placement hatch adders have no usable predicate. Only unresolved required
        // positions or an explicit preset need the expensive native place/check fallback here.
        if (result.isEmpty() && element != null
            && (simulation.option("presetActive")
                || simulation.world.isAirBlock(p.x(), p.y(), p.z()) && !acceptsAir(element, p))) {
            for (ItemStack candidate : simulation.items.hatches()) {
                if (result.size() >= 128) break;
                if (accepts(element, p, candidate)) add(result, candidate);
            }
        }
        return result;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    IStructureElement.BlocksToPlace query(IStructureElement<?> element, HologramProjectorSimulation.Position p,
        ItemStack signal) {
        if (element == null) return null;
        try {
            if (simulation.context instanceof MTEMultiBlockBase machine) machine.clearHatches();
            return ((IStructureElement) element).getBlocksToPlace(
                simulation.context,
                simulation.world,
                p.x(),
                p.y(),
                p.z(),
                simulation.probeSignal(signal),
                AutoPlaceEnvironment.fromLegacy(new HologramNativePreviewItems(), simulation.actor(), message -> {}));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    List<ItemStack> offers(IStructureElement<?> element, HologramProjectorSimulation.Position p, ItemStack signal) {
        List<ItemStack> known = slots.get(p);
        if (discovered && validated.contains(p)) return known == null ? Collections.emptyList() : copies(known);
        if (element == null) {
            ItemStack seed = rawSeeds.get(p);
            return seed == null ? Collections.emptyList() : Collections.singletonList(seed.copy());
        }
        if (known == null) known = proposals(element, p, query(element, p, signal));
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack candidate : known) if (accepts(element, p, candidate)) add(result, candidate);
        if (discovered) {
            slots.put(p, result);
            validated.add(p);
        }
        return result;
    }

    private static List<ItemStack> copies(List<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : stacks) out.add(stack.copy());
        return out;
    }

    private static void add(List<ItemStack> out, ItemStack stack) {
        for (ItemStack old : out) if (HologramValues.same(old, stack)) return;
        ItemStack copy = stack.copy();
        copy.stackSize = 1;
        out.add(copy);
    }

    private NBTTagCompound savedTile(HologramProjectorSimulation.Position p) {
        TileEntity tile = simulation.world.getTileEntity(p.x(), p.y(), p.z());
        if (tile == null) return null;
        NBTTagCompound nbt = new NBTTagCompound();
        tile.writeToNBT(nbt);
        return nbt;
    }

    private void restore(HologramProjectorSimulation.Position p, Block block, int meta, NBTTagCompound tile,
        ItemStack material) {
        simulation.world.setBlockToAir(p.x(), p.y(), p.z());
        if (material != null) simulation.world.materialSelected(material, true);
        simulation.world.setBlock(p.x(), p.y(), p.z(), block, meta, 2);
        if (tile != null) {
            TileEntity restored = simulation.world.getTileEntity(p.x(), p.y(), p.z());
            if (restored != null) restored.readFromNBT(tile);
        }
        if (material != null) simulation.world.materialSelected(material, false);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean accepts(IStructureElement element, HologramProjectorSimulation.Position p, ItemStack stack) {
        return accepts(element, p, stack, -1);
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean accepts(IStructureElement element, HologramProjectorSimulation.Position p, ItemStack stack,
        int direction) {
        Block original = simulation.world.getBlock(p.x(), p.y(), p.z());
        int meta = simulation.world.getBlockMetadata(p.x(), p.y(), p.z());
        NBTTagCompound tile = savedTile(p);
        ItemStack paid = material(p);
        try {
            if (simulation.context instanceof MTEMultiBlockBase machine) machine.clearHatches();
            if (!place(stack, p)) return false;
            if (direction >= 0) {
                if (!(simulation.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)
                    || direction > 5
                    || gt.getMetaTileEntity() == null
                    || !gt.getMetaTileEntity()
                        .isFacingValid(ForgeDirection.getOrientation(direction)))
                    return false;
                gt.setFrontFacing(ForgeDirection.getOrientation(direction));
            }
            return element.check(simulation.context, simulation.world, p.x(), p.y(), p.z());
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        } finally {
            restore(p, original, meta, tile, paid);
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean acceptsAir(IStructureElement element, HologramProjectorSimulation.Position p) {
        if (element == null) return false;
        Block block = simulation.world.getBlock(p.x(), p.y(), p.z());
        int meta = simulation.world.getBlockMetadata(p.x(), p.y(), p.z());
        NBTTagCompound tile = savedTile(p);
        ItemStack paid = material(p);
        try {
            simulation.world.setBlockToAir(p.x(), p.y(), p.z());
            return element.check(simulation.context, simulation.world, p.x(), p.y(), p.z());
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        } finally {
            restore(p, block, meta, tile, paid);
        }
    }

    boolean place(ItemStack stack, HologramProjectorSimulation.Position p) {
        if (stack == null || !(stack.getItem() instanceof ItemBlock block)) return false;
        simulation.world.setBlockToAir(p.x(), p.y(), p.z());
        simulation.world.materialSelected(stack, true);
        boolean placed = block.placeBlockAt(
            stack.copy(),
            simulation.actor(),
            simulation.world,
            p.x(),
            p.y(),
            p.z(),
            ForgeDirection.UP.ordinal(),
            .5f,
            .5f,
            .5f,
            block.getMetadata(stack.getItemDamage()));
        simulation.world.materialSelected(stack, false);
        return placed;
    }

    private void discoverRawSeeds() throws IOException {
        if (simulation.factory == null || simulation.facing == null) return;
        TileEntity fresh = simulation.factory.create();
        Object context = HologramStructureDiscovery.context(fresh);
        if (!(context instanceof INEIPreviewModifier hooks)) return;
        IConstructable constructable = HologramStructureDiscovery.localConstructable(fresh, context, simulation.facing);
        if (constructable == null) return;
        World old = fresh.getWorldObj();
        HologramNativePreviewWorld preview = HologramNativePreviewWorld.open(fresh);
        fresh.setWorldObj(preview);
        try {
            hooks.onPreviewConstruct(simulation.trigger.copy());
            hooks.onPreviewStructureComplete(simulation.trigger.copy());
            for (var it = preview.currentPlacements(); it.hasNext();) {
                var cell = it.next();
                if (!(cell.tile() instanceof IGregTechTileEntity gt)) continue;
                var p = new HologramProjectorSimulation.Position(cell.x(), cell.y(), cell.z());
                ItemStack casing = material(p);
                ItemStack seed = new ItemStack(cell.block(), 1, gt.getMetaTileID());
                if (casing == null || hatch(casing) || !hatch(seed) || slots.containsKey(p)) continue;
                rawFacings.put(p, gt.getFrontFacing());
                // A seed is only a proposal. The single configured native replay below, and its
                // exact result validation, decide whether a requested raw hatch survives.
                casings.put(p, casing.copy());
                rawSeeds.put(p, seed.copy());
                slots.put(p, Collections.singletonList(seed.copy()));
            }
        } finally {
            fresh.setWorldObj(old);
        }
    }

    private boolean rawModelMatches(HologramProjectorSimulation.Position p, ItemStack candidate) {
        ItemStack seed = rawSeeds.get(p);
        var original = seed == null ? null : gregtech.common.blocks.ItemMachines.getMetaTileEntity(seed);
        var selected = gregtech.common.blocks.ItemMachines.getMetaTileEntity(candidate);
        return original != null && selected != null
            && original.getClass()
                .isInstance(selected);
    }

    private boolean placeRaw(ItemStack stack, HologramProjectorSimulation.Position p) {
        if (!place(stack, p)) return false;
        ForgeDirection facing = rawFacings.get(p);
        if (facing != null && simulation.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)
            gt.setFrontFacing(facing);
        return true;
    }

    boolean apply(NBTTagCompound config, long deadline) throws IOException {
        if (config.getBoolean("footprintOnly")) return true;
        var pins = config.getTagList("nativeHatchChoices", 10);
        while (System.nanoTime() < deadline) {
            switch (applyStage) {
                case 0 -> {
                    if (casingCursor == null) casingCursor = casings.entrySet()
                        .iterator();
                    if (simulation.construction && casingCursor.hasNext()) {
                        var e = casingCursor.next();
                        if (!HologramValues.sameMaterial(material(e.getKey()), e.getValue())
                            && !place(e.getValue(), e.getKey())) throw new IOException("原生默认外壳无法放置。");
                    } else applyStage++;
                }
                case 1 -> {
                    if (slotCursor == null) slotCursor = slots.keySet()
                        .iterator();
                    if (simulation.construction && slotCursor.hasNext()) {
                        var p = slotCursor.next();
                        if (!casings.containsKey(p) && simulation.world.isAirBlock(p.x(), p.y(), p.z())) {
                            List<ItemStack> choices = offers(simulation.elements.get(p), p, simulation.signalAt(p));
                            if (!choices.isEmpty() && !place(choices.get(0), p))
                                throw new IOException("当前原生仓位没有接受所选等级的仓室。");
                        }
                    } else {
                        slotCursor = null;
                        applyStage++;
                    }
                }
                case 2 -> {
                    if (mandatoryCursor == null) mandatoryCursor = simulation.world.currentPlacements();
                    if (mandatoryCursor.hasNext()) {
                        var cell = mandatoryCursor.next();
                        var p = new HologramProjectorSimulation.Position(cell.x(), cell.y(), cell.z());
                        if (hatch(material(p)) && !casings.containsKey(p)) mandatory.add(relative(p));
                    } else applyStage++;
                }
                case 3 -> {
                    if (config.getBoolean("presetActive")) {
                        com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                            .setChannelData(simulation.trigger, "gt_hatch", 1);
                        if (slotCursor == null) slotCursor = slots.keySet()
                            .iterator();
                        if (slotCursor.hasNext()) {
                            var p = slotCursor.next();
                            if (!rawSeeds.containsKey(p)) simulation.world.setBlockToAir(p.x(), p.y(), p.z());
                            break;
                        }
                    }
                    applyStage++;
                }
                case 4 -> {
                    if (pinCursor < pins.tagCount()) {
                        var row = pins.getCompoundTagAt(pinCursor++);
                        var p = position(row);
                        ItemStack wanted = new ItemStack(
                            gregtech.api.GregTechAPI.sBlockMachines,
                            1,
                            row.getInteger("id"));
                        if (row.hasKey("item", 10)) {
                            ItemStack saved = ItemStack.loadItemStackFromNBT(row.getCompoundTag("item"));
                            if (saved == null || saved.getItem() != wanted.getItem()
                                || saved.getItemDamage() != wanted.getItemDamage())
                                throw new IOException("现场仓室物品身份无效。");
                            wanted = saved;
                            wanted.stackSize = 1;
                        }
                        IStructureElement<?> element = simulation.elements.get(p);
                        boolean raw = element == null && material(p) != null;
                        // A raw custom constructor has no StructureLib element. Placement is only a proposal;
                        // the native replay and exact-result validation below must retain it.
                        boolean valid = raw
                            ? hatch(wanted) && (rawSeeds.containsKey(p) || row.getBoolean("siteNativeAccepted"))
                            : element != null
                                && accepts(element, p, wanted, row.hasKey("facing", 3) ? row.getInteger("facing") : -1);
                        if (!valid) throw new IOException("所选仓室未被该位置的原生建造流程接受。");
                        pinnedPositions.add(p);
                        if (raw) {
                            if (!rawSeeds.containsKey(p)) siteOnlyRawPins = true;
                            rawPinned.add(p);
                            if (row.hasKey("facing", 3) && row.getInteger("facing") >= 0)
                                rawFacings.put(p, ForgeDirection.getOrientation(row.getInteger("facing")));
                            if (!placeRaw(wanted, p)) throw new IOException("所选仓室未被该位置的原生建造流程接受。");
                        } else {
                            if (!place(wanted, p)) throw new IOException("现场仓室物品不能原生放置。");
                            if (row.hasKey("facing", 3) && row.getInteger("facing") >= 0)
                                orient(p, row.getInteger("facing"));
                        }
                    } else applyStage++;
                }
                case 5 -> {
                    if (selectedSource == null)
                        selectedSource = new HologramNativePreviewItems(config, mandatory, this::installed);
                    if (rawCursor == null) rawCursor = rawSeeds.keySet()
                        .iterator();
                    if (config.getBoolean("presetActive") && rawCursor.hasNext()) {
                        var p = rawCursor.next();
                        if (pinnedPositions.contains(p)) break;
                        selectedSource.position(relative(p));
                        ItemStack selected = null;
                        for (ItemStack candidate : selectedSource.hatches())
                            if (selectedSource.takeOne(candidate, true) && rawModelMatches(p, candidate)) {
                                selected = candidate;
                                break;
                            }
                        if (selected == null || !placeRaw(selected, p)) throw new IOException("当前原生仓位没有接受所选等级的仓室。");
                    } else {
                        // A plain default target is already complete. Do not reconstruct it just to
                        // prove that the native constructor still accepts its own unchanged output.
                        if (config.getBoolean("presetActive") || pins.tagCount() > 0) {
                            simulation.beginReplay(selectedSource);
                            applyStage++;
                        } else applyStage = 7;
                    }
                }
                case 6 -> {
                    if (simulation.replayStep()) applyStage++;
                }
                case 7 -> {
                    if (siteOnlyRawPins && simulation.replayNativeResult != -1)
                        throw new IOException("现场仓室未被本次原生生存流程完整接受。");
                    if (pinCursor > 0) pinCursor = 0;
                    applyStage++;
                }
                case 8 -> {
                    if (pinCursor < pins.tagCount()) {
                        var row = pins.getCompoundTagAt(pinCursor++);
                        var p = position(row);
                        if (!(simulation.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)
                            || gt.getMetaTileID() != row.getInteger("id")) throw new IOException("所选仓室未被原生建造流程保留。");
                        if (row.hasKey("facing", 3) && row.getInteger("facing") >= 0)
                            orient(position(row), row.getInteger("facing"));
                    } else return true;
                }
                default -> throw new IllegalStateException("LOCAL_NATIVE_PREVIEW_APPLY_STAGE");
            }
        }
        return false;
    }

    private String relative(HologramProjectorSimulation.Position p) {
        return HologramNativeTargetPlan.position(
            p.x() - simulation.controller.xCoord,
            p.y() - simulation.controller.yCoord,
            p.z() - simulation.controller.zCoord);
    }

    private HologramProjectorSimulation.Position position(NBTTagCompound row) {
        return new HologramProjectorSimulation.Position(
            simulation.controller.xCoord + row.getInteger("x"),
            simulation.controller.yCoord + row.getInteger("y"),
            simulation.controller.zCoord + row.getInteger("z"));
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private boolean directionAccepted(HologramProjectorSimulation.Position p, int direction) {
        if (!(simulation.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)
            || gt.getMetaTileEntity() == null) return false;
        ForgeDirection facing = ForgeDirection.getOrientation(direction);
        if (!gt.getMetaTileEntity()
            .isFacingValid(facing)) return false;
        gt.setFrontFacing(facing);
        IStructureElement element = simulation.elements.get(p);
        if (element == null) return rawSeeds.containsKey(p) || rawPinned.contains(p);
        if (simulation.context instanceof MTEMultiBlockBase machine) machine.clearHatches();
        return element.check(simulation.context, simulation.world, p.x(), p.y(), p.z())
            && gt.getFrontFacing() == facing;
    }

    private void orient(HologramProjectorSimulation.Position p, int direction) throws IOException {
        if (rawSeeds.containsKey(p) || rawPinned.contains(p)) {
            if (!(simulation.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)
                || gt.getFrontFacing()
                    .ordinal() != direction
                || !gt.getMetaTileEntity()
                    .isFacingValid(ForgeDirection.getOrientation(direction)))
                throw new IOException("所选仓室方向未被原生建造流程保留。");
            return;
        }
        if (direction < 0 || direction > 5 || !directionAccepted(p, direction))
            throw new IOException("所选仓室方向不被原生位置接受。");
    }

    int facingMask(HologramProjectorSimulation.Position p, ItemStack sample) {
        if (!hatch(sample) || !HologramValues.sameMaterial(material(p), sample)
            || !(simulation.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)) return 0;
        ForgeDirection original = gt.getFrontFacing();
        int mask = 0;
        try {
            for (int direction = 0; direction < 6; direction++)
                if (directionAccepted(p, direction)) mask |= 1 << direction;
            return mask;
        } finally {
            gt.setFrontFacing(original);
        }
    }

    private Map<Integer, Integer> installed() {
        return simulation.world.installedHatches();
    }
}
