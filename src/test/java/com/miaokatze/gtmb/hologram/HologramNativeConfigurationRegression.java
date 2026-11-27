// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.ForgeDirection;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

import gregtech.api.GregTechAPI;
import gregtech.api.interfaces.IDataCopyable;
import gregtech.api.interfaces.ITexture;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import gregtech.common.blocks.ItemMachines;

/** Exact native feature/copy contracts. No instance launch, live network or paid-placement claim. */
public final class HologramNativeConfigurationRegression {

    private static final int ID = 32664;

    public static void main(String[] args) throws Exception {
        int status = 0;
        try {
            if (!MinecraftTestBootstrap.enter(HologramNativeConfigurationRegression.class, args)) return;
            run();
            System.out
                .println("PASS native GT configuration: legacy projection, selection, copy readback and asset fences");
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            status = 1;
        }
        System.exit(status);
    }

    static void run() throws Exception {
        HologramNativeSavedWorldRegression.withOfflineRegistries(() -> {
            try {
                verify();
            } catch (Exception failure) {
                throw new AssertionError("native configuration regression", failure);
            }
        });
    }

    private static void verify() throws Exception {
        Block oldBlock = GregTechAPI.sBlockMachines;
        IMetaTileEntity oldMachine = GregTechAPI.METATILEENTITIES[ID];
        Block host = new Block(Material.iron) {};
        Block.blockRegistry.addObject(4070, "gtmb:native_configuration_fixture", host);
        Item.itemRegistry.addObject(4070, "gtmb:native_configuration_fixture", new ItemMachines(host));
        GregTechAPI.sBlockMachines = host;
        GregTechAPI.METATILEENTITIES[ID] = new Controller();
        ItemStack sample = new ItemStack(host, 1, ID);
        try {
            NBTTagCompound legacy = legacy();
            NBTTagCompound projected = HologramExecutionEligibility
                .gtState((NBTTagCompound) legacy.copy(), sample, true, true);
            SimpleAssert.that(
                legacy.hasKey("mEUt") && legacy.hasKey("powerPanelMaxParallel"),
                "source persistence stays intact");
            SimpleAssert.that(
                !projected.hasKey("mEUt") && !projected.hasKey("checkRecipeResultID")
                    && !projected.hasKey("checkRecipeResult")
                    && !projected.hasKey("mProgresstime"),
                "runtime observation resets together");
            SimpleAssert.eq(
                16,
                projected.getCompoundTag(HologramNativeConfiguration.TAG)
                    .getCompoundTag("settings")
                    .getInteger("powerPanelMaxParallel"),
                "parallel selection is configuration, not runtime debris");
            reject(() -> {
                NBTTagCompound invalid = legacy();
                invalid.setString("mEUt", "160");
                HologramExecutionEligibility.gtState(invalid, sample, true, true);
            }, "malformed runtime type");
            reject(() -> {
                NBTTagCompound invalid = legacy();
                invalid.setInteger("unknownPrivateState", 1);
                HologramExecutionEligibility.gtState(invalid, sample, true, true);
            }, "unknown selected state");
            reject(() -> {
                NBTTagCompound invalid = legacy();
                invalid.setInteger("machineMode", 99);
                HologramExecutionEligibility.gtState(invalid, sample, true, true);
            }, "native mode domain");

            Tile tile = tile();
            Controller machine = (Controller) tile.getMetaTileEntity();
            HologramNativeConfiguration.apply(tile, null, projected);
            SimpleAssert.eq(16, machine.getPowerPanelMaxParallel(), "native parallel setter");
            SimpleAssert.that(HologramNativeConfiguration.matches(tile, null, projected), "native settings read back");
            machine.setPowerPanelMaxParallel(8);
            SimpleAssert.that(
                !HologramNativeConfiguration.matchesRetained(tile, null, sample, projected),
                "different selected parallel is unsatisfied");
            NBTTagCompound higherCeiling = (NBTTagCompound) projected.copy();
            higherCeiling.getCompoundTag(HologramNativeConfiguration.TAG)
                .getCompoundTag("settings")
                .setInteger("powerPanelMaxParallel", 65);
            HologramNativeConfiguration.apply(tile, null, higherCeiling);
            SimpleAssert
                .eq(65, machine.getPowerPanelMaxParallel(), "native ceiling survives a smaller current structure");
            SimpleAssert.eq(64, machine.getTrueParallel(), "native effective parallelism remains capacity-clamped");
            machine.setPowerPanelMaxParallel(8);
            NBTTagCompound wrongId = (NBTTagCompound) projected.copy();
            wrongId.getCompoundTag(HologramNativeConfiguration.TAG)
                .setInteger("mID", ID - 1);
            reject(() -> HologramNativeConfiguration.validate(wrongId, sample), "envelope machine binding");

            NBTTagCompound captured = legacy();
            captured.setInteger("powerPanelMaxParallel", 8);
            HologramNativeConfiguration.capture(tile, null, captured);
            NBTTagCompound selected = HologramBlueprintNbt
                .filterSelection(captured, Arrays.asList("powerPanelMaxParallel"), false);
            HologramExecutionEligibility.gtState(selected, sample, true, true);
            SimpleAssert.eq(
                8,
                selected.getCompoundTag(HologramNativeConfiguration.TAG)
                    .getCompoundTag("settings")
                    .getInteger("powerPanelMaxParallel"),
                "old root selection keeps corresponding envelope setting");
            SimpleAssert.that(
                !selected.getCompoundTag(HologramNativeConfiguration.TAG)
                    .getCompoundTag("settings")
                    .hasKey("batchMode"),
                "unselected sibling configuration does not reappear");
            NBTTagCompound none = HologramBlueprintNbt.filterSelection(captured, Collections.emptyList(), false);
            SimpleAssert.that(!none.hasKey(HologramNativeConfiguration.TAG), "deselected configuration is omitted");
            SimpleAssert.that(
                !HologramBlueprintNbt.nativePayload(captured)
                    .hasKey(HologramNativeConfiguration.TAG),
                "opaque template never enters raw native load");

            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            EntityPlayer player = (EntityPlayer) ((sun.misc.Unsafe) singleton.get(null))
                .allocateInstance(EntityPlayerMP.class);
            machine.copiedValue = 7;
            NBTTagCompound nativeCopy = identity();
            HologramNativeConfiguration.capture(tile, player, nativeCopy);
            machine.copiedValue = 0;
            HologramNativeConfiguration.apply(tile, player, nativeCopy);
            SimpleAssert.eq(7, machine.copiedValue, "native IDataCopyable paste");
            SimpleAssert
                .that(HologramNativeConfiguration.matches(tile, player, nativeCopy), "native copied payload read back");
            machine.copyMode = 1;
            reject(() -> HologramNativeConfiguration.apply(tile, player, nativeCopy), "native copy rejection");
            machine.copyMode = 2;
            machine.copiedValue = 0;
            reject(() -> HologramNativeConfiguration.apply(tile, player, nativeCopy), "native partial application");
            machine.copyMode = 3;
            reject(
                () -> HologramNativeConfiguration.apply(tile, player, nativeCopy),
                "copy cannot create physical inventory");
            machine.mInventory[0] = null; // This fixture has no transaction; production callers own rollback.
            machine.copyMode = 4;
            NBTTagCompound unavailable = identity();
            HologramNativeConfiguration.capture(tile, player, unavailable);
            SimpleAssert.that(
                !unavailable.getCompoundTag(HologramNativeConfiguration.TAG)
                    .hasKey("copied"),
                "null native copy stays unavailable");

            NBTTagCompound selectedColor = identity();
            selectedColor.setByte("mColor", (byte) 4);
            SimpleAssert.that(
                !HologramNativeConfiguration.matchesRetained(tile, player, sample, selectedColor),
                "selected retained scalar is compared");
            selectedColor.setByte("mColor", (byte) 0);
            SimpleAssert.that(
                HologramNativeConfiguration.matchesRetained(tile, player, sample, selectedColor),
                "unselected runtime progress does not spoil equality");
            NBTTagCompound optionalBuffer = identity();
            optionalBuffer.setLong("mStoredEnergy", 512L);
            SimpleAssert.that(
                !HologramNativeConfiguration.matchesRetained(tile, player, sample, optionalBuffer),
                "selected exact buffer is compared");
            SimpleAssert.that(
                HologramNativeConfiguration.matchesRetained(tile, player, sample, optionalBuffer, false),
                "optional buffer does not spoil pre-satisfaction");

            net.minecraft.tileentity.TileEntityChest chest = new net.minecraft.tileentity.TileEntityChest();
            NBTTagCompound chestData = new NBTTagCompound();
            chestData.setString("id", "Chest");
            chestData.setString("CustomName", "retained chest");
            NBTTagList contents = new NBTTagList();
            NBTTagCompound stack = new ItemStack(Blocks.stone, 3).writeToNBT(new NBTTagCompound());
            stack.setByte("Slot", (byte) 0);
            contents.appendTag(stack);
            chestData.setTag("Items", contents);
            chest.readFromNBT(chestData);
            SimpleAssert.that(
                HologramNativeConfiguration.matchesRetained(chest, player, new ItemStack(Blocks.chest), chestData),
                "ordinary retained TE comparison uses selected native fields");
            chest.setInventorySlotContents(0, new ItemStack(Blocks.stone, 2));
            SimpleAssert.that(
                !HologramNativeConfiguration.matchesRetained(chest, player, new ItemStack(Blocks.chest), chestData),
                "ordinary retained inventory differences remain unsatisfied");
            SimpleAssert.that(
                HologramNativeConfiguration.matchesRetained(null, player, new ItemStack(Blocks.stone), null),
                "ordinary non-TE targets need no retained tile payload");
            NBTTagCompound appearance = identity();
            appearance.setInteger("texturePage", 0);
            appearance.setInteger("textureIndex", 0);
            reject(
                () -> HologramExecutionEligibility.gtState(appearance, sample, true, true),
                "texture pair on a non-hatch");
            IMetaTileEntity controller = GregTechAPI.METATILEENTITIES[ID];
            gregtech.api.interfaces.ITexture[][] textures = gregtech.api.enums.Textures.BlockIcons.casingTexturePages;
            try {
                GregTechAPI.METATILEENTITIES[ID] = (IMetaTileEntity) ((sun.misc.Unsafe) singleton.get(null))
                    .allocateInstance(gregtech.api.metatileentity.implementations.MTEHatchEnergy.class);
                HologramExecutionEligibility.gtState(appearance, sample, true, true);
                gregtech.api.enums.Textures.BlockIcons.casingTexturePages = new ITexture[1][128];
                gregtech.api.enums.Textures.BlockIcons.casingTexturePages[0][1] = (ITexture) java.lang.reflect.Proxy
                    .newProxyInstance(
                        ITexture.class.getClassLoader(),
                        new Class<?>[] { ITexture.class },
                        (proxy, method, arguments) -> null);
                appearance.setInteger("textureIndex", 1);
                HologramExecutionEligibility.gtState(appearance, sample, true, true);
                appearance.setInteger("textureIndex", 128);
                reject(
                    () -> HologramExecutionEligibility.gtState(appearance, sample, true, true),
                    "texture index outside native seven-bit domain");
                appearance.setInteger("textureIndex", 2);
                reject(
                    () -> HologramExecutionEligibility.gtState(appearance, sample, true, true),
                    "unregistered casing texture");
            } finally {
                GregTechAPI.METATILEENTITIES[ID] = controller;
                gregtech.api.enums.Textures.BlockIcons.casingTexturePages = textures;
            }
            geometryContracts(sample, (EntityPlayerMP) player);
        } finally {
            GregTechAPI.sBlockMachines = oldBlock;
            GregTechAPI.METATILEENTITIES[ID] = oldMachine;
        }
    }

