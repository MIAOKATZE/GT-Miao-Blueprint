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

    boolean fillRequiredSpots() {
        boolean filled = false;
        for (var entry : new ArrayList<>(simulation.elements.entrySet())) {
            var p = entry.getKey();
            if (!simulation.world.isAirBlock(p.x(), p.y(), p.z()) || acceptsAir(entry.getValue(), p)) continue;
            List<ItemStack> candidates = offers(entry.getValue(), p, simulation.signalAt(p));
            if (!candidates.isEmpty() && place(candidates.get(0), p)) filled = true;
        }
        return filled;
    }

    void discover() throws IOException {
        List<HologramProjectorSimulation.Position> positions = new ArrayList<>(simulation.elements.keySet());
        for (var p : positions) {
            IStructureElement<?> element = simulation.elements.get(p);
            Block block = simulation.world.getBlock(p.x(), p.y(), p.z());
            if (block.getMaterial() == net.minecraft.block.material.Material.water
                && HologramNativeSourceWater.optionalWater(block)
                && acceptsAir(element, p)) {
                simulation.optionalWater.add(p);
                simulation.world.setBlockToAir(p.x(), p.y(), p.z());
                continue;
            }
            ItemStack actual = material(p);
            IStructureElement.BlocksToPlace offered = query(element, p, simulation.signalAt(p));
            ItemStack casing = null;
            if (offered != null && offered.getStacks() != null) for (ItemStack stack : offered.getStacks()) {
                if (stack != null && stack.getItem() != null && !hatch(stack) && stack.getItem() instanceof ItemBlock) {
                    casing = stack.copy();
                    break;
                }
            }
            List<ItemStack> candidates = offers(element, p, simulation.signalAt(p));
            if (!candidates.isEmpty()) {
                slots.put(p, candidates);
                if (actual != null && !hatch(actual)) casings.put(p, actual.copy());
                else if (casing != null) casings.put(p, casing);
            }
        }
        // Raw constructors expose no element. NEI hooks supply seeds only, never target authority.
        if (simulation.construction) discoverRawSeeds();
        discovered = true;
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
        if (known != null) return copies(known);
        if (discovered) return Collections.emptyList();
        if (element == null) {
            ItemStack seed = rawSeeds.get(p);
            return seed == null ? Collections.emptyList() : Collections.singletonList(seed.copy());
        }
        String signature = signal.stackSize + ":" + String.valueOf(signal.getTagCompound());
        Map<String, List<ItemStack>> bySignal = proposals.computeIfAbsent(element, key -> new LinkedHashMap<>());
        List<ItemStack> prior = bySignal.get(signature);
        if (prior != null) {
            List<ItemStack> result = new ArrayList<>();
            for (ItemStack candidate : prior) if (accepts(element, p, candidate)) add(result, candidate);
            return result;
        }
        var offered = query(element, p, signal);
        List<ItemStack> result = new ArrayList<>();
        if (offered == null) {
            ItemStack actual = material(p);
            if (hatch(actual) && accepts(element, p, actual)) add(result, actual);
            else if (simulation.world.isAirBlock(p.x(), p.y(), p.z()) && !acceptsAir(element, p)) {
                int examined = 0;
                for (ItemStack candidate : simulation.items.catalog()) {
                    if (++examined > 65536 || result.size() >= 128) break;
                    if (hatch(candidate) && accepts(element, p, candidate)) add(result, candidate);
                }
            }
            bySignal.put(signature, copies(result));
            return result;
        }
        if (offered.getStacks() != null) for (ItemStack stack : offered.getStacks())
            if (stack != null && hatch(stack) && accepts(element, p, stack)) add(result, stack);
        // A complete listed literal block has no unexplained inventory search. Native chains can carry partial lists.
        if (offered.getPredicate() != null
            && (offered.getStacks() == null || HologramNativeStructurePreview.isChain(element) || !result.isEmpty())) {
            int examined = 0;
            for (ItemStack candidate : simulation.items.catalog()) {
                if (++examined > 65536 || result.size() >= 128) break;
                if (!hatch(candidate)) continue;
                try {
                    ItemStack sample = HologramNativePreviewItems.match(offered.getPredicate(), candidate);
                    if (sample != null && accepts(element, p, sample)) add(result, sample);
                } catch (RuntimeException | LinkageError ignored) {}
            }
        }
        bySignal.put(signature, copies(result));
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
        Block original = simulation.world.getBlock(p.x(), p.y(), p.z());
        int meta = simulation.world.getBlockMetadata(p.x(), p.y(), p.z());
        NBTTagCompound tile = savedTile(p);
        ItemStack paid = material(p);
        try {
            if (simulation.context instanceof MTEMultiBlockBase machine) machine.clearHatches();
            return place(stack, p) && element.check(simulation.context, simulation.world, p.x(), p.y(), p.z());
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
                if (rawAccepts(p, seed)) {
                    casings.put(p, casing.copy());
                    rawSeeds.put(p, seed.copy());
                    slots.put(p, Collections.singletonList(seed.copy()));
                } else rawFacings.remove(p);
            }
        } finally {
            fresh.setWorldObj(old);
        }
    }

    private boolean rawAccepts(HologramProjectorSimulation.Position p, ItemStack stack) throws IOException {
        try (HologramProjectorSimulation isolated = simulation.fork()) {
            if (!isolated.probe.place(stack, p)) return false;
            ForgeDirection facing = rawFacings.get(p);
            if (facing != null && isolated.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)
                gt.setFrontFacing(facing);
            isolated.replayIsolated(new HologramNativePreviewItems());
            TileEntity result = isolated.world.getTileEntity(p.x(), p.y(), p.z());
            return result instanceof IGregTechTileEntity gt && gt.getMetaTileID() == stack.getItemDamage();
        }
    }

    private boolean placeRaw(ItemStack stack, HologramProjectorSimulation.Position p) {
        if (!place(stack, p)) return false;
        ForgeDirection facing = rawFacings.get(p);
        if (facing != null && simulation.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)
            gt.setFrontFacing(facing);
        return true;
    }

    void apply(NBTTagCompound config) throws IOException {
        // Restore actual per-position casing, never a controller-wide assumed material.
        if (simulation.construction)
            for (var e : casings.entrySet()) if (!place(e.getValue(), e.getKey())) throw new IOException("原生默认外壳无法放置。");
        if (simulation.construction) for (var e : slots.entrySet()) {
            var p = e.getKey();
            if (!casings.containsKey(p) && simulation.world.isAirBlock(p.x(), p.y(), p.z())
                && !e.getValue()
                    .isEmpty())
                if (!place(
                    e.getValue()
                        .get(0),
                    p)) throw new IOException("当前原生仓位没有接受所选等级的仓室。");
        }
        var pins = config.getTagList("nativeHatchChoices", 10);
        Set<String> mandatory = new LinkedHashSet<>();
        for (var it = simulation.world.currentPlacements(); it.hasNext();) {
            var cell = it.next();
            var p = new HologramProjectorSimulation.Position(cell.x(), cell.y(), cell.z());
            if (hatch(material(p)) && !casings.containsKey(p)) mandatory.add(
                HologramNativeTargetPlan.position(
                    p.x() - simulation.controller.xCoord,
                    p.y() - simulation.controller.yCoord,
                    p.z() - simulation.controller.zCoord));
        }
        if (config.getBoolean("presetActive")) {
            com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor
                .setChannelData(simulation.trigger, "gt_hatch", 1);
            for (var p : slots.keySet())
                if (!rawSeeds.containsKey(p)) simulation.world.setBlockToAir(p.x(), p.y(), p.z());

        }
        Set<HologramProjectorSimulation.Position> pinnedPositions = new LinkedHashSet<>();
        for (int n = 0; n < pins.tagCount(); n++) {
            var row = pins.getCompoundTagAt(n);
            var p = new HologramProjectorSimulation.Position(
                simulation.controller.xCoord + row.getInteger("x"),
                simulation.controller.yCoord + row.getInteger("y"),
                simulation.controller.zCoord + row.getInteger("z"));
            ItemStack wanted = new ItemStack(gregtech.api.GregTechAPI.sBlockMachines, 1, row.getInteger("id"));
            IStructureElement<?> element = simulation.elements.get(p);
            boolean raw = element == null && material(p) != null;
            if (raw && row.hasKey("facing", 3) && row.getInteger("facing") >= 0)
                rawFacings.put(p, ForgeDirection.getOrientation(row.getInteger("facing")));
            boolean valid = raw ? rawAccepts(p, wanted) : element != null && accepts(element, p, wanted);
            if (!valid) throw new IOException("所选仓室未被该位置的原生建造流程接受。");
            pinnedPositions.add(p);
            if (raw) {
                if (!placeRaw(wanted, p)) throw new IOException("所选仓室未被该位置的原生建造流程接受。");
            } else simulation.world.setBlockToAir(p.x(), p.y(), p.z());
            if (row.hasKey("facing", 3) && row.getInteger("facing") >= 0
                && simulation.world.getTileEntity(p.x(), p.y(), p.z()) instanceof IGregTechTileEntity gt)
                gt.setFrontFacing(ForgeDirection.getOrientation(row.getInteger("facing")));
        }
        if (config.getBoolean("presetActive") && !rawSeeds.isEmpty()) {
            HologramNativePreviewItems selectedSource = new HologramNativePreviewItems(
                config,
                mandatory,
                this::installed);
            int wanted = config.getInteger("hatchTier");
            for (var p : rawSeeds.keySet()) {
                if (pinnedPositions.contains(p)) continue;
                ItemStack selected = null;
                selectedSource.position(
                    HologramNativeTargetPlan.position(
                        p.x() - simulation.controller.xCoord,
                        p.y() - simulation.controller.yCoord,
                        p.z() - simulation.controller.zCoord));
                int examined = 0;
                for (ItemStack candidate : simulation.items.catalog()) {
                    if (++examined > 65536) break;
                    if (hatch(candidate) && selectedSource.takeOne(candidate, true)
                        && (HologramCellPolicy.hatchTier(candidate) == wanted
                            || config.getBoolean("hatchDowngrade") && HologramCellPolicy.hatchTier(candidate) <= wanted)
                        && rawAccepts(p, candidate)) {
                        selected = candidate;
                        break;
                    }
                }
                if (selected == null) throw new IOException("当前原生仓位没有接受所选等级的仓室。");
                placeRaw(selected, p);
            }
        }
        if (simulation.construction || config.getBoolean("presetActive") || pins.tagCount() > 0)
            simulation.replay(new HologramNativePreviewItems(config, mandatory, this::installed));
    }

    private Map<Integer, Integer> installed() {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (var it = simulation.world.currentPlacements(); it.hasNext();) {
            var cell = it.next();
            if (cell.tile() instanceof IGregTechTileEntity tile
                && tile.getMetaTileEntity() instanceof gregtech.api.metatileentity.implementations.MTEHatch)
                result.merge(tile.getMetaTileID(), 1, Integer::sum);
        }
        return result;
    }
}
