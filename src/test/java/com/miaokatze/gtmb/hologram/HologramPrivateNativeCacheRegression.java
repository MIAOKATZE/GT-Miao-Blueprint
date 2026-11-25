// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import com.miaokatze.gtmb.hologram.gtnb.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.charset.StandardCharsets.UTF_8;

/** Private source custody is deliberately independent of the public upload/download gallery. */
final class HologramPrivateNativeCacheRegression {
    private static void require(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    interface Throwing{void run()throws IOException;}
    static void refused(Throwing op,String why)throws IOException{try{op.run();throw new AssertionError(why);}catch(IOException expected){}}
    static byte[] data(int x)throws IOException{
        return NativeBlueprintMigration.convert(("{\"schema\":2,\"controllerType\":\"area\",\"cells\":[{\"x\":"+x+",\"y\":0,\"z\":0,\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\"}]}").getBytes(UTF_8)).bytes;
    }
    static HologramPrivateNativeCache.Pin add(HologramPrivateNativeCache c,UUID owner,byte[] bytes,long now)throws IOException{
        UUID id=UUID.randomUUID();String sha=HologramBlueprintArtifact.hash(bytes);
        c.begin(owner,id,sha,bytes.length,now);int split=bytes.length/2;
        c.append(owner,id,0,Arrays.copyOfRange(bytes,0,split),now+1);
        c.append(owner,id,split,Arrays.copyOfRange(bytes,split,bytes.length),now+2);
        var pin=c.finish(owner,id);c.promote(owner,id);return pin;
    }
    public static void exercise()throws Exception{
        UUID owner=UUID.randomUUID(),other=UUID.randomUUID();Path root=Files.createTempDirectory("gtmb-private-custody-");
        try{
            Path configFile=root.resolve("server.cfg");HologramLibraryConfig config=new HologramLibraryConfig(configFile.toFile());
            var orig=config.view();require(orig.nativeCacheSlots==5,"default separate private capacity");
            refused(()->config.update(false,orig.revision,orig.uploadKiB,orig.downloadKiB,orig.permissions,2),"non-admin configured private cache");
            refused(()->config.update(true,orig.revision,orig.uploadKiB,orig.downloadKiB,orig.permissions,0),"invalid private quota");
            config.update(true,orig.revision,orig.uploadKiB,orig.downloadKiB,orig.permissions,2);
            var loaded=new HologramLibraryConfig(configFile.toFile()).view();require(loaded.nativeCacheSlots==2&&loaded.maxUploads==orig.maxUploads&&loaded.tag(true).getInteger("privateNativeCacheSlots")==2,"persisted private quota independent of public quota");
            byte[][] versions=new byte[7][];String[] hashes=new String[7];for(int i=0;i<7;i++){versions[i]=data(i);hashes[i]=HologramBlueprintArtifact.hash(versions[i]);}
            try(var c=new HologramPrivateNativeCache(root,5)){
                for(int i=0;i<5;i++)c.release(add(c,owner,versions[i],1));
                var pinned=c.find(owner,hashes[0],versions[0].length);require(pinned!=null,"oldest hit");
                require(c.find(other,hashes[0],versions[0].length)==null,"another player cannot reference this source even with exact SHA");
                c.release(add(c,owner,versions[5],2));
                require(c.find(owner,hashes[1],versions[1].length)==null,"only oldest unpinned entry evicted");
                var hit=c.find(owner,hashes[0],versions[0].length);require(hit.definition==pinned.definition,"hot source is not decoded or hashed again");
                c.release(hit);c.release(pinned);
                c.release(add(c,owner,versions[6],3));
                require(c.find(owner,hashes[0],versions[0].length)==null,"hit never advances FIFO");
                c.configure(1);var inUse=c.find(owner,hashes[6],versions[6].length);
                refused(()->c.begin(owner,UUID.randomUUID(),hashes[0],versions[0].length,5),"all slots pinned");
                c.release(inUse);
                UUID bad=UUID.randomUUID();c.begin(owner,bad,hashes[0],versions[0].length,1);
                c.append(owner,bad,0,new byte[]{0},2);refused(()->c.append(owner,bad,0,new byte[]{1},3),"duplicate offset");
                UUID truncated=UUID.randomUUID();c.begin(owner,truncated,hashes[0],versions[0].length,3);
                refused(()->c.finish(owner,truncated),"truncated source installed");
                UUID wrong=UUID.randomUUID();c.begin(owner,wrong,hashes[0],versions[3].length,4);
                c.append(owner,wrong,0,versions[3],5);refused(()->c.finish(owner,wrong),"wrong saved revision installed");
                UUID expired=UUID.randomUUID();c.begin(owner,expired,hashes[0],versions[0].length,7);
                c.expire(7+HologramPrivateNativeCache.TTL_NANOS);
                refused(()->c.append(owner,expired,0,new byte[]{1},8),"expired inflight writable");
                c.release(add(c,other,versions[0],1));
                var remaining=c.find(owner,hashes[6],versions[6].length);require(remaining!=null,"failed replacement preserved old private source");c.release(remaining);
            }
            Path dir=root.resolve("gtmb-private-native-sources").resolve(owner.toString());
            // Interrupted/invalid entries in this owner's FIFO must not prevent startup for either owner.
            Files.createFile(dir.resolve(String.format(Locale.ROOT,"%018d-%s.gtnb",99,hashes[1])));
            try(var c=new HologramPrivateNativeCache(root,1)){
                var hit=c.find(owner,hashes[6],versions[6].length);var separate=c.find(other,hashes[0],versions[0].length);
                require(hit!=null&&separate!=null,"disk restart preserves separate owners and exact versions");c.release(hit);c.release(separate);
                Path file;try(var candidates=Files.newDirectoryStream(dir,"*"+hashes[6]+".gtnb")){file=candidates.iterator().next();}
                refused(()->c.find(owner,hashes[6],versions[6].length+1),"incorrect caller length accepted");
                UUID rejected=UUID.randomUUID();c.begin(owner,rejected,hashes[1],versions[1].length,5);
                c.append(owner,rejected,0,versions[1],6);var pending=c.finish(owner,rejected);
                require(c.find(owner,hashes[1],versions[1].length)==null,"unadmitted source was indexed");
                c.abort(owner,rejected);require(pending.closed,"staged file pin leaked");
                var good=c.find(owner,hashes[6],versions[6].length);require(good!=null,"unaccepted upload evicted good source");c.release(good);
                Files.delete(file);require(c.find(owner,hashes[6],versions[6].length)==null,"missing disk file remained a fatal hit");
                c.release(add(c,owner,versions[6],7));Path corrupt;
                try(var items=Files.newDirectoryStream(dir,"*"+hashes[6]+".gtnb")){corrupt=items.iterator().next();}
                byte[] broken=new byte[versions[6].length];Files.write(corrupt,broken);
                Files.setLastModifiedTime(corrupt,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()+2000));
                require(c.find(owner,hashes[6],versions[6].length)==null,"same-size corrupted disk file remained a fatal hit");
                c.release(add(c,owner,versions[6],8));
            }
            // Genuine directory permissions fault, rather than another file decode fixture. Skip only where
            // POSIX permissions are unavailable or privileged execution cannot be denied access.
            Path deniedDir=dir.resolveSibling(UUID.randomUUID().toString());
            if(Files.getFileStore(root).supportsFileAttributeView("posix")) {
                Files.createDirectory(deniedDir);
                var perms=Files.getPosixFilePermissions(deniedDir);
                try {
                    Files.setPosixFilePermissions(deniedDir,java.util.Collections.emptySet());
                    if(!Files.isReadable(deniedDir))try(var reopened=new HologramPrivateNativeCache(root,3)){
                        var hit=reopened.find(owner,hashes[6],versions[6].length);
                        require(hit!=null,"one unreadable owner hid a healthy account");reopened.release(hit);
                        refused(()->reopened.find(UUID.fromString(deniedDir.getFileName().toString()),hashes[6],versions[6].length),
                            "unreadable owner received an empty writable bucket");
                        System.out.println("GTMB_CACHE cold POSIX-denied owner isolated");
                    }
                } finally {Files.setPosixFilePermissions(deniedDir,perms);}
            }
        }finally{try(var paths=Files.walk(root)){for(Path p:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
}
