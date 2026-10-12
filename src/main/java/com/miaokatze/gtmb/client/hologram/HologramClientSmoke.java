package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ScreenShotHelper;

import org.lwjgl.opengl.GL11;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in isolated render smoke. It loads a real server snapshot and never sends an action packet. */
public final class HologramClientSmoke {

    private final long installedAt = System.nanoTime();
    private NBTTagCompound snapshot;
    private int scale = 0;
    private long stageAt;
    private boolean finished;
    private File snapshotFile;
    private NBTTagCompound blueprintSnapshot;
    private NBTTagCompound nativeDisplaySnapshot;
    private NBTTagCompound nativeCompatibilitySnapshot, nativeCompatibilityRotated, activeScene;
    private NBTTagCompound nativeRoofSnapshot, nativeRoofRotated;
    private int scenario = -1;
    private static final String[] BASE_SCENARIOS = { "blueprint", "prompts", "gizmo", "gizmo-hover-structure",
        "gizmo-hover-hint", "gizmo-hover-combined", "missing", "missing-source", "versions", "materials", "nbt-storage",
        "nbt-placement", "resources", "missing-confirm", "force-ready", "editor-selection", "editor-library",
        "prompt-preview", "native-display-target", "native-display-current", "editor-v004", "editor-gt-identity",
        "editor-family", "editor-tabs", "blueprint" };
    private static final String[] SCENARIOS = scenarios();

