package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Independent presentation DTO. Native persistence, inventories, authors and owner identities are never sent. */
final class HologramPublicPreview {

    private HologramPublicPreview() {}

    static NBTTagCompound project(HologramPresets.Preset trusted) throws IOException {
        NBTTagCompound result = new NBTTagCompound();
        result.setBoolean("virtual", true);
        result.setBoolean("blueprintPreview", true);
        result.setBoolean("virtualPlacement", true);
        result.setString("name", trusted.name);
        NBTTagList cells = new NBTTagList();
        for (HologramPresets.Cell cell : trusted.cells) cells.appendTag(row(cell));
        result.setTag("cells", cells);
        NBTTagCompound pending = new NBTTagCompound();
        pending.setBoolean("complete", false);
        pending.setBoolean("loading", true);
        result.setTag("localMaterials", pending);
        return result;
    }

    /** Server-thread continuation; one native material provider call is the smallest atomic step. */
    static final class Builder {

        final HologramPresets.Preset source;
        final NBTTagCompound result = new NBTTagCompound();
        final HologramStaticMaterials.Builder materials = new HologramStaticMaterials.Builder();
        int cursor;

        Builder(HologramPresets.Preset trusted) {
            source = trusted;
            if (!trusted.retainNbt || !trusted.retainCovers || trusted.retainedNbtKeys != null)
                materials.unaccounted("Custom payload retention needs final review");
        }

        boolean step() throws IOException {
            if (cursor < source.cells.size()) {
                HologramPresets.Cell cell = source.cells.get(cursor++);
                if (!cell.excluded) try {
                    materials.add(
                        cell.block(),
                        cell.metadata,
                        cell.stack(),
                        source.retainNbt ? HologramBlueprintNbt.decode(cell.tileNbt) : null,
                        source.retainNbt ? HologramBlueprintNbt.decode(cell.itemNbt) : null);
                } catch (IOException | RuntimeException unavailable) {
                    materials.unaccounted(unavailable.getMessage());
                }
            }
            if (cursor < source.cells.size()) return false;
            result.setTag("localMaterials", materials.publicSnapshot());
            return true;
        }
    }

    private static NBTTagCompound row(HologramPresets.Cell source) throws IOException {
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("x", source.x);
        row.setInteger("y", source.y);
        row.setInteger("z", source.z);
        row.setInteger("dx", source.x);
        row.setInteger("dy", source.y);
        row.setInteger("dz", source.z);
        row.setInteger("targetFacing", source.facing);
        row.setBoolean("anchor", source.controller);
        row.setBoolean("excluded", source.excluded);
        row.setBoolean("inScope", true);
        row.setString("status", "missing");
        String block = source.block == null ? "" : source.block;
        row.setString("wantId", block);
        row.setString("id", block);
        row.setInteger("wantMeta", source.metadata);
        row.setInteger("meta", source.metadata);
        ItemStack material = source.stack();
        if (material != null) {
            material.setTagCompound(null);
            row.setTag("targetStack", material.writeToNBT(new NBTTagCompound()));
        }
        NBTTagCompound visual = display(source);
        row.setTag("targetDisplay", visual);
        if (!visual.hasKey("nativeTile", 10) && source.block() != null) {
            String nativeType = source.block()
                .getClass()
                .getName();
            if (nativeType.equals("codechicken.multipart.BlockMultipart")
                || nativeType.equals("com.creativemd.littletiles.common.blocks.BlockTile")
                || nativeType.startsWith("gcewing.architecture.common.block.")
                || nativeType.equals("crazypants.enderio.conduit.BlockConduitBundle"))
                row.setString("sourceWarning", "Full native appearance is unavailable in the server library preview");
        }
        return row;
    }

