// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.compat.storagedrawers.common;

import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import com.miaokatze.gtmb.api.GTMBExtensions;
import com.miaokatze.gtmb.api.NativeCapture;
import com.miaokatze.gtmb.api.ResourceContainers;
import com.miaokatze.gtmb.compat.internal.NativeFields;
import com.miaokatze.gtmb.hologram.HologramNativePlacement;
import com.miaokatze.miaokatzecore.api.items.NativeItemIds;

/** Reviewed ordinary 2.2.31 drawers; compacting, framed and unlimited storage need their own contracts. */
public final class StorageDrawersCompatibility {

    private static final String PREFIX = "com.jaquadro.minecraft.storagedrawers.";
    private static final String TILE = PREFIX + "block.tile.TileEntityDrawersStandard";
    private static final Set<String> IDS = new HashSet<>(
        Arrays.asList(
            "StorageDrawers:tileDrawersStandard",
            "StorageDrawers:fullDrawers1",
            "StorageDrawers:fullDrawers2",
            "StorageDrawers:fullDrawers4",
            "StorageDrawers:halfDrawers2",
            "StorageDrawers:halfDrawers4"));
    private static final String[] UPGRADE_FIELDS = { "upgrade", "upgradeStatus", "upgradeLock", "upgradeVoid",
        "upgradeRedstone", "upgradeDowngrade" };

    private StorageDrawersCompatibility() {}

    public static List<GTMBExtensions.Registration> install() {
        List<GTMBExtensions.Registration> installed = new ArrayList<>();
        try {
            installed.add(NativeCapture.register("gtmb:drawers_capture", new NativeCapture.Adapter() {

                public boolean supports(TileEntity tile) {
                    return tile.getClass()
                        .getName()
                        .equals(TILE);
                }

                public NBTTagCompound capture(TileEntity tile) throws Exception {
                    return capturePure(tile);
                }
            }));
            installed.add(ResourceContainers.registerRoot("gtmb:drawers_slots", context -> {
                if (!IDS.contains(context.nativeTileId) || !context.selectedGroup("Slots")) return null;
                validate(context.root());
                return new ResourceContainers.RootClaim(
                    Collections.singleton("Slots"),
                    slotBill(
                        context.root()
                            .getTagList("Slots", 10)),
                    Collections.emptyList(),
                    BigInteger.ZERO);
            }));
            installed.add(ResourceContainers.registerRoot("gtmb:drawers_upgrades", context -> {
                if (!IDS.contains(context.nativeTileId) || !context.selectedGroup("Upgrades", "Down")) return null;
                NBTTagCompound root = context.root();
                validate(root);
                return new ResourceContainers.RootClaim(
                    Arrays.asList("Upgrades", "Down"),
                    upgradeBill(root.getTagList("Upgrades", 10)),
                    Collections.emptyList(),
                    BigInteger.ZERO);
            }));
            HologramNativePlacement.Adapter placement = new Placement();
            installed.add(GTMBExtensions.install("gtmb:drawers_placement", "native-placement", () -> {
                HologramNativePlacement.registerBuiltin("gtmb:drawers_placement", placement);
                return () -> HologramNativePlacement.unregister("gtmb:drawers_placement", placement);
            }));
            return Collections.unmodifiableList(installed);
        } catch (RuntimeException | Error failure) {
            for (int i = installed.size() - 1; i >= 0; i--) installed.get(i)
                .close();
            throw failure;
        }
    }

