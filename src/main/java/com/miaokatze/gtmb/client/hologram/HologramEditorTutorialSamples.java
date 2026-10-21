package com.miaokatze.gtmb.client.hologram;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import net.minecraft.nbt.JsonToNBT;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/** Version-bound, read-only observations from the owned native editor fixture. Never reads the player's library. */
final class HologramEditorTutorialSamples {

    enum Kind {
        PHYSICAL,
        AUTHOR
    }

    private static final String ROOT = "/assets/gtmb/tutorial/";
    private static final Map<Kind, Sample> CACHE = new HashMap<>();
    private static Properties manifest;

    private HologramEditorTutorialSamples() {}

    static synchronized NBTTagCompound snapshot(Kind kind) throws IOException {
        return (NBTTagCompound) sample(kind).data.copy();
    }

    static synchronized String primaryStructure(Kind kind) throws IOException {
        return sample(kind).primaryStructure;
    }

    static synchronized NBTTagCompound sameSourceMetadata(String id) throws IOException {
        Sample author = sample(Kind.AUTHOR), physical = sample(Kind.PHYSICAL);
        require(
            "true".equals(manifest.getProperty("composedSameSourceMetadata")) && author.primaryStructure.equals(id),
            "No observed source configuration for this member");
        NBTTagCompound declared = null;
        NBTTagList structures = author.data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) if (id.equals(
            structures.getCompoundTagAt(i)
                .getString("id")))
            declared = structures.getCompoundTagAt(i);
        require(
            declared != null && !physical.data.getString("sourceClass")
                .isEmpty()
                && physical.data.getString("sourceClass")
                    .equals(declared.getString("sourceClass")),
            "Recorded source classes differ");
        for (String axis : new String[] { "x", "y", "z" }) require(
            physical.data.getInteger(axis) == declared.getInteger(axis),
            "Recorded controller positions differ");
        NBTTagCompound before = controller(physical.data, true), after = controller(author.data, false);
        require(
            before != null && after != null
                && before.hasKey("targetStack", 10)
                && before.getCompoundTag("targetStack")
                    .equals(after.getCompoundTag("targetStack")),
            "Recorded controller device identities differ");
        for (String axis : new String[] { "x", "y", "z" }) require(
            before.getInteger(axis) == after.getInteger(axis)
                && before.getInteger(axis) == physical.data.getInteger(axis),
            "Recorded controller cells differ");
        return (NBTTagCompound) physical.data.copy();
    }

    private static NBTTagCompound controller(NBTTagCompound data, boolean physical) {
        NBTTagList cells = data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            if (physical ? cell.getBoolean("anchor") : "templateController".equals(cell.getString("role"))) return cell;
        }
        return null;
    }

    private static Sample sample(Kind kind) throws IOException {
        Sample existing = CACHE.get(kind);
        if (existing != null) return existing;
        if (manifest == null) {
            Properties loaded = properties(read("manifest.properties", 64 * 1024));
            require("1".equals(loaded.getProperty("format")), "Unsupported tutorial observation format");
            String pack = required(loaded, "gtnhVersion");
            if (!"notRecorded".equals(pack)) try {
                Class<?> source = Class.forName(
                    "com.dreammaster.coremod.DreamCoreMod",
                    false,
                    HologramEditorTutorialSamples.class.getClassLoader());
                require(
                    pack.equals(
                        source.getField("displayedModpackVersion")
                            .get(null)),
                    "Tutorial observations do not match this GTNH version");
            } catch (ReflectiveOperationException | LinkageError failure) {
                throw new IOException("Cannot verify the tutorial observation's GTNH version", failure);
            }
            manifest = loaded;
        }
        String prefix = kind.name()
            .toLowerCase(Locale.ROOT) + ".";
        String file = required(manifest, prefix + "snapshot");
        int bytes = number(manifest, prefix + "bytes");
        byte[] encoded = read(file, bytes);
        require(encoded.length == bytes, "Tutorial observation byte count changed");
        String digest = required(manifest, prefix + "sha256");
        require(digest.equals(sha256(encoded)), "Tutorial observation digest changed");
        byte[] evidence = read(required(manifest, prefix + "provenance"), 64 * 1024);
        require(
            required(manifest, prefix + "provenanceSha256").equals(sha256(evidence)),
            "Tutorial provenance digest changed");
        Properties provenance = properties(evidence);
        require(
            "true".equals(provenance.getProperty("fullData")) && "false".equals(provenance.getProperty("metadataOnly")),
            "Tutorial observation has no complete cells");
        require(digest.equals(provenance.getProperty("snapshotSha256")), "Tutorial snapshot provenance differs");
        require(
            required(manifest, prefix + "productSha256").equals(provenance.getProperty("productSha256")),
            "Tutorial producer jar provenance differs");
        for (String binding : new String[] { "coreSha256", "addonSha256" }) require(
            required(manifest, prefix + binding).equals(provenance.getProperty(binding)),
            "Tutorial runtime provenance differs: " + binding);
        require(bytes == number(provenance, "bytes"), "Tutorial provenance byte count differs");
        NBTTagCompound data;
        try {
            NBTBase decoded = JsonToNBT.func_150315_a(new String(encoded, StandardCharsets.UTF_8));
            require(decoded instanceof NBTTagCompound, "Tutorial observation must be a compound");
            data = (NBTTagCompound) decoded;
        } catch (Exception failure) {
            throw new IOException("Cannot decode the recorded tutorial observation", failure);
        }
        int cells = number(manifest, prefix + "cells");
        require(
            cells == number(provenance, "cells") && cells == number(provenance, "exportedCells"),
            "Tutorial provenance cell counts differ");
        require(
            data.hasKey("cells", 9) && data.getTagList("cells", 10)
                .tagCount() == cells && cells > 0,
            "Tutorial observation has incomplete geometry");
        require(data.getBoolean("virtual") == (kind != Kind.PHYSICAL), "Tutorial source context differs");
        String sourceClass = required(manifest, prefix + "sourceClass");
        String recordedClass = data.getString("sourceClass");
        if (kind != Kind.PHYSICAL) {
            NBTTagList sources = data.getTagList("structures", 10);
            for (int i = 0; i < sources.tagCount(); i++) if (manifest.getProperty(prefix + "primaryStructure", "")
                .equals(
                    sources.getCompoundTagAt(i)
                        .getString("id")))
                recordedClass = sources.getCompoundTagAt(i)
                    .getString("sourceClass");
        }
        require(sourceClass.equals(recordedClass), "Tutorial source declaration differs");
        try {
            Class.forName(sourceClass, false, HologramEditorTutorialSamples.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError missing) {
            throw new IOException("Tutorial machine class is unavailable", missing);
        }
        String primary = manifest.getProperty(prefix + "primaryStructure", "");
        validateStructureLinks(data, primary, number(manifest, prefix + "primaryCells"));
        int candidateCells = 0;
        NBTTagList rows = data.getTagList("cells", 10);
        for (int i = 0; i < rows.tagCount(); i++) if (rows.getCompoundTagAt(i)
            .getTagList("candidates", 10)
            .tagCount() > 0) candidateCells++;
        require(candidateCells == number(manifest, prefix + "candidateCells"), "Tutorial selected-cell details differ");
        if (kind == Kind.AUTHOR) require(candidateCells > 0, "Tutorial source has no recorded candidate details");
        require(
            data.getTagList("capabilities", 10)
                .tagCount() == number(manifest, prefix + "capabilities"),
            "Tutorial capability declarations differ");
        require(
            data.getCompoundTag("processing")
                .getInteger("requiredStages") == number(manifest, prefix + "requiredStages"),
            "Tutorial recorded processing stages differ");
        Sample loaded = new Sample(data, primary);
        CACHE.put(kind, loaded);
        return loaded;
    }

    private static void validateStructureLinks(NBTTagCompound data, String primary, int primaryCells)
        throws IOException {
        Set<String> declared = new HashSet<>();
        NBTTagList structures = data.getTagList("structures", 10);
        for (int i = 0; i < structures.tagCount(); i++) {
            String id = structures.getCompoundTagAt(i)
                .getString("id");
            require(!id.isEmpty() && declared.add(id), "Tutorial structure identities are missing or duplicated");
        }
        int members = 0;
        NBTTagList cells = data.getTagList("cells", 10);
        for (int i = 0; i < cells.tagCount(); i++) {
            NBTTagCompound cell = cells.getCompoundTagAt(i);
            String id = cell.getString("structureId");
            NBTTagList memberships = cell.getTagList("structureIds", 8);
            boolean primaryMember = !primary.isEmpty() && primary.equals(id);
            require(id.isEmpty() || declared.contains(id), "Tutorial cell references an unknown primary source");
            for (int j = 0; j < memberships.tagCount(); j++) {
                String member = memberships.getStringTagAt(j);
                require(declared.contains(member), "Tutorial cell references an unknown shared source");
                if (primary.equals(member)) primaryMember = true;
            }
            if (primaryMember) members++;
        }
        require(
            members == primaryCells && (primary.isEmpty() || declared.contains(primary) && members > 0),
            "Tutorial source membership does not match its recorded footprint");
    }

    private static byte[] read(String file, int limit) throws IOException {
        require(
            file.matches("[A-Za-z0-9_.-]+") && !file.contains("..") && limit > 0,
            "Invalid tutorial resource descriptor");
        try (InputStream input = HologramEditorTutorialSamples.class.getResourceAsStream(ROOT + file)) {
            if (input == null) throw new IOException("The recorded tutorial sample is unavailable: " + file);
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                require((long) output.size() + count <= limit, "Tutorial resource exceeded its recorded size");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static Properties properties(byte[] data) throws IOException {
        Properties result = new Properties();
        result.load(new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8));
        return result;
    }

    private static String required(Properties source, String key) throws IOException {
        String value = source.getProperty(key, "");
        require(!value.isEmpty(), "Missing tutorial provenance field: " + key);
        return value;
    }

    private static int number(Properties source, String key) throws IOException {
        try {
            return Integer.parseInt(required(source, key));
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid tutorial provenance count: " + key, invalid);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes);
            StringBuilder text = new StringBuilder();
            for (byte value : digest) text.append(String.format(Locale.ROOT, "%02x", value & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("SHA-256 unavailable", unavailable);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static final class Sample {

        final NBTTagCompound data;
        final String primaryStructure;

        Sample(NBTTagCompound data, String primaryStructure) {
            this.data = data;
            this.primaryStructure = primaryStructure;
        }
    }
}
