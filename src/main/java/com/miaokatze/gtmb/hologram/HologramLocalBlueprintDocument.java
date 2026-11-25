// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram;

import java.io.IOException;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintDefinition;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintEdits;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.github.bsideup.jabel.Desugar;
import com.miaokatze.gtmb.api.StructureSources;
import com.miaokatze.gtmb.hologram.HologramPresets.Cell;
import com.miaokatze.gtmb.hologram.HologramPresets.HintArea;
import com.miaokatze.gtmb.hologram.HologramPresets.Preset;
import com.miaokatze.gtmb.hologram.HologramPresets.Structure;

/** A private authoring document. It receives no world, inventory, connection, or server session authority. */
public final class HologramLocalBlueprintDocument {

    private static final int HISTORY_LIMIT = 32;

    @Desugar
    private record Version(Preset document, Object content, HistoryEdge edge) {}

    /** Local render input, emitted only for an exact directional history transition. Never serialized to disk. */
    @Desugar
    public record HistoryDelta(long fromRevision, long revision, int cellCount, int index, int x, int y, int z,
        String expectedPrompt, String prompt, String block, int metadata, int facing, boolean excluded) {

        public boolean header() {
            return index == -1;
        }
    }

    @Desugar
    private record PromptCellEdge(int index, List<Cell> beforeRows, List<Cell> afterRows, List<Cell> beforePalette,
        List<Cell> afterPalette, Cell before, Cell after) {

        boolean matches(Preset from, Preset to, boolean undoing) {
            var fromRows = undoing ? afterRows : beforeRows;
            var toRows = undoing ? beforeRows : afterRows;
            return from.cells == fromRows && to.cells == toRows
                && from.palette == (undoing ? afterPalette : beforePalette)
                && to.palette == (undoing ? beforePalette : afterPalette)
                && fromRows.size() == toRows.size()
                && index >= 0
                && index < fromRows.size()
                && fromRows.get(index) == (undoing ? after : before)
                && toRows.get(index) == (undoing ? before : after);
        }
    }

    @Desugar
    private record HistoryEdge(Object epoch, Object before, Object after, PromptCellEdge cell) {

        boolean matches(Object currentEpoch, Object fromContent, Version to, Preset from, boolean undoing) {
            if (epoch != currentEpoch || fromContent != (undoing ? after : before)
                || to.content() != (undoing ? before : after)) return false;
            Preset target = to.document();
            // A checked packed conversion replaces the shared header row root on all retained versions at once.
            return cell == null ? from.cells == target.cells && from.palette == target.palette
                : cell.matches(from, target, undoing);
        }
    }

    @Desugar
    private record Clipboard(List<Cell> cells, List<Structure> structures, Position origin) {}

    @Desugar
    private record StructureSettings(String id, NBTTagCompound signals) {}

    @Desugar
    private record StructureEvidence(Preset document, Structure source, NBTTagCompound settings,
        Map<HologramLocalBlueprintStructure.Position, Cell> rows, StructureSources.ReadSet proof) {}

    @Desugar
    private record AnnotationChange(long revision, int index, String prompt, boolean excluded) {}

    @Desugar
    private record Position(int x, int y, int z) {

        static Position of(Cell cell) {
            return new Position(cell.x, cell.y, cell.z);
        }

        static Position of(Structure structure) {
            return new Position(structure.x, structure.y, structure.z);
        }

        Position translate(long dx, long dy, long dz) throws IOException {
            long nextX = x + dx, nextY = y + dy, nextZ = z + dz;
            if (Math.abs(nextX) > 60000000L || Math.abs(nextY) > 256L || Math.abs(nextZ) > 60000000L)
                throw new IOException("蓝图坐标超出范围");
            return new Position((int) nextX, (int) nextY, (int) nextZ);
        }

        void write(Cell cell) {
            cell.x = x;
            cell.y = y;
            cell.z = z;
        }

        void write(Structure structure) {
            structure.x = x;
            structure.y = y;
            structure.z = z;
        }
    }

    private final String session = "local-blueprint:" + UUID.randomUUID();
    private final Deque<Version> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final Map<StructureSettings, NBTTagCompound> structureOptions = new LinkedHashMap<>();
    private volatile StructureEvidence structureEvidence;
    private volatile Preset document;
    // One validated row root shared by the bounded header-only history, until a legacy consumer needs DTO rows.
    private volatile HologramPackedAuthoring packed;
    private final HologramLocalBlueprintCodec codec;
    private String fileId = "";
    private LocalEditorOrigin editorOrigin = LocalEditorOrigin.STRICT;
    private boolean authoredHere;
    private boolean committedNamedImport;
    private Object content = new Object();
    private Object savedContent = content;
    private Object historySaveEpoch = new Object();
    private volatile HistoryDelta historyDelta;
    private byte[] encoded;
    private boolean explicitRetention = true;
    private Clipboard clipboard;
    private NativeBlueprintEdits.Clip nativeClipboard;
    private final Set<NativeBlueprintDefinition> ownedNativeSources=new HashSet<>();
    private void retireUnusedNativeSources(){
        var keep=new HashSet<NativeBlueprintDefinition>();
        if(nativeDraft!=null)keep.add(nativeDraft.revision().source());
        if(nativeClipboard!=null)keep.add(nativeClipboard.source());
        if(savedContent instanceof HologramNativeBlueprintDraft)keep.add(((HologramNativeBlueprintDraft)savedContent).revision().source());
        for(var draft:nativeUndo)keep.add(draft.revision().source());
        for(var draft:nativeRedo)keep.add(draft.revision().source());
        ownedNativeSources.removeIf(d->{if(keep.contains(d))return false;try{d.close();}catch(IOException ignored){}return true;});
    }
    private volatile long revision;
    private int selected = -1, layer, scope, mode;
    private int[] selection = new int[0];
    private String activeStructure = "", status = "";
    private NBTTagCompound textPlacementAck;
    private int sourceDimension, originX, originY, originZ;
    private NBTTagCompound processingView;
    private NBTTagList processingPresets;
    private HologramBlueprintProcessing.Policy processingPolicy;
    private boolean processingReviewed;
    private AnnotationChange annotationChange;

    private HologramLocalBlueprintDocument(Preset document, byte[] encoded, HologramLocalBlueprintCodec codec) {
        this.document = document;
        this.encoded = encoded;
        this.codec = codec;
        explicitRetention = codec.explicitRetention();
    }

    private volatile com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt nativeReceipt;
    public com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt nativeReceipt(){return nativeReceipt;}
    private volatile HologramNativeBlueprintDraft nativeDraft;
    private final Deque<HologramNativeBlueprintDraft> nativeUndo=new ArrayDeque<>(), nativeRedo=new ArrayDeque<>();
    public HologramNativeBlueprintDraft nativeDraft(){return nativeDraft;}
    public static HologramLocalBlueprintDocument openNative(NativeBlueprintDefinition def,String id,LocalEditorOrigin origin)throws IOException{
        return openNative(def,id,origin,null);
    }
    public static HologramLocalBlueprintDocument openNative(NativeBlueprintDefinition def,String id,LocalEditorOrigin origin,
        com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt receipt)throws IOException{
        try{
            var nativeDraft=new HologramNativeBlueprintDraft(new NativeBlueprintEdits(def));
            var doc=new HologramLocalBlueprintDocument(nativeDraft.header(),null,new HologramLocalBlueprintCodec());
            doc.fileId=id==null?"":HologramLibraryService.localId(id,true);
            doc.editorOrigin=origin;doc.ownedNativeSources.add(def);doc.nativeDraft=nativeDraft;doc.nativeReceipt=receipt;
            doc.content=nativeDraft;doc.savedContent=doc.fileId.isEmpty()?null:nativeDraft;
            return doc;
        }catch(IOException|RuntimeException failure){def.close();throw failure;}
    }
    public void closeNative(){for(var d:ownedNativeSources)try{d.close();}catch(IOException ignored){}ownedNativeSources.clear();}
    public NBTTagCompound nativeEditorMetadata()throws IOException{
        if(nativeDraft==null)throw new IOException("Not a native document");
        NBTTagCompound data=editorMetadata();data.setBoolean("nativeBlueprintDocument",true);
        data.setBoolean("editCanPaste",nativeClipboard!=null);data.setBoolean("editCanUndo",!nativeUndo.isEmpty());data.setBoolean("editCanRedo",!nativeRedo.isEmpty());return data;
    }
    public NativeBlueprintEdits nativeSaveRevision(NBTTagCompound options,String actor,UUID uuid,String id,LocalSaveIntent intent)throws IOException{
        if(nativeDraft==null)throw new IOException("Not a native document");
        String name=options.getString("name").trim();if(name.isEmpty())name=text(document.name);
        if(name.isEmpty()||name.length()>48||viewCellCount()==0)throw new IOException("请填写蓝图名称并保留至少一个方块");
        if(options.hasKey("retainNbt",1)&&options.getBoolean("retainNbt")!=document.retainNbt||
            options.hasKey("retainCovers",1)&&options.getBoolean("retainCovers")!=document.retainCovers||
            options.hasKey("deidentificationLevel",3)&&options.getInteger("deidentificationLevel")!=document.deidentificationLevel||
            options.hasKey("retainAllNbt",1)&&(options.getBoolean("retainAllNbt")!=(document.retainedNbtKeys==null)||
                !options.getBoolean("retainAllNbt")&&!java.util.Objects.equals(document.retainedNbtKeys,
                    HologramAuthoringQueries.selectedNbtKeys(options.getTagList("retainedNbtKeys",8)))))
            throw new IOException("此原生版本尚不支持更改保留策略；未保存");
        String author=text(document.authorName),owner=text(document.authorUuid);
        if(author.isEmpty()&&owner.isEmpty()&&uuid!=null&&actor!=null){author=actor.trim();owner=uuid.toString();}
        boolean incomplete=!author.isEmpty()&&owner.isEmpty()&&(intent==LocalSaveIntent.SAVE_AS_NEW&&editorOrigin==LocalEditorOrigin.NAMED_IMPORT&&fileId.isEmpty()||
            intent==LocalSaveIntent.SAVE_CURRENT&&id.equals(fileId)&&editorOrigin==LocalEditorOrigin.TRACKED_PRIVATE);
        if(author.isEmpty()||owner.isEmpty()&&!incomplete)throw new IOException("蓝图缺少完整作者信息");
        return nativeDraft.revision().author(HologramLibraryService.localId(id,false),name,author,owner.isEmpty()?null:owner,
            document.createdAt>0?document.createdAt:incomplete?0:System.currentTimeMillis());
    }
    public void nativeSaved(long expectedRevision,NativeBlueprintEdits edits,String id,
        com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore.Receipt receipt)throws IOException{
        if(revision!=expectedRevision||nativeDraft==null||nativeDraft.revision().source()!=edits.source())throw new IOException("蓝图已修改，请重新保存");
        nativeDraft=new HologramNativeBlueprintDraft(edits);document=nativeDraft.header();fileId=HologramLibraryService.localId(id,false);
        nativeReceipt=receipt;savedContent=content=nativeDraft;nativeUndo.clear();nativeRedo.clear();editorOrigin=LocalEditorOrigin.TRACKED_PRIVATE;retireUnusedNativeSources();revision++;status="原生蓝图已保存到客户端";
    }
    private boolean applyNative(String op,NBTTagCompound request,java.util.function.BooleanSupplier current)throws IOException{
        switch(op){
            case "selectCell" -> {setSelection(request.getInteger("selected"),request.getIntArray("selectedIndices"));return true;}
            case "selectStructure" -> {selectStructure(request.getString("structureId"));return true;}
            case "configure" -> {
                NBTTagCompound saved=new NBTTagCompound();writeStructureSettings(saved);
                if(request.hasKey("channels")&&(!request.hasKey("channels",10)||!saved.getCompoundTag("channels").equals(request.getCompoundTag("channels")))
                    ||request.hasKey("main")&&(!request.hasKey("main",3)||request.getInteger("main")!=saved.getInteger("main"))
                    ||request.hasKey("noHatches")&&(!request.hasKey("noHatches",1)||request.getBoolean("noHatches")!=saved.getBoolean("noHatches")))
                    throw new IOException("原生蓝图保留保存时的结构，不根据机器定义重新生成");
                if(request.hasKey("selected",3)||request.hasKey("selectedIndices",11))setSelection(
                    request.hasKey("selected",3)?request.getInteger("selected"):selected,
                    request.hasKey("selectedIndices",11)?request.getIntArray("selectedIndices"):selection);
                viewConfiguration(request);return true;
            }
            case "editUndo","editRedo" -> {Deque<HologramNativeBlueprintDraft> from=op.equals("editUndo")?nativeUndo:nativeRedo,to=op.equals("editUndo")?nativeRedo:nativeUndo;
                if(!from.isEmpty()){to.push(nativeDraft);nativeDraft=from.pop();document=nativeDraft.header();content=nativeDraft;revision++;}return true;}
        }
        NativeBlueprintEdits edits=nativeDraft.revision();
        NativeBlueprintEdits.Clip pendingClip=null;
        if("editCopy".equals(op)){nativeClipboard=edits.copy(indices(request));retireUnusedNativeSources();status="已复制选中蓝图格";return true;}

        if("editBlueprintPrompt".equals(op))edits=edits.prompt(HologramSessionPolicy.boundedPrompt(request.getString("prompt")));
        else if("configureBlueprintNbt".equals(op)){
            edits=edits.retention(request.hasKey("all",1),request.getBoolean("all")?null:HologramAuthoringQueries.selectedNbtKeys(request.getTagList("keys",8)),
                request.hasKey("retainNbt",1)?Boolean.valueOf(request.getBoolean("retainNbt")):null,
                request.hasKey("retainCovers",1)?Boolean.valueOf(request.getBoolean("retainCovers")):null);
        }else if("editCut".equals(op)){int[] ranks=indices(request);pendingClip=edits.copy(ranks);edits=edits.annotate(ranks,null,true);
        }else if("rotateCell".equals(op)){edits=nativeDraft.rotateCells(indices(request),request.getBoolean("vertical"),current);
        }else if("rotateBlueprint".equals(op)){edits=nativeDraft.rotate(current);
        }else if("editPaste".equals(op)){edits=edits.paste(nativeClipboard,primary(request),current);
        }else if("editMove".equals(op)||"editDelete".equals(op)){
            boolean del="editDelete".equals(op);int dx=del?0:offset(request,"dx"),dy=del?0:offset(request,"dy"),dz=del?0:offset(request,"dz");
            if(!del&&dx==0&&dy==0&&dz==0)return true;
            edits=edits.moveOrDelete(indices(request),dx,dy,dz,!del&&request.getBoolean("copy"),del,current);
        }else if("editCellFlags".equals(op)||"editHintArea".equals(op)){
            int[] ranks=request.getIntArray("indices");if(ranks.length==0)ranks=request.getIntArray("selectedIndices");
            if(ranks.length==0)ranks=new int[]{request.hasKey("selected",3)?request.getInteger("selected"):selected};
            if(ranks.length>HologramCapture.LIMIT)throw new IOException("选择数量超过上限");
            if(request.hasKey("structureId",8)&&!activeStructure.equals(request.getString("structureId")))throw new IOException("结构选择已变化");
            for(int rank:ranks){if(rank<0||rank>=viewCellCount())throw new IOException("无效的位置选择");
                if(!activeStructure.isEmpty()&&!nativeDraft.cell(rank).belongsTo(activeStructure))throw new IOException("选择不属于当前结构");}
            boolean region="editHintArea".equals(op);
            if(region)edits=edits.hintArea(ranks,HologramSessionPolicy.boundedPrompt(request.getString("prompt")));
            if(!region||request.hasKey("excluded",1))edits=edits.annotate(ranks,!region&&request.hasKey("prompt",8)?HologramSessionPolicy.boundedPrompt(request.getString("prompt")):null,
                request.hasKey("excluded",1)?Boolean.valueOf(request.getBoolean("excluded")):null);
        }else throw new IOException("此原生蓝图编辑操作尚未接入，已保留原件："+op);
        HologramNativeBlueprintDraft next;
        try{next=new HologramNativeBlueprintDraft(edits,nativeDraft);}
        catch(IOException|RuntimeException failure){if(edits.source()!=nativeDraft.revision().source())edits.source().close();throw failure;}
        ownedNativeSources.add(edits.source());
        if(pendingClip!=null)nativeClipboard=pendingClip;
        if(edits.source()!=nativeDraft.revision().source())setSelection(-1,new int[0]);
        nativeUndo.push(nativeDraft);if(nativeUndo.size()>HISTORY_LIMIT)nativeUndo.removeLast();
        nativeRedo.clear();nativeDraft=next;content=next;document=next.header();retireUnusedNativeSources();revision++;return true;
    }
    static NBTTagCompound nativeDisplayDescriptor(Cell cell,NBTTagCompound tile,NBTTagCompound item){
        NBTTagCompound row=cellRow(cell,new VisualPayloads(),false);
        if(!row.getBoolean("missingBlock")){
            if(tile!=null)row.setTag("previewTile",tile.copy());
            if(item!=null)row.setTag("previewItemTag",item.copy());
        }
        return row;
    }
    public static HologramLocalBlueprintDocument open(byte[] exactJson) throws IOException {
        return open(exactJson, null);
    }

