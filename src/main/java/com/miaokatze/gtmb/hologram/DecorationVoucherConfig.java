package com.miaokatze.gtmb.hologram;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import net.minecraftforge.common.config.Configuration;

/** Server-owned decoration classification. Registry namespaces, never translated display names. */
public final class DecorationVoucherConfig {

    public static final String FILE_NAME = "gtmb-decoration-voucher.cfg";
    private static final String CATEGORY = "decorationVoucher";
    private static final String[] TILE_MATERIALS = { "minecraft:wool", "minecraft:hardened_clay",
        "minecraft:stained_hardened_clay", "etfuturum:concrete", HologramPictureBox.WOOLEN_CLAY };
    private static final String[] AE_QUARTZ_GLASS = { "appliedenergistics2:tile.BlockQuartzGlass@0",
        "appliedenergistics2:tile.BlockQuartzLamp@0" };
    private static final String[] OPENBLOCKS_ELEVATORS = { "OpenBlocks:elevator@0", "OpenBlocks:elevator@1",
        "OpenBlocks:elevator@2", "OpenBlocks:elevator@3", "OpenBlocks:elevator@4", "OpenBlocks:elevator@5",
        "OpenBlocks:elevator@6", "OpenBlocks:elevator@7", "OpenBlocks:elevator@8", "OpenBlocks:elevator@9",
        "OpenBlocks:elevator@10", "OpenBlocks:elevator@11", "OpenBlocks:elevator@12", "OpenBlocks:elevator@13",
        "OpenBlocks:elevator@14", "OpenBlocks:elevator@15", "OpenBlocks:elevator_rotating@0",
        "OpenBlocks:elevator_rotating@1", "OpenBlocks:elevator_rotating@2", "OpenBlocks:elevator_rotating@3",
        "OpenBlocks:elevator_rotating@4", "OpenBlocks:elevator_rotating@5", "OpenBlocks:elevator_rotating@6",
        "OpenBlocks:elevator_rotating@7", "OpenBlocks:elevator_rotating@8", "OpenBlocks:elevator_rotating@9",
        "OpenBlocks:elevator_rotating@10", "OpenBlocks:elevator_rotating@11", "OpenBlocks:elevator_rotating@12",
        "OpenBlocks:elevator_rotating@13", "OpenBlocks:elevator_rotating@14", "OpenBlocks:elevator_rotating@15" };
    private static final String[] QUARTZ_GLASS = { "EnderIO:blockFusedQuartz@0", "EnderIO:blockFusedQuartz@1",
        "EnderIO:blockFusedQuartz@2", "EnderIO:blockFusedQuartz@3", "EnderIO:blockFusedQuartz@4",
        "EnderIO:blockFusedQuartz@5" };
    private static final String[] DEFAULT_MOD_IDS = { "CarpentersBlocks", "ForgeMultipart", "ForgeMicroblock",
        "LittleTiles", "ArchitectureCraft", "chisel", "chiseltones", "Ztones", "BiblioCraft", "Bibliowoods",
        "BiblioWoodsBoP", "BiblioWoodsForestry", "BiblioWoodsNatura", "floodlights", "fairylights", "GardenStuff",
        "GardenCore", "GardenTrees", "malisisdoors", "cfm" };
    private static final String[] DEFAULT_BLOCKS = { HologramPictureBox.WOOLEN_CLAY, "minecraft:wool",
        "minecraft:stained_hardened_clay", "minecraft:hardened_clay", "minecraft:carpet", "minecraft:stained_glass",
        "minecraft:stained_glass_pane", "minecraft:stonebrick", "minecraft:stone_slab@5",
        "minecraft:stone_brick_stairs", "minecraft:quartz_stairs", "minecraft:quartz_block@1",
        "minecraft:quartz_block@2", "minecraft:flower_pot", "minecraft:yellow_flower", "minecraft:red_flower",
        "minecraft:double_plant", "minecraft:tallgrass", "minecraft:deadbush", "minecraft:leaves", "minecraft:leaves2",
        "minecraft:vine", "etfuturum:concrete", "etfuturum:concrete_powder", "extrautilities:color_block",
        "extrautilities:greenscreen", "extrautilities:colorStoneBrick", "extrautilities:colorWoodPlanks",
        "extrautilities:color_brick", "extrautilities:color_lightgem", "extrautilities:color_redstoneLight",
        "extrautilities:color_quartzBlock", "projred|illumination:projectred.illumination.lamp@16",
        "projred|illumination:projectred.illumination.lamp@17", "projred|illumination:projectred.illumination.lamp@18",
        "projred|illumination:projectred.illumination.lamp@19", "projred|illumination:projectred.illumination.lamp@20",
        "projred|illumination:projectred.illumination.lamp@21", "projred|illumination:projectred.illumination.lamp@22",
        "projred|illumination:projectred.illumination.lamp@23", "projred|illumination:projectred.illumination.lamp@24",
        "projred|illumination:projectred.illumination.lamp@25", "projred|illumination:projectred.illumination.lamp@26",
        "projred|illumination:projectred.illumination.lamp@27", "projred|illumination:projectred.illumination.lamp@28",
        "projred|illumination:projectred.illumination.lamp@29", "projred|illumination:projectred.illumination.lamp@30",
        "projred|illumination:projectred.illumination.lamp@31" };
    private static Configuration configuration;
    private static File configurationFile;
    private static volatile long revision;
    // Mixed gameplay mods are intentionally absent: their machines must not become decoration for free.
    private static volatile View current = new View(true, Arrays.asList(DEFAULT_MOD_IDS));

