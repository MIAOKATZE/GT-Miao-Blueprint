package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Portable quantum group migration and the item-template accounting boundary, without a live network. */
public final class HologramNetworkBindingsRegression {

    private HologramNetworkBindingsRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramNetworkBindingsRegression.class, args)) return;
        run();
        System.out.println("Hologram network binding regressions passed");
    }

    public static void run() throws Exception {
        grouping();
        malformedGroup();
        physicalPatternsCost();
        bindingSurvivesDisabledPayload();
    }

    private static void grouping() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.cells.add(cell(0, 100));
        source.cells.add(cell(1, 100));
        source.cells.add(cell(2, 200));
        String[] originals = source.cells.stream()
            .map(cell -> cell.tileNbt)
            .toArray(String[]::new);
        HologramAePolicy.Draft sanitized = HologramAePolicy.sanitize(source);
        String first = tag(sanitized.preset.cells.get(0)).getString(HologramNetworkBindings.GROUP);
        SimpleAssert.that(
            first.equals(tag(sanitized.preset.cells.get(1)).getString(HologramNetworkBindings.GROUP)),
            "same captured network anchor remains one local group");
        SimpleAssert.that(
            !first.equals(tag(sanitized.preset.cells.get(2)).getString(HologramNetworkBindings.GROUP)),
            "different source networks remain independent groups");
        for (int i = 0; i < source.cells.size(); i++) {
            SimpleAssert.that(originals[i].equals(source.cells.get(i).tileNbt), "source draft is unchanged");
            NBTTagCompound clean = tag(sanitized.preset.cells.get(i));
            SimpleAssert.that(
                !clean.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR) && !clean.hasKey("GTSWN_IncorporationIdentity"),
                "no source anchor or incorporation UUID on disk");
            SimpleAssert.that("".equals(clean.getString("OwnerUUID")), "source player identity is removed");
            SimpleAssert.that("join".equals(clean.getString(HologramNetworkBindings.ROLE)), "join role is retained");
            SimpleAssert.that(
                clean.getCompoundTag("filter")
                    .getString("mode")
                    .equals("whitelist"),
                "independent ghost filter configuration survives deidentification");
            SimpleAssert.eq(
                5,
                HologramBlueprintResources.plan(clean).items.get(0).stackSize,
                "ordinary physical inventory still requires exact payment");
        }
        HologramAePolicy.Draft again = HologramAePolicy.sanitize(sanitized.preset);
        for (int i = 0; i < again.preset.cells.size(); i++) SimpleAssert.that(
            sanitized.preset.cells.get(i).tileNbt.equals(again.preset.cells.get(i).tileNbt),
            "portable network sanitization is idempotent");
    }

    private static void malformedGroup() throws Exception {
        HologramPresets.Preset malformed = new HologramPresets.Preset();
        HologramPresets.Cell cell = cell(0, 100);
        NBTTagCompound payload = tag(cell);
        payload.removeTag(HologramNetworkBindings.CAPTURE_ANCHOR);
        payload.setString(HologramNetworkBindings.GROUP, "q0");
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
        malformed.cells.add(cell);
        try {
            HologramAePolicy.sanitize(malformed);
            throw new AssertionError("invalid local group must reject before placement");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("group"),
                "malformed group has an explicit report");
        }
        payload.removeTag(HologramNetworkBindings.GROUP);
        payload.setTag(HologramNetworkBindings.CAPTURE_ANCHOR, new NBTTagCompound());
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
        try {
            HologramAePolicy.sanitize(malformed);
            throw new AssertionError("incomplete anchor cannot silently become a selectable network");
        } catch (IOException expected) {
            SimpleAssert.that(
                expected.getMessage()
                    .contains("anchor"),
                "partial anchor is not guessed");
        }
    }

    private static void bindingSurvivesDisabledPayload() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.cells.add(cell(0, 100));
        HologramPresets.Preset portable = HologramAePolicy.sanitize(source).preset;
        HologramNetworkBindings.editHint(portable, "q1", "Protected production network");
        NBTTagCompound selected = HologramBlueprintNbt.forPlacement(
            portable.cells.get(0).tileNbt,
            java.util.UUID.randomUUID(),
            "placer",
            20,
            64,
            30,
            false,
            false,
            null);
        SimpleAssert.that(
            selected != null && "q1".equals(selected.getString(HologramNetworkBindings.GROUP))
                && "join".equals(selected.getString(HologramNetworkBindings.ROLE))
                && "Protected production network".equals(selected.getString(HologramNetworkBindings.HINT)),
            "turning off optional NBT and covers never removes the mandatory binding descriptor");
        SimpleAssert.that(
            !selected.hasKey("Inventory") && !selected.hasKey("filter"),
            "mandatory network binding does not restore the omitted physical inventory or configuration");
    }

    private static void physicalPatternsCost() throws Exception {
        NBTTagCompound payload = new NBTTagCompound();
        NBTTagList patterns = new NBTTagList();
        patterns.appendTag(new ItemStack(Items.diamond, 7).writeToNBT(new NBTTagCompound()));
        payload.setTag("patterns", patterns);
        SimpleAssert.eq(
            7,
            HologramBlueprintResources.plan(payload).items.get(0).stackSize,
            "calling ordinary inventory patterns never authorizes free copying");
    }

    private static HologramPresets.Cell cell(int x, int anchorX) throws Exception {
        HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(Blocks.stone), x, 0, 0, -1, false);
        cell.block = "minecraft:stone";
        NBTTagCompound payload = new NBTTagCompound(), anchor = new NBTTagCompound();
        anchor.setInteger("anchorDim", 73);
        anchor.setInteger("anchorX", anchorX);
        anchor.setInteger("anchorY", 64);
        anchor.setInteger("anchorZ", 321);
        payload.setTag(HologramNetworkBindings.CAPTURE_ANCHOR, anchor);
        payload.setString(HologramNetworkBindings.ROLE, "join");
        payload.setString("GTSWN_IncorporationIdentity", "private-source-identity");
        payload.setString("OwnerUUID", "private-player-identity");
        NBTTagCompound filter = new NBTTagCompound();
        filter.setString("mode", "whitelist");
        payload.setTag("filter", filter);
        NBTTagList inventory = new NBTTagList();
        inventory.appendTag(new ItemStack(Items.diamond, 5).writeToNBT(new NBTTagCompound()));
        payload.setTag("Inventory", inventory);
        cell.tileNbt = HologramBlueprintNbt.encodeCanonical(payload);
        return cell;
    }

    private static NBTTagCompound tag(HologramPresets.Cell cell) throws Exception {
        return HologramBlueprintNbt.decode(cell.tileNbt);
    }
}