    private static void geometryContracts(ItemStack sample, EntityPlayerMP player) throws Exception {
        GregTechAPI.METATILEENTITIES[ID] = new AlignedController();
        NBTTagCompound raw = identity();
        raw.setShort("mFacing", (short) ForgeDirection.NORTH.ordinal());
        raw.setByte("eRotation", (byte) 1);
        raw.setByte("eFlip", (byte) 1);
        var wanted = com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.of(
            ForgeDirection.NORTH,
            com.gtnewhorizon.structurelib.alignment.enumerable.Rotation.CLOCKWISE,
            com.gtnewhorizon.structurelib.alignment.enumerable.Flip.HORIZONTAL);
        SimpleAssert
            .eq(wanted, HologramNativeConstruction.geometry(sample, 2, raw), "raw alignment is explicit geometry");
        NBTTagCompound turned = (NBTTagCompound) raw.copy();
        HologramBlockTransformAdapters.gregtech(turned, true);
        SimpleAssert.eq(
            HologramVirtualTransform.alignment(wanted, true),
            HologramNativeConstruction.geometry(sample, turned.getInteger("mFacing"), turned),
            "rotated raw descriptor retains all alignment axes");
        NBTTagCompound invalid = (NBTTagCompound) raw.copy();
        invalid.setByte("eFlip", (byte) 4);
        reject(() -> HologramNativeConstruction.geometry(sample, 2, invalid), "invalid native flip index");
        Tile tile = tile();
        player.worldObj = tile.getWorldObj();
        player.inventory = new net.minecraft.entity.player.InventoryPlayer(player);
        java.util.UUID id = java.util.UUID.randomUUID();
        for (Field field : EntityPlayer.class.getDeclaredFields())
            if (field.getType() == com.mojang.authlib.GameProfile.class) {
                field.setAccessible(true);
                field.set(player, new com.mojang.authlib.GameProfile(id, "NativeAlignmentFixture"));
            }
        for (Field field : net.minecraft.entity.Entity.class.getDeclaredFields())
            if (field.getType() == java.util.UUID.class) {
                field.setAccessible(true);
                field.set(player, id);
            }
        HologramSession session = new HologramSession(player, 0, 128, 0, false, 1, false);
        session.virtualPreset = new HologramPresets.Preset();
        session.virtualPreset.retainNbt = false;
        session.virtualPreset.retainCovers = false;
        var saved = HologramNativeConstruction
            .saved(session, GregTechAPI.sBlockMachines, 0, sample, 2, HologramBlueprintNbt.encodeCaptured(raw), "");
        SimpleAssert.eq(wanted, saved.alignment, "retain-NBT off does not erase target geometry");
        SimpleAssert.eq(null, saved.buffers.tileEmpty, "retained data remains disabled independently of geometry");
        AlignedController live = (AlignedController) tile.getMetaTileEntity();
        SimpleAssert.that(
            !HologramNativeConstruction.alignmentMatches(tile, saved.alignment),
            "same front with different rotation/flip is unsatisfied");
        HologramNativeConstruction.applyGeometry(saved, tile);
        SimpleAssert
            .that(HologramNativeConstruction.alignmentMatches(tile, wanted), "native alignment setter is read back");
        java.lang.reflect.Method nativeState = HologramNativeConstruction.class.getDeclaredMethod(
            "applyNativeState",
            HologramSession.class,
            HologramNativeConstruction.Prepared.class,
            net.minecraft.tileentity.TileEntity.class,
            int.class,
            int.class,
            int.class,
            boolean.class);
        nativeState.setAccessible(true);
        int captures = tile.captures;
        nativeState.invoke(null, session, saved, tile, 0, 128, 0, false);
        SimpleAssert.eq(captures, tile.captures, "no-op geometry adds no full-TE asset scans");
        live.reject = true;
        reject(() -> HologramNativeConstruction.applyGeometry(saved, tile), "live native alignment limits");
        live.reject = false;
        live.value = com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing.of(ForgeDirection.NORTH);
        live.partial = true;
        reject(() -> HologramNativeConstruction.applyGeometry(saved, tile), "partial alignment setter");
        live.partial = false;
        live.mutateAsset = true;
        try {
            nativeState.invoke(null, session, saved, tile, 0, 128, 0, false);
            throw new AssertionError("alignment setter created physical assets");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            SimpleAssert
                .that(expected.getCause() instanceof IOException, "native geometry shares the protected-state guard");
        }
        live.mInventory[0] = null;
        live.mutateAsset = false;
        var cache = new HologramNativeConstruction.ProjectionCache();
        var first = new HologramNativeTargetPlan.Cell(
            0,
            0,
            0,
            GregTechAPI.sBlockMachines,
            0,
            sample,
            2,
            null,
            raw,
            null,
            null,
            false);
        NBTTagCompound other = (NBTTagCompound) raw.copy();
        other.setByte("eFlip", (byte) 0);
        var second = new HologramNativeTargetPlan.Cell(
            1,
            0,
            0,
            GregTechAPI.sBlockMachines,
            0,
            sample,
            2,
            null,
            other,
            null,
            null,
            false);
        SimpleAssert.that(cache.get(first) == cache.get(first), "identical alignment reuses prepared projection");
        SimpleAssert.that(cache.get(first) != cache.get(second), "different flips do not share a prepared target");
    }