    private DecorationVoucherConfig() {}

    public static final class View {

        public final boolean enabled;
        public final Set<String> allowedModIds;
        public final Set<String> allowedBlocks, allowedOres, deniedModIds, deniedBlocks, deniedOres;

        public View(boolean enabled, Iterable<String> modIds) {
            this(
                enabled,
                modIds,
                defaultBlocks(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList());
        }

        public View(boolean enabled, Iterable<String> modIds, Iterable<String> blocks, Iterable<String> ores,
            Iterable<String> deniedMods, Iterable<String> deniedBlocks, Iterable<String> deniedOres) {
            this.enabled = enabled;
            Set<String> names = new HashSet<>();
            for (String name : modIds) {
                if (name == null) continue;
                String normalized = normalize(name.trim());
                if (normalized.matches("[a-z0-9_.|+-]+")) names.add(normalized);
            }
            allowedModIds = Collections.unmodifiableSet(names);
            allowedBlocks = names(blocks);
            allowedOres = names(ores);
            deniedModIds = names(deniedMods);
            this.deniedBlocks = names(deniedBlocks);
            this.deniedOres = names(deniedOres);
        }

        public boolean allows(String modId) {
            return enabled && allowedModIds.contains(normalize(modId)) && !deniedModIds.contains(normalize(modId));
        }

        public boolean denied(net.minecraft.item.ItemStack stack) {
            if (stack == null || stack.getItem() == null) return true;
            String id = normalize(net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()));
            return deniedModIds.contains(namespace(id)) || matches(deniedBlocks, id, stack.getItemDamage())
                || oreMatches(deniedOres, stack);
        }

