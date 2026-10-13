package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import gregtech.api.GregTechAPI;

/** Explicit device-field projection. Recipe and nested item data are never traversed. */
final class HologramDeidentification {

    static final int VERSION = 1;
    private static final String[][] FIELDS = { {},
        { "owner", "ownerName", "ownerID", "ownerUUID", "mOwnerName", "mOwnerUUID", "owner_uuid", "owner_id",
            "ownerUUIDMost", "ownerUUIDLeast", "mOwnerUUIDMost", "mOwnerUUIDLeast" },
        { "x", "y", "z", "dimension", "dimensionId", "dim", "anchorDim", "anchorX", "anchorY", "anchorZ",
            "QT_AnchorDim", "QT_AnchorX", "QT_AnchorY", "QT_AnchorZ", "QT_Bound" },
        { "frequency", "freq", "channelId", "channelID", "networkId", "networkID", "mFrequency" },
        { "securityKey", "securityId", "securityID" }, { "proxy", "linked" },
        { "coreX", "coreY", "coreZ", "partOfScreen", "screen" },
        { "PortableMonitorPanelId", "cachedEu", "cachedStatus", "samples", "aeChartSamples" }, { "currentTab" },
        { "shutDownReasonID", "shutDownReason", "mWasShutdown" }, { "lastAESampleTick", "mActive" } };
    private static final Set<String> CONFIG_WRAPPERS = new HashSet<>(
        Arrays.asList("gt.covers", "covers", "cover", "mCoverData"));

    private HologramDeidentification() {}

    static int checked(int level) throws IOException {
        if (level < 0 || level > 10) throw new IOException("去标识等级必须为 0 到 10");
        return level;
    }

    static int level(HologramPresets.Preset preset) {
        return preset == null ? 0
            : Math.max(
                preset.deidentificationLevel,
                preset.deidentificationVersion == 0 && preset.aePolicyVersion > 0 ? 1 : 0);
    }

    static HologramPresets.Preset apply(HologramPresets.Preset source, int requested) throws IOException {
        int level = Math.max(checked(requested), level(source));
        HologramPresets.Preset result = HologramPresets.copy(source);
        result.deidentificationLevel = level;
        result.deidentificationVersion = VERSION;
        if (level > 0) result.aeIssues.addAll(HologramNetworkBindings.sanitize(result));
        for (HologramPresets.Cell cell : result.cells) {
            cell.tileNbt = project(cell, cell.tileNbt, level);
            cell.itemNbt = project(cell, cell.itemNbt, level);
        }
        return result;
    }

    private static String project(HologramPresets.Cell cell, String encoded, int level) throws IOException {
        if (encoded == null || encoded.isEmpty() || level == 0) return encoded;
        NBTTagCompound tag = HologramBlueprintNbt.decode(encoded);
        boolean registered = cell.block() != null && cell.block() == GregTechAPI.sBlockMachines
            || HologramAeBlueprint.supports(cell.block(), tag)
            || HologramNetworkBindings.node(cell.block())
            || tag.getString("id")
                .startsWith("gtswn.");
        project(tag, level, registered, false);
        return HologramBlueprintNbt.encodeCanonical(tag);
    }

    /** Exact allowlists on device roots and registered cover/part wrappers, never arbitrary recursive keys. */
    static void project(NBTTagCompound tag, int level) throws IOException {
        project(tag, level, false, false);
    }

    private static void project(NBTTagCompound tag, int level, boolean registered, boolean cover) throws IOException {
        checked(level);
        if (tag == null || level == 0) return;
        // A recipe input/output ItemStack owns its entire tag subtree, even when names resemble identity fields.
        if (tag.hasKey("id", 99) && tag.hasKey("Count", 99) && tag.hasKey("Damage", 99)) return;
        for (Object raw : new ArrayList<>(tag.func_150296_c())) {
            String key = (String) raw;
            for (int index = 1; index <= (registered || cover ? level : Math.min(2, level)); index++)
                for (String field : FIELDS[index]) if (key.equalsIgnoreCase(field)) {
                    if (index == 1) clearActorField(tag, key);
                    else if (!("proxy".equals(field) && tag.hasKey("mID", 99))) tag.removeTag(key);
                }
        }
        for (Object raw : new ArrayList<>(tag.func_150296_c())) {
            String key = (String) raw;
            if (!"gt.covers".equals(key) && !(registered && CONFIG_WRAPPERS.contains(key))
                && !(registered && key.matches("mCoverData[0-5]"))
                && !(cover && ("d".equals(key) || "data".equals(key) || "coverData".equals(key)))) continue;
            NBTBase child = tag.getTag(key);
            if (child instanceof NBTTagCompound) project((NBTTagCompound) child, level, registered, true);
            else if (child instanceof NBTTagList) {
                NBTTagList list = (NBTTagList) child;
                if (list.func_150303_d() == 10) for (int i = 0; i < list.tagCount(); i++)
                    project(list.getCompoundTagAt(i), level, registered, true);
            }
        }
    }

