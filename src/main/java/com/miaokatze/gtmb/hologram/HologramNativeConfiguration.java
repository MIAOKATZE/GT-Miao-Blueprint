// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.enums.Textures;
import gregtech.api.enums.VoidingMode;
import gregtech.api.interfaces.IDataCopyable;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.modularui.IControllerWithOptionalFeatures;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.interfaces.tileentity.IRecipeLockable;
import gregtech.api.interfaces.tileentity.IVoidable;
import gregtech.api.metatileentity.implementations.MTEHatch;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Portable native configuration, separate from world persistence and from paid retained contents. */
final class HologramNativeConfiguration {

    static final String TAG = "gtmbNativeConfiguration";
    private static final int VERSION = 1;
    private static final Set<String> SETTINGS = new HashSet<>(
        Arrays.asList(
            "machineMode",
            "inputSeparation",
            "batchMode",
            "voidingMode",
            "mLockedToSingleRecipe",
            "powerPanelMaxParallel",
            "alwaysMaxParallel",
            "makePowerfailEvents"));
    private static final Set<String> INTS = new HashSet<>(
        Arrays.asList(
            "mProgresstime",
            "mMaxProgresstime",
            "mEfficiencyIncrease",
            "mEfficiency",
            "mPollution",
            "mRuntime",
            "lastParallel"));
    private static final Set<String> LONGS = new HashSet<>(
        Arrays.asList("mTotalRunTime", "mLastWorkingTick", "recipesDone"));
    private static final Set<String> MAINTENANCE = new HashSet<>(
        Arrays.asList("mWrench", "mScrewdriver", "mSoftMallet", "mHardHammer", "mSolderingTool", "mCrowbar"));
    // Same explicit network/security roots as the existing deidentification policy; copied data grants no binding.
    private static final Set<String> BINDINGS = new HashSet<>(
        Arrays.asList(
            "frequency",
            "freq",
            "channelId",
            "channelID",
            "networkId",
            "networkID",
            "mFrequency",
            "securityKey",
            "securityId",
            "securityID",
            "proxy",
            "linked",
            "GTSWN_IncorporationIdentity"));

    private HologramNativeConfiguration() {}

    /** Called only by blueprint capture, never by exact recovery capture. No paste or raw NBT load occurs here. */
    static void capture(TileEntity tile, EntityPlayer player, NBTTagCompound payload) throws IOException {
        if (!(tile instanceof IGregTechTileEntity gt) || payload == null) return;
        IMetaTileEntity machine = gt.getMetaTileEntity();
        if (machine == null) return;
        NBTTagCompound config = header(gt.getMetaTileID());
        NBTTagCompound settings = new NBTTagCompound();
        if (machine instanceof IControllerWithOptionalFeatures features) {
            if (features.supportsMachineModeSwitch()) settings.setInteger("machineMode", features.getMachineMode());
            if (features.supportsInputSeparation())
                settings.setBoolean("inputSeparation", features.isInputSeparationEnabled());
            if (features.supportsBatchMode()) settings.setBoolean("batchMode", features.isBatchModeEnabled());
        }
        if (machine instanceof IVoidable voidable && voidable.supportsVoidProtection())
            settings.setString("voidingMode", voidable.getVoidingMode().name);
        if (machine instanceof IRecipeLockable lockable && lockable.supportsSingleRecipeLocking())
            settings.setBoolean("mLockedToSingleRecipe", lockable.isRecipeLockingEnabled());
        if (machine instanceof MTEMultiBlockBase multi) {
            settings.setInteger("powerPanelMaxParallel", multi.getPowerPanelMaxParallel());
            settings.setBoolean("alwaysMaxParallel", multi.isAlwaysMaxParallel());
            settings.setBoolean("makePowerfailEvents", multi.makesPowerfailEvents());
        }
        if (!settings.hasNoTags()) config.setTag("settings", settings);
        if (player != null && machine instanceof IDataCopyable copyable) {
            NBTTagCompound copied = copyable.getCopiedData(player);
            if (copied != null) {
                String type = copyable.getCopiedDataIdentifier(player);
                if (type == null || type.isEmpty()
                    || !copied.hasKey("type", 8)
                    || !type.equals(copied.getString("type")))
                    throw new IOException("GT native configuration copy returned an invalid type");
                config.setTag("copied", copied.copy());
            }
        }
        if (config.hasKey("settings") || config.hasKey("copied")) payload.setTag(TAG, config);
    }

