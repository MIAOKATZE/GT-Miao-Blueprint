// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

import appeng.api.AEApi;
import appeng.api.parts.IPartItem;
import appeng.parts.reporting.PartPatternTerminalEx;
import appeng.util.item.AEItemStack;
import crazypants.enderio.conduit.ConduitUtil;
import crazypants.enderio.conduit.IConduit;
import crazypants.enderio.conduit.power.PowerConduit;

/** Invoke only after actual FML initialization. Does not attach, tick, or mutate a live world. */
public final class HologramModernAeEioNativeRegression {

    private static int checks;

    private HologramModernAeEioNativeRegression() {}

    public static void run(int expectedAllocation, boolean expectedModernConduits) throws Exception {
        checks = 0;
        ae(expectedAllocation);
        eio(expectedModernConduits);
        System.out
            .println("[GTMB-MODERN-AE-EIO] PASS " + checks + " initialized native assertions; no live-world mutation");
    }

    private static void ae(int expectedAllocation) throws Exception {
        Block cable = block("appeng.block.networking.BlockCableBus");
        ItemStack definition = AEApi.instance()
            .definitions()
            .parts()
            .patternTerminalEx()
            .maybeStack(1)
            .get();
        PartPatternTerminalEx terminal = (PartPatternTerminalEx) ((IPartItem) definition.getItem())
            .createPartFromItemStack(definition.copy());
        HologramAeTerminalCapabilities capability = HologramAeTerminalCapabilities.inspect(terminal);
        require(
            capability.inputs == expectedAllocation && capability.outputs == expectedAllocation,
            "actual target inventory allocation matches the selected profile");
        for (boolean inverted : new boolean[] { false, true }) {
            NBTTagCompound emptyExtra = new NBTTagCompound();
            terminal.writeToNBT(emptyExtra);
            emptyExtra.setBoolean("inverted", inverted);
            emptyExtra.setInteger("activePage", capability.pages - 1);
            NBTTagCompound empty = cable(cable, definition, emptyExtra);
            HologramAeBlueprint.Analysis emptyAnalysis = HologramAeBlueprint.analyze(cable, 0, empty);
            require(!emptyAnalysis.blocking, "empty actual Ex terminal has an admitted schema");
            int input = capability.effectiveInputs(inverted) - 1, output = capability.effectiveOutputs(inverted) - 1;
            NBTTagCompound extra = (NBTTagCompound) emptyExtra.copy();
            ghost(extra, "craftingGrid", input, new ItemStack(Items.diamond, 64));
            ghost(extra, "outputList", output, new ItemStack(Items.emerald, 64));
            NBTTagCompound raw = cable(cable, definition, extra), original = (NBTTagCompound) raw.copy();
            HologramAeBlueprint.Analysis analysis = HologramAeBlueprint.analyze(cable, 0, raw);
            require(!analysis.blocking && raw.equals(original), "native ghost analysis preserves its caller source");
            NBTTagCompound canonicalExtra = analysis.payload()
                .getCompoundTag("extra:2");
            require(
                canonicalExtra.getCompoundTag("craftingGrid")
                    .hasKey("#" + input, 10)
                    && canonicalExtra.getCompoundTag("outputList")
                        .hasKey("#" + output, 10)
                    && canonicalExtra.getInteger("activePage") == capability.pages - 1,
                "actual high input/output ghosts and final page survive canonicalization");
            require(
                analysis.materials.size() == emptyAnalysis.materials.size(),
                "ghosts add no physical material rows");
            for (int i = 0; i < analysis.materials.size(); i++) require(
                ItemStack.areItemStacksEqual(analysis.materials.get(i), emptyAnalysis.materials.get(i)),
                "ghosts add no physical item quantities");
            for (HologramAeBlueprint.Slot slot : analysis.slots) {
                if (slot.id.equals("extra:2/craftingGrid/#" + input) || slot.id.equals("extra:2/outputList/#" + output))
                    require(slot.kind.equals("ghost") && slot.enabled, "last effective slots remain editable ghosts");
                if (slot.id
                    .equals("extra:2/" + (inverted ? "craftingGrid" : "outputList") + "/#" + (expectedAllocation / 4)))
                    require(!slot.enabled, "first narrow-side hidden slot cannot accept a new marker");
            }
        }
    }