    /** Native client scans already own their detached preset. */
    static HologramLocalBlueprintDocument fromCapture(Preset capture) {
        capture.schema = 2;
        HologramLocalBlueprintDocument opened = new HologramLocalBlueprintDocument(
            capture,
            null,
            new HologramLocalBlueprintCodec());
        opened.savedContent = null;
        opened.authoredHere = true;
        return opened;
    }

    /** Existing imported/legacy files use the explicit migration flow instead of implicit Save As conversion. */
    public boolean unsavedLocalCapture() {
        return authoredHere && fileId.isEmpty();
    }

    static HologramLocalBlueprintDocument fromCapture(Preset capture, int dimension, int x, int y, int z) {
        HologramLocalBlueprintDocument opened = fromCapture(capture);
        opened.sourceDimension = dimension;
        opened.originX = x;
        opened.originY = y;
        opened.originZ = z;
        return opened;
    }

    /** A file ID records the library slot. A named import starts as an unsaved copy. */
    public static HologramLocalBlueprintDocument open(byte[] exactJson, String fileId) throws IOException {
        HologramLocalBlueprintCodec codec = new HologramLocalBlueprintCodec();
        HologramLocalBlueprintCodec.Loaded loaded = codec.read(exactJson, () -> true);
        codec.materialize(loaded, () -> true);
        return open(exactJson, fileId, codec, loaded.document());
    }

    @Desugar
    public record EditorRows(NBTTagCompound metadata, HologramBlueprintCompactCodec.DisplayRows rows,
        NBTTagCompound[] materials, int[] bounds) {}

    /** Exact editable ordinals projected from an in-memory ordinary draft, not from a second disk decode. */
    @Desugar
    public record DetachedEditorRows(NBTTagCompound metadata, int[] coordinates, NBTTagCompound[] materials,
        int[] bounds) {}

    @Desugar
    public record Opened(HologramLocalBlueprintDocument document, EditorRows editor) {}

    public static Opened openForEditor(byte[] exactJson, String fileId, java.util.function.BooleanSupplier current)
        throws IOException {
        return openForEditor(exactJson, fileId, current, editor -> {});
    }

    public static Opened openForEditor(byte[] exactJson, String fileId, java.util.function.BooleanSupplier current,
        java.util.function.Consumer<EditorRows> display) throws IOException {
        return openForEditor(exactJson, fileId, current, display, false);
    }

    /** Only opt-in managed opens can retain a layout already admitted by the existing editor view. */
    public static Opened openForEditor(byte[] exactJson, String fileId, java.util.function.BooleanSupplier current,
        java.util.function.Consumer<EditorRows> display, boolean retainPacked) throws IOException {
        return openForEditor(exactJson, fileId, current, display, retainPacked, LocalEditorOrigin.STRICT);
    }

    /** The client workspace sets this only after its own named read or persistently indexed private read. */
    public enum LocalEditorOrigin {
        STRICT,
        NAMED_IMPORT,
        TRACKED_PRIVATE
    }

    /** Internal native control, never derived from JSON or request payload. */
    public enum LocalSaveIntent {
        STRICT,
        SAVE_AS_NEW,
        SAVE_CURRENT
    }

    public static Opened openForEditor(byte[] exactJson, String fileId, java.util.function.BooleanSupplier current,
        java.util.function.Consumer<EditorRows> display, boolean retainPacked, LocalEditorOrigin origin)
        throws IOException {
        return openForEditor(exactJson, fileId, current, display, retainPacked, origin, false);
    }

    /** Game library admission precedes any editor publication, including the early packed view. */
    public static Opened openStoredForEditor(byte[] exactJson, String fileId,
        java.util.function.BooleanSupplier current, java.util.function.Consumer<EditorRows> display,
        boolean retainPacked, LocalEditorOrigin origin) throws IOException {
        return openForEditor(exactJson, fileId, current, display, retainPacked, origin, true);
    }

    private static Opened openForEditor(byte[] exactJson, String fileId, java.util.function.BooleanSupplier current,
        java.util.function.Consumer<EditorRows> display, boolean retainPacked, LocalEditorOrigin origin, boolean stored)
        throws IOException {
        HologramLocalBlueprintCodec codec = new HologramLocalBlueprintCodec();
        HologramLocalBlueprintCodec.Loaded loaded = codec.read(exactJson, current, stored);
        HologramLocalBlueprintDocument document = open(exactJson, fileId, codec, loaded.document());
        if (origin == LocalEditorOrigin.NAMED_IMPORT && "".equals(fileId)
            || origin == LocalEditorOrigin.TRACKED_PRIVATE && fileId != null && !fileId.isEmpty())
            document.editorOrigin = origin;
        EditorRows editor = document.editorRows(loaded.layout(), current);
        if (editor != null) display.accept(editor);
        document.packed = HologramPackedAuthoring
            .retain(loaded.layout(), retainPacked, editor != null, document.fileId, current);
        if (document.packed == null) codec.materialize(loaded, current);
        return new Opened(document, editor);
    }

    public boolean packedAuthoring() {
        return packed != null;
    }

    /** Only global prompt writes (not cell/hint prompts or NBT policy edits) add history while packed. */
    public boolean supportsPackedCommand(String operation) {
        return "selectCell".equals(operation) || "editBlueprintPrompt".equals(operation)
            || "editUndo".equals(operation)
            || "editRedo".equals(operation)
            || "configure".equals(operation) && activeStructure.isEmpty();
    }

    public EditorRows packedEditorRows(java.util.function.BooleanSupplier current) throws IOException {
        var root = packed;
        return root == null ? null : editorRows(root.layout(), current);
    }

    public HologramPackedAuthoring.Materialization preparePackedMaterialization(long expectedRevision,
        java.util.function.BooleanSupplier current) throws IOException {
        var root = packed;
        if (root == null) throw HologramPackedAuthoring.stale();
        return root.prepare(
            codec,
            expectedRevision,
            () -> packed == root && revision == expectedRevision && current.getAsBoolean());
    }

    public synchronized void acceptPackedMaterialization(HologramPackedAuthoring.Materialization prepared,
        long expectedRevision, java.util.function.BooleanSupplier current) throws IOException {
        var root = packed;
        if (root == null) throw HologramPackedAuthoring.stale();
        // Every packed version has the same unexpanded cells; never partially install if a caller violated that fence.
        List<Cell> unexpanded = document.cells;
        checkPackedHistory(undo, unexpanded);
        checkPackedHistory(redo, unexpanded);
        List<Cell> cells = root.accept(
            prepared,
            expectedRevision,
            () -> packed == root && revision == expectedRevision && current.getAsBoolean());
        for (Version v : undo) v.document().cells = cells;
        for (Version v : redo) v.document().cells = cells;
        document.cells = cells;
        packed = null;
    }

    private static void checkPackedHistory(Deque<Version> history, List<Cell> unexpanded) throws IOException {
        for (Version v : history) if (v.document().cells != unexpanded) throw HologramPackedAuthoring.stale();
    }

    private void requireMaterialized() {
        if (packedAuthoring()) throw new IllegalStateException("蓝图编辑数据尚未由工作线程准备");
    }

    private int viewCellCount() {
        if(nativeDraft!=null)return nativeDraft.space().size();
        var root = packed;
        return root == null ? document.cells.size() : root.size();
    }

    private Cell viewCell(int ordinal) {
        if(nativeDraft!=null)try{return nativeDraft.cell(ordinal);}catch(IOException invalid){throw new IllegalStateException(invalid);}
        var root = packed;
        return root == null ? document.cells.get(ordinal) : root.read(ordinal);
    }

    private static HologramLocalBlueprintDocument open(byte[] exactJson, String fileId,
        HologramLocalBlueprintCodec codec, Preset source) throws IOException {
        HologramLocalBlueprintDocument opened = new HologramLocalBlueprintDocument(source, exactJson.clone(), codec);
        if (fileId != null) {
            opened.fileId = HologramLibraryService.localId(fileId, true);
            if (fileId.isEmpty()) opened.savedContent = null;
        } else
            if (text(source.id).startsWith("local_")) opened.fileId = HologramLibraryService.localId(source.id, false);
        return opened;
    }

