package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Test evidence: source payload and native serialization are both retained, with every changed path reported. */
final class HologramCutNativeReceipt {

    private HologramCutNativeReceipt() {}

    static NBTTagCompound storedTile(EntityPlayerMP actor, String id, int dx) throws IOException {
        NBTTagList entries = new HologramCutStorage(HologramCutService.saveRoot(), actor.getUniqueID()).load()
            .getTagList("entries", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            NBTTagCompound entry = entries.getCompoundTagAt(i);
            if (!id.equals(entry.getString("id"))) continue;
            NBTTagList cells = entry.getTagList("cells", 10);
            for (int c = 0; c < cells.tagCount(); c++) {
                NBTTagCompound cell = cells.getCompoundTagAt(c);
                if (cell.getInteger("x") == dx && cell.getInteger("y") == 0 && cell.getInteger("z") == 0)
                    return (NBTTagCompound) cell.getCompoundTag("tile")
                        .copy();
            }
        }
        throw new IOException("native fixture raw stored tile missing");
    }

    static void record(String label, NBTTagCompound original, NBTTagCompound placed) throws IOException {
        Path file = HologramCutService.saveRoot()
            .resolve("gtmb-cut-native-receipt.nbt");
        NBTTagCompound root = Files.exists(file) ? HologramSnapshotCodec.decode(Files.readAllBytes(file))
            : new NBTTagCompound();
        NBTTagCompound entry = new NBTTagCompound();
        entry.setTag("source", original.copy());
        entry.setTag("nativePlaced", placed.copy());
        NBTTagList changes = new NBTTagList();
        differences("", original, placed, changes);
        entry.setTag("changedPaths", changes);
        root.setTag(label, entry);
        Files.write(file, HologramSnapshotCodec.compress(root));
        System.out.println("[GTMB-CUT-NATIVE-TRANSITION] " + label + " changedPaths=" + changes);
    }

    private static void differences(String path, NBTBase before, NBTBase after, NBTTagList result) {
        if (before == null ? after == null : before.equals(after)) return;
        if (before instanceof NBTTagCompound && after instanceof NBTTagCompound) {
            NBTTagCompound a = (NBTTagCompound) before, b = (NBTTagCompound) after;
            Set<String> keys = new TreeSet<>();
            for (Object key : a.func_150296_c()) keys.add((String) key);
            for (Object key : b.func_150296_c()) keys.add((String) key);
            for (String key : keys)
                differences(path.isEmpty() ? key : path + "." + key, a.getTag(key), b.getTag(key), result);
        } else if (before instanceof NBTTagList && after instanceof NBTTagList
            && ((NBTTagList) before).func_150303_d() == 10
            && ((NBTTagList) after).func_150303_d() == 10) {
                NBTTagList a = (NBTTagList) before, b = (NBTTagList) after;
                for (int i = 0; i < Math.max(a.tagCount(), b.tagCount()); i++) differences(
                    path + "[" + i + "]",
                    i < a.tagCount() ? a.getCompoundTagAt(i) : null,
                    i < b.tagCount() ? b.getCompoundTagAt(i) : null,
                    result);
            } else {
                NBTTagCompound row = new NBTTagCompound();
                row.setString("path", path);
                result.appendTag(row);
            }
    }
}
