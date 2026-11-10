package com.miaokatze.gtmb.testlab;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

import net.minecraft.nbt.NBTTagCompound;

import com.miaokatze.gtmb.hologram.HologramNetwork;
import com.miaokatze.gtmb.hologram.HologramSnapshotAssembly;
import com.miaokatze.gtmb.hologram.HologramSnapshotCodec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

/** Production codec, packet frame and ordered assembly load in memory, without claiming real Netty traffic. */
final class TestLabCodecWork {

    private final byte[] payload;
    private final NBTTagCompound source = new NBTTagCompound();
    private final HologramSnapshotAssembly assembly = new HologramSnapshotAssembly();
    private byte[] packed;
    private int index, iteration;

    TestLabCodecWork(int payloadKiB) {
        payload = new byte[payloadKiB * 1024];
        new Random(0x47544d42L).nextBytes(payload);
        source.setByteArray("blob", payload);
        source.setString("scope", "testlab-memory-codec");
    }

    boolean step(TestLabReport report, int iterations) throws IOException {
        if (packed == null) {
            packed = HologramSnapshotCodec.compress(source);
            index = 0;
            report.count("codecCompressedBytes", packed.length);
        }
        int offset = index * HologramSnapshotAssembly.CHUNK_BYTES;
        byte[] slice = Arrays
            .copyOfRange(packed, offset, Math.min(packed.length, offset + HologramSnapshotAssembly.CHUNK_BYTES));
        ByteBuf input = Unpooled.buffer(20 + slice.length), output = Unpooled.buffer(20 + slice.length);
        try {
            input.writeLong(iteration);
            input.writeInt(packed.length);
            input.writeInt(index);
            input.writeInt(slice.length);
            input.writeBytes(slice);
            HologramNetwork.CutFragment frame = new HologramNetwork.CutFragment();
            frame.fromBytes(input);
            frame.toBytes(output);
            if (output.readLong() != iteration || output.readInt() != packed.length
                || output.readInt() != index
                || output.readInt() != slice.length) throw new IOException("Production frame header differs");
            byte[] decoded = new byte[slice.length];
            output.readBytes(decoded);
            if (!Arrays.equals(slice, decoded) || output.isReadable())
                throw new IOException("Production frame payload differs");
            report.count("codecFrames", 1);
            report.count("codecFrameBytes", 20L + slice.length);
            byte[] complete = assembly.accept(iteration, packed.length, index++, decoded, System.nanoTime());
            if (complete != null) {
                if (!Arrays.equals(packed, complete) || !Arrays.equals(
                    payload,
                    HologramSnapshotCodec.decode(complete)
                        .getByteArray("blob")))
                    throw new IOException("Production codec/assembly roundtrip differs");
                packed = null;
                iteration++;
                report.count("codecIterations", 1);
            }
        } finally {
            input.release();
            output.release();
        }
        return iteration >= iterations;
    }
}
