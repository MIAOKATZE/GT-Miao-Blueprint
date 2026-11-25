// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.util.StringTranslate;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Isolated language-resource and native lookup regression; no Minecraft client or world starts. */
public final class HologramProjectorLocalizationRegression {

    private static final String PREFIX = "gtmb.hologram.projector.";
    private static final Pattern PLACEHOLDER = Pattern.compile("%([1-9][0-9]*)\\$s");

    private HologramProjectorLocalizationRegression() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> english = read("en_US");
        for (String locale : new String[] { "zh_CN", "zh_TW" }) {
            Map<String, String> other = read(locale);
            SimpleAssert.eq(english.keySet(), other.keySet(), "native projector key parity: " + locale);
            for (Map.Entry<String, String> entry : english.entrySet()) {
                String key = entry.getKey();
                SimpleAssert.eq(placeholders(entry.getValue()), placeholders(other.get(key)), locale + ": " + key);
            }
        }
        for (String value : english.values()) {
            SimpleAssert.that(
                value.codePoints()
                    .noneMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                "English projector text contains untranslated Chinese: " + value);
        }
        inject("en_US");
        expect("Blueprint Library", "library.title");
        expect("Search blueprints", "library.search");
        expect("Place Selected Blueprint", "library.place_selected");
        expect("Edit Selected", "library.edit_selected");
        expect("Upgrade All Legacy Blueprints", "library.upgrade");
        expect("Controller Projection", "editor.controller");
        var site = new net.minecraft.nbt.NBTTagCompound();
        SimpleAssert.eq("Confirm Construction", HologramUiBuildReview.buildLabel(site), "uncertain native site uses English");
        site.setInteger("siteOccupied", 0);
        SimpleAssert.eq("Confirm New Build", HologramUiBuildReview.buildLabel(site), "empty native site uses English");
        site.setInteger("siteMatchingSolid", 1);
        SimpleAssert.eq("Confirm Fill Missing Blocks", HologramUiBuildReview.buildLabel(site), "matching native site uses English");
        var data = new net.minecraft.nbt.NBTTagCompound();
        data.setBoolean("localStandardController", true);
        SimpleAssert.eq("This controller has no native structure available for construction.",
            HologramPlacementConfirmation.localStructureReviewBlocker(new HologramState(data)), "native eligibility blocker uses English");
        expect("Construction complete. 12 cells placed.", "stock.done_placement", "Construction", 12);
        expect("The selected material sources do not have enough stock. Placement did not start.", "stock.reason_materials");
        expect("7 cells · 2 structures", "library.row_counts", 7, 2);
        expect(
            "Permission group: Custom · Publish: Allowed · Download: Denied · Quota: 8",
            "library.permissions",
            "Custom",
            HologramUiText.projector("library.allowed"),
            HologramUiText.projector("library.denied"),
            8);
        String authored = "原作者 100% \\n";
        expect("Author: " + authored, "library.author", authored);
        expect(
            "Delete blueprint: " + authored + "\nYour current draft stays open. This cannot be undone.",
            "dialog.delete_body",
            authored);
        inject("zh_CN");
        expect("蓝图库", "library.title");
        expect("7 格 · 2 结构", "library.row_counts", 7, 2);
        inject("zh_TW");
        expect("藍圖庫", "library.title");
        expect("7 格 · 2 結構", "library.row_counts", 7, 2);
        System.out.println("PASS projector localization: " + english.size() + " keys in en_US, zh_CN, zh_TW");
    }

    private static void expect(String expected, String key, Object... args) {
        SimpleAssert.eq(expected, HologramUiText.projector(key, args), "native StatCollector lookup: " + key);
    }

    private static InputStream asset(String locale) {
        InputStream stream = HologramProjectorLocalizationRegression.class
            .getResourceAsStream("/assets/gtmb/lang/" + locale + ".lang");
        SimpleAssert.that(stream != null, "missing projector language resource " + locale);
        return stream;
    }

    private static void inject(String locale) throws IOException {
        try (InputStream stream = asset(locale)) {
            StringTranslate.inject(stream);
        }
    }

    private static Map<String, String> read(String locale) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(asset(locale), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith(PREFIX)) continue;
                int separator = line.indexOf('=');
                SimpleAssert.that(separator > PREFIX.length(), "missing language key: " + line);
                String key = line.substring(PREFIX.length(), separator);
                String value = line.substring(separator + 1);
                SimpleAssert.that(
                    !value.trim()
                        .isEmpty(),
                    "missing language value: " + key);
                SimpleAssert.that(entries.put(key, value) == null, "duplicate language key: " + key);
            }
        }
        SimpleAssert.that(!entries.isEmpty(), "missing projector locale section: " + locale);
        return entries;
    }

    private static List<String> placeholders(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        List<String> positions = new ArrayList<>();
        while (matcher.find()) positions.add(matcher.group(1));
        Collections.sort(positions);
        return positions;
    }
}
