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
    /** The actual new-capture Save As preparation and pair publisher; the cover is a fixture, not a rendered PNG. */
    public static void largeLiteralSaveAndColdRead(HologramLocalBlueprintDocument draft, int count) throws Exception {
        Path dir=Files.createTempDirectory("gtmb-literal-million-");
        var lib=new HologramClientLibrary(dir);
        try {
            Files.createDirectories(lib.directory());
            var options=new NBTTagCompound();options.setString("name","Million selected world cells");
            try (var capture=HologramLocalBlueprintWorkspace.prepareNewNativeCapture(draft,options,"Fixture",
                    UUID.fromString(UUID_TEXT),ID,()->true)) {
                HologramLocalBlueprintWorkspace.publishNewNativeCapture(lib,capture,ID,NativeBlueprintCodecRegression.png(),()->true);
            }
            var newlyOpened=new HologramClientLibrary(dir);
            try (var cold=newlyOpened.readPrivateEditorSource(ID).openNative()) {
                int first=cold.elementAt(0,0,0);
                check(cold.cells()==count && first>0 && first==cold.elementAt(127,63,127)
                    && first==cold.elementAt(57,32,93) && cold.elementAt(128,63,127)==0,
                    "cold pair has exactly 128*64*128 real stone cells and no out-of-bounds voxel");
                System.out.println("GTMB_CAPTURE "+count+" non-air physical cells; native save + cold reads; bytes="
                    +Files.size(com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.receipt(lib.directory(),ID).source));
            }
        } finally {
            try(Stream<Path> files=Files.walk(dir)){
                for(Path file:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(file);
            }
        }
    }
    private static void exerciseNativeTopology()throws Exception{
        Path dir=Files.createTempDirectory("gtmb-native-topology-");
        HologramClientLibrary lib=new HologramClientLibrary(dir);Files.createDirectories(lib.directory());
        NBTTagCompound typed=new NBTTagCompound();typed.setLong("serial",989899899898L);typed.setByteArray("raw",new byte[]{-3,0,127});
        java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();net.minecraft.nbt.CompressedStreamTools.writeCompressed(typed,bytes);
        String binary=Base64.getEncoder().encodeToString(bytes.toByteArray());
        String json="{\"schema\":2,\"blueprint\":{\"schema\":2,\"id\":\""+ID+"\",\"name\":\"Topo\",\"authorName\":\"Fixture\",\"authorUuid\":\""+UUID_TEXT+"\",\"cells\":["
            +"{\"x\":-2,\"y\":0,\"z\":0,\"block\":\"minecraft:chest\",\"item\":\"minecraft:chest\",\"count\":1,\"tileNbt\":\""+binary+"\"},"
            +String.format(Locale.ROOT,STONE,2,0,0)+","+String.format(Locale.ROOT,STONE,4,0,0)+"]}}";
        try{
            var png=NativeBlueprintCodecRegression.png();
            HologramBlueprintArtifact.writePair(lib.directory().resolve(ID+".json"),json.getBytes(StandardCharsets.UTF_8),png);
            var staged=lib.prepareNativeUpgrade(ID,()->true);HologramNativeBlueprintStorage.activate(lib.directory(),staged,staged.revision,()->true);
            var doc=open(lib,ID);try{
                var select=new NBTTagCompound();select.setInteger("selected",0);
                doc.apply("editCut",select);
                check(doc.nativeDraft().revision().wrapper(0,doc.nativeDraft().space().atOrdinal(0).descriptor)
                    .getAsJsonObject("fields").get("excluded").getAsBoolean(),"cut marks original only");
                var paste=new NBTTagCompound();paste.setInteger("selected",1);doc.apply("editPaste",paste);
                check(doc.nativeDraft().space().atPosition(2,0,0)!=null && doc.nativeDraft().space().size()==3,
                    "paste replaces selected ordinary destination without duplicating its position");
                var move=new NBTTagCompound();move.setInteger("selected",1);move.setInteger("dx",1);move.setInteger("dy",2);move.setInteger("dz",-3);
                doc.apply("editMove",move);check(doc.nativeDraft().space().atPosition(2,0,0)==null&&doc.nativeDraft().space().atPosition(3,2,-3)!=null,"move changes actual native geometry");
                doc.apply("editUndo",new NBTTagCompound());check(doc.nativeDraft().space().atPosition(2,0,0)!=null,"undo restores old source");
                doc.apply("editRedo",new NBTTagCompound());check(doc.nativeDraft().space().atPosition(3,2,-3)!=null,"redo native source");
                int stone=doc.nativeDraft().space().atPosition(4,0,0).ordinal;
                select.setInteger("selected",stone);doc.apply("editDelete",select);
                check(doc.nativeDraft().space().atPosition(4,0,0)==null,"delete removes from occupancy");
                doc.apply("rotateBlueprint",new NBTTagCompound());check(doc.nativeDraft().space().atPosition(3,2,3)!=null&&doc.nativeDraft().space().atPosition(0,0,-2)!=null,"whole authored rotation uses original local pivot and cannot invent filled cells");
                doc.apply("editUndo",new NBTTagCompound());
                var hint=new NBTTagCompound();hint.setIntArray("indices",new int[]{0,1});hint.setString("prompt","两个保留坐标");
                doc.apply("editHintArea",hint);
                var frozen=doc.nativeSaveRevision(new NBTTagCompound(),"Fixture",UUID.fromString(UUID_TEXT),ID,LocalSaveIntent.SAVE_CURRENT);
                var saved=HologramLocalBlueprintWorkspace.saveNativePair(lib,frozen,png,ID,LocalSaveIntent.SAVE_CURRENT,doc.nativeReceipt(),()->true);
                doc.nativeSaved(doc.revision(),frozen,ID,saved);
                var reread=open(lib,ID);try{
                    var r=reread.nativeDraft().revision();var p=r.source().spatial().atPosition(3,2,-3);
                    var hints=r.regionWrapper().getAsJsonObject("fields").getAsJsonArray("hintAreas");
                    check(hints.size()==1&&"两个保留坐标".equals(hints.get(0).getAsJsonObject().get("prompt").getAsString()),
                        "native editor area hint persisted in region section, not a cell annotation");
                    check(p!=null&&r.source().cells()==2,"saved native topology reopened with literal positions");
                    var w=r.wrapper(p.ordinal,p.descriptor);int id=r.source().payloadRef(w,"tileNbt");
                    check(id>0&&!w.getAsJsonObject("fields").has("excluded"),"cut clipboard retained original NBT, not excluded flag");
                    var raw=r.source().payload(id);
                    var tag=net.minecraft.nbt.CompressedStreamTools.func_152456_a(new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw)),new net.minecraft.nbt.NBTSizeTracker(1<<16));
                    check(tag.equals(typed),"typed NBT remained byte/long on real save/reopen");
                }finally{reread.closeNative();}
            }finally{doc.closeNative();}
        }finally{try(Stream<Path> paths=Files.walk(dir)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    private static void exerciseRegionAndOrientation()throws Exception {
        Path dir=Files.createTempDirectory("gtmb-native-orient-hint-");
        try{
            var lib=new HologramClientLibrary(dir);Files.createDirectories(lib.directory());
            var png=NativeBlueprintCodecRegression.png();
            String block="{\"x\":%d,\"y\":0,\"z\":0,\"block\":\"minecraft:furnace\",\"item\":\"minecraft:furnace\",\"metadata\":2}";
            String json="{\"schema\":2,\"blueprint\":{\"schema\":2,\"id\":\""+ID+"\",\"name\":\"Turn\",\"authorName\":\"Fixture\",\"authorUuid\":\""+UUID_TEXT+"\",\"cells\":["
                +String.format(Locale.ROOT,block,0)+","+String.format(Locale.ROOT,block,1)+"]}}";
            HologramBlueprintArtifact.writePair(lib.directory().resolve(ID+".json"),json.getBytes(StandardCharsets.UTF_8),png);
            var staged=lib.prepareNativeUpgrade(ID,()->true);HologramNativeBlueprintStorage.activate(lib.directory(),staged,staged.revision,()->true);
            var doc=open(lib,ID);try{
                var select=new NBTTagCompound();select.setInteger("selected",0);
                var hint=new NBTTagCompound();hint.setIntArray("indices",new int[]{0,1});hint.setString("prompt","first");
                doc.apply("editHintArea",hint);
                doc.apply("rotateCell",select);
                hint.setString("prompt","second");doc.apply("editHintArea",hint);
                var e=doc.nativeSaveRevision(new NBTTagCompound(),"Fixture",UUID.fromString(UUID_TEXT),ID,LocalSaveIntent.SAVE_CURRENT);
                var saved=HologramLocalBlueprintWorkspace.saveNativePair(lib,e,png,ID,LocalSaveIntent.SAVE_CURRENT,doc.nativeReceipt(),()->true);
                doc.nativeSaved(doc.revision(),e,ID,saved);
                var reread=open(lib,ID);try{
                    var def=reread.nativeDraft().revision().source();var view=def.spatial();
                    int a=def.element(com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintSpatialView.element(view.atPosition(0,0,0).descriptor)).get("metadata").getAsInt();
                    int b=def.element(com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintSpatialView.element(view.atPosition(1,0,0).descriptor)).get("metadata").getAsInt();
                    check((a==4||a==5)&&b==2,"turn only the selected furnace; preserve neighbor coordinates and metadata");
                    var hints=reread.nativeDraft().revision().regionWrapper().getAsJsonObject("fields").getAsJsonArray("hintAreas");
                    var h=hints.get(0).getAsJsonObject();
                    check(hints.size()==1&&h.get("x1").getAsInt()==0&&h.get("x2").getAsInt()==1&&h.get("prompt").getAsString().equals("second"),
                        "region preserved through single-cell edit, update deduplicated, filesystem round-trip");
                }finally{reread.closeNative();}
            }finally{doc.closeNative();}
        }finally{try(Stream<Path> paths=Files.walk(dir)){for(Path path:(Iterable<Path>)paths.sorted(Comparator.reverseOrder())::iterator)Files.deleteIfExists(path);}}
    }
    private static void exercise()throws Exception {
        exerciseNativeTopology();exerciseRegionAndOrientation();
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
                NBTTagCompound nbtPolicy=new NBTTagCompound();nbtPolicy.setBoolean("retainNbt",false);nbtPolicy.setBoolean("retainCovers",false);
                nbtPolicy.setBoolean("all",false);net.minecraft.nbt.NBTTagList keys=new net.minecraft.nbt.NBTTagList();
                keys.appendTag(new net.minecraft.nbt.NBTTagString("color"));nbtPolicy.setTag("keys",keys);
                doc.apply("configureBlueprintNbt",nbtPolicy);
                var policyHeader=doc.nativeDraft().revision().header();
                check(!policyHeader.get("retainNbt").getAsBoolean()&&!policyHeader.get("retainCovers").getAsBoolean()
                    &&policyHeader.getAsJsonArray("retainedNbtKeys").size()==1,"retention policy is native authored metadata");
                doc.apply("editUndo",new NBTTagCompound());doc.apply("editRedo",new NBTTagCompound());
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
    public static void runRevisionBoundary() {
        try {
            exerciseRevisionBoundary();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void exerciseRevisionBoundary() throws Exception {
        Path root = Files.createTempDirectory("gtmb-native-revision-boundary-");
        var earlier = new HologramClientLibrary(root.resolve("earlier"));
        var later = new HologramClientLibrary(root.resolve("later"));
        try {
            // Same logical ID and quantities are deliberately insufficient as a geometry/revision witness.
            var a = nativeRevision(earlier, 4);
            try {
                var b = nativeRevision(later, 7);
                try {
                    check(!a.nativeReceipt().physicalHash.equals(b.nativeReceipt().physicalHash),
                        "same ID and quantities, different physical file revision");
                    var aIndex = new HologramNativeViewport.Index(a.nativeDraft().space(), () -> true);
                    var av = HologramLocalBlueprintWorkspace.nativeView(a.nativeEditorMetadata(), a.nativeDraft(),
                        aIndex, null);
                    var bv = HologramLocalBlueprintWorkspace.nativeView(b.nativeEditorMetadata(), b.nativeDraft(),
                        new HologramNativeViewport.Index(b.nativeDraft().space(), () -> true), null);
                    check(av.regions.at(4, 5, -2) != null && av.regions.at(7, 5, -2) == null
                            && bv.regions.at(4, 5, -2) == null && bv.regions.at(7, 5, -2) != null,
                        "both preview models own only their specific saved geometry");
                    NBTTagCompound sa = renderedMaterial(av);
                    NBTTagCompound sb = renderedMaterial(bv);
                    check(hasRequired(sa, "2") && hasRequired(sb, "2"),
                        "both revisions display the same expected two-stone total");
                    check(av.regions.at(-2, 0, 1) != null && bv.regions.at(-2, 0, 1) != null
                            && av.regions.at(0, 0, 1) == null && bv.regions.at(0, 0, 1) == null,
                        "negative coordinates and sparse holes survive in both definitions");

                    var ui = new HologramUiModules(null);
                    // Install the same model and typed document the formal editor uses, without opening a screen.
                    var stateField = HologramUiSession.class.getDeclaredField("state");
                    stateField.setAccessible(true);
                    stateField.set(ui.session, av);
                    var docField = HologramUiSession.class.getDeclaredField("document");
                    docField.setAccessible(true);
                    docField.set(ui.session, a);
                    check(ui.session.document() == a && !ui.session.isTutorial(), "bound real native editor state");
                    NBTTagCompound unsaved = new NBTTagCompound();
                    unsaved.setString("prompt", "unsaved");
                    a.apply("editBlueprintPrompt",unsaved);
                    new HologramLocalBlueprintWorkspace(ui).placeCurrent();
                    check(a.dirty() && ui.session.notice().contains("先保存"),"dirty native workbench cannot place an older version");
                    a.apply("editUndo",new NBTTagCompound());
                    check(!a.dirty(),"undo restores saved workbench version");
                    try {
                        a.freeze();
                        throw new AssertionError("native was frozen as legacy for placement");
                    } catch (IllegalStateException expected) {
                        check(expected.getMessage().contains("不使用旧施工协议"), "native legacy freeze boundary");
                    }
                    var beforeUnsupportedEdit = a.nativeDraft();
                    for (String op : new String[] { "placeTextBox" }) {
                        try {
                            a.apply(op, new NBTTagCompound());
                            throw new AssertionError("unsupported editor route unexpectedly used a fallback: " + op);
                        } catch (java.io.IOException expected) {
                            check(expected.getMessage().contains("尚未接入") && a.nativeDraft() == beforeUnsupportedEdit
                                    && !a.dirty(), "unsupported editor command keeps the exact draft and original: " + op);
                        }
                    }

                    var selection = earlier.savedDisplayPath(ID);
                    check(selection.path().equals(a.nativeReceipt().source) && selection.indexed(),
                        "private library knows the active native pair");
                    var browser = new HologramLibraryClient();
                    // Exact production saved display entry used by both gallery and world preview.
                    var gallery = browser.readSavedDisplaySource(ID, selection, true, 0, 0);
                    var world = browser.readSavedDisplaySource(ID, selection, false, 0, 0);
                    check(world == gallery && gallery.json == null
                            && gallery.nativeReceipt.physicalHash.equals(a.nativeReceipt().physicalHash),
                        "gallery and world reuse the pinned native definition, not JSON or old construction authority");
                    check(gallery.thumbnail != null && gallery.thumbnail.getRGB(10, 10) == 0xffaaccdd
                            && !gallery.state.data.getBoolean("localBlueprintDocument"),
                        "gallery uses verified PNG pixels and a read-only display");
                    world.requireStoredUse();
                    var placement = HologramLocalPlacement.displayForPlacement(world.state, 7);
                    check(placement.nativeViewport == gallery.state.nativeViewport && placement.data.getBoolean("virtualPlacement")
                            && placement.data.getInteger("dimension") == 7 && placement.regions.at(4, 5, -2) != null
                            && placement.regions.at(7, 5, -2) == null,
                        "world preview overlays client context without recreating or changing native geometry");
                    var review = new HologramLibraryMaterialReviewScreen.CapturedDescription(placement, null);
                    var frame = review.frame(() -> new net.minecraft.item.ItemStack[36], 0);
                    check(frame.ready() && hasRequired(frame.rows(), "2"),
                        "actual existing placement material dialog reads the shared native summary");
                    var frozen = new HologramLocalPlacement.Submission(world, placement, 1, new NBTTagCompound());
                    check(browser.plainPlacementAvailability(frozen, null) == HologramLibraryClient.PlainAvailability.NATIVE_PENDING,
                        "view-only native source does not silently become an old construction source");

                    var moved = browser.readSavedDisplaySource(ID, later.savedDisplayPath(ID), true, 0, 0);
                    check(moved != gallery && moved.state.regions.at(7, 5, -2) != null
                            && moved.state.regions.at(4, 5, -2) == null && gallery.state.regions.at(4, 5, -2) != null,
                        "same logical ID and material totals never reuse replaced coordinates");
                    byte[] goodPng = Files.readAllBytes(gallery.nativeReceipt.png);
                    try {
                        Files.write(gallery.nativeReceipt.png, new byte[] { 1, 2, 3 });
                        try {
                            browser.readSavedDisplaySource(ID, selection, true, 0, 0);
                            throw new AssertionError("damaged pair became a gallery preview");
                        } catch (java.io.IOException expected) {
                            check(!expected.getMessage().equals("Input length = 1"),
                                "native corruption is rejected by pair validation, not legacy decoding");
                        }
                    } finally {
                        Files.write(gallery.nativeReceipt.png, goodPng);
                    }
                    System.out.println("[GTMB-NATIVE-GALLERY] native preview/world/material model and exact revision PASS");
                    var outbound = earlier.publicationArtifact(ID);
                    check(outbound.version() == com.miaokatze.gtmb.hologram.HologramNativePublicArtifact.VERSION,
                        "saved native pair chooses the native public format");
                    var pair = com.miaokatze.gtmb.hologram.HologramNativePublicArtifact.decode(outbound.bytes());
                    check(gallery.nativeReceipt.physicalHash.equals(pair.revision())
                        && gallery.nativeReceipt.pngHash.equals(HologramBlueprintArtifact.hash(pair.cover())),
                        "public transfer held the exact saved source and cover, not a compatibility JSON pair");
                    System.out.println("[GTMB-NATIVE-REVISION] equal totals, different geometry and revisions; place gated");
                } finally {
                    b.closeNative();
                }
            } finally {
                a.closeNative();
            }
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
    }

    private static HologramLocalBlueprintDocument nativeRevision(HologramClientLibrary lib, int x) throws Exception {
        Files.createDirectories(lib.directory());
        String cells = String.format(Locale.ROOT, STONE, -2, 0, 1) + "," + String.format(Locale.ROOT, STONE, x, 5, -2);
        String json = "{\"schema\":2,\"blueprint\":{\"id\":\"" + ID + "\",\"name\":\"Revision fixture\","
            + "\"authorName\":\"Fixture\",\"authorUuid\":\"" + UUID_TEXT + "\",\"cells\":[" + cells + "]}}";
        Path original = lib.directory().resolve(ID + ".json");
        HologramBlueprintArtifact.writePair(original, json.getBytes(StandardCharsets.UTF_8), NativeBlueprintCodecRegression.png());
        var prepared = lib.prepareNativeUpgrade(ID, () -> true);
        check(lib.readPrivateEditorSource(ID).nativePair() == null,
            "conversion preparation never activates or changes default source");
        // Explicit internal opt-in exercises the native editor; no claim that the public UI activates it.
        HologramNativeBlueprintStorage.activate(lib.directory(), prepared, prepared.revision, () -> true);
        check(Arrays.equals(json.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(original)),
            "source bytes unchanged when opting into the native pair");
        return open(lib, ID);
    }

    private static NBTTagCompound renderedMaterial(HologramState view) {
        var material = new HologramLocalMaterialReview(view, new NBTTagCompound());
        material.refresh(null, -1);
        check(material.ready() && !material.status().contains("未核算"), "known vanilla material is resolved");
        return material.visible();
    }

    public static void runAuthoredCapture() {
        try {
            exerciseAuthoredCapture();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void exerciseAuthoredCapture() throws Exception {
        var snapshot = new NBTTagCompound();
        snapshot.setString("title", "Native capture fixture");
        snapshot.setString("sourceClass", "fixture:must-not-instantiate");
        snapshot.setString("sourceMod", "fixture");
        snapshot.setInteger("x", 91);
        snapshot.setInteger("y", 68);
        snapshot.setInteger("z", 46);
        NBTTagCompound channels = new NBTTagCompound();
        channels.setInteger("hatchType", 3);
        snapshot.setTag("channels", channels);
        var cells = new net.minecraft.nbt.NBTTagList();
        var chest = new NBTTagCompound();
        chest.setString("id", "Chest");
        chest.setLong("fixtureLong", 1234567891234567L);
        chest.setByteArray("fixtureBytes", new byte[] { 127, -1, 0, 67 });
        var inventory = new net.minecraft.nbt.NBTTagList();
        inventory.appendTag(new net.minecraft.item.ItemStack(net.minecraft.init.Items.diamond, 3).writeToNBT(new NBTTagCompound()));
        chest.setTag("Inventory", inventory);
        for (int i = 0; i < 3; i++) {
            var row = new NBTTagCompound();
            row.setInteger("dx", i == 0 ? -2 : i == 1 ? 6 : 11);
            row.setInteger("dy", i == 0 ? 0 : 5);
            row.setInteger("dz", i == 0 ? 1 : -3);
            row.setString("wantId", i == 1 ? "minecraft:chest" : "minecraft:stone");
            row.setBoolean("excluded", i == 2);
            if (i == 1) {
                row.setInteger("wantMeta", 4);
                var display = new NBTTagCompound();
                display.setTag("nativeTile", chest);
                row.setTag("targetDisplay", display);
            }
            cells.appendTag(row);
        }
        snapshot.setTag("cells", cells);
        var draft = HologramLocalBlueprintInputs.controller(snapshot);
        check(draft.unsavedLocalCapture()
                && HologramLocalBlueprintWorkspace.nativeNewSave(draft, LocalSaveIntent.SAVE_AS_NEW, true)
                && !HologramLocalBlueprintWorkspace.nativeNewSave(draft, LocalSaveIntent.SAVE_AS_NEW, false)
                && !HologramLocalBlueprintWorkspace.nativeNewSave(draft, LocalSaveIntent.SAVE_CURRENT, true),
            "the existing Save As can opt in on a capture without switching the default or changing a current save");
        Path root = Files.createTempDirectory("gtmb-first-authored-native-");
        var library = new HologramClientLibrary(root);
        try {
            Files.createDirectories(library.directory());
            var options = new NBTTagCompound();
            options.setString("name", "Captured without legacy file");
            var actor = UUID.fromString(UUID_TEXT);
            try (var capture = HologramLocalBlueprintWorkspace.prepareNewNativeCapture(draft, options, "Fixture", actor,
                ID, () -> true)) {
                check(capture.view.nativeViewport != null && capture.view.cells.size() == 3
                        && capture.view.regions.at(6, 5, -3).tag().getInteger("wantMeta") == 4
                        && capture.view.regions.at(0, 0, 1) == null
                        && capture.view.regions.at(11, 5, -3).excluded,
                    "screenshot model already reads the same native coordinates, variant, sparse holes and exclusions");
                var png = NativeBlueprintCodecRegression.png();
                try {
                    HologramLocalBlueprintWorkspace.publishNewNativeCapture(library, capture, ID, png, () -> false);
                    throw new AssertionError("retired capture published");
                } catch (java.io.IOException expected) {
                    check(library.list().isEmpty(), "cancel never changes the catalog or publishes a partial pair");
                }
                try {
                    HologramLocalBlueprintWorkspace.publishNewNativeCapture(library, capture,
                        "local_88888888-8888-8888-8888-888888888888", png, () -> true);
                    throw new AssertionError("capture owned by a different slot was published");
                } catch (java.io.IOException expected) {
                    check(library.list().isEmpty(), "a valid capture cannot be rebound to a different save target");
                }
                var saved = HologramLocalBlueprintWorkspace.publishNewNativeCapture(library, capture, ID, png, () -> true);
                check(saved.physicalHash.equals(capture.revision) && library.list().size() == 1
                        && !Files.exists(library.directory().resolve(ID + ".json")),
                    "the first save produces exactly one native source+PNG pair and no legacy intermediate file");
                try (var definition = library.readPrivateEditorSource(ID).openNative()) {
                    int chestId = definition.elementAt(6, 5, -3);
                    var sourceHeader = definition.metadata();
                    check(!sourceHeader.has("migration") && definition.cells() == 3
                            && definition.elementAt(-2, 0, 1) > 0 && chestId > 0
                            && sourceHeader.getAsJsonObject("fields").get("authorUuid").getAsString().equals(UUID_TEXT)
                            && sourceHeader.getAsJsonObject("fields").get("name").getAsString().equals("Captured without legacy file"),
                        "new capture contains exact saved geometry and real authorship, with no fabricated migration");
                    var fields = definition.restoredFields(definition.extras(6, 5, -3, chestId));
                    byte[] typed = Base64.getDecoder().decode(fields.get("tileNbt").getAsString());
                    var reopened = net.minecraft.nbt.CompressedStreamTools.readCompressed(new java.io.ByteArrayInputStream(typed));
                    check(reopened.getLong("fixtureLong") == 1234567891234567L
                            && Arrays.equals(reopened.getByteArray("fixtureBytes"), new byte[] { 127, -1, 0, 67 })
                            && reopened.getTagList("Inventory", 10).tagCount() == 1,
                        "first save preserves binary special-block NBT (Long, byte array, inventory)");
                }
                try {
                    HologramLocalBlueprintWorkspace.publishNewNativeCapture(library, capture, ID, png, () -> true);
                    throw new AssertionError("native first save overwrote an occupied slot");
                } catch (java.io.IOException expected) {
                    check(library.readPrivateEditorSource(ID).nativePair().physicalHash.equals(saved.physicalHash),
                        "occupied slot remains same exact source on refusal");
                }
            }
            System.out.println("[GTMB-NATIVE-FIRST-SAVE] authored coordinates, binary payload, preview binding and no legacy intermediate PASS");
        } finally {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator)
                    Files.deleteIfExists(path);
            }
        }
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