    /** Captures private physical counts, never the virtual/infinite inventory view or the mutating native writer. */
    public static NBTTagCompound capturePure(TileEntity tile) throws Exception {
        NBTTagCompound tag = HologramNativePlacement.captureBase(tile);
        NBTTagCompound failed = (NBTTagCompound) get(tile, "failureSnapshot");
        if (failed != null) {
            for (Object key : failed.func_150296_c()) if (!tag.hasKey((String) key)) tag.setTag(
                (String) key,
                failed.getTag((String) key)
                    .copy());
            return tag;
        }
        tag.setByte("Dir", ((Integer) get(tile, "direction")).byteValue());
        int rotation = (Integer) get(tile, "rotation");
        if (rotation != 0) tag.setByte("Rot", (byte) rotation);
        if ((Boolean) get(tile, "taped")) tag.setBoolean("Tape", true);
        String name = (String) get(tile, "customName");
        if (name != null && !name.isEmpty()) tag.setString("CustomName", name);
        ItemStack[] upgrades = (ItemStack[]) get(tile, "upgrades");
        boolean downgraded = false;
        Item downgrade = modItem("upgradeDowngrade");
        NBTTagList upgradeTags = new NBTTagList();
        for (int i = 0; i < upgrades.length; i++) if (upgrades[i] != null) {
            NBTTagCompound row = writeItem(upgrades[i]);
            row.setByte("Slot", (byte) i);
            upgradeTags.appendTag(row);
            downgraded |= upgrades[i].getItem() == downgrade;
        }
        if (downgraded) tag.setBoolean("Down", true);
        tag.setInteger("Cap", (Integer) get(tile, "drawerCapacity"));
        if (upgradeTags.tagCount() != 0) tag.setTag("Upgrades", upgradeTags);
        Object locks = get(tile, "lockAttributes");
        if (locks != null) {
            Class<?> type = Class.forName(PREFIX + "api.storage.attribute.LockAttribute");
            int flags = ((Number) type.getMethod("getBitfield", EnumSet.class)
                .invoke(null, locks)).intValue();
            tag.setByte("Lock", (byte) flags);
        }
        for (String[] flag : new String[][] { { "shrouded", "Shr" }, { "quantified", "Qua" },
            { "hideUpgrade", "HideUp" } }) if ((Boolean) get(tile, flag[0])) tag.setBoolean(flag[1], true);
        UUID owner = (UUID) get(tile, "owner");
        if (owner != null) tag.setString("Own", owner.toString());
        String security = (String) get(tile, "securityKey");
        if (security != null) tag.setString("Sec", security);
        NBTTagList slots = new NBTTagList();
        for (Object drawer : (Object[]) get(tile, "drawers")) {
            if (!drawer.getClass()
                .getName()
                .equals(PREFIX + "storage.DrawerData")) throw unsupported("unknown drawer persistence");
            NBTTagCompound slot = new NBTTagCompound();
            call(drawer, "writeToNBT", new Class<?>[] { NBTTagCompound.class }, slot);
            if (slot.hasKey("ItemStack", 10)) {
                ItemStack prototype = (ItemStack) get(drawer, "protoStack");
                NativeItemIds.write(slot.getCompoundTag("ItemStack"), Item.getIdFromItem(prototype.getItem()));
            }
            slots.appendTag(slot.copy());
        }
        tag.setTag("Slots", slots);
        for (String[] material : new String[][] { { "materialSide", "MatS" }, { "materialFront", "MatF" },
            { "materialTrim", "MatT" } }) {
            ItemStack stack = (ItemStack) get(tile, material[0]);
            if (stack != null) tag.setTag(material[1], writeItem(stack));
        }
        return tag;
    }

    private static final class Placement implements HologramNativePlacement.Adapter {

        public boolean supports(Block block) {
            return block.getClass()
                .getName()
                .equals(PREFIX + "block.BlockDrawers");
        }

        public boolean requiresPayload() {
            return true;
        }

