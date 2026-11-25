// SPDX-License-Identifier: AGPL-3.0-only
// Additional terms: see AGPL-ADDITIONAL-TERMS.md in the project root.

package com.miaokatze.gtmb.hologram.gtnb;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** J1 preserves numeric tokens and property presence. No Gson DTO defaults or double conversion. */
public final class NativeBlueprintJson {
    private static final Pattern NUMBER=Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private NativeBlueprintJson() {}
    public static JsonElement parse(byte[] bytes)throws IOException {
        NativeBlueprintBytes.need(bytes.length<=NativeBlueprintLimits.JSON_BYTES,"JSON byte budget");
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        Parser p=new Parser(text);JsonElement e=p.value(0);p.space();NativeBlueprintBytes.need(p.at==text.length(),"JSON trailer");return e;
    }
    /** A cell table on old disk often precedes its dictionary. Keep its character interval only.
     * Decode each row once after reading the header/dictionary, and release that row immediately. */
    static final class LegacyInput {
        final JsonObject root;
        final java.util.IdentityHashMap<JsonObject,Rows> tables = new java.util.IdentityHashMap<>();
        LegacyInput(byte[] bytes)throws IOException {
            NativeBlueprintBytes.need(bytes.length<=NativeBlueprintLimits.FILE_BYTES,"legacy file budget");
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Parser p=new Parser(text);
            JsonElement e=p.value(0,1,tables);
            p.space();NativeBlueprintBytes.need(p.at==text.length()&&e.isJsonObject(),"legacy root/trailer");
            root=e.getAsJsonObject();
            // In a wrapped document a root-level "cells" can be an opaque envelope field.
            if((root.has("blueprint")||root.has("presets"))&&tables.containsKey(root)){
                Rows rows=tables.remove(root);JsonArray restored=new JsonArray();
                while(rows.hasNext())restored.add(rows.next());root.add("cells",restored);
            }
        }
    }
    static final class Rows {
        final Parser reader; final int end; boolean started,done;int count;
        Rows(String text,int begin,int end){reader=new Parser(text);reader.at=begin+1;this.end=end;}
        boolean hasNext()throws IOException {
            if(done)return false;
            if(reader.eat(']')){NativeBlueprintBytes.need(reader.at==end,"legacy cells end");done=true;return false;}
            if(started)reader.require(',');
            return true;
        }
        JsonElement next()throws IOException {
            started=true;NativeBlueprintBytes.need(++count<=NativeBlueprintLimits.CELLS,"legacy cell count budget");
            return reader.value(1);
        }
    }
    static JsonElement canonical(byte[] bytes)throws IOException {JsonElement v=parse(bytes);NativeBlueprintBytes.need(Arrays.equals(bytes,encode(v)),"noncanonical J1");return v;}
    private static final class Parser {
        final String text;int at;Parser(String t){text=t;}
        void space(){while(at<text.length()&&(text.charAt(at)==' '||text.charAt(at)=='\t'||text.charAt(at)=='\r'||text.charAt(at)=='\n'))at++;}
        boolean eat(char c){space();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
        void require(char c)throws IOException{NativeBlueprintBytes.need(eat(c),"expected JSON "+c);}
        String string()throws IOException {space();NativeBlueprintBytes.need(at<text.length()&&text.charAt(at++)=='"',"JSON string");StringBuilder s=new StringBuilder();boolean end=false;while(at<text.length()){char c=text.charAt(at++);if(c=='"'){end=true;break;}NativeBlueprintBytes.need(c>=32,"JSON control");if(c=='\\'){NativeBlueprintBytes.need(at<text.length(),"JSON escape");char e=text.charAt(at++);switch(e){case '"':case '\\':case '/':c=e;break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;case 'u':NativeBlueprintBytes.need(at+4<=text.length(),"JSON unicode");int v=0;for(int i=0;i<4;i++){int d=Character.digit(text.charAt(at++),16);NativeBlueprintBytes.need(d>=0,"JSON unicode digit");v=(v<<4)|d;}c=(char)v;break;default:throw NativeBlueprintBytes.bad("JSON escape");}}s.append(c);NativeBlueprintBytes.need(s.length()<=NativeBlueprintLimits.RECORD_BYTES,"JSON string budget");}NativeBlueprintBytes.need(end,"unterminated JSON string");return s.toString();}
        // The full row grammar, UTF and duplicate keys are checked during its one semantic decode.
        // Here only find the matching array terminator; never parse/retain N row objects at once.
        void skipCellTable(int depth)throws IOException {
            int nesting=0;boolean inString=false,escaped=false;
            do {
                NativeBlueprintBytes.need(at<text.length(),"unterminated cells");
                char c=text.charAt(at++);
                if(inString){if(escaped)escaped=false;else if(c==92)escaped=true;else if(c=='"')inString=false;}
                else if(c=='"')inString=true;
                else if(c=='['||c=='{'){nesting++;NativeBlueprintBytes.need(depth+nesting<=NativeBlueprintLimits.DEPTH,"legacy cells depth");}
                else if(c==']'||c=='}')nesting--;
            }while(nesting>0);
            NativeBlueprintBytes.need(text.charAt(at-1)==']',"legacy cells array");
        }
        JsonElement value(int depth)throws IOException{return value(depth,0,null);}
        JsonElement value(int depth,int scope,java.util.IdentityHashMap<JsonObject,Rows> tables)throws IOException{space();NativeBlueprintBytes.need(depth<=NativeBlueprintLimits.DEPTH&&at<text.length(),"JSON depth/token");char c=text.charAt(at);
            if(c=='{'){at++;JsonObject o=new JsonObject();if(eat('}'))return o;do{String k=string();NativeBlueprintBytes.need(!o.has(k),"duplicate JSON key: "+k);require(':');space();
                if(tables!=null&&(scope==1||scope==2)&&k.equals("cells")&&at<text.length()&&text.charAt(at)=='['){
                    int begin=at;skipCellTable(depth+1);tables.put(o,new Rows(text,begin,at));o.add(k,new JsonArray());
                }else o.add(k,value(depth+1,scope==1&&k.equals("blueprint")?2:scope==1&&k.equals("presets")?3:0,tables));}while(eat(','));require('}');return o;}
            if(c=='['){at++;JsonArray a=new JsonArray();if(eat(']'))return a;do{a.add(value(depth+1,scope==3?2:0,tables));}while(eat(','));require(']');return a;}
            if(c=='"')return new JsonPrimitive(string());
            if(text.startsWith("true",at)){at+=4;return new JsonPrimitive(true);}if(text.startsWith("false",at)){at+=5;return new JsonPrimitive(false);}if(text.startsWith("null",at)){at+=4;return JsonNull.INSTANCE;}
            int start=at;while(at<text.length()&&"-+0123456789.eE".indexOf(text.charAt(at))>=0)at++;String number=text.substring(start,at);NativeBlueprintBytes.need(number.length()<=128&&NUMBER.matcher(number).matches(),"JSON number/token");return new JsonPrimitive(new RawNumber(number));
        }
    }
    public static byte[] encode(JsonElement e){StringBuilder out=new StringBuilder();write(e,out);return out.toString().getBytes(StandardCharsets.US_ASCII);}
    private static void write(JsonElement e,StringBuilder s){
        if(e==null||e.isJsonNull()){s.append("null");return;}
        if(e.isJsonObject()){s.append('{');List<String> keys=new ArrayList<>();for(Map.Entry<String,JsonElement>x:e.getAsJsonObject().entrySet())keys.add(x.getKey());keys.sort((a,b)->quoted(a).compareTo(quoted(b)));boolean first=true;for(String k:keys){if(!first)s.append(',');first=false;s.append(quoted(k)).append(':');write(e.getAsJsonObject().get(k),s);}s.append('}');}
        else if(e.isJsonArray()){s.append('[');boolean first=true;for(JsonElement x:e.getAsJsonArray()){if(!first)s.append(',');first=false;write(x,s);}s.append(']');}
        else {JsonPrimitive p=e.getAsJsonPrimitive();s.append(p.isString()?quoted(p.getAsString()):p.toString());}
    }
    private static String quoted(String value){StringBuilder s=new StringBuilder("\"");for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){case '"':s.append("\\\"");break;case '\\':s.append("\\\\");break;case '\b':s.append("\\b");break;case '\f':s.append("\\f");break;case '\n':s.append("\\n");break;case '\r':s.append("\\r");break;case '\t':s.append("\\t");break;default:if(c<32||c>126)s.append(String.format("\\u%04x",(int)c));else s.append(c);}}return s.append('"').toString();}
    public static JsonObject copy(JsonObject o)throws IOException{return parse(encode(o)).getAsJsonObject();}
    private static final class RawNumber extends Number {
        private final String value;RawNumber(String v){value=v;}public int intValue(){return new java.math.BigDecimal(value).intValue();}public long longValue(){return new java.math.BigDecimal(value).longValue();}public float floatValue(){return Float.parseFloat(value);}public double doubleValue(){return Double.parseDouble(value);}public String toString(){return value;}
    }
}
