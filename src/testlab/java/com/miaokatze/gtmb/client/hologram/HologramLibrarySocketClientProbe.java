package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.miaokatze.gtmb.hologram.HologramLibraryDownload;
import com.miaokatze.gtmb.hologram.HologramLibraryProtocol;
import com.miaokatze.gtmb.hologram.HologramNetwork;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Independent optional addition to the existing real socket driver; never invokes planner or GUI internals. */
public final class HologramLibrarySocketClientProbe {

    private long started;
    private int stage;
    private String transfer, publicId, downloadId, downloadHash;
    private String downloadEncoding;
    private int decodedTotal;
    private byte[] fixture;
    private int outgoingIndex, outgoingOffset, incomingIndex, incomingTotal;
    private ByteArrayOutputStream incoming;
    private Path receipts;
    private boolean done;

    private HologramLibrarySocketClientProbe() {}

    public static void install() {
        if (!Boolean.getBoolean("gtmb.hologram.librarySocketSmoke")) return;
        HologramLibrarySocketClientProbe probe = new HologramLibrarySocketClientProbe();
        HologramNetwork.setLibraryProbeReceiver(
            reply -> Minecraft.getMinecraft()
                .func_152344_a(() -> probe.receive(reply)));
        FMLCommonHandler.instance()
            .bus()
            .register(probe);
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (done || event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.thePlayer == null || mc.getNetHandler() == null) return;
        try {
            if (mc.getNetHandler()
                .getNetworkManager()
                .isLocalChannel()) throw new IllegalStateException("Probe requires real dedicated socket");
            if (stage == 0) {
                receipts = Paths.get(System.getProperty("gtmb.hologram.libraryReceiptDir", ""))
                    .toRealPath();
                if (!receipts.getFileName()
                    .toString()
                    .equals("receipts")
                    || !receipts.toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("hologram"))
                    throw new IllegalStateException("Prepared isolated receipts directory required");
                started = System.nanoTime();
                stage = 1;
                HologramNetwork.sendLibrary(HologramLibraryProtocol.request("configGet"));
            } else if (System.nanoTime() - started > 120000000000L)
                throw new IllegalStateException("Library real socket timeout at stage " + stage);
        } catch (Exception failure) {
            fail(failure);
        }
    }