    /** Old files migrate only audited GT fields; no unknown MTE is initialized from its saved raw payload. */
    static void project(NBTTagCompound payload, ItemStack sample) throws IOException {
        if (payload == null) return;
        IMetaTileEntity machine = ItemMachines.getMetaTileEntity(sample);
        if (machine == null) throw new IOException("Unregistered GT configuration target");
        NBTTagCompound config = payload.hasKey(TAG) ? envelope(payload, sample.getItemDamage())
            : header(sample.getItemDamage());
        NBTTagCompound settings = config.getCompoundTag("settings");
        NBTTagCompound copied = config.getCompoundTag("copied");
        for (Object raw : new ArrayList<>(copied.func_150296_c())) {
            String key = (String) raw;
            if (key.equals("type") || !payload.hasKey(key)
                || copied.getTag(key)
                    .getId() >= 9)
                continue;
            if (!payload.getTag(key)
                .equals(copied.getTag(key)))
                throw new IOException("Conflicting GT native copied configuration: " + key);
            // The native IDataCopyable setter owns this exact configuration; never raw-load its duplicate.
            payload.removeTag(key);
        }
        if (machine instanceof MTEMultiBlockBase) {
            for (String key : SETTINGS) if (payload.hasKey(key)) {
                if (settings.hasKey(key) && !settings.getTag(key)
                    .equals(payload.getTag(key))) throw new IOException("Conflicting GT native configuration: " + key);
                settings.setTag(
                    key,
                    payload.getTag(key)
                        .copy());
                payload.removeTag(key);
            }
            // Old void-excess boolean predates the native enum. Preserve its documented meaning.
            if (payload.hasKey("voidExcess")) {
                bool(payload, "voidExcess");
                String mode = payload.getBoolean("voidExcess") ? "all" : "none";
                if (settings.hasKey("voidingMode") && !mode.equals(settings.getString("voidingMode")))
                    throw new IOException("Conflicting GT voiding configuration");
                settings.setString("voidingMode", mode);
                payload.removeTag("voidExcess");
            }
            for (String key : new ArrayList<String>(payload.func_150296_c())) {
                boolean observation = INTS.contains(key) || LONGS.contains(key)
                    || MAINTENANCE.contains(key)
                    || key.equals("mEUt")
                    || key.equals("checkRecipeResultID")
                    || key.equals("checkRecipeResult")
                    || key.equals("usesTurbine")
                    || key.equals("canBeMuffled");
                if (!observation) continue;
                if (INTS.contains(key)) typed(payload, key, 3);
                else if (LONGS.contains(key)) typed(payload, key, 4);
                else if (MAINTENANCE.contains(key) || key.equals("usesTurbine") || key.equals("canBeMuffled"))
                    bool(payload, key);
                else if (key.equals("mEUt")) {
                    // The native multiblock family persists this observation as int or long
                    // (extended-power controllers); neither width is a portable configuration value.
                    if (!payload.hasKey(key, 3) && !payload.hasKey(key, 4))
                        throw new IOException("Invalid GT native field type: " + key);
                } else typed(payload, key, key.equals("checkRecipeResultID") ? 8 : 10);
                payload.removeTag(key);
            }
            // A recipe lock is a setting; the check object is runtime recipe observation, not configuration.
            if (payload.hasKey("mSingleRecipeCheck")) {
                typed(payload, "mSingleRecipeCheck", 10);
                payload.removeTag("mSingleRecipeCheck");
            }
        }
        if (!settings.hasNoTags()) config.setTag("settings", settings);
        if (config.hasKey("settings") || config.hasKey("copied")) payload.setTag(TAG, config);
        validate(payload, sample);
        if (settings.hasKey("machineMode"))
            HologramGtNativeItemState.verifyMode(sample, settings.getInteger("machineMode"));
    }