    private static NBTTagCompound identity() {
        NBTTagCompound result = new NBTTagCompound();
        result.setString("id", "BaseMetaTileEntity");
        result.setInteger("mID", ID);
        return result;
    }

    private static NBTTagCompound legacy() {
        NBTTagCompound result = identity();
        result.setInteger("mEUt", -128);
        result.setInteger("mProgresstime", 43);
        result.setInteger("mMaxProgresstime", 100);
        result.setString("checkRecipeResultID", "success");
        result.setTag("checkRecipeResult", new NBTTagCompound());
        result.setInteger("powerPanelMaxParallel", 16);
        result.setBoolean("alwaysMaxParallel", false);
        result.setBoolean("inputSeparation", true);
        result.setBoolean("batchMode", false);
        result.setBoolean("mLockedToSingleRecipe", false);
        // IVoidable's native default is VOID_ALL when void protection is unsupported.
        result.setString("voidingMode", "all");
        return result;
    }

    private static Tile tile() {
        Tile tile = new Tile();
        HologramResourceWorld world = HologramResourceWorld.declarations(GregTechAPI.sBlockMachines, 0);
        world.tile = tile;
        tile.setWorldObj(world);
        tile.yCoord = 128;
        tile.setInitialValuesAsNBT(null, (short) ID);
        return tile;
    }

