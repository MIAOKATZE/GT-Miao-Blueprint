// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import static com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintBytes.*;

/** Local publication journal. The catalog switch is the sole visibility point; preserved legacy originals stay inactive. */
public final class NativeBlueprintPairStore {
    private static final String CATALOG=".gtmb-native-catalog.properties",JOURNAL=".gtmb-native-pending-";
    private NativeBlueprintPairStore(){}
    public interface Fault {void at(String stage)throws IOException;}
    private static final Fault NONE=stage->{};
    private static final String ACTIVE_MIRROR=".legacyMirror",DELETED_MIRROR="legacy-mirror-deleted.";
    public static final class LegacyMirrorProof {
        public final String originHash,digest;
        public LegacyMirrorProof(String originHash,String digest){
            if(!sha(originHash)||!sha(digest))throw new IllegalArgumentException("legacy mirror SHA-256");
            this.originHash=originHash;this.digest=digest;
        }
    }
    private static boolean sha(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private static String legacyOrigin(NativeBlueprintDefinition definition)throws IOException {
        var meta=definition.metadata();var migration=meta.get("migration");
        if(migration==null||!migration.isJsonObject())return "";
        var value=migration.getAsJsonObject().get("sourceSha256");
        return value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?value.getAsString():"";
    }
    public static final class Receipt {
        public final Path source,png;public final String logicalId,physicalHash,contentHash,pngHash;
        Receipt(Path s,Path p,String id,String physical,String content,String image){source=s;png=p;logicalId=id;physicalHash=physical;contentHash=content;pngHash=image;}
    }
    public static Receipt publish(Path directory,String id,byte[] nativeBytes,byte[] png,Path previousSource,
        byte[] expectedPrevious,BooleanSupplier current)throws IOException {
        return publish(directory,id,nativeBytes,png,previousSource,expectedPrevious,current,NONE);
    }
    public static Receipt publish(Path directory,String id,byte[] nativeBytes,byte[] png,Path previousSource,
        byte[] expectedPrevious,BooleanSupplier current,Fault fault)throws IOException {
        return publishCandidate(directory,id,nativeBytes,png,previousSource,
            expectedPrevious==null?null:hash(expectedPrevious),current,fault);
    }
    public static synchronized Receipt publishCandidate(Path directory,String id,byte[] nativeBytes,byte[] png,
        Path previousSource,String previousHash,BooleanSupplier current,Fault fault,String... aliases)throws IOException {
        return publishCandidate(directory,id,nativeBytes,png,previousSource,previousHash,current,fault,null,false,null,aliases);
    }
    public static synchronized Receipt publishCandidate(Path directory,String id,byte[] nativeBytes,byte[] png,
        Path previousSource,String previousHash,BooleanSupplier current,Fault fault,Receipt expected,boolean requireVacant,
        LegacyMirrorProof mirror,String... aliases)throws IOException {
        Path root=directory.toAbsolutePath().normalize();
        Files.createDirectories(root);
        need(Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS),"library directory");
        validIdentifier(id);
        need(nativeBytes.length<=NativeBlueprintLimits.FILE_BYTES,"source bytes");
        try(FileChannel lock=lockFile(root);java.nio.channels.FileLock ignored=lock.lock()) {
            reconcileLocked(root);
            NativeBlueprintPng.validatePreview(png);
            return publishLocked(root,id,nativeBytes,png,previousSource,previousHash,current,fault,aliases,expected,requireVacant,mirror);
        }
    }
    private static FileChannel lockFile(Path root)throws IOException {
        return FileChannel.open(root.resolve(".gtmb-native.lock"), StandardOpenOption.CREATE,
            StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }
    private static Receipt publishLocked(Path root,String id,byte[] nativeBytes,byte[] png,Path previousSource,
        String previousHash,BooleanSupplier current,Fault fault,String[] aliases,Receipt expected,boolean requireVacant,LegacyMirrorProof mirror)throws IOException {
        byte[] beforeBytes=catalogBytes(root);
        Properties before=catalog(root);
        Receipt previous=receipt(root,id,before);
        need(previous==null||previous.logicalId.equals(id),"native logical id is an alias");
        if(expected!=null){
            need(sameReceipt(previous,expected),"蓝图或预览已被其他编辑更新；请重新打开");
            need(hashFile(previous.source,NativeBlueprintLimits.FILE_BYTES).equals(expected.physicalHash)
                && hashFile(previous.png,4*1024*1024).equals(expected.pngHash),"原蓝图或预览已变化；请重新打开");
        }
        if(requireVacant)need(previous==null,"另存目标已存在");
        if(previous!=null&&previousSource!=null)
            need(previous.source.equals(previousSource)&&previous.physicalHash.equals(previousHash),"active revision changed");
        check(current);
        Path staged=Files.createTempFile(root,"gtmb-native-source-",".tmp");
        Path image=null,journal=null,ownedSource=null,ownedImage=null;
        boolean committed=false;
        try {
            image=Files.createTempFile(root,"gtmb-native-image-",".tmp");
            durable(staged,nativeBytes);
            fault.at("source-written");
            String physical,content,name,card;
            try(NativeBlueprintDefinition d=NativeBlueprintDefinition.open(staged)){
                physical=d.revision();content=NativeBlueprintIdentity.content(d);
                name=new JsonName(d.metadata()).name;card=displayCard(d);
                if(mirror!=null)need(previousSource!=null&&!previousSource.getFileName().toString().endsWith(".gtnb")
                    &&mirror.originHash.equals(previousHash)&&mirror.originHash.equals(legacyOrigin(d)),"legacy mirror provenance");
            }
            byte[] bound=NativeBlueprintPng.bind(png,physical);
            String imageHash=hash(bound);
            durable(image,bound);
            NativeBlueprintPng.verify(readRegular(image,4*1024*1024),physical);
            fault.at("pair-staged");
            // A receipt pins both files. Same source with a new capture gets a new pair, and
            // even byte-identical independent catalog slots have different physical names.
            String generation=hash((id+":"+imageHash).getBytes(StandardCharsets.UTF_8));
            String stem=portableStem(name)+"_"+physical+"_"+generation;
            Path target=child(root,stem+".gtnb"),targetPng=child(root,stem+".png");
            Receipt result=new Receipt(target,targetPng,id,physical,content,imageHash);
            String tx=UUID.randomUUID().toString(),k=key(id);
            Properties next=new Properties();next.putAll(before);
            next.setProperty(k+".id",id);next.setProperty(k+".source",target.getFileName().toString());
            next.setProperty(k+".png",targetPng.getFileName().toString());
            next.setProperty(k+".sourceHash",physical);next.setProperty(k+".pngHash",imageHash);
            next.setProperty(k+".contentHash",content);next.setProperty(k+".card",card);next.setProperty(k+".tx",tx);
            next.remove("deleted."+k);
            if(mirror!=null)next.setProperty(k+ACTIVE_MIRROR,mirror.digest);
            for(String alias:aliases){
                validIdentifier(alias);
                if(id.equals(alias))continue;
                Receipt claimed=receipt(root,alias,before);
                need(claimed==null||id.equals(claimed.logicalId),"native alias belongs to another entry");
                next.setProperty("alias."+key(alias),id);
            }
            if(previousSource!=null){
                pinPrevious(root,previousSource,previousHash);
                if(!previousSource.getFileName().toString().endsWith(".gtnb"))
                    next.setProperty("inactive."+key(previousSource.getFileName().toString()),previousHash);
            }
            Properties pending=new Properties();
            pending.setProperty("op","publish");pending.setProperty("id",id);pending.setProperty("tx",tx);
            putPair(pending,"",result);
            next.remove(k+".retire");
            next.remove("deleted-retire."+k);
            if(previous!=null&&!previous.source.equals(target)){
                putPair(pending,"retired.",previous);
                next.setProperty(k+".retire",retirementSeal(previous));
            }
            journal=root.resolve(JOURNAL+tx+".properties");
            writeProperties(journal,pending);
            fault.at("journal-written");
            check(current);
            if(moveNew(staged,target,nativeBytes))ownedSource=target;
            fault.at("source-moved");
            if(moveNew(image,targetPng,bound))ownedImage=targetPng;
            fault.at("image-moved");
            syncDirectory(root);
            NativeBlueprintPng.verify(readRegular(targetPng,4*1024*1024),physical);
            need(hashFile(target,NativeBlueprintLimits.FILE_BYTES).equals(physical),"published source readback");
            fault.at("before-catalog");
            check(current);
            if(previousSource!=null)pinPrevious(root,previousSource,previousHash);
            replaceCatalog(root,beforeBytes,next,current,fault);
            committed=true;
            fault.at("after-catalog");
            finishCommitted(root,next,journal,pending);
            return result;
        } catch(IOException | RuntimeException failure) {
            if(committed)return receipt(root,id);
            // Do not remove files referenced by the current catalog, even if rollback itself failed.
            try {
                Receipt observed=receipt(root,id);
                if(ownedSource!=null&&(observed==null||!ownedSource.equals(observed.source)))Files.deleteIfExists(ownedSource);
                if(ownedImage!=null&&(observed==null||!ownedImage.equals(observed.png)))Files.deleteIfExists(ownedImage);
                if(journal!=null&&(observed==null||!journalMatches(journal,observed)))Files.deleteIfExists(journal);
            } catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        } finally {
            Files.deleteIfExists(staged);
            if(image!=null)Files.deleteIfExists(image);
        }
    }
    private static boolean journalMatches(Path journal,Receipt r)throws IOException {
        return samePair(load(journal),"",r);
    }
    private static void pinPrevious(Path root,Path source,String hash)throws IOException {
        need(source.toAbsolutePath().normalize().getParent().equals(root),"previous source directory");
        need(hash!=null&&hash.equals(hashFile(source,NativeBlueprintLimits.FILE_BYTES)),"source changed before migration");
    }
    /** Unpublish first; only after index acknowledgement retire the exact catalog-owned native pair. */
    public static synchronized void delete(Path directory,Receipt expected,Fault fault,LegacyMirrorProof recovered)throws IOException {
        Path root=directory.toAbsolutePath().normalize();
        try(FileChannel lock=lockFile(root);java.nio.channels.FileLock ignored=lock.lock()) {
            reconcileLocked(root);
            byte[] beforeBytes=catalogBytes(root);
            Properties before=catalog(root),next=new Properties();
            next.putAll(before);
            String id=expected.logicalId,k=key(id);
            Receipt live=receipt(root,id,before);
            need(sameReceipt(live,expected),"native delete selection changed");
            String mirror=before.getProperty(k+ACTIVE_MIRROR);
            try(NativeBlueprintDefinition d=openExact(live.source,live.png,live.physicalHash,live.pngHash)) {
                if(mirror==null&&recovered!=null&&recovered.originHash.equals(legacyOrigin(d))
                    &&retainedOriginal(root,before,recovered.originHash)!=null)mirror=recovered.digest;
            }
            String tx=UUID.randomUUID().toString();
            for(String property:before.stringPropertyNames())
                if(property.startsWith(k+".")||property.startsWith("alias.")&&id.equals(before.getProperty(property)))
                    next.remove(property);
            next.setProperty("deleted."+k,tx);
            if(sha(mirror))next.setProperty(DELETED_MIRROR+key(LegacyBlueprintMirror.logicalId(id))+"."+mirror,"1");
            Properties pending=new Properties();
            pending.setProperty("op","delete");pending.setProperty("id",id);pending.setProperty("tx",tx);
            putPair(pending,"retired.",live);
            next.setProperty("deleted-retire."+k,retirementSeal(live));
            Path journal=root.resolve(JOURNAL+tx+".properties");
            writeProperties(journal,pending);
            boolean committed=false;
            try {
                fault.at("before-catalog");
                replaceCatalog(root,beforeBytes,next,()->true,fault);
                committed=true;
                fault.at("after-catalog");
                finishCommitted(root,next,journal,pending);
            } catch(IOException | RuntimeException failure) {
                if(committed)return; // Index is authoritative; remaining removal is safely retried on ordinary reopen.
                try {
                    if(!tx.equals(catalog(root).getProperty("deleted."+k)))Files.deleteIfExists(journal);
                } catch(IOException cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
    }
    private static void replaceCatalog(Path root,byte[] before,Properties next,BooleanSupplier current,Fault fault)throws IOException {
        check(current);
        need(Arrays.equals(before,catalogBytes(root)),"native catalog changed");
        byte[] replacement=encodeProperties(next);
        Path index=root.resolve(CATALOG);
        try {
            replaceBytes(index,replacement);
            fault.at("index-replaced");
            check(current);
        } catch(IOException | RuntimeException failure) {
            try {
                if(Arrays.equals(replacement,catalogBytes(root))) {
                    if(before==null){Files.delete(index);syncDirectory(root);}
                    else replaceBytes(index,before);
                }
            } catch(IOException rollback){failure.addSuppressed(rollback);}
            throw failure;
        }
    }
    public static synchronized Receipt receipt(Path directory,String id)throws IOException{
        Path root=directory.toAbsolutePath().normalize();return receipt(root,id,catalog(root));
    }
    private static Receipt receipt(Path root,String id,Properties p)throws IOException {
        if(id==null)return null;
        String k=key(id);
        if(!p.containsKey(k+".id")) {
            id=p.getProperty("alias."+k);
            if(id==null)return null;
            k=key(id);
            need(id.equals(p.getProperty(k+".id")),"dangling native alias");
        }
        need(id.equals(p.getProperty(k+".id")),"native catalog id");
        return readPair(root,p,k+".",id);
    }
    private static Receipt readPair(Path root,Properties p,String k,String id)throws IOException {
        Path source=child(root,p.getProperty(k+"source")),png=child(root,p.getProperty(k+"png"));
        String name=source.getFileName().toString();
        need(name.endsWith(".gtnb")&&png.getFileName().toString().equals(name.substring(0,name.length()-5)+".png"),
            "native catalog pair filenames");
        String h=p.getProperty(k+"sourceHash"),ph=p.getProperty(k+"pngHash");
        need(h!=null&&h.matches("[a-f0-9]{64}")&&ph!=null&&ph.matches("[a-f0-9]{64}"),"native catalog hashes");
        return new Receipt(source,png,id,h,p.getProperty(k+"contentHash"),ph);
    }
    private static void putPair(Properties p,String k,Receipt r){
        p.setProperty(k+"source",r.source.getFileName().toString());
        p.setProperty(k+"png",r.png.getFileName().toString());
        p.setProperty(k+"sourceHash",r.physicalHash);
        p.setProperty(k+"pngHash",r.pngHash);
        p.setProperty(k+"contentHash",r.contentHash);
    }
    /** Previously accepted native candidates have exact source-hash provenance but predate mirror proofs. */
    public static synchronized Path retainedOriginal(Path directory,Receipt receipt)throws IOException {
        Path root=directory.toAbsolutePath().normalize();Properties catalog=catalog(root);
        if(!sameReceipt(receipt(root,receipt.logicalId,catalog),receipt))return null;
        try(var d=openExact(receipt.source,receipt.png,receipt.physicalHash,receipt.pngHash)){
            return retainedOriginal(root,catalog,legacyOrigin(d));
        }
    }

    private static Path retainedOriginal(Path root,Properties catalog,String origin)throws IOException{
        if(!sha(origin))return null;
        for(String k:catalog.stringPropertyNames())if(k.startsWith("inactive.")&&origin.equals(catalog.getProperty(k))){
            String name;
            try{name=new String(Base64.getUrlDecoder().decode(k.substring(9)),StandardCharsets.UTF_8);}
            catch(IllegalArgumentException invalid){continue;}
            Path p=child(root,name);
            if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&hashFile(p,NativeBlueprintLimits.FILE_BYTES).equals(origin))return p;
        }
        return null;
    }

    public static final class CollectionVisibility {
        private final Map<String,java.util.Set<String>> mirrors=new LinkedHashMap<>();
        private final Map<String,String> nativeIds=new LinkedHashMap<>();
        private final java.util.Set<String> activeProven=new java.util.HashSet<>();
        public java.util.Set<String> mirrored(String uuid){
            return mirrors.getOrDefault(key(uuid),java.util.Collections.emptySet());
        }
        public boolean nativeSlot(String uuid){return nativeIds.containsKey(key(uuid));}
        public String nativeId(String uuid){return nativeIds.get(key(uuid));}
        public boolean activeProof(String uuid){return activeProven.contains(key(uuid));}
    }

    /** Immutable per-operation view of the same catalog transaction used by native publish/delete. */
    public static synchronized CollectionVisibility collectionVisibility(Path directory)throws IOException{
        var result=new CollectionVisibility();
        Properties catalog=catalog(directory.toAbsolutePath().normalize());
        for(String entry:catalog.stringPropertyNames()){
            if(entry.endsWith(".id")){
                String logical=LegacyBlueprintMirror.logicalId(catalog.getProperty(entry));
                if(!logical.isEmpty())result.nativeIds.put(key(logical),catalog.getProperty(entry));
            }
            String encoded=null,digest=null;
            if(entry.endsWith(ACTIVE_MIRROR)){
                String base=entry.substring(0,entry.length()-ACTIVE_MIRROR.length());
                String logical=LegacyBlueprintMirror.logicalId(catalog.getProperty(base+".id"));
                if(!logical.isEmpty()){
                    encoded=key(logical);digest=catalog.getProperty(entry);
                    if(sha(digest))result.activeProven.add(encoded);
                }
            }else if(entry.startsWith(DELETED_MIRROR)){
                int dot=entry.lastIndexOf('.');
                if(dot>DELETED_MIRROR.length()){encoded=entry.substring(DELETED_MIRROR.length(),dot);digest=entry.substring(dot+1);}
            }
            if(encoded!=null&&sha(digest))result.mirrors.computeIfAbsent(encoded,ignored->new java.util.HashSet<>()).add(digest);
        }
        result.mirrors.replaceAll((k,v)->java.util.Collections.unmodifiableSet(v));
        return result;
    }

    private static boolean samePair(Properties p,String k,Receipt r){
        return r!=null&&r.source.getFileName().toString().equals(p.getProperty(k+"source"))
            &&r.png.getFileName().toString().equals(p.getProperty(k+"png"))
            &&r.physicalHash.equals(p.getProperty(k+"sourceHash"))&&r.pngHash.equals(p.getProperty(k+"pngHash"));
    }
    public static boolean sameReceipt(Receipt a,Receipt b) {
        return a!=null&&b!=null&&a.logicalId.equals(b.logicalId)&&a.source.equals(b.source)&&a.png.equals(b.png)
            &&a.physicalHash.equals(b.physicalHash)&&a.pngHash.equals(b.pngHash);
    }
    public static synchronized NativeBlueprintDefinition open(Path directory,String id)throws IOException{Receipt r=receipt(directory,id);need(r!=null,"native entry absent");return openExact(r.source,r.png,r.physicalHash,r.pngHash);}
    public static NativeBlueprintDefinition openExact(Path source,Path image,String revision,String imageHash)throws IOException {
        byte[] png=readRegular(image,4*1024*1024);
        need(hash(png).equals(imageHash),"catalog PNG hash");NativeBlueprintPng.verify(png,revision);
        NativeBlueprintDefinition d=NativeBlueprintDefinition.open(source);
        if(!d.revision().equals(revision)){d.close();throw bad("catalog source changed");}return d;
    }
    public static synchronized Map<String,Path> active(Path directory)throws IOException {Path root=directory.toAbsolutePath().normalize();Properties p=catalog(root);Map<String,Path> result=new LinkedHashMap<>();for(String k:p.stringPropertyNames())if(k.endsWith(".id")){String id=p.getProperty(k);Receipt r=receipt(root,id,p);need(r!=null,"incomplete native catalog");result.put(id,r.source);}return result;}
    public static synchronized boolean inactive(Path directory,Path source)throws IOException {
        // This name was explicitly superseded. A separately imported replacement receives a new
        // name/entry; merely rewriting a retained original must never resurrect a second card.
        return catalog(directory.toAbsolutePath().normalize()).containsKey("inactive."+key(source.getFileName().toString()));
    }
    private static String displayCard(NativeBlueprintDefinition d)throws IOException {
        com.google.gson.JsonObject meta=d.metadata().getAsJsonObject("fields"),out=new com.google.gson.JsonObject();
        for(String k:new String[]{"id","name","authorName","authorUuid"}){
            com.google.gson.JsonElement v=meta.get(k);
            if(v!=null&&v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString())out.add(k,v);
        }
        com.google.gson.JsonElement time=meta.get("createdAt");
        if(time!=null&&time.isJsonPrimitive()&&time.getAsJsonPrimitive().isNumber())
            try{out.addProperty("createdAt",Long.parseLong(time.getAsString()));}catch(NumberFormatException ignored){}
        out.addProperty("displayCells",d.cells());
        com.google.gson.JsonObject regions=d.sectionWrapper(8),doc=d.sectionWrapper(9);
        com.google.gson.JsonElement structures=regions==null?null:regions.getAsJsonObject("fields").get("structures");
        out.addProperty("displayStructures",structures!=null&&structures.isJsonArray()?structures.getAsJsonArray().size():0);
        if(doc!=null&&doc.getAsJsonObject("fields").has("document")
            &&doc.getAsJsonObject("fields").get("document").isJsonObject()){
            com.google.gson.JsonObject fields=doc.getAsJsonObject("fields").getAsJsonObject("document");
            for(String k:new String[]{"sourceMod","fullyVoucherCovered"})
                if(fields.has(k)&&fields.get(k).isJsonPrimitive())out.add(k,fields.get(k));
            com.google.gson.JsonElement nb=fields.get("nativeBlueprint");
            if(nb!=null&&nb.isJsonObject()&&nb.getAsJsonObject().has("gtmbVersion")){
                com.google.gson.JsonObject stamp=new com.google.gson.JsonObject();
                stamp.add("gtmbVersion",nb.getAsJsonObject().get("gtmbVersion"));out.add("nativeBlueprint",stamp);
            }
        }
        return new String(NativeBlueprintJson.encode(out),StandardCharsets.US_ASCII);
    }
    /** Catalog reads never inflate NBT or walk geometry. The owner verified this hint before CAS. */
    public static synchronized com.google.gson.JsonObject display(Path directory,String id,Path source)throws IOException {
        Path root=directory.toAbsolutePath().normalize();Properties p=catalog(root);Receipt r=receipt(root,id,p);
        need(r!=null&&r.source.equals(source.toAbsolutePath().normalize()),"not an active native entry");
        String card=p.getProperty(key(r.logicalId)+".card");need(card!=null,"missing native display metadata");
        return NativeBlueprintJson.parse(card.getBytes(StandardCharsets.US_ASCII)).getAsJsonObject();
    }
    /**
     * Called by normal library startup/list/open, and inside the writer lock before another
     * transaction. A catalog receipt/transaction is authoritative, not a journal alone.
     */
    public static synchronized void recover(Path directory)throws IOException {
        Path root=directory.toAbsolutePath().normalize();
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))return;
        try(java.nio.file.DirectoryStream<Path> ds=Files.newDirectoryStream(root,JOURNAL+"*.properties")){
            if(!ds.iterator().hasNext())return;
        }
        try(FileChannel lock=lockFile(root);java.nio.channels.FileLock ignored=lock.lock()) {
            reconcileLocked(root);
        }
    }
    private static void reconcileLocked(Path root)throws IOException {
        Properties active=catalog(root);
        try(java.nio.file.DirectoryStream<Path> ds=Files.newDirectoryStream(root,JOURNAL+"*.properties")){
            int n=0;
            for(Path journal:ds) {
                need(++n<=4096,"pending journal budget");
                try {finishCommitted(root,active,journal,load(journal));}
                catch(IOException | RuntimeException unproven) {
                    // A bad/locked journal does not erase a known catalog or hide unrelated cards.
                    // Leave the journal and any unproven files untouched.
                }
            }
        }
    }
    private static void finishCommitted(Path root,Properties active,Path journal,Properties pending)throws IOException {
        String id=pending.getProperty("id"),tx=pending.getProperty("tx"),op=pending.getProperty("op");
        if(id==null)return;
        String k=key(id);
        Receipt live=receipt(root,id,active);
        if(op==null){ // Reconcile early candidate journals by exact active receipt (no retired files).
            if(!samePair(pending,"",live))return;
            try(NativeBlueprintDefinition d=openExact(live.source,live.png,live.physicalHash,live.pngHash)) {}
        }else{
            if(tx==null||!journal.getFileName().toString().equals(JOURNAL+tx+".properties"))return;
            if(op.equals("publish")){
                if(!tx.equals(active.getProperty(k+".tx"))||!samePair(pending,"",live))return;
                need(hashFile(live.source,NativeBlueprintLimits.FILE_BYTES).equals(live.physicalHash)
                    &&hashFile(live.png,4*1024*1024).equals(live.pngHash),"committed native pair changed");
            }else if(op.equals("delete")){
                if(live!=null||!tx.equals(active.getProperty("deleted."+k)))return;
            }else return;
            if(pending.containsKey("retired.source")){
                Receipt retired=readPair(root,pending,"retired.",id);
                String seal=active.getProperty(op.equals("publish")?k+".retire":"deleted-retire."+k);
                if(!retirementSeal(retired).equals(seal))return;
                for(String property:active.stringPropertyNames())
                    if(property.endsWith(".source")&&retired.source.getFileName().toString().equals(active.getProperty(property)))
                        return; // Another catalog still owns that path.
                retire(retired.source,retired.physicalHash,NativeBlueprintLimits.FILE_BYTES);
                retire(retired.png,retired.pngHash,4*1024*1024);
            }
        }
        Files.deleteIfExists(journal);
        syncDirectory(root);
    }
    private static String retirementSeal(Receipt r) {
        return hash((r.source.getFileName().toString()+(char)0+r.png.getFileName()+(char)0+r.physicalHash+r.pngHash)
            .getBytes(StandardCharsets.UTF_8));
    }
    private static void retire(Path file,String expected,int max)throws IOException {
        // Mismatched or non-regular paths are not ours any longer; leave them untouched.
        if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&hashFile(file,max).equals(expected))Files.delete(file);
    }
    private static byte[] catalogBytes(Path root)throws IOException {
        Path p=root.resolve(CATALOG);return Files.exists(p,LinkOption.NOFOLLOW_LINKS)?readRegular(p,1024*1024):null;
    }
    private static Properties catalog(Path root)throws IOException{Path p=root.resolve(CATALOG);return Files.exists(p,LinkOption.NOFOLLOW_LINKS)?load(p):new Properties();}
    private static Properties load(Path p)throws IOException{byte[] b=readRegular(p,1024*1024);Properties v=new Properties();v.load(new ByteArrayInputStream(b));need(v.size()<=32768,"catalog entry budget");return v;}
    private static byte[] encodeProperties(Properties values)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();values.store(b,"GTMB native pair catalog");need(b.size()<=1024*1024,"catalog byte budget");return b.toByteArray();}
    private static void writeProperties(Path p,Properties values)throws IOException {replaceBytes(p,encodeProperties(values));}
    private static void replaceBytes(Path p,byte[] bytes)throws IOException {Path tmp=Files.createTempFile(p.getParent(),"gtmb-native-index-",".tmp");try{durable(tmp,bytes);Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(p.getParent());}finally{Files.deleteIfExists(tmp);}}
    private static void durable(Path p,byte[] b)throws IOException{try(FileChannel f=FileChannel.open(p,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){ByteBuffer v=ByteBuffer.wrap(b);while(v.hasRemaining())f.write(v);f.force(true);}}
    private static boolean moveNew(Path from,Path to,byte[] expected)throws IOException{if(Files.exists(to,LinkOption.NOFOLLOW_LINKS)){need(Arrays.equals(expected,readRegular(to,NativeBlueprintLimits.FILE_BYTES)),"native filename collision");Files.delete(from);return false;}else {Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);return true;}}
    private static void syncDirectory(Path root)throws IOException{try(FileChannel f=FileChannel.open(root,StandardOpenOption.READ)){f.force(true);}catch(java.nio.file.FileSystemException e){if(!System.getProperty("os.name","").startsWith("Windows"))throw e;}}
    public static byte[] readRegular(Path p,int max)throws IOException {need(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"not a regular file");long n=Files.size(p);need(n>=0&&n<=max,"file budget");try(java.io.InputStream in=Files.newInputStream(p,LinkOption.NOFOLLOW_LINKS)){ByteArrayOutputStream out=new ByteArrayOutputStream((int)n);byte[] b=new byte[8192];int v;while((v=in.read(b))!=-1){need(v<=max-out.size(),"file grew beyond budget");out.write(b,0,v);}return out.toByteArray();}}
    private static void check(BooleanSupplier s)throws IOException{need(s.getAsBoolean(),"operation cancelled");}
    private static String key(String id){return Base64.getUrlEncoder().withoutPadding().encodeToString(id.getBytes(StandardCharsets.UTF_8));}
    private static Path child(Path root,String name)throws IOException{need(name!=null&&!name.isEmpty()&&!name.equals(".")&&!name.equals("..")&&name.indexOf('/')<0&&name.indexOf('\\')<0,"catalog filename");Path p=root.resolve(name).normalize();need(root.equals(p.getParent()),"catalog path escape");return p;}
    public static String hashFile(Path path,int maximum)throws IOException {
        need(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)&&Files.size(path)<=maximum,"file budget");
        java.security.MessageDigest digest;
        try{digest=java.security.MessageDigest.getInstance("SHA-256");}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
        try(java.io.InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){
            byte[] buffer=new byte[8192];int n,total=0;
            while((n=in.read(buffer))!=-1){need(n<=maximum-total,"file grew beyond budget");digest.update(buffer,0,n);total+=n;}
        }
        StringBuilder result=new StringBuilder(64);
        for(byte b:digest.digest())result.append(Character.forDigit((b&255)>>>4,16)).append(Character.forDigit(b&15,16));
        return result.toString();
    }
    private static void validIdentifier(String id)throws IOException {
        need(id!=null&&!id.isEmpty()&&id.length()<=255&&!id.equals(".")&&!id.equals("..")
            &&id.indexOf('/')<0&&id.indexOf((char)92)<0&&!id.matches(".*[\\p{Cntrl}].*"),"logical id");
    }
    public static String portableStem(String value) {
        StringBuilder name = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < value.length();) {
            int character = value.codePointAt(offset);
            offset += Character.charCount(character);
            if (Character.isISOControl(character) || character>=0xd800&&character<=0xdfff || "<>:\"/\\|?*".indexOf(character) >= 0) character = '_';
            String part = new String(Character.toChars(character));
            int width = part.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > 80) break;
            name.append(part);
            bytes += width;
        }
        String result = name.toString()
            .trim()
            .replaceAll("[. ]+$", "");
        if (result.isEmpty()) result = "蓝图";
        if (result.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?")) result = "_" + result;
        return result;
    }

    private static final class JsonName {final String name;JsonName(com.google.gson.JsonObject meta){com.google.gson.JsonObject fields=meta.getAsJsonObject("fields");name=fields.has("name")&&fields.get("name").isJsonPrimitive()&&fields.getAsJsonPrimitive("name").isString()?fields.get("name").getAsString():"blueprint";}}
}