    static void validate(NBTTagCompound payload, ItemStack sample) throws IOException {
        if (payload == null || !payload.hasKey(TAG)) return;
        NBTTagCompound config = envelope(payload, sample.getItemDamage());
        IMetaTileEntity machine = ItemMachines.getMetaTileEntity(sample);
        if (machine == null) throw new IOException("Unregistered GT native configuration");
        validateSettings(machine, config.getCompoundTag("settings"));
        if (config.hasKey("copied") && !(machine instanceof IDataCopyable))
            throw new IOException("GT target does not support native copied configuration");
    }

    /** MTEHatch's native int pair is loaded through updateTexture; it is not a general MTE setting schema. */
    static void validateHatchTexture(NBTTagCompound payload, ItemStack sample) throws IOException {
        if (payload == null || !payload.hasKey("texturePage") && !payload.hasKey("textureIndex")) return;
        if (!(ItemMachines.getMetaTileEntity(sample) instanceof MTEHatch))
            throw new IOException("GT texture page/index requires a native hatch");
        if (payload.hasKey("texturePage")) typed(payload, "texturePage", 3);
        if (payload.hasKey("textureIndex")) typed(payload, "textureIndex", 3);
        int page = payload.getInteger("texturePage"), index = payload.getInteger("textureIndex");
        // (0,0) is the hatch's built-in texture rather than an indexed external casing texture.
        if (page == 0 && index == 0) return;
        if (page < 0 || index < 0
            || index >= 128
            || Textures.BlockIcons.casingTexturePages == null
            || page >= Textures.BlockIcons.casingTexturePages.length
            || Textures.BlockIcons.casingTexturePages[page] == null
            || index >= Textures.BlockIcons.casingTexturePages[page].length
            || Textures.BlockIcons.casingTexturePages[page][index] == null)
            throw new IOException("GT hatch casing texture is not registered");
    }

    /** Preserve exact recovery payloads; only a copy of a projected construction payload loses this marker. */
    static NBTTagCompound strip(NBTTagCompound payload) {
        if (payload == null) return null;
        NBTTagCompound result = (NBTTagCompound) payload.copy();
        result.removeTag(TAG);
        return result;
    }

    /** Keep the established root-key selection meaningful after settings gain a versioned envelope. */
    static NBTTagCompound selected(NBTTagCompound source, Set<String> keys) throws IOException {
        if (!source.hasKey(TAG)) return null;
        if (!source.hasKey("mID", 99)) throw new IOException("GT configuration selection has no machine identity");
        NBTTagCompound config = envelope(source, source.getInteger("mID"));
        if (keys == null || keys.contains(TAG)) return config;
        NBTTagCompound kept = new NBTTagCompound();
        NBTTagCompound settings = config.getCompoundTag("settings");
        for (String key : SETTINGS) if (keys.contains(key) && settings.hasKey(key)) kept.setTag(
            key,
            settings.getTag(key)
                .copy());
        if (kept.hasNoTags()) return null;
        NBTTagCompound result = header(source.getInteger("mID"));
        result.setTag("settings", kept);
        return result;
    }

    /** Compare only retained semantics; live recipe progress and other unselected fields are irrelevant. */
    static boolean matchesRetained(TileEntity target, EntityPlayer player, ItemStack sample, NBTTagCompound expected)
        throws IOException {
        return matchesRetained(target, player, sample, expected, true);
    }