    private interface Checked {

        void run() throws IOException;
    }

    private static void reject(Checked work, String reason) throws IOException {
        try {
            work.run();
        } catch (IOException expected) {
            return;
        }
        throw new AssertionError("accepted " + reason);
    }

    private static final class Tile extends BaseMetaTileEntity {

        int captures;

        @Override
        public void writeToNBT(NBTTagCompound tag) {
            captures++;
            tag.setString("id", "BaseMetaTileEntity");
            tag.setInteger("mID", getMetaTileID());
            tag.setByte("mColor", (byte) 0);
            tag.setLong("mStoredEnergy", getStoredEU());
            tag.setLong("mStoredSteam", getStoredSteam());
            getMetaTileEntity().saveNBTData(tag);
            NBTTagList inventory = new NBTTagList();
            ItemStack[] slots = getMetaTileEntity().getRealInventory();
            for (int i = 0; i < slots.length; i++) if (slots[i] != null) {
                NBTTagCompound row = slots[i].writeToNBT(new NBTTagCompound());
                row.setInteger("IntSlot", i);
                inventory.appendTag(row);
            }
            tag.setTag("Inventory", inventory);
        }
    }

    private static class Controller extends MTEMultiBlockBase implements IDataCopyable {

        int copiedValue, copyMode;