    private static String[] scenarios() {
        if (!Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke")) return BASE_SCENARIOS;
        java.util.List<String> scenes = new java.util.ArrayList<>(java.util.Arrays.asList(BASE_SCENARIOS));
        scenes.addAll(
            scenes.size() - 1,
            java.util.Arrays.asList(
                "native-six-target-a",
                "native-six-current-a",
                "native-six-rotated-a",
                "native-six-target-b",
                "native-six-current-b",
                "native-six-rotated-b"));
        return scenes.toArray(new String[0]);
    }

    static void install() {
        if (Boolean.getBoolean("gtmb.hologram.clientSmoke")) FMLCommonHandler.instance()
            .bus()
            .register(new HologramClientSmoke());
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (finished || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (scale == 0) {
                if (!(mc.currentScreen instanceof GuiMainMenu)) {
                    if (System.nanoTime() - installedAt > 300000000000L)
                        throw new IllegalStateException("MainMenu initialization timed out");
                    return;
                }
                String path = System.getProperty("gtmb.hologram.clientState", "");
                if (path.isEmpty()) throw new IllegalStateException("Missing gtmb.hologram.clientState");
                snapshotFile = new File(path);
                try (InputStream input = new FileInputStream(new File(path))) {
                    snapshot = CompressedStreamTools.readCompressed(input);
                }
                if (snapshot == null || snapshot.getTagList("cells", 10)
                    .tagCount() == 0 || !snapshot.getBoolean("target")) {
                    throw new IllegalStateException("Expected a real target state with captured cells");
                }
                System.out.println(
                    "[GTMB-HOLOGRAM-CLIENT] real snapshot " + new File(path).getAbsolutePath()
                        + " cells="
                        + snapshot.getTagList("cells", 10)
                            .tagCount());
                File nativeSource = new File(
                    System.getProperty(
                        "gtmb.hologram.nativeDisplayClientState",
                        new File(snapshotFile.getParentFile(), "hologram-native-display-client-state.nbt").getPath()));
                try (InputStream input = new FileInputStream(nativeSource)) {
                    nativeDisplaySnapshot = CompressedStreamTools.readCompressed(input);
                }
                validateNativeDisplay(nativeDisplaySnapshot);
                if (Boolean.getBoolean("gtmb.hologram.extendedCompatibilitySmoke")) {
                    nativeCompatibilitySnapshot = HologramNativeCompatibilitySmoke.fixture(false);
                    nativeCompatibilityRotated = HologramNativeCompatibilitySmoke
                        .rotate(nativeCompatibilitySnapshot, false, 1);
                    verifyNativeTurn(nativeCompatibilitySnapshot, nativeCompatibilityRotated);
                    nativeRoofSnapshot = HologramNativeCompatibilitySmoke.fixture(true);
                    nativeRoofRotated = HologramNativeCompatibilitySmoke.rotate(nativeRoofSnapshot, false, 1);
                    verifyNativeTurn(nativeRoofSnapshot, nativeRoofRotated);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new File(mc.mcDataDir, "hologram-native-six-client-state.nbt"))) {
                        CompressedStreamTools.writeCompressed(nativeCompatibilitySnapshot, out);
                    }
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new File(mc.mcDataDir, "hologram-native-six-rotated-client-state.nbt"))) {
                        CompressedStreamTools.writeCompressed(nativeCompatibilityRotated, out);
                    }
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new File(mc.mcDataDir, "hologram-native-six-roof-client-state.nbt"))) {
                        CompressedStreamTools.writeCompressed(nativeRoofSnapshot, out);
                    }
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(
                        new File(mc.mcDataDir, "hologram-native-six-roof-rotated-client-state.nbt"))) {
                        CompressedStreamTools.writeCompressed(nativeRoofRotated, out);
                    }
                }
                nextScale(mc);
                return;
            }
            if (System.nanoTime() - stageAt < 2000000000L) return;
            if (!(mc.currentScreen instanceof HologramScreen))
                throw new IllegalStateException("Hologram screen was replaced");
            HologramScreen screen = (HologramScreen) mc.currentScreen;
            String layout = screen.smokeLayout();
            if (scenario >= 0 && SCENARIOS[scenario].startsWith("gizmo-hover-")) System.out.println(
                "[GTMB-HOLOGRAM-CLIENT] PASS " + SCENARIOS[scenario]
                    + " "
                    + screen.smokeRenderGizmoHover(SCENARIOS[scenario]));
            if (scenario >= 0 && "editor-selection".equals(SCENARIOS[scenario])) System.out
                .println("[GTMB-HOLOGRAM-CLIENT] PASS editor-selection " + screen.smokeCaptureLocalSelection());
            if (scenario >= 0 && "editor-library".equals(SCENARIOS[scenario])) System.out
                .println("[GTMB-HOLOGRAM-CLIENT] PASS editor-library " + screen.smokeCaptureLibraryWorkflow());
            if (scenario >= 0 && "editor-v004".equals(SCENARIOS[scenario]))
                System.out.println("[GTMB-HOLOGRAM-CLIENT] PASS editor-v004 " + screen.smokeCaptureV004Editor());
            if (scenario >= 0 && "editor-tabs".equals(SCENARIOS[scenario]))
                System.out.println("[GTMB-HOLOGRAM-CLIENT] PASS editor-tabs " + screen.smokeCaptureV004Tabs());
            if (scenario >= 0
                && ("editor-gt-identity".equals(SCENARIOS[scenario]) || "editor-family".equals(SCENARIOS[scenario])))
                System.out.println(
                    "[GTMB-HOLOGRAM-CLIENT] PASS " + SCENARIOS[scenario]
                        + " "
                        + captureDeclaredSelection(screen, "editor-gt-identity".equals(SCENARIOS[scenario])));
            if (scenario >= 0 && SCENARIOS[scenario].startsWith("native-six-")) System.out.println(
                "[GTMB-HOLOGRAM-CLIENT] PASS " + SCENARIOS[scenario]
                    + " "
                    + HologramNativeCompatibilitySmoke
                        .verify(new HologramState(activeScene), SCENARIOS[scenario].contains("current")));
            int glError = GL11.glGetError();
            if (glError != GL11.GL_NO_ERROR) throw new IllegalStateException("OpenGL error " + glError);
            String name = scenario < 0 ? "hologram-scale-" + scale
                : "hologram-" + SCENARIOS[scenario] + "-scale-" + scale;
            File screenshot = new File(new File(mc.mcDataDir, "screenshots"), name + ".png");
            if (screenshot.exists() && !screenshot.delete())
                throw new IllegalStateException("Cannot replace smoke screenshot: " + screenshot);
            ScreenShotHelper
                .saveScreenshot(mc.mcDataDir, name + ".png", mc.displayWidth, mc.displayHeight, mc.getFramebuffer());
            if (!screenshot.isFile() || screenshot.length() == 0)
                throw new IllegalStateException("Screenshot not saved: " + screenshot);
            if (scenario >= 0
                && ("force-ready".equals(SCENARIOS[scenario]) || "missing-confirm".equals(SCENARIOS[scenario]))) {
                NBTTagCompound captured = screen.smokeCaptureMissingBuildClick();
                System.out.println(
                    "[GTMB-HOLOGRAM-CLIENT] PASS actual modal mouse click captured op=" + captured.getString("op")
                        + " revision="
                        + captured.getLong("planRevision")
                        + "; build reply reopens once, cancel survives later snapshot; no packet sent (fixture)");
            }
            ScaledResolution resolution = new ScaledResolution(mc, mc.displayWidth, mc.displayHeight);
            System.out.println(
                "[GTMB-HOLOGRAM-CLIENT] PASS scale=" + scale
                    + " actual="
                    + resolution.getScaleFactor()
                    + " "
                    + layout
                    + " screenshot="
                    + screenshot.getAbsolutePath());
            if (scenario >= 0 || scale == 3) nextScenario(mc);
            else nextScale(mc);
        } catch (Throwable failure) {
            failure.printStackTrace();
            finish(mc, false, failure.toString());
        }
    }

    private void nextScale(Minecraft mc) {
        scale++;
        mc.gameSettings.guiScale = scale;
        mc.displayGuiScreen(new HologramScreen(new HologramState(snapshot)));
        stageAt = System.nanoTime();
    }

    private void nextScenario(Minecraft mc) throws Exception {
        scenario++;
        if (scenario >= SCENARIOS.length) {
            finish(
                mc,
                true,
                "structure + real schema2 blueprint/prompt/gizmo/material/version render; missing-block texture is an explicit unresolved-registry fixture; no world action packet sent");
            return;
        }
        if (blueprintSnapshot == null) {
            File source = new File(
                System.getProperty(
                    "gtmb.hologram.blueprintClientState",
                    new File(snapshotFile.getParentFile(), "hologram-blueprint-client-state.nbt").getPath()));
            try (InputStream input = new FileInputStream(source)) {
                blueprintSnapshot = CompressedStreamTools.readCompressed(input);
            }
            if (!blueprintSnapshot.getBoolean("virtual") || !blueprintSnapshot.hasKey("blueprintAwaitingConfirmation")
                || blueprintSnapshot.getTagList("cells", 10)
                    .tagCount() == 0)
                throw new IllegalStateException("Expected real schema2 blueprint snapshot: " + source);
            System.out.println("[GTMB-HOLOGRAM-CLIENT] real blueprint snapshot " + source.getAbsolutePath());
        }
        NBTTagCompound scene = (NBTTagCompound) blueprintSnapshot.copy();
        if (SCENARIOS[scenario].startsWith("native-display-")) {
            scene = (NBTTagCompound) nativeDisplaySnapshot.copy();
        }
        if ("editor-gt-identity".equals(SCENARIOS[scenario])) {
            scene = (NBTTagCompound) nativeDisplaySnapshot.copy();
            NBTTagList cells = scene.getTagList("cells", 10);
            for (int i = 0; i < cells.tagCount(); i++) {
                NBTTagCompound row = cells.getCompoundTagAt(i);
                row.setString(
                    "exactIdentity",
                    com.miaokatze.gtmb.hologram.HologramBlockIdentity
                        .key(net.minecraft.item.ItemStack.loadItemStackFromNBT(row.getCompoundTag("targetStack"))));
            }
        }
        if ("editor-family".equals(SCENARIOS[scenario])) scene = (NBTTagCompound) snapshot.copy();
        if ("editor-v004".equals(SCENARIOS[scenario])) scene = editorStructureFixture();
        if (SCENARIOS[scenario].startsWith("native-six-")) {
            boolean roof = SCENARIOS[scenario].endsWith("-b");
            scene = (NBTTagCompound) (SCENARIOS[scenario].contains("rotated")
                ? roof ? nativeRoofRotated : nativeCompatibilityRotated
                : roof ? nativeRoofSnapshot : nativeCompatibilitySnapshot).copy();
        }
        if ("editor-selection".equals(SCENARIOS[scenario])) scene = (NBTTagCompound) snapshot.copy();
        if ("prompt-preview".equals(SCENARIOS[scenario])) {
            scene.setString("blueprintPrompt", "Native preview annotation smoke");
            NBTTagCompound cell = scene.getTagList("cells", 10)
                .getCompoundTagAt(0);
            cell.setString("prompt", "Recorded cell instruction visible in preview");
            NBTTagCompound hint = new NBTTagCompound();
            hint.setString("prompt", "Recorded area instruction visible in preview");
            hint.setIntArray(
                "bounds",
                new int[] { cell.getInteger("dx"), cell.getInteger("dy"), cell.getInteger("dz"),
                    cell.getInteger("dx") + 1, cell.getInteger("dy") + 1, cell.getInteger("dz") + 1 });
            NBTTagList hints = new NBTTagList();
            hints.appendTag(hint);
            scene.setTag("hintAreas", hints);
        }
        if (SCENARIOS[scenario].startsWith("gizmo-hover-")) scene = gizmoHoverFixture(SCENARIOS[scenario]);
        if ("missing-confirm".equals(SCENARIOS[scenario]) || "force-ready".equals(SCENARIOS[scenario])) {
            scene.setBoolean("virtualPlacement", true);
            scene.setBoolean("virtualLocked", true);
            scene.setBoolean("blueprintAwaitingConfirmation", false);
            scene.setBoolean("forcePlacementReady", "force-ready".equals(SCENARIOS[scenario]));
            scene.setBoolean("creativeMaterials", false);
            scene.setInteger("missing", 1);
            scene.setString("status", "缺料测试 fixture：再次确认仅放已有，缺口保持未放置");
            NBTTagCompound cell = scene.getTagList("cells", 10)
                .getCompoundTagAt(0);
            cell.setBoolean("inScope", true);
            cell.setString("operation", "PLACE");
            cell.setString("status", "missing");
        }
        if ("missing".equals(SCENARIOS[scenario]) || "missing-source".equals(SCENARIOS[scenario])) {
            NBTTagCompound cell = scene.getTagList("cells", 10)
                .getCompoundTagAt(0);
            cell.setString("wantId", "gtmb_smoke_missing:block");
            cell.setBoolean("missingBlock", true);
            cell.setBoolean("anchor", false);
            cell.setBoolean("iconOnly", false);
            cell.setString("role", "");
            cell.removeTag("targetStack");
            cell.removeTag("actualStack");
            scene.setBoolean("sourceMissing", true);
            scene.setString("sourceClass", "gtmb_smoke_missing.UnavailableController");
            scene.setString("sourceWarning", "旧蓝图来源类不可加载或控制器 ID 已复用（测试 fixture）");
            cell.setBoolean("sourceMissing", true);
            cell.setString("sourceClass", scene.getString("sourceClass"));
            cell.setString("sourceWarning", scene.getString("sourceWarning"));
            if ("missing-source".equals(SCENARIOS[scenario]))
                scene.setTag("modVersions", new net.minecraft.nbt.NBTTagList());
        }
        scale = scenario == SCENARIOS.length - 1 ? 3 : 1;
        mc.gameSettings.guiScale = scale;
        HologramScreen screen = new HologramScreen(new HologramState(scene));
        activeScene = scene;
        mc.displayGuiScreen(screen);
        String renderScenario = SCENARIOS[scenario].startsWith("native-six-")
            ? SCENARIOS[scenario].contains("current") ? "native-display-current" : "native-display-target"
            : SCENARIOS[scenario];
        screen.smokeRenderScenario(renderScenario);
        if (SCENARIOS[scenario].startsWith("native-six-")) {
            java.lang.reflect.Field yaw = HologramScreen.class.getDeclaredField("yaw");
            yaw.setAccessible(true);
            yaw.setDouble(screen, SCENARIOS[scenario].endsWith("-b") ? -Math.PI / 3 : Math.PI / 4);
        }
        stageAt = System.nanoTime();
    }

    private static void verifyNativeTurn(NBTTagCompound source, NBTTagCompound rotated) {
        for (boolean current : new boolean[] { false, true }) HologramNativeCompatibilitySmoke
            .verifyTurn(new HologramState(source), new HologramState(rotated), current, false);
    }

    /** A complete real native controller capture supplies the members of this local schema2 editor fixture. */
    private NBTTagCompound editorStructureFixture() {
        NBTTagCompound scene = (NBTTagCompound) snapshot.copy();
        NBTTagList cells = scene.getTagList("cells", 10);
        if (!scene.getBoolean("supported") || scene.getBoolean("capturePending") || cells.tagCount() != 36)
            throw new IllegalStateException("Editor movement smoke requires a real complete native structure capture");
        NBTTagCompound anchor = null;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        String id = "native-ebf-editor";
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (cell.getBoolean("anchor")) anchor = cell;
            maxX = Math.max(maxX, cell.getInteger("dx"));
            maxZ = Math.max(maxZ, cell.getInteger("dz"));
            cell.setString("structureId", id);
            NBTTagList memberships = new NBTTagList();
            memberships.appendTag(new net.minecraft.nbt.NBTTagString(id));
            cell.setTag("structureIds", memberships);
        }
        if (anchor == null) throw new IllegalStateException("Native editor fixture lost its real controller anchor");
        // Protected controller targets may be a generic declared shell; actualStack carries the captured MetaTile ID.
        net.minecraft.item.ItemStack controller = net.minecraft.item.ItemStack
            .loadItemStackFromNBT(anchor.getCompoundTag("actualStack"));
        if (controller == null || !(controller.getItem() instanceof gregtech.common.blocks.ItemMachines))
            throw new IllegalStateException("Native editor fixture requires its captured GT controller identity");
        int controllerId = controller.getItemDamage();
        gregtech.api.interfaces.metatileentity.IMetaTileEntity prototype = controllerId >= 0
            && controllerId < gregtech.api.GregTechAPI.METATILEENTITIES.length
                ? gregtech.api.GregTechAPI.METATILEENTITIES[controllerId]
                : null;
        if (!(prototype instanceof gregtech.common.tileentities.machines.multi.MTEElectricBlastFurnace))
            throw new IllegalStateException("Native editor controller is not the captured registered EBF");
        NBTTagCompound structure = new NBTTagCompound();
        structure.setString("id", id);
        structure.setString("name", "Native captured EBF editor fixture");
        structure.setString("sourceMod", "gregtech");
        structure.setString(
            "sourceClass",
            prototype.getClass()
                .getName());
        structure.setInteger("controllerId", controllerId);
        structure.setBoolean("enabled", true);
        NBTTagList structures = new NBTTagList();
        structures.appendTag(structure);
        scene.setTag("structures", structures);
        NBTTagCompound outside = null;
        NBTTagList loose = blueprintSnapshot.getTagList("cells", 10);
        for (int i = 0; i < loose.tagCount(); i++) if ("minecraft:stone".equals(
            loose.getCompoundTagAt(i)
                .getString("wantId"))) {
                    outside = (NBTTagCompound) loose.getCompoundTagAt(i)
                        .copy();
                    break;
                }
        if (outside == null)
            throw new IllegalStateException("Editor smoke requires a real captured loose block outside the structure");
        outside.removeTag("structureIds");
        outside.setString("structureId", "");
        outside.setBoolean("anchor", false);
        outside.setInteger("dx", maxX + 2);
        outside.setInteger("dy", 0);
        outside.setInteger("dz", maxZ + 2);
        outside.setInteger("x", scene.getInteger("x") + outside.getInteger("dx"));
        outside.setInteger("y", scene.getInteger("y"));
        outside.setInteger("z", scene.getInteger("z") + outside.getInteger("dz"));
        cells.appendTag(outside);
        scene.setString("session", "native-ebf-editor-fixture:" + snapshot.getString("session"));
        scene.setString("activeStructure", "");
        scene.setBoolean("virtual", true);
        scene.setBoolean("geometryEditable", true);
        scene.setBoolean("virtualPlacement", false);
        scene.setBoolean("virtualLocked", false);
        scene.setBoolean("blueprintPreview", false);
        scene.setBoolean("blueprintAwaitingConfirmation", false);
        scene.setBoolean("presetBrowseForPlacement", false);
        scene.setInteger("job", 0);
        scene.setInteger("selected", -1);
        scene.setIntArray("selectedIndices", new int[0]);
        scene.setString("openPurpose", "");
        return scene;
    }

    /** Real preview hit testing and mouse routing must keep machine IDs and declared families distinct. */
    private String captureDeclaredSelection(HologramScreen screen, boolean exactGt) throws Exception {
        HologramState state = new HologramState(activeScene);
        int[] bounds = new int[4];
        String[] fields = { "viewportX", "viewportY", "viewportWidth", "viewportHeight" };
        for (int i = 0; i < fields.length; i++) {
            java.lang.reflect.Field field = HologramScreen.class.getDeclaredField(fields[i]);
            field.setAccessible(true);
            bounds[i] = field.getInt(screen);
        }
        if (exactGt) {
            boolean sameBlockMetadataDifferentMachine = false;
            for (HologramState.Cell a : state.cells) for (HologramState.Cell b : state.cells)
                if (a.wantId.equals(b.wantId) && a.wantMeta == b.wantMeta && !a.exactIdentity.equals(b.exactIdentity))
                    sameBlockMetadataDifferentMachine = true;
            if (!sameBlockMetadataDifferentMachine)
                throw new IllegalStateException("GT selection requires real distinct machines sharing block metadata");
        }
        for (int y = bounds[1] + 25; y < bounds[1] + bounds[3] - 35; y += 2)
            for (int x = bounds[0] + 10; x < bounds[0] + bounds[2] - 10; x += 2) {
                int index = HologramRenderer.pick(x, y);
                if (index < 0 || !HologramRenderer.pickStructure(x, y)
                    .isEmpty()) continue;
                HologramState.Cell clicked = state.cells.get(index);
                if (exactGt
                    ? clicked.targetStack == null
                        || !(clicked.targetStack.getItem() instanceof gregtech.common.blocks.ItemMachines)
                    : clicked.family.isEmpty()) continue;
                if (exactGt) {
                    boolean ambiguousWorldMetadata = false;
                    for (HologramState.Cell member : state.cells)
                        if (clicked.wantId.equals(member.wantId) && clicked.wantMeta == member.wantMeta
                            && !clicked.exactIdentity.equals(member.exactIdentity)) ambiguousWorldMetadata = true;
                    if (!ambiguousWorldMetadata) continue;
                }
                String active = state.data.getString("activeStructure");
                String scope = clicked.belongsToStructure(active) ? active : clicked.structureId;
                java.util.Set<Integer> expected = new java.util.LinkedHashSet<>();
                expected.add(index);
                for (HologramState.Cell member : state.cells) {
                    boolean inScope = scope.isEmpty() ? member.structureIds.isEmpty()
                        : member.belongsToStructure(scope);
                    if (inScope && (exactGt ? HologramSelection.sameType(clicked, member)
                        : clicked.family.equals(member.family))) expected.add(member.index);
                }
                if (!exactGt && expected.size() < 2) continue;
                int[] actual = screen.smokeCapturePreviewSelection(x, y, exactGt, false);
                java.util.Set<Integer> selected = new java.util.LinkedHashSet<>();
                for (int member : actual) selected.add(member);
                if (!expected.equals(selected)) throw new IllegalStateException(
                    "Native preview selection expected=" + expected + " actual=" + selected);
                return "actual mouse=" + x + "," + y + " selected=" + selected + " exactGT=" + exactGt;
            }
        throw new IllegalStateException(
            "No real preview face found for " + (exactGt ? "GT identity" : "declared family"));
    }

    /** This fixture is written from real GT pipe/hatch/cover objects by the isolated server smoke. */
    private static void validateNativeDisplay(NBTTagCompound scene) {
        HologramState state = new HologramState(scene);
        HologramTextures.begin(state);
        int pipes = 0, hatches = 0, covers = 0;
        for (HologramState.Cell cell : state.cells) {
            for (boolean current : new boolean[] { false, true }) {
                HologramTextures.Material material = HologramTextures.material(cell, current);
                if (!material.gregTech || material.fallback)
                    throw new IllegalStateException("Native fixture has unresolved GT material at cell " + cell.index);
                for (int side = 0; side < 6; side++) for (int layer = 0; layer < material.sides[side].size(); layer++) {
                    HologramTextures.Sample sample = material.sides[side].get(layer);
                    if (sample.icon.getIconName()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("error_rendering")
                        || sample.icon.getIconName()
                            .toLowerCase(java.util.Locale.ROOT)
                            .contains("rendering_error")
                        || sample.icon.getIconName()
                            .contains("missingno"))
                        throw new IllegalStateException(
                            "Native fixture uses error texture at cell " + cell.index
                                + " current="
                                + current
                                + " side="
                                + side
                                + " layer="
                                + layer
                                + " icon="
                                + sample.icon.getIconName()
                                + " "
                                + nativeTextureDiagnostic(cell, current, side));
                }
            }
            if (HologramTextures.material(cell, false).pipe) pipes++;
            if (cell.targetDisplay.hasKey("texturePage") && cell.targetDisplay.hasKey("textureIndex")) hatches++;
            covers += cell.targetDisplay.getTagList("gt.covers", 10)
                .tagCount();
        }
        if (pipes == 0 || hatches == 0 || covers == 0)
            throw new IllegalStateException("Native display smoke requires real pipe, cased hatch and cover snapshots");
        System.out.println(
            "[GTMB-HOLOGRAM-CLIENT] PASS real native GT display callbacks pipes=" + pipes
                + " cased-hatches="
                + hatches
                + " covers="
                + covers
                + " both target/current resolved without missing textures");
    }

    private static String nativeTextureDiagnostic(HologramState.Cell cell, boolean current, int side) {
        net.minecraft.item.ItemStack stack = current ? cell.actualStack : cell.targetStack;
        int id = stack == null ? -1 : stack.getItemDamage();
        gregtech.api.interfaces.metatileentity.IMetaTileEntity machine = id >= 0
            && id < gregtech.api.GregTechAPI.METATILEENTITIES.length ? gregtech.api.GregTechAPI.METATILEENTITIES[id]
                : null;
        StringBuilder info = new StringBuilder("stack=").append(stack)
            .append(" machineId=")
            .append(id)
            .append(" machine=")
            .append(
                machine == null ? "null"
                    : machine.getClass()
                        .getName());
        NBTTagCompound display = current ? cell.actualDisplay : cell.targetDisplay;
        info.append(" display=")
            .append(display);
        if (machine instanceof gregtech.api.metatileentity.implementations.MTEFluidPipe) {
            gregtech.api.metatileentity.implementations.MTEFluidPipe pipe = (gregtech.api.metatileentity.implementations.MTEFluidPipe) machine;
            info.append(" material=")
                .append(pipe.mMaterial)
                .append(" set=")
                .append(pipe.mMaterial.mIconSet.mSetName)
                .append(" thickness=")
                .append(pipe.mThickNess)
                .append(" pipeAmount=")
                .append(pipe.mPipeAmount);
        }
        if (machine != null) {
            gregtech.api.interfaces.ITexture[][] nativeFaces = HologramMachineTextures.resolve(
                machine,
                current ? cell.actualFacing : cell.targetFacing,
                new int[] { 0, 1, 2, 3, 4, 5 },
                display);
            if (nativeFaces != null && nativeFaces[side] != null)
                for (gregtech.api.interfaces.ITexture texture : nativeFaces[side]) {
                    info.append(" nativeLayer=")
                        .append(
                            texture == null ? "null"
                                : texture.getClass()
                                    .getName());
                    if (texture instanceof gregtech.common.render.IIconTexture) {
                        net.minecraft.util.IIcon base = ((gregtech.common.render.IIconTexture) texture)
                            .getIcon(side, null);
                        info.append(" base=")
                            .append(base == null ? "null" : base.getIconName());
                    }
                    if (texture instanceof gregtech.common.render.GTRenderedTexture) {
                        net.minecraft.util.IIcon overlay = ((gregtech.common.render.GTRenderedTexture) texture)
                            .getIcon(net.minecraftforge.common.util.ForgeDirection.getOrientation(side), true, null);
                        info.append(" overlay=")
                            .append(overlay == null ? "null" : overlay.getIconName());
                    }
                }
        }
        return info.toString();
    }

    /** Keep a real captured block, with local-only overlays arranged to overlap its movement axes. */
    private NBTTagCompound gizmoHoverFixture(String scenario) {
        NBTTagCompound scene = (NBTTagCompound) snapshot.copy();
        scene.setBoolean("virtual", true);
        scene.setBoolean("geometryEditable", true);
        NBTTagCompound cell = (NBTTagCompound) scene.getTagList("cells", 10)
            .getCompoundTagAt(0)
            .copy();
        cell.setString("prompt", "");
        cell.setString("role", "");
        cell.setBoolean("anchor", false);
        cell.removeTag("structureIds");
        cell.setString("structureId", "gizmo-hover-hint".equals(scenario) ? "" : "smoke-overlap");
        NBTTagList cells = new NBTTagList();
        cells.appendTag(cell);
        scene.setTag("cells", cells);
        scene.setString("blueprintPrompt", "");
        scene.setString("activeStructure", "");
        scene.setBoolean("blueprintAwaitingConfirmation", false);
        scene.setBoolean("blueprintPreview", false);
        scene.setBoolean("presetBrowseForPlacement", false);
        NBTTagList structures = new NBTTagList();
        if (!"gizmo-hover-hint".equals(scenario)) {
            NBTTagCompound structure = new NBTTagCompound();
            structure.setString("id", "smoke-overlap");
            structure.setString("name", "移动轴重叠结构");
            structures.appendTag(structure);
        }
        scene.setTag("structures", structures);
        NBTTagList hints = new NBTTagList();
        if (!"gizmo-hover-structure".equals(scenario)) {
            NBTTagCompound hint = new NBTTagCompound();
            hint.setString("prompt", "移动轴重叠提示");
            hint.setIntArray(
                "bounds",
                new int[] { cell.getInteger("dx"), cell.getInteger("dy"), cell.getInteger("dz"), cell.getInteger("dx"),
                    cell.getInteger("dy"), cell.getInteger("dz") });
            hints.appendTag(hint);
        }
        scene.setTag("hintAreas", hints);
        return scene;
    }

    private void finish(Minecraft mc, boolean passed, String reason) {
        finished = true;
        System.out.println("[GTMB-HOLOGRAM-CLIENT] FINAL " + (passed ? "PASS " : "FAIL ") + reason);
        mc.shutdown();
    }
}