    public long revision() {
        return revision;
    }

    public boolean dirty() {
        return content != savedContent;
    }

    public byte[] artifact() throws IOException {
        if(nativeDraft!=null)throw new IOException("原生蓝图不是旧 JSON");
        if (viewCellCount() == 0) throw new IOException("蓝图没有方块");
        if (encoded == null) {
            // An unencoded packed revision has no current artifact. Ordinary Save/Place own the worker conversion.
            if (packedAuthoring()) throw new IOException("蓝图编辑数据尚未由工作线程准备");
            encoded = codec.write(document);
        }
        return encoded.clone();
    }

    public FrozenDraft freeze() {
        if(nativeDraft!=null)throw new IllegalStateException("原生蓝图施工未启用，不使用旧施工协议");
        HologramPackedAuthoring sourcePacked = packed;
        return new FrozenDraft(
            document,
            codec,
            encoded,
            encoded == null || explicitRetention,
            false,
            LocalSaveIntent.STRICT,
            sourcePacked == null ? null : sourcePacked.layout());
    }

    @FunctionalInterface
    public interface DisplayCell {

        void accept(int x, int y, int z, String block, int metadata, String item, int damage, int facing,
            String tileNbt, String itemNbt, int count, boolean controller) throws IOException;
    }

    /** A copy-on-write document version that can outlive the editor preparing a placement or preview. */
    public static final class FrozenDraft {

        private final Preset document;
        private final HologramLocalBlueprintCodec codec;
        private final boolean explicitRetention;
        private final boolean savePrepared;
        private final HologramLocalBlueprintCodec.CompactLayout packedLayout;
        private final LocalSaveIntent incompleteAuthorIntent;
        private byte[] encoded;

        private FrozenDraft(Preset document, HologramLocalBlueprintCodec codec, byte[] encoded,
            boolean explicitRetention, boolean savePrepared, LocalSaveIntent incompleteAuthorIntent) {
            this(document, codec, encoded, explicitRetention, savePrepared, incompleteAuthorIntent, null);
        }

        private FrozenDraft(Preset document, HologramLocalBlueprintCodec codec, byte[] encoded,
            boolean explicitRetention, boolean savePrepared, LocalSaveIntent incompleteAuthorIntent,
            HologramLocalBlueprintCodec.CompactLayout packedLayout) {
            this.document = document;
            this.codec = codec;
            this.packedLayout = packedLayout;
            this.encoded = encoded;
            this.explicitRetention = explicitRetention;
            this.savePrepared = savePrepared;
            this.incompleteAuthorIntent = incompleteAuthorIntent;
        }

        private byte[] encodedArtifact() throws IOException {
            if (cellCount() == 0) throw new IOException("蓝图没有方块");
            if (document.cells instanceof HologramLiteralCaptureRows && cellCount() > HologramBlueprintLimits.MAX_CELLS)
                throw new IOException("该选区超过旧格式容量，须使用原生蓝图保存");
            if (encoded == null) encoded = codec.write(document, packedLayout);
            return encoded;
        }

        public synchronized byte[] artifact() throws IOException {
            return encodedArtifact().clone();
        }

        /** Bind the new cover to this prepared version's exact JSON, not caller-supplied bytes. */
        public synchronized HologramBlueprintArtifact.Artifact bindLocalSave(String targetId, byte[] png)
            throws IOException {
            if (!savePrepared || !document.id.equals(targetId)) throw new IOException("本地蓝图保存目标已变化");
            return HologramBlueprintArtifact.prepareLocalSave(encodedArtifact(), png);
        }

        /** Direct native encoding of the same retained and authorized save version as the ordinary writer. */
        public com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintMigration.Result nativeArtifact(
            String targetId, java.util.function.BooleanSupplier current) throws IOException {
            if (!savePrepared || !document.id.equals(targetId)) throw new IOException("本地蓝图保存目标已变化");
            return codec.writeNative(document, packedLayout, current);
        }

        public LocalSaveIntent incompleteAuthorIntent() {
            return incompleteAuthorIntent;
        }

        public int cellCount() {
            return packedLayout == null ? document.cells.size() : packedLayout.rows().cells();
        }

        public void visitDisplayCells(DisplayCell visitor,java.util.function.BooleanSupplier current)throws IOException{
            var rows = packedLayout == null ? null : packedLayout.rows();
            int total = cellCount();
            for (int ordinal = 0; ordinal < total; ordinal++) {
                if (!current.getAsBoolean()) throw new IOException("蓝图预览已取消");
                Cell cell = rows == null ? document.cells.get(ordinal)
                    : packedLayout.materials()[rows.materialIndex(ordinal)];
                if (cell.excluded) continue;
                int x = rows == null ? cell.x : rows.coordinate(ordinal, 0);
                int y = rows == null ? cell.y : rows.coordinate(ordinal, 1);
                int z = rows == null ? cell.z : rows.coordinate(ordinal, 2);
                String block = text(cell.block), item = text(cell.item);
                String tileNbt = text(cell.tileNbt), itemNbt = text(cell.itemNbt);
                visitor.accept(x, y, z, block, cell.metadata, item, cell.damage, cell.facing, tileNbt, itemNbt,
                    cell.count, cell.controller);
            }
        }

        /** Metadata-only material-description policy; no file serialization or source-cell traversal. */
        public boolean materialSummarySchemaKnown() { return document.schema == 2; }

        public boolean materialSummaryNeedsReview() {
            return document.retainedNbtKeys != null || !text(document.processingNbt).isEmpty()
                || !text(document.semanticNbt).isEmpty() || !text(document.quantumGroupingNbt).isEmpty()
                || codec.retainedMaterialPolicy(document);
        }

        public NBTTagCompound displayMetadata() {
            NBTTagCompound metadata = new NBTTagCompound();
            metadata.setBoolean("localMaterialPolicyPresent", explicitRetention);
            metadata.setBoolean("retainNbt", document.retainNbt);
            metadata.setBoolean("retainCovers", document.retainCovers);
            if (document.retainedNbtKeys != null)
                metadata.setTag("retainedNbtKeys", HologramSessionPolicy.nbtKeys(document.retainedNbtKeys));
            return metadata;
        }
    }

    public String fileId() {
        return fileId;
    }

    public byte[] prepareSave(String name, String authorName, UUID authorUuid, String localId) throws IOException {
        NBTTagCompound options = new NBTTagCompound();
        if (name != null) options.setString("name", name);
        return prepareSave(options, authorName, authorUuid, localId);
    }

    public byte[] prepareSave(NBTTagCompound options, String authorName, UUID authorUuid, String localId)
        throws IOException {
        return prepareSaveDraft(options, authorName, authorUuid, localId).artifact();
    }

    public FrozenDraft prepareSaveDraft(NBTTagCompound options, String authorName, UUID authorUuid, String localId)
        throws IOException {
        return prepareSaveDraft(options, authorName, authorUuid, localId, LocalSaveIntent.STRICT);
    }

    public FrozenDraft prepareSaveDraft(NBTTagCompound options, String authorName, UUID authorUuid, String localId,
        LocalSaveIntent saveIntent) throws IOException {
        HologramPackedAuthoring sourcePacked = packed;
        Preset prepared = copyHeaderForEdit(document);
        prepared.cells = document.cells;
        prepared.palette = document.palette;
        prepared.id = localId == null || localId.isEmpty() ? "local_" + UUID.randomUUID()
            : HologramLibraryService.localId(localId, false);
        String name = options.getString("name")
            .trim();
        if (!name.isEmpty()) prepared.name = name;
        if (options.hasKey("retainNbt", 1)) prepared.retainNbt = options.getBoolean("retainNbt");
        if (options.hasKey("retainCovers", 1)) prepared.retainCovers = options.getBoolean("retainCovers");
        if (options.hasKey("retainAllNbt", 1)) prepared.retainedNbtKeys = options.getBoolean("retainAllNbt") ? null
            : HologramAuthoringQueries.selectedNbtKeys(options.getTagList("retainedNbtKeys", 8));
        var identity = codec.saveIdentity(prepared);
        // Compare the same effective boundary the UI saw; never assign an option back into the stored policy.
        int authoredLevel = identity == null ? prepared.deidentificationLevel : identity.level();
        if (options.hasKey("deidentificationLevel", 3) && options.getInteger("deidentificationLevel") != authoredLevel)
            throw new IOException("请在专用处理流程确认去标识设置后保存");
        if (text(prepared.authorName).isEmpty() && text(prepared.authorUuid).isEmpty()
            && authorName != null
            && !authorName.trim()
                .isEmpty()
            && authorUuid != null) {
            prepared.authorName = authorName.trim();
            prepared.authorUuid = authorUuid.toString();
        }
        boolean missingName = text(prepared.authorName).isEmpty(), missingUuid = text(prepared.authorUuid).isEmpty();
        LocalSaveIntent incompleteAuthorIntent = LocalSaveIntent.STRICT;
        if (!missingName && missingUuid) {
            if (saveIntent == LocalSaveIntent.SAVE_AS_NEW && editorOrigin == LocalEditorOrigin.NAMED_IMPORT
                && fileId.isEmpty()) incompleteAuthorIntent = saveIntent;
            if (saveIntent == LocalSaveIntent.SAVE_CURRENT && prepared.id.equals(fileId)
                && (editorOrigin == LocalEditorOrigin.TRACKED_PRIVATE || committedNamedImport))
                incompleteAuthorIntent = saveIntent;
        }
        if ((missingName || missingUuid) && incompleteAuthorIntent == LocalSaveIntent.STRICT)
            throw new IOException("蓝图缺少完整作者信息");
        if (prepared.createdAt <= 0 && incompleteAuthorIntent == LocalSaveIntent.STRICT)
            prepared.createdAt = System.currentTimeMillis();
        if (viewCellCount() == 0 || text(prepared.name).trim()
            .isEmpty() || prepared.name.length() > 48) throw new IOException("请填写蓝图名称并保留至少一个方块");
        if (!codec.hasOriginalVersion(prepared)) HologramBlueprintVersions.stampSaved(prepared);
        return new FrozenDraft(prepared, codec, null, true, true, incompleteAuthorIntent,
            sourcePacked == null ? null : sourcePacked.layout());
    }

    /** The caller publishes a save only after the exact JSON and preview pair has committed. */
    public void saved(long expectedRevision, byte[] json) throws IOException {
        requireMaterialized();
        saved(expectedRevision, open(json), json);
    }

    public void saved(long expectedRevision, HologramLocalBlueprintDocument prepared, byte[] exactJson)
        throws IOException {
        requireMaterialized();
        prepared.requireMaterialized();
        if (expectedRevision != revision) throw new IOException("蓝图已修改，请重新保存");
        codec.adoptSaved(prepared.codec);
        explicitRetention = prepared.explicitRetention;
        acceptSave(prepared.document, exactJson);
    }

    /** The exact bytes come from a committed, verified pair produced from this save version. */
    public void saved(long expectedRevision, FrozenDraft prepared, byte[] exactJson) throws IOException {
        if (expectedRevision != revision || prepared.codec != codec) throw new IOException("蓝图已修改，请重新保存");
        explicitRetention = prepared.explicitRetention;
        acceptSave(prepared.document, exactJson);
        if (prepared.incompleteAuthorIntent == LocalSaveIntent.SAVE_AS_NEW) committedNamedImport = true;
    }

    private void acceptSave(Preset saved, byte[] exactJson) throws IOException {
        fileId = HologramLibraryService.localId(saved.id, false);
        document = saved;
        structureEvidence = null;
        savedContent = content;
        // Save may change opaque artifact metadata without changing the content token. Keep ordinary history,
        // but retire its certificates rather than guessing the exact pre-save editor publication.
        historySaveEpoch = new Object();
        historyDelta = null;
        encoded = exactJson.clone();
        revision++;
        status = "蓝图已保存到客户端本地";
    }

    /** A version owns its header and list; cells are copied when a command changes them. */
    private Preset copyForEdit(Preset source) {
        Preset next = copyHeaderForEdit(source);
        next.cells.addAll(source.cells);
        next.palette.addAll(source.palette);
        return next;
    }

    private Preset shareHeaderRows(Preset source) {
        Preset next = copyHeaderForEdit(source);
        next.cells = source.cells;
        next.palette = source.palette;
        return next;
    }

