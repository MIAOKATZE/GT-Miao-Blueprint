package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.FakePlayerFactory;

import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.core.worlddata.WorldData;
import appeng.me.helpers.IGridProxyable;

/** Opt-in full-pack test: real registered GTSWN nodes, incorporation registry and Service material transaction. */
public final class HologramNetworkBindingSmoke {

    private static final String PREFIX = "[GTMB-NETWORK-BINDING] ";

    private HologramNetworkBindingSmoke() {}

    public static void run(WorldServer world) throws Exception {
        require(
            world.getWorldInfo()
                .getWorldName()
                .startsWith("hologram-smoke-"),
            "isolated_world_guard");
        Block nodeBlock = null;
        for (Object candidate : Block.blockRegistry)
            if (candidate instanceof Block && HologramNetworkBindings.node((Block) candidate))
                nodeBlock = (Block) candidate;
        require(nodeBlock != null, "actual_gtswn_node_registered");
        Class<?> registryType = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumIncorporationRegistry");
        Class<?> controllers = Class.forName("com.miaokatze.gtswn.common.quantum.QuantumControllerRegistry");
        Object controllerRegistry = controllers.getMethod("get", World.class)
            .invoke(null, world);
        Object incorporation = registryType.getMethod("get", World.class)
            .invoke(null, world);
        EntityPlayerMP actor = actor(world, "GTMBNetworkPlace", "927fd84d-cb6a-42bf-a28e-25ac0a8a32e2");
        EntityPlayerMP sourceOwner = actor(world, "GTMBNetworkSource", "2701d0c1-9df8-44db-a9cc-fcd2d3ef8a52");
        actor.inventory.mainInventory = new ItemStack[36];
        actor.inventory.mainInventory[0] = new ItemStack(
            (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
        actor.inventory.currentItem = 0;
        actor.capabilities.isCreativeMode = false;
        actor.capabilities.allowEdit = true;
        actor.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
        int y = 88, z = 464;
        int[] anchors = { 800, 816, 832, 848 };
        Block controller = AEApi.instance()
            .definitions()
            .blocks()
            .controller()
            .maybeBlock()
            .get();
        for (int x : anchors) {
            world.getChunkFromBlockCoords(x, z);
            require(world.setBlock(x, y, z, controller, 0, 3), "real_anchor_" + x);
            ((IGridProxyable) world.getTileEntity(x, y, z)).getProxy()
                .setOwner(actor);
            ((IGridProxyable) world.getTileEntity(x, y, z)).getProxy()
                .onReady();
            controllers.getMethod("quantize", int.class, int.class, int.class)
                .invoke(controllerRegistry, x, y, z);
        }
        Block iface = AEApi.instance()
            .definitions()
            .blocks()
            .iface()
            .maybeBlock()
            .get();
        Block[] blocks = { nodeBlock, iface, nodeBlock };
        int[] sourceX = { 880, 884, 888 };
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.schema = 2;
        source.controllerType = "area";
        NBTTagCompound sourceTerminal = terminal(world, anchors[0], y, z);
        for (int i = 0; i < blocks.length; i++) {
            world.getChunkFromBlockCoords(sourceX[i], z);
            require(world.setBlock(sourceX[i], y, z, blocks[i], 0, 3), "source_" + i);
            TileEntity tile = world.getTileEntity(sourceX[i], y, z);
            if (i != 1) {
                tile.getClass()
                    .getMethod("setAnchor", int.class, int.class, int.class, int.class)
                    .invoke(tile, world.provider.dimensionId, anchors[i == 0 ? 0 : 1], y, z);
                tile.getClass()
                    .getMethod("setPlacer", EntityPlayer.class)
                    .invoke(tile, sourceOwner);
            } else {
                ((IGridProxyable) tile).getProxy()
                    .setOwner(sourceOwner);
                ((IGridProxyable) tile).getProxy()
                    .onReady();
                require(
                    (Boolean) registryType
                        .getMethod(
                            "add",
                            World.class,
                            int.class,
                            int.class,
                            int.class,
                            NBTTagCompound.class,
                            EntityPlayer.class)
                        .invoke(incorporation, world, sourceX[i], y, z, sourceTerminal, sourceOwner),
                    "source_join_registration");
            }
            NBTTagCompound raw = HologramNativeCapture.capture(tile);
            HologramPresets.Cell cell = HologramPresets.cell(new ItemStack(blocks[i]), i * 4, 0, 0, -1, false);
            cell.block = String.valueOf(Block.blockRegistry.getNameForObject(blocks[i]));
            cell.tileNbt = HologramBlueprintNbt.encodeCanonical(raw);
            source.cells.add(cell);
        }
        List<NBTTagCompound> original = new ArrayList<>();
        for (int x : sourceX) original.add(HologramNativeCapture.capture(world.getTileEntity(x, y, z)));
        HologramPresets.Preset portable = HologramAePolicy.sanitize(source).preset;
        String groupA = payload(portable, 0).getString(HologramNetworkBindings.GROUP);
        String groupB = payload(portable, 2).getString(HologramNetworkBindings.GROUP);
        require(
            groupA.equals(payload(portable, 1).getString(HologramNetworkBindings.GROUP)),
            "node_and_join_share_source_group");
        require(!groupA.equals(groupB), "two_source_networks_are_independent");
        for (int i = 0; i < blocks.length; i++) {
            require(
                original.get(i)
                    .equals(HologramNativeCapture.capture(world.getTileEntity(sourceX[i], y, z))),
                "sanitization_never_changes_source_" + i);
            NBTTagCompound clean = payload(portable, i);
            require(
                !clean.hasKey("anchorDim") && !clean.hasKey("GTSWN_IncorporationIdentity")
                    && !clean.hasKey(HologramNetworkBindings.CAPTURE_ANCHOR),
                "portable_identities_removed_" + i);
            int destination = 928 + i * 4;
            world.getChunkFromBlockCoords(destination, z);
            ItemStack primary = new ItemStack(blocks[i]);
            Object session = fixture(
                "cell",
                new Class<?>[] { Block.class, int.class, NBTTagCompound.class, ItemStack.class, int.class, int.class,
                    int.class },
                blocks[i],
                0,
                clean,
                primary,
                destination,
                y,
                z);
            HologramCapture.Cell cell = (HologramCapture.Cell) session;
            session = fixture(
                "session",
                new Class<?>[] { EntityPlayerMP.class, HologramCapture.Cell.class, ItemStack.class },
                actor,
                cell,
                primary);
            List<ItemStack> costs = HologramAeBlueprint.resourcePlan(blocks[i], 0, clean, null).items;
            fixture("fill", new Class<?>[] { EntityPlayerMP.class, List.class, boolean.class }, actor, costs, false);
            actor.inventory.mainInventory[20] = coordinateNode(nodeBlock, world, anchors[2], y, z);
            actor.inventory.mainInventory[21] = coordinateNode(nodeBlock, world, anchors[3], y, z);
            HologramNetworkBindings.State bindings = (HologramNetworkBindings.State) field(session, "networkBindings");
            bindings.select(actor, groupA, 20);
            bindings.select(actor, groupB, 21);
            actor.setPosition(destination + 0.5, y, z + 0.5);
            require(
                (Boolean) fixture(
                    "operate",
                    new Class<?>[] { Object.class, HologramCapture.Cell.class },
                    session,
                    cell),
                "service_installs_and_pays_" + i + " status=" + field(session, "status"));
            TileEntity placed = world.getTileEntity(destination, y, z);
            require(
                placed != null && placed.getClass()
                    .equals(
                        world.getTileEntity(sourceX[i], y, z)
                            .getClass()),
                "real_registered_factory_installed_" + i);
            if (i != 1) {
                require(
                    (Integer) placed.getClass()
                        .getMethod("getAnchorX")
                        .invoke(placed) == anchors[i == 0 ? 2 : 3],
                    "node_uses_inventory_coordinates_" + i);
                ((IGridProxyable) placed).getProxy()
                    .onReady();
                NBTTagCompound owned = new NBTTagCompound();
                placed.writeToNBT(owned);
                require(
                    owned.getCompoundTag("proxy")
                        .getInteger("p")
                        == WorldData.instance()
                            .playerData()
                            .getPlayerID(actor.getGameProfile()),
                    "node_placer_owner_before_network_join_" + i);
            } else {
                require(
                    (Boolean) registryType.getMethod("isIncorporated", World.class, int.class, int.class, int.class)
                        .invoke(null, world, destination, y, z),
                    "service_registers_actual_join");
                String identity = (String) placed.getClass()
                    .getMethod("gtswn$getIncorporationIdentity")
                    .invoke(placed);
                boolean owned = false;
                for (Object entry : (Iterable<?>) registryType.getMethod("snapshot")
                    .invoke(incorporation))
                    if (identity.equals(
                        entry.getClass()
                            .getMethod("identity")
                            .invoke(entry))) {
                                owned = actor.getUniqueID()
                                    .toString()
                                    .equals(field(entry, "owner"));
                                require(
                                    entry.getClass()
                                        .getField("ax")
                                        .getInt(entry) == anchors[2],
                                    "join_uses_inventory_coordinates");
                            }
                require(owned, "join_owner_is_actual_placer");
                HologramNetworkBindings.rollbackJoin(placed, actor);
                require(
                    !(Boolean) registryType.getMethod("isIncorporated", World.class, int.class, int.class, int.class)
                        .invoke(null, world, destination, y, z),
                    "new_join_rollback_removes_only_new_registration");
                require(
                    (Boolean) registryType.getMethod("isIncorporated", World.class, int.class, int.class, int.class)
                        .invoke(null, world, sourceX[1], y, z),
                    "new_join_rollback_preserves_original_registration");
            }
            require(
                actor.inventory.mainInventory[20] != null && actor.inventory.mainInventory[21] != null,
                "coordinate_carriers_are_never_consumed_as_free_network_authorization");
            NBTTagCompound dto = HologramNetworkBindings.snapshot(portable, actor, bindings);
            require(
                dto.getTagList("groups", 10)
                    .tagCount() == 2,
                "two_groups_in_inventory_selection_dto");
            require(
                !dto.toString()
                    .contains("QT_AnchorX")
                    && !dto.toString()
                        .contains("anchorDim"),
                "inventory_ui_does_not_expose_network_coordinates");
            actor.inventory.mainInventory[20].getTagCompound()
                .setInteger("anchorX", anchors[1]);
            try {
                bindings.resolve(actor, groupA);
                throw new AssertionError("changed carrier must reject");
            } catch (java.io.IOException expected) {
                require(
                    expected.getMessage()
                        .contains("changed"),
                    "server_rechecks_exact_carrier");
            }
        }
        System.out.println(
            PREFIX
                + "FINAL PASS actual_registered_gtswn node + join Service transaction two_groups owner source_preserved inventory_revalidated");
    }

    private static EntityPlayerMP actor(WorldServer world, String name, String uuid) {
        return FakePlayerFactory.get(world, new GameProfile(UUID.fromString(uuid), name));
    }

    private static ItemStack coordinateNode(Block block, WorldServer world, int x, int y, int z) {
        ItemStack stack = new ItemStack(block);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("anchorDim", world.provider.dimensionId);
        tag.setInteger("anchorX", x);
        tag.setInteger("anchorY", y);
        tag.setInteger("anchorZ", z);
        stack.setTagCompound(tag);
        return stack;
    }

    private static NBTTagCompound terminal(WorldServer world, int x, int y, int z) {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("QT_Bound", true);
        tag.setInteger("QT_AnchorDim", world.provider.dimensionId);
        tag.setInteger("QT_AnchorX", x);
        tag.setInteger("QT_AnchorY", y);
        tag.setInteger("QT_AnchorZ", z);
        return tag;
    }

    private static NBTTagCompound payload(HologramPresets.Preset preset, int index) throws Exception {
        return HologramBlueprintNbt.decode(preset.cells.get(index).tileNbt);
    }

    private static Object fixture(String name, Class<?>[] parameters, Object... arguments) throws Exception {
        Method method = HologramNativePlacementSmoke.class.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method.invoke(null, arguments);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass()
            .getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void require(boolean condition, String name) {
        if (!condition) throw new IllegalStateException(PREFIX + "FAIL " + name);
        System.out.println(PREFIX + "PASS " + name);
    }
}
