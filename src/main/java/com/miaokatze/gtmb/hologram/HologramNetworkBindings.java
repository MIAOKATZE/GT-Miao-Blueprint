package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.util.DimensionalCoord;
import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;
import appeng.util.Platform;

/** Exact GTSWN schemas. Library data contains local groups; inventory bindings remain session-local. */
final class HologramNetworkBindings {

    static final String NODE_BLOCK = "com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode";
    static final String NODE_TILE = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";
    static final String GROUP = "gtmbNetworkGroup";
    static final String ROLE = "gtmbNetworkRole";
    static final String HINT = "gtmbNetworkHint";
    static final String CAPTURE_ANCHOR = "gtmbCapturedQuantumAnchor";
    private static final String TERMINAL = "com.miaokatze.gtswn.common.items.ItemNetworkQuantumTerminal";
    private static final String REGISTRY = "com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry";
    private static Field incorporationEntries, incorporationDataName;
    private static final String[] NODE_ANCHOR = { "anchorDim", "anchorX", "anchorY", "anchorZ" };
    private static final String[] TERMINAL_ANCHOR = { "QT_AnchorDim", "QT_AnchorX", "QT_AnchorY", "QT_AnchorZ" };

    private HologramNetworkBindings() {}

    static boolean node(Block block) {
        return block != null && NODE_BLOCK.equals(
            block.getClass()
                .getName());
    }

    /** Read the registry only for the captured live tile; never serialize WorldSavedData into a blueprint. */
    static void capture(TileEntity tile, NBTTagCompound tag) throws IOException {
        if (tile == null || tile.getWorldObj() == null || tile.getWorldObj().isRemote) return;
        if (NODE_TILE.equals(
            tile.getClass()
                .getName()))
            return; // Its anchor is in its own exact tile schema.
        if (tag.getString("GTSWN_IncorporationIdentity")
            .isEmpty()) return;
        try {
            Object entry = entry(tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord);
            if (entry != null) {
                Class<?> type = entry.getClass();
                String identity = (String) type.getMethod("identity")
                    .invoke(entry);
                if (!identity.equals(tag.getString("GTSWN_IncorporationIdentity"))) return;
                NBTTagCompound anchor = new NBTTagCompound();
                for (int i = 0; i < NODE_ANCHOR.length; i++) anchor.setInteger(
                    NODE_ANCHOR[i],
                    type.getField(new String[] { "dim", "ax", "ay", "az" }[i])
                        .getInt(entry));
                tag.setTag(CAPTURE_ANCHOR, anchor);
                tag.setString(ROLE, "join");
                return;
            }
        } catch (ClassNotFoundException absentOptionalMod) {
            // Optional GTSWN is absent.
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("GTSWN incorporation capture schema changed", failure);
        }
    }

    /** Exact registry index, without snapshot allocation or creation of absent WorldSavedData during capture. */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object entry(World world, int x, int y, int z) throws ReflectiveOperationException {
        Class<?> type = Class.forName(REGISTRY);
        if (incorporationEntries == null) {
            incorporationEntries = type.getDeclaredField("entries");
            if (!Map.class.isAssignableFrom(incorporationEntries.getType()))
                throw new NoSuchFieldException("GTSWN entries index type changed");
            incorporationEntries.setAccessible(true);
            incorporationDataName = type.getDeclaredField("DATA_NAME");
            if (incorporationDataName.getType() != String.class)
                throw new NoSuchFieldException("GTSWN data name changed");
            incorporationDataName.setAccessible(true);
        }
        Object saved = world.perWorldStorage.loadData((Class) type, (String) incorporationDataName.get(null));
        if (saved == null) return null;
        Class<?> positions = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry");
        long key = (Long) positions.getMethod("pack", int.class, int.class, int.class)
            .invoke(null, x, y, z);
        return ((Map<?, ?>) incorporationEntries.get(saved)).get(key);
    }

