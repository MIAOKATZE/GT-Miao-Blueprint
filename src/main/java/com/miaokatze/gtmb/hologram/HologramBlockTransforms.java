package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/** Detached blueprint transforms. Adapters own named mod schemas; arbitrary NBT keys are never guessed. */
final class HologramBlockTransforms {

    interface Adapter {

        boolean accepts(Data data);

        void turn(Data data, boolean vertical) throws IOException;
    }

    static final class Data {

        final Block block;
        final String sourceClass;
        int metadata;
        NBTTagCompound tile;
        NBTTagCompound item;
        Map<String, int[]> littleReferences;
        int x, y, z;
        boolean authoring;

        Data(Block block, String sourceClass, int metadata, NBTTagCompound tile, NBTTagCompound item) {
            this.block = block;
            this.sourceClass = sourceClass == null ? "" : sourceClass;
            this.metadata = metadata;
            this.tile = tile == null ? null : (NBTTagCompound) tile.copy();
            this.item = item == null ? null : (NBTTagCompound) item.copy();
        }

        boolean type(String prefix) {
            // Cell.sourceClass is the declaring multiblock, not the target tile. Do not classify AE/FMP cells as GT.
            if (sourceClass.equals(prefix)) return true;
            for (Class<?> type = block == null ? null : block.getClass(); type != null; type = type.getSuperclass())
                if (type.getName()
                    .startsWith(prefix)) return true;
            return false;
        }

        boolean id(String id) {
            return tile != null && id.equals(tile.getString("id"));
        }
    }

    static final class Result {

        final int metadata;
        final String tileNbt;
        final String itemNbt;

        Result(int metadata, String tileNbt, String itemNbt) {
            this.metadata = metadata;
            this.tileNbt = tileNbt;
            this.itemNbt = itemNbt;
        }
    }

    private static final List<Adapter> ADAPTERS = new ArrayList<>();
    static {
        HologramBlockTransformAdapters.register();
    }

    private HologramBlockTransforms() {}

    static synchronized void register(Adapter adapter) {
        if (adapter == null) throw new IllegalArgumentException("adapter");
        ADAPTERS.add(adapter);
    }

    static synchronized void registerFirst(Adapter adapter) {
        ADAPTERS.add(0, adapter);
    }

    static synchronized void unregister(Adapter adapter) {
        ADAPTERS.remove(adapter);
    }

    static Data turn(Data original, boolean vertical) throws IOException {
        Data data = new Data(original.block, original.sourceClass, original.metadata, original.tile, original.item);
        data.littleReferences = original.littleReferences;
        data.x = original.x;
        data.y = original.y;
        data.z = original.z;
        data.authoring = original.authoring;
        List<Adapter> adapters;
        synchronized (HologramBlockTransforms.class) {
            adapters = new ArrayList<>(ADAPTERS);
        }
        boolean handled = false;
        for (Adapter adapter : adapters) {
            try {
                if (adapter.accepts(data)) {
                    adapter.turn(data, vertical);
                    handled = true;
                    break;
                }
            } catch (RuntimeException | LinkageError invalid) {
                throw new IOException(
                    "Block rotation adapter failed: " + adapter.getClass()
                        .getName(),
                    invalid);
            }
        }
        if (!handled && !turnVanillaMetadata(data, vertical)
            && data.authoring
            && (data.tile != null && !data.tile.hasNoTags() || data.item != null && !data.item.hasNoTags()))
            throw new IOException("该方块的设备或物品 NBT 没有已适配的转向规则");
        return data;
    }

    private static boolean turnVanillaMetadata(Data data, boolean vertical) {
        if (data.block != null && data.block.getClass()
            .getName()
            .startsWith("net.minecraft.block.")) {
            com.miaokatze.gtmb.api.CutTransforms.State state = new com.miaokatze.gtmb.api.CutTransforms.State(
                data.block,
                data.metadata,
                data.tile,
                data.x,
                data.y,
                data.z,
                data.x,
                data.y,
                data.z);
            if (HologramCutTransformMetadata.apply(
                state,
                new com.miaokatze.gtmb.api.CutTransforms.Transform(vertical ? 0 : 1, vertical ? 1 : 0, 0, 0))) {
                data.metadata = state.metadata;
                return true;
            }
        }
        return false;
    }

