package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.hologram.HologramAuthorPortableCompiler;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;

/** User-triggered source -> processed candidate -> exact native PNG -> export/private admission. */
final class HologramAuthorSubmission {

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(
        1,
        1,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<Runnable>(2),
        runnable -> {
            Thread thread = new Thread(runnable, "GTMB-author-candidate");
            thread.setDaemon(true);
            return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());

    private HologramAuthorSubmission() {}

    static void submit(HologramLocalAuthorRuntime owner, NBTTagCompound confirmedPolicy, boolean place, double yaw,
        double pitch) {
        if (owner == null || !owner.editable()) {
            notice("当前作者文档不可提交");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            notice("正在生成候选，请稍候");
            return;
        }
        AuthorDocument frozen = owner.document();
        Minecraft minecraft = Minecraft.getMinecraft();
        Object originalWorld = minecraft.theWorld;
        net.minecraft.item.ItemStack originalTool = minecraft.thePlayer == null ? null
            : minecraft.thePlayer.getHeldItem();
        int originalSlot = minecraft.thePlayer == null ? -1 : minecraft.thePlayer.inventory.currentItem;
        BooleanSupplier current = () -> HologramLocalAuthorRuntime.active() == owner && owner.document() == frozen
            && (!place || minecraft.theWorld == originalWorld && minecraft.thePlayer != null
                && minecraft.thePlayer.getHeldItem() == originalTool
                && minecraft.thePlayer.inventory.currentItem == originalSlot);
        try {
            // Native registry/providers are owner-thread work. Never put this call in the IO executor.
            byte[] json = HologramAuthorPortableCompiler.compile(frozen, confirmedPolicy, 0, 0, 0, 0);
            notice("正在从当前源版本生成原生预览；这是未获服务器审核的候选");
            work(() -> {
                NBTTagCompound exact = HologramBlueprintPreview.snapshot(json);
                HologramBlueprintPreview.capture(exact, yaw, pitch, current, pixels -> work(() -> {
                    byte[] png = png(pixels, current);
                    HologramBlueprintArtifact.Artifact pair = HologramBlueprintArtifact.prepareSave(json, png);
                    if (!current.getAsBoolean()) throw new IOException("文档已变化，候选已取消；草稿保留");
                    Minecraft.getMinecraft()
                        .func_152344_a(() -> {
                            if (!current.getAsBoolean()) {
                                fail(new IOException("文档已变化，请重新提交"));
                                return;
                            }
                            if (place) {
                                try {
                                    HologramLibraryClient.INSTANCE.beginAuthorCandidatePlacement(
                                        pair,
                                        exact,
                                        frozen.documentId() + ":" + frozen.revision(),
                                        current);
                                    BUSY.set(false);
                                    notice("候选已交给服务器内容核验；施工、材料和世界权限仍须服务器审核");
                                } catch (Exception failure) {
                                    fail(failure);
                                }
                            } else HologramLibraryClient.INSTANCE.persistAuthorArtifact(pair, current, path -> {
                                BUSY.set(false);
                                notice("未获服务器审核的候选 JSON + PNG 已导出：" + path + "；原草稿保留");
                            }, HologramAuthorSubmission::fail);
                        });
                }), HologramAuthorSubmission::fail);
            });
        } catch (Exception | LinkageError failure) {
            fail(failure);
        }
    }

    private interface Job {

        void run() throws Exception;
    }

    private static void work(Job job) {
        try {
            IO.execute(() -> {
                try {
                    job.run();
                } catch (Exception | LinkageError failure) {
                    fail(failure);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            fail(full);
        }
    }

    private static byte[] png(java.awt.image.BufferedImage image, BooleanSupplier current) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {

            int written;

            private void reserve(int n) throws IOException {
                if (!current.getAsBoolean()) throw new IOException("Author source changed during PNG encoding");
                if (n > HologramBlueprintArtifact.MAX_PNG_BYTES - written)
                    throw new IOException("Candidate PNG byte budget");
                written += n;
            }

            public void write(int value) throws IOException {
                reserve(1);
                bytes.write(value);
            }

            public void write(byte[] buffer, int offset, int length) throws IOException {
                reserve(length);
                bytes.write(buffer, offset, length);
            }
        };
        if (!ImageIO.write(image, "png", bounded)) throw new IOException("PNG encoder unavailable");
        return bytes.toByteArray();
    }

    private static void fail(Throwable failure) {
        BUSY.set(false);
        notice(
            "候选提交失败，原草稿及未兑现义务保留：" + java.util.Objects.toString(
                failure.getMessage(),
                failure.getClass()
                    .getSimpleName()));
    }

    private static void notice(String message) {
        Minecraft.getMinecraft()
            .func_152344_a(() -> {
                if (Minecraft.getMinecraft().currentScreen instanceof HologramScreen)
                    ((HologramScreen) Minecraft.getMinecraft().currentScreen).ui.session.notice(message);
            });
    }
}
