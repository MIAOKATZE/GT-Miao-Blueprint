// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** The Cut reply is small metadata, never a generic snapshot or a raw clipboard record. */
final class HologramCutReplyCodec {

    // 64 rows * 373 maximum NBT bytes + 1,948 envelope bytes = 25,820, even with all optional fields.
    static final int MAX_RAW_BYTES = 26 * 1024;
    static final int MAX_COMPRESSED_BYTES = 28 * 1024;
    static final int HEADER_BYTES = 20;
    // 28,693 bytes including this header and FML's discriminator; Forge S3F caps its payload at 2,097,050.
    static final int MAX_PACKET_BYTES = 1 + HEADER_BYTES + MAX_COMPRESSED_BYTES;
    private static final int MAX_MESSAGE = 512;
    private static final int MAX_CODE = 32;
    private static final int MAX_KEY = 22;
    private static final String BOUNDS_VERSION = "cutBoundsProtocol";
    private static final String BOUNDS_TOKEN = "cutBoundsToken";
    private static final long COUNTER_MASK = 0x0000ffffffffffffL;
    // Mark the existing positive sequence, not a new discriminator or a wider header.
    private static final long METADATA_FRAME = 0x4355000000000000L;

    private HologramCutReplyCodec() {}

    static long markSequence(long serial) {
        if (serial <= 0 || serial > COUNTER_MASK) throw new IllegalArgumentException("Cut reply sequence");
        return METADATA_FRAME | serial;
    }

    static boolean marked(long sequence) {
        return (sequence & ~COUNTER_MASK) == METADATA_FRAME && (sequence & COUNTER_MASK) != 0;
    }

    static boolean fitsFrame(int bytes) {
        return bytes > 0 && bytes <= MAX_COMPRESSED_BYTES;
    }

    static byte[] encode(NBTTagCompound reply) throws IOException {
        NBTTagCompound owned = copy(reply, false);
        validateRoot(owned);
        return HologramSnapshotCodec.compress(owned, MAX_RAW_BYTES, MAX_COMPRESSED_BYTES, () -> true);
    }

