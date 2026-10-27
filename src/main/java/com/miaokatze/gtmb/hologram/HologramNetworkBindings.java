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

import appeng.me.helpers.IGridProxyable;
import appeng.tile.networking.TileController;

/** Exact GTSWN schemas. Library data contains local groups; inventory bindings remain session-local. */
final class HologramNetworkBindings {

    static final String NODE_BLOCK = "com.miaokatze.gtswn.common.block.BlockNetworkQuantumNode";
    static final String NODE_TILE = "com.miaokatze.gtswn.common.tile.TileEntityNetworkQuantumNode";
    static final String GROUP = "gtmbNetworkGroup";
    static final String ROLE = "gtmbNetworkRole";
    static final String HINT = "gtmbNetworkHint";
    static final String CAPTURE_ANCHOR = "gtmbCapturedQuantumAnchor";
    static final String ORIGINAL = "gtmbQuantumOriginal";
    static final String MODE = "gtmbQuantumMode";
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
                .getName())) {
            if (tag.hasKey("anchorDim")) captureOriginal(tile.getWorldObj(), tag, tag);
            return;
        }
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
                if (HologramGtswnQuantumAccess.generationAware()) {
                    Field cursor = type.getDeclaredField("lastAppliedMigration");
                    if (cursor.getType() != long.class) throw new NoSuchFieldException("incorporation cursor changed");
                    cursor.setAccessible(true);
                    anchor.setLong("lastAppliedMigration", cursor.getLong(entry));
                    if (HologramGtswnQuantumAccess.anchorGenerationAware()) {
                        Field generation = type.getField("anchorGeneration");
                        if (generation.getType() != String.class)
                            throw new NoSuchFieldException("incorporation generation changed");
                        anchor.setString("anchorGeneration", (String) generation.get(entry));
                    }
                }
                tag.setTag(CAPTURE_ANCHOR, anchor);
                captureOriginal(tile.getWorldObj(), tag, anchor);
                tag.setString(ROLE, "join");
                return;
            }
        } catch (ClassNotFoundException absentOptionalMod) {
            // Optional GTSWN is absent.
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IOException("GTSWN incorporation capture schema changed", failure);
        }
    }

    private static void captureOriginal(World world, NBTTagCompound tag, NBTTagCompound anchor) throws IOException {
        anchorKey(anchor, NODE_ANCHOR);
        NBTTagCompound original = new NBTTagCompound();
        original.setString("world", HologramQuantumWorldIdentity.get(world));
        for (String key : NODE_ANCHOR) original.setInteger(key, anchor.getInteger(key));
        if (HologramGtswnQuantumAccess.generationAware()) {
            if (!anchor.hasKey("lastAppliedMigration", 4) || anchor.getLong("lastAppliedMigration") < 0L)
                throw new IOException("原量子网络迁移游标不可核验，请重新采集");
            HologramGtswnQuantumAccess.Migration migrated = HologramGtswnQuantumAccess.follow(
                world,
                coordinates(anchor, NODE_ANCHOR),
                anchor.getLong("lastAppliedMigration"),
                anchor.getString("anchorGeneration"));
            for (int i = 0; i < NODE_ANCHOR.length; i++) original.setInteger(NODE_ANCHOR[i], migrated.anchor[i]);
            WorldServer target = DimensionManager.getWorld(migrated.anchor[0]);
            TileEntity controller = target != null
                && target.blockExists(migrated.anchor[1], migrated.anchor[2], migrated.anchor[3])
                    ? target.getTileEntity(migrated.anchor[1], migrated.anchor[2], migrated.anchor[3])
                    : null;
            original.setInteger("version", 2);
            if (HologramGtswnQuantumAccess.anchorGenerationAware()) {
                if (!validOwnerField(anchor, "anchorGeneration", true)) throw new IOException("量子原网络世代格式无效");
                original.setInteger("version", 3);
                original.setString("anchorGeneration", migrated.generation);
            }
            original.setLong("migrationCursor", migrated.cursor);
            original.setString(
                "controllerIdentity",
                controller instanceof TileController ? HologramGtswnQuantumAccess.controllerIdentity(controller) : "");
        }
        tag.setTag(ORIGINAL, original);
    }

    static void validateOriginal(NBTTagCompound payload) throws IOException {
        if (payload.hasKey(MODE) && (!payload.hasKey(MODE, 8)
            || !("preserve".equals(payload.getString(MODE)) || "rebind".equals(payload.getString(MODE)))))
            throw new IOException("量子网络放置策略无效");
        if (!payload.hasKey(ORIGINAL)) return;
        if (!payload.hasKey(ORIGINAL, 10)) throw new IOException("量子原网络描述类型无效");
        NBTTagCompound original = payload.getCompoundTag(ORIGINAL);
        int fields = original.func_150296_c()
            .size();
        if (!original.hasKey("world", 8) || (fields != 5 && fields != 8 && fields != 9))
            throw new IOException("量子原网络描述字段无效");
        if (fields >= 8 && (!original.hasKey("version", 3) || original.getInteger("version") != (fields == 9 ? 3 : 2)
            || !original.hasKey("migrationCursor", 4)
            || original.getLong("migrationCursor") < 0L
            || !validOwnerField(original, "controllerIdentity", true))) throw new IOException("量子原网络世代或迁移描述无效");
        if (fields == 9 && !validOwnerField(original, "anchorGeneration", true)) throw new IOException("量子原网络世代身份无效");
        try {
            if (!java.util.UUID.fromString(original.getString("world"))
                .toString()
                .equals(original.getString("world"))) throw new IllegalArgumentException("noncanonical world identity");
        } catch (IllegalArgumentException invalid) {
            throw new IOException("量子原网络存档身份无效", invalid);
        }
        anchorKey(original, NODE_ANCHOR);
    }

    private static NBTTagCompound resolve(NBTTagCompound payload, EntityPlayerMP player, State state)
        throws IOException {
        if (!"preserve".equals(payload.getString(MODE)) && !payload.hasKey(ORIGINAL))
            return state.resolve(player, group(payload));
        if (!payload.hasKey(ORIGINAL)) throw new IOException("私用蓝图缺少可核验的原量子网络绑定，无法使用；请从原世界重新采集或按分享流程处理");
        validateOriginal(payload);
        NBTTagCompound original = payload.getCompoundTag(ORIGINAL);
        if (!HologramQuantumWorldIdentity.get(player.worldObj)
            .equals(original.getString("world"))) throw new IOException("私用蓝图的原量子网络属于其他存档，无法使用；请重新按分享流程处理并绑定自己的终端");
        try {
            NBTTagCompound target = original;
            if (HologramGtswnQuantumAccess.generationAware()) {
                if (!original.hasKey("version", 3) || original.getString("controllerIdentity")
                    .isEmpty()) throw new IOException("原网络世代信息不可核验，请加载原控制器后重新采集；不能推断旧坐标上的新网络");
                if (HologramGtswnQuantumAccess.anchorGenerationAware() && original.getInteger("version") != 3)
                    throw new IOException("旧蓝图缺少当前网络世代，请重新采集或明确改用自己的终端绑定");
                HologramGtswnQuantumAccess.Migration migrated = HologramGtswnQuantumAccess.follow(
                    player.worldObj,
                    coordinates(original, NODE_ANCHOR),
                    original.getLong("migrationCursor"),
                    original.getString("anchorGeneration"));
                target = (NBTTagCompound) original.copy();
                for (int i = 0; i < NODE_ANCHOR.length; i++) target.setInteger(NODE_ANCHOR[i], migrated.anchor[i]);
                if (HologramGtswnQuantumAccess.anchorGenerationAware())
                    target.setString("anchorGeneration", migrated.generation);
                if (java.util.Arrays.equals(coordinates(original, NODE_ANCHOR), migrated.anchor)) {
                    WorldServer sourceWorld = DimensionManager.getWorld(migrated.anchor[0]);
                    TileEntity source = sourceWorld != null
                        && sourceWorld.blockExists(migrated.anchor[1], migrated.anchor[2], migrated.anchor[3])
                            ? sourceWorld.getTileEntity(migrated.anchor[1], migrated.anchor[2], migrated.anchor[3])
                            : null;
                    if (source == null || !original.getString("controllerIdentity")
                        .equals(HologramGtswnQuantumAccess.controllerIdentity(source)))
                        throw new IOException("原量子控制器已更换，不能把私用蓝图绑定到旧坐标上的其他网络");
                }
            }
            return checkedAnchor(target, NODE_ANCHOR);
        } catch (IOException unavailable) {
            throw new IOException("私用蓝图的原量子网络不可用，无法使用：" + unavailable.getMessage(), unavailable);
        }
    }

    static boolean validOwnerField(NBTTagCompound data, String key, boolean uuid) {
        if (!data.hasKey(key, 8)) return false;
        String value = data.getString(key);
        if (value.isEmpty()) return true;
        if (!uuid) return value.length() <= 64 && value.chars()
            .noneMatch(Character::isISOControl);
        try {
            return java.util.UUID.fromString(value)
                .toString()
                .equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static int[] coordinates(NBTTagCompound tag, String[] fields) {
        int[] result = new int[fields.length];
        for (int i = 0; i < fields.length; i++) result[i] = tag.getInteger(fields[i]);
        return result;
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
        return sanitize(preset, null);
    }

    /** A non-null frozen map makes this path detached: missing anchors use their coordinate tuple, never a world. */
    static List<HologramAePolicy.Issue> sanitize(HologramPresets.Preset preset, Map<String, String> networks)
        throws IOException {
        return sanitize(preset, networks, HologramAePolicy.PreparationProgress.NONE);
    }

    static List<HologramAePolicy.Issue> sanitize(HologramPresets.Preset preset, Map<String, String> networks,
        HologramAePolicy.PreparationProgress progress) throws IOException {
        List<HologramAePolicy.Issue> changes = new ArrayList<>();
        Map<Object, String> groups = new LinkedHashMap<>();
        int next = 1;
        int completed = 0;
        long total = 2L * preset.cells.size();
        HologramAnalysisFacts facts = new HologramAnalysisFacts();
        for (HologramPresets.Cell cell : preset.cells) {
            progress.update("networks", completed++, total);
            NBTTagCompound tag = facts.read(cell.tileNbt);
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
            progress.update("networks", completed++, total);
            NBTTagCompound before = facts.read(cell.tileNbt), beforeItem = facts.read(cell.itemNbt);
            NBTTagCompound tag = before == null ? null : (NBTTagCompound) before.copy();
            NBTTagCompound item = beforeItem == null ? null : (NBTTagCompound) beforeItem.copy();
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
            if (item != null && !item.equals(beforeItem)) cell.itemNbt = HologramBlueprintNbt.encodeCaptured(item);
            if (tag == null) continue;
            boolean node = node(cell.block());
            NBTTagCompound anchor = node && tag.hasKey("anchorDim") ? tag
                : tag.hasKey(CAPTURE_ANCHOR, 10) ? tag.getCompoundTag(CAPTURE_ANCHOR) : null;
            if (anchor != null) {
                // A reviewed author classification owns the logical slot; source anchors only seed
                // unclassified captures and remain independently recorded for private placement.
                if (!tag.hasKey(GROUP)) {
                    String coordinate = anchorKey(anchor, NODE_ANCHOR);
                    Object key = networks == null ? capturedNetworkKey(anchor)
                        : networks.getOrDefault(coordinate, "coordinates:" + coordinate);
                    String local = groups.get(key);
                    if (local == null) {
                        local = "q" + next++;
                        groups.put(key, local);
                    }
                    tag.setString(GROUP, local);
                    tag.setString(ROLE, node ? "node" : "join");
                }
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
                for (String key : new String[] { "placerUUID", "placerName" })
                    if (validOwnerField(tag, key, "placerUUID".equals(key))) tag.removeTag(key);
                // The native migration journal belongs to a world, never to the portable local group.
                // Leave malformed values intact for the AE analyzer's durable unresolved-data blocker.
                if (HologramAeBlueprint.validQuantumMigrationCursor(tag)) {
                    tag.removeTag("lastAppliedMigration");
                    changes.add(
                        new HologramAePolicy.Issue(
                            "ae.quantum_migration_reset",
                            "warning",
                            "lastAppliedMigration",
                            "Source-world quantum migration cursor is omitted; placement uses the target network journal.",
                            cell.x,
                            cell.y,
                            cell.z));
                }
                if (validOwnerField(tag, "anchorGeneration", true)) tag.removeTag("anchorGeneration");
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
            NBTTagCompound projected = HologramAeBlueprint.supports(cell.block(), tag)
                ? HologramBlueprintNbt.canonicalActorPayload(cell.block(), tag, null, "", false)
                : tag;
            if (!before.equals(projected)) cell.tileNbt = HologramBlueprintNbt.encodeCanonical(projected);
        }
        progress.update("networks", completed, total);
        return changes;
    }

    /** Server-thread snapshot of a captured anchor's grid identity, converted to an opaque local token. */
    static void captureNetwork(HologramPresets.Cell cell, Map<String, String> networks, Map<Object, String> identities)
        throws IOException {
        NBTTagCompound tag = HologramBlueprintNbt.decode(cell.tileNbt);
        if (tag == null || tag.hasKey(GROUP)) return;
        NBTTagCompound anchor = node(cell.block()) && tag.hasKey("anchorDim") ? tag
            : tag.hasKey(CAPTURE_ANCHOR, 10) ? tag.getCompoundTag(CAPTURE_ANCHOR) : null;
        if (anchor == null) return;
        String coordinate = anchorKey(anchor, NODE_ANCHOR);
        if (networks.containsKey(coordinate)) return;
        Object identity = capturedNetworkKey(anchor);
        String token = identities.computeIfAbsent(identity, ignored -> "network:" + identities.size());
        networks.put(coordinate, token);
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
            NBTTagCompound before = (NBTTagCompound) tag.copy();
            tag.setString(HINT, hint);
            NBTTagCompound projected = HologramBlueprintNbt.canonicalActorPayload(cell.block(), tag, null, "", false);
            if (!before.equals(projected)) cell.tileNbt = HologramBlueprintNbt.encodeCanonical(projected);
            found = true;
        }
        if (!found) throw new IOException("Unknown blueprint quantum group");
    }

    static boolean descriptorKey(String key) {
        return GROUP.equals(key) || ROLE.equals(key) || HINT.equals(key) || ORIGINAL.equals(key) || MODE.equals(key);
    }

    static final class State {

        private final Map<String, Selection> selected = new LinkedHashMap<>();

        void clear() {
            selected.clear();
        }

        void select(EntityPlayerMP player, String group, int slot) throws IOException {
            if (!group.matches("q[1-9][0-9]{0,5}") || slot < 0 || slot >= player.inventory.mainInventory.length)
                throw new IOException("量子终端的背包槽位无效，请重新选择该类群使用的终端");
            ItemStack stack = player.inventory.mainInventory[slot];
            anchor(player, stack);
            selected.put(group, new Selection(slot, stack));
        }

        NBTTagCompound resolve(EntityPlayerMP player, String group) throws IOException {
            Selection selection = selected.get(group);
            if (selection == null) throw new IOException("量子类群 " + group + " 尚未绑定，请选择背包中已绑定真实网络的量子终端");
            ItemStack live = player.inventory.mainInventory[selection.slot];
            if (live == null || !live.isItemEqual(selection.stack))
                throw new IOException("所选背包槽位中的量子终端已变化，请重新为该类群选择终端");
            if (HologramGtswnQuantumAccess.modern() && terminal(live) && terminal(selection.stack)) {
                validateTerminalFields(live);
                validateTerminalFields(selection.stack);
                HologramGtswnQuantumAccess.synchronize(player, live);
                HologramGtswnQuantumAccess.synchronize(player, selection.stack);
            }
            if (!live.hasTagCompound() || !live.getTagCompound()
                .getBoolean("QT_Bound")
                || !anchorKey(live.getTagCompound(), TERMINAL_ANCHOR)
                    .equals(anchorKey(selection.stack.getTagCompound(), TERMINAL_ANCHOR))
                || HologramGtswnQuantumAccess.anchorGenerationAware() && !live.getTagCompound()
                    .getString("QT_AnchorGeneration")
                    .equals(
                        selection.stack.getTagCompound()
                            .getString("QT_AnchorGeneration")))
                throw new IOException("所选量子终端的目标网络已变化，请重新为该类群选择终端");
            return anchor(player, live); // Recheck only the selected network and structural availability.
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
            if (!tag.hasKey(field, 3)) throw new IOException("量子锚点坐标不完整或类型无效，请重新绑定终端");
            result.append(tag.getInteger(field))
                .append('/');
        }
        if (tag.getInteger(fields[2]) < 0 || tag.getInteger(fields[2]) > 255)
            throw new IOException("量子锚点高度超出世界范围，请重新绑定有效的控制器位置");
        return result.toString();
    }

    private static boolean carrier(ItemStack stack) {
        return terminal(stack);
    }

    private static boolean terminal(ItemStack stack) {
        return stack != null && stack.getItem() != null
            && TERMINAL.equals(
                stack.getItem()
                    .getClass()
                    .getName());
    }

    static void validateTerminalFields(ItemStack stack) throws IOException {
        NBTTagCompound tag = stack.getTagCompound();
        if (tag == null) return;
        if (tag.hasKey("QT_LastAppliedMigration")
            && (!tag.hasKey("QT_LastAppliedMigration", 4) || tag.getLong("QT_LastAppliedMigration") < 0L))
            throw new IOException("量子终端迁移游标无效，请核对原生终端");
        if (tag.hasKey("QT_Bound") && !tag.hasKey("QT_Bound", 1)) throw new IOException("量子终端绑定标记类型无效");
        if (tag.hasKey("QT_AnchorGeneration") && !validOwnerField(tag, "QT_AnchorGeneration", true))
            throw new IOException("量子终端网络世代格式无效");
        if (tag.getBoolean("QT_Bound")) anchorKey(tag, TERMINAL_ANCHOR);
    }

    /** A real bound terminal chooses a target network; security credentials never enter this projection. */
    private static NBTTagCompound anchor(EntityPlayerMP player, ItemStack stack) throws IOException {
        if (!carrier(stack) || stack.stackSize <= 0 || !stack.hasTagCompound())
            throw new IOException("请选择背包中已绑定目标网络的真实量子终端");
        validateTerminalFields(stack);
        if (HologramGtswnQuantumAccess.modern()) {
            HologramGtswnQuantumAccess.synchronize(player, stack);
        }
        NBTTagCompound tag = stack.getTagCompound();
        if (!tag.hasKey("QT_Bound", 1) || tag.getByte("QT_Bound") != 1)
            throw new IOException("该量子终端尚未绑定网络，请先在世界中绑定自己的量子网络控制器");
        return checkedAnchor(tag, TERMINAL_ANCHOR);
    }

    private static NBTTagCompound checkedAnchor(NBTTagCompound tag, String[] fields) throws IOException {
        anchorKey(tag, fields);
        int dim = tag.getInteger(fields[0]), x = tag.getInteger(fields[1]);
        int y = tag.getInteger(fields[2]), z = tag.getInteger(fields[3]);
        WorldServer world = DimensionManager.getWorld(dim);
        if (world == null || !world.blockExists(x, y, z)) throw new IOException("量子网络所在维度或锚点区块未加载，请先加载原控制器");
        TileEntity tile = world.getTileEntity(x, y, z);
        if (!(tile instanceof TileController) || !((IGridProxyable) tile).getProxy()
            .isReady()) throw new IOException("量子网络锚点处没有已就绪的 AE 控制器，请核对原控制器");
        try {
            Class<?> registry = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry");
            Object instance = registry.getMethod("get", World.class)
                .invoke(null, world);
            if (!(Boolean) registry.getMethod("isQuantized", int.class, int.class, int.class)
                .invoke(instance, x, y, z)) throw new IOException("锚点控制器尚未量子化，请先建立量子网络再绑定终端");
            if (!HologramGtswnQuantumAccess.currentController(world, x, y, z))
                throw new IOException("量子控制器世代已变化，不能沿用被替换控制器的登记状态");
            HologramGtswnQuantumAccess.checkAnchorGeneration(
                world,
                x,
                y,
                z,
                tag.getString(fields == TERMINAL_ANCHOR ? "QT_AnchorGeneration" : "anchorGeneration"));
        } catch (IOException failure) {
            throw failure;
        } catch (Exception | LinkageError failure) {
            throw new IOException("无法读取量子网络，请检查终端、控制器和模组接口；未扣除材料", failure);
        }
        NBTTagCompound clean = new NBTTagCompound();
        for (int i = 0; i < TERMINAL_ANCHOR.length; i++)
            clean.setInteger(TERMINAL_ANCHOR[i], tag.getInteger(fields[i]));
        clean.setBoolean("QT_Bound", true);
        if (HologramGtswnQuantumAccess.anchorGenerationAware()) clean.setString(
            "QT_AnchorGeneration",
            tag.getString(fields == TERMINAL_ANCHOR ? "QT_AnchorGeneration" : "anchorGeneration"));
        return clean;
    }

    static NBTTagCompound snapshot(HologramPresets.Preset preset, EntityPlayerMP player, State bindings)
        throws IOException {
        SnapshotTask task = new SnapshotTask(player, bindings);
        if (preset != null) for (HologramPresets.Cell cell : preset.cells) task.add(cell.tileNbt, cell.excluded);
        while (!task.step(Integer.MAX_VALUE, Long.MAX_VALUE)) {}
        return task.result;
    }

    /** World/security checks stay on the owner thread; each cell, group and inventory slot is a checkpoint. */
    static final class SnapshotTask {

        private final EntityPlayerMP player;
        private final State bindings;
        private final Map<String, NBTTagCompound> groups = new LinkedHashMap<>();
        final NBTTagCompound result = new NBTTagCompound();
        private final NBTTagList rows = new NBTTagList(), candidates = new NBTTagList();
        private java.util.Iterator<NBTTagCompound> iterator;
        private boolean terminalBinding, complete;
        private int slot, groupDone;

        SnapshotTask(EntityPlayerMP player, State bindings) {
            this.player = player;
            this.bindings = bindings;
            result.setBoolean("nativeAuthority", false);
            result.setBoolean("terminalSecurityChecked", false);
        }

        void add(String encoded, boolean excluded) throws IOException {
            if (excluded) return;
            add(HologramBlueprintNbt.decode(encoded));
        }

        /** Owner-thread decoded input is read only; every cell still performs its native binding checks. */
        void add(NBTTagCompound tag) throws IOException {
            if (tag == null || !tag.hasKey(GROUP)) return;
            String id = group(tag);
            NBTTagCompound row = groups.get(id);
            if (row == null) {
                row = new NBTTagCompound();
                row.setString("id", id);
                row.setString("hint", hint(tag));
                row.setString("prompt", checkedHint(tag.getString(HINT)));
                try {
                    resolve(tag, player, bindings);
                    row.setBoolean("bound", true);
                } catch (IOException invalid) {
                    row.setBoolean("bound", false);
                    row.setString("reason", HologramAePolicy.fallback(invalid.getMessage()));
                }
                groups.put(id, row);
            }
            row.setString("name", checkedHint(tag.getString(HINT)).isEmpty() ? "默认网络" : tag.getString(HINT));
            row.setBoolean("terminalBinding", !"preserve".equals(tag.getString(MODE)) && !tag.hasKey(ORIGINAL));
            if (row.getBoolean("bound")) try {
                resolve(tag, player, bindings);
            } catch (IOException unavailable) {
                row.setBoolean("bound", false);
                row.setString("reason", unavailable.getMessage());
            }
            row.setInteger("cells", row.getInteger("cells") + 1);
        }

        boolean step(int maxWork, long deadline) throws IOException {
            if (iterator == null) iterator = groups.values()
                .iterator();
            for (int work = 0; work < maxWork && !complete; work++) {
                if (work > 0 && System.nanoTime() >= deadline) return false;
                if (iterator.hasNext()) {
                    NBTTagCompound row = iterator.next();
                    rows.appendTag(row);
                    terminalBinding |= row.getBoolean("terminalBinding");
                    groupDone++;
                } else if (terminalBinding && slot < player.inventory.mainInventory.length) appendInventory(slot++);
                else {
                    result.setTag("groups", rows);
                    result.setBoolean("terminalBinding", terminalBinding);
                    result.setTag("inventory", candidates);
                    complete = true;
                }
            }
            return complete;
        }

        long done() {
            return groupDone + slot;
        }

        long total() {
            return groups.size() + (terminalBinding ? player.inventory.mainInventory.length : 0);
        }

        private void appendInventory(int slot) throws IOException {
            ItemStack stack = player.inventory.mainInventory[slot];
            if (stack == null || stack.getItem() == null || stack.stackSize <= 0) return;
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
    }

    static void validate(NBTTagCompound payload, EntityPlayerMP player, State state) throws IOException {
        if (payload != null && payload.hasKey(GROUP)) resolve(payload, player, state);
    }

    static boolean matchesBinding(TileEntity actual, NBTTagCompound payload, EntityPlayerMP player, State state)
        throws IOException {
        if (payload == null || !payload.hasKey(GROUP)) return true;
        if (actual == null) return false;
        NBTTagCompound bound = resolve(payload, player, state);
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
                if (HologramGtswnQuantumAccess.namedNode(actual) && (!player.getUniqueID()
                    .toString()
                    .equals(
                        actual.getClass()
                            .getMethod("getPlacerUUID")
                            .invoke(actual))
                    || !player.getCommandSenderName()
                        .equals(
                            actual.getClass()
                                .getMethod("getPlacerName")
                                .invoke(actual))))
                    return false;
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
            throw new IOException("无法核对目标设备现有的量子绑定，请检查模组接口与锚点状态", failure);
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
        NBTTagCompound bound = resolve(payload, player, state);
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
        NBTTagCompound bound = resolve(payload, player, state);
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
