package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.miaokatze.gtmb.blueprint.editor.AuthorDocument;
import com.miaokatze.gtmb.blueprint.editor.AuthorDocumentIO;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;
import com.miaokatze.gtmb.testutil.SimpleAssert;

/** N01: the real name field/save command must not silently rename a complete local source. */
public final class HologramAuthorSaveNameRegression {

    private HologramAuthorSaveNameRegression() {}

    public static void main(String[] args) throws Exception {
        if (!MinecraftTestBootstrap.enter(HologramAuthorSaveNameRegression.class, args)) return;
        run();
        System.out.println("HologramAuthorSaveNameRegression PASS");
    }

    public static void run() throws Exception {
        SimpleAssert.eq("original", HologramUiLibrary.authorSaveName(null, "original"), "missing field is not rename");
        SimpleAssert.eq("original", HologramUiLibrary.authorSaveName("  ", "original"), "empty field is not rename");
        SimpleAssert
            .eq(" padded ", HologramUiLibrary.authorSaveName(" padded ", " padded "), "unchanged title is exact");
        SimpleAssert.eq("renamed", HologramUiLibrary.authorSaveName(" renamed ", "original"), "explicit rename trims");
        SimpleAssert.eq("", HologramUiLibrary.authorSaveName("", ""), "unnamed source is not implicitly edited");

        Path game = Files.createTempDirectory("gtmb-save-name-");
        HologramLibraryClient client = HologramLibraryClient.INSTANCE;
        Field workerField = field(HologramLibraryClient.class, "worker");
        Field storeField = field(HologramLibraryClient.class, "store");
        Field loadingField = field(HologramLibraryClient.class, "loading");
        Object previousWorker = workerField.get(client), previousStore = storeField.get(client);
        boolean previousLoading = loadingField.getBoolean(client);
        HologramUiTabMemory previousTabMemory = HologramUiMemory.tabMemory();
        Field minecraftField = field(net.minecraft.client.Minecraft.class, "theMinecraft");
        Object previousMinecraft = minecraftField.get(null);
        ControlledWorker worker = new ControlledWorker();
        HologramLocalAuthorRuntime runtime = runtime();
        try {
            minecraftField.set(null, headlessTextMetrics(game));
            HologramClientLibrary store = new HologramClientLibrary(game);
            workerField.set(client, worker);
            storeField.set(client, store);
            // Completion is exercised directly, without scheduling an unrelated catalog refresh.
            loadingField.setBoolean(client, true);
            HologramUiModules ui = headlessUi(runtime);
            runtime.attach(ui.session);
            ui.library.initializeFields();
            AuthorDocument original = runtime.document();
            String title = original.title();
            SimpleAssert.eq(
                title,
                ui.library.presetNameField()
                    .getText(),
                "actual save field initializes from source");
            assertTitles(runtime, title);

            submit(ui);
            SimpleAssert
                .that(runtime.saving(), "save command dispatched one persistence task to the controlled worker");
            SimpleAssert
                .that(runtime.document() == original, "untouched save creates no rename revision or source root");
            SimpleAssert.eq(1, worker.submissions, "one persistence operation");
            byte[] bytes = AuthorDocumentIO.writeDraft(runtime.document(), AuthorDocumentIO.Limits.defaults());
            Path file = store.saveAuthorDraft(original.documentId(), bytes);
            AuthorDocument recovered = AuthorDocumentIO
                .read(Files.readAllBytes(file), AuthorDocumentIO.Limits.defaults());
            SimpleAssert.eq(title, recovered.title(), "recovery file retains source name");
            SimpleAssert.eq(original.revision(), recovered.revision(), "recovery file retains revision");
            SimpleAssert
                .eq(original.sourceCopy(), recovered.sourceCopy(), "recovery preserves all opaque source fields");
            SimpleAssert.eq(original.documentId(), recovered.documentId(), "save keeps document identity");
            runtime.saved(original, file.toString(), null);
            SimpleAssert
                .that(!runtime.saving() && !runtime.dirty(), "successful completion marks exact revision saved");

            ui.library.presetNameField()
                .setText("");
            submit(ui);
            SimpleAssert.that(runtime.document() == original, "cleared field cannot rename the source to unnamed");
            runtime.saved(original, "", "simulated disk failure");
            SimpleAssert.that(!runtime.saving() && !runtime.dirty(), "failed retry does not discard prior saved state");
            SimpleAssert.eq(
                file.toString(),
                runtime.view().data.getString("localDraftPath"),
                "failed retry retains saved path");
            ui.library.presetNameField()
                .setText(title);

            HologramDraftNameMemory memory = new HologramDraftNameMemory(runtime.view().data, "typed draft name");
            ui.library.presetNameField()
                .setText("  explicit rename  ");
            submit(ui);
            AuthorDocument renamed = runtime.document();
            SimpleAssert.eq("explicit rename", renamed.title(), "actual save honors explicit rename");
            SimpleAssert.eq(
                "explicit rename",
                ui.library.presetNameField()
                    .getText(),
                "save normalizes explicit field before dispatch");
            SimpleAssert
                .eq(original.revision() + 1, renamed.revision(), "explicit rename creates exactly one revision");
            SimpleAssert.eq(
                "explicit rename",
                memory.restore(runtime.view().data),
                "old memory follows changed canonical title");
            assertTitles(runtime, "explicit rename");
            runtime.saved(renamed, "", "simulated disk failure");
            SimpleAssert.that(runtime.dirty() && !runtime.saving(), "failed renamed save stays dirty and retryable");
            SimpleAssert.eq(
                "explicit rename",
                ui.library.presetNameField()
                    .getText(),
                "failed save preserves typed rename");

            runtime.undo();
            SimpleAssert.eq(
                title,
                ui.library.presetNameField()
                    .getText(),
                "undo synchronizes untouched save name field");
            assertTitles(runtime, title);
            long undoneRevision = runtime.document()
                .revision();
            worker.reject = true;
            submit(ui);
            SimpleAssert.eq(
                title,
                runtime.document()
                    .title(),
                "save after undo cannot reapply old name field");
            SimpleAssert.eq(
                undoneRevision,
                runtime.document()
                    .revision(),
                "save after undo adds no rename revision");
            SimpleAssert.that(!runtime.saving(), "worker rejection releases pending save state");

            runtime.rename("programmatic rename");
            SimpleAssert.eq(
                "programmatic rename",
                ui.library.presetNameField()
                    .getText(),
                "open field follows canonical rename");
            ui.library.presetNameField()
                .setText("in-progress input");
            runtime.rename("another canonical name");
            SimpleAssert.eq(
                "in-progress input",
                ui.library.presetNameField()
                    .getText(),
                "canonical update preserves edited input");
            ui.session.localDocumentChanged(runtime.view());
            SimpleAssert.eq(
                "in-progress input",
                ui.library.presetNameField()
                    .getText(),
                "no-op title publication preserves typed input");
            HologramDraftNameMemory typed = new HologramDraftNameMemory(runtime.view().data, "in-progress input");
            SimpleAssert.eq(
                "in-progress input",
                typed.restore(runtime.view().data),
                "same-source reopen preserves typed input");
            ui.library.initializeFields();
            SimpleAssert.eq(
                "another canonical name",
                ui.library.presetNameField()
                    .getText(),
                "fresh field uses latest canonical title");

            String longTitle = new String(new char[300]).replace('\0', '名');
            runtime.rename(longTitle);
            SimpleAssert.eq(
                longTitle,
                ui.library.presetNameField()
                    .getText(),
                "canonical sync does not truncate long imported title");
            ui.library.initializeFields();
            SimpleAssert.eq(
                longTitle,
                ui.library.presetNameField()
                    .getText(),
                "initial field retains full long canonical title");
            AuthorDocument longNamed = runtime.document();
            submit(ui);
            SimpleAssert
                .that(longNamed == runtime.document(), "saving long existing title creates no truncated rename");
            assertTitles(runtime, longTitle);

            ui.library.presetNameField()
                .setText("short saved name");
            worker.reject = false;
            submit(ui);
            AuthorDocument shortNamed = runtime.document();
            Path shortFile = store.saveAuthorDraft(
                shortNamed.documentId(),
                AuthorDocumentIO.writeDraft(shortNamed, AuthorDocumentIO.Limits.defaults()));
            runtime.saved(shortNamed, shortFile.toString(), null);
            runtime.undo();
            SimpleAssert.eq(
                longTitle,
                ui.library.presetNameField()
                    .getText(),
                "undo saved short rename restores long canonical field");
            String longInput = longTitle.substring(0, longTitle.length() - 1) + "稿";
            ui.library.presetNameField()
                .setText(longInput);
            runtime.redo();
            SimpleAssert.eq(
                "short saved name",
                runtime.document()
                    .title(),
                "redo returns to short canonical title");
            SimpleAssert.eq(
                longInput,
                ui.library.presetNameField()
                    .getText(),
                "redo retains long in-progress input");
            HologramUiMemory.tabMemory(
                new HologramUiTabMemory(
                    game.resolve("ui-memory.properties")
                        .toFile()));
            ui.memory.rememberUi();
            SimpleAssert.eq(
                longInput,
                HologramUiMemory.UI_MEMORY()
                    .get(runtime.view().session).draftName.restore(runtime.view().data),
                "actual UI memory captures complete long input against short canonical title");
            ui.library.initializeFields();
            SimpleAssert.eq(
                longInput,
                ui.library.presetNameField()
                    .getText(),
                "screen reinitialization restores long typed input without truncation");
            worker.reject = false;
            submit(ui);
            AuthorDocument restoredInput = runtime.document();
            SimpleAssert.eq(longInput, restoredInput.title(), "save after reopening uses exact restored input");
            Path restoredFile = store.saveAuthorDraft(
                restoredInput.documentId(),
                AuthorDocumentIO.writeDraft(restoredInput, AuthorDocumentIO.Limits.defaults()));
            AuthorDocument restoredSaved = AuthorDocumentIO
                .read(Files.readAllBytes(restoredFile), AuthorDocumentIO.Limits.defaults());
            SimpleAssert.eq(longInput, restoredSaved.title(), "disk roundtrip preserves all restored input characters");
            runtime.saved(restoredInput, restoredFile.toString(), null);
            SimpleAssert.that(!runtime.dirty() && !runtime.saving(), "restored long name save completes cleanly");
        } finally {
            minecraftField.set(null, previousMinecraft);
            workerField.set(client, previousWorker);
            storeField.set(client, previousStore);
            loadingField.setBoolean(client, previousLoading);
            HologramUiMemory.tabMemory(previousTabMemory);
            worker.shutdownNow();
            ((HologramAuthorProjection) field(HologramLocalAuthorRuntime.class, "projection").get(runtime)).close();
            HologramUiMemory.UI_MEMORY()
                .remove(
                    runtime.document()
                        .documentId());
            try (java.util.stream.Stream<Path> paths = Files.walk(game)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static void assertTitles(HologramLocalAuthorRuntime runtime, String title) {
        for (String key : new String[] { "virtualPresetName", "blueprintName", "title", "presetName" })
            SimpleAssert.eq(title, runtime.view().data.getString(key), "canonical title metadata: " + key);
    }

    private static void submit(HologramUiModules ui) throws Exception {
        Method submit = HologramUiLibrary.class.getDeclaredMethod("submitBlueprintSave", boolean.class);
        submit.setAccessible(true);
        submit.invoke(ui.library, true);
    }

    private static HologramLocalAuthorRuntime runtime() throws Exception {
        AuthorDocument document = AuthorDocumentIO.read(
            ("{\"blueprint\":{\"schema\":2,\"name\":\"合成名称保真测试\",\"future\":{\"keep\":7},"
                + "\"cells\":[{\"x\":1,\"y\":2,\"z\":3,\"block\":\"missing:block\",\"tileNbt\":\"opaque-original\"}]}}")
                    .getBytes(StandardCharsets.UTF_8),
            AuthorDocumentIO.Limits.defaults());
        java.lang.reflect.Constructor<HologramLocalAuthorRuntime> constructor = HologramLocalAuthorRuntime.class
            .getDeclaredConstructor(AuthorDocument.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(document, false);
    }

    /**
     * MUI cursor placement uses its simulate-only text measurement path even before a panel is mounted.
     * Supply real vanilla measurement code with synthetic metrics, not a game/GL renderer or a field mock.
     */
    private static net.minecraft.client.Minecraft headlessTextMetrics(Path game) throws Exception {
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        net.minecraft.client.Minecraft minecraft = (net.minecraft.client.Minecraft) unsafe
            .allocateInstance(net.minecraft.client.Minecraft.class);
        net.minecraft.client.gui.FontRenderer font = (net.minecraft.client.gui.FontRenderer) unsafe
            .allocateInstance(net.minecraft.client.gui.FontRenderer.class);
        int[] characterWidths = new int[256];
        byte[] glyphWidths = new byte[65536];
        java.util.Arrays.fill(characterWidths, 6);
        java.util.Arrays.fill(glyphWidths, (byte) 7);
        field(net.minecraft.client.gui.FontRenderer.class, "charWidth").set(font, characterWidths);
        field(net.minecraft.client.gui.FontRenderer.class, "glyphWidth").set(font, glyphWidths);
        font.FONT_HEIGHT = 9;
        minecraft.fontRenderer = font;
        field(net.minecraft.client.Minecraft.class, "mcDataDir").set(minecraft, game.toFile());
        return minecraft;
    }

    private static HologramUiModules headlessUi(HologramLocalAuthorRuntime runtime) throws Exception {
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        HologramScreen parent = (HologramScreen) unsafe.allocateInstance(HologramScreen.class);
        field(HologramMuiCanvasScreen.class, "fields").set(parent, new ArrayList<HologramMuiTextField>());
        HologramUiHost host = (HologramUiHost) Proxy.newProxyInstance(
            HologramUiHost.class.getClassLoader(),
            new Class<?>[] { HologramUiHost.class },
            (proxy, method, args) -> {
                if ("parent".equals(method.getName())) return parent;
                throw new AssertionError("name regression must not access Minecraft or rendering: " + method.getName());
            });
        HologramUiModules ui = new HologramUiModules(host);
        field(HologramUiSession.class, "author").set(ui.session, runtime);
        field(HologramUiSession.class, "state").set(ui.session, runtime.view());
        return ui;
    }

    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Capture dispatch without running a Minecraft main-thread callback; explicit completion stays deterministic. */
    private static final class ControlledWorker extends ThreadPoolExecutor {

        int submissions;
        boolean reject;

        ControlledWorker() {
            super(0, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>());
        }

        @Override
        public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("simulated busy worker");
            submissions++;
        }
    }
}
