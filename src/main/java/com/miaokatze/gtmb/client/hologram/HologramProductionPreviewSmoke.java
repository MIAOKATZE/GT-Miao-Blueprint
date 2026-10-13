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
import com.miaokatze.gtmb.hologram.HologramEnergyNetworkSmoke;
import com.miaokatze.gtmb.hologram.HologramNetworkInteractionSmoke;
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
    private boolean hatchVerified;
    private volatile boolean stopped;
    private int serverStage, settling, renderStage, frames;
    private EntityPlayerMP actor;
    private HologramAeWorldPreviewFixture.Fixture ae;
    private HologramDecorSourceSmoke.Source decor;
    private HologramDecorSourceSmoke.AdjacentSource adjacent;
    private final boolean adjacentProfile = Boolean.getBoolean("gtmb.hologram.productionAdjacentPreview");
    private volatile boolean sourceFrameReady, sourceFrameCaptured;
    private int sourceFrames;
    private long sourceClientTick = -1;
    private NBTTagCompound sources;
    private String savedId;
    private long loginTick;
    private boolean sourceChunksAcquired, sourceProofBeforeNetwork;

    private HologramProductionPreviewSmoke() {}

    public static void install() {
        if (!installed && Boolean.getBoolean("gtmb.hologram.productionPreviewSmoke")) {
            installed = true;
            if (Boolean.getBoolean("gtmb.hologram.energyNetworkSmoke")) HologramEnergyNetworkSmoke.installIfEnabled();
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
            if (rendered && Boolean.getBoolean("gtmb.hologram.networkInteractionSmoke")) {
                HologramNetworkInteractionClientSmoke.clientTick(mc, ++frames);
                if (HologramNetworkInteractionClientSmoke.failure() != null) throw new IllegalStateException(
                    "Quantum binding client witness failed",
                    HologramNetworkInteractionClientSmoke.failure());
            }
            if (System.nanoTime() - started > 900000000000L) throw new IllegalStateException(
                "production preview timed out at server=" + serverStage + "/render=" + renderStage);
            if (serverDone) {
                if (adjacentProfile && !hatchVerified) {
                    write("hatch-material-gl-receipt.nbt", HologramHatchMaterialVerification.verify(output.toFile()));
                    System.out
                        .println(PREFIX + "PASS native hatch casing target/current/world A-B-A and active fronts");
                    hatchVerified = true;
                }
                if (Boolean.getBoolean("gtmb.hologram.energyNetworkSmoke")) {
                    if (!HologramEnergyNetworkSmoke.done()) return;
                    require("PASS".equals(HologramEnergyNetworkSmoke.status()), "native network energy witness failed");
                }
                Files.write(
                    output.resolve("PASS.txt"),
                    ("PASS native world/Area/save/disk reload/network/editor target+current " + expectedCells()
                        + " cells; source unchanged; adjacent="
                        + adjacentProfile
                        + "\n"
                        + "preset="
                        + savedId
                        + "\n").getBytes(StandardCharsets.UTF_8));
                System.out.println(PREFIX + "PASS preset=" + savedId + " receipts=" + output.toAbsolutePath());
                stopped = true;
                mc.shutdown();
                return;
            }
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
                if (adjacentProfile) {
                    field(HologramScreen.class, "yaw").setDouble(mc.currentScreen, Math.PI / 4);
                    field(HologramScreen.class, "pitch").setDouble(mc.currentScreen, Math.PI / 6);
                }
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
            if (sourceChunksAcquired) HologramDecorSourceSmoke.retainNativeSourceChunks();
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
                if (Boolean.getBoolean("gtmb.hologram.networkInteractionSmoke")) {
                    sourceChunksAcquired = true;
                    HologramDecorSourceSmoke.acquireNativeSourceChunks(world);
                }
                decor = HologramDecorSourceSmoke.create(world, 8, 80, 8);
                ae = HologramAeWorldPreviewFixture.create(world, actor, 16, 80, 8);
                if (adjacentProfile) adjacent = HologramDecorSourceSmoke.createAdjacent(world);
                serverStage = 1;
                System.out.println(PREFIX + "native sources placed: decor=4 AE=3 (official CBC/FMP host)");
                return;
            }
            if (serverStage == 1) {
                if (++settling < 10) return;
                if (adjacentProfile) adjacent.freezeSettledBaseline();
                sources = ae.checkpoint();
                NBTTagList decoration = new NBTTagList();
                for (int i = 0; i < 4; i++) decoration.appendTag(decor.sourceNbt(i));
                sources.setTag("decor", decoration);
                if (adjacentProfile) {
                    sources.setTag("adjacent", adjacent.checkpoint());
                    sources.setTag("rawWorld", worldSourceRows((WorldServer) actor.worldObj, adjacent.bounds()));
                    sources.setInteger("expectedCells", expectedCells());
                    if (sourceChunksAcquired)
                        write("source-chunk-keeper.nbt", HologramDecorSourceSmoke.sourceChunkEvidence());
                    // Positive X/Z view matches the editor's yaw=PI/4, pitch=PI/6.
                    // The live world uses perspective; the editor uses its ordinary orthographic camera.
                    actor.playerNetServerHandler.setPlayerLocation(23, 87, 23, 135, 30);
                    sources.setFloat("sourceCameraYaw", 135);
                    sources.setFloat("sourceCameraPitch", 30);
                    sources.setDouble("editorCameraYaw", Math.PI / 4);
                    sources.setDouble("editorCameraPitch", Math.PI / 6);
                    sourceFrameReady = true;
                    serverStage = 11;
                    write("source-native.nbt", sources);
                    return;
                }
                write("source-native.nbt", sources);
                startArea();
            } else if (serverStage == 11) {
                if (!sourceFrameCaptured) return;
                startArea();
            } else if (serverStage == 2) {
                Object session = session();
                if (session == null || !Boolean.TRUE.equals(read(session, "virtual"))
                    || Boolean.TRUE.equals(read(session, "capturePending"))
                    || read(session, "capture") == null) return;
                Object capture = read(session, "capture");
                require(!Boolean.TRUE.equals(read(capture, "incomplete")), "complete native Area capture");
                require(
                    ((java.util.List<?>) read(capture, "cells")).size() == expectedCells(),
                    "Area collected complete original and adjacent world sources");
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
                if (Boolean.getBoolean("gtmb.hologram.networkInteractionSmoke")) {
                    if (!sourceProofBeforeNetwork) {
                        decor.assertUnchanged();
                        ae.assertUnchanged();
                        if (adjacent != null) adjacent.assertUnchanged();
                        HologramDecorSourceSmoke.recordStrictSourceProof(false);
                        sourceProofBeforeNetwork = true;
                    }
                    HologramNetworkInteractionSmoke.serverTick(actor);
                    if (HologramNetworkInteractionSmoke.failure() != null) throw new IllegalStateException(
                        "Quantum binding server witness failed",
                        HologramNetworkInteractionSmoke.failure());
                    if (!HologramNetworkInteractionSmoke.done() || !HologramNetworkInteractionClientSmoke.done())
                        return;
                    require(
                        "PASS".equals(HologramNetworkInteractionSmoke.status())
                            && "PASS".equals(HologramNetworkInteractionClientSmoke.status()),
                        "quantum binding witnesses did not pass");
                }
                decor.assertUnchanged();
                ae.assertUnchanged();
                if (adjacent != null) adjacent.assertUnchanged();
                sources.setBoolean("previewSaveSourceUnchanged", true);
                if (sourceChunksAcquired) {
                    HologramDecorSourceSmoke.recordStrictSourceProof(true);
                    HologramDecorSourceSmoke.releaseNativeSourceChunks(true);
                    sourceChunksAcquired = false;
                    write("source-chunk-keeper.nbt", HologramDecorSourceSmoke.sourceChunkEvidence());
                }
                write("source-native.nbt", sources);
                serverDone = true;
                serverStage = 5;
            }
        } catch (Throwable problem) {
            if (sourceChunksAcquired) {
                HologramDecorSourceSmoke.releaseNativeSourceChunks(false);
                sourceChunksAcquired = false;
            }
            failure = problem;
        }
    }

    @SubscribeEvent
    public void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stopped || failure != null || rendered) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (sourceFrameReady && !sourceFrameCaptured) {
            if (mc.theWorld == null || mc.thePlayer == null
                || mc.currentScreen != null
                || Math.abs(mc.thePlayer.posX - 23) > .1
                || Math.abs(mc.thePlayer.rotationYaw - 135) > .1) return;
            if (sourceClientTick < 0) sourceClientTick = mc.theWorld.getTotalWorldTime();
            if (mc.theWorld.getTotalWorldTime() - sourceClientTick < 5 || ++sourceFrames < 5) return;
            try {
                screenshot("production-native-source-world.png");
                sourceFrameCaptured = true;
            } catch (Throwable problem) {
                failure = problem;
            }
            return;
        }
        if (renderStage == 0) return;
        if (!(mc.currentScreen instanceof HologramScreen) || HologramClient.state == null
            || !editedSession.equals(HologramClient.state.session)) return;
        try {
            if (++frames < 3) return;
            HologramState state = HologramClient.state;
            require(
                state.cells.size() == expectedCells() && state.data.getBoolean("supported")
                    && !state.data.getBoolean("capturePending"),
                "complete original and adjacent network snapshot");
            boolean current = renderStage == 2;
            write("received-service-state.nbt", state.data);
            String name = current ? "production-native-current.png" : "production-native-target.png";
            screenshot(name);
            // Consume this displayed frame's faces, camera and native models before the
            // independent model verifiers begin fresh snapshots and invalidate the screen caches.
            NBTTagCompound adjacentReceipt = null;
            if (adjacentProfile) {
                adjacentReceipt = HologramAdjacentPreviewVerification.verify(state.data, current);
                // This separate control clones only coordinates/view bindings from the real wire state.
                // Every control block/metadata/native tile comes directly from the original world.
                NBTTagCompound control = sourceControl(state.data);
                write(current ? "source-current-control.nbt" : "source-target-control.nbt", control);
                NBTTagCompound reference = HologramSourcePreviewReference.renderReference(control, state.data, current);
                screenshot(
                    current ? "production-native-source-current-reference.png"
                        : "production-native-source-target-reference.png");
                write(
                    current ? "source-current-reference-receipt.nbt" : "source-target-reference-receipt.nbt",
                    reference);
            }
            NBTTagCompound receipt = HologramAeWorldPreviewVerification.verify(state.data, current);
            receipt.setTag("allNative", verifyAll(state, current, adjacentProfile));
            if (adjacentReceipt != null) receipt.setTag("adjacent", adjacentReceipt);
            write(current ? "current-receipt.nbt" : "target-receipt.nbt", receipt);
            System.out.println(
                PREFIX + "native models PASS current=" + current + " cells=" + expectedCells() + " screenshot=" + name);
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

    private NBTTagList verifyAll(HologramState state, boolean current, boolean adjacentProfile) {
        HologramBlockRender.begin(state);
        NBTTagList rows = new NBTTagList();
        int bareSlopes = 0, coveredSlopes = 0, brickRoofs = 0, quartzRoofs = 0, sawbenches = 0;
        int adjacentQuartzSlopes = 0;
        for (HologramState.Cell cell : state.cells) {
            if (!HologramNativeTiles.supported(cell.block(current))) continue;
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
                int sourceX = cell.dx + 8, sourceY = cell.dy + (adjacentProfile ? 79 : 80), sourceZ = cell.dz + 8;
                NBTTagCompound original = carpenterSource(sourceX, sourceY, sourceZ);
                NBTTagList skins = nativeTile.getTagList("cbAttrList", 10);
                NBTTagList originalSkins = original.getTagList("cbAttrList", 10);
                require(
                    carpenterSkinsEqual(originalSkins, skins)
                        && nativeTile.getInteger("cbMetadata") == original.getInteger("cbMetadata"),
                    "native Carpenter shape/skins match original world at " + sourceX + ":" + sourceY + ":" + sourceZ);
                row.setIntArray("sourcePosition", new int[] { sourceX, sourceY, sourceZ });
                row.setInteger("nativeSkinCount", skins.tagCount());
                row.setInteger("nativeSlopeShape", nativeTile.getInteger("cbMetadata"));
                require(sourceY == 80, "native Carpenter source height");
                if (sourceX == 8 && sourceZ == 8) {
                    require(
                        skins.tagCount() == 0 && original.getInteger("cbMetadata") == 8,
                        "original bare Carpenter slope");
                    bareSlopes++;
                } else if (sourceX == 10 && sourceZ == 8) {
                    require(
                        skins.tagCount() == 2 && original.getInteger("cbMetadata") == 8
                            && usesMaterial(cell, current, Blocks.quartz_block, 0)
                            && usesMaterial(cell, current, Blocks.planks, 3),
                        "original native quartz and jungle Carpenter skins");
                    coveredSlopes++;
                    row.setBoolean("quartzAndJungleAtlas", true);
                } else {
                    int expectedShape = sourceX == 9 && sourceZ == 12 ? 8
                        : sourceX == 12 && sourceZ == 12 ? 9
                            : sourceX == 9 && sourceZ == 15 ? 10 : sourceX == 12 && sourceZ == 15 ? 11 : -1;
                    require(adjacentProfile && expectedShape >= 0, "exact adjacent Carpenter source position");
                    require(
                        skins.tagCount() == 1 && original.getInteger("cbMetadata") == expectedShape,
                        "exact single-cover adjacent Carpenter shape");
                    NBTTagCompound skin = originalSkins.getCompoundTagAt(0);
                    require(
                        skin.getByte("cbAttribute") == 6 && skin.getString("cbUniqueId")
                            .equals("minecraft:quartz_block")
                            && skin.getShort("Damage") == 0
                            && skin.getByte("Count") == 1,
                        "exact original quartz base cover");
                    require(
                        usesMaterial(cell, current, Blocks.quartz_block, 0)
                            && !usesMaterial(cell, current, Blocks.planks, 3),
                        "adjacent native quartz-only Carpenter material");
                    coveredSlopes++;
                    adjacentQuartzSlopes++;
                    row.setBoolean("quartzOnlyAtlas", true);
                }
            } else if (type.equals("gcewing.architecture.common.block.BlockSawbench")) {
                sawbenches++;
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
            bareSlopes == 1 && coveredSlopes == (adjacentProfile ? 5 : 1)
                && brickRoofs == 1
                && quartzRoofs == 1
                && sawbenches == (adjacentProfile ? 1 : 0)
                && adjacentQuartzSlopes == (adjacentProfile ? 4 : 0),
            "all four native decor variants verified");
        return rows;
    }

    private NBTTagCompound carpenterSource(int x, int y, int z) {
        NBTTagList raw = sources.getTagList("rawWorld", 10);
        for (int i = 0; i < raw.tagCount(); i++) {
            NBTTagCompound row = raw.getCompoundTagAt(i);
            int[] p = row.getIntArray("sourcePosition");
            if (p.length == 3 && p[0] == x && p[1] == y && p[2] == z) return row.getCompoundTag("nativeTile");
        }
        // The original seven-cell profile predates the rawWorld control inventory.
        NBTTagList original = sources.getTagList("decor", 10);
        for (int i = 0; i < original.tagCount(); i++) {
            NBTTagCompound tile = original.getCompoundTagAt(i);
            if (tile.getInteger("x") == x && tile.getInteger("y") == y && tile.getInteger("z") == z) return tile;
        }
        throw new IllegalStateException(
            "Production preview: original Carpenter source not found at " + x + ":" + y + ":" + z);
    }

    private static boolean carpenterSkinsEqual(NBTTagList original, NBTTagList captured) {
        if (original.tagCount() != captured.tagCount()) return false;
        for (int i = 0; i < original.tagCount(); i++) {
            NBTTagCompound expected = original.getCompoundTagAt(i);
            NBTTagCompound actual = (NBTTagCompound) captured.getCompoundTagAt(i)
                .copy();
            // GTIT adds a registry binding to captured item records in the actual full pack.
            // It must identify this exact original material; all native fields remain exact.
            if (actual.hasKey("gtitBlueprintItemRegistry")) {
                if (!actual.hasKey("gtitBlueprintItemRegistry", 8) || !actual.getString("gtitBlueprintItemRegistry")
                    .equals(expected.getString("cbUniqueId"))) return false;
                actual.removeTag("gtitBlueprintItemRegistry");
            }
            if (!expected.equals(actual)) return false;
        }
        return true;
    }

    private int expectedCells() {
        return 7 + (adjacent == null ? 0 : adjacent.count());
    }

    private static NBTTagList worldSourceRows(WorldServer world, int[] bounds) {
        NBTTagList rows = new NBTTagList();
        for (int y = bounds[1]; y <= bounds[4]; y++)
            for (int z = bounds[2]; z <= bounds[5]; z++) for (int x = bounds[0]; x <= bounds[3]; x++) {
                if (world.isAirBlock(x, y, z)) continue;
                NBTTagCompound row = new NBTTagCompound();
                row.setIntArray("sourcePosition", new int[] { x, y, z });
                row.setString("block", Block.blockRegistry.getNameForObject(world.getBlock(x, y, z)));
                row.setInteger("metadata", world.getBlockMetadata(x, y, z));
                net.minecraft.tileentity.TileEntity tile = world.getTileEntity(x, y, z);
                if (tile != null) {
                    NBTTagCompound raw = new NBTTagCompound();
                    tile.writeToNBT(raw);
                    row.setTag("nativeTile", raw);
                }
                rows.appendTag(row);
            }
        return rows;
    }

    private NBTTagCompound sourceControl(NBTTagCompound snapshot) {
        NBTTagCompound control = (NBTTagCompound) snapshot.copy();
        NBTTagList sourceRows = sources.getTagList("rawWorld", 10);
        java.util.Map<String, NBTTagCompound> original = new java.util.HashMap<>();
        for (int i = 0; i < sourceRows.tagCount(); i++) {
            NBTTagCompound source = sourceRows.getCompoundTagAt(i);
            int[] p = source.getIntArray("sourcePosition");
            original.put(p[0] + ":" + p[1] + ":" + p[2], source);
        }
        NBTTagList cells = control.getTagList("cells", 10);
        int[] bounds = adjacent.bounds();
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            String key = (cell.getInteger("dx") + bounds[0]) + ":"
                + (cell.getInteger("dy") + bounds[1])
                + ":"
                + (cell.getInteger("dz") + bounds[2]);
            NBTTagCompound source = original.get(key);
            require(source != null, "raw source world row missing for control " + key);
            require(
                source.getString("block")
                    .equals(cell.getString("wantId")),
                "source/control block identity " + key);
            cell.setString("id", source.getString("block"));
            cell.setInteger("meta", source.getInteger("metadata"));
            cell.setInteger("wantMeta", source.getInteger("metadata"));
            for (String side : new String[] { "actualDisplay", "targetDisplay" }) {
                NBTTagCompound display = cell.getCompoundTag(side);
                display.removeTag("nativeTile");
                if (source.hasKey("nativeTile", 10)) display.setTag(
                    "nativeTile",
                    source.getCompoundTag("nativeTile")
                        .copy());
                cell.setTag(side, display);
            }
        }
        return control;
    }

    private void startArea() throws ReflectiveOperationException {
        int[] bounds = adjacent == null ? new int[] { 8, 80, 8, 19, 80, 8 } : adjacent.bounds();
        HologramService.beginAreaSelection(actor);
        HologramService.areaPoint(actor, 1, bounds[0], bounds[1], bounds[2], false);
        HologramService.areaPoint(actor, 2, bounds[3], bounds[4], bounds[5], false);
        HologramService.finishAreaSelection(actor, request(""));
        serverStage = 2;
        System.out.println(PREFIX + "real Area import started cells=" + expectedCells());
    }

    private void screenshot(String name) throws Exception {
        Minecraft mc = Minecraft.getMinecraft();
        ScreenShotHelper.saveScreenshot(mc.mcDataDir, name, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        Path image = new File(new File(mc.mcDataDir, "screenshots"), name).toPath();
        require(Files.isRegularFile(image) && Files.size(image) > 0, "actual framebuffer screenshot saved");
        Files.copy(image, output.resolve(name), StandardCopyOption.REPLACE_EXISTING);
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
        if (sourceChunksAcquired) {
            HologramDecorSourceSmoke.releaseNativeSourceChunks(false);
            sourceChunksAcquired = false;
        }
        if (Boolean.getBoolean("gtmb.hologram.networkInteractionSmoke"))
            write("source-chunk-keeper.nbt", HologramDecorSourceSmoke.sourceChunkEvidence());
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