    static boolean matchesRetained(TileEntity target, EntityPlayer player, ItemStack sample, NBTTagCompound expected,
        boolean matchBuffers) throws IOException {
        if (sample == null) return false;
        if (sample.getItem() instanceof ItemMachines) {
            if (!(target instanceof IGregTechTileEntity gt) || gt.getMetaTileID() != sample.getItemDamage()
                || gt.getMetaTileEntity() == null) return false;
        } else if (target instanceof IGregTechTileEntity || expected != null && expected.hasKey(TAG)) return false;
        if (expected == null || expected.hasNoTags()) return true;
        if (target == null) return false;
        if (sample.getItem() instanceof ItemMachines) {
            validate(expected, sample);
            if (!matches(target, player, expected)) return false;
        }
        NBTTagCompound wanted = HologramBlueprintNbt.nativePayload(expected);
        NBTTagCompound actual = HologramGtMeShadow.project(HologramNativeCapture.capture(target));
        if (!matchBuffers) {
            wanted = HologramEnergyPayload.withoutBuffers(wanted);
            actual = HologramEnergyPayload.withoutBuffers(actual);
        }
        for (String key : new ArrayList<String>(wanted.func_150296_c())) {
            // These roots describe transport/source identity or the native storage encoding version.
            // The transaction validates ownership and binding through their existing dedicated contracts.
            if (key.equals("x") || key.equals("y")
                || key.equals("z")
                || key.equals("nbtVersion")
                || key.equals("mOwnerName")
                || key.equals("mOwnerUuid")
                || key.equals("mOwnerUUID")
                || HologramNetworkBindings.descriptorKey(key)) continue;
            NBTBase value = wanted.getTag(key), found = actual.getTag(key);
            if (value instanceof NBTBase.NBTPrimitive && value.getId() <= 4
                && found instanceof NBTBase.NBTPrimitive
                && found.getId() <= 4) {
                if (wanted.getLong(key) != actual.getLong(key)) return false;
            } else if (!value.equals(found)) return false;
        }
        return true;
    }

    /** Run after paid asset restoration, inside the caller's world/payment rollback scope. */
    static void apply(TileEntity target, EntityPlayer player, NBTTagCompound payload) throws IOException {
        if (payload == null || !payload.hasKey(TAG)) return;
        IGregTechTileEntity gt = target(target);
        IMetaTileEntity machine = gt.getMetaTileEntity();
        NBTTagCompound config = envelope(payload, gt.getMetaTileID());
        NBTTagCompound settings = config.getCompoundTag("settings");
        validateSettings(machine, settings);
        StateGuard preserved = StateGuard.capture(target);
        if (config.hasKey("copied")) {
            if (player == null || !(machine instanceof IDataCopyable copyable))
                throw new IOException("GT native configuration requires a player and copy capability");
            NBTTagCompound copied = copied(config, copyable, player);
            // IDataCopyable can write real slots (e.g. the ME bus circuit slot). Native copy is no asset authority.
            if (!copyable.pasteCopiedData(player, copied))
                throw new IOException("GT rejected native copied configuration");
        }
        for (String key : new ArrayList<String>(settings.func_150296_c())) {
            switch (key) {
                case "machineMode" -> ((IControllerWithOptionalFeatures) machine)
                    .setMachineMode(settings.getInteger(key));
                case "inputSeparation" -> {
                    IControllerWithOptionalFeatures features = (IControllerWithOptionalFeatures) machine;
                    if (features.supportsInputSeparation()) features.setInputSeparation(settings.getBoolean(key));
                }
                case "batchMode" -> {
                    IControllerWithOptionalFeatures features = (IControllerWithOptionalFeatures) machine;
                    if (features.supportsBatchMode()) features.setBatchMode(settings.getBoolean(key));
                }
                case "voidingMode" -> {
                    IVoidable voidable = (IVoidable) machine;
                    if (voidable.supportsVoidProtection()) voidable.setVoidingMode(mode(settings.getString(key)));
                }
                case "mLockedToSingleRecipe" -> {
                    IRecipeLockable lockable = (IRecipeLockable) machine;
                    if (lockable.supportsSingleRecipeLocking()) lockable.setRecipeLocking(settings.getBoolean(key));
                }
                case "powerPanelMaxParallel" -> {
                    MTEMultiBlockBase multi = (MTEMultiBlockBase) machine;
                    // Native setter persists a ceiling, and getTrueParallel clamps effective parallelism
                    // against the current structure. An unformed target's transient limit is not its setting domain.
                    multi.setPowerPanelMaxParallel(settings.getInteger(key));
                }
                case "alwaysMaxParallel" -> ((MTEMultiBlockBase) machine)
                    .setAlwaysMaxParallel(settings.getBoolean(key));
                case "makePowerfailEvents" -> ((MTEMultiBlockBase) machine)
                    .setPowerfailEventCreationStatus(settings.getBoolean(key));
                default -> throw new IOException("Unknown GT native configuration: " + key);
            }
        }
        preserved.verify(target);
        if (!matches(target, player, payload))
            throw new IOException("GT applied only part of the requested native configuration");
    }

