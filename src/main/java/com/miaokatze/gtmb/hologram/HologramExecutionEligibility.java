// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import gregtech.api.GregTechAPI;
import gregtech.common.blocks.ItemMachines;

/** Checks device operations against registered factories, material identities and the supported GT state fields. */
final class HologramExecutionEligibility {

    private static final Set<String> GT_FIELDS = new HashSet<>(
        Arrays.asList(
            "id",
            "mID",
            "nbtVersion",
            "mFacing",
            "mColor",
            "mConnections",
            "mStrongRedstone",
            "mInputDisabled",
            "mOutputDisabled",
            "mWorks",
            "mTexture",
            "texture",
            "mStoredEnergy",
            "mStoredSteam",
            "gt.covers",
            HologramNetworkBindings.GROUP,
            HologramNetworkBindings.ROLE,
            HologramNetworkBindings.HINT));
    private static final Set<String> GT_CAPTURE_FIELDS = new HashSet<>(
        Arrays.asList(
            "x",
            "y",
            "z",
            "mOwnerName",
            "mOwnerUuid",
            "mOwnerUUID",
            "mOwnerNameChanged",
            "mWasShutdown",
            "shutDownReason",
            "shutDownReasonID",
            "mActive",
            "mRedstone",
            "mLightValue"));

    private HologramExecutionEligibility() {}

    static void validate(Block block, int metadata, ItemStack sample, int facing, boolean controller,
        NBTTagCompound tile, NBTTagCompound item) throws IOException {
        if (block == null || block == Blocks.air || metadata < 0 || metadata > 15 || sample == null)
            throw new IOException("服务器无法识别施工方块或耗材");
        forbidSourceAuthority(tile);
        forbidSourceAuthority(item);
        validateNetwork(block, sample, tile);
        if (item != null && item.hasKey(HologramNetworkBindings.GROUP)) throw new IOException("量子绑定只允许用于设备状态");
        if (sample.getItem() instanceof ItemMachines) {
            if (block != GregTechAPI.sBlockMachines || ItemMachines.getMetaTileEntity(sample) == null
                || HologramBlockTransforms.isPipe(sample) && (controller || facing != -1)
                || !HologramCellPolicy.virtualFrontAllowed(sample, facing)) throw new IOException("GT 设备或施工朝向无效");
            gtState(tile, sample, true, false);
            gtState(item, sample, false, false);
            if (tile != null && tile.hasKey("mFacing") && tile.getInteger("mFacing") != facing)
                throw new IOException("GT 设备配置与施工朝向不一致");
            return;
        }
        if (block == GregTechAPI.sBlockMachines || facing != -1 || controller) throw new IOException("施工设备或朝向无效");
        if (HologramNativePlacement.supported(block, tile)
            && (tile != null || HologramAeBlueprint.supports(block, tile))) {
            HologramCoordinatePolicies.validatePaymentPayload(tile);
            ItemStack exact = sample.copy();
            exact.setTagCompound(HologramBlueprintNbt.nativePayload(item));
            if (!HologramNativePlacement.matchesSample(block, metadata, tile, exact))
                throw new IOException("原生设备配置与实际付款材料不符");
            return;
        }
        if (!(sample.getItem() instanceof ItemBlock) || block.hasTileEntity(metadata)
            || tile != null && !tile.hasNoTags()
            || item != null && !item.hasNoTags()) throw new IOException("该设备没有已适配的施工状态，请调整保留项或手动放置");
        Block paidBlock = Block.getBlockFromItem(sample.getItem());
        if (!HologramBlockIdentity.sameForPlacement(paidBlock, 0, block, 0)
            && !(paidBlock == Blocks.redstone_torch && block == Blocks.unlit_redstone_torch))
            throw new IOException("施工方块与实际付款物品不一致");
        int itemMetadata = sample.getItem()
            .getMetadata(sample.getItemDamage());
        if (itemMetadata < 0 || itemMetadata > 15 || block.hasTileEntity(itemMetadata))
            throw new IOException("实际付款材料的方块状态无效");
        HologramPresets.validateMaterialVariant(block, metadata, sample);
    }

    static NBTTagCompound payload(String encoded) throws IOException {
        NBTTagCompound data = HologramBlueprintNbt.decodeData(encoded);
        return data == null ? null : HologramBlueprintNbt.restorePortableObservation(data);
    }