    static NBTTagCompound display(HologramPresets.Cell source) throws IOException {
        NBTTagCompound saved = HologramBlueprintNbt.decode(source.tileNbt);
        NBTTagCompound display = new NBTTagCompound();
        display.setInteger("color", -1);
        if (source.facing >= 0) display.setInteger("facing", source.facing);
        if (saved == null) return display;
        // These are the scalar fields consumed by the existing reviewed GT display snapshot path.
        for (String key : new String[] { "texturePage", "textureIndex" })
            if (saved.hasKey(key, 3)) display.setInteger(key, saved.getInteger(key));
        if (saved.hasKey("mColor", 99)) display.setInteger("color", saved.getByte("mColor") - 1);
        if (saved.hasKey("mConnections", 99)) display.setInteger("connections", saved.getByte("mConnections") & 63);
        if (source.facing < 0 && saved.hasKey("mFacing", 99)) display.setInteger("facing", saved.getShort("mFacing"));
        NBTTagList covers = new NBTTagList(), originalCovers = saved.getTagList("gt.covers", 10);
        for (int index = 0; index < originalCovers.tagCount(); index++) {
            NBTTagCompound cover = new NBTTagCompound();
            NBTTagCompound original = originalCovers.getCompoundTagAt(index);
            copyScalar(
                original,
                cover,
                "s",
                "id",
                HologramBlueprintNbt.COVER_REGISTRY,
                HologramBlueprintNbt.COVER_DAMAGE);
            covers.appendTag(cover);
        }
        if (covers.tagCount() > 0) display.setTag("gt.covers", covers);
        if (source.block() == net.minecraft.init.Blocks.standing_sign
            || source.block() == net.minecraft.init.Blocks.wall_sign) {
            NBTTagCompound sign = new NBTTagCompound();
            sign.setString("id", "Sign");
            for (int line = 1; line <= 4; line++)
                if (saved.hasKey("Text" + line, 8)) sign.setString("Text" + line, saved.getString("Text" + line));
            display.setTag("nativeTile", sign);
        } else if (HologramAeNativePreview.supports(source.block())) {
            display.setTag("nativeTile", HologramAeNativePreview.target(source.block(), saved));
        } else if (source.block() != null && source.block()
            .getClass() == appeng.block.networking.BlockCableBus.class) {
                display.setTag("nativeTile", cable(saved));
            } else if (HologramNetworkBindings.node(source.block())) {
                NBTTagCompound nativeTile = new NBTTagCompound();
                nativeTile.setTag("partContainer", cable(saved.getCompoundTag("partContainer")));
                if (HologramDisplaySnapshot.validQuantumSides(saved)) nativeTile.setInteger(
                    HologramDisplaySnapshot.QUANTUM_SIDES,
                    saved.getInteger(HologramDisplaySnapshot.QUANTUM_SIDES));
                display.setTag("nativeTile", nativeTile);
            } else if (HologramDeidentification.carpenterBlock(source.block())) {
                NBTTagCompound nativeTile = new NBTTagCompound();
                copyScalar(saved, nativeTile, "id", "cbMetadata", "cbDesign");
                for (int side = 0; side < 7; side++) copyScalar(saved, nativeTile, "cbChiselDesign_" + side);
                NBTTagList attributes = new NBTTagList(), originals = saved.getTagList("cbAttrList", 10);
                for (int index = 0; index < originals.tagCount(); index++) {
                    NBTTagCompound original = originals.getCompoundTagAt(index), attribute = new NBTTagCompound();
                    copyScalar(
                        original,
                        attribute,
                        "cbAttribute",
                        "id",
                        "Count",
                        "Damage",
                        "cbUniqueId",
                        HologramBlueprintNbt.ITEM_REGISTRY);
                    attributes.appendTag(attribute);
                }
                nativeTile.setTag("cbAttrList", attributes);
                display.setTag("nativeTile", nativeTile);
            }
        return display;
    }

    /** The audited native cable renderer consumes only definition items, facade material and reporting spin. */
    private static NBTTagCompound cable(NBTTagCompound saved) {
        NBTTagCompound result = new NBTTagCompound();
        for (int side = 0; side < 7; side++) {
            String definition = "def:" + side;
            if (saved.hasKey(definition, 10)) result.setTag(
                definition,
                HologramAeNativePreview.partDefinition(saved.getCompoundTag(definition))
                    .writeToNBT(new NBTTagCompound()));
            String extra = "extra:" + side;
            if (saved.hasKey(extra, 10)) {
                NBTTagCompound spin = new NBTTagCompound();
                spin.setByte(
                    "spin",
                    (byte) (saved.getCompoundTag(extra)
                        .getByte("spin") & 3));
                result.setTag(extra, spin);
            }
            String facade = "facade:" + side;
            if (side < 6 && saved.hasKey(facade, 10)) result.setTag(
                facade,
                HologramAeNativePreview.facadeDefinition(saved.getCompoundTag(facade))
                    .writeToNBT(new NBTTagCompound()));
        }
        return result;
    }

    private static void copyScalar(NBTTagCompound source, NBTTagCompound target, String... keys) {
        for (String key : keys) {
            NBTBase value = source.getTag(key);
            if (value != null && (value.getId() >= 1 && value.getId() <= 6 || value.getId() == 8))
                target.setTag(key, value.copy());
        }
    }
}
