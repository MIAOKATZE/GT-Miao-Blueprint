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
    private static final String[] DEFAULT_MOD_IDS = { "CarpentersBlocks", "ForgeMultipart", "ForgeMicroblock",
        "LittleTiles", "ArchitectureCraft", "chisel", "chiseltones", "Ztones", "BiblioCraft", "Bibliowoods",
        "floodlights", "fairylights", "GardenStuff", "GardenCore", "GardenTrees", "malisisdoors", "cfm" };
    // Mixed gameplay mods are intentionally absent: their machines must not become decoration for free.
    private static volatile View current = new View(true, Arrays.asList(DEFAULT_MOD_IDS));

    private DecorationVoucherConfig() {}

    public static final class View {

        public final boolean enabled;
        public final Set<String> allowedModIds;

        public View(boolean enabled, Iterable<String> modIds) {
            this.enabled = enabled;
            Set<String> names = new HashSet<>();
            for (String name : modIds) {
                if (name == null) continue;
                String normalized = normalize(name.trim());
                if (normalized.matches("[a-z0-9_.-]+")) names.add(normalized);
            }
            allowedModIds = Collections.unmodifiableSet(names);
        }

        public boolean allows(String modId) {
            return enabled && allowedModIds.contains(normalize(modId));
        }
    }

    public static View view() {
        return current;
    }

    /** Called during item registration. Administrators can edit the file and restart the server. */
    public static synchronized void load(File configDirectory) {
        Configuration config = new Configuration(new File(configDirectory, FILE_NAME));
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
        current = new View(enabled, Arrays.asList(modIds));
        if (config.hasChanged()) config.save();
    }

    static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
