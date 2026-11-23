// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.client.hologram;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;

import org.lwjgl.input.Keyboard;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.HologramRuntimeMetrics;
import com.miaokatze.miaokatzecore.api.testlab.ArtifactIdentity;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Independent, leased in-world measurement of an actual native Library row opening Edit. */
public final class HologramGiantNativeEditProbe {

    private static final String FLAG = "gtmb.testlab.giantNativeEdit";
    private static final String PACKED_FLAG = "gtmb.localBlueprint.packedAuthoring";
    private static final String WORLD_PATH = "miaotest.host.worldPath";
    private static final long SECOND = 1_000_000_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private enum Stage {
        TITLE, WORLD, OPEN, ROW, RENDER, WRITE, RESET_MENU, RESET_COMMAND, RESET_WAIT, EXIT, EXIT_WAIT,
        RESTORE, SHUTDOWN, DONE
    }

    /** Detached readback; never holds or changes live ItemStacks. */
    private static final class InventorySample {

        private final long serial;
        private final int selected;
        private final NBTTagList items;

        InventorySample(EntityPlayer player, long serial) {
            this.serial = serial;
            selected = player.inventory.currentItem;
            items = (NBTTagList) player.inventory.writeToNBT(new NBTTagList()).copy();
        }

        boolean matches(InventorySample other) {
            return other != null && selected == other.selected && items.equals(other.items);
        }
    }

    // Only test operator-selected copies are admitted; no stock fixture or digest is baked into the probe.
    private final String fixtureId = fixture("id", "local_[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}");
    private final String fixtureJsonSha = fixture("jsonSha256", "[a-f0-9]{64}");
    private final String fixturePngSha = fixture("pngSha256", "[a-f0-9]{64}");
    private final int fixtureCells = Integer.parseInt(fixture("cells", "[1-9][0-9]{0,5}"));
    private final String run = UUID.fromString(System.getProperty("miaotest.host.runId")).toString();
    private final Path output = Paths.get(System.getProperty("gtmb.testlab.clientUiReceiptDir")).toAbsolutePath();
    private final boolean expectedPackedAuthoring = Boolean.getBoolean(PACKED_FLAG);
    private final boolean firstWrite = Boolean.getBoolean(HologramGiantFirstWrite.FLAG);
    private final boolean crossHistory = Boolean.getBoolean(HologramGiantCellHistory.FLAG);
    private final HologramGiantHistoryTiming historyTiming = HologramGiantHistoryTiming.optional(
        Boolean.getBoolean(HologramGiantHistoryTiming.FLAG), crossHistory);
    private final List<Map<String, Object>> loads = new ArrayList<>();
    private final Map<String, Object> receipt = new LinkedHashMap<>();
    private final long start = System.nanoTime();
    private volatile Stage stage = Stage.TITLE;
    private volatile Path save;
    private volatile MinecraftServer server;
    private volatile Thread serverThread;
    private volatile UUID actor;
    private volatile String serverFailure;
    private volatile InventorySample serverInventory, admittedInventory;
    private InventorySample pendingStockInventory;
    private long inventorySerial, checkedAdmissionSerial, lastCloseInventorySerial;
    private boolean inventoryFinalChecked;

    private Path game, source;
    private HologramGiantWriteArtifact writeArtifact;
    private HologramGiantFirstWrite write;
    private boolean actualSavedReopen;
    private Object clientWorld, clientPlayer, connection;
    private GuiScreen title, lastTitleDrawn;
    private HologramScreen screen;
    private Map<String, Object> load;
    private HologramPreviewThresholdWitness witness;
    private long stageStart = start;
    private long guiPosts, consumedPosts, openedPosts, inputAt;
    private String observedOpeningSession;
    private NBTTagCompound countersBeforeRow;
    private boolean tutorialDismissed, libraryOpenInput, finalPairVerified, ownMaterialMetrics;
    private String failure;

    private static String fixture(String name, String pattern) {
        String property = FLAG + ".fixture." + name;
        String value = System.getProperty(property, "");
        require(value.matches(pattern), "Missing or invalid test fixture property: " + property);
        return value;
    }