    /** Shared native-setter invariant; configuration and geometry may change, protected assets/bindings may not. */
    static final class StateGuard {

        private final IGregTechTileEntity base;
        private final IMetaTileEntity machine;
        private final HologramBlueprintResources.Plan resources;
        private final NBTTagCompound inventory, binding;
        private final String owner;
        private final java.util.UUID ownerId;
        private final long energy, steam;

        private StateGuard(TileEntity tile) throws IOException {
            base = target(tile);
            machine = base.getMetaTileEntity();
            NBTTagCompound state = HologramNativeCapture.capture(tile);
            resources = HologramBlueprintResources.plan(state);
            inventory = physicalInventory(machine);
            binding = binding(state);
            owner = base.getOwnerName();
            ownerId = base.getOwnerUuid();
            energy = base.getStoredEU();
            steam = base.getStoredSteam();
        }

        static StateGuard capture(TileEntity tile) throws IOException {
            return new StateGuard(tile);
        }

        void verify(TileEntity tile) throws IOException {
            IGregTechTileEntity actual = target(tile);
            NBTTagCompound state = HologramNativeCapture.capture(tile);
            if (actual != base || actual.getMetaTileEntity() != machine
                || !Objects.equals(owner, actual.getOwnerName())
                || !Objects.equals(ownerId, actual.getOwnerUuid())
                || energy != actual.getStoredEU()
                || steam != actual.getStoredSteam()
                || !binding.equals(binding(state))
                || !inventory.equals(physicalInventory(machine))
                || !sameAssets(resources, HologramBlueprintResources.plan(state)))
                throw new IOException("GT native setter changed retained assets, ownership or network binding");
        }
    }

