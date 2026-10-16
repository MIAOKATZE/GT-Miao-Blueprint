package gtmb.v019.probe;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Disposable native transport benchmark, compatible with both normal v018 and v019. No product classes replaced. */
@Mod(modid = "gtmb_v019_download_probe", name = "GTMB native blueprint download witness", version = "1")
public final class V019BlueprintDownloadProbe {

    private final Properties proof = new Properties();
    private final ExecutorService disk = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "GTMB download probe verification");
        thread.setDaemon(true); return thread;
    });
    private File output, fixture, evidenceRoot;
    private Object mc, actor, preset;
    private boolean client, server, connected, requested, sourceReady;
    private volatile boolean configured;
    private volatile boolean done;
    private volatile boolean diskDone;
    private int ticks, placed, index, total, decodedTotal;
    private long started, firstChunk, lastChunk, wireBytes, packetBytes;
    private volatile long began;
    private volatile long lastPacketAt;
    private String transfer, expectedHash, encoding;
    private ByteArrayOutputStream incoming;
    private int rounds, warmupSeconds, round = 1, serverRound;
    private volatile boolean roundFinished;
    private boolean roundRequestSent, platformReady;
    private long warmupAt, nextRoundAt, doneReceivedAt;
    private String stableSourceHash = "", stableDownloadKiB = "", stableUploadKiB = "";
    private final java.util.Set<String> transfers = new java.util.HashSet<>();
    private final java.util.Map<String, java.util.List<Long>> samples = new java.util.LinkedHashMap<>();
    private static final String[] TIMINGS = { "headerToLastByteNanos", "headerToVerifiedNanos",
        "headerToVerifiedJsonDiskNanos", "workerQueueDelayNanos", "workerDecodeAndShaNanos",
        "independentShaNanos", "nativeJsonValidateNanos", "sourceReadCompareNanos", "atomicWriteNanos",
        "diskReadbackAndHashNanos" };

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) throws Exception {
        client = Boolean.getBoolean("gtmb.v019.downloadClient");
        server = Boolean.getBoolean("gtmb.v019.downloadServer");
        if (!client && !server) return;
        rounds = Integer.getInteger("gtmb.v019.downloadRounds", 1);
        warmupSeconds = Integer.getInteger("gtmb.v019.downloadWarmupSeconds", rounds == 1 ? 0 : 30);
        check(rounds >= 1 && rounds <= 3 && warmupSeconds >= 0 && warmupSeconds <= 120, "bounded rounds/warmup required");
        if (rounds > 1) check(rounds == 3 && warmupSeconds == 30
            && !System.getProperty("gtmb.v019.downloadSource", "").isEmpty(),
            "multi-round comparison requires exactly three rounds, thirty-second warmup, and the frozen baseline source");
        fixture = new File(System.getProperty("gtmb.hologram.followupRoot", "")).getCanonicalFile();
        check(fixture.getPath().replace('\\', '/').endsWith("/build/hologram-packaged-v018-mui"),
            "exact existing owned fixture required");
        check(new File(".").getCanonicalFile().equals(new File(fixture, client ? "client" : "server").getCanonicalFile()),
            "process must run inside the owned fixture side");
        evidenceRoot = new File(fixture.getParentFile(), "verification/download-v019").getCanonicalFile();
        output = new File(System.getProperty("gtmb.v019.output", "")).getCanonicalFile();
        check(output.toPath().startsWith(evidenceRoot.toPath()) && output.isDirectory(), "fresh owned evidence required");
        check(!file(client ? "download-client.properties" : "download-server.properties").exists(), "fresh receipts required");
        proof.setProperty("scope", "constructed 32-cubed real native block DTO; actual production prepareDraft/encoder/Netty download; final JSON disk readback; excludes PNG capture and owner save ACK");
        proof.setProperty("pid", ManagementFactory.getRuntimeMXBean().getName());
        proof.setProperty("javaVersion", System.getProperty("java.version"));
        proof.setProperty("protocolVersion", String.valueOf(get(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryProtocol"), "VERSION")));
        proof.setProperty("roundsRequested", String.valueOf(rounds));
        proof.setProperty("warmupSeconds", String.valueOf(warmupSeconds));
        proof.setProperty("roundPolicy", "all rounds retained; no fastest-round selection; five-second inter-round gap");
        started = System.nanoTime();
        if (client) {
            // Observe the existing post-delivery test hook. Enabling librarySocketSmoke would start another driver.
            // The actual product receiver, Netty handlers, serializers and channel are left installed.
            member(Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork"), "libraryProbeReceiver")
                .set(null, (Consumer<Object>) this::receive);
        }
        FMLCommonHandler.instance().bus().register(this);
        persist("RUNNING");
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (!client || done || event.phase != TickEvent.Phase.END) return;
        try {
            check(System.nanoTime() - started < 900000000000L, "native download client timeout");
            ticks++;
            if (mc == null) mc = call(Class.forName("net.minecraft.client.Minecraft"), "func_71410_x");
            if (diskDone) {
                done = true;
                call(mc, "func_71400_g");
                return;
            }
            Properties peer = read("download-server.properties");
            check(!peer.containsKey("failure"), "paired server failed: " + peer.getProperty("failure", ""));
            if (began == 0 && !roundRequestSent) check(System.nanoTime() - started < (rounds == 1 ? 180L : 300L) * 1000000000L, "metadata preparation/connection timeout");
            else if (began == 0) check(System.nanoTime() - lastPacketAt < 120000000000L, "round metadata timeout");
            else if (!diskDone) check(System.nanoTime() - lastPacketAt < 120000000000L, "download packet progress timeout");
            Object world = get(mc, "field_71441_e"), player = get(mc, "field_71439_g");
            if (!connected && ticks > 30 && get(mc, "field_71462_r") != null) {
                String address = System.getProperty("gtmb.v019.address", "127.0.0.1:25678");
                check(address.matches("127\\.0\\.0\\.1:[0-9]{2,5}"), "owned loopback server required");
                Object fml = call(Class.forName("cpw.mods.fml.client.FMLClientHandler"), "instance");
                call(fml, "setupServerList");
                call(fml, "connectToServer", get(mc, "field_71462_r"),
                    construct("net.minecraft.client.multiplayer.ServerData", "GTMB owned download benchmark", address));
                connected = true;
            }
            if (world == null || player == null) return;
            Object network = call(call(mc, "func_147114_u"), "func_147298_b");
            check(!(Boolean) call(network, "func_150731_c"), "actual dedicated Netty connection required");
            if (!requested && sourceReady()) {
                Object request = call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryProtocol"), "request", "configGet");
                call(Class.forName("com.miaokatze.gtmb.hologram.HologramNetwork"), "sendLibrary", request);
                requested = true;
            }
            if (configured && sourceReady() && !roundRequestSent) {
                if (!safeClientActor(world, player)) {
                    warmupAt = 0;
                    return;
                }
                long now = System.nanoTime();
                if (warmupAt == 0) {
                    warmupAt = now;
                    stableSourceHash = read("download-source-ready.properties").getProperty("sourceSha256", "");
                    check(stableSourceHash.matches("[a-f0-9]{64}"), "frozen source SHA required before warmup");
                    proof.setProperty("warmupStartedAtNanos", String.valueOf(now));
                    persist("WARMING_UP");
                }
                if (now - warmupAt >= warmupSeconds * 1000000000L && now >= nextRoundAt) {
                    resetRound();
                    proof.setProperty("warmupElapsedNanos", String.valueOf(now - warmupAt));
                    Properties p = new Properties(); p.setProperty("ready", "true");
                    p.setProperty("round", String.valueOf(round));
                    p.setProperty("warmupElapsedNanos", String.valueOf(now - warmupAt));
                    p.setProperty("aliveLoadedActor", "true");
                    store(requestFile(round), p);
                    lastPacketAt = now;
                    roundRequestSent = true;
                }
            }
            if (roundFinished && !diskDone) {
                roundFinished = false;
                round++;
                roundRequestSent = false;
                nextRoundAt = System.nanoTime() + 5000000000L;
                persist("BETWEEN_ROUNDS");
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private boolean sourceReady() { return file("download-source-ready.properties").isFile(); }

    private String requestFile(int number) {
        return rounds == 1 ? "download-request.properties" : "download-request-round-" + number + ".properties";
    }

    private boolean safeClientActor(Object world, Object player) throws Exception {
        if (!(Boolean) call(player, "func_70089_S") || (Boolean) get(player, "field_70128_L"))
            throw new IllegalStateException("benchmark actor is not alive");
        double x = ((Number) get(player, "field_70165_t")).doubleValue();
        double y = ((Number) get(player, "field_70163_u")).doubleValue();
        double z = ((Number) get(player, "field_70161_v")).doubleValue();
        if (x < 12 || x >= 21 || z < 12 || z >= 21 || y < 117 || y > 124) return false;
        int cx = ((int) Math.floor(x)) >> 4, cz = ((int) Math.floor(z)) >> 4;
        Object chunk = call(world, "func_72964_e", cx, cz);
        if (chunk == null || Class.forName("net.minecraft.world.chunk.EmptyChunk").isInstance(chunk)
            || !(Boolean) get(chunk, "field_76636_d") || (Integer) get(chunk, "field_76635_g") != cx
            || (Integer) get(chunk, "field_76647_h") != cz) return false;
        Object stone = call(get(Class.forName("net.minecraft.block.Block"), "field_149771_c"), "func_82594_a", "minecraft:stone");
        if (call(world, "func_147439_a", (int) Math.floor(x), 119, (int) Math.floor(z)) != stone) return false;
        proof.setProperty("actorAlive", "true"); proof.setProperty("actorChunkLoaded", "true");
        proof.setProperty("actorChunkCoordinates", cx + "," + cz);
        proof.setProperty("actorY", String.valueOf(y));
        proof.setProperty("actualPlatformReceived", "true");
        return true;
    }

    private synchronized void resetRound() {
        check(incoming == null, "previous transfer buffer must be finished");
        began = firstChunk = lastChunk = wireBytes = packetBytes = doneReceivedAt = 0;
        index = total = decodedTotal = 0;
        transfer = expectedHash = encoding = null;
        proof.setProperty("round", String.valueOf(round));
    }

    private synchronized void receive(Object packet) {
        if (!client || done || diskDone) return;
        try {
            String op = (String) call(packet, "func_74779_i", "op");
            if ("config".equals(op)) {
                String upload = String.valueOf(call(packet, "func_74762_e", "uploadKiB"));
                String download = String.valueOf(call(packet, "func_74762_e", "downloadKiB"));
                if (configured) check(upload.equals(stableUploadKiB) && download.equals(stableDownloadKiB), "bandwidth must stay unchanged");
                stableUploadKiB = upload; stableDownloadKiB = download;
                proof.setProperty("uploadKiB", upload);
                proof.setProperty("downloadKiB", download);
                configured = true; return;
            }
            if ("error".equals(op)) throw new IllegalStateException(String.valueOf(call(packet, "func_74779_i", "message")));
            if (!("downloadBegin".equals(op) || "downloadChunk".equals(op) || "downloadDone".equals(op))) return;
            long now = System.nanoTime();
            lastPacketAt = now;
            // Measure real serialized packet bytes including library version/length and FML discriminator.
            byte[] compressedPacket = (byte[]) call(Class.forName("net.minecraft.nbt.CompressedStreamTools"), "func_74798_a", packet);
            packetBytes += compressedPacket.length + 9;
            if ("downloadBegin".equals(op)) {
                check(incoming == null, "no overlapping transfer");
                began = now;
                transfer = (String) call(packet, "func_74779_i", "transfer");
                check(transfers.add(transfer), "each round must allocate a fresh actual transfer ID");
                expectedHash = (String) call(packet, "func_74779_i", "hash");
                check(expectedHash.equals(stableSourceHash), "all rounds must download the frozen original SHA");
                proof.setProperty("transferId", transfer);
                total = (Integer) call(packet, "func_74762_e", "total");
                encoding = (String) call(packet, "func_74779_i", "encoding");
                decodedTotal = (Integer) call(packet, "func_74762_e", "decodedTotal");
                if (encoding.isEmpty()) { encoding = "identity"; decodedTotal = total; }
                check(total > 0 && total <= 8388608 && decodedTotal > 0 && decodedTotal <= 8388608,
                    "independent wire and decoded byte bounds");
                check(expectedHash.matches("[a-f0-9]{64}"), "original SHA required");
                incoming = new ByteArrayOutputStream(total);
                proof.setProperty("rawBytes", String.valueOf(decodedTotal));
                proof.setProperty("wirePayloadBytes", String.valueOf(total));
                proof.setProperty("rawSha256", expectedHash);
                proof.setProperty("encoding", encoding);
                proof.setProperty("headerAtNanos", String.valueOf(began));
            } else {
                check(incoming != null && transfer.equals(call(packet, "func_74779_i", "transfer")), "transfer identity unchanged");
                if ("downloadChunk".equals(op)) {
                    byte[] data = (byte[]) call(packet, "func_74770_j", "data");
                    check((Integer) call(packet, "func_74762_e", "index") == index++, "ordered nonduplicate chunks");
                    check(data.length == Math.min(16384, total - incoming.size()), "bounded exact chunk size");
                    if (firstChunk == 0) firstChunk = now;
                    lastChunk = now;
                    incoming.write(data);
                    wireBytes += data.length;
                } else {
                    doneReceivedAt = now;
                    check(incoming.size() == total && wireBytes == total, "all declared wire bytes received");
                    proof.setProperty("headerToLastByteNanos", String.valueOf(lastChunk - began));
                    proof.setProperty("headerToDoneNanos", String.valueOf(now - began));
                    proof.setProperty("firstByteToLastByteNanos", String.valueOf(lastChunk - firstChunk));
                    proof.setProperty("chunkCount", String.valueOf(index));
                    proof.setProperty("serializedLibraryBytesIncludingHeaders", String.valueOf(packetBytes));
                    proof.setProperty("actualPayloadBytesPerSecond", String.valueOf(total * 1.0e9 / Math.max(1, lastChunk - began)));
                    for (String key : new String[] { "transferNanos", "budgetPolls", "chunks", "wireBytes", "decodedBytes" })
                        proof.setProperty("server." + key, String.valueOf(call(packet, "func_74763_f", key)));
                    byte[] bytes = incoming.toByteArray(); incoming = null;
                    disk.submit(() -> verifyAndWrite(bytes));
                }
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void verifyAndWrite(byte[] wire) {
        try {
            long workerStarted = System.nanoTime();
            byte[] raw;
            if ("identity".equals(encoding)) {
                raw = wire;
                check(raw.length == decodedTotal && sha(raw).equals(expectedHash), "baseline original SHA verified");
            } else {
                // Candidate's actual production bounded decoder and integrity check, not a mirror implementation.
                raw = (byte[]) call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryDownload"), "decode",
                    wire, encoding, decodedTotal, expectedHash);
            }
            long verifiedAt = System.nanoTime();
            check(sha(raw).equals(expectedHash), "independent decoded SHA verified");
            long independentAt = System.nanoTime();
            call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryFacade"), "validateNativeJson", raw);
            long nativeValidatedAt = System.nanoTime();
            File source = checkedSource();
            check(Arrays.equals(raw, Files.readAllBytes(source.toPath())), "exact source bytes independently compared");
            long sourceComparedAt = System.nanoTime();
            File target = file(rounds == 1 ? "downloaded-draft.json" : "downloaded-draft-round-" + round + ".json");
            check(!target.exists(), "each round must write a fresh atomic JSON target");
            call(Class.forName("com.miaokatze.gtmb.hologram.HologramBlueprintSharing"), "atomicWrite", target.toPath(), raw);
            long writtenAt = System.nanoTime();
            byte[] readback = Files.readAllBytes(target.toPath());
            String readbackHash = sha(readback);
            check(Arrays.equals(raw, readback) && readbackHash.equals(expectedHash), "actual disk source/readback equality");
            long diskAt = System.nanoTime();
            proof.setProperty("workerDecodeAndShaNanos", String.valueOf(verifiedAt - workerStarted));
            proof.setProperty("workerQueueDelayNanos", String.valueOf(workerStarted - doneReceivedAt));
            proof.setProperty("independentShaNanos", String.valueOf(independentAt - verifiedAt));
            proof.setProperty("nativeJsonValidateNanos", String.valueOf(nativeValidatedAt - independentAt));
            proof.setProperty("sourceReadCompareNanos", String.valueOf(sourceComparedAt - nativeValidatedAt));
            proof.setProperty("atomicWriteNanos", String.valueOf(writtenAt - sourceComparedAt));
            proof.setProperty("diskReadbackAndHashNanos", String.valueOf(diskAt - writtenAt));
            proof.setProperty("headerToVerifiedNanos", String.valueOf(verifiedAt - began));
            proof.setProperty("headerToVerifiedJsonDiskNanos", String.valueOf(diskAt - began));
            proof.setProperty("verifiedJsonDiskSha256", readbackHash);
            proof.setProperty("jsonDiskPath", target.getCanonicalPath());
            proof.setProperty("completePngArtifactSaveMeasured", "false");
            proof.setProperty("exactSourceBytesAndFinalSha", "true");
            proof.setProperty("round", String.valueOf(round));
            proof.setProperty("roundsCompleted", String.valueOf(round));
            proof.setProperty("round." + round + ".transferId", transfer);
            proof.setProperty("round." + round + ".rawSha256", expectedHash);
            proof.setProperty("round." + round + ".diskSha256", readbackHash);
            proof.setProperty("round." + round + ".wirePayloadBytes", String.valueOf(total));
            Properties receipt = new Properties(); receipt.putAll(proof); receipt.setProperty("status", "PASS");
            store("download-client-round-" + round + ".properties", receipt);
            for (String metric : TIMINGS) {
                java.util.List<Long> values = samples.get(metric);
                if (values == null) { values = new java.util.ArrayList<>(); samples.put(metric, values); }
                values.add(Long.parseLong(proof.getProperty(metric)));
                proof.setProperty("round." + round + "." + metric, proof.getProperty(metric));
            }
            if (round == rounds) {
                summarize();
                persist("PASS");
            } else persist("ROUND_PASSED");
            System.out.println("[GTMB-DOWNLOAD-NATIVE] PASS round=" + round + "/" + rounds + " raw=" + raw.length + " wire=" + wire.length
                + " transferNs=" + (lastChunk - began) + " diskNs=" + (diskAt - began));
            if (round == rounds) diskDone = true;
            else roundFinished = true;
        } catch (Throwable failure) { fail(failure); }
    }

    private void summarize() {
        for (java.util.Map.Entry<String, java.util.List<Long>> metric : samples.entrySet()) {
            java.util.List<Long> sorted = new java.util.ArrayList<>(metric.getValue());
            java.util.Collections.sort(sorted);
            proof.setProperty("summary." + metric.getKey() + ".min", String.valueOf(sorted.get(0)));
            proof.setProperty("summary." + metric.getKey() + ".median", String.valueOf(sorted.get(sorted.size() / 2)));
            proof.setProperty("summary." + metric.getKey() + ".max", String.valueOf(sorted.get(sorted.size() - 1)));
            proof.setProperty("summary." + metric.getKey() + ".all", metric.getValue().toString());
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (!server || done || event.phase != TickEvent.Phase.END) return;
        try {
            check(System.nanoTime() - started < 900000000000L, "native download server timeout");
            Properties peer = read("download-client.properties");
            check(!peer.containsKey("failure"), "paired client failed: " + peer.getProperty("failure", ""));
            if (!requested) check(System.nanoTime() - started < (rounds == 1 ? 180L : 300L) * 1000000000L, "native source/authorization preparation timeout");
            Object host = call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C");
            Object world = call(host, "func_71218_a", 0);
            check(String.valueOf(call(call(world, "func_72912_H"), "func_76065_j")).startsWith("hologram-cut-v020-"), "owned benchmark world required");
            if (!platformReady) preparePlatform(world);
            call(world, "func_72877_b", 6000L);
            List<?> players = (List<?>) get(call(host, "func_71203_ab"), "field_72404_b");
            if (players.isEmpty()) return;
            check(players.size() == 1, "one authenticated real benchmark player");
            if (actor == null) {
                actor = players.get(0);
                call(get(actor, "field_71135_a"), "func_147364_a", 16.5d, 120d, 16.5d, 180f, 0f);
                call(call(host, "func_71203_ab"), "func_72358_d", actor);
                proof.setProperty("teleportThenChunkWatchUpdate", "true");
                Object inventory = get(actor, "field_71071_by");
                Object item = call(get(Class.forName("net.minecraft.item.Item"), "field_150901_e"), "func_82594_a", "gtmb:neko_hologram_projector");
                java.lang.reflect.Array.set(get(inventory, "field_70462_a"), 0, construct("net.minecraft.item.ItemStack", item));
                set(inventory, "field_70461_c", 0);
                call(get(actor, "field_71069_bz"), "func_75142_b");
                call(Class.forName("com.miaokatze.gtmb.hologram.HologramService"), "openSettings", actor);
            }
            check((Boolean) call(actor, "func_70089_S") && !(Boolean) get(actor, "field_70128_L"), "live native server actor required");
            ticks++;
            if (!sourceReady) {
                String reuse = System.getProperty("gtmb.v019.downloadSource", "");
                if (!reuse.isEmpty()) {
                    File source = checkedSource();
                    byte[] bytes = Files.readAllBytes(source.toPath());
                    check(bytes.length > 1000000 && bytes.length <= 8388608, "same large baseline payload required");
                    preset = call(Class.forName("com.miaokatze.gtmb.hologram.HologramBlueprintSharing"), "decode", new String(bytes, StandardCharsets.UTF_8));
                    finishSource(bytes, true);
                } else {
                    createNativeCells(world);
                }
                return;
            }
            int nextRound = serverRound + 1;
            boolean previousComplete = serverRound == 0 || "PASS".equals(read("download-client-round-" + serverRound + ".properties").getProperty("status"));
            if (nextRound <= rounds && previousComplete && ticks > 200 && file(requestFile(nextRound)).isFile()) {
                Object session = call(Class.forName("com.miaokatze.gtmb.hologram.HologramWorkspace"), "find", call(actor, "func_110124_au"));
                Object current = call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryFacade"), "backend");
                Object view = call(current, "config");
                String down = String.valueOf(get(view, "downloadKiB")), up = String.valueOf(get(view, "uploadKiB"));
                if (serverRound > 0) check(down.equals(stableDownloadKiB) && up.equals(stableUploadKiB), "actual server rate unchanged across rounds");
                stableDownloadKiB = down; stableUploadKiB = up;
                proof.setProperty("downloadKiB", down);
                proof.setProperty("uploadKiB", up);
                Object capability = call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryProtocol"), "request", "exportDraft");
                call(capability, "func_74778_a", "session", get(session, "id"));
                call(capability, "func_74772_a", "planRevision", get(session, "planRevision"));
                check((Boolean) call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryService"), "sessionCurrent", actor, capability),
                    "actual connected projector/session/revision authorization before request");
                proof.setProperty("sessionAuthorizedBeforeRequest", "true");
                // Real service capability checks run again after the worker's production encoder returns.
                call(Class.forName("com.miaokatze.gtmb.hologram.HologramLibraryFacade"), "downloadDraft", actor, preset,
                    get(session, "id"), get(session, "planRevision"), "local_00000000-0000-0000-0000-000000000019");
                serverRound = nextRound;
                proof.setProperty("round", String.valueOf(serverRound));
                proof.setProperty("round." + serverRound + ".sessionAuthorized", "true");
                requested = true; persist("TRANSFER_REQUESTED");
                Properties serverReceipt = new Properties(); serverReceipt.putAll(proof);
                store("download-server-round-" + serverRound + ".properties", serverReceipt);
            }
            Properties result = read("download-client.properties");
            if ("PASS".equals(result.getProperty("status")) || "FAIL".equals(result.getProperty("status"))) {
                proof.setProperty("clientStatus", result.getProperty("status"));
                persist("PASS".equals(result.getProperty("status")) ? "PASS" : "FAIL");
                done = true; call(host, "func_71263_m");
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private void preparePlatform(Object world) throws Exception {
        call(Class.forName("com.miaokatze.gtmb.hologram.HologramCutChunks"), "load", world, 12, 12, 9, 9);
        Object registry = get(Class.forName("net.minecraft.block.Block"), "field_149771_c");
        Object stone = call(registry, "func_82594_a", "minecraft:stone");
        Object air = call(registry, "func_82594_a", "minecraft:air");
        for (int x = 12; x <= 20; x++) for (int z = 12; z <= 20; z++) {
            call(world, "func_147465_d", x, 119, z, stone, 0, 2);
            check(call(world, "func_147439_a", x, 119, z) == stone, "real platform block readback");
            for (int y = 120; y <= 123; y++) call(world, "func_147465_d", x, y, z, air, 0, 2);
        }
        // Standard spawn belongs to WorldInfo; World has no func_72950_a(int,int,int) SRG method.
        Object worldInfo = call(world, "func_72912_H");
        call(worldInfo, "func_76081_a", 16, 120, 16);
        check((Integer) call(worldInfo, "func_76079_c") == 16
            && (Integer) call(worldInfo, "func_76075_d") == 120
            && (Integer) call(worldInfo, "func_76074_e") == 16, "standard WorldInfo spawn readback");
        proof.setProperty("platformNativeBlockReadback", "81 stone blocks at y119; clear y120..123");
        proof.setProperty("standardWorldSpawn", "16,120,16; WorldInfo.func_76081_a; native getter readback");
        platformReady = true;
    }

    private void createNativeCells(Object world) throws Exception {
        // Explicit production loading rejects the shared EmptyChunk and validates every requested coordinate.
        // Repeat each construction tick so an unload between ticks cannot turn writes into empty-chunk writes.
        call(Class.forName("com.miaokatze.gtmb.hologram.HologramCutChunks"), "load", world, 0, 0, 32, 32);
        proof.setProperty("fixtureChunkLoader", "production HologramCutChunks.load each construction tick; non-EmptyChunk/coordinates/identity checked");
        Object registry = get(Class.forName("net.minecraft.block.Block"), "field_149771_c");
        Object[] blocks = { call(registry, "func_82594_a", "minecraft:stone"), call(registry, "func_82594_a", "minecraft:glass"), call(registry, "func_82594_a", "minecraft:brick_block") };
        if (preset == null) {
            preset = construct("com.miaokatze.gtmb.hologram.HologramPresets$Preset");
            set(preset, "schema", 2); set(preset, "controllerType", "area");
            set(preset, "id", "local_00000000-0000-0000-0000-000000000019");
            set(preset, "name", "V019 constructed native download benchmark");
            set(preset, "authorName", "GTMB Native Benchmark"); set(preset, "authorUuid", "00000000-0000-0000-0000-000000000019");
            set(preset, "createdAt", 1700000000000L);
            call(Class.forName("com.miaokatze.gtmb.hologram.HologramBlueprintVersions"), "stamp", preset);
        }
        List<Object> cells = (List<Object>) get(preset, "cells");
        int end = Math.min(32768, placed + 512);
        for (; placed < end; placed++) {
            int x = placed & 31, y = 80 + (placed >> 10), z = (placed >> 5) & 31;
            Object block = blocks[(placed % 17 == 0) ? 1 : (placed % 29 == 0) ? 2 : 0];
            // Bounded native writes are outside the timed transfer and touch only this disposable world's cells.
            call(world, "func_147465_d", x, y, z, block, 0, 2);
            Object actual = call(world, "func_147439_a", x, y, z);
            int meta = (Integer) call(world, "func_72805_g", x, y, z);
            check(actual == block && meta == 0, "actual native block/meta readback");
            Object cell = call(Class.forName("com.miaokatze.gtmb.hologram.HologramPresets"), "cell",
                construct("net.minecraft.item.ItemStack", actual), x, y - 80, z, -1, false);
            set(cell, "block", call(registry, "func_148750_c", actual)); set(cell, "metadata", meta);
            cells.add(cell);
        }
        if (placed == 32768) {
            byte[] bytes = (byte[]) call(Class.forName("com.miaokatze.gtmb.hologram.HologramServerLibrary"), "encodeShare", preset);
            Files.write(file("benchmark-source.json").toPath(), bytes);
            finishSource(bytes, false);
        }
    }

    private void finishSource(byte[] bytes, boolean reused) throws Exception {
        byte[] reencoded = (byte[]) call(Class.forName("com.miaokatze.gtmb.hologram.HologramServerLibrary"), "encodeShare", preset);
        check(Arrays.equals(bytes, reencoded), "actual encoder preserves the identical baseline payload");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) { gzip.write(bytes); }
        proof.setProperty("sourcePath", checkedSource().getCanonicalPath());
        proof.setProperty("sourceReusedUnmodified", String.valueOf(reused));
        proof.setProperty("sourceSha256", sha(bytes)); proof.setProperty("rawBytes", String.valueOf(bytes.length));
        proof.setProperty("wholeGzipBytes", String.valueOf(compressed.size()));
        proof.setProperty("wholeGzipRatio", String.valueOf((double) bytes.length / compressed.size()));
        proof.setProperty("nativeWorldReadbackCells", reused ? "baseline source reused" : "32768");
        Properties p = new Properties(); p.putAll(proof); p.setProperty("ready", "true"); store("download-source-ready.properties", p);
        sourceReady = true; ticks = 0; persist("SOURCE_READY");
        System.out.println("[GTMB-DOWNLOAD-NATIVE] actual encoder raw=" + bytes.length + " gzip=" + compressed.size()
            + " ratio=" + (double) bytes.length / compressed.size());
    }

    private File checkedSource() throws Exception {
        String path = System.getProperty("gtmb.v019.downloadSource", "");
        File source = path.isEmpty() ? file("benchmark-source.json") : new File(path).getCanonicalFile();
        check(source.getCanonicalFile().toPath().startsWith(evidenceRoot.toPath()), "source must be an owned benchmark artifact");
        return source;
    }

    private synchronized void fail(Throwable failure) {
        failure.printStackTrace();
        try { proof.setProperty("failure", failure.toString()); persist("FAIL"); } catch (Exception e) { e.printStackTrace(); }
        done = true; diskDone = true;
        try {
            if (client) call(mc == null ? call(Class.forName("net.minecraft.client.Minecraft"), "func_71410_x") : mc,
                "func_71400_g");
            if (server) call(call(Class.forName("net.minecraft.server.MinecraftServer"), "func_71276_C"), "func_71263_m");
        } catch (Exception shutdown) { shutdown.printStackTrace(); }
    }
    private File file(String name) { return new File(output, name); }
    private Properties read(String name) throws Exception {
        Properties p = new Properties();
        try {
            if (file(name).isFile()) try (FileInputStream in = new FileInputStream(file(name))) { p.load(in); }
        } catch (java.io.FileNotFoundException replacedBetweenCheckAndOpen) {
            return new Properties(); // Own atomic receipt replacement may briefly remove the directory entry.
        }
        return p;
    }
    private synchronized void persist(String status) throws Exception {
        proof.setProperty("status", status); store(client ? "download-client.properties" : "download-server.properties", proof);
    }
    private void store(String name, Properties p) throws Exception {
        File temp = file(name + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) { p.store(out, "GTMB native download benchmark"); }
        for (int attempt = 0; ; attempt++) {
            try {
                Files.move(temp.toPath(), file(name).toPath(), StandardCopyOption.REPLACE_EXISTING);
                break;
            } catch (java.nio.file.FileSystemException sharing) {
                if (attempt >= 9 || !(sharing instanceof java.nio.file.AccessDeniedException
                    || sharing.getReason() != null && sharing.getReason().toLowerCase(java.util.Locale.ROOT).contains("being used")))
                    throw sharing;
                Thread.sleep(10L);
            }
        }
    }
    private static String sha(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(); for (byte b : hash) result.append(String.format("%02x", b & 255)); return result.toString();
    }
    private static void check(boolean ok, String message) { if (!ok) throw new IllegalStateException(message); }
    private static Field member(Class<?> type, String name) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (NoSuchFieldException ignored) { }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }
    private static Object get(Object o, String name) throws Exception { return member(o instanceof Class ? (Class<?>) o : o.getClass(), name).get(o instanceof Class ? null : o); }
    private static void set(Object o, String name, Object value) throws Exception { member(o.getClass(), name).set(o, value); }
    private static Object construct(String type, Object... args) throws Exception {
        for (Constructor<?> c : Class.forName(type).getDeclaredConstructors()) if (compatible(c.getParameterTypes(), args)) { c.setAccessible(true); return c.newInstance(args); }
        throw new NoSuchMethodException(type + " constructor " + args.length);
    }
    private static Object call(Object o, String name, Object... args) throws Exception {
        Class<?> type = o instanceof Class ? (Class<?>) o : o.getClass();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods())
            if (m.getName().equals(name) && compatible(m.getParameterTypes(), args)) { m.setAccessible(true); return m.invoke(o instanceof Class ? null : o, args); }
        throw new NoSuchMethodException(type.getName() + "." + name + " args=" + args.length);
    }
    private static boolean compatible(Class<?>[] types, Object[] values) {
        if (types.length != values.length) return false;
        for (int i = 0; i < types.length; i++) {
            if (values[i] == null) { if (types[i].isPrimitive()) return false; continue; }
            Class<?> t = types[i]; if (t.isPrimitive()) t = t == int.class ? Integer.class : t == long.class ? Long.class : t == float.class ? Float.class : t == double.class ? Double.class : t == boolean.class ? Boolean.class : t;
            if (!t.isInstance(values[i])) return false;
        }
        return true;
    }
}