    private Preset copyHeaderForEdit(Preset source) {
        Preset next = HologramSnapshotMetadataSource.copyMetadata(source);
        codec.copied(source, next);
        for (int i = 0; i < source.structures.size(); i++)
            codec.copied(source.structures.get(i), next.structures.get(i));
        for (int i = 0; i < source.hintAreas.size(); i++) codec.copied(source.hintAreas.get(i), next.hintAreas.get(i));
        return next;
    }

    public static boolean metadataOnlyEdit(String op) {
        return "editBlueprintPrompt".equals(op) || "configureBlueprintNbt".equals(op);
    }

    public HistoryDelta historyDelta(long previous) {
        var delta = historyDelta;
        return delta != null && delta.fromRevision() == previous && delta.revision() == revision ? delta : null;
    }

    public NBTTagCompound annotationDelta() {
        AnnotationChange change = annotationChange;
        if (change == null || change.revision() != revision) return null;
        NBTTagCompound row = new NBTTagCompound();
        row.setInteger("index", change.index());
        row.setBoolean("annotationOnly", true);
        row.setString("prompt", change.prompt());
        row.setBoolean("excluded", change.excluded());
        return row;
    }

    private Cell copyCell(Cell original) {
        Cell copy = HologramSnapshotMetadataSource.copyCell(original);
        codec.copied(original, copy);
        return copy;
    }

    private Structure copyStructure(Structure original) {
        Structure copy = HologramBlueprintData.copyStructure(original);
        codec.copied(original, copy);
        return copy;
    }

    public static boolean handles(String op) {
        return switch (op) {
            case "editCopy", "editCut", "editPaste", "editMove", "editDelete", "editUndo", "editRedo", "addPosition", "restoreCell", "clearPin", "pin", "customPin", "sourcePin", "hatchFacing", "rotateCell", "rotateBlueprint", "editBlueprintPrompt", "editCellFlags", "editHintArea", "configureBlueprintNbt", "selectStructure", "selectCell", "configure", "placeTextBox" -> true;
            default -> processingCommand(op);
        };
    }

    public static boolean processingCommand(String op) {
        return switch (op) {
            case "beginBlueprintReprocessing", "processBlueprint", "configureBlueprintCellNbt", "editBlueprintCellNbt", "configureBlueprintQuantumGroups", "saveProcessingPreset", "loadProcessingPreset", "updateProcessingPreset", "deleteProcessingPreset" -> true;
            default -> false;
        };
    }

    /** Reuse the original workbench commands and the selection indices from this exact document revision. */
    public boolean apply(String op, NBTTagCompound request) throws IOException {
        return apply(op, request, () -> true);
    }

    public boolean apply(String op, NBTTagCompound request, java.util.function.BooleanSupplier current)
        throws IOException {
        if (!handles(op)) return false;
        checkRevision(request);
        if(nativeDraft!=null)return applyNative(op,request,current);
        if (!supportsPackedCommand(op)) requireMaterialized();
        if (processingCommand(op)) {
            process(op, request, current);
            return true;
        }
        switch (op) {
            case "editUndo" -> restore(undo, redo, "已撤销规划编辑", current);
            case "editRedo" -> restore(redo, undo, "已重做规划编辑", current);
            case "selectStructure" -> selectStructure(request.getString("structureId"));
            case "selectCell" -> setSelection(request.getInteger("selected"), request.getIntArray("selectedIndices"));
            case "configure" -> {
                Structure structure = configuredStructure(request);
                if (structure != null && !HologramLocalBlueprintStructure.settings(structure)
                    .equals(HologramLocalBlueprintStructure.requested(structure, request)))
                    throw new IOException("请在客户端结构面板应用原生信道设置");
                viewConfiguration(request);
            }
            case "editCopy" -> {
                clipboard = copySelection(indices(request));
                status = "已复制选中蓝图格及设备配置";
            }
            default -> edit(op, request, current);
        }
        return true;
    }

    private void checkRevision(NBTTagCompound request) throws IOException {
        if (request.hasKey("planRevision")
            && (!request.hasKey("planRevision", 4) || request.getLong("planRevision") != revision))
            throw new IOException("蓝图已更新，请重新选择编辑目标");
    }

    private Structure configuredStructure(NBTTagCompound request) throws IOException {
        if (request.hasKey("structureId", 8) && !activeStructure.equals(request.getString("structureId")))
            throw new IOException("结构选择已变化，请重新选择编辑目标");
        return HologramBlueprintData.blueprintSource(document, activeStructure);
    }

    private void viewConfiguration(NBTTagCompound request) {
        if (request.hasKey("scope", 3)) scope = HologramValues.clamp(request.getInteger("scope"), 0, 3);
        if (request.hasKey("layer", 3)) layer = HologramValues.clamp(request.getInteger("layer"), -256, 256);
        if (request.hasKey("mode", 3)) mode = HologramValues.clamp(request.getInteger("mode"), 0, 1);
    }

    /** Native callbacks belong to the client tick. The resulting portable rows commit on the existing editor worker. */
    public StructureTask beginStructureCommand(String op, NBTTagCompound request) throws IOException {
        if (nativeDraft!=null||!"selectStructure".equals(op) && !"configure".equals(op)) return null;
        checkRevision(request);
        if (!supportsPackedCommand(op)) requireMaterialized();
        if ("selectStructure".equals(op)) {
            String id = request.getString("structureId");
            if (id.isEmpty()) return null;
            Structure selected = HologramBlueprintData.blueprintSource(document, id);
            if (selected == null) throw new IOException("结构不存在");
            if (selected.textBoxNbt != null && !selected.textBoxNbt.isEmpty()) return null;
            return new StructureTask(selected, request, null);
        }
        Structure source = configuredStructure(request);
        if (source == null) return null;
        NBTTagCompound requested = HologramLocalBlueprintStructure.requested(source, request);
        return HologramLocalBlueprintStructure.settings(source)
            .equals(requested) ? null : new StructureTask(source, request, requested);
    }

    public final class StructureTask implements AutoCloseable {

        private final Preset original = document;
        private final long expectedRevision = revision;
        private final Structure source;
        private final NBTTagCompound request, previous, requested;
        private final long sourceRevision = StructureSources.revision();
        private final long declarationGeneration = HologramDeclarationAdapters.generation();
        private HologramLocalControllerCapture nativeCapture;
        private NBTTagCompound before, after;
        private StructureSources.ReadSet beforeProof, afterProof;
        private StructureEvidence reused;
        private boolean checkedEvidence;
        private String unavailable = "";
        private boolean done;

        private StructureTask(Structure source, NBTTagCompound request, NBTTagCompound requested) {
            this.source = source;
            this.request = request;
            this.requested = requested;
            previous = HologramLocalBlueprintStructure.settings(source);
        }

        public boolean step(int rows, long nanos) throws IOException {
            if (done) throw new IllegalStateException("已完成原生结构读取");
            checkCurrent();
            try {
                if (!checkedEvidence) {
                    checkedEvidence = true;
                    StructureEvidence evidence = structureEvidence;
                    if (evidence != null && evidence.document() == original
                        && evidence.source() == source
                        && evidence.settings()
                            .equals(previous)
                        && proofCurrent(evidence.proof())) {
                        reused = evidence;
                        if (requested == null) {
                            done = true;
                            return true;
                        }
                    }
                }
                boolean readingBefore = reused == null && before == null;
                if (nativeCapture == null) nativeCapture = HologramLocalBlueprintStructure
                    .capture(original, source, readingBefore ? previous : requested);
                NBTTagCompound result = nativeCapture.step(rows, nanos);
                if (result == null) return false;
                StructureSources.ReadSet proof = nativeCapture.reuseProof();
                close();
                if (readingBefore) {
                    before = result;
                    beforeProof = proof;
                } else {
                    StructureSources.ReadSet proofOfBefore = reused == null ? beforeProof : reused.proof();
                    if (proofOfBefore != null && !proofCurrent(proofOfBefore)) throw new IOException("原生结构来源已变化，请重试");
                    after = result;
                    afterProof = proof;
                }
                done = requested == null || after != null;
            } catch (IOException | RuntimeException | LinkageError failure) {
                close();
                if (requested != null) throw new IOException("结构设置未应用，原蓝图已保留：" + failure.getMessage(), failure);
                unavailable = "原生结构无法读取，仍可编辑蓝图格：" + failure.getMessage();
                done = true;
            }
            return done;
        }

        public void commit(java.util.function.BooleanSupplier current) throws IOException {
            if (!done) throw new IllegalStateException("原生结构仍在读取");
            checkCurrent();
            if (requested == null) {
                if (!current.getAsBoolean()) throw new IOException("结构读取已取消");
                selectStructure(source.id);
                if (reused == null) {
                    rememberStructureOptions(source.id, previous, before);
                    structureEvidence = null;
                    if (beforeProof != null) try {
                        structureEvidence = new StructureEvidence(
                            original,
                            source,
                            previous,
                            HologramLocalBlueprintStructure.declared(source, before),
                            beforeProof);
                    } catch (IOException invalidFootprint) {
                        // Options remain visible; changing geometry still requires a valid original footprint.
                    }
                }
                status = unavailable;
                return;
            }
            Preset next = copyForEdit(original);
            boolean singleSource = original.structures.stream()
                .filter(group -> text(group.textBoxNbt).isEmpty())
                .limit(2)
                .count() == 1;
            Map<HologramLocalBlueprintStructure.Position, Cell> previousRows = reused == null
                ? HologramLocalBlueprintStructure.declared(source, before)
                : reused.rows();
            Map<HologramLocalBlueprintStructure.Position, Cell> requestedRows = HologramLocalBlueprintStructure
                .declared(source, after);
            List<Cell> cells = HologramLocalBlueprintStructure.merge(
                original.cells,
                source.id,
                previousRows,
                requestedRows,
                singleSource,
                HologramLocalBlueprintDocument.this::copyCell,
                codec::replacedNativeTargets);
            next.cells.clear();
            next.cells.addAll(cells);
            Structure changed = HologramBlueprintData.blueprintSource(next, source.id);
            HologramLocalBlueprintStructure.applyConfiguration(changed, requested);
            if (!current.getAsBoolean()) throw new IOException("结构读取已取消");
            checkCurrent();
            if (reused == null) rememberStructureOptions(source.id, previous, before);
            rememberStructureOptions(source.id, requested, after);
            remember(undo);
            redo.clear();
            document = next;
            structureEvidence = afterProof == null ? null
                : new StructureEvidence(next, changed, requested, requestedRows, afterProof);
            content = new Object();
            encoded = null;
            revision++;
            viewConfiguration(request);
            setSelection(-1, new int[0]);
            if (processingPolicy != null) {
                processingReviewed = false;
                processingView = null;
            }
            status = "所选结构信道已更新";
        }

        private void checkCurrent() throws IOException {
            if (document != original || revision != expectedRevision
                || sourceRevision != StructureSources.revision()
                || declarationGeneration != HologramDeclarationAdapters.generation()
                || requested != null && !source.id.equals(activeStructure)) throw new IOException("蓝图或结构选择已变化，请重试");
        }

        private boolean proofCurrent(StructureSources.ReadSet proof) {
            try {
                return text(proof.invalidReason()).isEmpty();
            } catch (RuntimeException | LinkageError unavailable) {
                return false;
            }
        }

        @Override
        public void close() {
            if (nativeCapture != null) nativeCapture.close();
            nativeCapture = null;
        }
    }

    private void rememberStructureOptions(String id, NBTTagCompound settings, NBTTagCompound source) {
        StructureSettings key = new StructureSettings(id, settings);
        if (source == null) {
            structureOptions.remove(key);
            return;
        }
        NBTTagCompound options = new NBTTagCompound();
        options.setTag(
            "capabilities",
            source.getTagList("capabilities", 10)
                .copy());
        options.setTag(
            "structureDescription",
            source.getTagList("structureDescription", 8)
                .copy());
        structureOptions.put(key, options);
        if (structureOptions.size() > HISTORY_LIMIT * 2) structureOptions.remove(
            structureOptions.keySet()
                .iterator()
                .next());
    }