        public TileEntity create(Block block, int metadata, NBTTagCompound tag, World world) throws Exception {
            validate(tag);
            if (!IDS.contains(tag.getString("id"))) throw unsupported("wrong tile identity");
            TileEntity tile = block.createTileEntity(world, metadata);
            if (tile == null || !tile.getClass()
                .getName()
                .equals(TILE)) throw unsupported("wrong block factory");
            int count = (Integer) get(block, "drawerCount");
            if (tag.hasKey("Slots") && tag.getTagList("Slots", 10)
                .tagCount() != count) throw unsupported("drawer count does not match block identity");
            call(tile, "setDrawerCount", new Class<?>[] { int.class }, count);
            Object item = Item.getItemFromBlock(block);
            int nativeCapacity = (Integer) callDeclared(
                item,
                "getCapacityForBlock",
                new Class<?>[] { Block.class },
                block);
            int capacity = tag.hasKey("Cap") ? tag.getInteger("Cap") : nativeCapacity;
            if (capacity <= 0 || capacity > nativeCapacity) throw unsupported("capacity exceeds native block capacity");
            set(tile, "drawerCapacity", capacity);
            set(tile, "direction", (int) tag.getByte("Dir"));
            set(tile, "rotation", (int) tag.getByte("Rot"));
            set(tile, "taped", tag.getBoolean("Tape"));
            set(tile, "customName", tag.hasKey("CustomName") ? tag.getString("CustomName") : null);
            set(tile, "shrouded", tag.getBoolean("Shr"));
            set(tile, "quantified", tag.getBoolean("Qua"));
            set(tile, "hideUpgrade", tag.getBoolean("HideUp"));
            set(tile, "owner", tag.hasKey("Own") ? UUID.fromString(tag.getString("Own")) : null);
            set(tile, "securityKey", tag.hasKey("Sec") ? tag.getString("Sec") : null);
            if (tag.hasKey("Lock")) set(
                tile,
                "lockAttributes",
                Class.forName(PREFIX + "api.storage.attribute.LockAttribute")
                    .getMethod("getEnumSet", int.class)
                    .invoke(null, (int) tag.getByte("Lock")));
            ItemStack[] upgrades = new ItemStack[5];
            NBTTagList upgradeTags = tag.getTagList("Upgrades", 10);
            for (int i = 0; i < upgradeTags.tagCount(); i++) {
                NBTTagCompound row = upgradeTags.getCompoundTagAt(i);
                upgrades[row.getByte("Slot")] = readItem(row);
            }
            set(tile, "upgrades", upgrades);
            boolean downgraded = false;
            for (ItemStack upgrade : upgrades)
                downgraded |= upgrade != null && upgrade.getItem() == modItem("upgradeDowngrade");
            set(tile, "downgraded", downgraded);
            Object[] drawers = (Object[]) get(tile, "drawers");
            NBTTagList slots = tag.getTagList("Slots", 10);
            for (int i = 0; i < slots.tagCount(); i++) {
                NBTTagCompound row = slots.getCompoundTagAt(i);
                ItemStack prototype = prototype(row);
                if (prototype == null) continue;
                set(drawers[i], "protoStack", prototype);
                set(drawers[i], "count", row.getInteger("Count"));
                if (row.getInteger("Count") > (Integer) call(drawers[i], "getMaxCapacity", new Class<?>[0]))
                    throw unsupported("physical count exceeds drawer capacity");
                // These reviewed helpers refresh local caches only; the native setter dispatches population events.
                callDeclared(drawers[i], "refreshOreDictMatches", new Class<?>[0]);
                call(get(drawers[i], "inventoryStack"), "reset", new Class<?>[0]);
            }
            HologramNativePlacement.bindDetached(tile, world, tag);
            return tile;
        }

        public List<ItemStack> materials(Block block, int metadata, TileEntity tile) throws Exception {
            NBTTagCompound tag = capturePure(tile);
            validate(tag);
            List<ItemStack> result = new ArrayList<>();
            result.add(new ItemStack(block, 1, block.damageDropped(metadata)));
            result.addAll(slotBill(tag.getTagList("Slots", 10)));
            result.addAll(upgradeBill(tag.getTagList("Upgrades", 10)));
            return result;
        }

        public boolean includesInventoryDrops(Block block) {
            return true;
        }

        public TileEntity createRollback(Block block, int metadata, NBTTagCompound tag, World detachedWorld) {
            // Preserve the pre-module trusted native rollback path, including security and older saved schemas.
            return TileEntity.createAndLoadEntity((NBTTagCompound) tag.copy());
        }

