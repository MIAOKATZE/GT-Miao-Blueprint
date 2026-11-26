// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

/** Display only. Never turn a localized name or a client icon into a payment or placement identity. */
final class HologramMaterialRowPresentation {

    private final Map<HologramLibraryMaterialForecast.Identity, Sample> visible = new LinkedHashMap<HologramLibraryMaterialForecast.Identity, Sample>(
        32,
        0.75f,
        true) {

        @Override
        protected boolean removeEldestEntry(Map.Entry<HologramLibraryMaterialForecast.Identity, Sample> entry) {
            return size() > 32;
        }
    };

    static final class Sample {

        final String name, identity;
        final ItemStack icon;

        Sample(String name, String identity, ItemStack icon) {
            this.name = name;
            this.identity = identity;
            this.icon = icon;
        }
    }

    Sample describe(HologramLibraryMaterialDescription.Row row) {
        return visible.computeIfAbsent(row.identity(), key -> resolve(key.registry(), key.damage()));
    }

    private static Sample resolve(String registry, int damage) {
        String identity = registry + ":" + damage;
        try {
            Object object = Item.itemRegistry.getObject(registry);
            if (damage >= 0 && object instanceof Item item
                && registry.equals(Item.itemRegistry.getNameForObject(item))) {
                // GT machines share ONE registered item. Only this subtype's full item damage identifies its MTE.
                ItemStack icon = new ItemStack(item, 1, damage);
                return new Sample(itemName(icon, registry), identity, icon);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // Optional mod item cannot be constructed in this client. Retain the exact identity for diagnostics.
        }
        return new Sample(HologramUiText.projectorOr("native.material_unresolved", "无法解析的材料"), identity, null);
    }

    private static boolean technicalKey(String value) {
        return value.indexOf(' ') < 0 && (value.endsWith(".name") || value.startsWith("item.")
            || value.startsWith("tile.")
            || value.startsWith("gt.")
            || value.startsWith("GT5U.")
            || value.startsWith("gregtech."));
    }

    static String itemName(ItemStack stack, String registry) {
        try {
            String value = stack.getDisplayName();
            if (value != null && !value.trim()
                .isEmpty()) {
                if (stack.hasDisplayName()) return value; // Explicit player-given names are not localization keys.
                if (StatCollector.canTranslate(value)) value = StatCollector.translateToLocal(value);
                String key = stack.getUnlocalizedName();
                if (!value.equals(registry) && !value.equals(key)
                    && !value.equals(key + ".name")
                    && !technicalKey(value)) return value;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Broken or missing mod localization must not take the whole material list down.
        }
        return HologramUiText.projectorOr("native.material_name_unavailable", "材料名称不可用");
    }
}