    private static void eio(boolean expectedModern) throws Exception {
        for (String simple : new String[] { "PrismaticNaquadah", "Fluxed", "Transcendent", "Cosmic" }) {
            String type = "crazypants.enderio.conduit.liquid." + simple + "EnderLiquidConduit";
            NBTTagCompound row = new NBTTagCompound();
            row.setString("conduitType", type);
            row.setTag("conduit", new NBTTagCompound());
            NBTTagCompound imported = bundle(row);
            if (!expectedModern) {
                NBTTagCompound original = (NBTTagCompound) imported.copy();
                boolean refused = false;
                try {
                    HologramEnderIoCapabilities.requireRepresentable(imported);
                } catch (IOException expected) {
                    refused = expected.getMessage()
                        .contains(type);
                }
                require(
                    refused && imported.equals(original),
                    "old target refuses exact new class without losing its row");
                continue;
            }
            IConduit source = (IConduit) Class.forName(type)
                .getConstructor()
                .newInstance();
            source.setConnectionMode(
                net.minecraftforge.common.util.ForgeDirection.NORTH,
                crazypants.enderio.conduit.ConnectionMode.INPUT);
            NBTTagCompound before = new NBTTagCompound();
            source.writeToNBT(before);
            NBTTagCompound captured = HologramEnderIoCapabilities.modernLiquidAdapter()
                .capture(source);
            NBTTagCompound after = new NBTTagCompound();
            source.writeToNBT(after);
            require(
                before.equals(after) && captured.equals(before),
                "modern provider preserves complete source writer state");
            row.setTag("conduit", captured);
            NBTTagCompound mixed = bundle(row), power = new NBTTagCompound();
            ConduitUtil.writeToNBT(new PowerConduit(), power);
            mixed.getTagList("conduits", 10)
                .appendTag(power);
            HologramEnderIoCapabilities.requireRepresentable(mixed);
            TileEntity detached = newBundle();
            detached.readFromNBT((NBTTagCompound) mixed.copy());
            require(
                ((java.util.Collection<?>) detached.getClass()
                    .getMethod("getConduits")
                    .invoke(detached)).size() == 2,
                "mixed native bundle retains fluid and power rows");
            NBTTagCompound recaptured = HologramNativeCapture.capture(detached);
            require(
                recaptured.getTagList("conduits", 10)
                    .tagCount() == 2,
                "single capture boundary retains all mixed rows");
            boolean exact = false;
            for (int i = 0; i < 2; i++) {
                NBTTagCompound retained = recaptured.getTagList("conduits", 10)
                    .getCompoundTagAt(i);
                if (retained.getString("conduitType")
                    .equals(type))
                    exact = retained.getCompoundTag("conduit")
                        .equals(captured);
            }
            require(exact, "modern exact identity and complete body survive mixed native roundtrip");
        }
        require(
            !HologramEnderIoCapabilities.modernLiquidAdapter()
                .supports(new PowerConduit()),
            "modern liquid provider does not broaden unrelated type admission");
    }

    private static NBTTagCompound bundle(NBTTagCompound row) throws ReflectiveOperationException {
        TileEntity empty = newBundle();
        NBTTagCompound data = new NBTTagCompound();
        empty.writeToNBT(data);
        NBTTagList rows = new NBTTagList();
        rows.appendTag(row.copy());
        data.setTag("conduits", rows);
        return data;
    }

    private static TileEntity newBundle() throws ReflectiveOperationException {
        return (TileEntity) Class.forName("crazypants.enderio.conduit.TileConduitBundle")
            .getConstructor()
            .newInstance();
    }

    private static void ghost(NBTTagCompound extra, String root, int index, ItemStack item) {
        NBTTagCompound slots = new NBTTagCompound(), stack = new NBTTagCompound();
        appeng.util.Platform.writeStackNBT(AEItemStack.create(item), stack, true);
        slots.setTag("#" + index, stack);
        extra.setTag(root, slots);
    }

    private static NBTTagCompound cable(Block cable, ItemStack definition, NBTTagCompound extra)
        throws ReflectiveOperationException {
        NBTTagCompound raw = HologramNativeCompatibilityFixture.seed(cable, 0, 64, 0);
        raw.setTag("def:2", definition.writeToNBT(new NBTTagCompound()));
        raw.setTag("extra:2", extra);
        return raw;
    }

    private static Block block(String type) {
        for (Object value : Block.blockRegistry) if (value instanceof Block && value.getClass()
            .getName()
            .equals(type)) return (Block) value;
        throw new AssertionError("Missing native block " + type);
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }
}