        public void prepareActor(Block block, TileEntity tile, EntityPlayerMP actor) throws Exception {
            if (actor != null && get(tile, "owner") != null) set(tile, "owner", actor.getUniqueID());
        }

        public NBTTagCompound captureExpected(TileEntity tile) throws Exception {
            return capturePure(tile);
        }
    }

    private static void validate(NBTTagCompound root) throws IOException {
        keys(
            root,
            "id",
            "x",
            "y",
            "z",
            "Dir",
            "Rot",
            "Tape",
            "CustomName",
            "Down",
            "Cap",
            "Upgrades",
            "Lock",
            "Shr",
            "Qua",
            "Own",
            "Sec",
            "HideUp",
            "Slots",
            "gtmb.nativePreview");
        if (root.hasKey("gtmb.nativePreview")
            && (!root.hasKey("gtmb.nativePreview", 1) || root.getByte("gtmb.nativePreview") < 0
                || root.getByte("gtmb.nativePreview") > 1))
            throw unsupported("invalid preview marker");
        if (root.hasKey("Cap") && (!root.hasKey("Cap", 3) || root.getInteger("Cap") <= 0))
            throw unsupported("invalid drawer capacity");
        for (String flag : new String[] { "Dir", "Rot", "Tape", "Down", "Lock", "Shr", "Qua", "HideUp" })
            if (root.hasKey(flag) && !root.hasKey(flag, 1)) throw unsupported("invalid field type " + flag);
        for (String text : new String[] { "CustomName", "Own", "Sec" })
            if (root.hasKey(text) && !root.hasKey(text, 8)) throw unsupported("invalid field type " + text);
        if (root.getByte("Dir") < 0 || root.getByte("Dir") > 5 || root.getByte("Rot") < 0 || root.getByte("Rot") > 3)
            throw unsupported("invalid drawer orientation");
        if ((root.getByte("Lock") & ~3) != 0) throw unsupported("unknown drawer lock flags");
        if (root.hasKey("Sec") && !root.getString("Sec")
            .isEmpty()) throw unsupported("security-provider restoration needs a reviewed policy");
        if (root.hasKey("Own")) try {
            UUID.fromString(root.getString("Own"));
        } catch (IllegalArgumentException invalid) {
            throw unsupported("invalid owner identity");
        }
        for (String list : new String[] { "Slots", "Upgrades" })
            if (root.hasKey(list) && (!root.hasKey(list, 9) || root.getTagList(list, 10)
                .tagCount() != ((NBTTagList) root.getTag(list)).tagCount()))
                throw unsupported("invalid compound list " + list);
        slotBill(root.getTagList("Slots", 10));
        List<ItemStack> upgrades = upgradeBill(root.getTagList("Upgrades", 10));
        try {
            boolean downgraded = false;
            for (ItemStack upgrade : upgrades) downgraded |= upgrade.getItem() == modItem("upgradeDowngrade");
            if (root.getBoolean("Down") != downgraded) throw unsupported("inconsistent downgrade upgrade");
        } catch (ReflectiveOperationException failure) {
            throw new IOException("Storage Drawers upgrade registry is unavailable", failure);
        }
    }