    private void receive(NBTTagCompound reply) {
        if (done || stage == 0) return;
        try {
            String op = reply.getString("op");
            if (stage == 1 && "config".equals(op)) {
                if (reply.getInteger("uploadKiB") < 1 || reply.getInteger("downloadKiB") < 1)
                    throw new IllegalStateException("Invalid config DTO");
                String json = "{\"schema\":2,\"blueprint\":{\"schema\":2,\"id\":\"local_" + UUID.randomUUID()
                    + "\",\"name\":\"LibrarySocketFixture\",\"controllerType\":\"area\",\"authorName\":\"DeclaredCreator\",\"cells\":[{\"item\":\"minecraft:stone\",\"block\":\"minecraft:stone\",\"damage\":0,\"metadata\":0,\"count\":1,\"x\":0,\"y\":0,\"z\":0,\"facing\":-1}],\"palette\":[],\"structures\":[],\"hintAreas\":[],\"modVersions\":{}}}";
                fixture = json.getBytes(StandardCharsets.UTF_8);
                stage = 2;
                begin(false);
            } else if ((stage == 2 || stage == 5) && "uploadReady".equals(op)
                && transfer.equals(reply.getString("transfer"))) sendChunk();
            else if ((stage == 2 || stage == 5) && "chunkAck".equals(op)
                && transfer.equals(reply.getString("transfer"))) sendChunk();
            else if (stage == 2 && "uploaded".equals(op) && transfer.equals(reply.getString("transfer"))) {
                publicId = reply.getString("id");
                stage = 3;
                NBTTagCompound request = HologramLibraryProtocol.request("download");
                request.setString("id", publicId);
                HologramNetwork.sendLibrary(request);
            } else if (stage == 3 && "downloadBegin".equals(op) && publicId.equals(reply.getString("id"))) {
                downloadId = reply.getString("transfer");
                downloadHash = reply.getString("hash");
                downloadEncoding = reply.getString("encoding");
                decodedTotal = reply.getInteger("decodedTotal");
                incomingTotal = reply.getInteger("total");
                if (incomingTotal <= 0 || incomingTotal > HologramLibraryProtocol.MAX_BYTES)
                    throw new IllegalStateException("Invalid download size");
                incoming = new ByteArrayOutputStream();
                incomingIndex = 0;
                stage = 4;
            } else if (stage == 4 && "downloadChunk".equals(op) && downloadId.equals(reply.getString("transfer"))) {
                byte[] bytes = reply.getByteArray("data");
                if (reply.getInteger("index") != incomingIndex++
                    || bytes.length != Math.min(HologramLibraryProtocol.CHUNK_BYTES, incomingTotal - incoming.size()))
                    throw new IllegalStateException("Bad downloaded chunk");
                incoming.write(bytes, 0, bytes.length);
            } else if (stage == 4 && "downloadDone".equals(op) && downloadId.equals(reply.getString("transfer"))) {
                if (incoming.size() != incomingTotal || !hash(
                    HologramLibraryDownload
                        .decode(incoming.toByteArray(), downloadEncoding, decodedTotal, downloadHash))
                            .equals(downloadHash))
                    throw new IllegalStateException("Downloaded hash mismatch");
                stage = 5;
                begin(true);
            } else if (stage == 5 && "error".equals(op)
                && reply.getString("message")
                    .contains("hash mismatch")) {
                        stage = 6;
                        NBTTagCompound delete = HologramLibraryProtocol.request("delete");
                        delete.setString("id", publicId);
                        HologramNetwork.sendLibrary(delete);
                    } else
                if (stage == 6 && "deleted".equals(op) && publicId.equals(reply.getString("id"))) {
                    stage = 7;
                    HologramNetwork.sendLibrary(HologramLibraryProtocol.request("list"));
                } else if (stage == 7 && "list".equals(op)) {
                    NBTTagList entries = reply.getTagList("entries", 10);
                    for (int i = 0; i < entries.tagCount(); i++) if (publicId.equals(
                        entries.getCompoundTagAt(i)
                            .getString("id")))
                        throw new IllegalStateException("Deleted public blueprint remained in page");
                    Files.write(
                        receipts.resolve("library-socket-client-pass.txt"),
                        ("PASS real dedicated socket config/upload/download SHA256/wrong-hash rejection/own deletion "
                            + publicId
                            + "\n").getBytes(StandardCharsets.UTF_8));
                    done = true;
                    System.out.println("[GTMB-LIBRARY-SOCKET-CLIENT] PASS");
                } else
                    if ("error".equals(op) && stage != 5) throw new IllegalStateException(reply.getString("message"));
        } catch (Exception failure) {
            fail(failure);
        }
    }

    private void begin(boolean wrongHash) throws Exception {
        transfer = UUID.randomUUID()
            .toString();
        outgoingIndex = outgoingOffset = 0;
        NBTTagCompound begin = HologramLibraryProtocol.request("uploadBegin");
        begin.setString("transfer", transfer);
        begin.setInteger("total", fixture.length);
        begin.setString(
            "hash",
            wrongHash ? "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" : hash(fixture));
        HologramNetwork.sendLibrary(begin);
    }

    private void sendChunk() {
        if (outgoingOffset >= fixture.length) return;
        int end = Math.min(fixture.length, outgoingOffset + HologramLibraryProtocol.CHUNK_BYTES);
        NBTTagCompound chunk = HologramLibraryProtocol.request("uploadChunk");
        chunk.setString("transfer", transfer);
        chunk.setInteger("index", outgoingIndex++);
        chunk.setByteArray("data", java.util.Arrays.copyOfRange(fixture, outgoingOffset, end));
        outgoingOffset = end;
        HologramNetwork.sendLibrary(chunk);
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hash = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256")
            .digest(bytes)) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return hash.toString();
    }

    private void fail(Exception failure) {
        done = true;
        failure.printStackTrace();
        try {
            if (receipts != null) Files.write(
                receipts.resolve("library-socket-client-failed.txt"),
                failure.toString()
                    .getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        System.err.println("[GTMB-LIBRARY-SOCKET-CLIENT] FAIL " + failure);
    }
}