    private void edit(String op, NBTTagCompound request, java.util.function.BooleanSupplier current)
        throws IOException {
        Preset next = metadataOnlyEdit(op) ? shareHeaderRows(document) : copyForEdit(document);
        Clipboard copied = null;
        int[] affected = new int[0];
        String message = "蓝图已修改";
        switch (op) {
            case "editCut" -> {
                affected = indices(request);
                copied = copySelection(affected);
                for (int index : affected) change(next, index).excluded = true;
                message = "已剪切规划，原位置已标为排除";
            }
            case "editPaste" -> {
                if (clipboard == null) throw new IOException("请先复制蓝图格");
                Cell anchor = document.cells.get(primary(request));
                Position from = clipboard.origin();
                Position to = Position.of(anchor);
                appendCopies(next, clipboard, (long) to.x - from.x, (long) to.y - from.y, (long) to.z - from.z, true);
                message = "已粘贴蓝图规划";
            }
            case "editMove" -> {
                affected = indices(request);
                int dx = offset(request, "dx"), dy = offset(request, "dy"), dz = offset(request, "dz");
                if (dx == 0 && dy == 0 && dz == 0) return;
                if (request.getBoolean("copy")) appendCopies(next, copySelection(affected), dx, dy, dz, false);
                else move(next, affected, dx, dy, dz);
                message = request.getBoolean("copy") ? "已复制蓝图格到新坐标" : "已移动选中蓝图格";
            }
            case "editDelete" -> {
                affected = indices(request);
                remove(next, affected);
                message = "已删除选中蓝图格，可撤销";
            }
            case "addPosition" -> {
                addPosition(next, request);
                message = "位置已添加";
            }
            case "restoreCell", "clearPin" -> {
                affected = indices(request);
                for (int index : affected) {
                    Cell cell = editableCell(next, index);
                    ItemStack material = cell.defaultStack();
                    if (material == null) throw new IOException("所选蓝图格没有可恢复的默认外壳");
                    replaceMaterial(cell, material);
                }
            }
            case "pin", "customPin", "sourcePin" -> {
                affected = indices(request);
                int choice = request.hasKey("choice", 3) ? request.getInteger("choice") : -1;
                ItemStack sample = request.hasKey("expectedSample", 10)
                    ? ItemStack.loadItemStackFromNBT(request.getCompoundTag("expectedSample"))
                    : null;
                for (int index : affected) {
                    Cell cell = editableCell(next, index);
                    ItemStack target = sample;
                    if (target == null && "pin".equals(op)
                        && cell.allowedTargets != null
                        && choice >= 0
                        && choice < cell.allowedTargets.size())
                        target = cell.allowedTargets.get(choice)
                            .stack();
                    replaceMaterial(cell, target);
                }
            }
            case "rotateBlueprint" -> {
                next.cells.replaceAll(this::copyCell);
                HologramWorldPlacementWorkflow.turnLocalAuthorDocument(next);
            }
            case "rotateCell" -> {
                affected = indices(request);
                for (int index : affected) {
                    Cell cell = editableCell(next, index);
                    for (String member : cell.memberships()) {
                        Structure structure = HologramBlueprintData.blueprintSource(next, member);
                        if (structure != null && !text(structure.textBoxNbt).isEmpty())
                            throw new IOException("请使用整图旋转保留文字或图片对象");
                    }
                    rotateCell(cell, request.getBoolean("vertical"));
                }
            }
            case "hatchFacing" -> {
                int index = primary(request);
                if (!request.hasKey("facing", 3)) throw new IOException("仓室朝向无效");
                int direction = request.getInteger("facing");
                Cell cell = editableCell(next, index);
                if (direction < -1 || !HologramCellPolicy.virtualFrontAllowed(cell.stack(), direction))
                    throw new IOException("仓室不支持所选方向");
                cell.facing = direction;
                affected = new int[] { index };
            }
            case "editBlueprintPrompt" -> next.prompt = HologramSessionPolicy
                .boundedPrompt(request.getString("prompt"));
            case "editCellFlags", "editHintArea" -> {
                affected = indices(request);
                String prompt = HologramSessionPolicy.boundedPrompt(request.getString("prompt"));
                if ("editHintArea".equals(op)) updateHint(next, affected, prompt);
                for (int index : affected) {
                    Cell cell = change(next, index);
                    if (request.hasKey("excluded", 1)) cell.excluded = request.getBoolean("excluded");
                    if ("editCellFlags".equals(op) && request.hasKey("prompt", 8)) cell.prompt = prompt;
                }
            }
            case "configureBlueprintNbt" -> {
                if (request.hasKey("all", 1)) next.retainedNbtKeys = request.getBoolean("all") ? null
                    : HologramAuthoringQueries.selectedNbtKeys(request.getTagList("keys", 8));
                if (request.hasKey("retainNbt", 1)) next.retainNbt = request.getBoolean("retainNbt");
                if (request.hasKey("retainCovers", 1)) next.retainCovers = request.getBoolean("retainCovers");
            }
            case "placeTextBox" -> {
                HologramTextBox text = HologramTextBox.read(request.getCompoundTag("textBox"));
                placeAttachment(next, text, current);
                message = text.noun() + "已加入蓝图";
            }
            default -> throw new IllegalArgumentException(op);
        }
        int cells = packedAuthoring() ? viewCellCount() : next.cells.size();
        if (!HologramBlueprintLimits.acceptsLocal(cells) || next.structures.size() > 256)
            throw new IOException("蓝图方块或结构数量超出上限");
        if (!current.getAsBoolean()) throw new IOException("本地蓝图编辑已取消");
        Object nextContent = new Object();
        remember(undo, certifyHistoryEdge(op, affected, next, nextContent));
        redo.clear();
        document = next;
        structureEvidence = null;
        content = nextContent;
        encoded = null;
        revision++;
        if ("editCellFlags".equals(op) && affected.length == 1) {
            int index = affected[0];
            Cell cell = next.cells.get(index);
            annotationChange = new AnnotationChange(revision, index, text(cell.prompt), cell.excluded);
        }
        if (processingPolicy != null) {
            processingReviewed = false;
            processingView = null;
        }
        status = message;
        textPlacementAck = null;
        if ("placeTextBox".equals(op)) {
            textPlacementAck = new NBTTagCompound();
            textPlacementAck.setLong("request", request.getLong("textRequest"));
            textPlacementAck.setBoolean("accepted", true);
            textPlacementAck.setString("message", message);
        }
        if (copied != null) clipboard = copied;
        if ("addPosition".equals(op)) setSelection(document.cells.size() - 1, new int[] { document.cells.size() - 1 });
        else if ("editDelete".equals(op) || "editPaste".equals(op) || "editMove".equals(op))
            setSelection(-1, new int[0]);
        else if (affected.length > 0) setSelection(affected[0], affected);
    }

    private void process(String op, NBTTagCompound request, java.util.function.BooleanSupplier current)
        throws IOException {
        Preset before = document;
        Preset source = copyForEdit(before);
        codec.processingMetadata(before, source);
        HologramBlueprintProcessing.Policy policy = processingPolicy == null
            ? HologramBlueprintProcessing.policy(source)
            : HologramBlueprintProcessing.Policy.read(processingPolicy.write());
        Preset replacement = null;
        boolean reviewed = false;
        String message;
        switch (op) {
            case "beginBlueprintReprocessing" -> {
                policy.confirmedStages.clear();
                policy.resourceDecisions.clear();
                policy.coordinateOverrides.clear();
                policy.cellNbtOverrides.clear();
                message = "已按当前修改准备蓝图处理流程";
            }
            case "processBlueprint" -> {
                policy = HologramBlueprintProcessing.Policy.read(request.getCompoundTag("policy"));
                replacement = HologramBlueprintProcessing
                    .process(source, policy, sourceDimension, originX, originY, originZ);
                HologramAePolicy.Draft review = HologramAePolicy.sanitizeOwned(
                    replacement,
                    java.util.Collections.emptyMap(),
                    java.util.Collections.emptyMap(),
                    HologramAePolicy.PreparationProgress.NONE);
                if (review.blocking) throw new IOException("蓝图包含待处理的设备数据");
                replacement = HologramBlueprintProcessing.enforcePolicy(review.preset);
                reviewed = true;
                message = "蓝图处理流程已完成";
            }
            case "configureBlueprintQuantumGroups" -> {
                if (!request.hasKey("groups", 9)) throw new IOException("量子网络分类缺少完整类群列表");
                replacement = HologramQuantumGroups.configure(source, request.getTagList("groups", 10));
                HologramBlueprintProcessing.invalidate(replacement, "quantum");
                trimStages(policy, 2);
                message = "本蓝图量子类群已保存";
            }
            case "configureBlueprintCellNbt" -> {
                policy = configureCellNbt(source, policy, request);
                message = "方块 NBT 策略已更新，请重新审核 NBT 与提示步骤";
            }
            case "editBlueprintCellNbt" -> {
                int[] indices = indices(request);
                NBTTagList changes = request.getTagList("changes", 10);
                if (changes.tagCount() == 0 || changes.tagCount() > 128) throw new IOException("NBT 修改条目数量无效");
                for (int index : indices) change(source, index);
                for (int i = 0; i < changes.tagCount(); i++) {
                    NBTTagCompound change = changes.getCompoundTagAt(i);
                    HologramBlueprintProcessing.editNbtValue(
                        source,
                        indices,
                        change.getString("path"),
                        change.getString("type"),
                        change.getString("value"));
                }
                HologramBlueprintProcessing.invalidate(source, "nbt");
                trimStages(policy, 4);
                replacement = source;
                message = "方块 NBT 配置已修改，请重新审核 NBT 与提示步骤";
            }
            case "loadProcessingPreset" -> {
                HologramProcessingPresets.Entry preset = HologramProcessingPresets.get(request.getString("presetId"));
                if (preset == null) throw new IOException("处理流程预设不存在");
                policy = preset.policy;
                policy.confirmedStages.clear();
                message = "处理预设已选定";
            }
            default -> {
                if ("deleteProcessingPreset".equals(op))
                    HologramProcessingPresets.delete(request.getString("presetId"));
                else {
                    NBTTagCompound reusable = (NBTTagCompound) request.getCompoundTag("policy")
                        .copy();
                    reusable.removeTag("resourceDecisions");
                    HologramBlueprintProcessing.Policy saved = HologramBlueprintProcessing.Policy.read(reusable);
                    if ("updateProcessingPreset".equals(op)) HologramProcessingPresets
                        .update(request.getString("presetId"), request.getString("name"), saved);
                    else HologramProcessingPresets.save(request.getString("name"), saved);
                }
                processingPresets = HologramProcessingPresets.labels();
                status = "处理流程预设已更新";
                return;
            }
        }
        NBTTagCompound view = HologramBlueprintProcessing
            .state(replacement == null ? source : replacement, policy, sourceDimension, originX, originY, originZ);
        NBTTagList presets = HologramProcessingPresets.labels();
        if (!current.getAsBoolean()) throw new IOException("本地蓝图处理已取消");
        if (replacement != null) {
            codec.acceptProcessed(before, replacement);
            remember(undo);
            redo.clear();
            document = replacement;
            structureEvidence = null;
            content = new Object();
            encoded = null;
            revision++;
        }
        processingPolicy = policy;
        processingView = view;
        processingPresets = presets;
        processingReviewed = reviewed;
        status = message;
    }

    private HologramBlueprintProcessing.Policy configureCellNbt(Preset source,
        HologramBlueprintProcessing.Policy policy, NBTTagCompound request) throws IOException {
        if (request.hasKey("structureId", 8) && !activeStructure.equals(request.getString("structureId")))
            throw new IOException("结构选择已变化，请重新选择 NBT 编辑格");
        NBTTagCompound tag = policy.write();
        Map<Position, NBTTagCompound> byPosition = new LinkedHashMap<>();
        NBTTagList original = tag.getTagList("cellNbtOverrides", 10);
        for (int i = 0; i < original.tagCount(); i++) {
            NBTTagCompound row = original.getCompoundTagAt(i);
            byPosition.put(new Position(row.getInteger("x"), row.getInteger("y"), row.getInteger("z")), row);
        }
        for (int index : indices(request)) {
            Cell cell = source.cells.get(index);
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("x", cell.x);
            row.setInteger("y", cell.y);
            row.setInteger("z", cell.z);
            row.setBoolean("all", request.getBoolean("all"));
            row.setTag(
                "keys",
                HologramSessionPolicy.nbtKeys(HologramAuthoringQueries.selectedNbtKeys(request.getTagList("keys", 8))));
            row.setTag(
                "ignoredPaths",
                request.getTagList("ignoredPaths", 8)
                    .copy());
            byPosition.put(Position.of(cell), row);
        }
        NBTTagList rows = new NBTTagList();
        for (NBTTagCompound row : byPosition.values()) rows.appendTag(row);
        tag.setTag("cellNbtOverrides", rows);
        HologramBlueprintProcessing.Policy result = HologramBlueprintProcessing.Policy.read(tag);
        trimStages(result, 4);
        return result;
    }

