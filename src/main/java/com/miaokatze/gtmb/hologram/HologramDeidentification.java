package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
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
    private static final Set<String> CARPENTER_TILES = new HashSet<>(
        Arrays.asList(
            "TileEntityCarpentersSlope",
            "TileEntityCarpentersBed",
            "TileEntityCarpentersBlock",
            "TileEntityCarpentersExt",
            "TileEntityCarpentersDaylightSensor",
            "TileEntityCarpentersFlowerPot",
            "TileEntityCarpentersSafe",
            "TileEntityCarpentersTorch",
            "TileEntityCarpentersGarageDoor"));

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
        if (carpenterOwner(cell.block(), tag)) tag.setString("cbOwner", "");
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
        if (!cover && carpenterOwner(tag)) tag.setString("cbOwner", "");
        for (Object raw : new ArrayList<>(tag.func_150296_c())) {
            String key = (String) raw;
            for (int index = 1; index <= (registered || cover ? level : Math.min(2, level)); index++)
                for (String field : FIELDS[index]) if (key.equalsIgnoreCase(field)) {
                    // An explicitly reviewed relationship owns its tuple; later canonical passes retain that decision.
                    if (tag.hasKey(HologramCoordinatePolicies.RELATIONS, 9) && (index == 2 || index == 6)) continue;
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

    static boolean foreignOwner(Block block, NBTTagCompound tag, UUID actor, String actorName) {
        if (carpenterOwner(block, tag)) {
            String owner = tag.getString("cbOwner");
            if (!owner.isEmpty() && !owner.equals("00000000-0000-0000-0000-000000000000")
                && !owner.equalsIgnoreCase(actor.toString())
                && !owner.equalsIgnoreCase(actorName)) return true;
        }
        return foreignOwner(tag, actor, actorName);
    }

    /** TEBase 3.7.3's exact registered root IDs and string field; never arbitrary or nested cbOwner data. */
    static boolean carpenterOwner(NBTTagCompound tag) {
        return tag != null && CARPENTER_TILES.contains(tag.getString("id")) && tag.hasKey("cbOwner", 8);
    }

    static boolean carpenterOwner(Block block, NBTTagCompound tag) {
        return tag != null && tag.hasKey("cbOwner", 8) && (carpenterBlock(block) || carpenterOwner(tag));
    }

    /** The exact audited native base contract, rather than a class-name package prefix. */
    static boolean carpenterBlock(Block block) {
        for (Class<?> type = block == null ? null : block.getClass(); type != null; type = type.getSuperclass())
            if (type.getName()
                .equals("com.carpentersblocks.block.BlockCoverable")) return true;
        return false;
    }

    /** Reports only the fields understood by the projection; source values never become hint text. */
    static Map<Integer, List<String>> changedFields(HologramPresets.Cell cell, NBTTagCompound before,
        NBTTagCompound after, String path) {
        Map<Integer, Set<String>> fields = new java.util.TreeMap<>();
        collectFields(cell, before, after, path, registered(cell, before), false, false, fields);
        return fieldLists(fields);
    }

    static Map<Integer, List<String>> retainedRiskFields(HologramPresets.Cell cell, NBTTagCompound tag, String path) {
        Map<Integer, Set<String>> fields = new java.util.TreeMap<>();
        collectFields(cell, tag, tag, path, registered(cell, tag), false, true, fields);
        return fieldLists(fields);
    }

    private static boolean registered(HologramPresets.Cell cell, NBTTagCompound tag) {
        return cell.block() != null && cell.block() == GregTechAPI.sBlockMachines
            || HologramAeBlueprint.supports(cell.block(), tag)
            || HologramNetworkBindings.node(cell.block())
            || tag != null && tag.getString("id")
                .startsWith("gtswn.");
    }

    private static Map<Integer, List<String>> fieldLists(Map<Integer, Set<String>> fields) {
        Map<Integer, List<String>> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, Set<String>> entry : fields.entrySet())
            result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        return result;
    }

    private static void collectFields(HologramPresets.Cell cell, NBTTagCompound before, NBTTagCompound after,
        String path, boolean registered, boolean cover, boolean retained, Map<Integer, Set<String>> fields) {
        if (before == null || before.hasKey("id", 99) && before.hasKey("Count", 99) && before.hasKey("Damage", 99))
            return;
        for (Object raw : before.func_150296_c()) {
            String key = (String) raw;
            int group = !cover && "cbOwner".equals(key) && carpenterOwner(cell.block(), before) ? 1 : 0;
            for (int index = 1; index <= (registered || cover ? 10 : 2); index++)
                for (String field : FIELDS[index]) if (key.equalsIgnoreCase(field)) group = index;
            if (group == 0) continue;
            NBTBase original = before.getTag(key), current = after == null ? null : after.getTag(key);
            boolean changed = !java.util.Objects.equals(original, current);
            if (retained) {
                if (changed || group > 6 || !(group == 1 ? meaningfulActor(original) : meaningful(original))) continue;
                if (group == 2 && (key.equalsIgnoreCase("x") || key.equalsIgnoreCase("y") || key.equalsIgnoreCase("z")))
                    continue; // Native root XYZ is always rebound by placement.
            } else if (!changed) continue;
            fields.computeIfAbsent(group, ignored -> new java.util.TreeSet<>())
                .add(path + "/" + key);
        }
        for (Object raw : before.func_150296_c()) {
            String key = (String) raw;
            if (!"gt.covers".equals(key) && !(registered && CONFIG_WRAPPERS.contains(key))
                && !(registered && key.matches("mCoverData[0-5]"))
                && !(cover && ("d".equals(key) || "data".equals(key) || "coverData".equals(key)))) continue;
            NBTBase child = before.getTag(key), target = after == null ? null : after.getTag(key);
            if (child instanceof NBTTagCompound) collectFields(
                cell,
                (NBTTagCompound) child,
                target instanceof NBTTagCompound ? (NBTTagCompound) target : null,
                path + "/" + key,
                registered,
                true,
                retained,
                fields);
            else if (child instanceof NBTTagList && ((NBTTagList) child).func_150303_d() == 10) {
                NBTTagList list = (NBTTagList) child;
                NBTTagList next = target instanceof NBTTagList && ((NBTTagList) target).func_150303_d() == 10
                    ? (NBTTagList) target
                    : null;
                for (int i = 0; i < list.tagCount(); i++) collectFields(
                    cell,
                    list.getCompoundTagAt(i),
                    next != null && i < next.tagCount() ? next.getCompoundTagAt(i) : null,
                    path + "/" + key + "/#" + i,
                    registered,
                    true,
                    retained,
                    fields);
            }
        }
    }

    private static boolean meaningful(NBTBase value) {
        if (value instanceof net.minecraft.nbt.NBTTagString) {
            String text = ((net.minecraft.nbt.NBTTagString) value).func_150285_a_();
            return !text.isEmpty() && !"00000000-0000-0000-0000-000000000000".equals(text);
        }
        if (value instanceof NBTTagCompound) return !((NBTTagCompound) value).hasNoTags();
        if (value instanceof net.minecraft.nbt.NBTTagIntArray) {
            for (int part : ((net.minecraft.nbt.NBTTagIntArray) value).func_150302_c()) if (part != 0) return true;
            return false;
        }
        return !(value instanceof NBTBase.NBTPrimitive) || ((NBTBase.NBTPrimitive) value).func_150291_c() != 0;
    }

    private static boolean meaningfulActor(NBTBase value) {
        if (!(value instanceof NBTTagCompound)) return meaningful(value);
        NBTTagCompound actor = (NBTTagCompound) value;
        for (Object raw : actor.func_150296_c()) {
            String key = (String) raw;
            String name = key.toLowerCase(Locale.ROOT);
            if ((name.equals("id") || name.equals("uuid")
                || name.equals("name")
                || name.equals("uuidmost")
                || name.equals("uuidleast")) && meaningfulActor(actor.getTag(key))) return true;
        }
        return false;
    }

    private static boolean foreignOwner(NBTTagCompound tag, UUID actor, String actorName, boolean actorContext,
        boolean coverContext) {
        if (tag == null || tag.hasKey("id", 99) && tag.hasKey("Count", 99) && tag.hasKey("Damage", 99)) return false;
        if (!actorContext && !coverContext && carpenterOwner(tag)) {
            String owner = tag.getString("cbOwner");
            if (!owner.isEmpty() && !owner.equals("00000000-0000-0000-0000-000000000000")
                && !owner.equalsIgnoreCase(actor.toString())
                && !owner.equalsIgnoreCase(actorName)) return true;
        }
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
