package com.miaokatze.gtmb.client.hologram;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Opt-in observer of an actual save: it never substitutes a mocked transfer or IO completion. */
public final class HologramBackgroundNativeProbe {

    private final NBTTagCompound receipt = new NBTTagCompound();
    private int stage, ticks;
    private long token, started;
    private HologramBackgroundScreen firstScreen;
    private boolean done;
    private NBTTagCompound before;
    private net.minecraft.item.ItemStack projector;

    static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.backgroundProbe")) return;
        FMLCommonHandler.instance()
            .bus()
            .register(new HologramBackgroundNativeProbe());
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END || done) return;
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (stage > 0 && System.nanoTime() - started > 180_000_000_000L)
                throw new IllegalStateException("Background save probe timeout at stage " + stage);
            if (stage == 0) {
                if (!(mc.currentScreen instanceof HologramScreen) || !HologramBackgroundTasks.libraryTask()
                    || !HologramBackgroundTasks.label()
                        .contains("保存"))
                    return;
                NBTTagCompound head = HologramLibraryClient.actualSaveWitness()
                    .getCompoundTag("downloadBeginReply");
                if (HologramBackgroundTasks.awaitingReview()
                    || head.getLong("clientTaskToken") != HologramBackgroundTasks.token()) return;
                started = System.nanoTime();
                token = HologramBackgroundTasks.token();
                before = HologramBackgroundTasks.actualTaskWitness();
                projector = mc.thePlayer.getHeldItem()
                    .copy();
                require(
                    projector.getItem()
                        .hasEffect(projector, 0),
                    "busy projector has the actual enchantment glint");
                receipt.setString("session", HologramClient.state == null ? "" : HologramClient.state.session);
                ((HologramScreen) mc.currentScreen).ui.router.closeRoot();
                require(mc.currentScreen == null && HologramBackgroundTasks.busy(), "close keeps actual save alive");
                receipt.setBoolean("closedWithoutCancel", true);
                stage = 1;
            } else if (stage == 1) {
                if (++ticks < 2) return;
                require(HologramBackgroundTasks.token() == token, "background save retains exact task token");
                require(HologramBackgroundTasks.reopen(), "reopen attaches actual running save");
                require(mc.currentScreen instanceof HologramBackgroundScreen, "new task MUI screen installed");
                firstScreen = (HologramBackgroundScreen) mc.currentScreen;
                receipt.setBoolean("reopenedWithoutRestart", HologramBackgroundTasks.token() == token);
                stage = 2;
            } else if (stage == 2) {
                if (firstScreen.renderedFrames() == 0) return;
                receipt.setLong("actualNativeProgressFrames", firstScreen.renderedFrames());
                net.minecraft.util.ScreenShotHelper.saveScreenshot(
                    mc.mcDataDir,
                    "v019-background-task.png",
                    mc.displayWidth,
                    mc.displayHeight,
                    mc.getFramebuffer());
                Object previousPanel = firstScreen.panel();
                firstScreen.muiKey('\0', org.lwjgl.input.Keyboard.KEY_ESCAPE);
                require(mc.currentScreen == null, "task UI closes while native save is still owned");
                receipt.setBoolean("detachedAgain", true);
                require(HologramBackgroundTasks.reopen(), "second reopen keeps the actual task");
                HologramBackgroundScreen second = (HologramBackgroundScreen) mc.currentScreen;
                require(
                    second != firstScreen && second.panel() != previousPanel,
                    "reopening allocates a fresh MUI panel instead of disposed widgets");
                firstScreen = second;
                receipt.setBoolean("freshPanelAfterDispose", true);
                stage = 3;
            } else if (stage == 3) {
                if (firstScreen.renderedFrames() == 0) return;
                firstScreen.muiKey('\0', org.lwjgl.input.Keyboard.KEY_ESCAPE);
                stage = 4;
            } else if (stage == 4) {
                if (HologramBackgroundTasks.busy()) return;
                require(mc.currentScreen == null, "completion does not reopen a deliberately closed task UI");
                require(HologramBackgroundTasks.succeeded(), "real disk and server save completed successfully");
                NBTTagCompound after = HologramBackgroundTasks.actualTaskWitness();
                require(after.getLong("completedToken") == token, "completion belongs to the original save token");
                require(
                    after.getLong("backgroundNotifications") - before.getLong("backgroundNotifications") == 1,
                    "background chat is emitted once despite repeated UI disposal");
                require(
                    after.getLong("terminalNotifications") - before.getLong("terminalNotifications") == 1,
                    "real save emits one terminal chat notification");
                require(
                    after.getLong("levelupSounds") - before.getLong("levelupSounds") == 1,
                    "real save completion plays one actual level-up sound");
                require(
                    !projector.getItem()
                        .hasEffect(projector, 0),
                    "completed task releases projector glint");
                receipt.setTag("before", before);
                receipt.setTag("after", after);
                NBTTagCompound save = HologramLibraryClient.actualSaveWitness();
                require(
                    "saveCommitted".equals(
                        save.getCompoundTag("finalOutcome")
                            .getString("op")),
                    "success follows the actual committed server receipt");
                require(save.hasKey("ownerDiskAck", 10), "owner disk acknowledgement precedes success");
                receipt.setTag("actualSave", save);
                receipt.setBoolean("glintReleased", true);
                String id = HologramLibraryClient.INSTANCE.selected(false);
                Path json = mc.mcDataDir.toPath()
                    .resolve("MiaoBlueprints/" + id + ".json");
                Path png = mc.mcDataDir.toPath()
                    .resolve("MiaoBlueprints/" + id + ".png");
                require(
                    !id.isEmpty() && Files.isRegularFile(json) && Files.isRegularFile(png),
                    "actual saved JSON and PNG exist after background completion");
                require(
                    javax.imageio.ImageIO.read(png.toFile()) != null,
                    "actual preview PNG decodes after UI disposal");
                receipt.setString("localId", id);
                receipt.setString("json", json.toString());
                receipt.setString("png", png.toString());
                receipt.setLong("jsonBytes", Files.size(json));
                receipt.setLong("pngBytes", Files.size(png));
                receipt.setBoolean("actualIoCompleted", true);
                receipt.setBoolean("completedWhileUiClosed", true);
                receipt.setBoolean("passed", true);
                finish();
                HologramBackgroundTasks.returnToWorkspace();
            }
        } catch (Throwable failure) {
            receipt.setBoolean("passed", false);
            receipt.setString("failure", failure.toString());
            com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG.error("Background save native probe failed", failure);
            try {
                finish();
            } catch (Exception persistenceFailure) {
                com.miaokatze.gtmb.main.GTMiaoBlueprint.LOG
                    .error("Background probe receipt failed", persistenceFailure);
            }
        }
    }

    private void finish() throws Exception {
        done = true;
        receipt.setBoolean("completed", true);
        receipt.setBoolean("actualClientThread", true);
        receipt.setBoolean("dedicatedSocket", HologramLibraryClient.dedicated());
        receipt.setLong("taskToken", token);
        File directory = new File(
            System.getProperty("gtmb.hologram.backgroundProbeDir", "build/verification/render-v019/background"));
        Files.createDirectories(directory.toPath());
        CompressedStreamTools.write(receipt, new File(directory, "background-save-native.nbt"));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }
}