    /** Group by captured network anchor before erasing it. No source dimension/coordinates survive this projection. */
    static List<HologramAePolicy.Issue> sanitize(HologramPresets.Preset preset) throws IOException {
        List<HologramAePolicy.Issue> changes = new ArrayList<>();
        Map<Object, String> groups = new LinkedHashMap<>();
        int next = 1;
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag != null && tag.hasKey(GROUP)) {
                String group = group(tag);
                try {
                    next = Math.max(next, Integer.parseInt(group.substring(1)) + 1);
                } catch (NumberFormatException invalid) {
                    throw new IOException("Invalid quantum group", invalid);
                }
            }
        }
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            NBTTagCompound item = HologramBlueprintNbt.decode(cell.itemNbt);
            boolean removedReferences = HologramNetworkSchemas.removeExternalReferences(cell.block(), cell.damage, tag);
            removedReferences |= HologramNetworkSchemas.removeExternalReferences(cell.block(), cell.damage, item);
            if (removedReferences) changes.add(
                new HologramAePolicy.Issue(
                    "network.external_links_reset",
                    "warning",
                    "network",
                    "Registered machine network identities and world-coordinate links are omitted; reconnect the new devices.",
                    cell.x,
                    cell.y,
                    cell.z));
            if (item != null) cell.itemNbt = HologramBlueprintNbt.encodeCaptured(item);
            if (tag == null) continue;
            boolean node = node(cell.block());
            NBTTagCompound anchor = node && tag.hasKey("anchorDim") ? tag
                : tag.hasKey(CAPTURE_ANCHOR, 10) ? tag.getCompoundTag(CAPTURE_ANCHOR) : null;
            if (anchor != null) {
                Object key = capturedNetworkKey(anchor);
                String local = groups.get(key);
                if (local == null) {
                    local = "q" + next++;
                    groups.put(key, local);
                }
                tag.setString(GROUP, local);
                tag.setString(ROLE, node ? "node" : "join");
            }
            if (node && !tag.hasKey(GROUP)) {
                tag.setString(GROUP, "q" + next++);
                tag.setString(ROLE, "node");
            }
            tag.removeTag(CAPTURE_ANCHOR);
            tag.removeTag("GTSWN_IncorporationIdentity");
            if (node) {
                for (String key : NODE_ANCHOR) tag.removeTag(key);
                tag.removeTag("proxy");
                tag.removeTag("linked");
            }
            String tileId = tag.getString("id");
            if (tileId.equals("gtswn.network_info_panel")) {
                tag.removeTag("proxy");
                tag.removeTag("lastAESampleTick");
            }
            if (tileId.equals("gtswn.network_info_panel_extender")) {
                for (String key : new String[] { "coreX", "coreY", "coreZ", "partOfScreen" }) tag.removeTag(key);
            }
            if (tag.hasKey(GROUP)) group(tag);
            cell.tileNbt = HologramAeBlueprint.supports(cell.block(), tag)
                ? HologramBlueprintNbt.encodeCanonical(cell.block(), tag)
                : HologramBlueprintNbt.encodeCaptured(tag);
        }
        return changes;
    }

    private static Object capturedNetworkKey(NBTTagCompound anchor) throws IOException {
        String fallback = anchorKey(anchor, NODE_ANCHOR);
        WorldServer world = DimensionManager.getWorld(anchor.getInteger("anchorDim"));
        int x = anchor.getInteger("anchorX"), y = anchor.getInteger("anchorY"), z = anchor.getInteger("anchorZ");
        if (world == null || !world.blockExists(x, y, z)) return fallback;
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileController) || !((IGridProxyable) tile).getProxy()
            .isReady()) return fallback;
        try {
            return ((IGridProxyable) tile).getProxy()
                .getGrid();
        } catch (Exception unavailableNetwork) {
            return fallback;
        }
    }

    static String group(NBTTagCompound tag) throws IOException {
        String value = tag.getString(GROUP);
        if (!tag.hasKey(GROUP, 8) || !value.matches("q[1-9][0-9]{0,5}")
            || !("node".equals(tag.getString(ROLE)) || "join".equals(tag.getString(ROLE))))
            throw new IOException("Invalid blueprint quantum group descriptor");
        if (tag.hasKey(HINT)) {
            if (!tag.hasKey(HINT, 8)) throw new IOException("Invalid quantum group hint type");
            checkedHint(tag.getString(HINT));
        }
        return value;
    }

    static String checkedHint(String hint) throws IOException {
        if (hint == null || hint.length() > 256
            || hint.chars()
                .anyMatch(c -> c < 32 || c == 167))
            throw new IOException("Quantum group hint must contain at most 256 ordinary text characters");
        return hint;
    }

    static String hint(NBTTagCompound tag) throws IOException {
        String id = group(tag), text = checkedHint(tag.getString(HINT));
        return text.isEmpty() ? "量子网络组 " + id.substring(1) : text;
    }

    static void editHint(HologramPresets.Preset preset, String group, String hint) throws IOException {
        checkedHint(hint);
        boolean found = false;
        for (HologramPresets.Cell cell : preset.cells) {
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null || !tag.hasKey(GROUP) || !group.equals(group(tag))) continue;
            tag.setString(HINT, hint);
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(cell.block(), tag);
            found = true;
        }
        if (!found) throw new IOException("Unknown blueprint quantum group");
    }

    static boolean descriptorKey(String key) {
        return GROUP.equals(key) || ROLE.equals(key) || HINT.equals(key);
    }

    static final class State {

        private final Map<String, Selection> selected = new LinkedHashMap<>();

        void clear() {
            selected.clear();
        }

        void select(EntityPlayerMP player, String group, int slot) throws IOException {
            if (!group.matches("q[1-9][0-9]{0,5}") || slot < 0 || slot >= player.inventory.mainInventory.length)
                throw new IOException("Invalid quantum network inventory selection");
            ItemStack stack = player.inventory.mainInventory[slot];
            anchor(player, stack);
            selected.put(group, new Selection(slot, stack));
        }

        NBTTagCompound resolve(EntityPlayerMP player, String group) throws IOException {
            Selection selection = selected.get(group);
            if (selection == null)
                throw new IOException("Quantum group " + group + " needs a real bound quantum terminal");
            ItemStack live = player.inventory.mainInventory[selection.slot];
            if (live == null || !live.isItemEqual(selection.stack)
                || !ItemStack.areItemStackTagsEqual(live, selection.stack))
                throw new IOException("Selected quantum terminal changed; select it again");
            return anchor(player, live); // Permission/liveness is checked again immediately before placement.
        }
    }

    private static final class Selection {

        final int slot;
        final ItemStack stack;

        Selection(int slot, ItemStack stack) {
            this.slot = slot;
            this.stack = stack.copy();
            this.stack.stackSize = 1;
        }
    }

    private static String anchorKey(NBTTagCompound tag, String[] fields) throws IOException {
        StringBuilder result = new StringBuilder();
        for (String field : fields) {
            if (!tag.hasKey(field, 3)) throw new IOException("Incomplete or malformed quantum anchor");
            result.append(tag.getInteger(field))
                .append('/');
        }
        if (tag.getInteger(fields[2]) < 0 || tag.getInteger(fields[2]) > 255)
            throw new IOException("Quantum anchor height is invalid");
        return result.toString();
    }

    private static boolean carrier(ItemStack stack) {
        return stack != null && stack.getItem() != null
            && (TERMINAL.equals(
                stack.getItem()
                    .getClass()
                    .getName())
                || node(Block.getBlockFromItem(stack.getItem())));
    }

    /** A real node item may carry the terminal coordinate attributes, or the native node anchor schema. */
    private static NBTTagCompound anchor(EntityPlayerMP player, ItemStack stack) throws IOException {
        if (!carrier(stack) || stack.stackSize <= 0 || !stack.hasTagCompound())
            throw new IOException("Select a real quantum node or terminal carrying network coordinates");
        NBTTagCompound tag = stack.getTagCompound();
        boolean terminal = TERMINAL.equals(
            stack.getItem()
                .getClass()
                .getName());
        String[] fields = !terminal && tag.hasKey("anchorDim", 3) ? NODE_ANCHOR : TERMINAL_ANCHOR;
        if (terminal && (!tag.hasKey("QT_Bound", 1) || !tag.getBoolean("QT_Bound")))
            throw new IOException("Quantum terminal is not bound");
        anchorKey(tag, fields);
        int dim = tag.getInteger(fields[0]), x = tag.getInteger(fields[1]);
        int y = tag.getInteger(fields[2]), z = tag.getInteger(fields[3]);
        WorldServer world = DimensionManager.getWorld(dim);
        if (world == null || !world.blockExists(x, y, z)) throw new IOException("Quantum anchor is not loaded");
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileController) || !((IGridProxyable) tile).getProxy()
            .isReady()) throw new IOException("Quantum anchor controller is unavailable");
        try {
            Class<?> registry = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry");
            Object instance = registry.getMethod("get", World.class)
                .invoke(null, world);
            if (!(Boolean) registry.getMethod("isQuantized", int.class, int.class, int.class)
                .invoke(instance, x, y, z)) throw new IOException("Selected controller is not a quantum network");
            ISecurityGrid security = ((IGridProxyable) tile).getProxy()
                .getGrid()
                .getCache(ISecurityGrid.class);
            if (security == null || !security.hasPermission(player, SecurityPermissions.BUILD)
                || !Platform.hasPermissions(new DimensionalCoord(world, x, y, z), player))
                throw new IOException("No permission to bind the selected quantum network");
        } catch (IOException failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            throw new IOException("Quantum network verification failed", failure);
        }
        NBTTagCompound clean = new NBTTagCompound();
        for (int i = 0; i < TERMINAL_ANCHOR.length; i++)
            clean.setInteger(TERMINAL_ANCHOR[i], tag.getInteger(fields[i]));
        clean.setBoolean("QT_Bound", true);
        return clean;
    }

    static NBTTagCompound snapshot(HologramPresets.Preset preset, EntityPlayerMP player, State bindings)
        throws IOException {
        NBTTagCompound result = new NBTTagCompound();
        Map<String, NBTTagCompound> groups = new LinkedHashMap<>();
        if (preset != null) for (HologramPresets.Cell cell : preset.cells) {
            if (cell.excluded) continue;
            NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
            if (tag == null || !tag.hasKey(GROUP)) continue;
            String id = group(tag);
            NBTTagCompound row = groups.get(id);
            if (row == null) {
                row = new NBTTagCompound();
                row.setString("id", id);
                row.setString("hint", hint(tag));
                row.setString("prompt", checkedHint(tag.getString(HINT)));
                try {
                    bindings.resolve(player, id);
                    row.setBoolean("bound", true);
                } catch (IOException invalid) {
                    row.setBoolean("bound", false);
                    row.setString("reason", HologramAePolicy.fallback(invalid.getMessage()));
                }
                groups.put(id, row);
            }
            row.setInteger("cells", row.getInteger("cells") + 1);
        }
        NBTTagList rows = new NBTTagList();
        for (NBTTagCompound row : groups.values()) rows.appendTag(row);
        result.setTag("groups", rows);
        NBTTagList candidates = new NBTTagList();
        for (int slot = 0; slot < player.inventory.mainInventory.length; slot++) {
            ItemStack stack = player.inventory.mainInventory[slot];
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("slot", slot);
            row.setString("name", stack.getDisplayName());
            NBTTagCompound sample = new NBTTagCompound();
            new ItemStack(stack.getItem(), Math.min(64, stack.stackSize), stack.getItemDamage()).writeToNBT(sample);
            row.setTag("item", sample); // Never expose anchor/owner item NBT as display data.
            row.setBoolean("carrier", carrier(stack));
            try {
                anchor(player, stack);
                row.setBoolean("available", true);
            } catch (IOException invalid) {
                row.setString("reason", HologramAePolicy.fallback(invalid.getMessage()));
            }
            candidates.appendTag(row);
        }
        result.setTag("inventory", candidates);
        return result;
    }

    static void validate(NBTTagCompound payload, EntityPlayerMP player, State state) throws IOException {
        if (payload != null && payload.hasKey(GROUP)) state.resolve(player, group(payload));
    }

    static boolean matchesBinding(TileEntity actual, NBTTagCompound payload, EntityPlayerMP player, State state)
        throws IOException {
        if (payload == null || !payload.hasKey(GROUP)) return true;
        if (actual == null) return false;
        NBTTagCompound bound = state.resolve(player, group(payload));
        try {
            if ("node".equals(payload.getString(ROLE))) {
                if (!NODE_TILE.equals(
                    actual.getClass()
                        .getName()))
                    return false;
                String[] getters = { "getAnchorDim", "getAnchorX", "getAnchorY", "getAnchorZ" };
                for (int i = 0; i < getters.length; i++) if ((Integer) actual.getClass()
                    .getMethod(getters[i])
                    .invoke(actual) != bound.getInteger(TERMINAL_ANCHOR[i])) return false;
                appeng.api.networking.IGridNode node = ((IGridProxyable) actual).getProxy()
                    .getNode();
                return node != null && node.getPlayerID() == appeng.core.worlddata.WorldData.instance()
                    .playerData()
                    .getPlayerID(player.getGameProfile());
            }
            String identity = (String) actual.getClass()
                .getMethod("gtswn$getIncorporationIdentity")
                .invoke(actual);
            Object existing = entry(actual.getWorldObj(), actual.xCoord, actual.yCoord, actual.zCoord);
            if (existing != null) {
                Class<?> type = existing.getClass();
                if (!identity.equals(
                    type.getMethod("identity")
                        .invoke(existing)))
                    return false;
                java.lang.reflect.Field owner = type.getDeclaredField("owner");
                owner.setAccessible(true);
                if (!player.getUniqueID()
                    .toString()
                    .equals(owner.get(existing))) return false;
                String[] fields = { "dim", "ax", "ay", "az" };
                for (int i = 0; i < fields.length; i++) if (type.getField(fields[i])
                    .getInt(existing) != bound.getInteger(TERMINAL_ANCHOR[i])) return false;
                return true;
            }
            return false;
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("Cannot verify existing quantum binding", failure);
        }
    }

    /** Called after actor ownership reset; no source network identifier reaches a native reader. */
    static void bindNode(TileEntity tile, NBTTagCompound payload, EntityPlayerMP player, State state)
        throws IOException {
        if (!NODE_TILE.equals(
            tile.getClass()
                .getName())
            || payload == null
            || !payload.hasKey(GROUP)) return;
        NBTTagCompound bound = state.resolve(player, group(payload));
        try {
            tile.getClass()
                .getMethod("setAnchor", int.class, int.class, int.class, int.class)
                .invoke(
                    tile,
                    bound.getInteger(TERMINAL_ANCHOR[0]),
                    bound.getInteger(TERMINAL_ANCHOR[1]),
                    bound.getInteger(TERMINAL_ANCHOR[2]),
                    bound.getInteger(TERMINAL_ANCHOR[3]));
            tile.getClass()
                .getMethod("setPlacer", EntityPlayer.class)
                .invoke(tile, player);
        } catch (ReflectiveOperationException failure) {
            throw new IOException("Quantum node binding schema changed", failure);
        }
    }

    static void bindJoin(TileEntity tile, NBTTagCompound payload, EntityPlayerMP player, State state)
        throws IOException {
        if (payload == null || !payload.hasKey(GROUP) || !"join".equals(payload.getString(ROLE))) return;
        NBTTagCompound bound = state.resolve(player, group(payload));
        try {
            Class<?> type = Class.forName(REGISTRY);
            Object registry = type.getMethod("get", World.class)
                .invoke(null, tile.getWorldObj());
            if (!(Boolean) type
                .getMethod(
                    "add",
                    World.class,
                    int.class,
                    int.class,
                    int.class,
                    NBTTagCompound.class,
                    EntityPlayer.class)
                .invoke(registry, tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord, bound, player))
                throw new IOException("Quantum incorporation was rejected");
        } catch (IOException failure) {
            throw failure;
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("Quantum incorporation binding failed", failure);
        }
    }

    /** Remove only a newly added entry before the enclosing cell transaction restores its original world tile. */
    static void rollbackJoin(TileEntity tile, EntityPlayerMP player) throws IOException {
        if (tile == null) return;
        try {
            Class<?> type = Class.forName(REGISTRY);
            if (!(Boolean) type.getMethod("isIncorporated", World.class, int.class, int.class, int.class)
                .invoke(null, tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord)) return;
            Object registry = type.getMethod("get", World.class)
                .invoke(null, tile.getWorldObj());
            if (!(Boolean) type.getMethod("remove", World.class, int.class, int.class, int.class, EntityPlayer.class)
                .invoke(registry, tile.getWorldObj(), tile.xCoord, tile.yCoord, tile.zCoord, player))
                throw new IOException("Failed to roll back new quantum incorporation");
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("Quantum incorporation rollback failed", failure);
        }
    }
}
