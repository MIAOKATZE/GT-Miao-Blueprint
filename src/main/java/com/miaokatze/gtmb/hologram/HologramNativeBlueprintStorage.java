// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCli;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintLimits;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;

/** The editor library's candidate/published boundary. A prepared pair is never a visible save receipt. */
public final class HologramNativeBlueprintStorage {
    private HologramNativeBlueprintStorage() {}

    public static final class Prepared {
        public final String logicalId, revision, pngHash;
        public final Path original, source, png;
        private final String originalHash, originalPngHash, lookup;
        private final Path root;
        private final NativeBlueprintPairStore.Receipt expected;
        private final boolean requireVacant;
        private final NativeBlueprintPairStore.LegacyMirrorProof mirror;

        private Prepared(Path root, String id, String lookup, Path original, String originalHash, String originalPngHash,
            NativeBlueprintPairStore.Receipt receipt, NativeBlueprintPairStore.Receipt expected,boolean requireVacant,NativeBlueprintPairStore.LegacyMirrorProof mirror) {
            this.root = root;
            this.expected=expected;this.requireVacant=requireVacant;this.mirror=mirror;
            logicalId = id;
            this.lookup = lookup;
            this.original = original;
            this.originalHash = originalHash;
            this.originalPngHash = originalPngHash;
            source = receipt.source;
            png = receipt.png;
            revision = receipt.physicalHash;
            pngHash = receipt.pngHash;
        }
    }

    private static String hash(Path path, int limit) throws IOException {
        return NativeBlueprintPairStore.hashFile(path,limit);
    }

