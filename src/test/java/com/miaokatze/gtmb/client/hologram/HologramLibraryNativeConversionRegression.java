// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.client.hologram;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.NBTTagCompound;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListenableFutureTask;
import com.miaokatze.gtmb.hologram.HologramBlueprintArtifact;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintCodecRegression;
import com.miaokatze.gtmb.hologram.gtnb.NativeBlueprintPairStore;

/** One standalone, no-world regression: actual UI routing, background client library, actual file publication and cold open. */
public final class HologramLibraryNativeConversionRegression {
    private static final String A=id(1), B=id(2), C=id(3), N=id(4), BAD=id(5);
    private static String id(int n) { return "local_"+new UUID(33,n); }

    @SuppressWarnings({"unchecked","rawtypes"})
    private static final class Client extends Minecraft {
        Queue<ListenableFutureTask<?>> tasks;
        private Client(){super(null,1,1,false,false,null,null,null,null,"fixture",null,"fixture");throw new AssertionError();}
        @Override public ListenableFuture func_152344_a(Runnable r){
            ListenableFutureTask<?> f=ListenableFutureTask.create(r,null); tasks.add(f); return f;
        }
        void until(BooleanSupplier ready) throws Exception {
            long end = System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while(true){
                ListenableFutureTask<?> f;
                while((f=tasks.poll())!=null){f.run();f.get();}
                if(ready.getAsBoolean())return;
                if(System.nanoTime()>end)throw new AssertionError("Timed out waiting for conversion/library refresh");
                Thread.sleep(5);
            }
        }
    }
    private static Field field(Class<?> type,String name)throws Exception{
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private static void check(boolean b,String detail){if(!b)throw new AssertionError(detail);}
    private static Path put(HologramClientLibrary store,String id,String block)throws Exception{
        Path source=store.directory().resolve(id+".json");
        byte[] json=("{\"schema\":2,\"blueprint\":{\"id\":\""+id+"\",\"name\":\"same title\",\"authorName\":\"Fixture\",\"authorUuid\":\"00000000-0000-0000-0000-000000000001\",\"cells\":[{\"x\":-2,\"y\":1,\"z\":3,\"block\":\""+block+"\",\"metadata\":0,\"item\":\""+block+"\",\"damage\":0,\"count\":1}]}}").getBytes(StandardCharsets.UTF_8);
        HologramBlueprintArtifact.writePair(source,json,NativeBlueprintCodecRegression.png());
        return source;
    }
    private static NBTTagCompound row(HologramLibraryClient lib,String id) {
        var rows=lib.rows(false);
        for(int i=0;i<rows.tagCount();i++)if(id.equals(rows.getCompoundTagAt(i).getString("id")))return rows.getCompoundTagAt(i);
        throw new AssertionError("missing row "+id);
    }
    private static void idle(Client mc,HologramLibraryClient lib)throws Exception {
        Field loading=field(HologramLibraryClient.class,"loading");
        mc.until(()->{try{return !lib.busy()&&!loading.getBoolean(lib);}catch(Exception e){throw new AssertionError(e);}});
    }

    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("gtmb-library-native-ui-");
        var unsafe=(sun.misc.Unsafe)field(sun.misc.Unsafe.class,"theUnsafe").get(null);
        Field singleton=Arrays.stream(Minecraft.class.getDeclaredFields()).filter(f->Modifier.isStatic(f.getModifiers())&&f.getType()==Minecraft.class).findFirst().get();
        singleton.setAccessible(true); Object previous=singleton.get(null);
        try {
            var mc=(Client)unsafe.allocateInstance(Client.class); mc.tasks=new ConcurrentLinkedQueue<>(); singleton.set(null,mc);
            var owner=HologramLibraryClient.INSTANCE; var store=new HologramClientLibrary(root); Files.createDirectories(store.directory());
            field(HologramLibraryClient.class,"store").set(owner,store);
            var screen=(HologramScreen)unsafe.allocateInstance(HologramScreen.class);
            var ui=new HologramUiModules(screen);screen.ui=ui;
            field(HologramUiSession.class,"state").set(ui.session,new HologramState(new NBTTagCompound()));
            var txt=(HologramMuiTextField)unsafe.allocateInstance(HologramMuiTextField.class);
            field(HologramMuiTextField.class,"value").set(txt,new com.cleanroommc.modularui.value.StringValue(B));
            field(HologramUiLibrary.class,"presetNameField").set(ui.library,txt);
            Path original=put(store,A,"minecraft:stone"), other=put(store,B,"minecraft:dirt");
            Path portable=store.directory().resolve("Unindexed Same Title.json");
            Files.move(HologramBlueprintArtifact.previewPath(original), HologramBlueprintArtifact.previewPath(portable));
            Files.move(original,portable); original=portable;
            put(store,C,"minecraft:stone");put(store,N,"minecraft:stone");
            store.commitSelectedNativeUpgrade(N,()->true);
            var nativeBefore=NativeBlueprintPairStore.receipt(store.directory(),N);
            // Paired and visible, but the sidecar no longer belongs to BAD. The failure must retain its original.
            Path invalid=put(store,BAD,"minecraft:stone");
            Files.write(HologramBlueprintArtifact.previewPath(invalid),new byte[]{1,2,3});
            byte[] old=Files.readAllBytes(original),png=Files.readAllBytes(HologramBlueprintArtifact.previewPath(original)),otherOld=Files.readAllBytes(other);
            Path excluded=store.directory().resolve("public_00000000-0000-0000-0000-000000000001.json"), unpaired=store.directory().resolve("unpaired.json");
            Files.copy(original,excluded); Files.copy(HologramBlueprintArtifact.previewPath(original),HologramBlueprintArtifact.previewPath(excluded)); Files.copy(original,unpaired);
            owner.refreshLocal();idle(mc,owner);
            var adminHeader = new NBTTagCompound(); adminHeader.setString("id", "admin");
            check(!ui.library.conversionRequired(adminHeader), "an administrator control is not a blueprint card");
            owner.select(false,B);
            ui.library.nativeBindRow(0,row(owner,A));
            ui.router.activate(14000);
            check(ui.library.conversionPromptOpen()&&ui.library.conversionRequired(row(owner,A))&&!ui.library.conversionRequired(row(owner,N)),"legacy intercepted; native rows unchanged");
            check(owner.selected(false).equals(B)&&!owner.busy()&&!ui.library.openingLocalSource()&&!ui.library.browsePreviewActive(),"a gray-row click must not select, decode, render or start a task");
            ui.library.cancelConversionPrompt();
            check(!ui.library.conversionPromptOpen()&&NativeBlueprintPairStore.receipt(store.directory(),A)==null,"cancel writes nothing");
            ui.router.activate(14000); ui.library.confirmConversionPrompt(false);idle(mc,owner);
            check(row(owner,A).getBoolean("nativeFile")&&NativeBlueprintPairStore.receipt(store.directory(),B)==null,"clicked A wins over name field/selection B; refreshed card is native");
            check(com.miaokatze.gtmb.hologram.HologramBlueprintFiles.indexedPath(store.directory(),B)==null,
                "single conversion did not adopt or alter the unrelated portable B alias");
            owner.select(false,A);ui.library.nativeConvertSelected();check(!owner.busy(),"native re-upgrade is a no-op");
            try(var d=new HologramClientLibrary(root).readPrivateEditorSource(A).openNative()){
                check(d.cells()==1&&d.elementAt(-2,1,3)>0,"cold editor opens upgraded selected geometry");
            }
            check(Arrays.equals(old,Files.readAllBytes(original))&&Arrays.equals(png,Files.readAllBytes(HologramBlueprintArtifact.previewPath(original))),"source pair byte-identical");
            ui.library.nativeBindRow(0,row(owner,B));ui.router.activate(14000);ui.library.confirmConversionPrompt(true);idle(mc,owner);
            check(row(owner,B).getBoolean("nativeFile")&&row(owner,C).getBoolean("nativeFile")&&!row(owner,BAD).getBoolean("nativeFile"),"all converts B+C, retains failed source");
            check(owner.upgradeDetails().size()==1&&owner.upgradeDetails().get(0).contains(BAD),"failure visibly identifies file");
            check(NativeBlueprintPairStore.sameReceipt(nativeBefore,NativeBlueprintPairStore.receipt(store.directory(),N))&&Arrays.equals(otherOld,Files.readAllBytes(other)),"already native skipped, original B retained");
            Path next=put(store,id(6),"minecraft:stone");owner.refreshLocal();idle(mc,owner);
            ui.library.activateControl(226);idle(mc,owner);
            check(row(owner,id(6)).getBoolean("nativeFile")&&Files.isRegularFile(next),"library-wide control uses same whole-library converter");
            check(Files.exists(excluded)&&Arrays.equals(old,Files.readAllBytes(excluded))&&Arrays.equals(old,Files.readAllBytes(unpaired))
                &&Arrays.equals(new byte[]{1,2,3},Files.readAllBytes(HologramBlueprintArtifact.previewPath(invalid))),"public, unpaired, failed original content not rewritten");
            System.out.println("PASS native library settings: gray row/no open, cancel, clicked-ID vs typed-ID, native no-op, cold open + unchanged originals, all legacy + partial failure, whole-library action.");
        }finally{
            singleton.set(null,previous);
            try(var files=Files.walk(root)){for(Path f:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(f);}
        }
    }
}
