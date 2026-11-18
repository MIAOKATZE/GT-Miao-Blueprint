// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.util.Arrays;

import com.gtnewhorizon.structurelib.structure.StructureDefinition;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Checks the bounded preview against StructureLib's encoded navigation. */
public final class HologramControllerOutlineRegression {

    private HologramControllerOutlineRegression() {}

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        StructureDefinition<?> sparse = definition(new String[][] { { " ~+", " +-  -" }, { "    +", "", "   +" } });
        SimpleAssert.that(
            Arrays.equals(new int[] { 4, 3, 2 }, HologramControllerOutline.dimensions(sparse)),
            "skips reset the encoded axes and air checks do not enlarge the outline");
        SimpleAssert.that(
            HologramControllerOutline.dimensions(definition(new String[][] { { "~-" } })) == null,
            "a controller and air checks alone supply no outline");
        char[] row = new char[64];
        Arrays.fill(row, ' ');
        SimpleAssert.that(
            HologramControllerOutline.dimensions(definition(new String[][] { { "+" + new String(row) + "+" } })) == null,
            "a sparse declaration beyond the bounded preview dimensions is skipped");

        String[] rows = new String[9];
        Arrays.fill(row, '+');
        Arrays.fill(rows, new String(row));
        SimpleAssert.that(
            HologramControllerOutline.dimensions(definition(new String[][] { rows })) == null,
            "a dense declaration beyond the entry cap is skipped");

        StructureDefinition<?> multiple = (StructureDefinition<?>) StructureDefinition.builder()
            .addShape("main", new String[][] { { "~+" } })
            .addShape("repeat", new String[][] { { "+" } })
            .build();
        SimpleAssert.that(
            HologramControllerOutline.dimensions(multiple) == null,
            "the local outline does not guess a piece for multipart controllers");
        SimpleAssert.that(HologramControllerOutline.create(null) == null, "no local tile supplies no outline");
    }

    private static StructureDefinition<?> definition(String[][] shape) {
        return (StructureDefinition<?>) StructureDefinition.builder()
            .addShape("main", shape)
            .build();
    }
}