        Controller() {
            super("native_configuration_fixture");
        }

        @Override
        public String[] getDescription() {
            return new String[0];
        }

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity base) {
            return new Controller();
        }

        @Override
        public ITexture[] getTexture(IGregTechTileEntity base, ForgeDirection side, ForgeDirection front, int color,
            boolean active, boolean redstone) {
            return new ITexture[0];
        }

        @Override
        public int getMaxParallelRecipes() {
            return 64;
        }

        @Override
        public boolean supportsInputSeparation() {
            return true;
        }

        @Override
        public boolean supportsBatchMode() {
            return false;
        }

        @Override
        public void setBatchMode(boolean value) {
            throw new AssertionError("unsupported batch setter");
        }

        @Override
        public boolean supportsSingleRecipeLocking() {
            return false;
        }

        @Override
        public void setRecipeLocking(boolean value) {
            throw new AssertionError("unsupported lock setter");
        }

        @Override
        public boolean supportsVoidProtection() {
            return false;
        }

        @Override
        public void setVoidingMode(gregtech.api.enums.VoidingMode value) {
            throw new AssertionError("unsupported void setter");
        }

        @Override
        public boolean supportsMachineModeSwitch() {
            return true;
        }

        @Override
        public String getCopiedDataIdentifier(EntityPlayer player) {
            return "gtmb:native_fixture";
        }

        @Override
        public NBTTagCompound getCopiedData(EntityPlayer player) {
            if (copyMode == 4) return null;
            NBTTagCompound result = new NBTTagCompound();
            result.setString("type", getCopiedDataIdentifier(player));
            result.setInteger("value", copiedValue);
            return result;
        }

        @Override
        public boolean pasteCopiedData(EntityPlayer player, NBTTagCompound data) {
            if (copyMode == 1) return false;
            if (copyMode != 2) copiedValue = data.getInteger("value");
            if (copyMode == 3) mInventory[0] = new ItemStack(Blocks.dirt);
            return true;
        }
    }

    private static final class AlignedController extends Controller
        implements com.gtnewhorizon.structurelib.alignment.IAlignment {

        com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing value = com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing
            .of(ForgeDirection.NORTH);
        boolean reject, partial, mutateAsset;

        @Override
        public IMetaTileEntity newMetaEntity(IGregTechTileEntity base) {
            return new AlignedController();
        }

        @Override
        public com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing getExtendedFacing() {
            return value;
        }

        @Override
        public com.gtnewhorizon.structurelib.alignment.IAlignmentLimits getAlignmentLimits() {
            return (direction, rotation, flip) -> !reject;
        }

        @Override
        public void setExtendedFacing(com.gtnewhorizon.structurelib.alignment.enumerable.ExtendedFacing next) {
            if (!partial) value = next;
            if (mutateAsset) mInventory[0] = new ItemStack(Blocks.dirt);
        }
    }
}
