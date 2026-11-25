// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import com.miaokatze.gtmb.common.items.hologram.ItemNekoHologramProjector;
import com.miaokatze.gtmb.hologram.gtnb.*;
import com.miaokatze.gtmb.util.ServerTaskScheduler;
import static com.miaokatze.gtmb.hologram.HologramPrivateNativeWire.*;

/** Exact authenticated connection -> per-player private cache -> same world/inventory transaction as single player.
 * The world thread only handles bounded controls and actor validation; source I/O + hashing run on the writer.
 */
final class HologramPrivateNativeServer implements AutoCloseable {
    interface Sink { void reply(EntityPlayerMP actor,Reply m); }
    private static HologramPrivateNativeServer live;
    private final Path saveRoot;
    private final Sink send;
    private final IntSupplier slots;
    private final ThreadPoolExecutor io;
    private HologramPrivateNativeCache cache; // I/O worker only
    private final Map<UUID,Active> requests=new HashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger retiring=new java.util.concurrent.atomic.AtomicInteger();
    private final WeakHashMap<HologramWorkspace.ActionEndpoint,Seen> recent=new WeakHashMap<>();
    private boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger requestedSlots=new java.util.concurrent.atomic.AtomicInteger();
    HologramPrivateNativeServer(Path saveRoot,IntSupplier slots,Sink send){
        this.saveRoot=saveRoot;this.slots=slots;this.send=send;
        io=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(48),r->{Thread t=new Thread(r,"GTMB private saved blueprint I/O");t.setDaemon(true);return t;});
    }
    static void receive(HologramWorkspace.ActionEndpoint ep,Request m){
        if(!HologramWorkspace.currentActionEndpoint(ep))return;
        if(live==null){
            if(!allowed(ep,m)){
                if(m!=null&&m.op==BEGIN&&m.id!=null)HologramPrivateNativeWire.reply(ep.player,
                    new Reply(FAILED,m.id,0,0,0,0,1,"无施工权限、目标越界或料源已改变"));
                return;
            }
            var svc=HologramLibraryFacade.backend();
            if(svc==null||svc.config()==null){HologramPrivateNativeWire.reply(ep.player,new Reply(FAILED,m.id,0,0,0,0,1,"服务器蓝图服务未就绪"));return;}
            Path root=ep.world.getSaveHandler().getWorldDirectory().toPath();
            live=new HologramPrivateNativeServer(root,()->svc.config().nativeCacheSlots,HologramPrivateNativeWire::reply);
        }
        live.accept(ep,m);
    }
    static void tickLive(){if(live!=null)live.tick();}
    /** Admin changes operate on the active world only; directory traversal stays on the cache I/O worker. */
    static void configChanged(HologramServerLibrary origin,int limit){
        ServerTaskScheduler.scheduleServerTask(()->{
            HologramPrivateNativeServer s=live;
            if(s==null||s.closed||HologramLibraryFacade.backend()!=origin)return;
            if(s.requestedSlots.getAndSet(limit)!=0)return;
            s.io.execute(()->{
                int value;
                while((value=s.requestedSlots.getAndSet(0))!=0)try{
                    if(s.cache!=null&&s.cache.slots()!=value)s.cache.configure(value);
                }catch(IOException failure){org.apache.logging.log4j.LogManager.getLogger("GTMB").warn("Private native cache quota update",failure);}
            });
        });
    }
    static void stopLive(){if(live!=null){live.close();live=null;}}
    private static boolean allowed(HologramWorkspace.ActionEndpoint ep,Request begin){
        if(begin==null||begin.op!=BEGIN||!HologramWorkspace.currentActionEndpoint(ep)||HologramWorkspace.constructionGate(ep)==null)return false;
        var p=ep.player;ItemStack tool=p.getHeldItem();
        return !p.isDead&&p.capabilities.allowEdit&&!p.capabilities.isCreativeMode&&p.dimension==begin.dimension
            &&ItemNekoHologramProjector.isProjector(tool)&&tool.hasTagCompound()&&begin.tool!=null
            &&begin.tool.toString().equals(tool.getTagCompound().getString(HologramSessionKeys.TOOL_ID))
            &&HologramProjectedMainPolicy.settingsKey(HologramMaterials.read(tool))==begin.sources
            &&HologramWorldFlow.worldPosition(begin.x,begin.y,begin.z,ep.world.getHeight())&&ep.world.blockExists(begin.x,begin.y,begin.z)
            &&HologramSessionPolicy.previewReach(p,begin.x,begin.y,begin.z);
    }
    private static final class Seen{
        long intent;UUID id;Reply reply;
    }
    private static final class Active{
        final HologramWorkspace.ActionEndpoint ep;final Request begin;
        final HologramNativeLocalConstruction.Attempt attempt;
        volatile HologramPrivateNativeCache.Pin pin;
        volatile boolean retired;
        boolean loading,executing;int offset;long sequence,resultSequence,last=System.nanoTime();
        Active(HologramWorkspace.ActionEndpoint ep,Request begin){this.ep=ep;this.begin=begin;attempt=new HologramNativeLocalConstruction.Attempt(begin.intent,begin.id);}
    }
    private HologramPrivateNativeCache cache(int limit)throws IOException{
        if(cache==null)cache=new HologramPrivateNativeCache(saveRoot,limit);
        else if(limit!=cache.slots())cache.configure(limit);
        return cache;
    }
    void accept(HologramWorkspace.ActionEndpoint ep,Request m){
        if(closed||m==null||m.id==null||!HologramWorkspace.currentActionEndpoint(ep))return;
        Active a=requests.get(ep.actor);
        if(m.op==BEGIN){
            if(!allowed(ep,m)){send.reply(ep.player,new Reply(FAILED,m.id,0,0,0,0,1,"无施工权限、目标越界或料源已改变"));return;}
            Seen seen=recent.computeIfAbsent(ep,unused->new Seen());
            if(m.id.equals(seen.id)&&m.intent==seen.intent){if(seen.reply!=null)send.reply(ep.player,seen.reply);return;}
            if(m.intent<=seen.intent||a!=null||requests.size()+retiring.get()>=8){send.reply(ep.player,new Reply(FAILED,m.id,0,0,0,0,1,"过期或已有原生施工"));return;}
            seen.intent=m.intent;seen.id=m.id;seen.reply=null;
            Active next=new Active(ep,m);requests.put(ep.actor,next);int limit=slots.getAsInt();
            work(next,()->{var c=cache(limit);c.expire(System.nanoTime());var pin=c.find(ep.actor,m.sha,m.length);
                if(pin==null){c.begin(ep.actor,m.id,m.sha,m.length,System.nanoTime());return null;}
                return prepare(next,pin);});return;
        }
        if(a==null||a.ep!=ep||!a.begin.id.equals(m.id))return;
        if(m.op==CANCEL){a.attempt.stop();if(!a.executing)end(a,CANCELLED,0,0,0,"施工已取消");return;}
        if(a.executing)return; // Late file frames cannot dispose an in-use execution pin.
        if(m.op!=DATA||a.loading||m.offset!=a.offset||!allowed(ep,a.begin)){end(a,FAILED,0,0,0,"上传非预期或上下文已变");return;}
        int limit=slots.getAsInt();
        work(a,()->{var c=cache(limit);int next=c.append(ep.actor,m.id,m.offset,m.bytes,System.nanoTime());
            a.offset=next;if(next<a.begin.length)return null;return prepare(a,c.finish(ep.actor,m.id));});
    }
    private HologramNativeLocalConstruction.Prepared prepare(Active a,HologramPrivateNativeCache.Pin pin)throws IOException{
        a.pin=pin;
        if(pin.entry.plan==null)pin.entry.plan=HologramNativeLocalConstruction.preparePinned(
            new HologramNativeBlueprintDraft(new NativeBlueprintEdits(pin.definition)),()->true,()->!a.retired&&!a.attempt.cancelled());
        return pin.entry.plan.bound(()->!a.retired&&!pin.closed);
    }
    private void work(Active a,Callable<HologramNativeLocalConstruction.Prepared> op){
        a.loading=true;a.last=System.nanoTime();
        try{io.execute(()->{
            try{
                if(a.retired)return;
                var plan=op.call();
                ServerTaskScheduler.scheduleServerTask(()->{if(a.retired)return;a.loading=false;a.last=System.nanoTime();
                    if(!allowed(a.ep,a.begin)){end(a,FAILED,0,0,0,"施工上下文已变化");return;}
                    if(plan==null){emit(a,NEED,0,0,0,"");return;}
                    a.executing=true;Request b=a.begin;
                    HologramNativeLocalConstruction.submit(a.attempt,plan,()->allowed(a.ep,b)?a.ep.player:null,b.tool.toString(),b.dimension,
                        new int[]{b.x,b.y,b.z},b.pose,b.sources,b.voucher,b.bindingSlots);
                });
            }catch(Exception e){ServerTaskScheduler.scheduleServerTask(()->{if(!a.retired)end(a,FAILED,0,0,0,e.getMessage());});}
        });}catch(RejectedExecutionException full){end(a,FAILED,0,0,0,"服务器原生蓝图队列已满");}
    }
    private void emit(Active a,int op,int placed,int satisfied,int total,String why){
        Reply m=new Reply(op,a.begin.id,a.offset,placed,satisfied,total,++a.sequence,why);
        var seen=recent.get(a.ep);if(seen!=null&&a.begin.id.equals(seen.id))seen.reply=m;
        if(HologramWorkspace.currentActionEndpoint(a.ep))send.reply(a.ep.player,m);
    }
    private void end(Active a,int op,int placed,int satisfied,int total,String why){
        if(a.retired)return;
        requests.remove(a.ep.actor,a);a.retired=true;a.attempt.stop();
        retiring.incrementAndGet();
        io.execute(()->{try{if(cache!=null){try{
                if(a.attempt.admitted())cache.promote(a.ep.actor,a.begin.id);
            }finally{try{cache.abort(a.ep.actor,a.begin.id);}finally{cache.release(a.pin);}}}}
            catch(IOException e){org.apache.logging.log4j.LogManager.getLogger("GTMB").warn("Private native cache cleanup",e);}
            finally{retiring.decrementAndGet();}});
        // Never leak an endpoint, upload, or source pin when the connection disappears during the last reply.
        try{emit(a,op,placed,satisfied,total,why);}catch(RuntimeException unavailable){
            org.apache.logging.log4j.LogManager.getLogger("GTMB").debug("Private native terminal reply unavailable",unavailable);
        }
    }
    void tick(){
        for(Active a:new ArrayList<>(requests.values())){
            if(!HologramWorkspace.currentActionEndpoint(a.ep)||!allowed(a.ep,a.begin)){
                a.attempt.stop();if(!a.executing){end(a,CANCELLED,0,0,0,"原生施工已取消");continue;}
            }
            if(!a.executing){if(System.nanoTime()-a.last>=HologramPrivateNativeCache.TTL_NANOS)end(a,FAILED,0,0,0,"上传已超时");continue;}
            var r=a.attempt.result();if(r.sequence<=a.resultSequence)continue;a.resultSequence=r.sequence;
            switch(r.phase){
                case EXECUTING -> emit(a,PROGRESS,r.placed,r.satisfied,r.total,r.detail);
                case COMPLETE -> end(a,COMPLETE,r.placed,r.satisfied,r.total,r.detail);
                case CANCELLED -> end(a,CANCELLED,r.placed,r.satisfied,r.total,r.detail);
                case FAILED -> end(a,FAILED,r.placed,r.satisfied,r.total,r.detail);
                default -> {}
            }
        }
    }
    @Override public void close(){if(closed)return;closed=true;
        for(Active a:new ArrayList<>(requests.values()))end(a,CANCELLED,0,0,0,"服务器已停止");
        io.execute(()->{if(cache!=null)try{cache.close();}catch(IOException ignored){}});io.shutdown();recent.clear();}
}
