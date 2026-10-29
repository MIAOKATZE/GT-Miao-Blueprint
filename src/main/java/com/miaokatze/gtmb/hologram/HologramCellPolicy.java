package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.block.BlockBush;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.structurelib.StructureLibAPI;
import com.gtnewhorizon.structurelib.alignment.constructable.ChannelDataAccessor;
import com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing;
import com.gtnewhorizon.structurelib.structure.AutoPlaceEnvironment;
import com.gtnewhorizon.structurelib.structure.IStructureElement;
import com.gtnewhorizon.structurelib.structure.IStructureElementChain;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.IHeatingCoil;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEEnhancedMultiBlockBase;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.common.blocks.BlockCasings5;
import gregtech.common.blocks.BlockFrameBox;
import gregtech.common.blocks.ItemMachines;

/** Read-only cell declarations, native payload projections, candidate intersection and target eligibility. */
final class HologramCellPolicy {

    private HologramCellPolicy() {}

    static ExtendedFacing virtualControllerFacing(HologramSession s) {
        ExtendedFacing facing = HologramVirtualTransform.controller(s.virtualPreset);
        Integer front = s.facingPins.get(s.x + "," + s.y + "," + s.z);
        return front == null ? facing : facing.with(ForgeDirection.getOrientation(front));
    }

    static boolean virtualFrontAllowed(ItemStack target, int front) {
        if (front < 0) return true;
        if (target == null || !(target.getItem() instanceof ItemMachines)) return false;
        gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(target);
        return front <= 5 && prototype != null && prototype.isFacingValid(ForgeDirection.getOrientation(front));
    }

    static boolean trivial(World w, int x, int y, int z) {
        Block b = w.getBlock(x, y, z);
        return w.isAirBlock(x, y, z)
            || (b instanceof BlockBush && b.isReplaceable(w, x, y, z) && w.getTileEntity(x, y, z) == null);
    }

    static boolean knownSatisfied(HologramCapture.Cell c, Block old, int meta) {
        if (HologramBlockIdentity.sameForPlacement(old, meta, c.block, c.meta)) return true;
        if (old instanceof IHeatingCoil && HologramValues.coil(c.block, c.meta)
            && factory(c.element, "ofCoil", new IdentityHashMap<>(), 0)) {
            try {
                return ((IHeatingCoil) old).getCoilHeat(meta) == BlockCasings5.getCoilHeatFromDamage(c.meta);
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    /** A capture position plus a real valid coil family is the deliberately narrow destructive adapter. */
    static boolean destructive(HologramCapture.Cell c, Block old, int meta) {
        if (HologramFrameSupport.accepts(c.element, old, meta)) return true;
        if (old instanceof BlockFrameBox && !old.hasTileEntity(meta)
            && old == c.block
            && meta == c.meta
            && factory(c.element, "ofBlock", new IdentityHashMap<>(), 0)) return true;
        HologramReplacementFamily.Family family = HologramReplacementFamily.resolve(c.element, c.block, c.meta);
        if (family != null && family.contains(old, meta)) return true;
        if (HologramValues.coil(c.block, c.meta) && HologramValues.coil(old, meta))
            return factory(c.element, "ofCoil", new IdentityHashMap<>(), 0);
        return old == c.block && meta == c.meta
            && (!old.hasTileEntity(meta) || HologramFrameSupport.inertFrameBlock(old, meta))
            && factory(c.element, "ofBlock", new IdentityHashMap<>(), 0);
    }

    /**
     * Identify actual factory provenance through element delegates, rather than treating a visual hint as authority.
     */
    static boolean factory(Object value, String name, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || depth > 12 || seen.put(value, Boolean.TRUE) != null) return false;
        if (value instanceof IStructureElement) {
            try {
                IStructureElement<?> backing = HologramDeclarationWrappers.backing((IStructureElement<?>) value);
                if (backing != null && factory(backing, name, seen, depth + 1)) return true;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {}
        }
        Class<?> type = value.getClass();
        java.lang.reflect.Method enclosing = type.getEnclosingMethod();
        if (enclosing != null && enclosing.getName()
            .equals(name)
            && enclosing.getDeclaringClass()
                .getName()
                .equals(
                    (name.equals("ofCoil") || name.equals("ofFrame")) ? "gregtech.api.util.GTStructureUtility"
                        : "com.gtnewhorizon.structurelib.structure.StructureUtility"))
            return true;
        for (java.lang.reflect.Field field : type.getDeclaredFields())
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())
                && IStructureElement.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        if (factory(field.get(value), name, seen, depth + 1)) return true;
                    } catch (IllegalAccessException | RuntimeException ignored) {}
                }
        if (value instanceof IStructureElementChain)
            for (Object child : ((IStructureElementChain<?>) value).fallbacks())
                if (factory(child, name, seen, depth + 1)) return true;
        return false;
    }

    static ItemStack targetFor(HologramSession s, HologramCapture.Cell c, int index) {
        if (s.localPlanPending) return HologramEssentia.shellMaterial(draftTarget(s, c, index));
        if (index >= 0 && index < s.planTargets.size()) {
            ItemStack target = s.planTargets.get(index);
            if (!hintMaterial(target)) return target == null ? null : HologramEssentia.shellMaterial(target);
        }
        return HologramEssentia.shellMaterial(draftTarget(s, c, index));
    }

    static ItemStack draftTarget(HologramSession s, HologramCapture.Cell c, int index) {
        ItemStack pin = s.pins.get(index);
        if (pin == null && s.scope == 3
            && s.selected >= 0
            && s.selected < s.capture.cells.size()
            && !c.family.isEmpty()
            && HologramSessionPolicy.sameSelectedFamily(s, c)) pin = s.pins.get(s.selected);
        if (pin != null) return pin.copy();
        ItemStack originalController = implicitBlueprintController(s, c);
        if (originalController != null) return originalController;
        if (c.userPosition) return null;
        ItemStack automatic = s.autoTargets.get(index);
        if (!s.noHatches && automatic != null) return automatic.copy();
        TileEntity actual = HologramSessionPolicy.authoring(s) ? null : s.player.worldObj.getTileEntity(c.x, c.y, c.z);
        // Capture replaces hint blocks with any recognized casing fallback. A remaining
        // hatch hint therefore needs its declared hatch even when automatic filling is off.
        // Preserve only an installed hatch accepted by that declaration; never synthesize one.
        if ((!s.noHatches || (c.block == StructureLibAPI.getBlockHint() && "hatch".equals(c.role)))
            && actual instanceof IGregTechTileEntity
            && !c.anchor) {
            ItemStack existing = new ItemStack(
                GregTechAPI.sBlockMachines,
                1,
                ((IGregTechTileEntity) actual).getMetaTileID());
            if (hatchElement(s, c, existing, true) != null) return existing;
        }
        if (c.block != null && c.block != Blocks.air && c.block != StructureLibAPI.getBlockHint()) {
            ItemStack block = HologramBlockIdentity.materialStack(c.block, c.meta);
            if (block != null) return block;
        }
        List<ItemStack> options = candidates(s, c);
        for (ItemStack option : options)
            if (!hintMaterial(option) && placeableSample(s, c, option)) return option.copy();
        return null;
    }

    /** StructureLib's visual marker is never a payable construction material. */
    static boolean hintMaterial(ItemStack sample) {
        Block hint = StructureLibAPI.getBlockHint();
        return hint != null && sample != null
            && sample.getItem() != null
            && Block.getBlockFromItem(sample.getItem()) == hint;
    }

