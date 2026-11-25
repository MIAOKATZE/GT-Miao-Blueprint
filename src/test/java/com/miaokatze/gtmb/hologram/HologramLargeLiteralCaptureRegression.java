// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram;

import java.util.Arrays;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import com.miaokatze.gtmb.client.hologram.HologramNativeViewportRegression;
import com.miaokatze.gtmb.testutil.MinecraftTestBootstrap;

public final class HologramLargeLiteralCaptureRegression {
    public static void main(String[] args) throws Exception {
        if(!MinecraftTestBootstrap.enter(HologramLargeLiteralCaptureRegression.class,args)) return;
        var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        var unsafe=(sun.misc.Unsafe)field.get(null);
        var world=HologramStockEbfConstructionRegression.RawWorld.create(unsafe,0,0);
        world.isRemote=true;
        for(int cx=0;cx<8;cx++)for(int cz=0;cz<8;cz++){
            long key=ChunkCoordIntPair.chunkXZ2Int(cx,cz);
            var chunks=world.theChunkProviderServer.loadedChunkHashMap;
            var chunk=(Chunk)chunks.getValueByKey(key);
            if(chunk==null){chunk=new Chunk(world,cx,cz);chunk.isChunkLoaded=true;chunks.add(key,chunk);}
            for(int section=0;section<4;section++){
                var cells=new ExtendedBlockStorage(section*16,false);
                Arrays.fill(cells.getBlockLSBArray(),(byte)Block.getIdFromBlock(Blocks.stone));
                cells.removeInvalidBlocks();
                chunk.getBlockStorageArray()[section]=cells;
            }
        }
        var selected=new HologramAreaSelection();selected.point(1,0,0,0,"replace");selected.point(2,127,63,127,"replace");
        try(var capture=new HologramLocalAreaCapture(world,0,selected)){
            long deadline=System.nanoTime()+120_000_000_000L;
            HologramLocalBlueprintDocument doc=null;
            while(doc==null){
                if(capture.progress()>=100||System.nanoTime()>=deadline)throw new AssertionError("early done/timeout");
                doc=capture.step(32768,20_000_000L);
                Thread.yield();
            }
            HologramNativeViewportRegression.largeLiteralSaveAndColdRead(doc,128*64*128);
        }
    }
}