    static Result turn(Block block, String sourceClass, int metadata, String tile, String item, boolean vertical)
        throws IOException {
        return turn(block, sourceClass, metadata, tile, item, vertical, null, 0, 0, 0);
    }

    static Result turn(Block block, String sourceClass, int metadata, String tile, String item, boolean vertical,
        Map<String, int[]> references, int x, int y, int z) throws IOException {
        return turn(block, sourceClass, metadata, tile, item, vertical, references, x, y, z, false);
    }

    private static Result turn(Block block, String sourceClass, int metadata, String tile, String item,
        boolean vertical, Map<String, int[]> references, int x, int y, int z, boolean authoring) throws IOException {
        NBTTagCompound tileData = HologramBlueprintNbt.decode(tile), itemData = HologramBlueprintNbt.decode(item);
        Data original = new Data(block, sourceClass, metadata, tileData, itemData);
        original.littleReferences = references;
        original.x = x;
        original.y = y;
        original.z = z;
        original.authoring = authoring;
        Data data = turn(original, vertical);
        if (HologramAeBlueprint.supports(block, data.tile)) HologramAeBlueprint.rotateUnresolved(data.tile, vertical);
        // Preserve unchanged payload byte-for-byte, including every inventory/configuration tag.
        return new Result(
            data.metadata,
            same(tileData, data.tile) ? tile
                : HologramAeBlueprint.supports(block, data.tile)
                    ? HologramBlueprintNbt.encodeCanonical(block, data.tile)
                    : HologramBlueprintNbt.encodeCaptured(data.tile),
            same(itemData, data.item) ? item
                : HologramAeBlueprint.supports(block) ? HologramBlueprintNbt.encodeCanonical(data.item)
                    : HologramBlueprintNbt.encodeCaptured(data.item));
    }

    static void turn(HologramPresets.Cell cell, boolean vertical) throws IOException {
        turn(cell, vertical, null);
    }

    static boolean isPipe(ItemStack stack) {
        return stack != null && stack.getItem() instanceof gregtech.common.blocks.ItemMachines
            && gregtech.common.blocks.ItemMachines
                .getMetaTileEntity(stack) instanceof gregtech.api.metatileentity.MetaPipeEntity;
    }

    static void turn(HologramPresets.Cell cell, boolean vertical, Map<String, int[]> references) throws IOException {
        turn(cell, vertical, references, false);
    }

    static void turn(HologramPresets.Cell cell, boolean vertical, Map<String, int[]> references, boolean authoring)
        throws IOException {
        String type = cell.stack() != null && cell.stack()
            .getItem() instanceof gregtech.common.blocks.ItemMachines ? "gregtech." : "";
        Result result = turn(
            cell.block(),
            type,
            cell.metadata,
            cell.tileNbt,
            cell.itemNbt,
            vertical,
            references,
            cell.x,
            cell.y,
            cell.z,
            authoring);
        cell.metadata = result.metadata;
        cell.tileNbt = result.tileNbt;
        cell.itemNbt = result.itemNbt;
    }

    static void turn(HologramCapture.Cell cell, boolean vertical) throws IOException {
        turn(cell, vertical, null, cell.x, cell.y, cell.z);
    }

    static void turn(HologramCapture.Cell cell, boolean vertical, Map<String, int[]> references, int oldX, int oldY,
        int oldZ) throws IOException {
        turn(cell, vertical, references, oldX, oldY, oldZ, false);
    }

    static void turn(HologramCapture.Cell cell, boolean vertical, Map<String, int[]> references, int oldX, int oldY,
        int oldZ, boolean authoring) throws IOException {
        Result result = turn(
            cell.block,
            cell.sourceClass,
            cell.meta,
            cell.tileNbt,
            cell.itemNbt,
            vertical,
            references,
            oldX,
            oldY,
            oldZ,
            authoring);
        cell.meta = result.metadata;
        cell.tileNbt = result.tileNbt;
        cell.itemNbt = result.itemNbt;
    }

