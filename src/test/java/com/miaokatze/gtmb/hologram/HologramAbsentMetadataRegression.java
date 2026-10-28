package com.miaokatze.gtmb.hologram;

import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Absent scopes avoid empty certificates while explicit payloads and native identity boundaries remain strict. */
public final class HologramAbsentMetadataRegression {

    private HologramAbsentMetadataRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAbsentMetadataRegression.class, args)) return;
        run();
        System.out.println("HologramAbsentMetadataRegression passed");
    }

    static void run() throws Exception {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.schema = 2;
        for (int index = 0; index < 64; index++)
            source.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), index, 0, 0, -1, false));
        NBTTagCompound cold = report(source, true);
        SimpleAssert.eq(cold, report(source, true), "cold and warm absent-scope reports are identical");
        SimpleAssert.eq(cold, report(source, false), "cache switches preserve complete absent-scope reports");
        SimpleAssert
            .eq(0, cold.getInteger("requiredStages"), "ordinary absent scopes require no invented review stages");
        for (HologramPresets.Cell cell : source.cells) {
            SimpleAssert.eq(0L, cell.resourceTileRows, "absent tile scopes still publish exact zero resource counts");
            SimpleAssert.eq(0L, cell.resourceItemRows, "absent item scopes still publish exact zero resource counts");
        }
        String explicitEmpty = HologramBlueprintNbt.encodeCaptured(new NBTTagCompound());
        source.cells.get(0).tileNbt = explicitEmpty;
        source.cells.get(1).itemNbt = explicitEmpty;
        SimpleAssert
            .eq(cold, report(source, true), "explicit empty payloads remain semantically empty after strict review");
        SimpleAssert.that(
            source.analysisDocument.computed.getOrDefault(HologramSegmentContract.Domain.REPORT, 0L) > 0,
            "explicit empty payloads retain their report certificate work");
        NBTTagCompound changed = new NBTTagCompound();
        changed.setInteger("futureSetting", 7);
        source.cells.get(2).tileNbt = HologramBlueprintNbt.encodeCaptured(changed);
        NBTTagCompound withPayload = report(source, true);
        SimpleAssert.eq(
            withPayload,
            report(source, false),
            "filling an absent scope runs the same strict report with caches disabled");
        SimpleAssert.that(
            (withPayload.getInteger("requiredStages") & (1 << 4)) != 0,
            "new payload fields require their actual NBT review stage");
        SimpleAssert.that(
            withPayload.getTagList("nbtKeys", 8)
                .tagCount() > 0,
            "new payload fields cannot inherit a cached empty key report");
        source.cells.get(2).tileNbt = null;
        SimpleAssert.eq(cold, report(source, true), "removing payloads removes their actual field obligations");
        net.minecraft.block.Block ae = DecorationVoucherRegression
            .registeredFixture("gtmb:absent_metadata_ae_fixture", new appeng.block.GtmbAeStaticFixture());
        source.cells.get(3).block = net.minecraft.block.Block.blockRegistry.getNameForObject(ae);
        NBTTagCompound nativeBoundary = report(source, true);
        long nativeRelations = source.analysisDocument.computed
            .getOrDefault(HologramSegmentContract.Domain.RELATIONS, 0L);
        SimpleAssert.eq(
            nativeBoundary,
            report(source, false),
            "changing a plain shell into an AE shell invalidates its empty facts");
        SimpleAssert
            .eq(1, nativeBoundary.getInteger("minimumLevel"), "AE identity review remains mandatory without tile NBT");
        SimpleAssert.that(
            nativeRelations > 0,
            "native AE membership classification is not treated as an ordinary absent scope");
        source.cells.get(3).block = "minecraft:stone";
        source.cells.get(4).block = "gtmb:missing_absent_metadata_fixture";
        NBTTagCompound missing = report(source, true);
        long unknownReports = source.analysisDocument.computed.getOrDefault(HologramSegmentContract.Domain.REPORT, 0L);
        SimpleAssert
            .eq(missing, report(source, false), "missing blocks keep their original strict classification path");
        SimpleAssert.that(unknownReports > 0, "unknown block identity does not receive an absent-field shortcut");
    }

    private static NBTTagCompound report(HologramPresets.Preset source, boolean cache) throws Exception {
        try (HologramSnapshotDataContext context = new HologramSnapshotDataContext()) {
            for (HologramPresets.Cell cell : source.cells) context.freezeBlock(cell);
            HologramSnapshotMetadataSource.enterDetached(context);
            try (HologramAnalysisFacts facts = new HologramAnalysisFacts(cache)) {
                NBTTagCompound result = HologramBlueprintProcessing
                    .state(source, HologramBlueprintProcessing.defaults("self", 0), 0, 100, 64, 200, facts);
                facts.publish();
                return result;
            } finally {
                HologramSnapshotMetadataSource.exitDetached();
            }
        }
    }
}