    static boolean install() {
        if (!Boolean.getBoolean(FLAG)) return false;
        require(Boolean.getBoolean("miaotest.host.environment"), "Exact TestLab host lease required");
        String packed = System.getProperty(PACKED_FLAG, "");
        require("false".equals(packed) || "true".equals(packed), "Explicit compact false/true arm required");
        require(!Boolean.getBoolean("gtmb.testlab.tutorialIssue100")
            && !Boolean.getBoolean("gtmb.testlab.renderParity")
            && !Boolean.getBoolean("gtmb.hologram.productionPreviewSmoke"),
            "Independent native Edit does not run another client probe");
        require(!Boolean.getBoolean(HologramGiantFirstWrite.FLAG) || Boolean.getBoolean("gtmb.hologram.previewCaptureWitness"),
            "First-write lifecycle requires the existing native Save capture witness");
        require(!Boolean.getBoolean(HologramGiantCellHistory.FLAG)
            || Boolean.getBoolean(HologramGiantFirstWrite.FLAG) && "true".equals(packed),
            "Cross-history requires explicit packed ON and the existing first-write host guards");
        HologramGiantNativeEditProbe probe = new HologramGiantNativeEditProbe();
        FMLCommonHandler.instance().bus().register(probe);
        MinecraftForge.EVENT_BUS.register(probe);
        return true;
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || save == null || stage == Stage.DONE || stage == Stage.SHUTDOWN) return;
        try {
            MinecraftServer actual = FMLCommonHandler.instance().getMinecraftServerInstance();
            if (actual == null || actual.worldServerForDimension(0) == null) return;
            World world = actual.worldServerForDimension(0);
            if (server == null) {
                Path path = world.getSaveHandler().getWorldDirectory().toPath().toRealPath();
                require(path.equals(save) && actual.isSinglePlayer()
                    && Boolean.TRUE.equals(Class.forName("com.miaokatze.miaokatzecore.testlab.LabWorldPolicy")
                        .getMethod("owns", World.class).invoke(null, world)), "Server is not our marker-owned singleplayer");
                serverThread = Thread.currentThread();
                server = actual;
            }
            if (actor == null || server != actual || stage == Stage.RENDER || stage == Stage.WRITE) return;
            EntityPlayerMP player = null;
            for (Object member : server.getConfigurationManager().playerEntityList) {
                if (member instanceof EntityPlayerMP current && actor.equals(current.getUniqueID())) player = current;
            }
            if (player == null) return;
            require(!(player instanceof FakePlayer) && !player.capabilities.isCreativeMode && player.dimension == 0,
                "Expected actual survival actor in the owned world");
            // Inventory synchronization is untimed. Do not allocate snapshots while waiting for a timed frame.
            InventorySample sample = new InventorySample(player, ++inventorySerial);
            InventorySample baseline = admittedInventory;
            require(baseline == null || baseline.matches(sample), "Stock server inventory or selected slot changed");
            serverInventory = sample;
        } catch (Exception | LinkageError problem) {
            serverFailure = problem.toString();
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage == Stage.DONE) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (stage == Stage.RESTORE || stage == Stage.SHUTDOWN) {
                cleanup(mc);
                return;
            }
            require(serverFailure == null, serverFailure);
            long limit = stage == Stage.TITLE || stage == Stage.WORLD || stage == Stage.WRITE ? 240
                : stage == Stage.RENDER ? 180 : 20;
            require(System.nanoTime() - stageStart < limit * SECOND, "Timed out waiting for " + stage);
            if (stage == Stage.TITLE) startWorld(mc);
            else if (stage == Stage.WORLD) adoptWorld(mc);
            else {
                live(mc);
                if (stage == Stage.OPEN) openEditor(mc);
                else if (stage == Stage.EXIT_WAIT) observeClose(mc);
                else {
                    require(screen != null && mc.currentScreen == screen, "Owned editor lost focus or changed");
                    if (stage == Stage.ROW) openRow();
                    else if (stage == Stage.WRITE) {
                        write.tick();
                        if (write.saved()) transition(Stage.RESET_MENU);
                    } else if (stage == Stage.RESET_MENU) openReset();
                    else if (stage == Stage.RESET_COMMAND) clearCleanWorkspace();
                    else if (stage == Stage.RESET_WAIT) observeReset();
                    else if (stage == Stage.EXIT) clickExit();
                }
            }
        } catch (Exception | LinkageError problem) {
            fail(problem);
        }
    }

    private void startWorld(Minecraft mc) throws Exception {
        if (!HologramTestLabUiProfile.readyTitle(mc.currentScreen) || lastTitleDrawn != mc.currentScreen) return;
        require(mc.theWorld == null && mc.thePlayer == null && mc.getIntegratedServer() == null
            && !HologramLocalPlacement.INSTANCE.active() && System.getProperty(WORLD_PATH) == null,
            "Refusing a preexisting client session or TestLab world");
        title = mc.currentScreen;
        game = mc.mcDataDir.toPath().toRealPath();
        require(output.equals(output.toRealPath()) && !output.startsWith(game), "Use a separate owned receipt directory");
        require(run.equals(read(game.resolve(".testlab-host/lease.json")).get("runId").getAsString()),
            "Exact host lease missing");
        Path library = game.resolve("MiaoBlueprints");
        require(library.equals(library.toRealPath()), "Managed source directory is not canonical");
        source = library.resolve(fixtureId + ".json");
        verifyPair();
        if (firstWrite) writeArtifact = new HologramGiantWriteArtifact(
            game, source, fixtureJsonSha, fixturePngSha, fixtureCells, fixtureId);
        Path parent = game.resolve("saves").toRealPath();
        save = parent.resolve("MiaoHost-" + run);
        Files.createDirectory(save);
        JsonObject marker = new JsonObject();
        marker.addProperty("runId", run);
        marker.addProperty("parent", parent.toString());
        Files.write(save.resolve(".miaotest-host-owner.json"),
            GSON.toJson(marker).getBytes(StandardCharsets.UTF_8), java.nio.file.StandardOpenOption.CREATE_NEW);
        System.setProperty(WORLD_PATH, save.toString());
        receipt.put("ownedSave", save.toString());
        transition(Stage.WORLD);
        mc.launchIntegratedServer(save.getFileName().toString(), "GTMB native Edit lease",
            new WorldSettings(61312138L, WorldSettings.GameType.SURVIVAL, false, false, WorldType.FLAT));
    }

    private void adoptWorld(Minecraft mc) throws Exception {
        if (server == null || mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null
            || mc.thePlayer.ticksExisted < 60) return;
        require(mc.getIntegratedServer() == server && !mc.thePlayer.capabilities.isCreativeMode
            && mc.thePlayer.dimension == 0, "Unexpected real-world actor context");
        Path environment = output.resolve("environment.json");
        if (!Files.isRegularFile(environment)) return;
        JsonObject env = read(environment);
        require(run.equals(env.get("runId").getAsString()) && env.get("registered").getAsBoolean(),
            "Core runtime did not register the exact lease");
        if (actor == null) {
            clientWorld = mc.theWorld;
            clientPlayer = mc.thePlayer;
            connection = mc.getNetHandler();
            actor = mc.thePlayer.getUniqueID();
        }
        live(mc);
        InventorySample serverSample = serverInventory;
        if (serverSample == null || serverSample.serial == checkedAdmissionSerial) return;
        checkedAdmissionSerial = serverSample.serial;
        InventorySample clientSample = new InventorySample(mc.thePlayer, 0);
        if (!clientSample.matches(serverSample)) {
            pendingStockInventory = null;
            return;
        }
        if (!clientSample.matches(pendingStockInventory)) {
            pendingStockInventory = clientSample;
            return;
        }
        // Require the same serialized client/server content and selection over two distinct server ticks.
        admittedInventory = clientSample;
        receipt.put("exactWorldPolicyObserved", true);
        receipt.put("actor", "same real local survival actor, world, server, connection and dimension");
        receipt.put("stockInventorySynchronized", true);
        receipt.put("inventoryFixtureWrites", 0);
        transition(Stage.OPEN);
    }

    private void verifyStockInventory(Minecraft mc) {
        InventorySample baseline = admittedInventory;
        require(baseline != null && baseline.matches(serverInventory)
            && baseline.matches(new InventorySample(mc.thePlayer, 0)), "Stock actor inventory or selection changed");
    }

    private void live(Minecraft mc) {
        require(clientWorld == mc.theWorld && clientPlayer == mc.thePlayer && connection == mc.getNetHandler()
            && mc.getIntegratedServer() == server && mc.thePlayer != null && actor.equals(mc.thePlayer.getUniqueID())
            && mc.thePlayer.dimension == 0 && !mc.thePlayer.capabilities.isCreativeMode
            && !HologramLocalPlacement.INSTANCE.active(), "Owned player/world/connection changed");
        InventorySample baseline = admittedInventory;
        require(baseline == null || mc.thePlayer.inventory.currentItem == baseline.selected,
            "Stock actor's selected slot changed");
    }

    private void openEditor(Minecraft mc) throws ReflectiveOperationException {
        if (mc.currentScreen != null || HologramBackgroundTasks.busy()) return;
        verifyStockInventory(mc);
        Field draft = HologramClient.class.getDeclaredField("localWorkspaceDraft");
        draft.setAccessible(true);
        require(draft.get(null) == null,
            "An automatic previous-document resume would invalidate this row benchmark");
        load = new LinkedHashMap<>();
        load.put("arm", loads.isEmpty() ? "cold" : firstWrite ? "saved-reopen" : "warm");
        loads.add(load);
        witness = new HologramPreviewThresholdWitness(fixtureCells);
        openedPosts = guiPosts;
        tutorialDismissed = libraryOpenInput = false;
        inputAt = 0;
        observedOpeningSession = null;
        countersBeforeRow = null;
        HologramClient.openSettings();
        require(mc.currentScreen instanceof HologramScreen, "Production openSettings did not open a native projector editor");
        screen = (HologramScreen) mc.currentScreen;
        load.put("editorAdmission", "guarded in-world production openSettings with unmodified held stack; setup not timed");
        transition(Stage.ROW);
    }

    private void openRow() throws Exception {
        if (guiPosts <= openedPosts || !screen.ui.shell.built() || !screen.getScreen().getMainPanel().isValid()) return;
        if (screen.ui.tutorial.active()) {
            if (!tutorialDismissed) {
                HologramMuiProbeInput.key(screen, (char) 0, Keyboard.KEY_ESCAPE);
                tutorialDismissed = true;
                load.put("tutorialEscape", "native Escape without progress");
            }
            return;
        }
        if (!screen.ui.library.libraryDrawer()) {
            if (!libraryOpenInput && screen.ui.shell.targetBounds("header.library") != null) {
                nativeTarget("header.library", "openLibrary");
                libraryOpenInput = true;
            }
            return;
        }
        require(!screen.ui.shell.hasModalPanel() && !screen.ui.library.publicLibrary()
            && !screen.ui.session.localDocument() && screen.ui.session.state().cells.isEmpty(),
            "Expected clean blank local workspace and no modal");
        if (screen.ui.library.localBusy() || HologramLibraryClient.INSTANCE.busy() || HologramBackgroundTasks.busy())
            return;
        NBTTagList rows = screen.ui.library.nativeRows();
        if (rows.tagCount() != 1 || screen.ui.shell.targetBounds("library.row.0") == null) return;
        Method binding = HologramUiLibrary.class.getDeclaredMethod("visibleLibraryRow", int.class);
        binding.setAccessible(true);
        NBTTagCompound row = (NBTTagCompound) binding.invoke(screen.ui.library, 14_000);
        require(fixtureId.equals(row.getString("id")) && row.getInteger("cells") == fixtureCells
            && (firstWrite ? writeArtifact.expectedPath() : source)
                .equals(HologramLibraryClient.INSTANCE.privateStore().savedPath(fixtureId)),
            "Native row did not resolve to the exact managed file");
        verifyStockInventory(Minecraft.getMinecraft());
        load.put("row", fixtureId);
        load.put("sourceRows", fixtureCells);
        load.put("heapGcBeforeNativeRow", heapGc());
        countersBeforeRow = HologramRuntimeMetrics.snapshot().getCompoundTag("queues");
        Map<String, Object> hit = new LinkedHashMap<>();
        load.put("nativeEditRowClick", hit);
        HologramMuiProbeInput.nativeTarget(screen, "library.row.0", hit, () -> {
            if (!HologramRuntimeMetrics.isEnabled()) {
                HologramRuntimeMetrics.setEnabled(true);
                ownMaterialMetrics = true;
            }
            load.put("materialRecordEnabledAtNativePress", HologramRuntimeMetrics.isEnabled());
            inputAt = System.nanoTime();
            witness.start(inputAt);
        });
        transition(Stage.RENDER);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void before(GuiScreenEvent.DrawScreenEvent.Pre event) {
        if (historyTiming != null && stage == Stage.WRITE && event.gui == screen && write != null)
            write.beforeHistoryDraw();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void after(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (stage == Stage.TITLE || stage == Stage.SHUTDOWN) lastTitleDrawn = event.gui;
        if (event.gui == screen) {
            guiPosts++;
            if (historyTiming != null && stage == Stage.WRITE && write != null) write.afterHistoryDraw();
        }
    }

    @SubscribeEvent
    public void render(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || guiPosts == consumedPosts
            || stage != Stage.RENDER && stage != Stage.WRITE) return;
        consumedPosts = guiPosts;
        long end = System.nanoTime();
        try {
            Minecraft mc = Minecraft.getMinecraft();
            live(mc);
            if (stage == Stage.WRITE) {
                require(mc.currentScreen == screen && !screen.ui.library.browsePreviewActive() && !screen.ui.tutorial.active()
                    && fixtureId.equals(HologramLibraryClient.INSTANCE.selected(false)), "OWNER_CHANGED: native write pane lost");
                write.renderEnd(end);
                return;
            }
            require(mc.currentScreen == screen && !screen.ui.shell.hasModalPanel()
                && !screen.ui.library.browsePreviewActive() && !screen.ui.tutorial.active(), "Edit lost native pane ownership");
            if (!fixtureId.equals(HologramLibraryClient.INSTANCE.selected(false))) {
                require(!Boolean.TRUE.equals(load.get("normalEditSelectionObserved")),
                    "Native selection changed after Edit admission");
                return;
            }
            if (load.putIfAbsent("normalEditSelectionObserved", true) == null)
                load.put("selectionObservedAtEndNanos", end - inputAt);
            HologramState opening = screen.ui.library.openingPreview();
            HologramState state = opening;
            if (state == null && screen.ui.session.localDocument()) state = screen.ui.session.state();
            if (state == null || !fixtureId.equals(state.data.getString("libraryLocalId"))) return;
            if (opening != null && observedOpeningSession == null) {
                observedOpeningSession = opening.session;
                load.put("openingSnapshotObservedAtEndNanos", end - inputAt);
                load.put("openingSnapshotRows", opening.cells.size());
                load.put("openingSnapshotPartial", opening.data.getBoolean("displayPartial"));
            }
            witness.observe(state, HologramRenderer.editorMaterialPresentation());
            if (witness.sawMaterial()) load.putIfAbsent("firstEndNanos", end - inputAt);
            if (witness.sawComplete()) load.putIfAbsent("completeEndNanos", end - inputAt);
            var document = screen.ui.session.document();
            // A clean editable native frame is a separate endpoint; do not hide it when material diagnostics fail.
            if (document == null || screen.ui.session.state() != state || screen.ui.library.localBusy()
                || HologramBackgroundTasks.busy()) return;
            require(fixtureId.equals(document.fileId()) && !document.dirty() && state.data.getBoolean("target")
                && state.cells.size() == fixtureCells && !state.data.getBoolean("displayPartial"),
                "Complete body was not the exact clean active target authoring document");
            boolean packed = document.packedAuthoring();
            require(firstWrite && loads.size() == 2 || packed == expectedPackedAuthoring,
                "Clean document did not use the explicitly requested storage arm");
            if (load.putIfAbsent("packedAuthoringObservedOnCleanDocument", packed) == null)
                load.put("cleanDocumentObservedAtEndNanos", end - inputAt);
            if (!screen.ui.session.editable() || !HologramRenderer.previewMeshReady(state)
                || !(screen.ui.shell.scene() instanceof HologramEditorSceneWidget edit) || !edit.editFrameReady()) return;
            load.putIfAbsent("cleanEditableObservedAtEndNanos", end - inputAt);
            if (!witness.sawComplete()) return;
            load.put("heapGcAfterCompleteBeforeProofCopies", heapGc());
            recordCompletedRenderTrace(state, end);
            require((firstWrite ? writeArtifact.expectedJson() : fixtureJsonSha)
                .equals(HologramBlueprintArtifact.hash(document.artifact())),
                firstWrite ? "REOPEN_FROM_CACHE_OR_PAYLOAD: exact managed bytes" : "Authoring loaded other bytes or changed the original document");
            capture(mc, state);
            load.put("rendererMaterial", thresholds());
            load.put("editableCleanCells", state.cells.size());
            load.put("renderEndAndGuiPostOwned", true);
            if (firstWrite && loads.size() == 1) {
                write = new HologramGiantFirstWrite(screen, writeArtifact, output, fixtureId, fixtureCells,
                    expectedPackedAuthoring, crossHistory, historyTiming);
                receipt.put("firstWrite", write.evidence());
                transition(Stage.WRITE);
            } else {
                if (firstWrite) {
                    require(write.isReopen(state), "REOPEN_FROM_CACHE_OR_PAYLOAD: same session or missing actual saved prompt");
                    actualSavedReopen = true;
                    load.put("actualSavedDiskReopen", true);
                }
                transition(Stage.RESET_MENU);
            }
        } catch (Exception | LinkageError problem) {
            fail(problem);
        }
    }

    private void capture(Minecraft mc, HologramState state) throws Exception {
        MiaoVisonLiteRenderer pane = HologramRenderer.viewportFor(state);
        NBTTagCompound real = pane.lessonInspection(state);
        require(real.getBoolean("snapshotPresented") && real.getInteger("renderedCellCount") > 0
            && real.getInteger("nativeModelCellCount") > 0, "Missing natural material/native model witness");
        load.put("renderedCells", real.getInteger("renderedCellCount"));
        load.put("nativeModelCells", real.getInteger("nativeModelCellCount"));
        String file = "giant-edit-" + load.get("arm") + "-full.png";
        Files.createDirectories(output.resolve("screenshots"));
        ScreenShotHelper.saveScreenshot(output.toFile(), file, mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
        Path png = output.resolve("screenshots").resolve(file);
        require(Files.size(png) > 1024, "Real framebuffer capture missing");
        load.put("settledFrameSha256", HologramBlueprintArtifact.hash(Files.readAllBytes(png)));
        load.put("settledFrame", "screenshots/" + file);
    }

    private String toolControl(int surface, String key) {
        HologramEditorCommands commands = new HologramEditorCommands(screen.ui);
        int index = commands.index(key);
        require(index >= 0, "Unknown native command " + key);
        return "control." + (HologramEditorControlIds.TOOLS + 100 * surface + index);
    }

    private void openReset() throws Exception {
        require(!screen.ui.shell.hasModalPanel() && screen.ui.session.document() != null
            && !screen.ui.session.document().dirty() && !HologramBackgroundTasks.busy(), "Reset requires clean, idle Edit");
        if (!firstWrite) require(screen.ui.session.document().packedAuthoring() == expectedPackedAuthoring,
            "Read-only exposure changed the actual authoring storage arm before clear");
        load.put("packedAuthoringBeforeCleanClear", screen.ui.session.document().packedAuthoring());
        nativeTarget(toolControl(0, "viewWorkspace"), "openViewWorkspace");
        transition(Stage.RESET_COMMAND);
    }

    private void clearCleanWorkspace() throws Exception {
        String key = toolControl(4, "clearWorkspace");
        if (screen.ui.shell.targetBounds(key) == null) return;
        nativeTarget(key, "clearCleanWorkspace");
        transition(Stage.RESET_WAIT);
    }

    private void observeReset() throws Exception {
        if (screen.ui.shell.hasModalPanel()) return;
        require(!screen.ui.session.state().data.getBoolean("discardConfirmationRequired"),
            "The probe cannot confirm a discard");
        if (screen.ui.session.localDocument() || !screen.ui.session.state().cells.isEmpty()) return;
        verifyStockInventory(Minecraft.getMinecraft());
        if (firstWrite) {
            writeArtifact.verifyFinal();
            load.put("savedPairUnchangedAfterClear", true);
        } else {
            verifyPair();
            load.put("pristineAfterClear", true);
        }
        transition(Stage.EXIT);
    }

    private void clickExit() throws Exception {
        require(!screen.ui.library.localBusy() && !HologramBackgroundTasks.busy() && !screen.ui.shell.hasModalPanel(),
            "Closing requires no local work or confirmation");
        nativeTarget("header.back", "closeEditor");
        transition(Stage.EXIT_WAIT);
    }

    private void observeClose(Minecraft mc) {
        require(mc.currentScreen == screen || mc.currentScreen == null, "Native exit reached another GUI");
        if (mc.currentScreen != null) return;
        verifyStockInventory(mc);
        screen = null;
        load.put("closedToSameWorld", true);
        if (loads.size() == 1) transition(Stage.OPEN);
        else {
            finalPairVerified = true;
            lastCloseInventorySerial = serverInventory.serial;
            transition(Stage.RESTORE);
        }
    }

    static Map<String, Object> heapGc() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("heapInitBytes", heap.getInit());
        sample.put("heapUsedBytes", heap.getUsed());
        sample.put("heapCommittedBytes", heap.getCommitted());
        sample.put("heapMaxBytes", heap.getMax());
        List<Map<String, Object>> collectors = new ArrayList<>();
        for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
            Map<String, Object> gc = new LinkedHashMap<>();
            gc.put("name", collector.getName());
            gc.put("collections", collector.getCollectionCount());
            gc.put("collectionTimeMillis", collector.getCollectionTime());
            collectors.add(gc);
        }
        sample.put("gcCumulative", collectors);
        return sample;
    }

    private void recordCompletedRenderTrace(HologramState active, long end) {
        NBTTagCompound metrics = HologramRuntimeMetrics.snapshot();
        NBTTagList stages = metrics.getTagList("loadStages", 10);
        List<Map<String, Object>> trace = new ArrayList<>();
        for (int i = 0; i < stages.tagCount(); i++) {
            NBTTagCompound entry = stages.getCompoundTagAt(i);
            long elapsed = entry.getLong("atNanos") - inputAt;
            if (elapsed < 0 || elapsed > end - inputAt) continue;
            String session = entry.getString("session");
            if (!active.session.equals(session) && !session.equals(observedOpeningSession)) continue;
            if (entry.getLong("revision") != active.data.getLong("planRevision")) continue;
            String name = entry.getString("stage");
            if (!"CLIENT_FIRST_MATERIAL".equals(name) && !"CLIENT_OUTLINE_READY".equals(name)
                && !"CLIENT_RENDER_READY".equals(name)) continue;
            Map<String, Object> mark = new LinkedHashMap<>();
            mark.put("stage", name);
            mark.put("fromNativePressNanos", elapsed);
            mark.put("submittedOrOutlineCount", entry.getLong("count"));
            trace.add(mark);
        }
        load.put("matchingRendererTrace", trace);
        load.put("rendererTraceDroppedEntriesAcrossJvm", metrics.getLong("loadTraceDropped"));
        NBTTagCompound after = metrics.getCompoundTag("queues");
        Map<String, Object> deltas = new LinkedHashMap<>();
        for (String key : new String[] { "clientDescriptorTemplates", "clientDescriptorCells" })
            deltas.put(key, after.getLong(key) - countersBeforeRow.getLong(key));
        load.put("jvmAggregateDescriptorCounterDeltas", deltas);
    }

    private void nativeTarget(String k, String field) throws Exception {
        Map<String, Object> hit = new LinkedHashMap<>();
        load.put(field, hit);
        HologramMuiProbeInput.nativeTarget(screen, k, hit);
    }

    private void liveGuardedUnload(Minecraft mc) throws Exception {
        if (mc.theWorld != null || mc.getIntegratedServer() != null) {
            require(server != null && mc.getIntegratedServer() == server && save != null,
                "Refusing to unload an unowned server");
            if (mc.theWorld != null) mc.theWorld.sendQuittingDisconnectingPacket();
            mc.loadWorld(null);
            server.initiateShutdown();
        }
        if (title != null && mc.currentScreen != title) mc.displayGuiScreen(title);
    }

    private void cleanup(Minecraft mc) throws Exception {
        if (stage == Stage.RESTORE) {
            if (failure == null) {
                InventorySample serverSample = serverInventory;
                if (serverFailure != null) failure = serverFailure;
                else if (serverSample == null || serverSample.serial <= lastCloseInventorySerial) {
                    if (System.nanoTime() - stageStart < 15 * SECOND) return;
                    failure = "Fresh post-close stock inventory verification timed out";
                } else try {
                    live(mc);
                    verifyStockInventory(mc);
                    inventoryFinalChecked = true;
                } catch (IllegalStateException changed) {
                    failure = changed.getMessage();
                }
            }
            // No inventory restoration is needed; the probe never mutates it, including failure cleanup.
            liveGuardedUnload(mc);
            transition(Stage.SHUTDOWN);
            return;
        }
        JsonObject env = Files.isRegularFile(output.resolve("environment.json"))
            ? read(output.resolve("environment.json")) : null;
        boolean stopped = server == null && mc.getIntegratedServer() == null
            || serverThread != null && !serverThread.isAlive() && env != null
                && run.equals(env.get("runId").getAsString()) && env.has("serverStopped")
                && env.get("serverStopped").getAsBoolean() && !env.get("registered").getAsBoolean();
        boolean clean = stopped && mc.theWorld == null && mc.thePlayer == null && mc.currentScreen == title
            && lastTitleDrawn == title;
        if (!clean && System.nanoTime() - stageStart < 20 * SECOND) return;
        finish(clean);
    }

    private void finish(boolean clean) throws Exception {
        stage = Stage.DONE;
        if (ownMaterialMetrics) HologramRuntimeMetrics.setEnabled(false);
        receipt.put("materialMetricsScopeRestored", !ownMaterialMetrics || !HologramRuntimeMetrics.isEnabled());
        FMLCommonHandler.instance().bus().unregister(this);
        MinecraftForge.EVENT_BUS.unregister(this);
        if (save != null && save.toString().equals(System.getProperty(WORLD_PATH))) System.clearProperty(WORLD_PATH);
        boolean pairPristine = false;
        if (source != null) try {
            if (firstWrite) writeArtifact.verifyFinal();
            else verifyPair();
            pairPristine = true;
        } catch (Exception proof) {
            receipt.put("fixtureReadbackError", proof.toString());
        }
        boolean functional = failure == null && clean && inventoryFinalChecked && finalPairVerified && pairPristine
            && loads.size() == 2 && (!firstWrite || actualSavedReopen && write != null && write.saved());
        boolean deadlines = functional;
        for (Map<String, Object> part : loads) {
            boolean first = within(part, "firstEndNanos", HologramPreviewThresholdWitness.BLUEPRINT_FIRST_NANOS);
            boolean all = within(part, "completeEndNanos", HologramPreviewThresholdWitness.BLUEPRINT_COMPLETE_NANOS);
            part.put("endFirstUnder20ms", first);
            part.put("endCompleteUnder200ms", all);
            deadlines &= first && all;
        }
        receipt.put("runId", run);
        receipt.put("profile", "independent-in-world-native-library-Edit");
        receipt.put("explicitPackedAuthoring", expectedPackedAuthoring);
        receipt.put("observationScope",
            "Task/preview/document markers are first observed on owned END; not file, parse, or worker duration.");
        receipt.put("memoryScope", firstWrite
            ? "Untimed JVM heap/GC before native Apply and after the first updated full body END; sampled, not peak or retained."
            : "Untimed JVM heap and cumulative GC samples before row press and after full/ready before proof copies; no forced GC.");
        receipt.put("openingOnly", !firstWrite);
        if (firstWrite) receipt.put("functionalFirstWriteVerified", functional);
        receipt.put("fixtureJsonSha256", fixtureJsonSha);
        receipt.put("fixturePngSha256", fixturePngSha);
        receipt.put("outcome", functional ? (firstWrite || deadlines) ? "PASS" : "TIMING_MISS" : "INCOMPLETE");
        receipt.put("failure", failure);
        receipt.put("functionalNativeEditVerified", functional);
        receipt.put("actorInventoryUnchanged", inventoryFinalChecked);
        receipt.put("inventoryFixtureWrites", 0);
        if (firstWrite) receipt.put("finalManagedAndPristinePairsVerified", pairPristine);
        else receipt.put("pairPristine", pairPristine);
        receipt.put("worldStopped", clean);
        receipt.put("screenRestored", clean);
        receipt.put("cleanupPending", !clean);
        receipt.put("timing", "exact native row press after widget hit-test to natural editor RenderTick.END");
        receipt.put("coldMeaning", "first file Edit in a fresh JVM after title, world, and catalogue readiness; not cold disk");
        receipt.put("warmMeaning", firstWrite ? "physical saved-copy read after clear, native exit, and normal library row reopen"
            : "same native row after normal clean clear, exit, and reopening the same owned world");
        receipt.put("loads", loads);
        receipt.put("artifact", ArtifactIdentity.capture(HologramGiantNativeEditProbe.class).asMap());
        receipt.put("osInput", false);
        if (crossHistory) receipt.put("crossMaterializationHistoryRequested", true);
        Path pending = Files.createTempFile(output, "gtmb-giant-native-edit-", ".json");
        Files.write(pending, (GSON.toJson(receipt) + "\n").getBytes(StandardCharsets.UTF_8));
        Path terminal = output.resolve(crossHistory ? "gtmb-giant-native-cross-history.json"
            : firstWrite ? "gtmb-giant-native-first-write.json" : "gtmb-giant-native-edit.json");
        require(!Files.exists(terminal), "Refusing to replace an existing receipt");
        Files.move(pending, terminal, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        if (historyTiming != null) {
            Path timing = output.resolve(HologramGiantHistoryTiming.FILE);
            require(!Files.exists(timing), "Refusing to replace an existing history timing receipt");
            Path temporary = Files.createTempFile(output, "gtmb-giant-history-timing-", ".json");
            Files.write(temporary, (GSON.toJson(historyTiming.receipt(functional)) + "\n").getBytes(StandardCharsets.UTF_8));
            Files.move(temporary, timing, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private void fail(Throwable problem) {
        problem.printStackTrace(System.err);
        if (failure == null) {
            failure = stage + ": " + problem;
            if (write != null) {
                try {
                    write.recordSaveFailure();
                } catch (RuntimeException witnessFailure) {
                    receipt.put("saveFailureWitnessError", witnessFailure.toString());
                }
            }
            if (load != null) {
                load.put("ownedGuiPosts", guiPosts - openedPosts);
                load.put("consumedGuiPostsAtRenderEnd", Math.max(0, consumedPosts - openedPosts));
                load.put("inputStarted", inputAt != 0);
                if (witness != null) load.putIfAbsent("rendererMaterial", thresholds());
                Minecraft mc = Minecraft.getMinecraft();
                if (screen != null && mc.currentScreen == screen) {
                    load.put("nativeModal", screen.ui.shell.hasModalPanel());
                    load.put("localWorkspaceBusy", screen.ui.library.localBusy());
                    load.put("localOpenPending", screen.ui.library.openingLocalSource());
                    load.put("localRowStillSelected", fixtureId.equals(HologramLibraryClient.INSTANCE.selected(false)));
                    HologramState opening = screen.ui.library.openingPreview();
                    load.put("openingRows", opening == null ? 0 : opening.cells.size());
                    load.put("activeDocument", screen.ui.session.localDocument());
                    HologramState active = screen.ui.session.state();
                    load.put("activeRows", active.cells.size());
                    load.put("sourcePreparing", screen.ui.preview.sourcePreparing());
                    var latest = HologramRenderer.editorMaterialPresentation();
                    load.put("lastEditorMaterialRecordPresent", latest != null);
                    if (latest != null) {
                        load.put("lastEditorMaterialMatchesSubject", latest.state() == (opening == null ? active : opening));
                        load.put("lastEditorMaterialBody", latest.bodyMaterial());
                        load.put("lastEditorMaterialSerial", latest.serial());
                        load.put("lastEditorMaterialRows", latest.submittedCells());
                    }
                }
            }
        }
        if (stage == Stage.RESTORE || stage == Stage.SHUTDOWN) {
            try {
                finish(false);
            } catch (Exception receiptFailure) {
                receiptFailure.printStackTrace(System.err);
            }
            return;
        }
        transition(Stage.RESTORE);
    }

    private static boolean within(Map<String, Object> values, String key, long limit) {
        return values.get(key) instanceof Long nanos && nanos >= 0 && nanos < limit;
    }

    private Map<String, Object> thresholds() {
        return witness.receipt(HologramPreviewThresholdWitness.BLUEPRINT_FIRST_NANOS,
            HologramPreviewThresholdWitness.BLUEPRINT_COMPLETE_NANOS);
    }

    private void transition(Stage next) {
        stageStart = System.nanoTime();
        stage = next;
    }

    private void verifyPair() throws Exception {
        require(source.equals(source.toRealPath())
            && HologramBlueprintArtifact.previewPath(source).equals(HologramBlueprintArtifact.previewPath(source).toRealPath()),
            "Fixture is not the exact managed regular pair");
        var pair = HologramBlueprintArtifact.readPair(source);
        require(fixtureJsonSha.equals(HologramBlueprintArtifact.hash(pair.json)) && fixturePngSha.equals(HologramBlueprintArtifact.hash(pair.png)),
            "Fixture pair bytes changed");
    }

    private static JsonObject read(Path path) throws Exception {
        require(path.equals(path.toRealPath()) && Files.size(path) <= 4096, "Invalid scoped host receipt");
        return new JsonParser().parse(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