    static boolean explicitTarget(HologramSession s, HologramCapture.Cell c, int index) {
        return s.pins.containsKey(index) || implicitBlueprintController(s, c) != null
            || (s.scope == 3 && s.pins.containsKey(s.selected)
                && s.selected >= 0
                && s.selected < s.capture.cells.size()
                && !c.family.isEmpty()
                && HologramSessionPolicy.sameSelectedFamily(s, c));
    }

    /** Removing an override does not remove a saved controller's registered material identity. */
    static ItemStack implicitBlueprintController(HologramSession s, HologramCapture.Cell cell) {
        if (!s.virtual || s.virtualPreset == null
            || !"templateController".equals(cell.role)
            || !cell.sourceWarning.isEmpty()
            || cell.blueprintItem.isEmpty()) return null;
        HologramPresets.Cell original = new HologramPresets.Cell();
        original.item = cell.blueprintItem;
        original.damage = cell.blueprintDamage;
        original.controller = true;
        original.structureId = cell.structureId;
        ItemStack target = original.stack();
        if (target == null || !(target.getItem() instanceof ItemMachines)
            || original.damage < 0
            || original.damage >= GregTechAPI.METATILEENTITIES.length
            || !(GregTechAPI.METATILEENTITIES[original.damage] instanceof MTEEnhancedMultiBlockBase)
            || !HologramBlueprintData.blueprintControllerMatches(
                s.virtualPreset,
                original,
                HologramBlueprintData.blueprintSourceWarning(s.virtualPreset, cell.structureId)))
            return null;
        try {
            NBTTagCompound payload = HologramBlueprintNbt.decode(cell.itemNbt);
            if (payload != null && payload.hasKey(HologramRecovery.TAG))
                target.setTagCompound(HologramBlueprintNbt.nativePayload(payload));
        } catch (java.io.IOException invalid) {
            return null;
        }
        return target;
    }

    static boolean explicitGrade(HologramSession s, HologramCapture.Cell c) {
        if (s.mainExplicit) return true;
        for (String channel : HologramDeclarationWrappers.channels(c.element))
            if (s.channels.hasKey(channel, 3)) return true;
        if (c.family.equals("coil")) return s.channels.hasKey("coil", 3);
        if (c.family.startsWith("glass:")) return s.channels.hasKey("glass", 3);
        return c.family.startsWith("tiered:")
            && (s.channels.hasKey("item_pipe", 3) || s.channels.hasKey("coke_oven_casing", 3));
    }

    static boolean targetSatisfied(HologramSession s, HologramCapture.Cell c, int index, Block old, int meta) {
        ItemStack target = targetFor(s, c, index);
        if (s.facingPins.containsKey(HologramValues.position(c))) {
            TileEntity actual = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            if (!(actual instanceof IGregTechTileEntity) || ((IGregTechTileEntity) actual).getFrontFacing()
                .ordinal() != s.facingPins.get(HologramValues.position(c))) return false;
        }
        if (explicitTarget(s, c, index) && HologramRecovery.hasSeal(target))
            return sealedMatchesPlaced(s, c, index, s.facingPins.getOrDefault(HologramValues.position(c), -1));
        if (s.facingPins.containsKey(HologramValues.position(c)) || s.autoTargets.containsKey(index)
            || explicitGrade(s, c)
            || explicitTarget(s, c, index)) return targetMatches(s, c, index, old, meta);
        HologramReplacementFamily.Family family = HologramReplacementFamily.resolve(c.element, c.block, c.meta);
        if (family != null && family.contains(old, meta)) return true;
        return knownSatisfied(c, old, meta);
    }

    static boolean targetMatches(HologramSession s, HologramCapture.Cell c, int index, Block old, int meta) {
        return targetMatches(s, c, index, old, meta, true);
    }