        public boolean allows(net.minecraft.item.ItemStack stack) {
            if (!enabled || stack == null || denied(stack)) return false;
            String id = normalize(net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem()));
            return allows(namespace(id)) || matches(allowedBlocks, id, stack.getItemDamage())
                || oreMatches(allowedOres, stack);
        }
    }

    public static View view() {
        return current;
    }

    static long revision() {
        return revision;
    }

    /** Called during item registration. Administrators can edit the file and restart the server. */
    public static synchronized void load(File configDirectory) {
        configurationFile = new File(configDirectory, FILE_NAME);
        Configuration config = configuration = new Configuration(configurationFile);
        config.load();
        boolean enabled = config
            .get(
                CATEGORY,
                "enabled",
                true,
                "Enable decoration vouchers and their recipe. Existing items remain registered for save compatibility.")
            .getBoolean(true);
        String[] modIds = config
            .get(
                CATEGORY,
                "allowedModIds",
                DEFAULT_MOD_IDS,
                "Registered decoration mod IDs (case insensitive). Edit and restart. GT/AE and industrial resources "
                    + "are always charged, including when embedded in a decoration. Do not allow mixed machine mods.")
            .getStringList();
        Set<String> blocks = names(read("allowedBlocks", DEFAULT_BLOCKS));
        Set<String> allowedMods = names(Arrays.asList(modIds));
        if (config.get(CATEGORY, "decorationModDefaultsVersion", 0)
            .getInt(0) < 1) {
            allowedMods = new java.util.TreeSet<>(allowedMods);
            allowedMods.add("ztones");
            allowedMods.add("chiseltones");
        }
        if (config.get(CATEGORY, "tileMaterialDefaultsVersion", 0)
            .getInt(0) < 1) {
            blocks = new java.util.TreeSet<>(blocks);
            blocks.addAll(Arrays.asList(TILE_MATERIALS));
        }
        if (config.get(CATEGORY, "quartzGlassDefaultsVersion", 0)
            .getInt(0) < 1) {
            blocks = new java.util.TreeSet<>(blocks);
            blocks.addAll(Arrays.asList(QUARTZ_GLASS));
        }
        if (config.get(CATEGORY, "aeQuartzGlassDefaultsVersion", 0)
            .getInt() < 1) {
            blocks = new java.util.TreeSet<>(blocks);
            blocks.addAll(Arrays.asList(AE_QUARTZ_GLASS));
        }
        if (config.get(CATEGORY, "openBlocksElevatorDefaultsVersion", 0)
            .getInt(0) < 1) {
            blocks = new java.util.TreeSet<>(blocks);
            blocks.addAll(Arrays.asList(OPENBLOCKS_ELEVATORS));
        }
        View loaded = new View(
            enabled,
            allowedMods,
            blocks,
            read("allowedOres", new String[0]),
            read("deniedModIds", new String[0]),
            read("deniedBlocks", new String[0]),
            read("deniedOres", new String[0]));
        persist(loaded);
        current = loaded;
        revision++;
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    public static View defaults() {
        return new View(true, Arrays.asList(DEFAULT_MOD_IDS));
    }

    private static Iterable<String> defaultBlocks() {
        java.util.List<String> result = new java.util.ArrayList<>(Arrays.asList(DEFAULT_BLOCKS));
        result.addAll(Arrays.asList(QUARTZ_GLASS));
        result.addAll(Arrays.asList(AE_QUARTZ_GLASS));
        result.addAll(Arrays.asList(OPENBLOCKS_ELEVATORS));
        return result;
    }

    private static boolean matches(Set<String> rules, String id, int meta) {
        return rules.contains(id) || rules.contains(id + "@*") || rules.contains(id + "@" + meta);
    }

    private static boolean oreMatches(Set<String> rules, net.minecraft.item.ItemStack stack) {
        if (rules.isEmpty()) return false;
        for (int id : net.minecraftforge.oredict.OreDictionary.getOreIDs(stack))
            if (rules.contains(normalize(net.minecraftforge.oredict.OreDictionary.getOreName(id)))) return true;
        return false;
    }

    private static Set<String> names(Iterable<String> values) {
        Set<String> result = new java.util.TreeSet<>();
        for (String value : values) {
            String name = normalize(value == null ? "" : value.trim());
            if (!name.isEmpty() && name.length() <= 192 && name.matches("[a-z0-9_.:|@*+/-]+")) result.add(name);
        }
        return Collections.unmodifiableSet(result);
    }

    private static Iterable<String> read(String key, String[] defaults) {
        return Arrays.asList(
            configuration
                .get(
                    CATEGORY,
                    key,
                    defaults,
                    "Blocks: namespace:name or namespace:name@metadata. Blacklists win. /gtmb voucher edits live.")
                .getStringList());
    }

    static synchronized boolean save(View next, long expectedRevision) {
        if (configuration == null || expectedRevision != revision) return false;
        persist(next);
        current = next;
        revision++;
        return true;
    }

    /** Forge logs save IO failures. Verify a staged file before publishing live rules. */
    private static void persist(View next) {
        java.nio.file.Path file = configurationFile.toPath()
            .toAbsolutePath();
        java.nio.file.Path temporary = null;
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            if (java.nio.file.Files.exists(file) && !java.nio.file.Files.isRegularFile(file))
                throw new java.io.IOException("Voucher configuration is not a regular file");
            temporary = java.nio.file.Files.createTempFile(file.getParent(), "voucher-rules-", ".cfg");
            if (java.nio.file.Files.exists(file))
                java.nio.file.Files.write(temporary, HologramBlueprintSharing.readBytes(file, 8 * 1024 * 1024));
            Configuration staged = new Configuration(temporary.toFile());
            staged.load();
            staged.get(CATEGORY, "enabled", true)
                .set(next.enabled);
            staged.get(CATEGORY, "tileMaterialDefaultsVersion", 1)
                .set(1);
            staged.get(CATEGORY, "decorationModDefaultsVersion", 1)
                .set(1);
            staged.get(CATEGORY, "quartzGlassDefaultsVersion", 1)
                .set(1);
            staged.get(CATEGORY, "aeQuartzGlassDefaultsVersion", 1)
                .set(1);
            staged.get(CATEGORY, "openBlocksElevatorDefaultsVersion", 1)
                .set(1);
            String[] keys = ruleKeys();
            Set<?>[] values = lists(next);
            for (int i = 0; i < keys.length; i++) staged.get(CATEGORY, keys[i], new String[0])
                .set(values[i].toArray(new String[0]));
            staged.save();
            Configuration readback = new Configuration(temporary.toFile());
            readback.load();
            if (readback.get(CATEGORY, "enabled", !next.enabled)
                .getBoolean(!next.enabled) != next.enabled
                || readback.get(CATEGORY, "tileMaterialDefaultsVersion", 0)
                    .getInt(0) != 1
                || readback.get(CATEGORY, "decorationModDefaultsVersion", 0)
                    .getInt(0) != 1
                || readback.get(CATEGORY, "quartzGlassDefaultsVersion", 0)
                    .getInt(0) != 1
                || readback.get(CATEGORY, "openBlocksElevatorDefaultsVersion", 0)
                    .getInt(0) != 1
                || readback.get(CATEGORY, "aeQuartzGlassDefaultsVersion", 0)
                    .getInt(0) != 1)
                throw new java.io.IOException("Voucher configuration read-back failed");
            for (int i = 0; i < keys.length; i++) if (!names(
                Arrays.asList(
                    readback.get(CATEGORY, keys[i], new String[0])
                        .getStringList())).equals(values[i]))
                throw new java.io.IOException("Voucher configuration rule read-back failed");
            HologramBlueprintSharing.atomicWrite(file, HologramBlueprintSharing.readBytes(temporary, 8 * 1024 * 1024));
            configuration = new Configuration(file.toFile());
            configuration.load();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("抵用券配置保存失败，原规则保留", failure);
        } finally {
            if (temporary != null) try {
                java.nio.file.Files.deleteIfExists(temporary);
            } catch (java.io.IOException cleanup) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.warn("Voucher staging cleanup failed", cleanup);
            }
        }
    }

    static String[] ruleKeys() {
        return new String[] { "allowedModIds", "allowedBlocks", "allowedOres", "deniedModIds", "deniedBlocks",
            "deniedOres" };
    }

    private static Set<?>[] lists(View value) {
        return new Set<?>[] { value.allowedModIds, value.allowedBlocks, value.allowedOres, value.deniedModIds,
            value.deniedBlocks, value.deniedOres };
    }

    static net.minecraft.nbt.NBTTagCompound snapshot() {
        net.minecraft.nbt.NBTTagCompound result = new net.minecraft.nbt.NBTTagCompound();
        result.setLong("revision", revision);
        result.setBoolean("enabled", current.enabled);
        String[] keys = ruleKeys();
        Set<?>[] lists = lists(current);
        for (int i = 0; i < keys.length; i++) {
            net.minecraft.nbt.NBTTagList list = new net.minecraft.nbt.NBTTagList();
            for (Object entry : lists[i]) list.appendTag(new net.minecraft.nbt.NBTTagString(entry.toString()));
            result.setTag(keys[i], list);
        }
        return result;
    }

    static String namespace(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "" : id.substring(0, colon);
    }
}
