package com.miaokatze.gtmb.hologram;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** A fresh real-player socket fixture. Observations never mutate a Service session. */
public final class HologramV011InteractionFixture {

    private EntityPlayerMP player;
    private int ticks, lastSequence;
    private boolean finished;
    private final NBTTagCompound source = new NBTTagCompound();

    public static void install() {
        if (Boolean.getBoolean("gtmb.hologram.followupAreaServer")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramV011InteractionFixture());
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = MinecraftServer.getServer();
        try {
            require(++ticks <= 12000, "area server fixture exceeded ten minutes");
            WorldServer world = server.worldServerForDimension(0);
            require(
                world.getWorldInfo()
                    .getWorldName()
                    .startsWith("hologram-followup-"),
                "isolated world name");
            if (HologramV011Evidence.directory()
                .resolve("area-client-fail.txt")
                .toFile()
                .isFile()) throw new AssertionError("client reported a fixture failure");
            if (player == null) {
                if (server.getConfigurationManager().playerEntityList.isEmpty()) return;
                require(server.getConfigurationManager().playerEntityList.size() == 1, "exactly one fixture player");
                player = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
                for (int x = 49; x <= 52; x++) {
                    world.getChunkFromBlockCoords(x, 44);
                    require(world.isAirBlock(x, 80, 44), "new world source positions are air");
                    require(world.setBlock(x, 80, 44, Blocks.stone, 0, 3), "actual source stone installed");
                }
                require(world.isAirBlock(50, 79, 46), "new world footing position is air");
                require(
                    world.setBlock(50, 79, 46, Blocks.stone, 0, 3),
                    "actual footing supports downward hit and visible feet cursor");
                player.theItemInWorldManager.setGameType(net.minecraft.world.WorldSettings.GameType.SURVIVAL);
                player.capabilities.allowFlying = true;
                player.capabilities.isFlying = true;
                player.sendPlayerAbilities();
                player.playerNetServerHandler.setPlayerLocation(50.5, 80, 46.5, 180, 0);
                for (int i = 0; i < player.inventory.mainInventory.length; i++)
                    player.inventory.mainInventory[i] = null;
                player.inventory.mainInventory[0] = new ItemStack(
                    (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector"));
                player.inventory.mainInventory[1] = new ItemStack(Items.stick, 3);
                player.inventory.currentItem = 0;
                player.inventoryContainer.detectAndSendChanges();
                source.setString(
                    "actor",
                    player.getUniqueID()
                        .toString());
                source
                    .setString("fixtureKind", "real Netty player; server initializes only native source and inventory");
                source.setString(
                    "world",
                    world.getWorldInfo()
                        .getWorldName());
                source.setTag("inventory", inventory(player));
                bindEvidence(source, "server", "", -1);
                HologramV011Evidence.write("area-server-ready.nbt", source);
                return;
            }
            for (int x = 49; x <= 52; x++) require(
                world.getBlock(x, 80, 44) == Blocks.stone && world.getBlockMetadata(x, 80, 44) == 0,
                "area selection preserves every real source block");
            require(
                world.getBlock(50, 79, 46) == Blocks.stone && world.getBlockMetadata(50, 79, 46) == 0,
                "selection and preview preserve the real footing stone");
            require(
                player.inventory.mainInventory[0] != null && player.inventory.mainInventory[0].stackSize == 1
                    && com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector
                        .isProjector(player.inventory.mainInventory[0]),
                "native projector item identity and count remain unchanged");
            require(
                player.inventory.mainInventory[1] != null && player.inventory.mainInventory[1].getItem() == Items.stick
                    && player.inventory.mainInventory[1].stackSize == 3,
                "ordinary held inventory remains unchanged");
            NBTTagCompound request = HologramV011Evidence.read("area-client-observe.nbt");
            if (request != null && request.getInteger("sequence") > lastSequence) {
                if (player.inventory.currentItem != request.getInteger("heldSlot")) return; // Wait for the actual C09.
                Object session = sessions().get(player.getUniqueID());
                require(
                    session != null && request.getString("session")
                        .equals(field(session, "id")),
                    "observed original Service session");
                if ((Long) field(session, "planRevision") != request.getLong("planRevision")) return;
                HologramArea area = areas().get(player.getUniqueID());
                boolean selecting = area != null && area.selecting;
                int[] points = area == null ? new int[0] : area.points();
                if (selecting != request.getBoolean("selecting")
                    || !Arrays.equals(points, request.getIntArray("points"))) return;
                boolean placement = (Boolean) field(session, "virtualPlacement"),
                    locked = (Boolean) field(session, "virtualLocked");
                int[] anchor = { (Integer) field(session, "x"), (Integer) field(session, "y"),
                    (Integer) field(session, "z") };
                if (placement != request.getBoolean("virtualPlacement") || locked != request.getBoolean("virtualLocked")
                    || !Arrays.equals(anchor, request.getIntArray("serverAnchor"))) return;
                if ("locked".equals(request.getString("cursorKind"))) require(
                    locked && placement && Arrays.equals(anchor, request.getIntArray("expectedTarget")),
                    "actual server lock equals the client's previously drawn Alt target");
                NBTTagCompound input = request.getCompoundTag("nativeInput"), packet = input.getCompoundTag("packet");
                if (request.getInteger("sequence") >= 9 && request.getInteger("sequence") <= 16) {
                    require(
                        "areaPoint".equals(packet.getString("action")) && packet.getString("session")
                            .equals(request.getString("session")),
                        "matrix corner comes from the actual observed native action packet");
                    int[] sent = { packet.getInteger("x"), packet.getInteger("y"), packet.getInteger("z") };
                    require(
                        Arrays.equals(sent, request.getIntArray("expectedTarget")),
                        "actual transmitted corner equals requested native target");
                    if (packet.getBoolean("atPlayer")) require(
                        Arrays.equals(
                            sent,
                            new int[] { (int) Math.floor(player.posX), (int) Math.floor(player.posY),
                                (int) Math.floor(player.posZ) }),
                        "actual foot packet also equals independent server feet");
                    else if (input.getInteger("distance") == 0 && input.getBoolean("nativeBlockHit")
                        && !input.getBoolean("alt"))
                        require(
                            Arrays.equals(sent, input.getIntArray("hitBlock")),
                            "actual zero-distance non-Alt packet retains the independent block corner");
                }
                NBTTagCompound witness = (NBTTagCompound) source.copy();
                witness.setInteger("sequence", request.getInteger("sequence"));
                witness.setString("label", request.getString("label"));
                witness.setString("session", (String) field(session, "id"));
                witness.setLong("planRevision", (Long) field(session, "planRevision"));
                witness.setLong("worldTick", world.getTotalWorldTime());
                witness.setInteger("heldSlot", player.inventory.currentItem);
                witness.setBoolean("selecting", selecting);
                witness.setIntArray("points", points);
                witness.setTag("regions", area == null ? new NBTTagList() : area.regionSnapshot());
                witness.setBoolean("virtualPlacement", placement);
                witness.setBoolean("virtualLocked", locked);
                witness.setInteger("job", (Integer) field(session, "job"));
                witness.setIntArray("serverAnchor", anchor);
                witness.setIntArray(
                    "feet",
                    new int[] { (int) Math.floor(player.posX), (int) Math.floor(player.posY),
                        (int) Math.floor(player.posZ) });
                witness.setTag("observedNativePacket", packet.copy());
                witness.setString("clientSourceLaunchSha256", request.getString("sourceLaunchSha256"));
                witness.setTag("inventory", inventory(player));
                witness.setBoolean("sourceUnchanged", true);
                witness.setBoolean("passed", true);
                bindEvidence(witness, "server", (String) field(session, "id"), (Long) field(session, "planRevision"));
                lastSequence = request.getInteger("sequence");
                HologramV011Evidence.write("area-server-step-" + lastSequence + ".nbt", witness);
            }
            NBTTagCompound complete = HologramV011Evidence.read("area-client-final.nbt");
            if (complete != null) {
                require(
                    complete.getBoolean("passed") && lastSequence == 23 && complete.getInteger("observations") == 23,
                    "all independent server observations completed");
                HologramArea area = areas().get(player.getUniqueID());
                require(
                    area == null || !area.selecting && !area.importing,
                    "both Esc paths leave no active area operation");
                NBTTagCompound result = (NBTTagCompound) source.copy();
                result.setInteger("observations", lastSequence);
                result.setBoolean("passed", true);
                result.setBoolean("sourceUnchanged", true);
                result.setTag("inventory", inventory(player));
                Object session = sessions().get(player.getUniqueID());
                require(
                    session != null && !(Boolean) field(session, "virtualPlacement"),
                    "final Esc leaves no unpaid placement operation");
                bindEvidence(result, "server", (String) field(session, "id"), (Long) field(session, "planRevision"));
                HologramV011Evidence.write("area-server-final.nbt", result);
                HologramV011Evidence.marker(
                    "area-server-pass.txt",
                    "PASS actual Netty area/placement hit-air matrix, reverse corners, modifiers, wheel, pause/resume and Esc paths");
                finished = true;
                server.initiateShutdown();
            }
        } catch (Throwable failure) {
            failure.printStackTrace();
            finished = true;
            try {
                HologramV011Evidence.marker("area-server-fail.txt", "FAIL " + failure);
            } catch (Exception evidenceFailure) {
                failure.addSuppressed(evidenceFailure);
            }
            server.initiateShutdown();
        }
    }

    /** Each observation binds to the current role's immutable launch descriptor and its source hashes. */
    public static void bindEvidence(NBTTagCompound data, String role, String session, long revision) throws Exception {
        require("client".equals(role) || "server".equals(role), "bounded source launch role");
        Path launch = HologramV011Evidence.directory()
            .getParent()
            .resolve(role + "-launch.json");
        require(Files.isRegularFile(launch), "actual role launch descriptor exists before evidence is written");
        byte[] hash = MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(launch));
        StringBuilder hex = new StringBuilder();
        for (byte value : hash) hex.append(String.format("%02x", value & 255));
        data.setString("releaseVersion", "0.1.1");
        data.setString("sourceLaunchSha256", hex.toString());
        data.setString("sourceRole", role);
        data.setString("session", session);
        data.setLong("planRevision", revision);
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> sessions() throws Exception {
        return (Map<UUID, Object>) field(HologramService.class, "SESSIONS");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, HologramArea> areas() throws Exception {
        return (Map<UUID, HologramArea>) field(HologramService.class, "AREAS");
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = (owner instanceof Class ? (Class<?>) owner : owner.getClass()).getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner instanceof Class ? null : owner);
    }

    private static NBTTagList inventory(EntityPlayerMP actor) {
        NBTTagList result = new NBTTagList();
        for (int i = 0; i < actor.inventory.mainInventory.length; i++) {
            ItemStack stack = actor.inventory.mainInventory[i];
            if (stack == null) continue;
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("slot", i);
            stack.writeToNBT(row);
            result.appendTag(row);
        }
        return result;
    }

    private static void require(boolean condition, String message) {
        HologramV011Evidence.require(condition, message);
    }
}
