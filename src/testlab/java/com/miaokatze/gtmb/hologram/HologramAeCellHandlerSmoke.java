package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import appeng.api.AEApi;
import appeng.api.storage.ICellHandler;
import appeng.api.storage.ICellRegistry;
import appeng.api.storage.StorageChannel;
import appeng.tile.AEBaseInvTile;
import appeng.tile.inventory.AppEngInternalInventory;

/** Native default-AE observer: delegate actual registered handlers, prove sanitizer never opens cell contents. */
public final class HologramAeCellHandlerSmoke {

    private static final String PREFIX = "[GTMB-HOLOGRAM-AE-CELL-HANDLERS] ";

    private HologramAeCellHandlerSmoke() {}

    @SuppressWarnings("unchecked")
    public static void run(EntityPlayerMP actor) throws Exception {
        if (actor == null) throw new IllegalArgumentException("Genuine FML server actor required");
        ICellRegistry registry = AEApi.instance()
            .registries()
            .cell();
        check(
            registry.getClass()
                .getName()
                .equals("appeng.core.features.registries.CellRegistry"),
            "pinned_official_registry");
        Field handlersField = registry.getClass()
            .getDeclaredField("handlers");
        handlersField.setAccessible(true);
        Field basicField = registry.getClass()
            .getDeclaredField("basicCellHandler");
        basicField.setAccessible(true);
        List<ICellHandler> actual = (List<ICellHandler>) handlersField.get(registry);
        List<ICellHandler> saved = new ArrayList<>(actual);
        ICellHandler basic = (ICellHandler) basicField.get(registry);
        int[] calls = { 0 };
        for (int i = 0; i < actual.size(); i++) actual.set(i, observe(actual.get(i), calls));
        basicField.set(registry, observe(basic, calls));
        try {
            ItemStack known = AEApi.instance()
                .definitions()
                .items()
                .cell1k()
                .maybeStack(1)
                .get();
            check(
                registry.getCellInventory(known.copy(), null, StorageChannel.ITEMS) != null && calls[0] > 0,
                "observer_calibrated_by_real_native_cell_handler_invocation");
            calls[0] = 0;
            Block drive = AEApi.instance()
                .definitions()
                .blocks()
                .drive()
                .maybeBlock()
                .get();
            ItemStack unknown = AEApi.instance()
                .definitions()
                .items()
                .spatialCell2()
                .maybeStack(1)
                .get();
            NBTTagCompound pointer = new NBTTagCompound();
            pointer.setInteger("StorageDim", actor.worldObj.provider.dimensionId);
            pointer.setString("diskuuid", "46b10c7b-7139-4c5a-bfd1-516bb7c6e36");
            unknown.setTagCompound(pointer);
            AEBaseInvTile nativeSource = (AEBaseInvTile) drive.createTileEntity(null, 0);
            AppEngInternalInventory inventory = (AppEngInternalInventory) nativeSource.getInternalInventory();
            inventory.setTileEntity(null); // This disposable provenance fixture does not touch a live source callback.
            inventory.setInventorySlotContents(0, unknown);
            NBTTagCompound raw = new NBTTagCompound();
            nativeSource.writeToNBT(raw);
            NBTTagCompound before = (NBTTagCompound) raw.copy();
            HologramAeBlueprint.Analysis first = HologramAeBlueprint.analyze(drive, 0, raw);
            check(
                !first.blocking && issue(first, "unsupported_cell", "warning", "inv/item0"),
                "unknown_native_external_pointer_type_is_explicitly_warned_and_omitted");
            check(
                !first.payload()
                    .getCompoundTag("inv")
                    .hasKey("item0") && first.materials.isEmpty()
                    && first.payload()
                        .toString()
                        .indexOf(pointer.getString("diskuuid")) < 0
                    && first.payload()
                        .toString()
                        .indexOf("StorageDim") < 0,
                "unknown_cell_and_external_pointer_are_absent_from_payload_and_materials");
            AEBaseInvTile restored = (AEBaseInvTile) HologramAeBlueprint
                .createNative(drive, 0, first.payload(), HologramResourceWorld.create(drive, 0));
            for (int i = 0; i < restored.getInternalInventory()
                .getSizeInventory(); i++)
                check(
                    restored.getInternalInventory()
                        .getStackInSlot(i) == null,
                    "actual_native_restoration_of_remaining_drive_has_no_unreviewed_cell_slot_" + i);

            // A missing/unreviewed cell may be omitted. Malformed physical records still require explicit repair.
            NBTTagCompound malformed = (NBTTagCompound) raw.copy();
            malformed.getCompoundTag("inv")
                .getCompoundTag("item0")
                .setString("Count", "invalid-physical-count");
            NBTTagCompound malformedBefore = (NBTTagCompound) malformed.copy();
            HologramAeBlueprint.Analysis blocked = HologramAeBlueprint.analyze(drive, 0, malformed);
            check(
                blocked.blocking && issue(blocked, "missing_physical_item", "blocker", "inv/item0"),
                "malformed_registered_physical_cell_record_remains_a_blocker");
            HologramAeBlueprint.Analysis repeated = HologramAeBlueprint.analyze(drive, 0, blocked.payload());
            check(
                repeated.blocking
                    && HologramAeBlueprint.updateSlot(drive, 0, repeated.payload(), "inv/item1", null).blocking,
                "malformed_physical_loss_survives_reanalysis_and_unrelated_slot_clear");
            boolean denied = false;
            try {
                HologramAeBlueprint.createNative(drive, 0, repeated.payload(), HologramResourceWorld.create(drive, 0));
            } catch (IOException expected) {
                denied = true;
            }
            check(denied, "malformed_physical_record_blocks_native_restore_before_any_cell_handler_opening");
            check(
                !HologramAeBlueprint.updateSlot(drive, 0, repeated.payload(), "inv/item0", null).blocking
                    && malformedBefore.equals(malformed),
                "only_exact_clear_repairs_malformed_physical_marker_without_mutating_source");
            HologramAeBlueprint.Analysis clear = HologramAeBlueprint
                .updateSlot(drive, 0, first.payload(), "inv/item0", null);
            check(!clear.blocking && clear.materials.isEmpty(), "native_slot_clear_resolves_only_blueprint_pointer");
            check(
                calls[0] == 0,
                "analysis_empty_factory_malformed_refusal_and_clear_never_invoke_registered_cell_inventory_handler");
            check(
                before.equals(raw) && unknown.getTagCompound()
                    .equals(pointer),
                "unknown_source_pointer_unchanged");
        } finally {
            actual.clear();
            actual.addAll(saved);
            basicField.set(registry, basic);
        }
        check(basicField.get(registry) == basic && actual.equals(saved), "actual_handler_registry_restored_in_finally");
        System.out.println(PREFIX + "complete; actual handlers delegated with zero inventory openings during analysis");
    }

    private static ICellHandler observe(ICellHandler delegate, int[] calls) {
        return (ICellHandler) Proxy.newProxyInstance(
            ICellHandler.class.getClassLoader(),
            new Class<?>[] { ICellHandler.class },
            (proxy, method, arguments) -> {
                if (method.getName()
                    .equals("getCellInventory")) calls[0]++;
                try {
                    return method.invoke(delegate, arguments);
                } catch (InvocationTargetException failed) {
                    throw failed.getCause();
                }
            });
    }

    private static boolean issue(HologramAeBlueprint.Analysis analysis, String code, String severity, String path) {
        for (HologramAeBlueprint.Issue issue : analysis.issues)
            if (issue.code.equals(code) && issue.severity.equals(severity) && issue.path.equals(path)) return true;
        return false;
    }

    private static void check(boolean passed, String message) {
        if (!passed) throw new AssertionError(PREFIX + message);
        System.out.println(PREFIX + "PASS " + message);
    }
}
