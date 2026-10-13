package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;

import com.miaokatze.gtmb.hologram.HologramAeWorldPreviewFixture;
import com.miaokatze.gtmb.hologram.HologramDecorSourceSmoke;
import com.miaokatze.gtmb.hologram.HologramService;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Tiny opt-in packaged client witness: native world -> Area -> disk -> network -> real editor. */
public final class HologramProductionPreviewSmoke {

    private static final String PREFIX = "[GTMB-PRODUCTION-PREVIEW] ";
    private static boolean installed;
    private final long started = System.nanoTime();
    private volatile MinecraftServer server;
    private volatile String editedSession;
    private volatile boolean rendered, serverDone;
    private volatile Throwable failure;
    private Path output;
    private boolean launched, muted;
    private volatile boolean stopped;
    private int serverStage, settling, renderStage, frames;
    private EntityPlayerMP actor;
    private HologramAeWorldPreviewFixture.Fixture ae;
    private HologramDecorSourceSmoke.Source decor;
    private NBTTagCompound sources;
    private String savedId;
    private long loginTick;

    private HologramProductionPreviewSmoke() {}

    public static void install() {
        if (!installed && Boolean.getBoolean("gtmb.hologram.productionPreviewSmoke")) {
            installed = true;
            FMLCommonHandler.instance()
                .bus()
                .register(new HologramProductionPreviewSmoke());
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stopped) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (!muted) {
                mc.gameSettings.setSoundLevel(SoundCategory.MASTER, 0);
                mc.gameSettings.setSoundLevel(SoundCategory.MUSIC, 0);
                mc.gameSettings.setSoundLevel(SoundCategory.RECORDS, 0);
                mc.gameSettings.pauseOnLostFocus = false;
                muted = true;
            }
            if (output == null) {
                String configured = System.getProperty("gtmb.hologram.productionPreviewOutput", "");
                output = configured.isEmpty()
                    ? new File(System.getProperty("gtmb.hologram.packagedProbeDir", mc.mcDataDir.getPath()), "receipts")
                        .toPath()
                    : new File(configured).toPath();
                Files.createDirectories(output);
            }
            if (failure != null) {
                fail(failure);
                stopped = true;
                mc.shutdown();
                return;
            }
            if (serverDone) {
                Files.write(
                    output.resolve("PASS.txt"),
                    ("PASS native world/Area/save/disk reload/network/editor target+current 7 cells; source unchanged\n"
                        + "preset="
                        + savedId
                        + "\n").getBytes(StandardCharsets.UTF_8));
                System.out.println(PREFIX + "PASS preset=" + savedId + " receipts=" + output.toAbsolutePath());
                stopped = true;
                mc.shutdown();
                return;
            }
            if (System.nanoTime() - started > 900000000000L) throw new IllegalStateException(
                "production preview timed out at server=" + serverStage + "/render=" + renderStage);
            // Only the two known main menu implementations may launch the isolated world.
            boolean mainMenu = mc.currentScreen instanceof GuiMainMenu
                || mc.currentScreen != null && mc.currentScreen.getClass()
                    .getName()
                    .equals("lumien.custommainmenu.gui.GuiCustom");
            if (!launched && mainMenu) {
                launched = true;
                System.out.println(
                    PREFIX + "startup guiClass="
                        + mc.currentScreen.getClass()
                            .getName()
                        + " viewport="
                        + mc.displayWidth
                        + "x"
                        + mc.displayHeight
                        + " muted=true");
                String name = "hologram-production-" + System.currentTimeMillis();
                WorldSettings settings = new WorldSettings(
                    107L,
                    WorldSettings.GameType.CREATIVE,
                    false,
                    false,
                    WorldType.FLAT);
                settings.enableCommands();
                mc.launchIntegratedServer(name, name, settings);
                server = mc.getIntegratedServer();
                System.out.println(PREFIX + "fresh integrated world=" + name);
            }
            if (editedSession != null && mc.currentScreen instanceof HologramScreen
                && renderStage == 0
                && HologramClient.state != null
                && editedSession.equals(HologramClient.state.session)) {
                // These are local view controls on the genuine network-opened screen, not server state.
                field(HologramScreen.class, "libraryDrawer").setBoolean(mc.currentScreen, false);
                field(HologramScreen.class, "view").setInt(mc.currentScreen, 0);
                renderStage = 1;
                frames = 0;
            }
        } catch (Throwable problem) {
            failure = problem;
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stopped || failure != null || server == null) return;
        try {
            if (actor == null) {
                if (server.getConfigurationManager().playerEntityList.isEmpty()) return;
                actor = (EntityPlayerMP) server.getConfigurationManager().playerEntityList.get(0);
                WorldServer world = (WorldServer) actor.worldObj;
                require(
                    world.getWorldInfo()
                        .getWorldName()
                        .startsWith("hologram-production-"),
                    "fresh isolated world guard");
                actor.theItemInWorldManager.setGameType(WorldSettings.GameType.CREATIVE);
                actor.capabilities.isFlying = true;
                actor.setPositionAndUpdate(12.5, 82, 14.5);
                actor.sendPlayerAbilities();
                Item item = (Item) Item.itemRegistry.getObject("gtmb:neko_hologram_projector");
                require(item != null, "registered projector required");
                actor.inventory.currentItem = 0;
                actor.inventory.mainInventory[0] = new ItemStack(item);
                actor.inventory.markDirty();
                actor.inventoryContainer.detectAndSendChanges();
                loginTick = world.getTotalWorldTime();
                System.out.println(
                    PREFIX + "native login initialization actor="
                        + actor.getUniqueID()
                        + " worldTick="
                        + loginTick
                        + "; waiting 20 world ticks before source placement");
                return;
            }
            if (serverStage == 0) {
                WorldServer world = (WorldServer) actor.worldObj;
                if (world.getTotalWorldTime() - loginTick < 20) return;
                decor = HologramDecorSourceSmoke.create(world, 8, 80, 8);
                ae = HologramAeWorldPreviewFixture.create(world, actor, 16, 80, 8);
                serverStage = 1;
                System.out.println(PREFIX + "native sources placed: decor=4 AE=3 (official CBC/FMP host)");
                return;
            }
            if (serverStage == 1) {
                if (++settling < 10) return;
                sources = ae.checkpoint();
                NBTTagList decoration = new NBTTagList();
                for (int i = 0; i < 4; i++) decoration.appendTag(decor.sourceNbt(i));
                sources.setTag("decor", decoration);
                write("source-native.nbt", sources);
                HologramService.beginAreaSelection(actor);
                HologramService.areaPoint(actor, 1, 8, 80, 8, false);
                HologramService.areaPoint(actor, 2, 19, 80, 8, false);
                HologramService.finishAreaSelection(actor, request(""));
                serverStage = 2;
                System.out.println(PREFIX + "real Area import started");
            } else if (serverStage == 2) {
                Object session = session();
                if (session == null || !Boolean.TRUE.equals(read(session, "virtual"))
                    || Boolean.TRUE.equals(read(session, "capturePending"))
                    || read(session, "capture") == null) return;
                Object capture = read(session, "capture");
                require(!Boolean.TRUE.equals(read(capture, "incomplete")), "complete native Area capture");
                require(
                    ((java.util.List<?>) read(capture, "cells")).size() == 7,
                    "Area collected exactly seven native sources");
                NBTTagCompound configuration = request("configureBlueprintNbt");
                configuration.setBoolean("all", true);
                HologramService.handle(actor, configuration);
                NBTTagCompound save = request("saveBlueprintAs");
                save.setString("name", "Production native preview");
                save.setBoolean("retainNbt", true);
                save.setBoolean("retainCovers", true);
                save.setBoolean("retainAllNbt", true);
                HologramService.handle(actor, save);
                serverStage = 3;
            } else if (serverStage == 3) {
                Object session = session();
                Object review = read(read(session, "ae"), "review");
                require(review != null, "real AE save review issued");
                NBTTagCompound confirmation = request("confirmAeSave");
                confirmation.setString("transactionId", (String) read(review, "transactionId"));
                HologramService.handle(actor, confirmation);
                require(Boolean.TRUE.equals(read(session(), "blueprintOperationSuccess")), "confirmed save succeeded");
                savedId = (String) read(read(session(), "virtualPreset"), "id");
                require(savedId != null && !savedId.isEmpty(), "saved blueprint id issued");
                Path library = new File("config/gtmb/hologram-presets.json").toPath();
                require(Files.isRegularFile(library), "real library file written");
                Files.copy(library, output.resolve("saved-library.json"), StandardCopyOption.REPLACE_EXISTING);
                // Evict only the library cache to force the ordinary edit operation to read the actual saved disk file.
                field(Class.forName("com.miaokatze.gtmb.hologram.HologramPresets"), "cached").set(null, null);
                NBTTagCompound edit = request("editPreset");
                edit.setString("presetId", savedId);
                HologramService.handle(actor, edit);
                editedSession = (String) read(session(), "id");
                require(savedId.equals(read(read(session(), "virtualPreset"), "id")), "saved disk blueprint opened");
                serverStage = 4;
                System.out.println(PREFIX + "confirmed save/disk reload/network open preset=" + savedId);
            } else if (serverStage == 4 && rendered) {
                decor.assertUnchanged();
                ae.assertUnchanged();
                sources.setBoolean("previewSaveSourceUnchanged", true);
                write("source-native.nbt", sources);
                serverDone = true;
                serverStage = 5;
            }
        } catch (Throwable problem) {
            failure = problem;
        }
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stopped || failure != null || renderStage == 0 || rendered) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (!(mc.currentScreen instanceof HologramScreen) || HologramClient.state == null
            || !editedSession.equals(HologramClient.state.session)) return;
        try {
            if (++frames < 3) return;
            HologramState state = HologramClient.state;
            require(
                state.cells.size() == 7 && state.data.getBoolean("supported")
                    && !state.data.getBoolean("capturePending"),
                "complete seven-cell network snapshot");
            boolean current = renderStage == 2;
            write("received-service-state.nbt", state.data);
            String name = current ? "production-native-current.png" : "production-native-target.png";
            ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            Path screenshot = new File(new File(mc.mcDataDir, "screenshots"), name).toPath();
            require(Files.isRegularFile(screenshot) && Files.size(screenshot) > 0, "actual editor screenshot saved");
            Files.copy(screenshot, output.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            NBTTagCompound receipt = HologramAeWorldPreviewVerification.verify(state.data, current);
            receipt.setTag("allNative", verifyAll(state, current));
            write(current ? "current-receipt.nbt" : "target-receipt.nbt", receipt);
            System.out.println(PREFIX + "native models PASS current=" + current + " cells=7 screenshot=" + name);
            if (!current) {
                field(HologramScreen.class, "view").setInt(mc.currentScreen, 1);
                renderStage = 2;
                frames = 0;
            } else {
                rendered = true;
                renderStage = 3;
            }
        } catch (Throwable problem) {
            failure = problem;
        }
    }

    private static NBTTagList verifyAll(HologramState state, boolean current) {
        HologramBlockRender.begin(state);
        NBTTagList rows = new NBTTagList();
        int bareSlopes = 0, coveredSlopes = 0, brickRoofs = 0, quartzRoofs = 0;
        for (HologramState.Cell cell : state.cells) {
            require(
                HologramBlockRender.ready(cell, current),
                "real model unavailable " + cell.index + ": " + HologramBlockRender.failure(cell, current));
            require(!HologramBlockRender.missingTexture(cell, current), "missing native atlas " + cell.index);
            require(HologramBlockRender.vertexCount(cell, current) > 0, "empty native geometry " + cell.index);
            NBTTagCompound row = new NBTTagCompound();
            row.setString("block", current ? cell.id : cell.wantId);
            row.setInteger("vertices", HologramBlockRender.vertexCount(cell, current));
            row.setInteger("uniquePositions", HologramBlockRender.uniquePositions(cell, current));
            Block block = cell.block(current);
            String type = block.getClass()
                .getName();
            NBTTagCompound nativeTile = (current ? cell.actualDisplay : cell.targetDisplay)
                .getCompoundTag("nativeTile");
            if (type.equals("com.carpentersblocks.block.BlockCarpentersSlope")) {
                int skins = nativeTile.getTagList("cbAttrList", 10)
                    .tagCount();
                if (skins == 0) bareSlopes++;
                else {
                    require(
                        skins == 2 && usesMaterial(cell, current, Blocks.quartz_block, 0)
                            && usesMaterial(cell, current, Blocks.planks, 3),
                        "native quartz and jungle Carpenter skins");
                    coveredSlopes++;
                    row.setBoolean("quartzAndJungleAtlas", true);
                }
            } else if (type.equals("gcewing.architecture.common.block.BlockShape")) {
                String material = nativeTile.getString("BaseName");
                if (material.equals("minecraft:brick_block")) {
                    require(usesMaterial(cell, current, Blocks.brick_block, 0), "native brick roof atlas");
                    brickRoofs++;
                } else if (material.equals("minecraft:quartz_block")) {
                    require(usesMaterial(cell, current, Blocks.quartz_block, 0), "native quartz roof atlas");
                    quartzRoofs++;
                } else require(false, "unexpected native roof material " + material);
                row.setString("nativeMaterial", material);
            }
            rows.appendTag(row);
        }
        require(
            bareSlopes == 1 && coveredSlopes == 1 && brickRoofs == 1 && quartzRoofs == 1,
            "all four native decor variants verified");
        return rows;
    }

    private static boolean usesMaterial(HologramState.Cell cell, boolean current, Block material, int metadata) {
        for (int side = 0; side < 6; side++)
            if (HologramBlockRender.usesIcon(cell, current, material.getIcon(side, metadata))) return true;
        return false;
    }

    private Object session() throws ReflectiveOperationException {
        Map<?, ?> sessions = (Map<?, ?>) field(HologramService.class, "SESSIONS").get(null);
        return sessions.get(actor.getUniqueID());
    }

    private NBTTagCompound request(String operation) throws ReflectiveOperationException {
        Object session = session();
        require(session != null, "real Service session exists");
        NBTTagCompound request = new NBTTagCompound();
        request.setString("session", (String) read(session, "id"));
        request.setLong("planRevision", (Long) read(session, "planRevision"));
        request.setString("op", operation);
        return request;
    }

    private static Object read(Object target, String name) throws ReflectiveOperationException {
        require(target != null, "missing probe state for " + name);
        return field(target.getClass(), name).get(target);
    }

    private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
        Field result = owner.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    private void write(String name, NBTTagCompound data) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(
            output.resolve(name)
                .toFile())) {
            CompressedStreamTools.writeCompressed(data, stream);
        }
    }

    private void fail(Throwable problem) throws Exception {
        problem.printStackTrace();
        if (HologramClient.state != null) write("failure-service-state.nbt", HologramClient.state.data);
        java.io.StringWriter trace = new java.io.StringWriter();
        problem.printStackTrace(new java.io.PrintWriter(trace));
        Files.write(
            output.resolve("FAIL.txt"),
            (trace + "\nserverStage=" + serverStage + " renderStage=" + renderStage + "\n")
                .getBytes(StandardCharsets.UTF_8));
        System.out.println(PREFIX + "FAIL " + problem);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Production preview: " + message);
    }
}
