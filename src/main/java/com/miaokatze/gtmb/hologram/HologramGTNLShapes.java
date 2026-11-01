package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import com.gtnewhorizon.structurelib.structure.StructureUtility;

/** Only the five hash-bound rc2.1 resources are accepted; this is not a user-file decoder. */
final class HologramGTNLShapes {

    private static final Map<String, String[][]> CACHE = new HashMap<>();
    private static final String CORE_CHARS = "0123456789abcdefghijklmnopqrstuvwxyzDEFGHIJKLMNOPQRSTUVWYZ";

    private HologramGTNLShapes() {}

    static synchronized String[][] read(String name) throws IOException {
        String[][] cached = CACHE.get(name);
        if (cached != null) return copy(cached);
        String expected;
        int bytes, height, depth, width;
        switch (name) {
            case "assembler_matrix":
                expected = "77b2452124d7870b03fc1e7fba4e9e154323dc764bdc3b62e4009bb4ca2666ba";
                bytes = 424;
                height = depth = width = 9;
                break;
            case "large_steam_mixer":
                expected = "be7cd0987aa45e0c5135bb462f238b3c2158d93421823fd00a81f80335da0372";
                bytes = 379;
                height = depth = width = 7;
                break;
            case "atomic_energy_excitation_plant":
                expected = "b8fec59640ea1b6454bc36a4fec4e9af373f1800bee3d69e3eecf399aa8063d3";
                bytes = 4140;
                height = 29;
                depth = 23;
                width = 17;
                break;
            case "atomic_energy_excitation_plant_sphere":
                expected = "cd0c71a89c2d4ba090a3d623ba60e5b546b4bc37174dc01d9eb98af64ea3156c";
                bytes = 632;
                height = depth = width = 9;
                break;
            case "precise_assembler":
                expected = "f707ca3be74b262e0f0fa0d0f43c3c5256eadf20e175d669d8e2e10354955954";
                bytes = 210;
                height = depth = 5;
                width = 9;
                break;
            default:
                throw new IOException("GTNL_UNREVIEWED_RESOURCE " + name);
        }
        String path = "/assets/sciencenotleisure/multiblock/" + name + ".mbs";
        byte[] data;
        try (InputStream input = HologramGTNLShapes.class.getResourceAsStream(path)) {
            if (input == null) throw new IOException("GTNL_MISSING_RESOURCE " + name);
            ByteArrayOutputStream output = new ByteArrayOutputStream(bytes);
            byte[] buffer = new byte[1024];
            for (int n; (n = input.read(buffer)) != -1;) {
                if (output.size() + n > bytes) throw new IOException("GTNL_RESOURCE_SIZE " + name);
                output.write(buffer, 0, n);
            }
            data = output.toByteArray();
        }
        if (data.length != bytes || !expected.equals(sha256(data)))
            throw new IOException("GTNL_RESOURCE_IDENTITY " + name);
        String[][] decoded = decode(data, height, depth, width);
        CACHE.put(name, decoded);
        return copy(decoded);
    }

    static String[][] decode(byte[] data, int height, int depth, int width) throws IOException {
        if (data.length > 8192 || height < 1 || height > 32 || depth < 1 || depth > 32 || width < 1 || width > 32)
            throw new IOException("GTNL_RESOURCE_LIMIT");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            if (input.readInt() != 0x4d425331) throw new IOException("GTNL_RESOURCE_HEADER");
            int count = input.readInt();
            if (count < 1 || count > height * depth) throw new IOException("GTNL_RESOURCE_TABLE");
            String[] table = new String[count];
            for (int i = 0; i < count; i++) {
                int length = input.readInt();
                if (length != width) throw new IOException("GTNL_RESOURCE_WIDTH");
                byte[] text = new byte[length];
                input.readFully(text);
                for (byte value : text) if (value != ' ' && value != '~' && (value < 'A' || value > 'Z'))
                    throw new IOException("GTNL_RESOURCE_CHARACTER");
                table[i] = new String(text, StandardCharsets.US_ASCII);
            }
            if (input.readInt() != height) throw new IOException("GTNL_RESOURCE_HEIGHT");
            String[][] result = new String[height][depth];
            for (int row = 0; row < height; row++) {
                if (input.readInt() != depth) throw new IOException("GTNL_RESOURCE_DEPTH");
                for (int col = 0; col < depth; col++) {
                    int index = input.readInt();
                    if (index < 0 || index >= count) throw new IOException("GTNL_RESOURCE_INDEX");
                    result[row][col] = table[index];
                }
            }
            if (input.read() != -1) throw new IOException("GTNL_RESOURCE_TRAILING_DATA");
            return result;
        }
    }

    static String[][] structure(String name) throws IOException {
        return StructureUtility.transpose(read(name));
    }

    static String[][] sphere(boolean air) throws IOException {
        String[][] result = read("atomic_energy_excitation_plant_sphere");
        if (air) for (String[] row : result) for (int i = 0; i < row.length; i++) row[i] = row[i].replace('J', 'L')
            .replace('K', 'L');
        return StructureUtility.transpose(result);
    }

    static int nuclearTier(int value) {
        return Math.max(1, Math.min(3, value));
    }

    static int coreSize(int tier) {
        return 2 * nuclearTier(tier) + 1;
    }

    static char coreChar(int row, int col) {
        return CORE_CHARS.charAt(row * 7 + col);
    }

    static boolean coreCell(int tier, int row, int col) {
        int n = coreSize(tier);
        if (row < 0 || col < 0 || row >= n || col >= n) return false;
        int cut = nuclearTier(tier) - 1;
        return row + col >= cut && n - 1 - row + col >= cut
            && row + n - 1 - col >= cut
            && 2 * (n - 1) - row - col >= cut;
    }

    /** NuclearReactor.buildShape already returns StructureLib depth/row order; do not transpose it. */
    static String[][] nuclear(int rawTier) {
        int tier = nuclearTier(rawTier);
        int core = coreSize(tier);
        int width = core + 2;
        int center = width / 2;
        int pipeRadius = (core - 2) / 2;
        String[][] result = new String[width][5];
        for (int depth = 0; depth < width; depth++) {
            for (int row = 0; row < 5; row++) {
                char[] line = new char[width];
                java.util.Arrays.fill(line, 'X');
                if (depth == 0 || depth == width - 1) {
                    java.util.Arrays.fill(line, row == 0 ? 'X' : 'B');
                    line[0] = line[width - 1] = ' ';
                    if (depth == 0 && row == 2) line[center] = '~';
                } else if (row == 0) {
                    for (int col = 0; col < core; col++)
                        if (coreCell(tier, depth - 1, col)) line[col + 1] = coreChar(depth - 1, col);
                } else if (row == 4) {
                    java.util.Arrays.fill(line, 'B');
                } else {
                    java.util.Arrays.fill(line, 'B');
                    for (int col = 1; col < width - 1; col++)
                        line[col] = Math.abs(depth - center) <= pipeRadius && Math.abs(col - center) <= pipeRadius ? 'A'
                            : 'C';
                }
                result[depth][row] = new String(line);
            }
        }
        return result;
    }

    private static String[][] copy(String[][] input) {
        String[][] result = new String[input.length][];
        for (int i = 0; i < input.length; i++) result[i] = input[i].clone();
        return result;
    }

    private static String sha256(byte[] input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(input);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