    static NBTTagCompound decode(byte[] compressed) throws IOException {
        if (!fitsFrame(compressed.length) || compressed.length <= 18 || (compressed[0] & 255) != 0x1f
            || (compressed[1] & 255) != 0x8b || compressed[2] != 8 || compressed[3] != 0)
            throw invalid();
        byte[] raw = new byte[MAX_RAW_BYTES + 1];
        int trailer = compressed.length - 8, written = 0;
        Inflater inflater = new Inflater(true);
        try {
            // Raw zlib may look ahead one byte. Feed the trailer but require it all to remain unconsumed.
            inflater.setInput(compressed, 10, compressed.length - 10);
            while (!inflater.finished()) {
                int count = inflater.inflate(raw, written, raw.length - written);
                written += count;
                if (written > MAX_RAW_BYTES || count == 0 && !inflater.finished()) throw invalid();
            }
            if (inflater.getRemaining() != 8) throw invalid();
        } catch (DataFormatException malformed) {
            throw new IOException("Invalid Cut reply compression", malformed);
        } finally {
            inflater.end();
        }
        CRC32 crc = new CRC32();
        crc.update(raw, 0, written);
        if (crc.getValue() != littleWord(compressed, trailer) || written != littleWord(compressed, trailer + 4))
            throw invalid();
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw, 0, written))) {
            if (input.readUnsignedByte() != 10 || !readString(input, 0).isEmpty()) throw invalid();
            NBTTagCompound reply = readCompound(input, false);
            if (input.read() != -1) throw invalid();
            validateRoot(reply);
            return reply;
        }
    }

    private static long littleWord(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
            | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }

    private static int fieldType(String key, boolean row) {
        if (row) {
            switch (key) {
                case "id":
                case "name":
                    return 8;
                case "sequence":
                    return 4;
                case "x":
                case "y":
                case "z":
                case "dimension":
                case "sizeX":
                case "sizeY":
                case "sizeZ":
                case "blocks":
                case "selectedCells":
                    return 3;
                default:
                    return -1;
            }
        }
        switch (key) {
            case "action":
            case "requestId":
            case "id":
            case "reconciledRequestId":
            case "code":
            case "message":
            case BOUNDS_TOKEN:
                return 8;
            case BOUNDS_VERSION:
                return 3;
            case "success":
            case "requestCompleted":
            case "demolitionActive":
            case HologramCutNaming.SKIP_PROMPT:
            case HologramCutPlacementPreference.SKIP_PROMPT:
                return 1;
            case "entries":
                return 9;
            default:
                return -1;
        }
    }

    private static NBTTagCompound copy(NBTTagCompound source, boolean row) throws IOException {
        NBTTagCompound out = new NBTTagCompound();
        for (Object name : source.func_150296_c()) {
            String key = (String) name;
            int type = fieldType(key, row);
            if (type < 0 || !source.hasKey(key, type)) throw invalid();
            switch (type) {
                case 1:
                    out.setByte(key, hBoolean(source.getByte(key)));
                    break;
                case 3:
                    out.setInteger(key, source.getInteger(key));
                    break;
                case 4:
                    out.setLong(key, positive(source.getLong(key)));
                    break;
                case 8:
                    String value = source.getString(key);
                    if ("message".equals(key)) value = boundedNotice(value);
                    out.setString(key, checkedString(key, value));
                    break;
                case 9:
                    NBTTagList entries = (NBTTagList) source.getTag(key), clean = new NBTTagList();
                    int count = entries.tagCount();
                    checkList(entries.func_150303_d(), count);
                    for (int i = 0; i < count; i++) clean.appendTag(copy(entries.getCompoundTagAt(i), true));
                    out.setTag(key, clean);
                    break;
                default:
                    throw invalid();
            }
        }
        return out;
    }

    private static NBTTagCompound readCompound(DataInputStream input, boolean row) throws IOException {
        NBTTagCompound out = new NBTTagCompound();
        for (int type = input.readUnsignedByte(); type != 0; type = input.readUnsignedByte()) {
            String key = readString(input, MAX_KEY);
            // Never interpret the value or allocate a nested body for an unknown/duplicate field.
            if (type != fieldType(key, row) || out.hasKey(key)) throw invalid();
            switch (type) {
                case 1:
                    out.setByte(key, hBoolean(input.readByte()));
                    break;
                case 3:
                    out.setInteger(key, input.readInt());
                    break;
                case 4:
                    out.setLong(key, positive(input.readLong()));
                    break;
                case 8:
                    out.setString(key, checkedString(key, readString(input, stringLimit(key))));
                    break;
                case 9:
                    int element = input.readUnsignedByte(), count = input.readInt();
                    checkList(element, count);
                    NBTTagList entries = new NBTTagList();
                    for (int i = 0; i < count; i++) entries.appendTag(readCompound(input, true));
                    out.setTag(key, entries);
                    break;
                default:
                    throw invalid();
            }
        }
        return out;
    }

    private static void validateRoot(NBTTagCompound reply) throws IOException {
        if (!"state".equals(reply.getString("action")) || !reply.hasKey("success", 1)
            || !HologramCutEntry.canonicalId(reply.getString("requestId"))) throw invalid();
        if (reply.hasKey(BOUNDS_VERSION) || reply.hasKey(BOUNDS_TOKEN))
            if (!reply.getBoolean("success") || !reply.hasKey("entries", 9)
                || HologramCutProtocol.boundsProof(reply) == null) throw invalid();
        if (reply.hasKey("reconciledRequestId") != reply.hasKey("requestCompleted")) throw invalid();
    }

    private static void checkList(int element, int count) throws IOException {
        if (count < 0 || count > HologramCutProtocol.MAX_ENTRIES || element != 10 && (element != 0 || count != 0))
            throw invalid();
    }

    private static byte hBoolean(byte value) throws IOException {
        if (value != 0 && value != 1) throw invalid();
        return value;
    }

    private static long positive(long value) throws IOException {
        if (value <= 0) throw invalid();
        return value;
    }

    private static String boundedNotice(String value) {
        if (value.length() <= MAX_MESSAGE) return value;
        int end = MAX_MESSAGE - 1;
        if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end) + "…";
    }

    private static int stringLimit(String key) {
        switch (key) {
            case "action": return 5;
            case "code": return MAX_CODE;
            case "message": return MAX_MESSAGE;
            case "name": return HologramCutNaming.MAX_LENGTH;
            default: return 36;
        }
    }

    private static String checkedString(String key, String value) throws IOException {
        if (value.length() > stringLimit(key)) throw invalid();
        switch (key) {
            case "action":
                if (!"state".equals(value)) throw invalid();
                break;
            case "id":
            case "requestId":
            case "reconciledRequestId":
            case BOUNDS_TOKEN:
                if (!HologramCutEntry.canonicalId(value)) throw invalid();
                break;
            case "name":
                if (!value.equals(HologramCutNaming.checkedName(value))) throw invalid();
                break;
            case "code":
                if (value.isEmpty()) throw invalid();
                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);
                    if (!('a' <= c && c <= 'z' || '0' <= c && c <= '9' || c == '_')) throw invalid();
                }
                break;
            default:
                break;
        }
        return value;
    }

    private static String readString(DataInputStream input, int maxChars) throws IOException {
        int length = input.readUnsignedShort();
        if (length > maxChars * 3) throw invalid();
        byte[] bounded = new byte[length + 2];
        bounded[0] = (byte) (length >>> 8);
        bounded[1] = (byte) length;
        input.readFully(bounded, 2, length);
        try (DataInputStream chars = new DataInputStream(new ByteArrayInputStream(bounded))) {
            String value = chars.readUTF();
            if (value.length() > maxChars) throw invalid();
            return value;
        }
    }

    private static IOException invalid() {
        return new IOException("Invalid Cut metadata reply");
    }
}