    private static Path staging(Path root,String id,String sourceHash,String pngHash) {
        // Keep candidates detached from the active catalog and from newer revisions of the same alias.
        String generation=HologramBlueprintArtifact.hash((id+":"+sourceHash+":"+pngHash)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return root.resolve(".gtmb-native-staging").resolve(generation);
    }

    /** Explicit conversion only. Game and CLI share the parser, emitter, binding and publication code. */
    public static Prepared prepare(Path directory, String id, BooleanSupplier current) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        Path original = HologramBlueprintFiles.resolve(root,id);
        if(!Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS)
            && id.matches("local_[a-fA-F0-9-]{36}")) {
            // An unadopted portable file already has this display UUID. Adopt it before pinning.
            HologramBlueprintFiles.managed(root);
            original=HologramBlueprintFiles.resolve(root,id);
        }
        Path png = HologramBlueprintArtifact.previewPath(original);
        byte[] source=NativeBlueprintPairStore.readRegular(original,NativeBlueprintLimits.FILE_BYTES);
        byte[] image=NativeBlueprintPairStore.readRegular(png,HologramBlueprintArtifact.MAX_PNG_BYTES);
        String sourceHash=HologramBlueprintArtifact.hash(source),pngHash=HologramBlueprintArtifact.hash(image);
        String stableId=HologramBlueprintFiles.displayId(root,original,NativeBlueprintCli.legacyLogicalId(source));
        byte[] nativeBytes=NativeBlueprintCli.migratePair(source,image,-1,current).bytes;
        NativeBlueprintPairStore.Receipt receipt = NativeBlueprintPairStore.publish(
            staging(root,stableId,sourceHash,pngHash),stableId,nativeBytes,image,null,null,current);
        Prepared p = new Prepared(root,stableId,id,original,sourceHash,pngHash,receipt,null,false,legacyMirror(source,sourceHash,stableId));
        checkSlot(p, current);
        return p;
    }

    /** An already-frozen native revision and its corresponding capture. No JSON/Cell/Preset roundtrip. */
    public static Prepared prepareSave(Path directory, String id, NativeBlueprintDefinition frozen,
        byte[] freshlyCapturedPng, BooleanSupplier current, NativeBlueprintPairStore.Receipt expected,
        boolean requireVacant) throws IOException {
        if((expected==null)!=requireVacant)throw new IOException("Native save requires an exact current receipt or a vacant new slot");
        Path root = directory.toAbsolutePath().normalize();
        if(expected!=null&&!NativeBlueprintPairStore.sameReceipt(NativeBlueprintPairStore.receipt(root,id),expected))
            throw new IOException("蓝图或预览已被其他编辑更新；请重新打开");
        Path slot = HologramBlueprintFiles.resolve(root,id), png = HologramBlueprintArtifact.previewPath(slot);
        boolean exists = Files.exists(slot, LinkOption.NOFOLLOW_LINKS);
        if (requireVacant&&(exists||Files.exists(png,LinkOption.NOFOLLOW_LINKS)))throw new IOException("另存目标已存在");
        if(exists != Files.exists(png, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Incomplete existing blueprint pair");
        String oldHash = exists ? hash(slot,NativeBlueprintLimits.FILE_BYTES) : null;
        String oldImage = exists ? hash(png,HologramBlueprintArtifact.MAX_PNG_BYTES) : null;
        if(expected!=null&&(!expected.logicalId.equals(id)||!expected.source.equals(slot)||!expected.png.equals(png)
            ||!expected.physicalHash.equals(oldHash)||!expected.pngHash.equals(oldImage)))
            throw new IOException("蓝图或预览已被其他编辑更新；请重新打开");
        java.io.ByteArrayOutputStream source = new ByteArrayOutputStream();
        frozen.copyTo(source);
        byte[] bytes = source.toByteArray();
        if(!frozen.revision().equals(HologramBlueprintArtifact.hash(bytes))) throw new IOException("Frozen revision changed");
        NativeBlueprintPairStore.Receipt receipt = NativeBlueprintPairStore.publish(staging(root,id,frozen.revision(),HologramBlueprintArtifact.hash(freshlyCapturedPng)),id,bytes,
            freshlyCapturedPng,null,null,current);
        Prepared p = new Prepared(root,id,id,slot,oldHash,oldImage,receipt,expected,requireVacant,null);
        checkSlot(p,current);
        return p;
    }

    private static NativeBlueprintPairStore.LegacyMirrorProof legacyMirror(byte[] source,String hash,String id) {
        try{
            var root=HologramBlueprintCompactCodec.parseStored(source,NativeBlueprintLimits.FILE_BYTES).root();
            var member=HologramBlueprintCompactCodec.documentBlueprint(root);
            String digest=com.miaokatze.gtmb.hologram.gtnb.LegacyBlueprintMirror.digest(root,member,id);
            return digest.isEmpty()?null:new NativeBlueprintPairStore.LegacyMirrorProof(hash,digest);
        }catch(IOException|RuntimeException invalid){
            return null; // Archival admission must not erase/guess unsupported legacy business data.
        }
    }

    /** Compatibility for native candidates published before the mirror provenance field existed. */
    public static NativeBlueprintPairStore.LegacyMirrorProof recoverMirror(Path directory,NativeBlueprintPairStore.Receipt selected){
        if(selected==null)return null;
        try{
            Path retained=NativeBlueprintPairStore.retainedOriginal(directory,selected);
            if(retained==null)return null;
            byte[] source=NativeBlueprintPairStore.readRegular(retained,NativeBlueprintLimits.FILE_BYTES);
            return legacyMirror(source,HologramBlueprintArtifact.hash(source),selected.logicalId);
        }catch(IOException|RuntimeException invalid){return null;}
    }

    public static void delete(Path directory,NativeBlueprintPairStore.Receipt selected,
        NativeBlueprintPairStore.Fault fault)throws IOException{
        var visibility=NativeBlueprintPairStore.collectionVisibility(directory);
        var recovered=visibility.activeProof(com.miaokatze.gtmb.hologram.gtnb.LegacyBlueprintMirror.logicalId(selected.logicalId))
            ? null : recoverMirror(directory,selected);
        NativeBlueprintPairStore.delete(directory,selected,fault,recovered);
    }

    private static void checkSlot(Prepared p, BooleanSupplier current) throws IOException {
        if(!current.getAsBoolean() || !p.original.equals(HologramBlueprintFiles.resolve(p.root,p.lookup)))
            throw new IOException("Native blueprint owner or save slot changed");
        Path image = HologramBlueprintArtifact.previewPath(p.original);
        if(p.originalHash == null) {
            if(Files.exists(p.original,LinkOption.NOFOLLOW_LINKS) || Files.exists(image,LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Native blueprint destination was occupied");
        } else if(!p.originalHash.equals(hash(p.original,NativeBlueprintLimits.FILE_BYTES))
            || !p.originalPngHash.equals(hash(image,HologramBlueprintArtifact.MAX_PNG_BYTES)))
            throw new IOException("Original blueprint or preview changed");
    }

    /** Pin by receipt, not by a mutable staging alias that may have been overwritten by a newer save. */
    public static NativeBlueprintDefinition openPrepared(Prepared p) throws IOException {
        return NativeBlueprintPairStore.openExact(p.source,p.png,p.revision,p.pngHash);
    }

    /** A caller must have consumed this exact prepared revision before making it the active generation. */
    public static NativeBlueprintPairStore.Receipt activate(Path directory, Prepared p, String acceptedRevision,
        BooleanSupplier ownerCurrent) throws IOException {
        return activate(directory,p,acceptedRevision,ownerCurrent,s -> {});
    }

    public static NativeBlueprintPairStore.Receipt activate(Path directory, Prepared p, String acceptedRevision,
        BooleanSupplier ownerCurrent, NativeBlueprintPairStore.Fault fault) throws IOException {
        if(!p.root.equals(directory.toAbsolutePath().normalize()) || !p.revision.equals(acceptedRevision))
            throw new IOException("Wrong native blueprint destination or accepted revision");
        synchronized(HologramBlueprintFiles.class) {
            checkSlot(p,ownerCurrent);
            String[] aliases=HologramBlueprintFiles.nativePublicationAliases(p.root,p.original,p.lookup);
            try(NativeBlueprintDefinition ignored = openPrepared(p)) {
                byte[] source = NativeBlueprintPairStore.readRegular(p.source,NativeBlueprintLimits.FILE_BYTES);
                byte[] image = NativeBlueprintPairStore.readRegular(p.png,HologramBlueprintArtifact.MAX_PNG_BYTES);
                if(!p.revision.equals(HologramBlueprintArtifact.hash(source)) || !p.pngHash.equals(HologramBlueprintArtifact.hash(image)))
                    throw new IOException("Prepared blueprint pair changed");
                return NativeBlueprintPairStore.publishCandidate(p.root,p.logicalId,source,image,
                    p.originalHash==null?null:p.original,p.originalHash,ownerCurrent,s -> {
                        fault.at(s);
                        if(s.equals("before-catalog")) checkSlot(p,ownerCurrent);
                    },p.expected,p.requireVacant,p.mirror,aliases);
            }
        }
    }
}