    static NBTTagCompound gtState(NBTTagCompound tag, ItemStack sample, boolean tile, boolean project)
        throws IOException {
        if (tag == null || tag.hasNoTags()) return null;
        boolean pipe = HologramBlockTransforms.isPipe(sample);
        if (tile || tag.hasKey("id") || tag.hasKey("mID")) HologramPresets
            .validateGtPayload(tag, sample.getItemDamage(), pipe ? "BaseMetaPipeEntity" : "BaseMetaTileEntity");
        for (Object raw : new ArrayList<>(tag.func_150296_c())) {
            String key = (String) raw;
            if (project && (GT_CAPTURE_FIELDS.contains(key) || !GT_FIELDS.contains(key) && empty(tag.getTag(key)))) {
                tag.removeTag(key);
                continue;
            }
            if (!GT_FIELDS.contains(key)) throw new IOException("GT 设备配置尚未适配施工：" + key);
            if (key.equals("mFacing")) {
                if (!smallNumber(tag, key, -1, 5)) throw new IOException("GT 机器朝向无效");
                if (pipe) {
                    if (!project) throw new IOException("管道不使用机器朝向");
                    tag.removeTag(key);
                }
            } else if (key.equals("gt.covers")) {
                if (!tile || !tag.hasKey(key, 9)) throw new IOException("覆盖板只能随设备状态放置");
            } else if (key.equals("mConnections") || key.equals("mStrongRedstone")) {
                if (!smallNumber(tag, key, -128, 255)) throw new IOException("GT 连接状态无效");
            } else if (key.equals("mInputDisabled") || key.equals("mOutputDisabled") || key.equals("mWorks")) {
                if (!smallNumber(tag, key, 0, 1)) throw new IOException("GT 输入输出设置无效");
            } else if (key.equals("mStoredEnergy") || key.equals("mStoredSteam")) {
                if (!tile || !tag.hasKey(key, 4) || tag.getLong(key) < 0) throw new IOException("GT 设备缓存格式无效");
            } else if (key.equals("nbtVersion")) {
                if (!tag.hasKey(key, 3) || tag.getInteger(key) < 0) throw new IOException("GT 设备版本字段无效");
            } else if (key.equals("mTexture") || key.equals("texture") || key.equals("mColor")) {
                if (!smallNumber(tag, key, -128, 255)) throw new IOException("GT 颜色或外观设置无效");
            }
        }
        return tag;
    }

    private static boolean smallNumber(NBTTagCompound tag, String key, int min, int max) {
        return tag.hasKey(key, 99) && tag.getTag(key)
            .getId() <= 4 && tag.getLong(key) >= min && tag.getLong(key) <= max;
    }

    private static boolean empty(NBTBase tag) {
        if (tag instanceof NBTTagList list) return list.tagCount() == 0;
        if (tag instanceof NBTTagCompound compound) return compound.hasNoTags();
        return tag instanceof NBTBase.NBTPrimitive number && number.func_150286_g() == 0;
    }

    private static void forbidSourceAuthority(NBTTagCompound tag) throws IOException {
        if (tag == null) return;
        if (tag.hasKey(HologramRecovery.TAG) || tag.hasKey(HologramFullPreservation.TAG)
            || tag.hasKey(HologramNetworkBindings.ORIGINAL)
            || tag.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR)
            || tag.hasKey(HologramNetworkBindings.MODE)) throw new IOException("施工指令不能携带原世界的绑定或设备恢复凭据");
    }

    private static void validateNetwork(Block block, ItemStack sample, NBTTagCompound tile) throws IOException {
        boolean node = HologramNetworkBindings.node(block);
        if (tile == null || !tile.hasKey(HologramNetworkBindings.GROUP)) {
            if (node) throw new IOException("量子节点缺少本地组，请先处理蓝图网络设置");
            return;
        }
        HologramNetworkBindings.group(tile);
        if (node != "node".equals(tile.getString(HologramNetworkBindings.ROLE))
            || !node && !(sample.getItem() instanceof ItemMachines) && !HologramAeBlueprint.supports(block, tile))
            throw new IOException("当前设备不支持提交的量子绑定用途");
    }
}
