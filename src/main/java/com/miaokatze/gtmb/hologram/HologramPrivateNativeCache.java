// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintLimits;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Disk-only private saved sources. Caller serializes operations on its bounded I/O worker.
 * No local paths, public ids, cover images or executable placement instructions cross this interface.
 * FIFO order is recoverable from the committed filename; reads do not refresh that order.
 */
final class HologramPrivateNativeCache implements AutoCloseable {
    static final int FRAME=24*1024, DEFAULT_SLOTS=5, MAX_SLOTS=32, MAX_TRANSFERS=4;
    static final long PLAYER_BYTES=512L<<20, GLOBAL_BYTES=4L<<30, TTL_NANOS=90_000_000_000L;
    private static final Pattern NAME=Pattern.compile("([0-9]{18})-([0-9a-f]{64})\\.gtnb");
    private final Path root;
    private final Map<UUID,Bucket> owners=new HashMap<>();
    private final Map<UUID,Upload> uploads=new HashMap<>();
    private final Set<UUID> inaccessibleOwners=new HashSet<>();
    private long bytes,reserved;
    private int limit;
    // Disk FIFO and parsed hot-source retention are independent. Hits never advance the disk FIFO.
    private final LinkedHashMap<Entry,Boolean> hot=new LinkedHashMap<>(8,.75f,true);
    private static final long HOT_BYTES=64L<<20;private long hotBytes;
    private static final int HOT_FILES=4;
    static final class Entry {
        final String sha;Path path; final long size,order;
        final Bucket owner;
        boolean provisional;int pins;NativeBlueprintDefinition parsed;
        HologramNativeLocalConstruction.Prepared plan;
        java.nio.file.attribute.BasicFileAttributes stamp;
        Entry(Bucket owner,String sha,Path path,long size,long order){this.owner=owner;this.sha=sha;this.path=path;this.size=size;this.order=order;}
    }
    static final class Bucket {
        final UUID owner;final Path dir;final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
        long size,next=1;
        Bucket(UUID owner,Path dir){this.owner=owner;this.dir=dir;}
    }
    static final class Pin {
        final Entry entry;final NativeBlueprintDefinition definition;volatile boolean closed;
        Pin(Entry e,NativeBlueprintDefinition d){entry=e;definition=d;e.pins++;}
    }
    static final class Upload {
        final UUID request;final Bucket owner;final String sha;final int length;final Path temp;
        final OutputStream stream;Pin staged;int offset;long active;
        Upload(UUID request,Bucket owner,String sha,int length,Path temp,OutputStream stream,long now){
            this.request=request;this.owner=owner;this.sha=sha;this.length=length;this.temp=temp;this.stream=stream;active=now;}
    }
    HologramPrivateNativeCache(Path saveRoot,int slots)throws IOException {
        root=saveRoot.toRealPath().resolve("gtmb-private-native-sources");directory(root);
        require(slots>=1&&slots<=MAX_SLOTS,"无效的原生蓝图缓存数量");limit=slots;
        try(DirectoryStream<Path> directories=Files.newDirectoryStream(root)){
            for(Path path:directories){UUID id;try{id=UUID.fromString(path.getFileName().toString());}
                catch(IllegalArgumentException ignored){continue;}
                if(!id.toString().equals(path.getFileName().toString())||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
                Bucket b=new Bucket(id,path);
                try {
                TreeMap<Long,Entry> sorted=new TreeMap<>();try(DirectoryStream<Path> items=Files.newDirectoryStream(path)){
                    for(Path item:items){String name=item.getFileName().toString();
                        if(!Files.isRegularFile(item,LinkOption.NOFOLLOW_LINKS))continue;
                        if(name.startsWith("inflight-")&&name.endsWith(".tmp")){Files.deleteIfExists(item);continue;}
                        Matcher m=NAME.matcher(name);if(!m.matches())continue;
                        long order=Long.parseLong(m.group(1)),size=Files.size(item);
                        if(order==0||size==0||size>NativeBlueprintLimits.FILE_BYTES){Files.deleteIfExists(item);continue;}
                        // Order collisions can only come from a damaged index. Keep the same winner on every restart.
                        Entry old=sorted.get(order);
                        if(old!=null){if(old.sha.compareTo(m.group(2))<=0){Files.deleteIfExists(item);continue;}
                            Files.deleteIfExists(old.path);}
                        sorted.put(order,new Entry(b,m.group(2),item,size,order));}
                }
                for(Entry e:sorted.values()){b.next=e.order+1;
                    if(b.entries.containsKey(e.sha)){Files.deleteIfExists(e.path);continue;}b.entries.put(e.sha,e);
                    b.size+=e.size;}
                bytes+=b.size;
                try { trim(b); } catch(IOException failure) { bytes-=b.size;throw failure; }
                owners.put(id,b);
                } catch(IOException|DirectoryIteratorException unavailable) {
                    // A broken owner must not prevent everybody from loading the world, nor allow the
                    // same owner to replace unknown data. Reserve its normal maximum until restart.
                    inaccessibleOwners.add(id);bytes+=PLAYER_BYTES;
                }
            }
        }
    }
    private static void directory(Path p)throws IOException{
        if(Files.notExists(p,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(p);
        require(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS),"原生蓝图私缓存目录不可用");
    }
    private static void require(boolean ok,String why)throws IOException{if(!ok)throw new IOException(why);}
    private Bucket owner(UUID id)throws IOException{
        require(id!=null,"缺少已认证的玩家");require(!inaccessibleOwners.contains(id),"该玩家的原生蓝图缓存无法读取；请管理员检查目录权限后重新加载");Bucket b=owners.get(id);if(b!=null)return b;
        Path p=root.resolve(id.toString());directory(p);b=new Bucket(id,p);owners.put(id,b);return b;
    }
    void configure(int slots)throws IOException{
        require(slots>=1&&slots<=MAX_SLOTS,"无效的私缓存数量");limit=slots;for(Bucket b:owners.values())trim(b);
    }
    int slots(){return limit;}
    private static boolean digest(String sha){return sha!=null&&sha.matches("[0-9a-f]{64}");}
    Pin find(UUID user,String sha,int length)throws IOException{
        require(digest(sha)&&length>0&&length<=NativeBlueprintLimits.FILE_BYTES,"原生源身份或大小无效");
        Entry e=owner(user).entries.get(sha);if(e==null)return null;
        // A stale disk index is a cache miss, never a permanent refusal to re-upload.
        var attrs=attributes(e.path);
        if(attrs==null||!attrs.isRegularFile()||attrs.size()!=e.size){remove(e.owner,e);return null;}
        if(e.parsed!=null&&(!Objects.equals(e.stamp.fileKey(),attrs.fileKey())||!e.stamp.lastModifiedTime().equals(attrs.lastModifiedTime()))){
            require(e.pins==0,"服务器私缓存文件在使用时被外部替换");forget(e);
        }
        if(e.parsed==null){
            NativeBlueprintDefinition d;
            try{d=NativeBlueprintDefinition.open(e.path);}
            catch(IOException malformed){remove(e.owner,e);return null;}
            if(!sha.equals(d.revision())){d.close();remove(e.owner,e);return null;}
            e.parsed=d;e.stamp=attrs;hotBytes+=e.size;hot.put(e,true);
        }
        if(e.size!=length){trimHot();throw new IOException("原生源长度与缓存不匹配");}
        hot.put(e,true);Pin pin=new Pin(e,e.parsed);trimHot();return pin;
    }
    private static java.nio.file.attribute.BasicFileAttributes attributes(Path path)throws IOException{
        try{return Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
        catch(NoSuchFileException missing){return null;}
    }
    void release(Pin pin)throws IOException{
        if(pin==null||pin.closed)return;pin.closed=true;Entry e=pin.entry;e.pins--;
        if(e.provisional){e.parsed.close();e.parsed=null;e.plan=null;}
        trim(e.owner);trimHot();
    }
    private void forget(Entry e)throws IOException{
        if(e.parsed!=null){try{e.parsed.close();}finally{hot.remove(e);hotBytes-=e.size;e.parsed=null;e.plan=null;e.stamp=null;}}
    }
    private void trimHot()throws IOException{
        for(Entry e:new ArrayList<>(hot.keySet())){if(hot.size()<=HOT_FILES&&hotBytes<=HOT_BYTES)break;if(e.pins==0)forget(e);}
    }
    private void remove(Bucket b,Entry e)throws IOException{
        require(e.pins==0,"施工占用缓存版本");forget(e);Files.deleteIfExists(e.path);
        b.entries.remove(e.sha);b.size-=e.size;bytes-=e.size;
    }
    private void trim(Bucket b)throws IOException{
        for(var e:new ArrayList<>(b.entries.values())){if(b.entries.size()<=limit&&b.size<=PLAYER_BYTES)break;if(e.pins==0)remove(b,e);}
    }
    private List<Entry> victims(Bucket b,int size)throws IOException{
        List<Entry> victims=new ArrayList<>();int count=b.entries.size();long used=b.size;
        for(Entry e:b.entries.values()){if(count+1<=limit&&used+size<=PLAYER_BYTES)break;
            if(e.pins!=0)continue;victims.add(e);used-=e.size;count--;}
        require(count+1<=limit&&used+size<=PLAYER_BYTES,"该玩家私缓存已满，缓存条目正在施工");return victims;
    }
    void begin(UUID user,UUID request,String sha,int length,long now)throws IOException{
        require(digest(sha)&&length>0&&length<=NativeBlueprintLimits.FILE_BYTES&&request!=null,"原生源身份或大小无效");
        Bucket b=owner(user);require(!b.entries.containsKey(sha)&&!uploads.containsKey(user)&&uploads.size()<MAX_TRANSFERS,"私缓存已有版本或正在上传");
        victims(b,length);require(bytes+reserved+length<=GLOBAL_BYTES,"全服原生私缓存已满");
        Path temp=Files.createTempFile(b.dir,"inflight-",".tmp");try{
            Upload u=new Upload(request,b,sha,length,temp,new BufferedOutputStream(Files.newOutputStream(temp,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)),now);
            uploads.put(user,u);reserved+=length;
        }catch(IOException e){Files.deleteIfExists(temp);throw e;}
    }
    int append(UUID user,UUID request,int offset,byte[] data,long now)throws IOException{
        Upload u=uploads.get(user);require(u!=null&&u.request.equals(request),"上传请求已失效");
        try{require(now-u.active<TTL_NANOS&&offset==u.offset&&data!=null&&data.length>0&&data.length<=FRAME&&data.length<=u.length-offset,"原生上传帧过期或偏移无效");
            u.stream.write(data);u.offset+=data.length;u.active=now;return u.offset;
        }catch(IOException e){abort(user,request);throw e;}
    }
    Pin finish(UUID user,UUID request)throws IOException{
        Upload u=uploads.get(user);require(u!=null&&u.request.equals(request),"上传请求已失效");
        try{
            require(u.offset==u.length&&u.staged==null,"原生源未传完或已经完成");u.stream.close();
            NativeBlueprintDefinition verified=NativeBlueprintDefinition.open(u.temp);
            if(!verified.revision().equals(u.sha)){verified.close();throw new IOException("上传的原生源与已保存版本不同");}
            Entry entry=new Entry(u.owner,u.sha,u.temp,u.length,u.owner.next);
            entry.provisional=true;entry.parsed=verified;u.staged=new Pin(entry,verified);return u.staged;
        }catch(IOException|RuntimeException failure){abort(user,request);throw failure;}
    }
    /** Only a source accepted by the real construction registry may consume a FIFO slot. */
    void promote(UUID user,UUID request)throws IOException{
        Upload u=uploads.get(user);if(u==null||!u.request.equals(request)||u.staged==null)return;
        Entry entry=u.staged.entry;Bucket b=u.owner;List<Entry> evict=victims(b,u.length);
        require(entry.order==b.next&&b.next<1_000_000_000_000_000_000L,"私缓存序号溢出");
        Path dst=b.dir.resolve(String.format(Locale.ROOT,"%018d-%s.gtnb",b.next,u.sha));
        var stamp=attributes(u.temp);require(stamp!=null&&stamp.size()==u.length,"临时原生源已变化");
        Files.move(u.temp,dst,StandardCopyOption.ATOMIC_MOVE);
        uploads.remove(user);reserved-=u.length;
        b.next++;entry.path=dst;entry.provisional=false;entry.stamp=stamp;
        b.entries.put(u.sha,entry);b.size+=u.length;bytes+=u.length;hot.put(entry,true);hotBytes+=u.length;
        for(Entry victim:evict)remove(b,victim);
    }
    void abort(UUID user,UUID request)throws IOException{
        Upload u=uploads.get(user);if(u==null||!u.request.equals(request))return;uploads.remove(user);reserved-=u.length;
        try{u.stream.close();}finally{try{if(u.staged!=null)release(u.staged);}finally{Files.deleteIfExists(u.temp);}}
    }
    void expire(long now)throws IOException{
        for(var u:new ArrayList<>(uploads.values()))if(u.staged==null&&now-u.active>=TTL_NANOS)abort(u.owner.owner,u.request);
    }
    public void close()throws IOException{for(var u:new ArrayList<>(uploads.values()))abort(u.owner.owner,u.request);for(Entry e:new ArrayList<>(hot.keySet()))if(e.pins==0)forget(e);}
}
