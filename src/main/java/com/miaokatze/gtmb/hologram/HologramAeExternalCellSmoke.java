package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.security.PlayerSource;
import appeng.api.storage.IMEInventoryHandler;
import appeng.api.storage.StorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.tile.AEBaseInvTile;
import appeng.util.item.AEItemStack;

/** Strict coexistence-only test against a genuine registered GTIT Infinity Cell and its existing external bucket. */
public final class HologramAeExternalCellSmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-AE-EXTERNAL-CELL] ";

    private HologramAeExternalCellSmoke() {}

    @SuppressWarnings("unchecked")
    public static void run(WorldServer world, EntityPlayerMP actor) throws Exception {
        if (world == null || !world.getWorldInfo()
            .getWorldName()
            .startsWith("hologram-smoke-") || actor.worldObj != world)
            throw new IllegalArgumentException("Isolated smoke world and genuine server actor required");
        Item external = null;
        for (Object entry : Item.itemRegistry) if (entry instanceof Item && entry.getClass()
            .getName()
            .equals("com.miaokatze.gtit.common.items.infinitycell.ItemInfinityStorageCell")) {
                external = (Item) entry;
                break;
            }
        check(external != null, "genuine_registered_gtit_infinity_cell_required");
        ItemStack sourceCell = new ItemStack(external);
        IMEInventoryHandler<IAEItemStack> handler = (IMEInventoryHandler<IAEItemStack>) AEApi.instance()
            .registries()
            .cell()
            .getCellInventory(sourceCell, null, StorageChannel.ITEMS);
        check(
            handler != null && handler.getClass()
                .getName()
                .startsWith("com.miaokatze.gtit.common.items.infinitycell."),
            "genuine_external_native_handler");
        check(
            handler.injectItems(
                AEItemStack.create(new ItemStack(Items.diamond, 73)),
                Actionable.MODULATE,
                new PlayerSource(actor, null)) == null,
            "official_external_bucket_injection_73_diamonds");
        check(
            sourceCell.hasTagCompound() && !sourceCell.getTagCompound()
                .getString("diskuuid")
                .isEmpty(),
            "native_handler_allocated_real_external_pointer");
        Block drive = AEApi.instance()
            .definitions()
            .blocks()
            .drive()
            .maybeBlock()
            .get();
        int x = 512, y = 88, z = 192;
        world.getChunkFromBlockCoords(x, z);
        check(world.isAirBlock(x, y, z) && world.setBlock(x, y, z, drive, 0, 3), "fresh_external_source_drive");
        TileEntity tile = world.getTileEntity(x, y, z);
        ((AEBaseInvTile) tile).getInternalInventory()
            .setInventorySlotContents(0, sourceCell);
        NBTTagCompound source = HologramNativeCapture.capture(tile);

        Class<?> managerClass = Class.forName("com.miaokatze.gtit.common.items.infinitycell.StorageManager");
        Object manager = managerClass.getMethod("getInstance")
            .invoke(null);
        check(manager != null, "actual_world_external_storage_manager_initialized");
        Method save = managerClass.getMethod("writeToNBT", NBTTagCompound.class);
        Field disksField = managerClass.getDeclaredField("disks");
        disksField.setAccessible(true);
        Map<Object, Object> originalMap = (Map<Object, Object>) disksField.get(manager);
        check(!originalMap.isEmpty(), "real_external_bucket_exists_before_analysis");
        NBTTagCompound bucketBefore = new NBTTagCompound();
        save.invoke(manager, bucketBefore);
        byte[] cellBefore = bytes(sourceCell), nativeBefore = bytes(source);
        ReadObserver observer = new ReadObserver(originalMap);
        HologramAeBlueprint.Analysis first;
        // Observe existing native storage; do not replace any bucket, registry entry, cell class or contents.
        // AE blueprint analysis must not invoke the addon's bucket-resolution path, even once.
        disksField.set(manager, observer);
        try {
            first = HologramAeBlueprint.analyze(drive, 0, source);
            check(first.blocking && issue(first, "unsupported_cell"), "unknown_external_cell_blocks_restoration");
            ItemStack typeOnly = slot(first, "inv/item0").stack();
            check(
                typeOnly.isItemEqual(sourceCell) && !typeOnly.hasTagCompound(),
                "unknown_cell_retains_blank_type_sample_only");
            HologramAeBlueprint.Analysis second = HologramAeBlueprint.analyze(drive, 0, first.payload());
            check(
                second.blocking && issue(second, "unsupported_cell"),
                "external_blocker_persists_after_pointer_omission");
            check(
                HologramAeBlueprint.updateSlot(drive, 0, second.payload(), "inv/item1", null).blocking,
                "unrelated_clear_never_authorizes_unknown_external_cell");
            expectDenied(
                () -> HologramAeBlueprint
                    .createNative(drive, 0, second.payload(), HologramResourceWorld.create(drive, 0)),
                "unknown_cell_blocker_checked_before_native_inventory_loading");
            HologramAeBlueprint.Analysis cleared = HologramAeBlueprint
                .updateSlot(drive, 0, second.payload(), "inv/item0", null);
            check(!cleared.blocking && cleared.materials.isEmpty(), "explicit_clear_drops_only_blueprint_cell_marker");
            check(
                observer.reads == 0 && observer.writes == 0,
                "analysis_and_clear_never_read_or_write_external_bucket_map");
        } finally {
            disksField.set(manager, originalMap);
        }
        NBTTagCompound bucketAfter = new NBTTagCompound();
        save.invoke(manager, bucketAfter);
        check(
            Arrays.equals(bytes(bucketBefore), bytes(bucketAfter)),
            "entire_real_external_storage_serialization_unchanged");
        check(
            Arrays.equals(cellBefore, bytes(sourceCell))
                && Arrays.equals(nativeBefore, bytes(HologramNativeCapture.capture(tile))),
            "live_external_source_cell_and_pointer_unchanged");
        // A separate post-observation native read proves the preexisting source bucket still contains its stock.
        appeng.api.storage.data.IItemList<IAEItemStack> remaining = AEApi.instance()
            .storage()
            .createItemList();
        handler.getAvailableItems(remaining, 0);
        IAEItemStack diamonds = remaining.findPrecise(AEItemStack.create(new ItemStack(Items.diamond)));
        check(diamonds != null && diamonds.getStackSize() == 73L, "actual_external_bucket_retains_all_73_diamonds");
        System.out.println(PREFIX + "complete; actual GTIT registered source and zero observed bucket resolutions");
    }

    private static final class ReadObserver extends AbstractMap<Object, Object> {

        private final Map<Object, Object> actual;
        int reads, writes;

        ReadObserver(Map<Object, Object> actual) {
            this.actual = actual;
        }

        @Override
        public Object get(Object key) {
            reads++;
            return actual.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            reads++;
            return actual.containsKey(key);
        }

        @Override
        public Set<Entry<Object, Object>> entrySet() {
            reads++;
            return actual.entrySet();
        }

        @Override
        public Object put(Object key, Object value) {
            writes++;
            return actual.put(key, value);
        }

        @Override
        public Object remove(Object key) {
            writes++;
            return actual.remove(key);
        }

        @Override
        public void clear() {
            writes++;
            actual.clear();
        }
    }

    private static HologramAeBlueprint.Slot slot(HologramAeBlueprint.Analysis analysis, String id) {
        for (HologramAeBlueprint.Slot slot : analysis.slots) if (slot.id.equals(id)) return slot;
        throw new AssertionError("Missing native slot " + id);
    }

    private static boolean issue(HologramAeBlueprint.Analysis analysis, String code) {
        for (HologramAeBlueprint.Issue issue : analysis.issues) if (issue.code.equals(code)) return true;
        return false;
    }

    private static byte[] bytes(ItemStack stack) throws IOException {
        NBTTagCompound tag = new NBTTagCompound();
        stack.writeToNBT(tag);
        return bytes(tag);
    }

    private static byte[] bytes(NBTTagCompound tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            CompressedStreamTools.write(tag, output);
        }
        return bytes.toByteArray();
    }

    private interface Action {

        void run() throws Exception;
    }

    private static void expectDenied(Action action, String message) throws Exception {
        boolean denied = false;
        try {
            action.run();
        } catch (IOException expected) {
            denied = true;
        }
        check(denied, message);
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(PREFIX + message);
        System.out.println(PREFIX + "PASS " + message);
    }
}
