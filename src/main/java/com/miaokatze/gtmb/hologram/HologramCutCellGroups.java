package com.miaokatze.gtmb.hologram;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Transaction-local read-only views of the owned entry; never stored across a world mutation barrier. */
final class HologramCutCellGroups {

    final Map<Long, List<NBTTagCompound>> chunks = new LinkedHashMap<>();

    HologramCutCellGroups(NBTTagList cells, int ox, int oz) {
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            long key = HologramCutChunks.key((ox + cell.getInteger("x")) >> 4, (oz + cell.getInteger("z")) >> 4);
            chunks.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(cell);
        }
    }
}
