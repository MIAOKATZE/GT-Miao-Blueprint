// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.minecraft.nbt.NBTTagCompound;

import com.github.bsideup.jabel.Desugar;

/** Player intent only: exceptions to the registered controller's server-side declaration, never geometry. */
@Desugar
public record HologramDeclaredNativeSelection(int controller, Map<String, Integer> channels, int scope, int layer,
    List<Offset> selected, List<Hatch> hatches, int operation, int destination) {

    public HologramDeclaredNativeSelection(int controller, Map<String, Integer> channels, int scope, int layer,
        List<Offset> selected, List<Hatch> hatches) {
        this(controller, channels, scope, layer, selected, hatches, 0, -1);
    }

    public static final int MAX_POINTS = 128, MAX_OFFSET = 4096;

    @Desugar
    public record Offset(int x, int y, int z) {
        boolean valid() {
            return coordinate(x) && coordinate(y) && coordinate(z);
        }
    }

    @Desugar
    public record Hatch(Offset position, int id, int facing) {}

    public HologramDeclaredNativeSelection {
        var channelCopy = new LinkedHashMap<String, Integer>();
        channels.forEach((name, tier) -> channelCopy.put(Objects.requireNonNull(name), Objects.requireNonNull(tier)));
        channels = Collections.unmodifiableMap(channelCopy);
        selected = immutableList(selected);
        hatches = immutableList(hatches);
    }

    private static <T> List<T> immutableList(List<T> source) {
        var copy = new ArrayList<T>(source.size());
        for (T item : source) copy.add(Objects.requireNonNull(item));
        return Collections.unmodifiableList(copy);
    }

    public NBTTagCompound channelTag() {
        var tag = new NBTTagCompound();
        channels.forEach(tag::setInteger);
        return tag;
    }

    public boolean valid() {
        if (operation < 0 || operation > 2 || (operation == 2 ? destination < 0 || destination > 1
            || scope != 2 || !hatches.isEmpty() : destination != -1)
            || operation == 1 && (hatches.isEmpty() || scope != 2)) return false;
        if (controller <= 0 || controller > Short.MAX_VALUE || channels.size() > 64
            || scope < 0 || scope > 3 || !coordinate(layer) || scope != 1 && layer != 0
            || (scope == 2 ? selected.isEmpty() || selected.size() > MAX_POINTS
                : selected.size() != (scope == 3 ? 1 : 0))
            || hatches.size() > MAX_POINTS) return false;
        if (!HologramSessionPolicy.validChannels(channelTag())) return false;
        // Main/auxiliary channels are selection levels, not client-defined dimensions.
        if (channels.values().stream().anyMatch(tier -> tier > 64)) return false;
        var points = new HashSet<Offset>();
        for (Offset offset : selected) if (!offset.valid() || !points.add(offset)) return false;
        var edited = new HashSet<Offset>();
        for (Hatch hatch : hatches) if (!hatch.position.valid() || !edited.add(hatch.position)
            || hatch.id <= 0 || hatch.id > Short.MAX_VALUE || hatch.facing < -1 || hatch.facing > 5) return false;
        return operation != 1 || edited.equals(points);
    }

    static boolean coordinate(int value) {
        return value >= -MAX_OFFSET && value <= MAX_OFFSET;
    }
}
