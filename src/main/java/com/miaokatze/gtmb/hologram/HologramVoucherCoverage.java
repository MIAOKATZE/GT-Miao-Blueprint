package com.miaokatze.gtmb.hologram;

import java.io.IOException;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Descriptive saved-file marker, never an authorization to waive a future material transaction. */
final class HologramVoucherCoverage {

    private HologramVoucherCoverage() {}

    static final class Builder {

        final HologramPresets.Preset source;
        final long rules = DecorationVoucherConfig.revision();
        int cursor, occupied, nativeChecks;
        final java.util.Map<java.util.List<Object>, Boolean> checked = new java.util.HashMap<>();
        boolean covered = true;

        Builder(HologramPresets.Preset source) {
            this.source = source;
        }

        boolean step() {
            if (cursor >= source.cells.size() || !covered) return true;
            HologramPresets.Cell cell = source.cells.get(cursor++);
            if (cell.excluded || "minecraft:air".equals(cell.block)) return cursor >= source.cells.size();
            occupied++;
            try {
                java.util.List<Object> identity = java.util.Arrays
                    .asList(cell.block, cell.item, cell.metadata, cell.damage, cell.tileNbt, cell.itemNbt);
                Boolean previous = checked.get(identity);
                if (previous == null) {
                    nativeChecks++;
                    covered = cellCovered(cell);
                    checked.put(identity, covered);
                } else covered = previous;
            } catch (IOException | RuntimeException unavailable) {
                covered = false;
            }
            return cursor >= source.cells.size() || !covered;
        }

        boolean result() {
            return source.schema >= 2 && covered && occupied > 0 && rules == DecorationVoucherConfig.revision();
        }
    }

    static boolean compute(HologramPresets.Preset preset) {
        Builder builder = new Builder(preset);
        while (!builder.step()) {
            if (Thread.currentThread()
                .isInterrupted()) return false;
        }
        return builder.result();
    }

    private static boolean cellCovered(HologramPresets.Cell cell) throws IOException {
        Block block = cell.block();
        ItemStack shell = cell.stack();
        if (block == null || block == Blocks.air || shell == null) return false;
        NBTTagCompound tile = HologramBlueprintNbt.decode(cell.tileNbt),
            item = HologramBlueprintNbt.decode(cell.itemNbt);
        if (item != null) shell.setTagCompound((NBTTagCompound) item.copy());
        if (HologramRecovery.hasSeal(shell)) return false;
        HologramBlueprintResources.Plan resources = HologramBlueprintResources.plan(tile)
            .merge(HologramBlueprintResources.plan(item));
        if (!resources.items.isEmpty() || !resources.fluids.isEmpty()
            || !resources.essentia.isEmpty()
            || resources.energy.signum() != 0
            || !HologramBlueprintCovers.materials(cell.tileNbt)
                .isEmpty())
            return false;
        HologramEnergyPayload.Prepared cache = HologramEnergyPayload.prepare(block, cell.metadata, tile, item);
        if (!cache.demand.empty() || !cache.warnings.isEmpty()) return false;
        if (HologramDecorMaterials.supports(block, tile)) {
            HologramDecorMaterials.Descriptor descriptor = HologramDecorMaterials.describe(block, cell.metadata, tile);
            return HologramDecorationVoucher.hasCovered(block, descriptor)
                && HologramDecorationVoucher.payable(block, descriptor).costs.isEmpty();
        }
        if (!HologramDecorationVoucher.covered(block, shell)) return false;
        if (!HologramGtswnPlacementMaterials.extraMaterials(block, tile)
            .isEmpty()) return false;
        if (HologramNativePlacement.supported(block)) {
            java.util.List<ItemStack> nativeItems = HologramNativePlacement.materials(block, cell.metadata, tile);
            if (!HologramNativePlacement.additionalMaterials(block, nativeItems, shell)
                .isEmpty()) return false;
        }
        return true;
    }

    static void begin(HologramSession s, HologramPresets.Preset preset, String operation, boolean update, boolean ae,
        boolean nativeTemplate) throws IOException {
        if (s.voucherCoverageTask != null) throw new IOException("正在核对蓝图抵用范围，请稍候");
        s.voucherCoverageTask = new Task(s, preset, operation, update, ae, nativeTemplate);
        s.blueprintSaveReceipt = s.voucherCoverageTask.receipt;
        s.blueprintSaveRevision = s.planRevision;
        s.voucherCoverageTask.tick();
    }

    static final class Task {

        final HologramSession s;
        final HologramPresets.Preset preset;
        final Builder builder;
        final String operation, receipt = "coverage_" + java.util.UUID.randomUUID();
        final long revision;
        final boolean update, ae, nativeTemplate;

        Task(HologramSession s, HologramPresets.Preset preset, String operation, boolean update, boolean ae,
            boolean nativeTemplate) {
            this.s = s;
            this.preset = preset;
            this.operation = operation;
            this.update = update;
            this.ae = ae;
            this.nativeTemplate = nativeTemplate;
            revision = s.planRevision;
            builder = new Builder(preset);
        }

        void tick() {
            if (s.voucherCoverageTask != this) return;
            if (!receipt.equals(s.blueprintSaveReceipt) || revision != s.planRevision
                || !HologramSessionPolicy.saveValid(s)) {
                s.voucherCoverageTask = null;
                if (receipt.equals(s.blueprintSaveReceipt)) s.blueprintSaveReceipt = "";
                return;
            }
            long deadline = HologramWorkBudget.deadline(2_000_000L);
            int nativeBefore = builder.nativeChecks;
            for (int units = 0; units < 512 && builder.nativeChecks - nativeBefore < 8
                && System.nanoTime() < deadline; units++) {
                if (!builder.step()) continue;
                preset.fullyVoucherCovered = builder.result();
                s.voucherCoverageTask = null;
                s.blueprintSaveReceipt = "";
                try {
                    HologramLibraryWorkflow.finishReviewedArtifact(s, preset, operation, update, ae, nativeTemplate);
                } catch (IOException | RuntimeException failure) {
                    s.blueprintDirty = true;
                    s.status = "蓝图保存失败：" + HologramAePolicy.fallback(failure.getMessage());
                    HologramSnapshots.send(s);
                }
                return;
            }
            s.status = "正在核对蓝图抵用范围 " + builder.cursor + " / " + preset.cells.size();
            HologramSnapshots.sendProgress(s);
        }
    }
}
