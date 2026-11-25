// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram.gtnb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.google.gson.JsonObject;

/** Exercises the shipped standalone converter/reader and durable publication, without Forge or a game. */
public final class NativeBlueprintCodecRegression {
    private NativeBlueprintCodecRegression() {}
    public static void main(String[] args)throws Exception {run();System.out.println("native-blueprint codec/CLI/pair regression PASS");}
    public static void run()throws Exception {
        json();Path root=Files.createTempDirectory("gtmb-native-contract-");
        try {
            reject(()->NativeBlueprintNbt.validate(new byte[]{10,0,2,(byte)0xc1,(byte)0x81,0}),"overlong MUTF8");
            reject(()->NativeBlueprintNbt.validate(new byte[]{10,0,3,(byte)0xe0,(byte)0x80,(byte)0x81,0}),"overlong MUTF8 triple");
            NativeBlueprintNbt.validate(new byte[]{10,0,2,(byte)0xc0,(byte)0x80,0});
            byte[] golden;try(InputStream in=NativeBlueprintCodecRegression.class.getResourceAsStream("/native-blueprint-v1/small.gtnb")){require(in!=null,"golden resource");ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1)b.write(chunk,0,n);golden=b.toByteArray();}
            Path gold=root.resolve("gold.gtnb");Files.write(gold,golden);try(NativeBlueprintDefinition d=NativeBlueprintDefinition.open(gold)){require(d.cells()==4&&d.elementAt(-2,-1,3)==1&&d.elementAt(0,-1,3)==0&&d.elementAt(1,-1,3)==2,"gold geometry");require(d.revision().equals("1c138bd9c8d0c7e0ce909691dcdb9c60b17b5bca7c19dcf57461b3c8e6f2037b"),"independent physical golden");require(NativeBlueprintIdentity.content(d).equals("gtmb-native-content-1:cd4575f85a84f0e033fef11c9689e549fc6beb56012bd5ede1bbf12103e9120f"),"independent semantic golden");require(d.payload(1).length==112&&!d.allowsGeometryMutation(),"NBT/unknown guard");}
            byte[] bad=golden.clone();bad[16]=1;Path malformed=root.resolve("malformed.gtnb");Files.write(malformed,bad);reject(()->NativeBlueprintDefinition.open(malformed),"required feature");
            bad=Arrays.copyOf(golden,golden.length-1);Files.write(malformed,bad);reject(()->NativeBlueprintDefinition.open(malformed),"truncated");
            reject(()->NativeBlueprintMigration.convert(bytes("{\"cells\":[{\"x\":true}]}")),"bool coordinate");
            reject(()->NativeBlueprintMigration.convert(bytes("{\"cells\":[{\"x\":1.0}]}")),"float coordinate");
            reject(()->NativeBlueprintMigration.convert(bytes("{\"cells\":[{\"x\":1},{\"x\":1}]}")),"duplicate coordinates");
            canonicalFailures(root);
            knownTypeGuards(root);
            String source="{\"schema\":2,\"materials\":{\"custom\":1},\"nbt\":null,\"blueprint\":{\"id\":\"gold\",\"name\":\"sample\",\"cells\":[{\"x\":-0,\"z\":5,\"block\":\"minecraft:stone\"}]}}";
            byte[] old=bytes(source);NativeBlueprintMigration.Result result=NativeBlueprintMigration.convert(old);Path converted=root.resolve("converted.gtnb");Files.write(converted,result.bytes);
            try(NativeBlueprintDefinition d=NativeBlueprintDefinition.open(converted)){require(d.elementAt(0,0,5)==1,"absent axes effective zero");JsonObject syntax=d.extras(0,0,5,1).getAsJsonObject("coordinateSource");require(syntax.get("presentMask").getAsInt()==5&&syntax.get("negativeZeroMask").getAsInt()==1,"sparse axis bitmasks");JsonObject envelope=d.sectionWrapper(9).getAsJsonObject("fields").getAsJsonObject("envelope");require(envelope.has("materials")&&envelope.has("nbt")&&envelope.get("nbt").isJsonNull()&&!d.allowsGeometryMutation(),"expanded envelope reserved names preserved");}
            byte[] image=png();NativeBlueprintPng.validatePreview(image);byte[] bound=NativeBlueprintPng.bind(image,NativeBlueprintBytes.hash(result.bytes));NativeBlueprintPng.verify(bound,NativeBlueprintBytes.hash(result.bytes));require(Arrays.equals(bound,NativeBlueprintPng.bind(bound,NativeBlueprintBytes.hash(result.bytes))),"PNG idempotent metadata-only bind");
            for(String stage:new String[]{"source-written","pair-staged","journal-written","source-moved","image-moved","before-catalog"}){Path dir=Files.createDirectory(root.resolve("fault-"+stage));Path original=dir.resolve("old.json");Files.write(original,old);reject(()->NativeBlueprintPairStore.publish(dir,"local_gold",result.bytes,image,original,old,()->true,s->{if(s.equals(stage))throw new IOException("injected "+stage);}),stage);require(NativeBlueprintPairStore.active(dir).isEmpty()&&Arrays.equals(old,Files.readAllBytes(original)),"failed publication retains old "+stage);NativeBlueprintPairStore.recover(dir);require(NativeBlueprintPairStore.active(dir).isEmpty(),"recovery does not guess CAS "+stage);}
            Path committed=Files.createDirectory(root.resolve("committed"));Path original=committed.resolve("old.json");Files.write(original,old);NativeBlueprintPairStore.Receipt receipt=NativeBlueprintPairStore.publish(committed,"local_gold",result.bytes,image,original,old,()->true,s->{if(s.equals("after-catalog"))throw new IOException("cleanup interrupted");});require(receipt!=null&&NativeBlueprintPairStore.inactive(committed,original),"CAS committed despite cleanup interruption");NativeBlueprintPairStore.recover(committed);try(NativeBlueprintDefinition d=NativeBlueprintPairStore.open(committed,"local_gold")){require(d.cells()==1,"recovery committed pair");}
            Path cliInput=root.resolve("cli.json");Files.write(cliInput,old);Path cliImage=root.resolve("cli.png");Files.write(cliImage,image);Path cliOut=Files.createDirectory(root.resolve("cli-out"));NativeBlueprintCli.run(new String[]{"convert",cliInput.toString(),cliOut.toString(),"local_gold",cliImage.toString()});NativeBlueprintPairStore.Receipt cliReceipt=NativeBlueprintPairStore.receipt(cliOut,"local_gold");require(Arrays.equals(result.bytes,Files.readAllBytes(cliReceipt.source)),"CLI calls identical core");
            Path sourceOnly=root.resolve("source-only.gtnb");NativeBlueprintCli.run(new String[]{"source-only",cliInput.toString(),sourceOnly.toString()});require(Arrays.equals(result.bytes,Files.readAllBytes(sourceOnly)),"source-only remains separate staging");
            // Sparse width > signed-int range: two points, no dense volume allocation.
            byte[] sparse=NativeBlueprintMigration.convert(bytes("{\"cells\":[{\"x\":-2147483648},{\"x\":0}]}" )).bytes;Path sparsePath=root.resolve("sparse.gtnb");Files.write(sparsePath,sparse);try(NativeBlueprintDefinition d=NativeBlueprintDefinition.open(sparsePath)){require(d.cells()==2&&d.frame().sizeX==2147483649L,"sparse wide frame");}
        } finally {try(java.util.stream.Stream<Path> paths=Files.walk(root)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException e){throw new java.io.UncheckedIOException(e);}});}}
    }
    private static void json()throws Exception {byte[] raw=bytes("{\"z\":-0,\"x\":9007199254740993,\"a\":1.00,\"u\":\"\\ud800\"}");String j=new String(NativeBlueprintJson.encode(NativeBlueprintJson.parse(raw)),StandardCharsets.US_ASCII);require(j.contains("9007199254740993")&&j.contains("1.00")&&j.contains("-0")&&j.contains("\\ud800"),"exact J1 numbers and surrogate");reject(()->NativeBlueprintJson.parse(bytes("{\"a\":1,\"a\":2}")),"duplicate JSON key");}
    private static void knownTypeGuards(Path root)throws Exception {
        byte[] odd=bytes("{\"cells\":[{\"x\":2,\"block\":\"minecraft:stone\",\"count\":\"7\",\"tileNbt\":{\"old\":true}},{\"x\":1}],\"controllerFacing\":\"2\"}");
        var moved=NativeBlueprintMigration.convert(odd);
        Path path=root.resolve("known-weird.gtnb");Files.write(path,moved.bytes);
        java.util.TreeMap<Integer,byte[]> undecorated=new java.util.TreeMap<>();
        try(var d=NativeBlueprintDefinition.open(path)){
            require(!d.allowsGeometryMutation()&&d.sectionBytes(10)>0,"weird known types preserve source order and are not interpreted");
            int e=d.elementAt(2,0,0);
            require(d.element(e).getAsJsonPrimitive("count").isString()&&"7".equals(d.element(e).get("count").getAsString()),
                "known count string preserved exactly, not silently converted into a numeric material");
            require(d.extras(2,0,0,e).getAsJsonObject("fields").getAsJsonObject("tileNbt").get("old").getAsBoolean(),
                "object in known NBT field survives exactly");
            require(d.sectionWrapper(9).getAsJsonObject("fields").getAsJsonObject("document").getAsJsonPrimitive("controllerFacing").isString(),
                "known document field malformed value survives exactly");
            require(d.metadata().getAsJsonObject("migration").get("unknownSemantics").getAsBoolean(),"legacy intake sets guard");
            for(int t=1;t<10;t++)if(d.sectionBytes(t)>0)undecorated.put(t,d.rawSection(t));
            JsonObject meta=d.metadata();
            meta.remove("migration");undecorated.put(1,NativeBlueprintJson.encode(meta));
            reject(()->new NativeBlueprintEdits(d).annotate(new int[]{0},"must not edit",false),"unknown cell mutation");
        }
        // A hand-authored native file without any migration mark/order must apply the same gate.
        Files.write(path,NativeBlueprintMigration.pack(undecorated,2).bytes);
        try(var d=NativeBlueprintDefinition.open(path)){
            require(!d.allowsGeometryMutation()&&d.sectionBytes(10)==0,"native shape is checked independently of provenance");
        }
        ByteArrayOutputStream report=new ByteArrayOutputStream();
        java.io.PrintStream original=System.out;
        try{System.setOut(new java.io.PrintStream(report,true,"US-ASCII"));
            NativeBlueprintCli.run(new String[]{"inspect",path.toString()});}
        finally{System.setOut(original);}
        require(report.toString("US-ASCII").contains("\"geometryMutationSupported\":false"),"CLI reports native type guard");
    }
    private static void canonicalFailures(Path root)throws Exception {
        byte[] input=NativeBlueprintMigration.convert(bytes("{\"cells\":[{\"x\":0},{\"x\":1}]}")).bytes;
        Path file=root.resolve("canonical.gtnb");Files.write(file,input);
        java.util.TreeMap<Integer,byte[]> sections=new java.util.TreeMap<>();
        try(NativeBlueprintDefinition d=NativeBlueprintDefinition.open(file)){
            for(int t=1;t<=10;t++)if(d.sectionBytes(t)>0)sections.put(t,d.rawSection(t));
        }
        byte[] index=sections.get(3);
        java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(index).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(12,3);
        Files.write(file,NativeBlueprintMigration.pack(sections,2).bytes);
        reject(()->NativeBlueprintDefinition.open(file),"declared bounds not attained");
        b.putInt(12,2);b.putLong(56,2);b.putLong(80+16,2);
        b.putInt(112+24,6);b.putInt(112+28,2);
        sections.put(4,new byte[]{0,1,1,0,1,1});
        Files.write(file,NativeBlueprintMigration.pack(sections,2).bytes);
        reject(()->NativeBlueprintDefinition.open(file),"two adjacent identical records not one maximal run");
        // An explicitly selected collection member and a duplicate property within a streamed row.
        NativeBlueprintMigration.convert(bytes("{\"presets\":[{\"cells\":[]},{\"cells\":[{\"x\":-0}]}]}"),1,()->true);
        reject(()->NativeBlueprintMigration.convert(bytes("{\"cells\":[{\"x\":0,\"x\":1}]}")),"duplicate key in streamed cell");
    }
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static byte[] png()throws IOException{BufferedImage image=new BufferedImage(640,640,BufferedImage.TYPE_INT_ARGB);image.setRGB(10,10,0xffaaccdd);ByteArrayOutputStream out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();}
    private interface Action{void run()throws Exception;}
    private static void reject(Action a,String name)throws Exception {try{a.run();}catch(IOException expected){return;}throw new AssertionError("accepted "+name);}
    private static void require(boolean b,String s){if(!b)throw new AssertionError(s);}
}