    static boolean targetMatches(HologramSession s, HologramCapture.Cell c, int index, Block old, int meta,
        boolean restoredConfiguration) {
        ItemStack wanted = targetFor(s, c, index);
        if (wanted == null) return false;
        // These paths can never accept an absent primary block. Reject the mismatch before decoding
        // full payloads or invoking native variant validators; matching blocks still use every check below.
        if (s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && !HologramRecovery.hasSeal(wanted)
            && (HologramNativePlacement.supported(c.block) && old != c.block
                || wanted.getItem() instanceof ItemMachines && old != Block.getBlockFromItem(wanted.getItem())
                || old == Blocks.air && wanted.getItem() instanceof ItemBlock
                    && Block.getBlockFromItem(wanted.getItem()) != Blocks.air))
            return false;
        if (s.virtual && s.virtualPreset.schema >= 2 && HologramNetworkBindings.node(c.block)) {
            try {
                if (HologramBlueprintProcessing.quantumCleared(s.virtualPreset)
                    && !HologramNativePlacement.hasUnboundNativeNode(s.player.worldObj.getTileEntity(c.x, c.y, c.z)))
                    return false;
            } catch (java.io.IOException invalidPolicy) {
                return false;
            }
        }
        if (HologramRecovery.hasSeal(wanted))
            return sealedMatchesPlaced(s, c, index, s.facingPins.getOrDefault(HologramValues.position(c), -1));
        if (s.virtual && s.virtualPreset.schema >= 2) {
            try {
                if (!HologramNetworkBindings.matchesBinding(
                    s.player.worldObj.getTileEntity(c.x, c.y, c.z),
                    HologramBlueprintNbt.decode(c.tileNbt),
                    s.player,
                    s.networkBindings)) return false;
            } catch (java.io.IOException invalidBinding) {
                return false;
            }
        }
        if (s.virtual && s.virtualPreset.schema >= 2 && HologramNativePlacement.supported(c.block)) {
            // A multipart/conduit material item often has no ItemBlock and maps to air.
            // Only the captured native block can satisfy this target, never that item's block mapping.
            if (old != c.block || meta != c.meta && !HologramEnergyPayload.volatileEnergyMetadata(c.block))
                return false;
            try {
                return HologramNativePlacement.matchesPlaced(
                    c.block,
                    c.meta,
                    s.player.worldObj.getTileEntity(c.x, c.y, c.z),
                    HologramCoordinatePolicies.placementProjection(
                        HologramBlueprintNbt.forPlacement(
                            c.block,
                            c.meta,
                            c.tileNbt,
                            s.player.getUniqueID(),
                            s.player.getCommandSenderName(),
                            c.x,
                            c.y,
                            c.z,
                            s.virtualPreset.retainNbt,
                            HologramSessionPolicy.placementRetainCovers(s),
                            HologramSessionPolicy.placementKeys(s)),
                        s.dimension,
                        c.x,
                        c.y,
                        c.z),
                    s.player);
            } catch (java.io.IOException invalid) {
                return false;
            }
        }
        if (wanted != null && wanted.getItem() instanceof ItemMachines) {
            TileEntity tile = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            return old == Block.getBlockFromItem(wanted.getItem()) && tile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) tile).getMetaTileID() == wanted.getItemDamage();
        }
        if (s.virtual && s.virtualPreset.schema >= 2 && wanted.getItem() instanceof ItemBlock) {
            try {
                validateOrdinaryPlacedVariant(s.player.worldObj, c, wanted);
                if (meta != targetPreviewMeta(s, c, wanted)) return false;
                Boolean panelMatches = nativePanelMatches(s, c, restoredConfiguration);
                return panelMatches == null || panelMatches;
            } catch (java.io.IOException invalid) {
                return false;
            }
        }
        if (s.virtual && s.virtualPreset.schema >= 2
            && (c.block == Blocks.lit_redstone_lamp || c.block == Blocks.redstone_lamp))
            return HologramBlockIdentity.sameForPlacement(old, meta, c.block, c.meta);
        if (old != Block.getBlockFromItem(wanted.getItem())) return false;
        if (s.virtual && s.virtualPreset.schema >= 2 && c.block == old) return meta == c.meta;
        if (explicitTarget(s, c, index)) {
            // Direction and half-block placement metadata need not equal the item's damage value.
            try {
                return old.getDamageValue(s.player.worldObj, c.x, c.y, c.z) == wanted.getItemDamage();
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return meta == wanted.getItem()
            .getMetadata(wanted.getItemDamage());
    }

    static ItemStack elementTrigger(HologramSession s, HologramCapture.Cell c) {
        ItemStack result = HologramSessionPolicy.trigger(s);
        if (c.elementTrigger != null) {
            // Preserve the current inventory tool's identity/unrelated NBT while retaining the real piece signal.
            result.stackSize = c.elementTrigger.stackSize;
            List<String> existing = new ArrayList<>();
            ChannelDataAccessor.iterateChannelData(result)
                .forEach(entry -> existing.add(entry.getKey()));
            for (String name : existing) ChannelDataAccessor.unsetChannelData(result, name);
            ChannelDataAccessor.iterateChannelData(c.elementTrigger)
                .forEach(entry -> ChannelDataAccessor.setChannelData(result, entry.getKey(), entry.getValue()));
        }
        // A concrete new hatch pin authorizes that item even when the automatic hatch toggle is off.
        if (!s.noHatches && "hatch".equals(c.role)) {
            int index = HologramSessionPolicy.cellIndex(s, c);
            if (index >= 0 && (explicitTarget(s, c, index) || s.autoTargets.containsKey(index))) {
                ItemStack target = targetFor(s, c, index);
                if (target != null && target.getItem() instanceof ItemMachines)
                    ChannelDataAccessor.setChannelData(result, "gt_hatch", 1);
            }
        }
        return result;
    }

    /** The existing per-cell resource cache's complete detached input, without concatenating encoded payloads. */
    static final class ResourcePlanKey {

        final String tile, item, sourceItem, actorName;
        final int sourceDamage, metadata, dimension, x, y, z;
        final Block block;
        final HologramNativePlacement.Adapter adapter;
        final long generation;
        final boolean retainNbt, retainCovers;
        final List<String> keys;
        final java.util.UUID actor;
        final HologramBlueprintResources.ExactKey target;

        ResourcePlanKey(HologramSession s, HologramCapture.Cell cell, ItemStack target,
            HologramNativePlacement.Adapter adapter) {
            tile = cell.tileNbt;
            item = cell.itemNbt;
            sourceItem = cell.blueprintItem;
            sourceDamage = cell.blueprintDamage;
            block = cell.block;
            metadata = cell.meta;
            dimension = s.dimension;
            x = cell.x;
            y = cell.y;
            z = cell.z;
            this.adapter = adapter;
            generation = com.miaokatze.gtmb.api.ResourceContainers.generation();
            retainNbt = s.virtualPreset.retainNbt;
            retainCovers = HologramSessionPolicy.placementRetainCovers(s);
            List<String> selected = HologramSessionPolicy.placementKeys(s);
            keys = selected == null ? null : Collections.unmodifiableList(new ArrayList<>(selected));
            actor = s.player.getUniqueID();
            actorName = s.player.getCommandSenderName();
            this.target = new HologramBlueprintResources.ExactKey(target, null);
        }

        boolean matches(HologramSession s, HologramCapture.Cell cell, ItemStack target,
            HologramNativePlacement.Adapter adapter) {
            return block == cell.block && metadata == cell.meta
                && this.adapter == adapter
                && generation == com.miaokatze.gtmb.api.ResourceContainers.generation()
                && dimension == s.dimension
                && x == cell.x
                && y == cell.y
                && z == cell.z
                && sourceDamage == cell.blueprintDamage
                && java.util.Objects.equals(sourceItem, cell.blueprintItem)
                && java.util.Objects.equals(tile, cell.tileNbt)
                && java.util.Objects.equals(item, cell.itemNbt)
                && retainNbt == s.virtualPreset.retainNbt
                && retainCovers == HologramSessionPolicy.placementRetainCovers(s)
                && java.util.Objects.equals(keys, HologramSessionPolicy.placementKeys(s))
                && java.util.Objects.equals(actor, s.player.getUniqueID())
                && java.util.Objects.equals(actorName, s.player.getCommandSenderName())
                && this.target.matches(target);
        }
    }

    static HologramBlueprintResources.Plan resourcePlan(HologramSession s, HologramCapture.Cell cell)
        throws java.io.IOException {
        return resourcePlan(s, cell, null);
    }

    /**
     * Return miss-only preparation in the caller's fresh slot. The caller still merges resources before preparing a
     * hit,
     * preserving its partial report if that later preparation fails. Expanded trees never live on the cell.
     */
    static HologramBlueprintResources.Plan resourcePlan(HologramSession s, HologramCapture.Cell cell,
        HologramEnergyPayload.Prepared[] prepared) throws java.io.IOException {
        if (!s.virtual || s.virtualPreset == null || s.virtualPreset.schema < 2)
            return new HologramBlueprintResources.Plan();
        int index = HologramSessionPolicy.cellIndex(s, cell);
        ItemStack finalTarget = targetFor(s, cell, index);
        if (HologramRecovery.hasSeal(finalTarget)) {
            sealedPlacementTarget(s, cell, index);
            cell.resourceError = "";
            return new HologramBlueprintResources.Plan().addNativeItems(
                HologramGtswnPlacementMaterials.extraMaterials(cell.block, HologramBlueprintNbt.decode(cell.tileNbt)));
        }
        HologramNativePlacement.Adapter adapter = HologramNativePlacement.adapter(cell.block);
        if (cell.resourcePlanKey == null || !cell.resourcePlanKey.matches(s, cell, finalTarget, adapter)) {
            cell.resourcePlanKey = new ResourcePlanKey(s, cell, finalTarget, adapter);
            cell.resourceFingerprint = "prepared"; // Short diagnostics only; never participates in cache identity.
            cell.resourceError = "";
            cell.resourcePlan = null;
            try {
                // cachePlan performs the same placement selection and validation before preparing buffers.
                // The former two projections were immediately overwritten, including two AE analyses per cell.
                HologramEnergyPayload.Prepared cache = cachePlan(s, cell, index);
                if (prepared != null) prepared[0] = cache;
                NBTTagCompound tile = cache.tileEmpty;
                NBTTagCompound item = cache.itemEmpty;
                Block finalBlock = targetPreviewBlock(s, cell, finalTarget);
                int finalMeta = targetPreviewMeta(s, cell, finalTarget);
                HologramEssentia.validatePlacement(finalBlock, finalMeta, tile);
                if (HologramAeBlueprint.supports(cell.block, tile)) {
                    cell.resourcePlan = HologramAeBlueprint.resourcePlan(cell.block, cell.meta, tile, finalTarget);
                } else {
                    List<ItemStack> nativeComponents = adapter != null
                        ? HologramNativePlacement.materials(cell.block, cell.meta, tile)
                        : Collections.emptyList();
                    cell.resourcePlan = HologramBlueprintResources.plan(tile, nativeComponents)
                        .merge(HologramBlueprintResources.plan(item));
                    if (adapter != null && decoration(s, cell) == null) {
                        if (!s.virtualPreset.retainNbt)
                            throw new java.io.IOException("Native composite tile requires retained NBT");
                        if (adapter.includesInventoryDrops(cell.block))
                            cell.resourcePlan.removeItemsIncludedByNativeDrops(nativeComponents);
                        cell.resourcePlan.addNativeItems(
                            HologramNativePlacement.additionalMaterials(cell.block, nativeComponents, finalTarget));
                    }
                }
                cell.resourcePlan.addNativeItems(HologramGtswnPlacementMaterials.extraMaterials(finalBlock, tile));
            } catch (java.io.IOException invalid) {
                cell.resourceError = invalid.getMessage();
            }
        }
        if (!cell.resourceError.isEmpty()) throw new java.io.IOException(cell.resourceError);
        return cell.resourcePlan == null ? new HologramBlueprintResources.Plan() : cell.resourcePlan;
    }

    static HologramEnergyPayload.Prepared cachePlan(HologramSession s, HologramCapture.Cell cell, int index)
        throws java.io.IOException {
        if (HologramRecovery.hasSeal(targetFor(s, cell, index))) {
            sealedPlacementTarget(s, cell, index);
            return HologramEnergyPayload.prepare(null, null);
        }
        NBTTagCompound tile = HologramBlueprintNbt.forPlacement(
            cell.block,
            cell.meta,
            cell.tileNbt,
            s.player.getUniqueID(),
            s.player.getCommandSenderName(),
            cell.x,
            cell.y,
            cell.z,
            s.virtualPreset.retainNbt,
            HologramSessionPolicy.placementRetainCovers(s),
            HologramSessionPolicy.placementKeys(s));
        NBTTagCompound item = HologramBlueprintNbt.itemForPlacement(
            cell.itemNbt,
            s.player.getUniqueID(),
            s.player.getCommandSenderName(),
            s.virtualPreset.retainNbt,
            HologramSessionPolicy.placementRetainCovers(s),
            HologramSessionPolicy.placementKeys(s));
        ItemStack target = targetFor(s, cell, index);
        int targetMeta = targetPreviewMeta(s, cell, target);
        Block targetBlock = targetPreviewBlock(s, cell, target);
        if (target != null) HologramPresets.validateMaterialVariant(targetBlock, targetMeta, target);
        validateGtPayloadIdentity(target, tile);
        if (item != null && (item.hasKey("mID") || item.hasKey("id"))) validateGtPayloadIdentity(target, item);
        tile = HologramGtMeShadow.project(tile);
        return HologramEnergyPayload.prepare(targetBlock, targetMeta, tile, item);
    }

    /** A GT native reader can recreate its metatile from mID; that identity must match the paid material. */
    static void validateGtPayloadIdentity(ItemStack target, NBTTagCompound payload) throws java.io.IOException {
        if (target == null || !(target.getItem() instanceof ItemMachines) || payload == null) return;
        String expected = ItemMachines.getMetaTileEntity(target) instanceof gregtech.api.metatileentity.MetaPipeEntity
            ? "BaseMetaPipeEntity"
            : "BaseMetaTileEntity";
        HologramPresets.validateGtPayload(payload, target.getItemDamage(), expected);
    }

    static void validateGtPlacedIdentity(ItemStack target, Block actualBlock, TileEntity actual)
        throws java.io.IOException {
        if (target == null || !(target.getItem() instanceof ItemMachines)) return;
        if (actualBlock != Block.getBlockFromItem(target.getItem()) || !(actual instanceof IGregTechTileEntity)
            || ((IGregTechTileEntity) actual).getMetaTileID() != target.getItemDamage())
            throw new java.io.IOException("放置后的 GT 设备身份与已付款材料不一致，已回滚本格");
    }

    /** Preserve source orientation only when it represents the same physical item variant as the chosen material. */
    static int targetPreviewMeta(HologramSession s, HologramCapture.Cell cell, ItemStack target)
        throws java.io.IOException {
        if (target == null) return cell.meta;
        int nativeMetadata = target.getItem()
            .getMetadata(target.getItemDamage());
        if (!s.virtual || s.virtualPreset == null || s.virtualPreset.schema < 2) return nativeMetadata;
        validateOrdinarySourceVariant(s, cell);
        Block material = Block.getBlockFromItem(target.getItem());
        if (HologramNativePlacement.supported(cell.block)) return cell.meta;
        if (target.getItem() instanceof ItemMachines) return cell.block == material ? cell.meta : nativeMetadata;
        if (!(target.getItem() instanceof ItemBlock)) return nativeMetadata;
        try {
            HologramPresets.validateMaterialVariant(cell.block, cell.meta, target);
            return cell.meta;
        } catch (java.io.IOException differentVariant) {
            return nativeMetadata;
        }
    }

    /** Imported source identity must be valid before a selected variant can reuse or replace its metadata. */
    static void validateOrdinarySourceVariant(HologramSession s, HologramCapture.Cell cell) throws java.io.IOException {
        if (!s.virtual || s.virtualPreset == null
            || s.virtualPreset.schema < 2
            || HologramNativePlacement.supported(cell.block)
            || cell.blueprintItem.isEmpty()) return;
        Object original = Item.itemRegistry.getObject(cell.blueprintItem);
        if (original instanceof ItemBlock && !(original instanceof ItemMachines)) HologramPresets
            .validateMaterialVariant(cell.block, cell.meta, new ItemStack((Item) original, 1, cell.blueprintDamage));
    }

    static Block targetPreviewBlock(HologramSession s, HologramCapture.Cell cell, ItemStack target) {
        if (target == null || s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && HologramNativePlacement.supported(cell.block)) return cell.block;
        Block material = Block.getBlockFromItem(target.getItem());
        if (s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && target.getItem() instanceof ItemBlock
            && !(target.getItem() instanceof ItemMachines)) {
            try {
                HologramPresets.validateMaterialVariant(cell.block, cell.meta, target);
                return cell.block;
            } catch (java.io.IOException differentVariant) {
                // A selected physical variant gets its own native block and metadata.
            }
        }
        return material;
    }

    static void validateOrdinaryPlacedVariant(World world, HologramCapture.Cell cell, ItemStack paid)
        throws java.io.IOException {
        if (paid == null || !(paid.getItem() instanceof ItemBlock)
            || paid.getItem() instanceof ItemMachines
            || HologramNativePlacement.supported(cell.block)) return;
        Block actual = world.getBlock(cell.x, cell.y, cell.z);
        HologramPresets.validateMaterialVariant(actual, world.getBlockMetadata(cell.x, cell.y, cell.z), paid);
        try {
            int actualDamage = paid.getItem()
                .getHasSubtypes() ? actual.getDamageValue(world, cell.x, cell.y, cell.z) : 0;
            if (actualDamage != paid.getItemDamage()) throw new java.io.IOException("实际方块材料变种与已付款物品不一致，已回滚本格");
        } catch (RuntimeException | LinkageError invalidNative) {
            throw new java.io.IOException("无法核验实际方块的原生材料变种，已回滚本格", invalidNative);
        }
    }

    /** NC's native panel factory supplies defaults; only its audited sensor observations can differ. */
    static Boolean nativePanelMatches(HologramSession s, HologramCapture.Cell cell, boolean restoredConfiguration)
        throws java.io.IOException {
        if (!s.virtualPreset.retainNbt || cell.tileNbt.isEmpty()
            || cell.block == null
            || cell.meta != 4 && cell.meta != 9) return null;
        String blockType = cell.block.getClass()
            .getName();
        if (!blockType.equals("shedar.mods.ic2.nuclearcontrol.blocks.BlockNuclearControlMain")
            && !blockType.equals("shedar.mods.ic2.nuclearcontrol.blocks.BlockNuclearControlLight")) return null;
        String registry = Block.blockRegistry.getNameForObject(cell.block);
        if (registry == null || Block.getBlockFromName(registry) != cell.block) return false;
        TileEntity actual = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
        if (actual == null) return false;
        try {
            HologramResourceWorld detached = HologramResourceWorld.create(cell.block, cell.meta);
            TileEntity expected = cell.block.createTileEntity(detached, cell.meta);
            if (expected == null || !expected.getClass()
                .equals(actual.getClass())) return false;
            String tileType = expected.getClass()
                .getName();
            if (!tileType.equals("shedar.mods.ic2.nuclearcontrol.tileentities.TileEntityInfoPanel")
                && !tileType.equals("shedar.mods.ic2.nuclearcontrol.tileentities.TileEntityAdvancedInfoPanel"))
                return false;
            if (!restoredConfiguration) return true;
            NBTTagCompound payload = HologramBlueprintNbt.nativePayload(
                HologramCoordinatePolicies.placementProjection(
                    HologramBlueprintNbt.forPlacement(
                        cell.block,
                        cell.meta,
                        cell.tileNbt,
                        s.player.getUniqueID(),
                        s.player.getCommandSenderName(),
                        cell.x,
                        cell.y,
                        cell.z,
                        true,
                        HologramSessionPolicy.placementRetainCovers(s),
                        HologramSessionPolicy.placementKeys(s)),
                    s.dimension,
                    cell.x,
                    cell.y,
                    cell.z));
            if (payload == null) return false;
            expected.setWorldObj(detached);
            detached.tile = expected;
            expected.readFromNBT(payload);
            NBTTagCompound expectedState = nativePanelState(expected), actualState = nativePanelState(actual);
            if (!HologramCoordinatePolicies.nativePanelPayload(expectedState)
                || payload.hasKey("id") && !payload.getString("id")
                    .equals(expectedState.getString("id")))
                return false;
            return HologramCoordinatePolicies.nativePanelProjection(expectedState)
                .equals(HologramCoordinatePolicies.nativePanelProjection(actualState));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError invalidNative) {
            throw new java.io.IOException("无法核验原生 NC 面板的完整配置与传感卡目标", invalidNative);
        }
    }

    /** NC retains screenData until its screen joins the world; writer omission cannot erase those static bounds. */
    static NBTTagCompound nativePanelState(TileEntity panel) throws ReflectiveOperationException {
        NBTTagCompound tag = new NBTTagCompound();
        panel.writeToNBT(tag);
        if (!tag.hasKey("screenData")) {
            java.lang.reflect.Field screen = panel.getClass()
                .getField("screenData");
            if (screen.getType() != NBTTagCompound.class) throw new NoSuchFieldException("NC screenData schema");
            NBTTagCompound pending = (NBTTagCompound) screen.get(panel);
            if (pending != null) tag.setTag("screenData", pending.copy());
        }
        return tag;
    }

    static HologramDecorMaterials.Descriptor decoration(HologramSession s, HologramCapture.Cell cell)
        throws java.io.IOException {
        if (!s.virtual || s.virtualPreset == null
            || s.virtualPreset.schema < 2
            || !HologramDecorMaterials.supports(cell.block)
            || HologramRecovery.hasSeal(targetFor(s, cell, HologramSessionPolicy.cellIndex(s, cell)))) return null;
        NBTTagCompound payload = HologramBlueprintNbt.decode(targetPreviewTile(s, cell));
        if (!HologramDecorMaterials.supports(cell.block, payload)) return null;
        if (!s.virtualPreset.retainNbt) throw new java.io.IOException("装饰形状需要保留已登记的设备 NBT");
        return HologramDecorMaterials.describe(cell.block, cell.meta, payload);
    }

    static boolean decorationTarget(HologramSession s, HologramCapture.Cell cell) {
        try {
            return decoration(s, cell) != null;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    static boolean resourcePlanValid(HologramSession s, HologramCapture.Cell cell) {
        try {
            resourcePlan(s, cell);
            return true;
        } catch (java.io.IOException invalid) {
            return false;
        }
    }

    static List<ItemStack> coverCosts(HologramSession s, HologramCapture.Cell cell) {
        if (s.virtual && HologramRecovery.hasSeal(targetFor(s, cell, HologramSessionPolicy.cellIndex(s, cell))))
            return Collections.emptyList();
        if (!s.virtual || s.virtualPreset == null
            || s.virtualPreset.schema < 2
            || !HologramSessionPolicy.placementRetainCovers(s)
            || cell.tileNbt.isEmpty()) return Collections.emptyList();
        if (!cell.tileNbt.equals(cell.coverCostsNbt)) {
            cell.coverCostsNbt = cell.tileNbt;
            cell.invalidCoverCosts = false;
            try {
                cell.coverMaterials = HologramBlueprintCovers.materials(cell.tileNbt);
            } catch (java.io.IOException invalid) {
                cell.coverMaterials = Collections.emptyList();
                cell.invalidCoverCosts = true;
            }
        }
        return cell.coverMaterials;
    }

    static boolean coverCostsValid(HologramSession s, HologramCapture.Cell cell) {
        if (s.virtual && HologramRecovery.hasSeal(targetFor(s, cell, HologramSessionPolicy.cellIndex(s, cell))))
            return true;
        coverCosts(s, cell);
        return !s.virtual || s.virtualPreset == null
            || !HologramSessionPolicy.placementRetainCovers(s)
            || !cell.invalidCoverCosts;
    }

    static boolean originalMember(HologramSession s, HologramCapture.Cell c) {
        if (c.anchor || c.userPosition || c.element == null) return false;
        HologramCapture original = s.capture;
        if (original == null) return false;
        World world = s.player.worldObj;
        TileEntity tile = world.getTileEntity(c.x, c.y, c.z);
        if (tile instanceof IGregTechTileEntity) {
            if (world.getBlock(c.x, c.y, c.z) instanceof BlockFrameBox) return HologramFrameSupport
                .acceptsCovered(c.element, world.getBlock(c.x, c.y, c.z), world.getBlockMetadata(c.x, c.y, c.z));
            return ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEHatch && hatchElement(
                s,
                c,
                new ItemStack(GregTechAPI.sBlockMachines, 1, ((IGregTechTileEntity) tile).getMetaTileID()),
                true) != null;
        }
        if (c.block == null || c.block == Blocks.air || c.unknown) return false;
        return tile == null && destructive(c, world.getBlock(c.x, c.y, c.z), world.getBlockMetadata(c.x, c.y, c.z));
    }

    static boolean foreignBlueprintOwner(HologramSession s, HologramCapture.Cell cell) throws java.io.IOException {
        return foreignBlueprintPayloadOwner(s, cell.block, HologramBlueprintNbt.decode(cell.tileNbt))
            || foreignBlueprintPayloadOwner(s, cell.block, HologramBlueprintNbt.decode(cell.itemNbt));
    }

    static boolean foreignBlueprintPayloadOwner(HologramSession s, Block block, NBTTagCompound payload) {
        return HologramDeidentification
            .foreignOwner(block, payload, s.player.getUniqueID(), s.player.getCommandSenderName());
    }

    /** A physical seal is the paid state. Template data may supply a verified join descriptor, never assets. */
    static ItemStack sealedPlacementTarget(HologramSession s, HologramCapture.Cell cell, int index)
        throws java.io.IOException {
        ItemStack target = targetFor(s, cell, index);
        if (!HologramRecovery.hasSeal(target)) return null;
        Block block = Block.getBlockFromItem(target.getItem());
        if (!HologramRecovery.validItem(target, block)
            || !(target.getItem() instanceof ItemMachines || block instanceof BlockFrameBox))
            throw new java.io.IOException("封存设备身份或完整状态无效，未扣材料");
        NBTTagCompound envelope = target.getTagCompound()
            .getCompoundTag(HologramRecovery.TAG);
        NBTTagCompound stored = envelope.getCompoundTag("tile");
        int id = envelope.getInteger("mID");
        if (id <= 0 || id >= GregTechAPI.METATILEENTITIES.length || GregTechAPI.METATILEENTITIES[id] == null)
            throw new java.io.IOException("封存设备的原机器 ID 未注册，未扣材料");
        if (HologramNetworkSchemas.removeExternalReferences(block, id, (NBTTagCompound) stored.copy())
            || stored.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)
            || stored.hasKey("anchorDim")
            || stored.hasKey(HologramNetworkBindings.GROUP))
            throw new java.io.IOException("封存设备包含无法安全合并的外部网络状态；请先解除原网络绑定，未扣材料");
        HologramBlueprintCovers.materials(HologramBlueprintNbt.encodeCanonical(stored)); // Validate paid covers once.
        NBTTagCompound descriptor = HologramBlueprintNbt.decode(cell.tileNbt);
        if (descriptor != null && descriptor.hasKey(HologramNetworkBindings.GROUP)) {
            if (!"join".equals(descriptor.getString(HologramNetworkBindings.ROLE)))
                throw new java.io.IOException("封存设备不能合并此量子节点角色，未扣材料");
            HologramNetworkBindings.validate(descriptor, s.player, s.networkBindings);
        }
        ItemStack placed = target.copy();
        NBTTagCompound copy = (NBTTagCompound) target.getTagCompound()
            .copy();
        NBTTagCompound seal = copy.getCompoundTag(HologramRecovery.TAG);
        seal.setTag(
            "tile",
            sealedActorTile(stored, s.player.getUniqueID(), s.player.getCommandSenderName(), cell.x, cell.y, cell.z));
        placed.setTagCompound(copy);
        return placed;
    }

    /** Pure actor-only projection: inventory, fluid, energy, machine settings and cover contents stay exact. */
    static NBTTagCompound sealedActorTile(NBTTagCompound stored, java.util.UUID actor, String actorName, int x, int y,
        int z) throws java.io.IOException {
        return HologramBlueprintNbt.nativePayload(
            HologramBlueprintNbt
                .forPlacement(HologramBlueprintNbt.encodeCanonical(stored), actor, actorName, x, y, z, true, true));
    }

    static boolean sealedMatchesPlaced(HologramSession s, HologramCapture.Cell cell, int index, int facing) {
        try {
            ItemStack expected = s.virtual ? sealedPlacementTarget(s, cell, index) : targetFor(s, cell, index);
            if (expected == null) return false;
            TileEntity actual = s.player.worldObj.getTileEntity(cell.x, cell.y, cell.z);
            if (s.virtual && actual != null) {
                NBTTagCompound seal = expected.getTagCompound()
                    .getCompoundTag(HologramRecovery.TAG);
                NBTTagCompound actorState = HologramBlueprintOwnership
                    .prepare(actual, seal.getCompoundTag("tile"), s.player);
                seal.setTag("tile", actorState);
                NBTTagCompound descriptor = HologramBlueprintNbt.decode(cell.tileNbt);
                if (!HologramNetworkBindings.matchesBinding(actual, descriptor, s.player, s.networkBindings))
                    return false;
            }
            return HologramRecovery.matchesPlaced(s.player.worldObj, cell.x, cell.y, cell.z, expected, facing);
        } catch (java.io.IOException | RuntimeException | LinkageError unsafe) {
            return false;
        }
    }

    /** Select the planned display payload without installation assertions that would hide repairable blockers. */
    static String targetPreviewTile(HologramSession s, HologramCapture.Cell cell) throws java.io.IOException {
        if (!s.virtual) return "";
        if (s.virtualPreset == null || s.virtualPreset.schema < 2) return cell.tileNbt;
        String selected = HologramBlueprintNbt.filterCanonical(
            cell.block,
            cell.tileNbt,
            s.virtualPreset.retainNbt ? HologramSessionPolicy.placementKeys(s) : Collections.emptyList(),
            HologramSessionPolicy.placementRetainCovers(s));
        return HologramBlueprintNbt.encodeCanonical(
            HologramCoordinatePolicies.placementProjection(
                HologramGtMeShadow.project(HologramBlueprintNbt.decode(selected)),
                s.dimension,
                cell.x,
                cell.y,
                cell.z));
    }

    static boolean plainTargetInstalled(HologramSession s, HologramCapture.Cell cell, ItemStack target) {
        World world = s.player.worldObj;
        if (!world.blockExists(cell.x, cell.y, cell.z)) return false;
        if (target.getItem() instanceof ItemMachines) {
            TileEntity tile = world.getTileEntity(cell.x, cell.y, cell.z);
            return tile instanceof IGregTechTileEntity
                && ((IGregTechTileEntity) tile).getMetaTileID() == target.getItemDamage();
        }
        return world.getBlock(cell.x, cell.y, cell.z) == Block.getBlockFromItem(target.getItem())
            && world.getBlockMetadata(cell.x, cell.y, cell.z) == target.getItem()
                .getMetadata(target.getItemDamage());
    }

    /** Frozen coordinates survive extension changes; all derived interpretation/candidate caches must not. */
    static void refreshDeclarationGeneration(HologramSession s) {
        if (!s.declarations.invalidateIfStale()) return;
        s.hatchCandidates.clear();
        s.hatchParts.clear();
        s.hatchRequirements.clear();
        if (s.capture == null) return;
        s.capture.clearInterpretationCaches();
        for (HologramCapture.Cell cached : s.capture.cells) {
            cached.candidates = null;
            cached.candidateComplete = false;
            if (cached.element == null || cached.anchor) continue;
            String role = HologramElementCatalog.hatchRole(cached.element);
            cached.role = "block".equals(role) && (cached.actualStack != null || cached.unknown) ? "interface" : role;
            HologramReplacementFamily.Family family = HologramReplacementFamily
                .resolve(cached.element, cached.block, cached.meta);
            cached.family = family == null ? "" : family.id;
        }
    }

    @SuppressWarnings("unchecked")
    static List<ItemStack> candidates(HologramSession s, HologramCapture.Cell cell) {
        refreshDeclarationGeneration(s);
        if (s.virtual && s.virtualPreset != null) {
            cell.candidateComplete = false;
            cell.candidateBoundary = "预设调色板仅提供编辑样本；原机器结构声明未随预设保存";
            List<ItemStack> result = new ArrayList<>();
            if (cell.structureTargets != null) {
                cell.candidateBoundary = "仅提供本格保存的结构声明候选；共享格须同时符合各成员结构";
                for (ItemStack identity : cell.structureTargets) {
                    ItemStack sample = declaredSample(s, cell, identity);
                    if (placeableSample(s, cell, sample)) result.add(sample);
                }
                return result;
            }
            for (HologramPresets.Cell entry : s.virtualPreset.palette) {
                ItemStack sample = entry.stack();
                if (placeableSample(s, cell, sample)) result.add(sample);
            }
            return result;
        }
        if (cell.element == null) return Collections.emptyList();
        if (cell.candidates != null) return cell.candidates;
        List<ItemStack> result = declaredCandidates(s, cell);
        HologramArea area = HologramWorkspace.AREAS.get(s.player.getUniqueID());
        if (area != null && !s.areaStructureId.isEmpty()) for (String id : cell.memberships()) {
            if (id.equals(s.areaStructureId)) continue;
            HologramSession other = HologramAreaDeclarations.areaDeclarationSession(s, area, id);
            HologramCapture.Cell shared = other == null || other.capture == null ? null
                : other.capture.positions.get(HologramValues.position(cell));
            if (shared == null || other.capture.incomplete || other.capture.pending) {
                result.clear();
                cell.candidateComplete = false;
                cell.candidateBoundary = "共享格的成员结构声明不完整，目标编辑已保护";
                break;
            }
            List<ItemStack> allowed = declaredCandidates(other, shared);
            if (!shared.candidateComplete) {
                cell.candidateComplete = false;
                cell.candidateBoundary = "共享格的成员声明未完整解析，仅提供已知候选交集";
            }
            result.removeIf(
                target -> allowed.stream()
                    .noneMatch(option -> HologramValues.sameMaterial(option, target)));
        }
        cell.candidates = result;
        return result;
    }

    /** Revalidate a native declaration summary once per cache generation, without publishing unselected options. */
    static List<ItemStack> snapshotCandidates(HologramSession s, HologramCapture.Cell cell, boolean selected) {
        if (!selected && (s.virtual || cell.element == null || cell.anchor)) return Collections.emptyList();
        // candidates() owns generation invalidation and the per-cell/collector caches.
        // A cleared cache is pending interpretation, not evidence of an incomplete declaration.
        List<ItemStack> current = candidates(s, cell);
        return selected ? current : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    static List<ItemStack> declaredCandidates(HologramSession s, HologramCapture.Cell cell) {
        refreshDeclarationGeneration(s);
        ItemStack signal = elementTrigger(s, cell);
        ChannelDataAccessor.setChannelData(signal, "gt_hatch", 1);
        HologramDeclarationCandidates declaration = s.declarations.collect(
            cell.element,
            s.machine,
            s.player.worldObj,
            cell.x,
            cell.y,
            cell.z,
            s.areaStructureId,
            signal,
            AutoPlaceEnvironment.fromLegacy(
                (predicate, simulate, count) -> HologramMaterialAccess.materialSource(s)
                    .take(predicate, true, count),
                s.player,
                message -> {}));
        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : declaration.stacks) {
            Block block = Block.getBlockFromItem(stack.getItem());
            if (!s.noHatches || cell.block == StructureLibAPI.getBlockHint() && "hatch".equals(cell.role)
                || (block != Blocks.air && (!block.hasTileEntity(stack.getItemDamage())
                    || HologramFrameSupport.inertFrameBlock(block, stack.getItemDamage()))))
                result.add(stack);
        }
        cell.candidateComplete = declaration.complete;
        cell.candidateBoundary = declaration.complete ? "" : "仅列出已识别的结构声明；自定义校验或动态条件未完整解析";
        return result;
    }

    @SuppressWarnings("unchecked")
    static IStructureElement hatchElement(HologramSession s, HologramCapture.Cell cell, ItemStack sample) {
        return hatchElement(s, cell, sample, true);
    }

    @SuppressWarnings("unchecked")
    static IStructureElement hatchElement(HologramSession s, HologramCapture.Cell cell, ItemStack sample,
        boolean manual) {
        ItemStack signal = elementTrigger(s, cell);
        ChannelDataAccessor.setChannelData(signal, "gt_hatch", 1);
        return hatchElement(s, cell, sample, manual, signal);
    }

    @SuppressWarnings("unchecked")
    static IStructureElement hatchElement(HologramSession s, HologramCapture.Cell cell, ItemStack sample,
        boolean manual, ItemStack signal) {
        refreshDeclarationGeneration(s);
        if (hatchTier(sample) == Integer.MAX_VALUE) return null;
        List<IStructureElement<?>> parts = s.hatchParts.get(cell.element);
        if (parts == null) {
            parts = new ArrayList<>();
            for (IStructureElement<?> part : HologramElementCatalog.parts(cell.element))
                if ("hatch".equals(HologramElementCatalog.hatchRole(part))) parts.add(part);
            s.hatchParts.put(cell.element, parts);
        }
        for (IStructureElement part : parts) {
            try {
                java.util.function.Predicate<ItemStack> declared = manual
                    ? HologramHatchPolicy.predicate(part, s.machine, signal)
                    : null;
                if (declared != null) {
                    if (declared.test(sample)) return part;
                    continue;
                }
                IStructureElement.BlocksToPlace blocks = part.getBlocksToPlace(
                    s.machine,
                    s.player.worldObj,
                    cell.x,
                    cell.y,
                    cell.z,
                    signal,
                    AutoPlaceEnvironment.fromLegacy(
                        (predicate, simulate, count) -> HologramMaterialAccess.materialSource(s)
                            .take(predicate, true, count),
                        s.player,
                        message -> {}));
                if (blocks != null && blocks.getPredicate()
                    .test(sample)) return part;
            } catch (RuntimeException | LinkageError ignored) {}
        }
        return null;
    }

    static boolean recoverable(HologramSession s, HologramCapture.Cell c) {
        TileEntity tile = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
        if (!HologramRecovery.supported(tile)) return false;
        if (c.anchor) return false;
        int index = HologramSessionPolicy.cellIndex(s, c);
        if (explicitTarget(s, c, index) && tile instanceof IGregTechTileEntity) return true;
        if (s.mode == 2 && !c.userPosition
            && c.block != null
            && c.block != Blocks.air
            && tile instanceof IGregTechTileEntity) return true;
        if (tile instanceof IGregTechTileEntity
            && ((IGregTechTileEntity) tile).getMetaTileEntity() instanceof MTEHatch) {
            int id = ((IGregTechTileEntity) tile).getMetaTileID();
            return hatchElement(s, c, new ItemStack(GregTechAPI.sBlockMachines, 1, id)) != null;
        }
        Block block = s.player.worldObj.getBlock(c.x, c.y, c.z);
        int meta = s.player.worldObj.getBlockMetadata(c.x, c.y, c.z);
        return block instanceof BlockFrameBox && (HologramFrameSupport.acceptsCovered(c.element, block, meta)
            || destructive(c, block, meta & ~BlockFrameBox.MTE_BIT));
    }

    static boolean removable(HologramSession s, HologramCapture.Cell c) {
        World world = s.player.worldObj;
        if (s.mode == 1) return originalMember(s, c);
        if (world.getTileEntity(c.x, c.y, c.z) != null)
            return recoverable(s, c) && (s.mode != 0 || originalMember(s, c));
        if (s.mode == 0 && s.player.capabilities.isCreativeMode) return !c.anchor;
        int index = HologramSessionPolicy.cellIndex(s, c);
        return !c.anchor && (explicitTarget(s, c, index)
            || destructive(c, world.getBlock(c.x, c.y, c.z), world.getBlockMetadata(c.x, c.y, c.z)));
    }

    static void setBlueprintTarget(HologramSession s, HologramCapture.Cell cell, ItemStack target) {
        if (!s.virtual || s.virtualPreset == null || s.virtualPreset.schema < 2 || target == null) return;
        Block block = Block.getBlockFromItem(target.getItem());
        if (HologramNativePlacement.supported(cell.block)) {
            try {
                if (HologramNativePlacement
                    .matchesSample(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt), target))
                    block = cell.block;
            } catch (java.io.IOException invalid) {
                // Changing an unsupported draft still uses the normal replacement reset below.
            }
        } else block = targetPreviewBlock(s, cell, target);
        int metadata = target.getItem()
            .getMetadata(target.getItemDamage());
        try {
            if (!HologramNativePlacement.supported(cell.block) || block == cell.block)
                metadata = targetPreviewMeta(s, cell, target);
        } catch (java.io.IOException invalidSource) {
            // This explicit user choice repairs its source identity using the chosen native material.
        }
        String item = String.valueOf(Item.itemRegistry.getNameForObject(target.getItem()));
        if (cell.block != block || cell.meta != metadata
            || (!cell.blueprintItem.isEmpty()
                && (!cell.blueprintItem.equals(item) || cell.blueprintDamage != target.getItemDamage()))) {
            cell.tileNbt = "";
            cell.itemNbt = "";
            cell.coverCostsNbt = "";
            s.nbtKeysCapture = null;
            cell.coverMaterials = Collections.emptyList();
            cell.invalidCoverCosts = false;
            cell.block = block;
            cell.meta = metadata;
        }
        cell.blueprintItem = item;
        cell.blueprintDamage = target.getItemDamage();
        cell.blueprintBlock = String.valueOf(Block.blockRegistry.getNameForObject(block));
        cell.unknown = false;
    }

    /** Saved declarations contain identities, while native cuts require the exact retained shape NBT. */
    private static ItemStack declaredSample(HologramSession s, HologramCapture.Cell cell, ItemStack identity) {
        if (s.virtual && s.virtualPreset.schema >= 2 && HologramNativePlacement.supported(cell.block)) {
            try {
                ItemStack nativeSample = HologramNativePlacement
                    .sample(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt));
                return HologramValues.sameMaterial(identity, nativeSample) ? nativeSample : null;
            } catch (java.io.IOException invalid) {
                return null;
            }
        }
        return identity;
    }

    static boolean acceptableSample(HologramSession s, HologramCapture.Cell cell, ItemStack sample) {
        if (!placeableSample(s, cell, sample)) return false;
        if ("templateController".equals(cell.role))
            return sample.getItem() instanceof ItemMachines && sample.getItemDamage() == cell.blueprintDamage;
        // A captured world blueprint already declares the actual GT item at this cell. StructureLib's
        // advertised editing candidates can omit an add-on hatch accepted by its live structure element.
        // Replaying that exact source item uses ItemMachines; it must not require a hatch-adder candidate.
        if (s.virtual && s.virtualPreset != null
            && s.virtualPreset.schema >= 2
            && sample.getItem() instanceof ItemMachines
            && cell.block == Block.getBlockFromItem(sample.getItem())
            && cell.blueprintDamage == sample.getItemDamage()
            && cell.blueprintItem.equals(Item.itemRegistry.getNameForObject(sample.getItem()))) return true;
        if (cell.structureTargets != null || (!s.areaStructureId.isEmpty() && !cell.memberships()
            .isEmpty())) {
            // placeableSample above already validates the full native payload. Compare the declared
            // identity here; validating its intentionally tagless serialization would discard all cuts.
            List<ItemStack> declared = s.virtual && cell.structureTargets != null ? cell.structureTargets
                : candidates(s, cell);
            if (declared.stream()
                .anyMatch(option -> HologramValues.sameMaterial(option, sample))) return true;
            // Old height-dependent declarations can describe a different cap at this coordinate.
            // Only an exact, verified plain source may bypass that stale editing list; never enlarge it.
            return HologramBlueprintSourceMaterial.plainReplay(s, cell, sample);
        }
        return true;
    }

    static boolean placeableSample(HologramSession s, HologramCapture.Cell cell, ItemStack sample) {
        if (cell.anchor || sample == null || sample.getItem() == null || sample.stackSize < 1) return false;
        if (hintMaterial(sample)) return false;
        if (sample.getItemDamage() < 0) return false;
        if (s.virtual && (s.virtualPreset == null || (s.virtualPreset.schema < 2 && !s.virtualPreset.allows(sample))))
            return false;
        if (s.virtual && s.virtualPreset.schema < 2
            && "templateController".equals(cell.role)
            && (!(sample.getItem() instanceof ItemMachines) || sample.getItemDamage() != s.virtualPreset.controllerId))
            return false;
        if (sample.getItem() instanceof ItemMachines) {
            int id = sample.getItemDamage();
            return id >= 0 && id < GregTechAPI.METATILEENTITIES.length && GregTechAPI.METATILEENTITIES[id] != null;
        }
        if (s.virtual && s.virtualPreset.schema >= 2 && HologramNativePlacement.supported(cell.block)) {
            try {
                return HologramNativePlacement
                    .matchesSample(cell.block, cell.meta, HologramBlueprintNbt.decode(cell.tileNbt), sample);
            } catch (java.io.IOException invalid) {
                return false;
            }
        }
        Block block = Block.getBlockFromItem(sample.getItem());
        return sample.getItem() instanceof ItemBlock && block != Blocks.air
            && (!s.virtual || s.virtualPreset.schema >= 2
                || !block.hasTileEntity(
                    sample.getItem()
                        .getMetadata(sample.getItemDamage())));
    }

    static int hatchTier(ItemStack stack) {
        if (stack == null) return Integer.MAX_VALUE;
        int id = stack.getItemDamage();
        return stack.getItem() instanceof ItemMachines && id >= 0
            && id < GregTechAPI.METATILEENTITIES.length
            && GregTechAPI.METATILEENTITIES[id] instanceof MTEHatch
                ? ((MTEHatch) GregTechAPI.METATILEENTITIES[id]).mTier
                : Integer.MAX_VALUE;
    }

    static Map<gregtech.api.interfaces.IHatchElement<?>, Integer> hatchRequirements(HologramSession s,
        HologramCapture.Cell c) {
        refreshDeclarationGeneration(s);
        Map<gregtech.api.interfaces.IHatchElement<?>, Integer> cached = s.hatchRequirements.get(c.element);
        if (cached != null) return cached;
        Map<gregtech.api.interfaces.IHatchElement<?>, Integer> result = new LinkedHashMap<>();
        for (IStructureElement<?> part : HologramElementCatalog.parts(c.element)) HologramHatchPolicy.requirements(part)
            .forEach((type, count) -> result.merge(type, count, Math::max));
        s.hatchRequirements.put(c.element, result);
        return result;
    }

    static String candidateRole(HologramSession s, HologramCapture.Cell c, ItemStack stack) {
        if (hatchTier(stack) == Integer.MAX_VALUE) return "外壳/方块";
        gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(stack);
        for (IStructureElement<?> part : HologramElementCatalog.parts(c.element))
            for (gregtech.api.interfaces.IHatchElement<?> type : HologramHatchPolicy.declared(part))
                if (HologramHatchPolicy.matchesHatch(type, prototype)) return type.getDisplayName();
        return "仓室";
    }

    static int[] legalFacings(HologramSession s, HologramCapture.Cell c, ItemStack target) {
        if (c.anchor || target == null
            || (!(s.virtual && target.getItem() instanceof ItemMachines) && hatchTier(target) == Integer.MAX_VALUE))
            return new int[0];
        gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = ItemMachines.getMetaTileEntity(target);
        if (prototype instanceof gregtech.api.metatileentity.MetaPipeEntity) return new int[0];
        IStructureElement<?> part = s.virtual ? null : hatchElement(s, c, target, true);
        List<Integer> result = new ArrayList<>();
        for (ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS)
            if (prototype != null && prototype.isFacingValid(direction)
                && (part == null || HologramHatchPolicy.allowed(part, s.machine.getExtendedFacing(), direction)))
                result.add(direction.ordinal());
        int[] values = new int[result.size()];
        for (int i = 0; i < values.length; i++) values[i] = result.get(i);
        return values;
    }

    static boolean orientationOnly(HologramSession s, HologramCapture.Cell c, int index) {
        if (HologramRecovery.hasSeal(targetFor(s, c, index))) {
            TileEntity actual = s.player.worldObj.getTileEntity(c.x, c.y, c.z);
            return !c.anchor && s.facingPins.containsKey(HologramValues.position(c))
                && actual instanceof IGregTechTileEntity
                && sealedMatchesPlaced(
                    s,
                    c,
                    index,
                    ((IGregTechTileEntity) actual).getFrontFacing()
                        .ordinal());
        }
        return !c.anchor && s.facingPins.containsKey(HologramValues.position(c))
            && s.player.worldObj.getTileEntity(c.x, c.y, c.z) instanceof IGregTechTileEntity
            && targetMatches(
                s,
                c,
                index,
                s.player.worldObj.getBlock(c.x, c.y, c.z),
                s.player.worldObj.getBlockMetadata(c.x, c.y, c.z));
    }
}
