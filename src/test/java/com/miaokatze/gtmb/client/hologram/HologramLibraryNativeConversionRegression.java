// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCodecRegression;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;

/**
 * One standalone, no-world regression: actual UI routing, background client library, actual file publication and cold
 * open.
 */
public final class HologramLibraryNativeConversionRegression {

    private static final String A = id(1), B = id(2), C = id(3), N = id(4), BAD = id(5);

    private static String id(int n) {
        return "local_" + new UUID(33, n);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static final class Client extends Minecraft {

        Queue<ListenableFutureTask<?>> tasks;

        private Client() {
            super(null, 1, 1, false, false, null, null, null, null, "fixture", null, "fixture");
            throw new AssertionError();
        }

        @Override
        public ListenableFuture func_152344_a(Runnable r) {
            ListenableFutureTask<?> f = ListenableFutureTask.create(r, null);
            tasks.add(f);
            return f;
        }

        void until(BooleanSupplier ready) throws Exception {
            long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (true) {
                ListenableFutureTask<?> f;
                while ((f = tasks.poll()) != null) {
                    f.run();
                    f.get();
                }
                if (ready.getAsBoolean()) return;
                if (System.nanoTime() > end)
                    throw new AssertionError("Timed out waiting for conversion/library refresh");
                Thread.sleep(5);
            }
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static void check(boolean b, String detail) {
        if (!b) throw new AssertionError(detail);
    }

    private static Path put(HologramClientLibrary store, String id, String block) throws Exception {
        Path source = store.directory()
            .resolve(id + ".json");
        byte[] json = ("{\"schema\":2,\"blueprint\":{\"id\":\"" + id
            + "\",\"name\":\"same title\",\"authorName\":\"Fixture\",\"authorUuid\":\"00000000-0000-0000-0000-000000000001\",\"cells\":[{\"x\":-2,\"y\":1,\"z\":3,\"block\":\""
            + block
            + "\",\"metadata\":0,\"item\":\""
            + block
            + "\",\"damage\":0,\"count\":1}]}}").getBytes(StandardCharsets.UTF_8);
        HologramBlueprintArtifact.writePair(source, json, NativeBlueprintCodecRegression.png());
        return source;
    }

    private static boolean hasRow(HologramLibraryClient lib, String id) {
        var rows = lib.rows(false);
        for (int i = 0; i < rows.tagCount(); i++) if (id.equals(
            rows.getCompoundTagAt(i)
                .getString("id")))
            return true;
        return false;
    }

    private static NBTTagCompound row(HologramLibraryClient lib, String id) {
        var rows = lib.rows(false);
        for (int i = 0; i < rows.tagCount(); i++) if (id.equals(
            rows.getCompoundTagAt(i)
                .getString("id")))
            return rows.getCompoundTagAt(i);
        throw new AssertionError("missing row " + id);
    }

    private static void idle(Client mc, HologramLibraryClient lib) throws Exception {
        Field loading = field(HologramLibraryClient.class, "loading");
        mc.until(() -> {
            try {
                return !lib.busy() && !loading.getBoolean(lib);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("gtmb-library-native-ui-");
        var unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        Field singleton = Arrays.stream(Minecraft.class.getDeclaredFields())
            .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == Minecraft.class)
            .findFirst()
            .get();
        singleton.setAccessible(true);
        Object previous = singleton.get(null);
        try {
            var mc = (Client) unsafe.allocateInstance(Client.class);
            mc.tasks = new ConcurrentLinkedQueue<>();
            singleton.set(null, mc);
            var owner = HologramLibraryClient.INSTANCE;
            var store = new HologramClientLibrary(root);
            Files.createDirectories(store.directory());
            field(HologramLibraryClient.class, "store").set(owner, store);
            var screen = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
            var ui = new HologramUiModules(screen);
            screen.ui = ui;
            field(HologramUiSession.class, "state").set(ui.session, new HologramState(new NBTTagCompound()));
            var txt = (HologramMuiTextField) unsafe.allocateInstance(HologramMuiTextField.class);
            field(HologramMuiTextField.class, "value").set(txt, new com.cleanroommc.modularui.value.StringValue(B));
            field(HologramUiLibrary.class, "presetNameField").set(ui.library, txt);
            Path original = put(store, A, "minecraft:stone"), other = put(store, B, "minecraft:dirt");
            Path portable = store.directory()
                .resolve("Unindexed Same Title.json");
            Files
                .move(HologramBlueprintArtifact.previewPath(original), HologramBlueprintArtifact.previewPath(portable));
            Files.move(original, portable);
            original = portable;
            put(store, C, "minecraft:stone");
            put(store, N, "minecraft:stone");
            store.commitSelectedNativeUpgrade(N, () -> true);
            var nativeBefore = NativeBlueprintPairStore.receipt(store.directory(), N);
            // Paired and visible, but the sidecar no longer belongs to BAD. The failure must retain its original.
            Path invalid = put(store, BAD, "minecraft:stone");
            byte[] goodCover = Files.readAllBytes(HologramBlueprintArtifact.previewPath(invalid));
            Files.write(HologramBlueprintArtifact.previewPath(invalid), new byte[] { 1, 2, 3 });
            // Same shape as the real library: five missing legacy index targets; one stale alias is
            // shadowed by a valid native receipt for N. No user assets or game process involved.
            Path index = store.directory()
                .resolve(".gtmb-blueprint-files.properties");
            java.util.Properties mappings = new java.util.Properties();
            if (Files.exists(index)) try (var input = Files.newInputStream(index)) {
                mappings.load(input);
            }
            for (int i = 20; i < 25; i++) mappings.setProperty(id(i), "missing-" + i + ".json");
            mappings.setProperty(N, "removed-original-native-N.json");
            try (var out = Files.newOutputStream(index)) {
                mappings.store(out, "fixture");
            }
            byte[] initialIndex = Files.readAllBytes(index);
            byte[] old = Files.readAllBytes(original),
                png = Files.readAllBytes(HologramBlueprintArtifact.previewPath(original)),
                otherOld = Files.readAllBytes(other);
            Path excluded = store.directory()
                .resolve("public_00000000-0000-0000-0000-000000000001.json"),
                unpaired = store.directory()
                    .resolve("unpaired.json");
            Files.copy(original, excluded);
            Files
                .copy(HologramBlueprintArtifact.previewPath(original), HologramBlueprintArtifact.previewPath(excluded));
            Files.copy(original, unpaired);
            owner.refreshLocal();
            idle(mc, owner);
            var adminHeader = new NBTTagCompound();
            adminHeader.setString("id", "admin");
            check(!ui.library.conversionRequired(adminHeader), "an administrator control is not a blueprint card");
            owner.select(false, B);
            ui.library.nativeBindRow(0, row(owner, A));
            ui.router.activate(14000);
            check(
                ui.library.conversionPromptOpen() && ui.library.conversionRequired(row(owner, A))
                    && !ui.library.conversionRequired(row(owner, N)),
                "legacy intercepted; native rows unchanged");
            check(
                owner.selected(false)
                    .equals(B) && !owner.busy()
                    && !ui.library.openingLocalSource()
                    && !ui.library.browsePreviewActive(),
                "a gray-row click must not select, decode, render or start a task");
            ui.library.cancelConversionPrompt();
            check(
                !ui.library.conversionPromptOpen() && NativeBlueprintPairStore.receipt(store.directory(), A) == null,
                "cancel writes nothing");
            ui.router.activate(14000);
            ui.library.confirmConversionPrompt(false);
            idle(mc, owner);
            check(
                row(owner, A).getBoolean("nativeFile")
                    && NativeBlueprintPairStore.receipt(store.directory(), B) == null,
                "clicked A wins over name field/selection B; refreshed card is native");
            check(
                com.miaokatze.gtmb.hologram.HologramBlueprintFiles.indexedPath(store.directory(), B) == null,
                "single conversion did not adopt or alter the unrelated portable B alias");
            owner.select(false, A);
            ui.library.nativeConvertSelected();
            check(!owner.busy(), "native re-upgrade is a no-op");
            try (var d = new HologramClientLibrary(root).readPrivateEditorSource(A)
                .openNative()) {
                check(d.cells() == 1 && d.elementAt(-2, 1, 3) > 0, "cold editor opens upgraded selected geometry");
            }
            check(
                Arrays.equals(old, Files.readAllBytes(original))
                    && Arrays.equals(png, Files.readAllBytes(HologramBlueprintArtifact.previewPath(original))),
                "source pair byte-identical");
            ui.library.nativeBindRow(0, row(owner, B));
            ui.router.activate(14000);
            ui.library.confirmConversionPrompt(true);
            idle(mc, owner);
            check(
                row(owner, B).getBoolean("nativeFile") && row(owner, C).getBoolean("nativeFile")
                    && !row(owner, BAD).getBoolean("nativeFile"),
                "all converts B+C, retains failed source");
            check(
                owner.upgradeDetails()
                    .size() == 1 && owner.upgradeDetails()
                        .get(0)
                        .contains(BAD),
                "only the genuinely bad pair fails, not the five stale mappings");
            check(
                HologramBackgroundTasks.status()
                    .contains("部分完成") && !HologramBackgroundTasks.succeeded(),
                "true mixed publication is shown as partial, not all failed or all successful");
            check(
                NativeBlueprintPairStore
                    .sameReceipt(nativeBefore, NativeBlueprintPairStore.receipt(store.directory(), N))
                    && Arrays.equals(otherOld, Files.readAllBytes(other)),
                "already native skipped, original B retained");
            Path next = put(store, id(6), "minecraft:stone");
            owner.refreshLocal();
            idle(mc, owner);
            ui.library.activateControl(226);
            idle(mc, owner);
            check(
                row(owner, id(6)).getBoolean("nativeFile") && Files.isRegularFile(next),
                "library-wide control uses same whole-library converter");
            check(
                Files.exists(excluded) && Arrays.equals(old, Files.readAllBytes(excluded))
                    && Arrays.equals(old, Files.readAllBytes(unpaired))
                    && Arrays.equals(
                        new byte[] { 1, 2, 3 },
                        Files.readAllBytes(HologramBlueprintArtifact.previewPath(invalid))),
                "public, unpaired, failed original content not rewritten");
            // Retry with just a genuine failure; then repair the synthetic sidecar and retry through the same header.
            ui.library.activateControl(226);
            idle(mc, owner);
            check(
                !HologramBackgroundTasks.succeeded() && HologramBackgroundTasks.status()
                    .contains("失败")
                    && !HologramBackgroundTasks.status()
                        .contains("部分完成")
                    && owner.upgradeDetails()
                        .size() == 1,
                "all newly attempted work failed, no bogus partial/success");
            Files.write(HologramBlueprintArtifact.previewPath(invalid), goodCover);
            ui.library.activateControl(226);
            idle(mc, owner);
            check(
                HologramBackgroundTasks.succeeded() && owner.upgradeDetails()
                    .isEmpty() && row(owner, BAD).getBoolean("nativeFile"),
                "after actual repair, only stale legacy aliases remain; normal status is success and card refreshes");
            Path nativeIndex = store.directory()
                .resolve(".gtmb-native-catalog.properties");
            var time = Files.getLastModifiedTime(nativeIndex);
            byte[] catalog = Files.readAllBytes(nativeIndex);
            ui.library.activateControl(226);
            idle(mc, owner);
            var report = new HologramClientLibrary.ConversionReport();
            store.convertAllLocal(() -> true, (i, n) -> {}, report);
            check(
                report.stale == 5 && report.skipped == 6
                    && report.requested == 11
                    && report.validated == 0
                    && report.converted == 0
                    && report.problems.isEmpty()
                    && HologramBackgroundTasks.succeeded()
                    && time.equals(Files.getLastModifiedTime(nativeIndex))
                    && Arrays.equals(catalog, Files.readAllBytes(nativeIndex))
                    && Arrays.equals(initialIndex, Files.readAllBytes(index))
                    && NativeBlueprintPairStore
                        .sameReceipt(nativeBefore, NativeBlueprintPairStore.receipt(store.directory(), N))
                    && row(owner, N).getBoolean("nativeFile"),
                "repeated bulk skip only; native beats shadowed stale mapping, neither index rewritten");
            // A legacy mapping is stale only when neither side exists. A remaining PNG is evidence,
            // not garbage. Do not lose it or include its absolute location in any completion message.
            Path orphanCover = store.directory()
                .resolve("missing-20.png");
            Files.write(orphanCover, goodCover);
            ui.library.activateControl(226);
            idle(mc, owner);
            check(
                !HologramBackgroundTasks.succeeded() && owner.upgradeDetails()
                    .size() == 1
                    && owner.upgradeDetails()
                        .get(0)
                        .contains("missing-20.json")
                    && !HologramBackgroundTasks.status()
                        .contains("missing-20")
                    && !owner.upgradeDetails()
                        .get(0)
                        .contains(root.toString())
                    && Arrays.equals(goodCover, Files.readAllBytes(orphanCover)),
                "orphan cover is a real error, safe detailed identity, intact file");
            check(
                !HologramClientLibrary.conversionReason(new java.nio.file.AccessDeniedException(orphanCover.toString()))
                    .contains(root.toString())
                    && !HologramClientLibrary.conversionReason(new java.io.IOException("读取失败：" + orphanCover))
                        .contains(root.toString()),
                "OS path never copied into error message");
            Files.delete(orphanCover);
            // Absence of an active native source is real corruption, unlike the five historical missing JSON pointers.
            Files.delete(nativeBefore.source);
            ui.library.activateControl(226);
            idle(mc, owner);
            check(
                !HologramBackgroundTasks.succeeded() && owner.upgradeDetails()
                    .size() == 1
                    && owner.upgradeDetails()
                        .get(0)
                        .contains(
                            nativeBefore.source.getFileName()
                                .toString()),
                "broken native receipt is not silently treated as a stale legacy pointer");
            // Same-name, same-ID archive with real content differences must remain visible yet not send
            // the player back to the dead upgrade dialog. An identical mirror and unrelated name are distinct cases.
            String historicB = "legacy:" + B.substring("local_".length());
            String historicA = "legacy:" + A.substring("local_".length());
            String unrelated = id(45).substring("local_".length());
            var fixtureJson = new com.google.gson.JsonParser();
            var same = fixtureJson.parse(new String(old, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonObject("blueprint");
            same.addProperty("id", A.substring("local_".length()));
            var different = fixtureJson.parse(new String(otherOld, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonObject("blueprint");
            different.addProperty("id", B.substring("local_".length()));
            different.getAsJsonArray("cells")
                .get(0)
                .getAsJsonObject()
                .addProperty("block", "minecraft:glass");
            var namesake = fixtureJson.parse(different.toString())
                .getAsJsonObject();
            namesake.addProperty("id", unrelated);
            byte[] archives = ("{\"presets\":[" + same + "," + different + "," + namesake + "]}")
                .getBytes(StandardCharsets.UTF_8);
            Path history = store.legacyCatalogPath();
            Files.write(history, archives);
            owner.refreshLocal();
            idle(mc, owner);
            var clicked = (NBTTagCompound) row(owner, historicB).copy();
            check(
                !hasRow(owner, historicA) && row(owner, A).getBoolean("nativeFile"),
                "identical mirror remains one native card");
            check(
                B.equals(clicked.getString("nativeCounterpart")) && !ui.library.conversionRequired(clicked)
                    && ui.library.conversionRequired(row(owner, "legacy:" + unrelated))
                    && row(owner, "legacy:" + unrelated).getString("nativeCounterpart")
                        .isEmpty(),
                "no repeated upgrade at the same local ID, no title-based redirect");
            String details = new String(store.readLegacyDocument(historicB), StandardCharsets.UTF_8);
            check(
                details.contains("minecraft:glass") && Arrays.equals(archives, Files.readAllBytes(history)),
                "different archive's exact contents are preserved");
            var dirty = new NBTTagCompound();
            dirty.setBoolean("blueprintDirty", true);
            field(HologramUiSession.class, "state").set(ui.session, new HologramState(dirty));
            owner.select(false, A);
            ui.library.nativeBindRow(0, clicked);
            ui.router.activate(14000);
            check(
                ui.library.discardReview() && !ui.library.conversionPromptOpen()
                    && owner.selected(false)
                        .equals(A),
                "formal row click goes to the existing unsaved-editor guard, never to upgrade");
            check(
                B.equals(field(HologramUiLibrary.class, "deferredLibraryTargetId").get(ui.library)),
                "accepted row switch is pinned to the actual native receipt ID");
            ui.library.cancelSwitchReview(225);
            field(HologramUiSession.class, "state").set(ui.session, new HologramState(new NBTTagCompound()));
            field(HologramUiLibrary.class, "libraryDrawer").setBoolean(ui.library, true);
            ui.library.nativeBindRow(0, clicked);
            ui.router.activate(14000);
            check(
                !ui.library.conversionPromptOpen() && owner.selected(false)
                    .equals(B),
                "clean workspace formal click opens the existing native card by stable ID");
            var readyBefore = NativeBlueprintPairStore.receipt(store.directory(), B);
            try (var nativeOpened = store.readPrivateEditorSource(owner.selected(false))
                .openNative()) {
                check(
                    nativeOpened.cells() == 1 && nativeOpened.elementAt(-2, 1, 3) > 0,
                    "the routed ID cold-opens the actual preserved native pair");
            }
            check(
                NativeBlueprintPairStore
                    .sameReceipt(readyBefore, NativeBlueprintPairStore.receipt(store.directory(), B))
                    && Arrays.equals(archives, Files.readAllBytes(history)),
                "opening never reruns conversion or mutates the archive");
            byte[] imageB = Files.readAllBytes(readyBefore.png);
            Files.delete(readyBefore.png);
            owner.refreshLocal();
            idle(mc, owner);
            check(
                row(owner, historicB).getString("nativeCounterpart")
                    .isEmpty(),
                "missing native cover invalidates row redirect");
            ui.library.nativeBindRow(0, clicked);
            ui.router.activate(14000);
            check(
                ui.library.conversionPromptOpen() && !ui.library.promptCanConvert(),
                "stale click does not redirect to a now-missing pair or upgrade an unpaired archive");
            ui.library.cancelConversionPrompt();
            Files.write(readyBefore.png, imageB);
            System.out.println(
                "PASS packed-library identity, different archive retention, native route/discard and stale row handling.");
            System.out.println(
                "PASS stale index classification, precise success/partial/failure, preserved active native precedence and idempotent retries.");
            System.out.println(
                "PASS native library settings: gray row/no open, cancel, clicked-ID vs typed-ID, native no-op, cold open + unchanged originals, all legacy + partial failure, whole-library action.");
        } finally {
            singleton.set(null, previous);
            try (var files = Files.walk(root)) {
                for (Path f : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(f);
            }
        }
    }
}
