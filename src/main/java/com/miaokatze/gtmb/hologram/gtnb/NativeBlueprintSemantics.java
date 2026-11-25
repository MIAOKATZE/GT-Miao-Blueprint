// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.
package com.miaokatze.gtmb.hologram.gtnb;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/** Shared typed knowledge boundary. Unknown fields remain exact data, never interpreted defaults. */
final class NativeBlueprintSemantics {
    private NativeBlueprintSemantics(){}
    private static final Predicate<JsonElement> STRING=e->e.isJsonPrimitive()&&e.getAsJsonPrimitive().isString();
    private static final Predicate<JsonElement> BOOL=e->e.isJsonPrimitive()&&e.getAsJsonPrimitive().isBoolean();
    private static final Predicate<JsonElement> INT=e->integer(e,true),LONG=e->integer(e,false);
    private static final Predicate<JsonElement> LEGACY_NBT=STRING;
    private static final Predicate<JsonElement> NATIVE_NBT=e->STRING.test(e)&&e.getAsString().isEmpty();
    private static final class Schema {
        final Map<String,Predicate<JsonElement>> fields=new HashMap<>();
        Schema with(Predicate<JsonElement> type,String keys){
            for(String key:keys.split(" "))fields.put(key,type);
            return this;
        }
        Schema add(Schema other){fields.putAll(other.fields);return this;}
        boolean matches(JsonElement e){
            if(!e.isJsonObject())return false;
            for(var field:e.getAsJsonObject().entrySet()){
                var type=fields.get(field.getKey());
                if(type==null||!nullable(type,field.getValue()))return false;
            }
            return true;
        }
    }
    private static boolean nullable(Predicate<JsonElement> test,JsonElement e){return e.isJsonNull()||test.test(e);}
    private static Predicate<JsonElement> array(Predicate<JsonElement> members){
        return e->{
            if(!e.isJsonArray())return false;
            for(JsonElement value:e.getAsJsonArray())if(!members.test(value))return false;
            return true;
        };
    }
    private static Predicate<JsonElement> values(Predicate<JsonElement> members){
        return e->{
            if(!e.isJsonObject())return false;
            for(var value:e.getAsJsonObject().entrySet())if(!members.test(value.getValue()))return false;
            return true;
        };
    }
    private static boolean integer(JsonElement e,boolean small){
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isNumber())return false;
        try{
            long value=Long.parseLong(e.getAsString());
            return !small||value>=Integer.MIN_VALUE&&value<=Integer.MAX_VALUE;
        }catch(NumberFormatException invalid){return false;}
    }
    private static final Schema META=new Schema().with(STRING,"id name authorName authorUuid").with(LONG,"createdAt");
    private static final Schema ELEMENT=new Schema().with(STRING,"block item defaultItem")
        .with(INT,"metadata damage count defaultDamage");
    private static final Schema TARGET=new Schema().with(STRING,"item").with(INT,"damage");
    private static Schema extras(Predicate<JsonElement> nbt){return new Schema().with(nbt,"tileNbt itemNbt")
        .with(STRING,"prompt structureId structureRole structureFamily structureHint")
        .with(BOOL,"excluded controller structureCandidatesComplete").with(INT,"facing")
        .with(array(STRING),"structureIds").with(array(INT),"structureHatchFacings")
        .with(array(TARGET::matches),"allowedTargets");}
    private static final Schema CELL_EXTRA=extras(NATIVE_NBT);
    private static final Schema LEGACY_CELL=new Schema().add(ELEMENT).add(extras(LEGACY_NBT)).with(INT,"x y z");
    private static final Schema NATIVE_CELL=new Schema().add(ELEMENT).add(CELL_EXTRA).with(INT,"x y z");
    private static Schema regions(Predicate<JsonElement> nbt){
        Schema structure=new Schema().with(STRING,"id name sourceMod sourceClass")
            .with(INT,"x y z controllerId main").with(BOOL,"enabled mainExplicit noHatches")
            .with(values(INT),"channels").with(nbt,"semanticNbt textBoxNbt");
        Schema hint=new Schema().with(INT,"x1 x2 y1 y2 z1 z2").with(STRING,"prompt");
        return new Schema().with(array(structure::matches),"structures").with(array(hint::matches),"hintAreas");
    }
    private static final Schema LEGACY_REGIONS=regions(LEGACY_NBT),NATIVE_REGIONS=regions(NATIVE_NBT);
    private static Schema document(Predicate<JsonElement> nbt,Schema palette){
        Schema stamp=new Schema().with(INT,"formatVersion").with(STRING,"gtmbVersion gtnhVersion gtnhVersionSource");
        Schema frame=new Schema().with(INT,"x y z facing rotation damage").with(STRING,"item");
        Schema issue=new Schema().with(INT,"x y z").with(LONG,"count").with(STRING,"code severity path message");
        return new Schema().with(INT,"schema aePolicyVersion deidentificationVersion deidentificationLevel controllerId controllerFacing")
            .with(BOOL,"fullyVoucherCovered deidentificationChosen sourceItemFramesTruncated retainNbt retainCovers")
            .with(STRING,"prompt sourceMod sourceClass controllerType").with(values(STRING),"modVersions")
            .with(array(STRING),"retainedNbtKeys").with(array(frame::matches),"sourceItemFrames")
            .with(array(issue::matches),"aeIssues").with(stamp::matches,"nativeBlueprint")
            .with(array(palette::matches),"palette").with(nbt,"processingNbt semanticNbt quantumGroupingNbt");
    }
    private static final Schema LEGACY_DOC=document(LEGACY_NBT,LEGACY_CELL),NATIVE_DOC=document(NATIVE_NBT,NATIVE_CELL);
    static boolean legacyCellKnown(JsonObject fields){return LEGACY_CELL.matches(fields);}
    static boolean legacyKnown(JsonObject meta,JsonObject regions,JsonObject doc){
        return META.matches(meta)&&LEGACY_REGIONS.matches(regions)&&LEGACY_DOC.matches(doc);
    }
    static boolean metaKnown(JsonObject fields){return META.matches(fields);}
    static boolean elementKnown(JsonObject fields){return ELEMENT.matches(fields);}
    static boolean wrapKnown(JsonObject fields,int scope){
        switch(scope){
            case 7:return CELL_EXTRA.matches(fields);
            case 8:return NATIVE_REGIONS.matches(fields);
            case 9:
                var doc=fields.get("document");var envelope=fields.get("envelope");var legacy=fields.get("legacyEnvelope");
                return doc!=null&&NATIVE_DOC.matches(doc)&&envelope!=null&&envelope.isJsonObject()
                    &&envelope.getAsJsonObject().entrySet().isEmpty()&&legacy!=null&&legacy.isJsonObject()
                    &&fields.entrySet().size()==3;
            default:return false;
        }
    }
}