    private static void trimStages(HologramBlueprintProcessing.Policy policy, int count) {
        while (policy.confirmedStages.size() > count) policy.confirmedStages.remove(policy.confirmedStages.size() - 1);
    }

    private static void placeAttachment(Preset draft, HologramTextBox text, java.util.function.BooleanSupplier current)
        throws IOException {
        if (text.id.isEmpty()) throw new IOException("缺少附加对象标识");
        Structure group = HologramBlueprintData.blueprintSource(draft, text.id);
        if (group != null && (group.textBoxNbt == null || group.textBoxNbt.isEmpty()))
            throw new IOException("目标不是附加对象");
        Block host = Block.getBlockFromName("littletiles:BlockLittleTiles");
        if (host == null || host == Blocks.air || !HologramNativePlacement.supported(host))
            throw new IOException("需要 LittleTiles 兼容方块");
        Map<Position, Cell> occupied = positions(draft.cells);
        draft.cells.removeIf(cell -> cell.belongsTo(text.id));
        HologramBlueprintNbt.Encoded definition = HologramBlueprintNbt.encodeCapturedWithSize(null, text.write());
        long payloadBytes = definition.expandedBytes;
        Set<Position> replaced = new HashSet<>();
        List<Cell> addedCells = new ArrayList<>();
        for (HologramTextBox.Cell shape : text.generate()) {
            if (!current.getAsBoolean()) throw new IOException("附加对象编辑已取消");
            Position position = new Position(shape.x, shape.y, shape.z).translate(0, 0, 0);
            Cell old = occupied.get(position);
            if (old != null && !old.belongsTo(text.id)) {
                if (old.controller || !old.excluded) throw new IOException(text.noun() + "与蓝图内容冲突，请移动后确认");
                replaced.add(position);
            }
            HologramCapture.Cell generated = HologramTextWorkflow.generatedCell(host, shape, shape.x, shape.y, shape.z);
            if (!generated.tileNbt.isEmpty()) payloadBytes += shape.encodedTileBytes();
            if (payloadBytes > 64L * 1024 * 1024) throw new IOException("附加对象细节超出 64 MiB 数据预算");
            Cell cell = new Cell();
            cell.x = shape.x;
            cell.y = shape.y;
            cell.z = shape.z;
            cell.block = generated.blueprintBlock;
            cell.metadata = generated.meta;
            cell.item = generated.blueprintItem;
            cell.damage = generated.blueprintDamage;
            cell.tileNbt = generated.tileNbt;
            cell.structureId = text.id;
            addedCells.add(cell);
        }
        if (addedCells.isEmpty()) throw new IOException("附加对象没有可用内容");
        draft.cells.removeIf(cell -> replaced.contains(Position.of(cell)));
        draft.cells.addAll(addedCells);
        if (group == null) {
            group = new Structure();
            draft.structures.add(group);
            group.id = text.id;
        }
        String title = text.picture == null ? text.text : text.picture.filename;
        group.name = text.noun() + " · " + title.substring(0, Math.min(28, title.length()));
        group.sourceMod = "gtmb";
        group.x = text.x;
        group.y = text.y;
        group.z = text.z;
        group.textBoxNbt = definition.value;
    }

    /** Keep full copies only on the established fallback. A certificate never scans rows or compares whole docs. */
    private HistoryEdge certifyHistoryEdge(String operation, int[] affected, Preset next, Object nextContent) {
        if ("editBlueprintPrompt".equals(operation)) {
            return new HistoryEdge(historySaveEpoch, content, nextContent, null);
        }
        if (!"editCellFlags".equals(operation) || affected.length != 1 || packedAuthoring()) return null;
        int index = affected[0];
        Cell before = document.cells.get(index), after = next.cells.get(index);
        if (!plainPromptCell(before) || before.excluded != after.excluded
            || text(before.prompt).equals(text(after.prompt))
            || before.x != after.x
            || before.y != after.y
            || before.z != after.z) return null;
        // This sole production command writes only prompt/excluded to a copy of exactly this cell.
        return new HistoryEdge(
            historySaveEpoch,
            content,
            nextContent,
            new PromptCellEdge(index, document.cells, next.cells, document.palette, next.palette, before, after));
    }

    private static boolean plainPromptCell(Cell cell) {
        return !cell.controller && !text(cell.block).isEmpty()
            && !"minecraft:air".equals(cell.block)
            && text(cell.tileNbt).isEmpty()
            && text(cell.itemNbt).isEmpty()
            && text(cell.structureId).isEmpty()
            && (cell.structureIds == null || cell.structureIds.isEmpty())
            && (cell.allowedTargets == null || cell.allowedTargets.isEmpty())
            && (cell.structureHatchFacings == null || cell.structureHatchFacings.length == 0);
    }

    private boolean boundedHistoryHeader(Preset preset) {
        // Header copies below must not traverse a processing report, attachment, placement ack or a long source list.
        return activeStructure.isEmpty() && processingPolicy == null
            && processingView == null
            && processingPresets == null
            && textPlacementAck == null
            && preset.structures.isEmpty()
            && preset.hintAreas.isEmpty()
            && preset.aeIssues.isEmpty()
            && (preset.sourceItemFrames == null || preset.sourceItemFrames.isEmpty())
            && preset.modVersions.size() <= 64
            && (preset.retainedNbtKeys == null || preset.retainedNbtKeys.size() <= 64);
    }

    private void remember(Deque<Version> history) {
        remember(history, null);
    }

    private void remember(Deque<Version> history, HistoryEdge edge) {
        if (history.size() >= HISTORY_LIMIT) history.removeFirst();
        history.addLast(new Version(document, content, edge));
    }

    private void restore(Deque<Version> from, Deque<Version> to, String message,
        java.util.function.BooleanSupplier current) throws IOException {
        if (from.isEmpty()) return;
        boolean headerOnly = packedAuthoring(), undoing = from == undo;
        Version previous = from.peekLast();
        Preset source = document;
        HistoryEdge edge = previous.edge();
        boolean certified = !headerOnly && edge != null
            && edge.matches(historySaveEpoch, content, previous, source, undoing)
            && boundedHistoryHeader(source)
            && boundedHistoryHeader(previous.document());
        Preset replacement = headerOnly || certified ? shareHeaderRows(previous.document())
            : copyForEdit(previous.document());
        if ((headerOnly || certified) && !current.getAsBoolean()) throw new IOException("本地蓝图编辑已取消");
        // Carry exactly this edge across the inverse; any unknown adjacency stays unclassified.
        remember(to, edge);
        from.removeLast();
        replacement.id = source.id;
        replacement.name = source.name;
        replacement.authorName = source.authorName;
        replacement.authorUuid = source.authorUuid;
        replacement.createdAt = source.createdAt;
        replacement.nativeBlueprint = source.nativeBlueprint;
        document = replacement;
        structureEvidence = null;
        content = previous.content();
        encoded = null;
        long oldRevision = revision++;
        historyDelta = !certified ? null
            : edge.cell() == null ? new HistoryDelta(
                oldRevision,
                revision,
                source.cells.size(),
                -1,
                0,
                0,
                0,
                text(source.prompt),
                text(replacement.prompt),
                "",
                0,
                0,
                false) : promptHistoryDelta(edge.cell(), undoing, oldRevision, source.cells.size());
        status = message;
        if (!activeStructure.isEmpty() && HologramBlueprintData.blueprintSource(document, activeStructure) == null)
            activeStructure = "";
        setSelection(-1, new int[0]);
    }

    private HistoryDelta promptHistoryDelta(PromptCellEdge pair, boolean undoing, long previous, int size) {
        Cell from = undoing ? pair.after() : pair.before();
        Cell target = undoing ? pair.before() : pair.after();
        return new HistoryDelta(
            previous,
            revision,
            size,
            pair.index(),
            from.x,
            from.y,
            from.z,
            text(from.prompt),
            text(target.prompt),
            text(from.block),
            from.metadata,
            from.facing,
            from.excluded);
    }

    private int[] indices(NBTTagCompound request) throws IOException {
        int[] values = request.getIntArray("indices");
        if (values.length == 0) values = request.getIntArray("selectedIndices");
        if (values.length == 0) values = new int[] { primary(request) };
        if (values.length > HologramCapture.LIMIT) throw new IOException("选择数量超过蓝图上限");
        if (request.hasKey("structureId", 8) && !request.getString("structureId")
            .equals(activeStructure)) throw new IOException("结构选择已变化，请重新选择编辑目标");
        for (int index : values) if (index < 0 || index >= viewCellCount()
            || !activeStructure.isEmpty() && !(nativeDraft!=null?nativeDraft.cell(index):document.cells.get(index))
                .memberships()
                .isEmpty()
                && !(nativeDraft!=null?nativeDraft.cell(index):document.cells.get(index))
                    .belongsTo(activeStructure))
            throw new IOException("选择位置或结构已变化，请重新选择");
        return Arrays.stream(values)
            .distinct()
            .toArray();
    }

    private int primary(NBTTagCompound request) throws IOException {
        int index = request.hasKey("selected", 3) ? request.getInteger("selected") : selected;
        if (index < 0 || index >= viewCellCount()) throw new IOException("请先选择蓝图格");
        return index;
    }

    private void setSelection(int primary, int[] values) {
        int count = viewCellCount();
        selected = primary >= 0 && primary < count ? primary : -1;
        selection = Arrays.stream(values)
            .filter(index -> index >= 0 && index < count)
            .distinct()
            .toArray();
    }

    private void selectStructure(String id) throws IOException {
        if (!id.isEmpty() && HologramBlueprintData.blueprintSource(document, id) == null)
            throw new IOException("结构不存在");
        activeStructure = id;
        setSelection(-1, new int[0]);
    }

    private Cell change(Preset draft, int index) {
        Cell copy = copyCell(draft.cells.get(index));
        draft.cells.set(index, copy);
        return copy;
    }

    private Cell editableCell(Preset draft, int index) throws IOException {
        Cell copy = change(draft, index);
        if (copy.controller) throw new IOException("控制器始终受保护");
        return copy;
    }

    private static int offset(NBTTagCompound request, String key) throws IOException {
        if (request.hasKey(key) && !request.hasKey(key, 3)) throw new IOException("蓝图移动坐标无效");
        return request.getInteger(key);
    }

    private void addPosition(Preset draft, NBTTagCompound request) throws IOException {
        if (!request.hasKey("dx", 3) || !request.hasKey("dy", 3) || !request.hasKey("dz", 3))
            throw new IOException("蓝图位置无效");
        Position position = new Position(0, 0, 0)
            .translate(request.getInteger("dx"), request.getInteger("dy"), request.getInteger("dz"));
        for (Cell cell : draft.cells) if (position.equals(Position.of(cell))) throw new IOException("蓝图位置已存在");
        Cell cell = new Cell();
        position.write(cell);
        cell.block = "minecraft:air";
        if (!activeStructure.isEmpty()) cell.structureId = activeStructure;
        draft.cells.add(cell);
    }

    private static void replaceMaterial(Cell cell, ItemStack target) throws IOException {
        if (target == null || HologramRecovery.hasSeal(target)) throw new IOException("请先选择可放置的普通方块或设备");
        Block block = Block.getBlockFromItem(target.getItem());
        if (block == null || block == Blocks.air) throw new IOException("样本没有可放置方块");
        if (target.getItem() instanceof gregtech.common.blocks.ItemMachines
            && gregtech.common.blocks.ItemMachines.getMetaTileEntity(target) == null) throw new IOException("样本设备尚未注册");
        String item = String.valueOf(Item.itemRegistry.getNameForObject(target.getItem()));
        if (item.equals(cell.item) && cell.damage == target.getItemDamage()) {
            cell.excluded = false;
            return;
        }
        cell.item = item;
        cell.block = String.valueOf(Block.blockRegistry.getNameForObject(block));
        cell.damage = target.getItemDamage();
        cell.metadata = target.getItem()
            .getMetadata(target.getItemDamage());
        cell.count = 1;
        cell.itemNbt = null;
        cell.tileNbt = null;
        cell.facing = -1;
        cell.excluded = false;
    }

    static void rotateCell(Cell cell, boolean vertical) throws IOException {
        ItemStack target = cell.stack();
        if (target != null && target.getItem() instanceof gregtech.common.blocks.ItemMachines
            && !HologramBlockTransforms.isPipe(target)) {
            int[] legal = java.util.stream.IntStream.range(0, 6)
                .filter(side -> HologramCellPolicy.virtualFrontAllowed(target, side))
                .toArray();
            int next = HologramCellRotation.nextFacing(cell.facing, legal);
            if (next < 0) throw new IOException("本格目标没有可轮换的合法朝向");
            cell.facing = next;
        } else {
            HologramBlockTransforms.turn(cell, vertical, java.util.Collections.emptyMap(), true);
        }
    }