    static boolean matches(TileEntity target, EntityPlayer player, NBTTagCompound payload) throws IOException {
        if (payload == null || !payload.hasKey(TAG)) return true;
        if (!(target instanceof IGregTechTileEntity gt) || gt.getMetaTileEntity() == null) return false;
        IMetaTileEntity machine = gt.getMetaTileEntity();
        NBTTagCompound config = envelope(payload, gt.getMetaTileID());
        NBTTagCompound settings = config.getCompoundTag("settings");
        validateSettings(machine, settings);
        for (String key : new ArrayList<String>(settings.func_150296_c())) {
            boolean equal = switch (key) {
                case "machineMode" -> ((IControllerWithOptionalFeatures) machine).getMachineMode()
                    == settings.getInteger(key);
                case "inputSeparation" -> ((IControllerWithOptionalFeatures) machine).isInputSeparationEnabled()
                    == settings.getBoolean(key);
                case "batchMode" -> ((IControllerWithOptionalFeatures) machine).isBatchModeEnabled()
                    == settings.getBoolean(key);
                case "voidingMode" -> ((IVoidable) machine).getVoidingMode() == mode(settings.getString(key));
                case "mLockedToSingleRecipe" -> ((IRecipeLockable) machine).isRecipeLockingEnabled()
                    == settings.getBoolean(key);
                case "powerPanelMaxParallel" -> ((MTEMultiBlockBase) machine).getPowerPanelMaxParallel()
                    == settings.getInteger(key);
                case "alwaysMaxParallel" -> ((MTEMultiBlockBase) machine).isAlwaysMaxParallel()
                    == settings.getBoolean(key);
                case "makePowerfailEvents" -> ((MTEMultiBlockBase) machine).makesPowerfailEvents()
                    == settings.getBoolean(key);
                default -> false;
            };
            if (!equal) return false;
        }
        if (config.hasKey("copied")) {
            if (player == null || !(machine instanceof IDataCopyable copyable)) return false;
            return copied(config, copyable, player).equals(copyable.getCopiedData(player));
        }
        return true;
    }

    private static NBTTagCompound header(int id) {
        NBTTagCompound result = new NBTTagCompound();
        result.setInteger("version", VERSION);
        result.setInteger("mID", id);
        return result;
    }

    private static NBTTagCompound envelope(NBTTagCompound payload, int id) throws IOException {
        typed(payload, TAG, 10);
        NBTTagCompound config = (NBTTagCompound) payload.getCompoundTag(TAG)
            .copy();
        typed(config, "version", 3);
        typed(config, "mID", 3);
        if (config.getInteger("version") != VERSION || config.getInteger("mID") != id)
            throw new IOException("GT native configuration version or machine identity differs");
        for (String key : new ArrayList<String>(config.func_150296_c())) {
            if (!key.equals("version") && !key.equals("mID") && !key.equals("settings") && !key.equals("copied"))
                throw new IOException("Unknown GT native configuration envelope: " + key);
            if (key.equals("settings") || key.equals("copied")) typed(config, key, 10);
        }
        if (config.hasKey("copied")) {
            typed(config.getCompoundTag("copied"), "type", 8);
            if (config.getCompoundTag("copied")
                .getString("type")
                .isEmpty()) throw new IOException("Empty GT native copied type");
        }
        return config;
    }

    private static void validateSettings(IMetaTileEntity machine, NBTTagCompound settings) throws IOException {
        for (String key : new ArrayList<String>(settings.func_150296_c())) {
            if (!SETTINGS.contains(key)) throw new IOException("Unknown GT native setting: " + key);
            boolean supported;
            switch (key) {
                case "machineMode" -> {
                    typed(settings, key, 3);
                    supported = settings.getInteger(key) >= 0 && machine instanceof IControllerWithOptionalFeatures f
                        && f.supportsMachineModeSwitch();
                }
                case "inputSeparation" -> {
                    bool(settings, key);
                    supported = machine instanceof IControllerWithOptionalFeatures f
                        && (f.supportsInputSeparation() || !settings.getBoolean(key));
                }
                case "batchMode" -> {
                    bool(settings, key);
                    supported = machine instanceof IControllerWithOptionalFeatures f
                        && (f.supportsBatchMode() || !settings.getBoolean(key));
                }
                case "voidingMode" -> {
                    typed(settings, key, 8);
                    VoidingMode value = mode(settings.getString(key));
                    supported = machine instanceof IVoidable v && v.getAllowedVoidingModes()
                        .contains(value) && (v.supportsVoidProtection() || value == v.getDefaultVoidingMode());
                }
                case "mLockedToSingleRecipe" -> {
                    bool(settings, key);
                    supported = machine instanceof IRecipeLockable l
                        && (l.supportsSingleRecipeLocking() || !settings.getBoolean(key));
                }
                case "powerPanelMaxParallel" -> {
                    typed(settings, key, 3);
                    supported = machine instanceof MTEMultiBlockBase multi && settings.getInteger(key) >= 1
                        && (multi.supportsPowerPanel() || settings.getInteger(key) == multi.getPowerPanelMaxParallel());
                }
                default -> {
                    bool(settings, key);
                    supported = machine instanceof MTEMultiBlockBase;
                }
            }
            if (!supported) throw new IOException("GT target does not support selected native setting: " + key);
        }
    }

