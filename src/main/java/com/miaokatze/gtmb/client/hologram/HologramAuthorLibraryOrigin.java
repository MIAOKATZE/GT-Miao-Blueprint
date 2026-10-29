package com.miaokatze.gtmb.client.hologram;

import net.minecraft.nbt.NBTTagList;

/** Client navigation provenance, never a server admission or processing certificate. */
final class HologramAuthorLibraryOrigin {

    final String id;
    final boolean integrated;
    final long revision;
    final NBTTagList catalog;
    private final byte[] source;
    final String sourceHash;
    final String contentHash;

    HologramAuthorLibraryOrigin(String id, boolean integrated, long revision, NBTTagList catalog) {
        this(id, integrated, revision, catalog, null);
    }

    HologramAuthorLibraryOrigin(String id, boolean integrated, long revision, NBTTagList catalog, byte[] source) {
        if (id == null || id.isEmpty() || id.startsWith("draft_"))
            throw new IllegalArgumentException("A finished source requires a library identity");
        this.id = id;
        this.integrated = integrated;
        this.revision = revision;
        this.source = source == null ? null : source.clone();
        this.sourceHash = source == null ? "" : com.miaokatze.gtmb.hologram.HologramBlueprintArtifact.hash(source);
        String content = "";
        if (source != null) try {
            content = com.miaokatze.gtmb.hologram.HologramBlueprintContentHash.of(source);
        } catch (java.io.IOException legacy) {
            // Legacy/raw library sources still use exact JSON identity; no invented content admission hash.
        }
        this.contentHash = content;
        this.catalog = catalog == null ? new NBTTagList() : (NBTTagList) catalog.copy();
    }

    byte[] source() {
        return source == null ? null : source.clone();
    }

    void requireSource(byte[] current) throws java.io.IOException {
        requireHash(com.miaokatze.gtmb.hologram.HologramBlueprintArtifact.hash(current));
    }

    void requireHash(String hash) throws java.io.IOException {
        if (sourceHash.isEmpty() || !sourceHash.equals(hash))
            throw new java.io.IOException("库蓝图来源已变化，请重新打开后再放置或覆盖；当前原稿保留");
    }

    boolean current(long value) {
        return revision == value;
    }

    boolean publicLibrary() {
        return id.startsWith("public_");
    }
}