    private Clipboard copySelection(int[] indices) throws IOException {
        Set<String> complete = completeStructures(document, indices);
        List<Cell> cells = new ArrayList<>(indices.length);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        for (int index : indices) {
            Cell original = document.cells.get(index);
            if (original.controller) throw new IOException("控制器始终受保护");
            Cell copy = copyCell(original);
            List<String> members = copy.memberships();
            members.retainAll(complete);
            memberships(copy, members);
            cells.add(copy);
            minX = Math.min(minX, copy.x);
            minY = Math.min(minY, copy.y);
            minZ = Math.min(minZ, copy.z);
        }
        List<Structure> structures = new ArrayList<>();
        for (Structure structure : document.structures)
            if (complete.contains(structure.id)) structures.add(copyStructure(structure));
        return new Clipboard(cells, structures, new Position(minX, minY, minZ));
    }

    /** Keep complete attachment metadata with its blocks, and never move half an attachment. */
    private static Set<String> completeStructures(Preset draft, int[] indices) throws IOException {
        Set<Integer> selected = new HashSet<>();
        for (int index : indices) selected.add(index);
        Map<String, Integer> chosen = new HashMap<>(), total = new HashMap<>();
        for (int index = 0; index < draft.cells.size(); index++) {
            for (String member : draft.cells.get(index)
                .memberships()) {
                total.merge(member, 1, Integer::sum);
                if (selected.contains(index)) chosen.merge(member, 1, Integer::sum);
            }
        }
        Set<String> complete = new HashSet<>();
        for (Structure structure : draft.structures) {
            int count = chosen.getOrDefault(structure.id, 0);
            if (count == 0) continue;
            if (count == total.get(structure.id)) complete.add(structure.id);
            else if (!text(structure.textBoxNbt).isEmpty()) throw new IOException("文字或图片须整体选择后编辑");
        }
        return complete;
    }

    private void appendCopies(Preset draft, Clipboard clipboard, long dx, long dy, long dz, boolean overlay)
        throws IOException {
        Map<Position, Cell> cells = positions(draft.cells);
        Map<String, String> replacements = new HashMap<>();
        for (Structure original : clipboard.structures()) {
            Structure copy = copyStructure(original);
            if (!text(original.textBoxNbt).isEmpty())
                copy.id = (original.id.startsWith(HologramPictureBox.PREFIX) ? HologramPictureBox.PREFIX
                    : HologramTextBox.PREFIX) + UUID.randomUUID();
            else copy.id = UUID.randomUUID()
                .toString();
            Position.of(copy)
                .translate(dx, dy, dz)
                .write(copy);
            replacements.put(original.id, copy.id);
            draft.structures.add(copy);
        }
        for (Cell original : clipboard.cells()) {
            Cell copy = copyCell(original);
            Position target = Position.of(copy)
                .translate(dx, dy, dz);
            Cell previous = cells.get(target);
            if (previous != null && (!overlay || previous.controller
                || !previous.memberships()
                    .isEmpty()))
                throw new IOException("目标与其他蓝图格重叠，坐标未修改");
            target.write(copy);
            List<String> members = copy.memberships();
            members.replaceAll(replacements::get);
            memberships(copy, members);
            cells.put(target, copy);
        }
        draft.cells = new ArrayList<>(cells.values());
    }

    private void move(Preset draft, int[] indices, long dx, long dy, long dz) throws IOException {
        Set<String> complete = completeStructures(draft, indices);
        Map<Position, Cell> cells = positions(draft.cells);
        Map<Position, Position> translated = new HashMap<>();
        List<Cell> moved = new ArrayList<>(indices.length);
        for (int index : indices) {
            Cell original = draft.cells.get(index);
            if (original.controller) throw new IOException("控制器始终受保护");
            Position initial = Position.of(original);
            Cell target = copyCell(original);
            List<String> remaining = original.memberships(), moving = original.memberships();
            remaining.removeAll(complete);
            moving.retainAll(complete);
            Position position = initial.translate(dx, dy, dz);
            position.write(target);
            memberships(target, moving);
            moved.add(target);
            if (remaining.isEmpty()) {
                cells.remove(initial);
                translated.put(initial, position);
            } else {
                Cell retained = copyCell(original);
                memberships(retained, remaining);
                cells.put(initial, retained);
            }
        }
        for (Cell target : moved)
            if (cells.putIfAbsent(Position.of(target), target) != null) throw new IOException("目标与其他蓝图格重叠，坐标未修改");
        for (Structure structure : draft.structures) if (complete.contains(structure.id)) Position.of(structure)
            .translate(dx, dy, dz)
            .write(structure);
        for (HologramAePolicy.Issue issue : draft.aeIssues) {
            if ("policy".equals(issue.path)) continue;
            Position position = translated.get(new Position(issue.x, issue.y, issue.z));
            if (position != null) {
                issue.x = position.x;
                issue.y = position.y;
                issue.z = position.z;
            }
        }
        draft.cells = new ArrayList<>(cells.values());
    }

    private void remove(Preset draft, int[] indices) throws IOException {
        if (!activeStructure.isEmpty()) throw new IOException("请在区域总览删除蓝图方块");
        Set<String> complete = completeStructures(draft, indices);
        Set<Position> remove = new HashSet<>();
        for (int index : indices) {
            Cell cell = draft.cells.get(index);
            if (cell.controller) throw new IOException("控制器始终受保护");
            remove.add(Position.of(cell));
        }
        draft.cells.removeIf(cell -> remove.contains(Position.of(cell)));
        draft.structures.removeIf(structure -> complete.contains(structure.id));
        for (int i = 0; i < draft.palette.size(); i++) {
            Cell cell = draft.palette.get(i);
            List<String> members = cell.memberships();
            if (!members.removeAll(complete)) continue;
            Cell copy = copyCell(cell);
            memberships(copy, members);
            draft.palette.set(i, copy);
        }
        draft.aeIssues.removeIf(
            issue -> !"policy".equals(issue.path) && remove.contains(new Position(issue.x, issue.y, issue.z)));
    }

    private static Map<Position, Cell> positions(List<Cell> rows) {
        Map<Position, Cell> result = new LinkedHashMap<>();
        for (Cell cell : rows) result.put(Position.of(cell), cell);
        return result;
    }

    private static void memberships(Cell cell, List<String> members) {
        cell.structureIds = new ArrayList<>(members);
        cell.structureId = members.isEmpty() ? null : members.get(0);
    }

    private void updateHint(Preset draft, int[] indices, String prompt) throws IOException {
        HintArea hint = new HintArea();
        Cell first = draft.cells.get(indices[0]);
        hint.x1 = hint.x2 = first.x;
        hint.y1 = hint.y2 = first.y;
        hint.z1 = hint.z2 = first.z;
        for (int index : indices) {
            Cell cell = draft.cells.get(index);
            hint.x1 = Math.min(hint.x1, cell.x);
            hint.y1 = Math.min(hint.y1, cell.y);
            hint.z1 = Math.min(hint.z1, cell.z);
            hint.x2 = Math.max(hint.x2, cell.x);
            hint.y2 = Math.max(hint.y2, cell.y);
            hint.z2 = Math.max(hint.z2, cell.z);
        }
        hint.prompt = prompt;
        for (HintArea previous : draft.hintAreas) if (previous.x1 == hint.x1 && previous.y1 == hint.y1
            && previous.z1 == hint.z1
            && previous.x2 == hint.x2
            && previous.y2 == hint.y2
            && previous.z2 == hint.z2) codec.copied(previous, hint);
        HologramBlueprintData.updateHintArea(draft.hintAreas, hint);
    }

    public NBTTagCompound snapshotMetadata() {
        NBTTagCompound state = new NBTTagCompound();
        state.setString("session", session);
        state.setString("generation", session);
        state.setLong("planRevision", revision);
        state.setBoolean("localBlueprintDocument", true);
        state.setBoolean("virtual", true);
        state.setBoolean("target", true);
        state.setBoolean("supported", true);
        state.setBoolean("blueprintEditing", true);
        state.setBoolean("blueprintPreview", true);
        state.setBoolean("blueprintSaved", !fileId.isEmpty());
        state.setBoolean("blueprintDirty", dirty());
        state.setBoolean("blueprintDirectSave", true);
        state.setBoolean("blueprintRetainNbt", document.retainNbt);
        state.setBoolean("blueprintRetainCovers", document.retainCovers);
        state.setBoolean("blueprintNbtAll", document.retainedNbtKeys == null);
        state.setTag("blueprintNbtKeys", HologramSessionPolicy.nbtKeys(document.retainedNbtKeys));
        state.setBoolean("editCanUndo", !undo.isEmpty());
        state.setBoolean("editCanRedo", !redo.isEmpty());
        state.setBoolean("editCanPaste", clipboard != null);
        state.setString("editorWorkspace", "blueprint");
        state.setString("workspaceTab", "blueprint");
        state.setString("blueprintSource", fileId.isEmpty() ? "imported" : "saved");
        state.setString("title", "全息构造器");
        state.setString("status", status);
        if (textPlacementAck != null) state.setTag("textPlacementAck", textPlacementAck.copy());
        state.setBoolean("blueprintProcessingRequired", processingPolicy != null);
        state.setBoolean("blueprintProcessingComplete", processingReviewed);
        if (processingPolicy != null) state.setTag("processingPolicy", processingPolicy.write());
        if (processingView != null) state.setTag("processing", processingView.copy());
        else state.setTag("processing", new NBTTagCompound());
        if (processingPresets != null) state.setTag("processingPresets", processingPresets.copy());
        writeSelectedNbt(state);
        state.setString("openPurpose", "blueprintEdit");
        state.setString("virtualPresetId", text(document.id));
        state.setString("virtualPresetName", text(document.name));
        state.setString("libraryLocalId", fileId);
        state.setString("selectedPresetId", fileId);
        state.setString("blueprintAuthorName", text(document.authorName));
        state.setString("blueprintAuthorUuid", text(document.authorUuid));
        state.setString("blueprintPrompt", text(document.prompt));
        // Preserve the legacy projection for opaque policies; do not interpret them or expand Save eligibility.
        var identity = codec.saveIdentity(document);
        state.setInteger(
            "deidentificationLevel",
            identity == null ? HologramValues.clamp(document.deidentificationLevel, 0, 6) : identity.level());
        if (identity != null) {
            state.setInteger("blueprintDeidentificationLevel", identity.level());
            state.setInteger("deidentificationMinimumLevel", identity.level());
            state.setBoolean("deidentificationChosen", identity.chosen());
        }
        state.setString("activeStructure", activeStructure);
        writeStructureSettings(state);
        state.setInteger("selected", selected);
        state.setIntArray("selectedIndices", selection);
        state.setInteger("scope", scope);
        state.setInteger("mode", mode);
        state.setInteger("layer", layer);
        return state;
    }

    private void writeStructureSettings(NBTTagCompound state) {
        Structure source = HologramBlueprintData.blueprintSource(document, activeStructure);
        NBTTagCompound settings = source == null ? new NBTTagCompound()
            : HologramLocalBlueprintStructure.settings(source);
        state.setInteger("main", Math.max(1, settings.getInteger("main")));
        state.setBoolean("mainExplicit", settings.getBoolean("mainExplicit"));
        state.setBoolean("noHatches", settings.getBoolean("noHatches"));
        state.setTag(
            "channels",
            settings.getCompoundTag("channels")
                .copy());
        NBTTagCompound options = structureOptions.get(new StructureSettings(activeStructure, settings));
        state.setTag(
            "capabilities",
            options == null ? new NBTTagList()
                : options.getTagList("capabilities", 10)
                    .copy());
        state.setTag(
            "structureDescription",
            options == null ? new NBTTagList()
                : options.getTagList("structureDescription", 8)
                    .copy());
    }

