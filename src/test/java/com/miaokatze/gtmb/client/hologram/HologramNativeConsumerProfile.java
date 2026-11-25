// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicLong;
import com.miaokatze.gtmb.hologram.*;
import com.miaokatze.gtmb.hologram.gtnb.*;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

/** Same offline registry and actual editor preparation seam, one cold JVM per input mode.
 * Sampled total heap is diagnostic (not GPU, retained allocation attribution or a frame-time promise). */
public final class HologramNativeConsumerProfile {
    private static volatile Object keep;
    private static long used(){return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();}
    public static void main(String[] args)throws Exception{
        if(!MinecraftTestBootstrap.enter(HologramNativeConsumerProfile.class,args))return;
        java.util.concurrent.atomic.AtomicBoolean sampling=new java.util.concurrent.atomic.AtomicBoolean(true);
        System.gc();Thread.sleep(80);long before=used();AtomicLong peak=new AtomicLong(before);
        Thread sampler=new Thread(()->{while(sampling.get()){peak.accumulateAndGet(used(),Math::max);try{Thread.sleep(2);}catch(InterruptedException e){return;}}});sampler.setDaemon(true);sampler.start();
        long a=System.nanoTime();int count;String id="local_9861e912-720f-5417-a01a-6e6e28859680";
        if("legacy".equals(args[0])){
            var opened=HologramLocalBlueprintDocument.openStoredForEditor(Files.readAllBytes(Paths.get(args[1])),id,()->true,early->{},true,HologramLocalBlueprintDocument.LocalEditorOrigin.TRACKED_PRIVATE);
            var rows=opened.editor();if(rows==null)rows=opened.document().packedEditorRows(()->true);
            var prep=new HologramState.Preparation(rows.metadata(),true,new HologramBlueprintPreview.CompactCells(rows));
            while(!prep.step(4096,50_000_000L)){}
            var state=prep.result();count=state.cells.size();keep=new Object[]{opened.document(),state};
        }else{
            var def=NativeBlueprintDefinition.open(Paths.get(args[1]));
            var doc=HologramLocalBlueprintDocument.openNative(def,id,HologramLocalBlueprintDocument.LocalEditorOrigin.TRACKED_PRIVATE);
            var draft=doc.nativeDraft();var view=HologramLocalBlueprintWorkspace.nativeView(doc.nativeEditorMetadata(),draft,new HologramNativeViewport.Index(def.spatial(),()->true),null);
            count=view.cells.size();keep=new Object[]{doc,view};
        }
        long done=System.nanoTime();sampling.set(false);sampler.join();System.gc();Thread.sleep(100);
        if(count!=123369)throw new AssertionError("comparison count");
        System.out.println("GTMB_NATIVE_PROFILE mode="+args[0]+" count="+count+" coldReadyMs="+(done-a)/1000000.0+" sampledPeakOverBaseline="+(peak.get()-before)+" retainedOverBaseline="+(used()-before));
    }
}