    private static NBTTagCompound copied(NBTTagCompound config, IDataCopyable copyable, EntityPlayer player)
        throws IOException {
        NBTTagCompound result = HologramBlueprintNbt.nativePayload(config.getCompoundTag("copied"));
        if (!result.getString("type")
            .equals(copyable.getCopiedDataIdentifier(player)))
            throw new IOException("GT native copied configuration type differs from target");
        return result;
    }

    private static VoidingMode mode(String name) throws IOException {
        for (VoidingMode value : VoidingMode.values()) if (value.name.equals(name)) return value;
        throw new IOException("Unknown GT voiding mode");
    }

    private static IGregTechTileEntity target(TileEntity tile) throws IOException {
        if (!(tile instanceof IGregTechTileEntity gt) || gt.getMetaTileEntity() == null)
            throw new IOException("GT native configuration target is missing");
        return gt;
    }

    static boolean bindingField(String key) {
        return HologramNetworkBindings.descriptorKey(key) || BINDINGS.contains(key)
            || HologramNativeHubData.bindingKey(key);
    }

    private static NBTTagCompound binding(NBTTagCompound state) {
        NBTTagCompound result = new NBTTagCompound();
        for (String key : new ArrayList<String>(state.func_150296_c())) if (bindingField(key)) result.setTag(
            key,
            state.getTag(key)
                .copy());
        return result;
    }

    private static boolean sameAssets(HologramBlueprintResources.Plan a, HologramBlueprintResources.Plan b) {
        if (!a.energy.equals(b.energy) || a.items.size() != b.items.size()
            || a.fluids.size() != b.fluids.size()
            || a.essentia.size() != b.essentia.size()) return false;
        for (ItemStack item : a.items) if (b.items.stream()
            .noneMatch(
                other -> item.stackSize == other.stackSize && item.isItemEqual(other)
                    && ItemStack.areItemStackTagsEqual(item, other)))
            return false;
        for (HologramBlueprintResources.FluidCost fluid : a.fluids) if (b.fluids.stream()
            .noneMatch(other -> fluid.amount == other.amount && fluid.stack.isFluidEqual(other.stack))) return false;
        for (HologramEssentia.Cost cost : a.essentia) if (b.essentia.stream()
            .noneMatch(other -> cost.amount == other.amount && cost.aspect.equals(other.aspect))) return false;
        return true;
    }

    private static NBTTagCompound physicalInventory(IMetaTileEntity machine) throws IOException {
        ItemStack[] slots = machine.getRealInventory();
        if (slots == null || slots.length > 65536) throw new IOException("GT native real inventory is unavailable");
        NBTTagCompound result = new NBTTagCompound();
        for (int slot = 0; slot < slots.length; slot++) if (slots[slot] != null && slots[slot].stackSize > 0) {
            NBTTagCompound row = slots[slot].writeToNBT(new NBTTagCompound());
            row.setInteger("Count", slots[slot].stackSize);
            result.setTag(Integer.toString(slot), row);
        }
        return result;
    }

    private static void bool(NBTTagCompound tag, String key) throws IOException {
        typed(tag, key, 1);
        if (tag.getByte(key) != 0 && tag.getByte(key) != 1) throw new IOException("Invalid GT native boolean: " + key);
    }

    private static void typed(NBTTagCompound tag, String key, int type) throws IOException {
        if (!tag.hasKey(key, type)) throw new IOException("Invalid GT native field type: " + key);
    }
}
