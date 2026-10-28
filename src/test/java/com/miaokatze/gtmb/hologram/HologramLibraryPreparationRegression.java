package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.testutil.SimpleAssert;

/** Actual admitted backend upload, worker callback and owner polling, with a read-only world and packet sink. */
final class HologramLibraryPreparationRegression {

    static void run() throws Exception {
        ownerAdmissionBoundaries();
        try (Fixture f = new Fixture()) {
            f.begin(false, false);
            f.actor.inventory.currentItem = 1;
            f.store.heartbeat();
            Object peer = f.peer();
            SimpleAssert.that(
                (Boolean) get(get(peer, "upload"), "valid"),
                "accepted private upload survives temporary held-slot change");
            f.upload();
            SimpleAssert.that(
                get(peer, "upload") == null,
                "builtin upload keeps its existing IO canonical fast path before activation callback");
            SimpleAssert.eq(
                0,
                (Integer) get(f.store, "activeUploads"),
                "builtin upload adds no pending owner canonicalization slot");
            call(HologramWorkspaceScheduler.class, "tickSession", f.session);
            SimpleAssert
                .that(f.session.libraryUiActive, "worker-to-callback gap cannot close the admitted editor capability");
            f.serverCallbacks();
            SimpleAssert.eq(0, f.starts, "worker result waits for held-tool activation admission");
            SimpleAssert.that(
                get(f.result, "prepared") != null && f.replies.isEmpty(),
                "prepared owned DTO has no premature terminal receipt");
            f.actor.inventory.currentItem = 0;
            f.poll();
            SimpleAssert.eq(1, f.starts, "returning original tool activates the real worker result once");
            SimpleAssert.eq(
                "privateImported",
                f.replies.get(0)
                    .getString("op"),
                "only completed activation is acknowledged");
            SimpleAssert.that(get(f.result, "prepared") == null, "completed result releases its DTO closure");
            f.poll();
            SimpleAssert.eq(1, f.replies.size(), "completion is terminal exactly once");
        }
        try (Fixture f = new Fixture()) {
            f.begin(true, false);
            f.actor.inventory.currentItem = 1;
            f.serverCallbacks();
            SimpleAssert.that(
                f.replies.isEmpty() && f.starts == 0 && get(f.result, "prepared") != null,
                "publicPrepare without any upload retains its original owner across callback delay");
            f.actor.inventory.currentItem = 0;
            f.poll();
            SimpleAssert.eq(
                "publicPrepared",
                f.replies.get(0)
                    .getString("op"),
                "public preparation resumes its original terminal identity");
        }
        try (Fixture f = new Fixture()) {
            f.begin(false, true);
            f.actor.inventory.currentItem = 1;
            f.store.heartbeat();
            f.upload();
            f.poll();
            SimpleAssert.eq(
                "error",
                f.replies.get(0)
                    .getString("op"),
                "malformed native artifact still fails after valid held-tool admission");
            f.poll();
            SimpleAssert.eq(1, f.replies.size(), "backend decode error and owner poll cannot emit two terminals");
            SimpleAssert.that(
                get(f.result, "prepared") == null && f.starts == 0,
                "failed decode retains no ready DTO and never replaces editor");
        }
        for (String fence : new String[] { "remove", "replace", "revision", "disconnect" })
            try (Fixture f = new Fixture()) {
                f.begin(false, false);
                f.actor.inventory.currentItem = 1;
                f.upload();
                f.serverCallbacks();
                if ("remove".equals(fence)) f.actor.inventory.mainInventory[0] = null;
                if ("replace".equals(fence)) f.actor.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
                if ("revision".equals(fence)) f.session.planRevision++;
                if ("disconnect".equals(fence)) f.actor.playerNetServerHandler.netManager.channel()
                    .close();
                f.poll();
                f.poll();
                SimpleAssert.eq(1, f.replies.size(), "revoked admitted preparation terminates once: " + fence);
                SimpleAssert.eq(
                    "error",
                    f.replies.get(0)
                        .getString("op"),
                    "revoked preparation cannot activate: " + fence);
                SimpleAssert.that(
                    get(f.result, "prepared") == null && f.starts == 0
                        && HologramWorkspace.find(f.actor.getUniqueID()) == f.session,
                    "revocation drops DTO and preserves old route: " + fence);
            }
        try (Fixture f = new Fixture()) {
            f.request.setBoolean("admittedOnly", true);
            f.request.setBoolean("hashProbe", true);
            f.request.setString("jsonHash", repeat('a', 64));
            f.begin(false, false);
            f.poll();
            SimpleAssert.that(
                f.result.terminal() && f.replies.isEmpty(),
                "admitted-only cache miss retires as contentMissing without an extra activation error");
            SimpleAssert.that(get(f.peer(), "upload") == null, "probe miss reserves no upload");
            f.request.setLong("clientTaskToken", 102);
            f.request.setString(
                "transfer",
                UUID.randomUUID()
                    .toString());
            SimpleAssert.that(
                HologramLibraryFacade.beginActivation(f.actor, f.request, f.store),
                "a real successor token is not blocked by the retired probe");
        }
        for (String fence : new String[] { "held", "remove", "replace", "world", "dimension", "disconnect" })
            try (Fixture f = new Fixture()) {
                ((Map<?, ?>) get(HologramWorkspace.class, "SESSIONS")).remove(f.actor.getUniqueID());
                f.request.removeTag("session");
                f.request.removeTag("planRevision");
                f.request.removeTag("action");
                f.request.setBoolean("contentOnly", true);
                f.request.setString(
                    "jsonHash",
                    HologramBlueprintContentHash.of(HologramServerLibrary.encodeShare(preset())));
                f.request.setInteger("total", f.bytes.length);
                f.request.setString("hash", HologramBlueprintArtifact.hash(f.bytes));
                f.store.submit(f.actor, f.request, false);
                f.worker(() -> {});
                f.worker(() -> {});
                Object peer = f.peer();
                SimpleAssert.that(
                    get(peer, "upload") != null && (Boolean) get(peer, "contentValid"),
                    "contextless upload captures actual admitted owner without a workspace");
                f.actor.inventory.currentItem = 1;
                if ("remove".equals(fence)) f.actor.inventory.mainInventory[0] = null;
                if ("replace".equals(fence)) f.actor.inventory.mainInventory[0] = new ItemStack(Blocks.stone);
                if ("world".equals(fence)) f.actor.worldObj = null;
                if ("dimension".equals(fence)) f.actor.dimension++;
                if ("disconnect".equals(fence)) f.actor.playerNetServerHandler.netManager.channel()
                    .close();
                f.store.heartbeat();
                if ("held".equals(fence)) {
                    SimpleAssert.that(
                        (Boolean) get(peer, "contentValid") && (Boolean) get(get(peer, "upload"), "valid"),
                        "pure content upload survives temporary held-slot change");
                    f.upload();
                    f.serverCallbacks();
                    SimpleAssert.that(
                        !(Boolean) get(peer, "contentValid") && get(peer, "contentWorld") == null
                            && get(peer, "contentTool") == null,
                        "successful content ready releases tool/world ownership");
                    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
                    SimpleAssert.that(f.store.enqueue(() -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("content hit worker release");
                        } catch (InterruptedException error) {
                            Thread.currentThread()
                                .interrupt();
                        }
                    }), "real worker holds the cache lookup queue boundary");
                    SimpleAssert
                        .that(entered.await(5, TimeUnit.SECONDS), "cache lookup has not run before initial admission");
                    try {
                        f.actor.inventory.currentItem = 0;
                        f.request.setString("op", "contentResolve");
                        f.store.submit(f.actor, f.request, false);
                        f.actor.inventory.currentItem = 1;
                        f.store.heartbeat();
                        SimpleAssert.that(
                            (Boolean) get(peer, "contentValid"),
                            "queued content cache hit retains original tool while another slot is held");
                    } finally {
                        release.countDown();
                    }
                    f.worker(() -> {});
                    f.worker(() -> {});
                    SimpleAssert.that(
                        !(Boolean) get(peer, "contentValid") && get(peer, "contentTool") == null
                            && ((Long) get(peer, "retiredThrough")) == 0,
                        "real cache-hit result completes without revoking or retaining its contextless owner");
                } else SimpleAssert.that(
                    !(Boolean) get(peer, "contentValid") && get(peer, "contentWorld") == null
                        && get(peer, "contentTool") == null,
                    "true content owner revocation releases references: " + fence);
                SimpleAssert.that(
                    HologramWorkspace.find(f.actor.getUniqueID()) == null,
                    "pure content work never invents a workspace");
            }
        try (Fixture f = new Fixture()) {
            for (int i = 0; i < 64; i++) {
                HologramLibraryActivationResult retired = new HologramLibraryActivationResult(
                    new NBTTagCompound(),
                    "",
                    () -> true,
                    () -> true,
                    tag -> {});
                retired.failed("finished");
                f.activations.put(UUID.randomUUID(), retired);
            }
            SimpleAssert.that(
                HologramLibraryFacade.beginActivation(f.actor, f.request, f.store),
                "64 terminal tombstones consume no active preparation capacity");
            f.actor.inventory.currentItem = 1;
            f.request.setLong("clientTaskToken", 103);
            SimpleAssert.that(
                !HologramLibraryFacade.beginActivation(f.actor, f.request, f.store),
                "new admission remains held-tool-only even when an older preparation exists");
        }
    }

    private static void ownerAdmissionBoundaries() throws Exception {
        for (String outcome : new String[] { "accepted", "rejected", "cancelled", "changedRevision" }) {
            try (Fixture f = new Fixture()) {
                HologramBlueprintProcessing.Policy policy = HologramBlueprintProcessing.defaults("self", 0);
                policy.confirmedStages.addAll(HologramBlueprintProcessing.STAGES);
                HologramPresets.Preset source = HologramBlueprintProcessing
                    .processDetached(preset(), policy, 0, 0, 0, 0);
                NBTTagCompound receipt = HologramBlueprintNbt.decode(source.processingNbt);
                NBTTagCompound forged = new NBTTagCompound();
                forged.setBoolean("forgedApproval", true);
                receipt.setTag("extensions", forged);
                source.processingNbt = HologramBlueprintNbt.encodeCanonical(receipt);
                String before = source.processingNbt;
                f.bytes = HologramBlueprintArtifact
                    .encode(HologramServerLibrary.encodeShare(source), HologramBlueprintArtifact.decode(f.bytes).png);
                java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
                Thread owner = Thread.currentThread();
                try (
                    com.miaokatze.gtmb.api.GTMBExtensions.Registration nativeRegistration = com.miaokatze.gtmb.api.NativePlacement
                        .register(
                            "gtmb_test:private-owner-native",
                            new com.miaokatze.gtmb.api.NativePlacement.Adapter() {

                                public boolean supports(net.minecraft.block.Block block) {
                                    SimpleAssert.that(
                                        Thread.currentThread() == owner,
                                        "external native applicability never runs on upload IO");
                                    return false;
                                }

                                public net.minecraft.tileentity.TileEntity create(net.minecraft.block.Block block,
                                    int metadata, NBTTagCompound tag, net.minecraft.world.World world) {
                                    throw new AssertionError("nonmatching native adapter create");
                                }

                                public List<ItemStack> materials(net.minecraft.block.Block block, int metadata,
                                    net.minecraft.tileentity.TileEntity tile) {
                                    throw new AssertionError("nonmatching native adapter materials");
                                }
                            });
                    com.miaokatze.gtmb.api.GTMBExtensions.Registration registration = com.miaokatze.gtmb.api.BlueprintSnapshots
                        .register(
                            "gtmb_test:private-owner-admission",
                            com.miaokatze.gtmb.api.BlueprintSnapshots.Stage.AUTHORING_COMPLETE,
                            snapshot -> {
                                SimpleAssert.that(
                                    Thread.currentThread() == owner,
                                    "private admission extension runs on owner, never the upload IO worker");
                                calls.incrementAndGet();
                                if ("rejected".equals(outcome)) throw new IOException("extension veto");
                                return new NBTTagCompound();
                            })) {
                    f.begin(false, false);
                    f.upload();
                    SimpleAssert.eq(0, calls.get(), "private bytes alone never invoke completion callbacks");
                    SimpleAssert.that(
                        get(f.peer(), "upload") != null,
                        "external compatibility upload retains its bounded owner-review reservation");
                    SimpleAssert.eq(
                        1,
                        (Integer) get(f.store, "activeUploads"),
                        "only external compatibility path reserves pending owner work");
                    if ("cancelled".equals(outcome)) {
                        NBTTagCompound cancel = (NBTTagCompound) f.request.copy();
                        cancel.setString("op", "cancel");
                        f.store.submit(f.actor, cancel, false);
                        f.worker(() -> {});
                    }
                    if ("changedRevision".equals(outcome)) {
                        f.session.planRevision++;
                        f.store.heartbeat();
                        f.worker(() -> {});
                    }
                    f.serverCallbacks();
                    f.worker(() -> {});
                    boolean accepted = "accepted".equals(outcome);
                    SimpleAssert.eq(
                        accepted || "rejected".equals(outcome) ? 1 : 0,
                        calls.get(),
                        "one review per exact activation; stale/cancelled work invokes none: " + outcome);
                    SimpleAssert
                        .eq(accepted ? 1 : 0, f.starts, "extension veto or stale source cannot activate: " + outcome);
                    if (accepted) SimpleAssert.that(
                        !HologramBlueprintNbt.decode(f.activatedSource.processingNbt)
                            .getCompoundTag("extensions")
                            .hasKey("forgedApproval"),
                        "private activation replaces forged incoming report before receiver");
                    SimpleAssert.eq(
                        0,
                        (Integer) get(f.store, "activeUploads"),
                        "owner review terminal releases active upload quota: " + outcome);
                    SimpleAssert.eq(
                        0L,
                        (Long) get(f.store, "reservedBytes"),
                        "owner review terminal releases byte reservation: " + outcome);
                    SimpleAssert.eq(
                        0L,
                        (Long) get(f.store, "reviewDtoBytes"),
                        "owner review terminal releases expanded DTO quota: " + outcome);
                    SimpleAssert.eq(before, source.processingNbt, "upload review preserves original candidate source");
                    if (!accepted) SimpleAssert.that(
                        HologramWorkspace.find(f.actor.getUniqueID()) == f.session,
                        "failed review preserves original server presentation");
                }
            }
        }
    }

    static EntityPlayerMP connectedPlayer(String name) throws Exception {
        Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
        HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
            .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
        set(world, "provider", new net.minecraft.world.WorldProviderSurface());
        Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
            "player",
            sun.misc.Unsafe.class,
            HologramServiceEditRegression.ReadOnlyWorld.class,
            UUID.class,
            String.class);
        factory.setAccessible(true);
        EntityPlayerMP actor = (EntityPlayerMP) factory.invoke(null, unsafe, world, UUID.randomUUID(), name);
        actor.posY = 64;
        return actor;
    }

    private static final class Fixture implements AutoCloseable {

        final Path root = Files.createTempDirectory("gtmb-admitted-preparation-");
        final HologramServerLibrary store = new HologramServerLibrary(
            root.resolve("entries"),
            root.resolve("library.cfg"));
        final EntityPlayerMP actor;
        final HologramSession session;
        final NBTTagCompound request = HologramLibraryProtocol.request("privateBegin");
        final List<NBTTagCompound> replies = new ArrayList<>();
        final Map<UUID, HologramLibraryActivationResult> activations;
        final Map<UUID, HologramLibraryActivationResult> oldActivations;
        final Object oldBackend, oldReceiver, oldDirectory;
        HologramLibraryActivationResult result;
        byte[] bytes;
        int starts;
        HologramPresets.Preset activatedSource;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            Field singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            sun.misc.Unsafe unsafe = (sun.misc.Unsafe) singleton.get(null);
            HologramServiceEditRegression.ReadOnlyWorld world = (HologramServiceEditRegression.ReadOnlyWorld) unsafe
                .allocateInstance(HologramServiceEditRegression.ReadOnlyWorld.class);
            set(world, "provider", new net.minecraft.world.WorldProviderSurface());
            Method factory = HologramLibraryServiceRegression.class.getDeclaredMethod(
                "player",
                sun.misc.Unsafe.class,
                HologramServiceEditRegression.ReadOnlyWorld.class,
                UUID.class,
                String.class);
            factory.setAccessible(true);
            actor = (EntityPlayerMP) factory.invoke(null, unsafe, world, UUID.randomUUID(), "PreparationOwner");
            actor.posY = 64;
            session = new HologramSession(actor, 0, 64, 0, false, 2);
            session.virtual = true;
            session.libraryUiActive = true;
            HologramWorkspace.activate(actor.getUniqueID(), session);
            oldBackend = get(HologramLibraryFacade.class, "library");
            oldReceiver = get(HologramLibraryFacade.class, "privateReceiver");
            oldDirectory = get(HologramLibraryFacade.class, "clientGameDirectory");
            activations = (Map<UUID, HologramLibraryActivationResult>) get(HologramLibraryFacade.class, "activations");
            oldActivations = new HashMap<>(activations);
            activations.clear();
            set(HologramLibraryFacade.class, "library", store);
            HologramLibraryFacade.setPrivateReceiver((player, identity, source, completion) -> {
                starts++;
                activatedSource = source;
                completion.completed(source.id);
            });
            request.setString("session", session.id);
            request.setLong("planRevision", session.planRevision);
            request.setLong("clientTaskToken", 101);
            request.setString(
                "transfer",
                UUID.randomUUID()
                    .toString());
            request.setString("action", "edit");
            request.setInteger("artifactVersion", HologramBlueprintArtifact.VERSION);
            java.io.ByteArrayOutputStream image = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(
                new java.awt.image.BufferedImage(
                    HologramBlueprintArtifact.WIDTH,
                    HologramBlueprintArtifact.HEIGHT,
                    java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png",
                image);
            bytes = HologramBlueprintArtifact.encode(HologramServerLibrary.encodeShare(preset()), image.toByteArray());
            worker(() -> {});
        }

        void begin(boolean publicSource, boolean malformed) throws Exception {
            if (publicSource) {
                HologramLibraryFacade.configureClientGameDirectory(root.resolve("game"));
                HologramPresets.Preset source = preset();
                HologramPresets.save(source);
                request.setString("op", "publicPrepare");
                request.setString("id", source.id);
                request.setString("integratedPresetId", source.id);
                request.setString("sourceHash", HologramPresets.contentHash(source.id));
            }
            if (malformed) bytes = new byte[] { 1, 2, 3, 4 };
            request.setInteger("total", bytes.length);
            request.setString("hash", HologramBlueprintArtifact.hash(bytes));
            SimpleAssert.that(
                HologramLibraryFacade.beginActivation(actor, request, store),
                "strict original held-tool request admitted");
            result = activations.get(actor.getUniqueID());
            set(result, "sink", (java.util.function.Consumer<NBTTagCompound>) replies::add);
            store.submit(actor, request, false);
            worker(() -> {});
            worker(() -> {});
        }

        Object peer() throws Exception {
            return ((Map<?, ?>) get(store, "peers")).get(actor.getUniqueID());
        }

        void upload() throws Exception {
            for (int offset = 0, index = 0; offset
                < bytes.length; offset += HologramLibraryProtocol.CHUNK_BYTES, index++) {
                NBTTagCompound chunk = HologramLibraryProtocol.request("privateChunk");
                chunk.setString("transfer", request.getString("transfer"));
                chunk.setLong("clientTaskToken", request.getLong("clientTaskToken"));
                chunk.setInteger("index", index);
                chunk.setByteArray(
                    "data",
                    Arrays.copyOfRange(
                        bytes,
                        offset,
                        Math.min(bytes.length, offset + HologramLibraryProtocol.CHUNK_BYTES)));
                Object peer = peer();
                worker(() -> call(store, "process", peer, chunk));
            }
        }

        void worker(Checked work) throws Exception {
            CompletableFuture<Void> future = new CompletableFuture<>();
            SimpleAssert.that(store.enqueue(() -> {
                try {
                    work.run();
                    future.complete(null);
                } catch (Throwable error) {
                    future.completeExceptionally(error);
                }
            }), "real library worker accepts bounded test operation");
            future.get(10, TimeUnit.SECONDS);
        }

        void serverCallbacks() throws Exception {
            ownerCallbacks();
            worker(() -> {
                Object peer = peer();
                if (peer != null) call(store, "finishReviewedUpload", peer);
            }); // Reviewed outcome returns to IO; no owner-thread producer waits in production.
            ownerCallbacks(); // Private activation is separately owner/held-tool fenced.
            poll();
        }

        void ownerCallbacks() throws Exception {
            HologramWorkBudget.startServerTick();
            com.miaokatze.gtmb.util.ServerTaskScheduler.INSTANCE.onServerTick(
                new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                    cpw.mods.fml.common.gameevent.TickEvent.Phase.END));
            HologramWorkBudget.endServerTick();
        }

        void poll() throws Exception {
            call(HologramLibraryFacade.class, "pollActivations");
        }

        public void close() throws Exception {
            store.stop();
            com.miaokatze.gtmb.util.ServerTaskScheduler.clear();
            actor.playerNetServerHandler.netManager.channel()
                .close();
            ((Map<?, ?>) get(HologramWorkspace.class, "SESSIONS")).remove(actor.getUniqueID());
            activations.clear();
            activations.putAll(oldActivations);
            set(HologramLibraryFacade.class, "library", oldBackend);
            set(HologramLibraryFacade.class, "privateReceiver", oldReceiver);
            set(HologramLibraryFacade.class, "clientGameDirectory", oldDirectory);
            HologramPresets.clearCache();
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder())
                    .toArray(Path[]::new)) Files.deleteIfExists(path);
            }
        }
    }

    private static HologramPresets.Preset preset() throws IOException {
        HologramPresets.Preset source = new HologramPresets.Preset();
        source.schema = 2;
        source.id = "prepared-fixture";
        source.name = "prepared";
        source.controllerType = "area";
        source.cells.add(HologramPresets.cell(new ItemStack(Blocks.stone), 0, 0, 0, -1, false));
        HologramBlueprintVersions.stamp(source);
        HologramPresets.validate(source);
        return source;
    }

    private static String repeat(char c, int length) {
        char[] value = new char[length];
        Arrays.fill(value, c);
        return new String(value);
    }

    private static Field field(Object target, String name) throws Exception {
        for (Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass(); type
            != null; type = type.getSuperclass()) try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException missing) {}
        throw new NoSuchFieldException(name);
    }

    private static Object get(Object target, String name) throws Exception {
        return field(target, name).get(target instanceof Class ? null : target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        field(target, name).set(target instanceof Class ? null : target, value);
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        for (Class<?> type = target instanceof Class ? (Class<?>) target : target.getClass(); type
            != null; type = type.getSuperclass())
            for (Method method : type.getDeclaredMethods()) if (method.getName()
                .equals(name) && method.getParameterCount() == args.length) {
                    method.setAccessible(true);
                    try {
                        return method.invoke(target instanceof Class ? null : target, args);
                    } catch (InvocationTargetException failed) {
                        if (failed.getCause() instanceof Exception) throw (Exception) failed.getCause();
                        throw failed;
                    }
                }
        throw new NoSuchMethodException(name);
    }

    interface Checked {

        void run() throws Exception;
    }
}
