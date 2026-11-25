// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import net.minecraft.nbt.NBTTagCompound;
import com.miaokatze.gtmb.hologram.*;
import com.miaokatze.gtmb.hologram.HologramLocalBlueprintDocument.LocalSaveIntent;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCodecRegression;

/** Makes the real supported candidate path cross the storage/viewport/material/editor/save boundaries. */
public final class HologramNativeViewportRegression {
    private HologramNativeViewportRegression(){}
    public static void run(){try{exercise();}catch(Exception e){throw new IllegalStateException(e);}}
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static final String UUID_TEXT="77777777-7777-7777-7777-777777777777",ID="local_"+UUID_TEXT;
    private static final String STONE="{\"block\":\"minecraft:stone\",\"item\":\"minecraft:stone\",\"count\":1,\"x\":%d,\"y\":%d,\"z\":%d}";
    private static void exercise()throws Exception {
        byte[] PNG=NativeBlueprintCodecRegression.png();Path dir=Files.createTempDirectory("gtmb-native-editor-");
        try{
            HologramClientLibrary lib=new HologramClientLibrary(dir);Files.createDirectories(lib.directory());
            String cells=String.format(java.util.Locale.ROOT,STONE,-2,0,1)+","+String.format(java.util.Locale.ROOT,STONE,18,1,1)+
                ",{\"x\":32,\"y\":1,\"z\":-8,\"block\":\"offline:unknown\",\"item\":\"offline:unknown\",\"count\":1}";
            String json="{\"schema\":2,\"blueprint\":{\"id\":\""+ID+"\",\"name\":\"Edited native\",\"authorName\":\"Fixture\",\"authorUuid\":\""+UUID_TEXT+"\",\"cells\":["+cells+"]}}";
            Path source=lib.directory().resolve(ID+".json");
            HologramBlueprintArtifact.writePair(source,json.getBytes(StandardCharsets.UTF_8),PNG);
            var staged=lib.prepareNativeUpgrade(ID,()->true);HologramNativeBlueprintStorage.activate(lib.directory(),staged,staged.revision,()->true);
            var doc=open(lib,ID);
            var staleDoc=open(lib,ID);
            try {
                var old=doc.nativeDraft();var idx=new HologramNativeViewport.Index(old.space(),()->true);
                var state=HologramLocalBlueprintWorkspace.nativeView(doc.nativeEditorMetadata(),old,idx,null);
                check(state.cells.size()==3&&state.parsedCells==0&&state.nativeViewport!=null,"renderer has lazy geometry");
                check(state.withMetadata(new NBTTagCompound()).nativeViewport==state.nativeViewport,"metadata keeps native owner");
                check(!doc.dirty(),"existing open is clean");
                var ui=new HologramUiModules(null);
                ui.session.initializeIsolated(state,(op,req)->{try{check(doc.apply(op,req),"formal configure routed");}
                    catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}});
                var formal=ui.config.configuration();
                formal.setInteger("scope",1);formal.setInteger("selected",1);
                ui.session.send("configure",formal,true);
                var metadata=doc.nativeEditorMetadata();
                check(metadata.getInteger("scope")==1&&metadata.getInteger("selected")==1,"formal scope/details options");
                var unchanged=HologramLocalBlueprintWorkspace.publishNativeView(metadata,old,idx,state,()->true);
                check(unchanged.follows(state)&&unchanged.cells==state.cells&&unchanged.regions==state.regions,"no-op UI reuse renderer lineage");
                var changed=(NBTTagCompound)formal.copy();changed.getCompoundTag("channels").setInteger("notAllowed",8);
                try{doc.apply("configure",changed);throw new AssertionError("source redefinition accepted");}
                catch(java.io.IOException expected){}
                check(!doc.dirty()&&!state.plainDisplayExamples.isEmpty(),"unchanged metadata remains clean; ordinary palette supplies prepared template");
                var a=state.regions.at(-2,0,1);var b=state.regions.at(18,1,1);check(a!=null&&b!=null&&state.regions.at(0,0,1)==null,"sparse negative and disjoint positive regions");
                check(state.regions.regions.size()==3&&a==state.cells.get(a.index),"region picking and list share the same cell");
                var all=new HologramLocalMaterialReview(state,new NBTTagCompound());all.refresh(null,-1);
                check(all.status().contains("3 个目标格")&&all.status().contains("1 格未核算"),"unknown cost counted and signalled: "+all.status());
                check(hasRequired(all.visible(),"2"),"two ordinary blocks are exactly two items: "+all.visible());
                var selected=new HologramSelection();selected.bindMaterials(state);selected.click(a.index,false);selected.click(b.index,true);
                NBTTagCompound settings=new NBTTagCompound();settings.setInteger("scope",2);settings.setIntArray("selectedIndices",new int[]{a.index,b.index});
                var selection=new HologramLocalMaterialReview(state,settings,true,selected);
                check(selection.status().contains("2 个目标格")&&!selection.status().contains("未核算"),"chosen materials equal chosen ordinals");
                NBTTagCompound change=new NBTTagCompound();change.setInteger("selected",a.index);change.setBoolean("excluded",true);change.setString("prompt","left untouched");
                doc.apply("editCellFlags",change);var after=HologramLocalBlueprintWorkspace.nativeView(doc.nativeEditorMetadata(),doc.nativeDraft(),idx,state.nativeViewport);
                check(after.regions.at(-2,0,1).tag().getBoolean("excluded")&&!a.tag().getBoolean("excluded"),"one cell edit keeps previous revision immutable");
                check(after.nativeViewport.geometry==state.nativeViewport.geometry&&after.nativeViewport.index==idx,"editing did not construct a second geometry");
                check(new HologramLocalMaterialReview(after,new NBTTagCompound()).status().contains("2 个目标格"),"excluded cell doesn't cost materials");
                check(doc.dirty(),"edit dirty");
                doc.apply("editUndo",new NBTTagCompound());check(doc.nativeDraft()==old&&!doc.dirty(),"undo to opened definition is clean");
                doc.apply("editRedo",new NBTTagCompound());check(doc.dirty(),"redo dirty");
                var frozen=doc.nativeSaveRevision(new NBTTagCompound(),"Fixture",UUID.fromString(UUID_TEXT),ID,LocalSaveIntent.SAVE_CURRENT);
                boolean stale=false;try{HologramLocalBlueprintWorkspace.saveNativePair(lib,frozen,PNG,ID,LocalSaveIntent.SAVE_CURRENT,doc.nativeReceipt(),()->false);}catch(java.io.IOException expected){stale=true;}check(stale,"retired user cannot save");
                var receipt=HologramLocalBlueprintWorkspace.saveNativePair(lib,frozen,PNG,ID,LocalSaveIntent.SAVE_CURRENT,doc.nativeReceipt(),()->true);
                doc.nativeSaved(doc.revision(),frozen,ID,receipt);
                check(!doc.dirty(),"save marks accepted baseline clean");
                NBTTagCompound staleChange=new NBTTagCompound();staleChange.setString("prompt","stale A");
                staleDoc.apply("editBlueprintPrompt",staleChange);
                var staleFrozen=staleDoc.nativeSaveRevision(new NBTTagCompound(),"Fixture",UUID.fromString(UUID_TEXT),ID,LocalSaveIntent.SAVE_CURRENT);
                byte[] bBytes=Files.readAllBytes(receipt.source),bImage=Files.readAllBytes(receipt.png);
                try{HologramLocalBlueprintWorkspace.saveNativePair(lib,staleFrozen,PNG,ID,LocalSaveIntent.SAVE_CURRENT,staleDoc.nativeReceipt(),()->true);
                    throw new AssertionError("stale open overwrote later B");}catch(java.io.IOException expected){check(expected.getMessage().contains("重新打开"),"stale open exact refusal");}
                check(Arrays.equals(bBytes,Files.readAllBytes(receipt.source))&&Arrays.equals(bImage,Files.readAllBytes(receipt.png)),"stale save retained B exact files");
                String copy="local_88888888-8888-8888-8888-888888888888";
                var copyFrozen=staleDoc.nativeSaveRevision(new NBTTagCompound(),"Fixture",UUID.fromString(UUID_TEXT),copy,LocalSaveIntent.SAVE_AS_NEW);
                HologramLocalBlueprintWorkspace.saveNativePair(lib,copyFrozen,PNG,copy,LocalSaveIntent.SAVE_AS_NEW,staleDoc.nativeReceipt(),()->true);
                var afterCopy=lib.readPrivateEditorSource(ID).nativePair();
                check(afterCopy.physicalHash.equals(receipt.physicalHash)&&afterCopy.pngHash.equals(receipt.pngHash),"Save As leaves B active");
                lib.delete(copy);
                // Same source with dissimilar image changes receipt. Old preview provenance is never accepted.
                byte[] newImage=tiny(640);var latest=HologramLocalBlueprintWorkspace.saveNativePair(lib,frozen,newImage,ID,LocalSaveIntent.SAVE_CURRENT,receipt,()->true);
                check(latest.physicalHash.equals(receipt.physicalHash)&&!latest.pngHash.equals(receipt.pngHash),"pair CAS includes image-only generation");
                try{HologramLocalBlueprintWorkspace.saveNativePair(lib,frozen,PNG,ID,LocalSaveIntent.SAVE_CURRENT,receipt,()->true);
                    throw new AssertionError("old screenshot receipt accepted");}catch(java.io.IOException expected){}
                try{HologramLocalBlueprintWorkspace.saveNativePair(lib,frozen,tiny(1),ID,LocalSaveIntent.SAVE_CURRENT,latest,()->true);
                    throw new AssertionError("1x1 native preview accepted");}catch(java.io.IOException expected){check(expected.getMessage().contains("640"),"invalid raster refused");}
                check(lib.readPrivateEditorSource(ID).nativePair().pngHash.equals(latest.pngHash),"bad raster never overwrites indexed pair");
                var reread=open(lib,ID);
                try{
                    check(!reread.dirty(),"reopened is clean");
                    var again=HologramLocalBlueprintWorkspace.nativeView(reread.nativeEditorMetadata(),reread.nativeDraft(),new HologramNativeViewport.Index(reread.nativeDraft().space(),()->true),null);
                    check(again.regions.at(-2,0,1).tag().getBoolean("excluded")&&"left untouched".equals(again.regions.at(-2,0,1).tag().getString("prompt")),"saved editable native point reopened");
                    check(!reread.nativeDraft().revision().source().revision().equals(staged.revision)&&lib.list().size()==1,"new revision in same logical card");
                    check(Arrays.equals(json.getBytes(StandardCharsets.UTF_8),Files.readAllBytes(source)),"original retained");
                }finally{reread.closeNative();}
            }finally{doc.closeNative();staleDoc.closeNative();}
            try{com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCli.convertPair(source,HologramBlueprintArtifact.previewPath(source),source.getParent(),ID,-1,()->true);
                throw new AssertionError("CLI activates in live library");}catch(java.io.IOException expected){check(expected.getMessage().contains("separate export"),"explicit export admission");}
            special(dir);
            String metadata=System.getProperty("gtmb.reviewedMetadata.fixture","");if(!metadata.isEmpty())mixed(dir,Paths.get(metadata).getParent());
        }finally{try(Stream<Path> files=Files.walk(dir)){for(Path p:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(p);}}
    }
    private static byte[] tiny(int size)throws java.io.IOException{
        var image=new java.awt.image.BufferedImage(size,size,java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0xffeeaabb);var out=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"PNG",out);return out.toByteArray();
    }
    private static void special(Path game)throws Exception{
        HologramClientLibrary lib=new HologramClientLibrary(game.resolve("specials"));Path dir=lib.directory();Files.createDirectories(dir);
        net.minecraftforge.fluids.Fluid water=net.minecraftforge.fluids.FluidRegistry.getFluid("water");
        if(water==null)throw new AssertionError("Forge bootstrap missing standard fluid");
        NBTTagCompound itemData=new NBTTagCompound();itemData.setString("id","fixture:tile");
        itemData.setTag("mFluid",new net.minecraftforge.fluids.FluidStack(water,1250).writeToNBT(new NBTTagCompound()));
        NBTTagCompound tile=new NBTTagCompound();tile.setString("id","Chest");
        net.minecraft.nbt.NBTTagList inventory=new net.minecraft.nbt.NBTTagList();var diamond=new net.minecraft.item.ItemStack(net.minecraft.init.Items.diamond,3);inventory.appendTag(diamond.writeToNBT(new NBTTagCompound()));tile.setTag("Inventory",inventory);
        String aeFields=nbt(itemData),tileFields=nbt(tile);
        NBTTagCompound providerData=new NBTTagCompound();providerData.setLong("opaqueProviderLong",1234567891234567L);
        providerData.setByteArray("opaqueBytes",new byte[]{0,-1,17});String unknown=nbt(providerData);
        String[] blocks={"stone","chest","chest"};String[] extras={",\"itemNbt\":\""+aeFields+"\"",",\"tileNbt\":\""+tileFields+"\"",",\"tileNbt\":\""+tileFields+"\""};
        StringBuilder rows=new StringBuilder();for(int i=0;i<3;i++){if(i>0)rows.append(',');rows.append("{\"x\":").append(i).append(",\"y\":0,\"z\":0,\"block\":\"minecraft:").append(blocks[i]).append("\",\"item\":\"minecraft:").append(blocks[i]).append("\",\"count\":1").append(extras[i]).append('}');}
        rows.append(",{\"x\":3,\"y\":0,\"z\":0,\"block\":\"appliedenergistics2:tile.BlockDrive\",\"item\":\"appliedenergistics2:tile.BlockDrive\",\"tileNbt\":\"").append(unknown).append("\"}");
        rows.append(",{\"x\":4,\"y\":0,\"z\":0,\"block\":\"gtswn:network_quantum_node\",\"item\":\"gtswn:network_quantum_node\",\"tileNbt\":\"").append(unknown).append("\"}");
        String src="{\"schema\":2,\"blueprint\":{\"id\":\""+ID+"\",\"name\":\"Specials\",\"authorName\":\"Fixture\",\"authorUuid\":\""+UUID_TEXT+"\",\"cells\":["+rows+"]}}";
        Path old=dir.resolve(ID+".json");HologramBlueprintArtifact.writePair(old,src.getBytes(StandardCharsets.UTF_8),NativeBlueprintCodecRegression.png());
        var prepared=lib.prepareNativeUpgrade(ID,()->true);HologramNativeBlueprintStorage.activate(dir,prepared,prepared.revision,()->true);
        var doc=open(lib,ID);try{
            var draft=doc.nativeDraft();draft.preparePayloads(()->true);int preparedCount=draft.payloadDecodeCount();
            var state=HologramLocalBlueprintWorkspace.nativeView(doc.nativeEditorMetadata(),draft,new HologramNativeViewport.Index(draft.space(),()->true),null);
            check(preparedCount==3&&draft.payloadDecodeCount()==preparedCount,"distinct descriptors share three decoded binary payloads, no owner re-inflate");
            var r=new HologramLocalMaterialReview(state,new NBTTagCompound());r.refresh(null,-1);
            check(r.status().contains("2 格未核算"),"offline AE/node unresolved, never free or silently treated as ordinary");
            for(int rank:new int[]{3,4}){
                var point=draft.space().atOrdinal(rank);var wrap=draft.space().wrapper(point.descriptor);
                var fields=draft.revision().source().restoredFields(wrap);
                byte[] encoded=Base64.getDecoder().decode(fields.get("tileNbt").getAsString());
                var round=net.minecraft.nbt.CompressedStreamTools.readCompressed(new java.io.ByteArrayInputStream(encoded));
                check(providerData.equals(round),"provider payload kept exact including precise Long/byte array");
            }
            var material=r.visible();check(hasRequired(material,"1250")&&hasRequired(material,"6"),"retained 1250 mB fluid + chest items preserve exact quantities: "+material);
            var t1=state.cells.get(1).tag();var t2=state.cells.get(2).tag();check(t1.getCompoundTag("targetDisplay").getCompoundTag("nativeTile").hasKey("Inventory",9)&&t2.getCompoundTag("targetDisplay").getCompoundTag("nativeTile").hasKey("Inventory",9),"two cells retain same payload");
            t1.getCompoundTag("targetDisplay").getCompoundTag("nativeTile").removeTag("Inventory");check(state.cells.get(2).tag().getCompoundTag("targetDisplay").getCompoundTag("nativeTile").hasKey("Inventory",9),"returned NBT does not alias another cell");
        }finally{doc.closeNative();}
    }
    private static String nbt(NBTTagCompound tag)throws Exception{
        java.io.ByteArrayOutputStream buf=new java.io.ByteArrayOutputStream();net.minecraft.nbt.CompressedStreamTools.writeCompressed(tag,buf);
        return Base64.getEncoder().encodeToString(buf.toByteArray());
    }
    private static boolean hasRequired(NBTTagCompound tag,String expected){
        var list=tag.getTagList("rows",10);for(int i=0;i<list.tagCount();i++)if(expected.equals(list.getCompoundTagAt(i).getString("requiredExact")))return true;return false;
    }
    private static HologramLocalBlueprintDocument open(HologramClientLibrary lib,String id)throws Exception{
        return HologramLocalBlueprintWorkspace.openNativeEditorSource(lib.readPrivateEditorSource(id),id);
    }
    private static void mixed(Path game,Path fixture)throws Exception{
        String id="local_9861e912-720f-5417-a01a-6e6e28859680";
        HologramClientLibrary lib=new HologramClientLibrary(game.resolve("real"));Path dir=lib.directory();Files.createDirectories(dir);
        Path old=dir.resolve(id+".json");Files.copy(fixture.resolve(id+".json"),old);Files.copy(fixture.resolve(id+".png"),dir.resolve(id+".png"));
        var staged=lib.prepareNativeUpgrade(id,()->true);HologramNativeBlueprintStorage.activate(dir,staged,staged.revision,()->true);
        long start=System.nanoTime();var doc=open(lib,id);long open=System.nanoTime();
        try {
            var draft=doc.nativeDraft();var idx=new HologramNativeViewport.Index(draft.space(),()->true);
            var state=HologramLocalBlueprintWorkspace.nativeView(doc.nativeEditorMetadata(),draft,idx,null);
            long ready=System.nanoTime();var review=new HologramLocalMaterialReview(state,new NBTTagCompound());long read=System.nanoTime();
            check(state.cells.size()==123369&&state.parsedCells==0&&review.ready(),"actual mixed native editor and material review");
            var ui=new HologramUiModules(null);ui.session.initializeIsolated(state,(op,req)->{});
            int accessed=state.nativeViewport.materializedCells();
            long markerStart=System.nanoTime();var markers=ui.annotations.currentMarkers();long markersNs=System.nanoTime()-markerStart;
            check(markers.isEmpty()&&state.nativeViewport.materializedCells()==accessed,"official zero markers do not traverse geometry");
            var global=new NBTTagCompound();var issue=new NBTTagCompound();issue.setString("severity","warning");
            issue.setString("code","UNKNOWN_CELL");issue.setString("title","global");var issues=new net.minecraft.nbt.NBTTagList();issues.appendTag(issue);global.setTag("aeIssues",issues);
            var reported=state.withMetadata(global);ui.session.initializeIsolated(reported,(op,req)->{});accessed=state.nativeViewport.materializedCells();
            check(!ui.annotations.currentMarkers().isEmpty()&&state.nativeViewport.materializedCells()==accessed,"global AE report has no occupied-cell traversal");
            System.out.println("GTMB_NATIVE_ANNOTATIONS zeroMarkersNs="+markersNs+" constructedCells=0");
            for(int i:new int[]{0,128,4095,23456,123368}){var row=state.cells.get(i);check(state.regions.at(row.x,row.y,row.z).index==i,"mixed point rank "+i);}
            NBTTagCompound command=new NBTTagCompound();command.setInteger("selected",0);command.setBoolean("excluded",true);long beginEdit=System.nanoTime();
            if(draft.revision().source().allowsGeometryMutation()){
                doc.apply("editCellFlags",command);
                var modified=HologramLocalBlueprintWorkspace.nativeView(doc.nativeEditorMetadata(),doc.nativeDraft(),idx,state.nativeViewport);
                check(modified.cells.get(0).tag().getBoolean("excluded"),"mixed edit");
            }
            System.out.println("GTMB_NATIVE_STAGE2_MIXED cells="+state.cells.size()+" regions="+idx.regions.size()+" variants="+idx.variants.size()+" openNs="+(open-start)+" readySinceOpenNs="+(ready-start)+" materialOpenNs="+(read-ready)+" editNs="+(System.nanoTime()-beginEdit)+" status="+review.status());
        }finally{doc.closeNative();}
    }
}