    private static void clearActorField(NBTTagCompound tag, String key) {
        NBTBase value = tag.getTag(key);
        if (value instanceof NBTTagCompound) {
            NBTTagCompound identity = (NBTTagCompound) value;
            for (Object raw : new ArrayList<>(identity.func_150296_c())) {
                String child = (String) raw;
                String name = child.toLowerCase(Locale.ROOT);
                if (name.equals("id") || name.equals("uuid")
                    || name.equals("name")
                    || name.equals("uuidmost")
                    || name.equals("uuidleast")) clearActorField(identity, child);
            }
        } else if (value.getId() == 11) tag.setIntArray(key, new int[tag.getIntArray(key).length]);
        else if (value.getId() == 4) tag.setLong(key, 0);
        else if (value.getId() == 3) tag.setInteger(key, 0);
        else if (value.getId() == 2) tag.setShort(key, (short) 0);
        else if (value.getId() == 1) tag.setByte(key, (byte) 0);
        else tag.setString(key, "");
    }

    static boolean foreignOwner(NBTTagCompound tag, UUID actor, String actorName) {
        return foreignOwner(tag, actor, actorName, false, false);
    }

    private static boolean foreignOwner(NBTTagCompound tag, UUID actor, String actorName, boolean actorContext,
        boolean coverContext) {
        if (tag == null || tag.hasKey("id", 99) && tag.hasKey("Count", 99) && tag.hasKey("Damage", 99)) return false;
        for (Object raw : tag.func_150296_c()) {
            String key = (String) raw;
            String normalized = key.toLowerCase(Locale.ROOT);
            boolean owner = actorContext && (normalized.equals("id") || normalized.equals("uuid")
                || normalized.equals("name")
                || normalized.equals("uuidmost")
                || normalized.equals("uuidleast"));
            for (String field : FIELDS[1]) if (key.equalsIgnoreCase(field)) owner = true;
            if (owner && tag.getTag(key) instanceof NBTTagCompound
                && foreignOwner(tag.getCompoundTag(key), actor, actorName, true, coverContext)) return true;
            if (owner && tag.hasKey(key, 8)) {
                String value = tag.getString(key);
                if (!value.isEmpty() && !value.equals("00000000-0000-0000-0000-000000000000")
                    && !value.equalsIgnoreCase(actor.toString())
                    && !value.equalsIgnoreCase(actorName)) return true;
            } else if (owner && normalized.endsWith("most")
                && tag.getLong(key) != 0
                && tag.getLong(key) != actor.getMostSignificantBits()) return true;
            else if (owner && normalized.endsWith("least")
                && tag.getLong(key) != 0
                && tag.getLong(key) != actor.getLeastSignificantBits()) return true;
            else if (owner && tag.hasKey(key, 11)) {
                int[] id = tag.getIntArray(key);
                int[] expected = { (int) (actor.getMostSignificantBits() >>> 32), (int) actor.getMostSignificantBits(),
                    (int) (actor.getLeastSignificantBits() >>> 32), (int) actor.getLeastSignificantBits() };
                if (id.length != 4 || !Arrays.equals(id, new int[4]) && !Arrays.equals(id, expected)) return true;
            }
            if (!CONFIG_WRAPPERS.contains(key) && !key.matches("mCoverData[0-5]")
                && !(coverContext && (key.equals("d") || key.equals("data") || key.equals("coverData")))) continue;
            NBTBase child = tag.getTag(key);
            if (child instanceof NBTTagCompound && foreignOwner((NBTTagCompound) child, actor, actorName, false, true))
                return true;
            if (child instanceof NBTTagList && ((NBTTagList) child).func_150303_d() == 10) {
                NBTTagList list = (NBTTagList) child;
                for (int i = 0; i < list.tagCount(); i++)
                    if (foreignOwner(list.getCompoundTagAt(i), actor, actorName, false, true)) return true;
            }
        }
        return false;
    }
}