    private static List<ItemStack> slotBill(NBTTagList slots) throws IOException {
        if (slots.tagCount() > 4) throw unsupported("compacting or unknown drawer layout");
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < slots.tagCount(); i++) {
            NBTTagCompound row = slots.getCompoundTagAt(i);
            ItemStack prototype = prototype(row);
            if (prototype != null && row.getInteger("Count") > 0) {
                prototype.stackSize = row.getInteger("Count");
                result.add(prototype);
            }
        }
        return result;
    }

    private static ItemStack prototype(NBTTagCompound row) throws IOException {
        keys(row, "ItemStack", "Count");
        if (row.hasNoTags()) return null;
        if (!row.hasKey("ItemStack", 10) || !row.hasKey("Count", 3) || row.getInteger("Count") < 0)
            throw unsupported("invalid physical drawer count");
        NBTTagCompound item = row.getCompoundTag("ItemStack");
        keys(item, "id", "idExt", "Count", "Damage", "tag");
        return readItem(item);
    }

    private static List<ItemStack> upgradeBill(NBTTagList tags) throws IOException {
        List<ItemStack> result = new ArrayList<>();
        Set<Byte> occupied = new HashSet<>();
        for (int i = 0; i < tags.tagCount(); i++) {
            NBTTagCompound row = tags.getCompoundTagAt(i);
            keys(row, "id", "idExt", "Count", "Damage", "tag", "Slot");
            if (!row.hasKey("Slot", 1) || row.getByte("Slot") < 0
                || row.getByte("Slot") >= 5
                || !occupied.add(row.getByte("Slot"))) throw unsupported("invalid or duplicate upgrade slot");
            ItemStack stack = readItem(row);
            try {
                if (stack.getItem() == modItem("upgradeCreative"))
                    throw unsupported("unlimited storage is not a finite template");
                boolean known = false;
                for (String field : UPGRADE_FIELDS) known |= stack.getItem() == modItem(field);
                if (!known) throw unsupported("unreviewed upgrade item");
            } catch (ReflectiveOperationException failure) {
                throw new IOException("Storage Drawers upgrade registry is unavailable", failure);
            }
            result.add(stack);
        }
        return result;
    }

    private static Item modItem(String field) throws ReflectiveOperationException {
        return (Item) Class.forName(PREFIX + "core.ModItems")
            .getField(field)
            .get(null);
    }

    private static ItemStack readItem(NBTTagCompound tag) throws IOException {
        if (tag.hasKey("id") && !tag.hasKey("id", 2) && !tag.hasKey("id", 3)
            || tag.hasKey("idExt") && !tag.hasKey("idExt", 3)
            || !tag.hasKey("Count", 1)
            || !tag.hasKey("Damage", 2)
            || tag.hasKey("tag") && !tag.hasKey("tag", 10)) throw unsupported("invalid item encoding");
        int id = NativeItemIds.read(tag);
        Item identity = Item.getItemById(id);
        int damage = tag.getShort("Damage");
        if (id <= 0 || identity == null || damage < 0 || tag.getByte("Count") != 1)
            throw unsupported("invalid physical item prototype");
        ItemStack stack = new ItemStack(identity, 1, damage);
        if (tag.hasKey("tag")) stack.setTagCompound(
            (NBTTagCompound) tag.getCompoundTag("tag")
                .copy());
        return stack;
    }

    private static NBTTagCompound writeItem(ItemStack stack) {
        NBTTagCompound tag = stack.writeToNBT(new NBTTagCompound());
        NativeItemIds.write(tag, Item.getIdFromItem(stack.getItem()));
        return (NBTTagCompound) tag.copy();
    }

    private static void keys(NBTTagCompound tag, String... allowed) throws IOException {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        for (Object key : tag.func_150296_c()) if (!names.contains(key)) throw unsupported("unreviewed field " + key);
    }

    private static Object get(Object source, String name) throws ReflectiveOperationException {
        return NativeFields.find(source.getClass(), name)
            .get(source);
    }

    private static void set(Object source, String name, Object value) throws ReflectiveOperationException {
        NativeFields.find(source.getClass(), name)
            .set(source, value);
    }

    private static Object call(Object source, String name, Class<?>[] parameters, Object... arguments)
        throws ReflectiveOperationException {
        return source.getClass()
            .getMethod(name, parameters)
            .invoke(source, arguments);
    }

    private static Object callDeclared(Object source, String name, Class<?>[] parameters, Object... arguments)
        throws ReflectiveOperationException {
        for (Class<?> type = source.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(name, parameters);
                method.setAccessible(true);
                return method.invoke(source, arguments);
            } catch (NoSuchMethodException absent) {}
        }
        throw new NoSuchMethodException(name);
    }

    private static IOException unsupported(String detail) {
        return new IOException("Unsupported Storage Drawers blueprint: " + detail);
    }
}
