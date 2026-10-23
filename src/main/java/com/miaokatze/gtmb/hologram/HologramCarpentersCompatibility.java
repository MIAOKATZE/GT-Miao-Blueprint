package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.nbt.NBTTagCompound;

/** Blueprint-only ownership projection for the audited Carpenter root; never changes a live tile. */
final class HologramCarpentersCompatibility {

    private static final String[] ROOT_OWNER_FIELDS = { "cbOwner", "ownerID" };

    private HologramCarpentersCompatibility() {}

    /**
     * TEBase 3.7.3 stores cbOwner. ownerID is an explicit blueprint compatibility alias, not a recursive rule.
     * The legacy owner key selects the old native attribute decoder, so retain that marker with an empty value.
     * Both ignored identity fields are omitted regardless of their encoded type; physical data is untouched.
     */
    static boolean stripOwner(Block block, NBTTagCompound tag) {
        if (tag == null || !(block == null ? HologramDeidentification.carpenterRoot(tag)
            : HologramDeidentification.carpenterBlock(block))) return false;
        boolean changed = false;
        for (String key : ROOT_OWNER_FIELDS) {
            if (!tag.hasKey(key)) continue;
            tag.removeTag(key);
            changed = true;
        }
        if (tag.hasKey("owner", 8) && !tag.getString("owner")
            .isEmpty()) {
            tag.setString("owner", "");
            changed = true;
        }
        return changed;
    }

    static String normalize(Block block, String encoded) throws IOException {
        if (!HologramDeidentification.carpenterBlock(block) || encoded == null || encoded.isEmpty()) return encoded;
        NBTTagCompound tag = HologramBlueprintNbt.decode(encoded);
        return stripOwner(block, tag) ? HologramBlueprintNbt.encodeCanonical(tag) : encoded;
    }
}