    private static boolean same(NBTTagCompound a, NBTTagCompound b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Native drop tags can encode dimensions and part geometry; choose a payable sample from the turned payload. */
    static ItemStack nativeSample(Block block, int metadata, String tileNbt, ItemStack previous) throws IOException {
        if (!HologramNativePlacement.supported(block)) return null;
        List<ItemStack> costs = HologramNativePlacement
            .materials(block, metadata, HologramBlueprintNbt.decode(tileNbt));
        if (costs.isEmpty())
            return HologramNativePlacement.sample(block, metadata, HologramBlueprintNbt.decode(tileNbt));
        for (ItemStack cost : costs)
            if (previous != null && cost.isItemEqual(previous) && ItemStack.areItemStackTagsEqual(cost, previous))
                return previous.copy();
        ItemStack selected = null;
        for (ItemStack cost : costs) if (previous != null && cost.isItemEqual(previous)) {
            selected = cost;
            break;
        }
        if (selected == null) selected = costs.get(0);
        ItemStack result = selected.copy();
        result.stackSize = 1;
        if (previous != null && !sameResources(
            HologramBlueprintResources.plan(previous.getTagCompound()),
            HologramBlueprintResources.plan(result.getTagCompound())))
            throw new IOException("Native rotation sample would change retained inventory, fluid or energy costs");
        return result;
    }

    private static boolean sameResources(HologramBlueprintResources.Plan before,
        HologramBlueprintResources.Plan after) {
        if (!before.energy.equals(after.energy)) return false;
        List<ItemStack> remaining = new ArrayList<>();
        for (ItemStack stack : after.items) remaining.add(stack.copy());
        for (ItemStack stack : before.items) {
            int amount = stack.stackSize;
            for (ItemStack other : remaining)
                if (stack.isItemEqual(other) && ItemStack.areItemStackTagsEqual(stack, other)) {
                    int paid = Math.min(amount, other.stackSize);
                    amount -= paid;
                    other.stackSize -= paid;
                }
            if (amount != 0) return false;
        }
        for (ItemStack stack : remaining) if (stack.stackSize != 0) return false;
        long[] fluids = new long[after.fluids.size()];
        for (int i = 0; i < fluids.length; i++) fluids[i] = after.fluids.get(i).amount;
        for (HologramBlueprintResources.FluidCost fluid : before.fluids) {
            long amount = fluid.amount;
            for (int i = 0; i < fluids.length; i++) if (fluid.stack.isFluidEqual(after.fluids.get(i).stack)) {
                long paid = Math.min(amount, fluids[i]);
                amount -= paid;
                fluids[i] -= paid;
            }
            if (amount != 0) return false;
        }
        for (long amount : fluids) if (amount != 0) return false;
        return true;
    }

    static void nativeSample(HologramPresets.Cell cell) throws IOException {
        ItemStack previous = cell.stack();
        if (previous != null)
            previous.setTagCompound(HologramBlueprintNbt.nativePayload(HologramBlueprintNbt.decode(cell.itemNbt)));
        ItemStack sample = nativeSample(cell.block(), cell.metadata, cell.tileNbt, previous);
        if (sample == null) return;
        cell.item = String.valueOf(Item.itemRegistry.getNameForObject(sample.getItem()));
        cell.damage = sample.getItemDamage();
        cell.itemNbt = HologramAeBlueprint.supports(cell.block())
            ? HologramBlueprintNbt.encodeCanonical(sample.getTagCompound())
            : HologramBlueprintNbt.encodeCaptured(sample.getTagCompound());
    }

    static ItemStack nativeSample(HologramCapture.Cell cell, ItemStack previous) throws IOException {
        ItemStack sample = nativeSample(cell.block, cell.meta, cell.tileNbt, previous);
        if (sample == null) return null;
        cell.blueprintItem = String.valueOf(Item.itemRegistry.getNameForObject(sample.getItem()));
        cell.blueprintDamage = sample.getItemDamage();
        String encoded = HologramAeBlueprint.supports(cell.block)
            ? HologramBlueprintNbt.encodeCanonical(sample.getTagCompound())
            : HologramBlueprintNbt.encodeCaptured(sample.getTagCompound());
        cell.itemNbt = encoded == null ? "" : encoded;
        cell.resourceFingerprint = "";
        cell.resourcePlanKey = null;
        cell.resourceError = "";
        cell.resourcePlan = null;
        return sample;
    }
}
