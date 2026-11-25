// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;

/** Small explicit intent; it has no synthetic client revision, geometry, arbitrary item, or quote. */
public final class HologramCompactNativeRequest {

    static final String FLAG = "nativeCompactPlace";
    /** Distinct action: an old server cannot misinterpret it as an ordinary controller-opening click. */
    static final String ORDINARY_ACTION = "nativeOrdinaryPlace";
    private static final Set<String> FIELDS = Collections.unmodifiableSet(
        new HashSet<>(
            Arrays.asList(
                "action",
                FLAG,
                "placementRequestId",
                "x",
                "y",
                "z",
                "side",
                "slot",
                "controller",
                "facing",
                "main",
                "mainExplicit",
                "channels",
                "noHatches",
                "materialMain",
                "materialContainers",
                "materialMe",
                "materialPriority")));

    private static final Set<String> ORDINARY_FIELDS = ordinaryFields();

    private static Set<String> ordinaryFields() {
        Set<String> fields = new HashSet<>(FIELDS);
        fields.remove(FLAG);
        return Collections.unmodifiableSet(fields);
    }

    static final String HATCH_PRESET_REVIEW_REQUIRED = "当前投影仪有仓室等级信道。请先在仓室预设明确应用目标等级，再核对施工；普通补建不会猜测等级。";

    private HologramCompactNativeRequest() {}

    static boolean needsHatchPresetReview(NBTTagCompound config) {
        return !config.getBoolean("noHatches") && config.getCompoundTag("channels")
            .hasKey("gt_hatch", 3);
    }

    public static boolean enabled() {
        return HologramNativeReadyPlacement.enabled();
    }

    public static NBTTagCompound encode(UUID id, TileEntity controller, int side, int slot, NBTTagCompound state,
        NBTTagCompound confirmed, boolean unchangedCells) throws IOException {
        return encode(id, controller, side, slot, state, confirmed, unchangedCells, false);
    }

    public static NBTTagCompound encodeOrdinary(UUID id, TileEntity controller, int side, int slot,
        NBTTagCompound state, NBTTagCompound confirmed, boolean unchangedCells) throws IOException {
        return encode(id, controller, side, slot, state, confirmed, unchangedCells, true);
    }

    private static NBTTagCompound encode(UUID id, TileEntity controller, int side, int slot, NBTTagCompound state,
        NBTTagCompound confirmed, boolean unchangedCells, boolean ordinary) throws IOException {
        if (!(controller instanceof IGregTechTileEntity machine) || !unchangedCells
            || state.getInteger("mode") != 0
            || confirmed.getInteger("scope") != 0
            || confirmed.getIntArray("selectedIndices").length != 0
            || state.getBoolean("presetActive")
            || !confirmed.getString("structureId")
                .isEmpty())
            throw new IOException("紧凑原生放置暂不支持局部范围、手动仓室位置或仓室预设。");
        if (ordinary && needsHatchPresetReview(confirmed)) throw new IOException(HATCH_PRESET_REVIEW_REQUIRED);
        if (!ordinary && (!confirmed.getBoolean("materialMain") || confirmed.getBoolean("materialContainers")
            || confirmed.getBoolean("materialMe"))) throw new IOException("紧凑原生放置暂仅支持已明确选择的玩家背包材料。");
        NBTTagCompound request = new NBTTagCompound();
        request.setString("action", ordinary ? ORDINARY_ACTION : "useBlock");
        if (!ordinary) request.setBoolean(FLAG, true);
        request.setString("placementRequestId", id.toString());
        request.setInteger("x", controller.xCoord);
        request.setInteger("y", controller.yCoord);
        request.setInteger("z", controller.zCoord);
        request.setInteger("side", side);
        request.setInteger("slot", slot);
        request.setInteger("controller", machine.getMetaTileID());
        request.setInteger("facing", state.getInteger("facing"));
        request.setBoolean("mainExplicit", state.getBoolean("mainExplicit"));
        for (String name : new String[] { "main", "materialPriority", "channels", "noHatches", "materialMain",
            "materialContainers", "materialMe" }) {
            if (!confirmed.hasKey(name)) throw new IOException("原生放置选项未明确确认。");
            request.setTag(
                name,
                confirmed.getTag(name)
                    .copy());
        }
        if (!valid(request)) throw new IOException("原生放置选项格式无效。");
        return request;
    }

    static boolean ordinary(NBTTagCompound request) {
        return ORDINARY_ACTION.equals(request.getString("action"));
    }

    static boolean valid(NBTTagCompound request) {
        boolean ordinary = ordinary(request);
        if (!hasFields(request, ordinary ? ORDINARY_FIELDS : FIELDS)
            || !ordinary && !"useBlock".equals(request.getString("action"))
            || !request.hasKey("channels", 10)) return false;
        for (String key : new String[] { "x", "y", "z", "side", "slot", "controller", "facing", "main",
            "materialPriority" }) if (!request.hasKey(key, 3)) return false;
        for (String key : new String[] { "mainExplicit", "noHatches", "materialMain", "materialContainers",
            "materialMe" }) {
            if (!request.hasKey(key, 1) || request.getByte(key) < 0 || request.getByte(key) > 1) return false;
        }
        return (ordinary ? HologramLocalControllerCapture.validSettings(request)
            : request.hasKey(FLAG, 1) && request.getByte(FLAG) == 1
                && request.getBoolean("materialMain")
                && !request.getBoolean("materialContainers")
                && !request.getBoolean("materialMe"))
            && request.getInteger("side") >= 0
            && request.getInteger("side") < 6
            && request.getInteger("slot") >= 0
            && request.getInteger("slot") < 9
            && request.getInteger("materialPriority") >= 0
            && request.getInteger("materialPriority") < 3;
    }

    static boolean hasFields(NBTTagCompound request, Set<String> expected) {
        Set<?> canonical = request.func_150296_c();
        if (canonical.size() < expected.size() || canonical.size() > expected.size() + 3) return false;
        var fields = new HashSet<>(canonical);
        for (String key : new String[] { "clientSnapshotProtocol", "clientSnapshotPalette", "clientSnapshotFlow" }) {
            if (!fields.remove(key)) continue;
            int version = "clientSnapshotProtocol".equals(key) ? 1
                : "clientSnapshotPalette".equals(key) ? HologramSnapshotPalette.VERSION
                    : HologramSnapshotFlowControl.VERSION;
            if (!request.hasKey(key, 3) || request.getInteger(key) != version) return false;
        }
        return fields.equals(expected);
    }
}