    private void writeSelectedNbt(NBTTagCompound state) {
        NBTTagList rows = new NBTTagList(), entries = new NBTTagList();
        if (selected >= 0 && selected < viewCellCount()) try {
            Cell cell = viewCell(selected);
            entries = HologramBlueprintProcessing.nbtEntries(cell);
            HologramBlueprintProcessing.CellNbtOverride selectedPolicy = null;
            if (processingPolicy != null)
                for (HologramBlueprintProcessing.CellNbtOverride override : processingPolicy.cellNbtOverrides)
                    if (cell.x == override.x && cell.y == override.y && cell.z == override.z) selectedPolicy = override;
            boolean all = selectedPolicy == null || selectedPolicy.all;
            for (int i = 0; i < entries.tagCount(); i++) {
                NBTTagCompound entry = entries.getCompoundTagAt(i);
                String key = entry.getString("key");
                entry.setString("label", key);
                entry.setBoolean("selected", all || selectedPolicy.keys.contains(key));
                entry.setBoolean("blueprintSelected", entry.getBoolean("selected"));
            }
            NBTTagCompound row = new NBTTagCompound();
            row.setInteger("index", selected);
            row.setBoolean("all", all);
            row.setTag("entries", entries);
            row.setTag(
                "ignoredPaths",
                selectedPolicy == null ? new NBTTagList()
                    : HologramBlueprintProcessing.strings(selectedPolicy.ignoredPaths));
            rows.appendTag(row);
        } catch (IOException | RuntimeException unavailable) {
            entries = new NBTTagList();
        }
        state.setTag("nbtEntries", entries);
        state.setTag("cellNbtEntries", rows);
    }

    /** Identity and cardinality of a complete, accepted local editor presentation. No cell walk. */
    public boolean ownsPresentation(String session, long revision, int cells) {
        return this.session.equals(session) && this.revision == revision && cells > 0 && cells == viewCellCount();
    }

    /** New screen metadata from the current draft, without row projection or retired command receipts. */
    public NBTTagCompound resumePresentationMetadata(java.util.function.BooleanSupplier current) throws IOException {
        if (!current.getAsBoolean()) throw new IOException("蓝图恢复已取消");
        NBTTagCompound metadata = editorMetadata(current);
        metadata.removeTag("textPlacementAck");
        return metadata;
    }

    @Desugar
    private record EditorDescriptor(NBTTagCompound scalarFields, String tilePayload, String itemPayload) {

        static EditorDescriptor of(NBTTagCompound descriptor, Cell source) {
            boolean tile = descriptor.hasKey("previewTile", 10), item = descriptor.hasKey("previewItemTag", 10);
            if (!tile && !item) return new EditorDescriptor(descriptor, null, null);
            NBTTagCompound scalar = new NBTTagCompound();
            for (Object name : descriptor.func_150296_c()) {
                String key = (String) name;
                if (!"previewTile".equals(key) && !"previewItemTag".equals(key))
                    scalar.setTag(key, descriptor.getTag(key));
            }
            // Opaque native tags use exact original encoded equality, not NBT's Float/Double comparison.
            // Distinct input payloads remain distinct even if their display bytes happen to be equivalent.
            return new EditorDescriptor(scalar, tile ? source.tileNbt : null, item ? source.itemNbt : null);
        }
    }

    /** The ordinary draft uses the same descriptor/coordinate viewport as file-backed compact opens. */
    public DetachedEditorRows detachedEditorRows(java.util.function.BooleanSupplier current) throws IOException {
        requireMaterialized();
        if (!current.getAsBoolean()) throw new IOException("蓝图预览已取消");
        long expectedRevision = revision;
        List<Cell> source = document.cells;
        HologramBlueprintLimits.requireLocal(source.size());
        int[] coordinates = new int[Math.multiplyExact(source.size(), 4)];
        int[] bounds = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
            Integer.MIN_VALUE };
        Map<EditorDescriptor, Integer> ids = new HashMap<>();
        List<NBTTagCompound> materials = new ArrayList<>();
        VisualPayloads payloads = new VisualPayloads();
        for (int i = 0; i < source.size(); i++) {
            if ((i & 255) == 0 && (!current.getAsBoolean() || revision != expectedRevision))
                throw new IOException("蓝图预览已取消");
            Cell cell = source.get(i);
            NBTTagCompound descriptor = cellRow(cell, payloads, false);
            EditorDescriptor key = EditorDescriptor.of(descriptor, cell);
            Integer id = ids.get(key);
            if (id == null) {
                id = materials.size();
                ids.put(key, id);
                materials.add(descriptor);
            }
            int at = i * 4;
            coordinates[at] = cell.x;
            coordinates[at + 1] = cell.y;
            coordinates[at + 2] = cell.z;
            coordinates[at + 3] = id;
            bounds[0] = Math.min(bounds[0], cell.x);
            bounds[1] = Math.min(bounds[1], cell.y);
            bounds[2] = Math.min(bounds[2], cell.z);
            bounds[3] = Math.max(bounds[3], cell.x);
            bounds[4] = Math.max(bounds[4], cell.y);
            bounds[5] = Math.max(bounds[5], cell.z);
        }
        NBTTagCompound state = editorMetadata(current);
        if (!current.getAsBoolean() || revision != expectedRevision) throw new IOException("蓝图预览已取消");
        return new DetachedEditorRows(state, coordinates, materials.toArray(new NBTTagCompound[0]), bounds);
    }

    /** Editable rows use precisely the portable document order, including disabled and missing-mod cells. */
    public NBTTagCompound snapshot() throws IOException {
        requireMaterialized();
        NBTTagCompound state = editorMetadata();
        NBTTagList cells = new NBTTagList();
        VisualPayloads payloads = new VisualPayloads();
        for (Cell cell : document.cells) cells.appendTag(cellRow(cell, payloads));
        state.setTag("cells", cells);
        return state;
    }

    private EditorRows editorRows(HologramLocalBlueprintCodec.CompactLayout layout,
        java.util.function.BooleanSupplier current) throws IOException {
        if (layout == null || layout.rows()
            .cells() == 0) return null;
        NBTTagCompound[] descriptors = new NBTTagCompound[layout.materials().length];
        VisualPayloads payloads = new VisualPayloads();
        long projectedBytes = 0;
        for (int material = 0; material < descriptors.length; material++) {
            if (!current.getAsBoolean()) throw new IOException("蓝图预览已取消");
            Cell descriptor = layout.materials()[material];
            if (descriptor == null) continue;
            long before = payloads.total;
            NBTTagCompound row = cellRow(descriptor, payloads);
            projectedBytes += (payloads.total - before) * layout.occurrences()[material];
            if (payloads.exceeded || projectedBytes > VisualPayloads.MAX_BYTES) return null;
            for (String key : new String[] { "x", "y", "z", "dx", "dy", "dz" }) row.removeTag(key);
            descriptors[material] = row;
        }
        return new EditorRows(editorMetadata(), layout.rows(), descriptors, layout.bounds());
    }

    private NBTTagCompound editorMetadata() throws IOException {
        return editorMetadata(() -> true);
    }

    private NBTTagCompound editorMetadata(java.util.function.BooleanSupplier current) throws IOException {
        NBTTagCompound state = snapshotMetadata();
        NBTTagList structures = new NBTTagList();
        for (Structure structure : document.structures) {
            NBTTagCompound row = new NBTTagCompound();
            row.setString("id", structure.id);
            row.setString("name", text(structure.name));
            row.setString("sourceClass", text(structure.sourceClass));
            row.setString("sourceMod", text(structure.sourceMod));
            row.setInteger("x", structure.x);
            row.setInteger("y", structure.y);
            row.setInteger("z", structure.z);
            row.setBoolean("enabled", structure.enabled);
            HologramAttachmentSnapshot.capture(row, structure);
            structures.appendTag(row);
        }
        state.setTag("structures", structures);
        NBTTagList hints = new NBTTagList();
        for (HintArea hint : document.hintAreas) {
            NBTTagCompound row = new NBTTagCompound();
            row.setIntArray("bounds", new int[] { hint.x1, hint.y1, hint.z1, hint.x2, hint.y2, hint.z2 });
            row.setString("prompt", text(hint.prompt));
            hints.appendTag(row);
        }
        state.setTag("hintAreas", hints);
        HologramAttachmentSnapshot.expand(state, current);
        return state;
    }

    private static NBTTagCompound cellRow(Cell cell, VisualPayloads payloads) {
        return cellRow(cell, payloads, true);
    }

    private static NBTTagCompound cellRow(Cell cell, VisualPayloads payloads, boolean coordinates) {
        NBTTagCompound row = new NBTTagCompound();
        if (coordinates) {
            row.setInteger("x", cell.x);
            row.setInteger("y", cell.y);
            row.setInteger("z", cell.z);
            row.setInteger("dx", cell.x);
            row.setInteger("dy", cell.y);
            row.setInteger("dz", cell.z);
        }
        ItemStack stack = cell.stack();
        Block block = text(cell.block).isEmpty() && stack != null ? Block.getBlockFromItem(stack.getItem())
            : cell.block();
        boolean missing = cell.missing() || block == null || block == Blocks.air && !"minecraft:air".equals(cell.block);
        row.setBoolean("missingBlock", missing);
        row.setString(
            "wantId",
            missing ? "minecraft:air" : String.valueOf(Block.blockRegistry.getNameForObject(block)));
        boolean legacyItem = text(cell.block).isEmpty() && stack != null
            && !missing
            && !(stack.getItem() instanceof gregtech.common.blocks.ItemMachines);
        row.setInteger(
            "wantMeta",
            legacyItem ? stack.getItem()
                .getMetadata(cell.damage) : cell.metadata);
        row.setInteger("targetFacing", cell.facing);
        row.setString("previewItem", missing ? "" : text(cell.item));
        row.setInteger("previewDamage", cell.damage);
        if (!missing) {
            NBTTagCompound tile = payloads.value(cell.tileNbt, coordinates),
                item = payloads.value(cell.itemNbt, coordinates);
            if (tile != null) row.setTag("previewTile", tile);
            if (item != null) row.setTag("previewItemTag", item);
        }
        row.setString("status", "preview");
        row.setString("operation", "EDIT");
        row.setString("exactIdentity", text(cell.item).isEmpty() ? text(cell.block) : cell.item);
        row.setString("prompt", text(cell.prompt));
        row.setString("role", text(cell.structureRole));
        row.setString("family", text(cell.structureFamily));
        row.setString("candidateBoundary", text(cell.structureHint));
        row.setBoolean(
            "candidateComplete",
            cell.structureCandidatesComplete != null ? cell.structureCandidatesComplete : cell.allowedTargets != null);
        if (cell.structureHatchFacings != null) row.setIntArray("hatchFacings", cell.structureHatchFacings);
        if (cell.allowedTargets != null) {
            NBTTagList candidates = new NBTTagList(), roles = new NBTTagList();
            int[] tiers = new int[cell.allowedTargets.size()];
            int index = 0;
            for (HologramPresets.Target target : cell.allowedTargets) {
                if (target == null) continue;
                ItemStack candidate = target.stack();
                if (candidate == null) continue;
                int tier = HologramCellPolicy.hatchTier(candidate);
                tiers[index] = tier == Integer.MAX_VALUE ? -1 : tier;
                if (HologramValues.same(stack, candidate)) row.setInteger("chosenChoice", index);
                candidates.appendTag(candidate.writeToNBT(new NBTTagCompound()));
                roles.appendTag(new net.minecraft.nbt.NBTTagString(tier == Integer.MAX_VALUE ? "外壳/方块" : "仓室"));
                index++;
            }
            row.setTag("candidates", candidates);
            row.setTag("candidateRoles", roles);
            row.setIntArray("candidateTiers", Arrays.copyOf(tiers, index));
        }
        row.setString("structureId", text(cell.structureId));
        row.setTag("structureIds", HologramSessionPolicy.nbtKeys(cell.memberships()));
        row.setBoolean("anchor", cell.controller);
        row.setBoolean("inScope", true);
        row.setBoolean("excluded", cell.excluded);
        return row;
    }

    private static final class VisualPayloads {

        private static final long MAX_BYTES = 64L * 1024 * 1024;
        private final Map<String, Integer> sizes = new HashMap<>();
        private final Map<String, NBTTagCompound> decoded = new HashMap<>();
        private long total;
        private boolean exceeded;

        NBTTagCompound value(String source, boolean detachedCopy) {
            if (text(source).isEmpty()) return null;
            if (total >= MAX_BYTES) {
                exceeded = true;
                return null;
            }
            try {
                Integer size = sizes.get(source);
                if (size == null) {
                    size = HologramBlueprintNbt.expandedBytes(source);
                    sizes.put(source, size);
                }
                if (size < 0) return null;
                if (total > MAX_BYTES - size) {
                    exceeded = true;
                    return null;
                }
                NBTTagCompound payload = decoded.get(source);
                if (payload == null) {
                    payload = HologramBlueprintNbt.decodeData(source);
                    decoded.put(source, payload);
                }
                total += size;
                // Dictionary descriptors are immutable until CompactCells.copyMaterial prepares a native template.
                return detachedCopy ? (NBTTagCompound) payload.copy() : payload;
            } catch (IOException unavailable) {
                sizes.put(source, -1);
                return null;
            }
        }
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
