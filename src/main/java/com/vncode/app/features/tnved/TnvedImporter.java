package com.vncode.app.features.tnved;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import static com.vncode.app.features.tnved.TnvedModels.*;

/** Bounded data-only import. A citation in a file is not proof of legal verification. */
public final class TnvedImporter {
    private static final long MAX_BYTES=32L*1024*1024;
    private TnvedImporter() { }
    public record Batch(Version version,List<Node> nodes) {
        public Batch { nodes=List.copyOf(nodes); }
    }
    public static Batch read(Path path) throws IOException {
        String text=bounded(path);
        try {
            JsonObject manifest;List<JsonObject> rows=new ArrayList<>();
            if(path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")) {
                manifest=JsonParser.parseString(bounded(Path.of(path+".manifest.json"))).getAsJsonObject();
                List<List<String>> csv=parseCsv(text);
                if(csv.isEmpty())throw new IllegalArgumentException("Missing CSV header.");
                List<String> header=csv.getFirst();if(new HashSet<>(header).size()!=header.size())throw new IllegalArgumentException("Duplicate CSV columns.");
                for(int i=1;i<csv.size();i++) {
                    var cells=csv.get(i);if(cells.size()==1&&cells.getFirst().isBlank())continue;
                    if(cells.size()!=header.size())throw new IllegalArgumentException("Inconsistent CSV column count.");
                    JsonObject row=new JsonObject();
                    for(int j=0;j<header.size();j++) {
                        String key=header.get(j),value=cells.get(j);
                        if(key.equals("is_leaf")||key.equals("is_active")) {
                            if(!value.equals("true")&&!value.equals("false"))throw new IllegalArgumentException("Invalid boolean.");
                            row.addProperty(key,Boolean.parseBoolean(value));
                        } else if(!value.isEmpty())row.addProperty(key,value);
                    }
                    rows.add(row);
                }
            } else if(path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) {
                manifest=JsonParser.parseString(text).getAsJsonObject();
                var nodes=manifest.getAsJsonArray("nodes");if(nodes==null||nodes.size()>100_000)throw new IllegalArgumentException("Invalid nodes array.");
                for(var e:nodes)rows.add(e.getAsJsonObject());
            } else throw new IllegalArgumentException("Only CSV and JSON are supported.");
            Version version=new Version(string(manifest,"version",true),string(manifest,"source_url",true),date(manifest,"valid_from",true));
            TnvedCatalog.requireSource(version.sourceUrl());
            List<Node> nodes=new ArrayList<>();
            for(var j:rows)nodes.add(new Node(string(j,"code",true),string(j,"parent_code",false),string(j,"node_type",true),string(j,"section_code",true),
                    string(j,"name_ru",true),optional(j,"name_vi"),optional(j,"description_ru"),optional(j,"notes"),bool(j,"is_leaf"),bool(j,"is_active"),
                    date(j,"valid_from",false),date(j,"valid_to",false),j.has("source_url")?string(j,"source_url",true):version.sourceUrl()));
            return new Batch(version,nodes);
        } catch(RuntimeException error) {
            throw new IllegalArgumentException("Invalid TN VED import format, type or date.",error);
        }
    }
    private static String bounded(Path path)throws IOException {
        if(Files.size(path)>MAX_BYTES)throw new IOException("TN VED import exceeds 32 MiB.");
        try(var input=Files.newInputStream(path)) {
            byte[] bytes=input.readNBytes((int)MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new IOException("TN VED import exceeds 32 MiB.");
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replaceFirst("^\uFEFF","");
        }
    }
    private static String string(JsonObject j,String key,boolean required) {
        var e=j.get(key);if(e==null||e.isJsonNull()){if(required)throw new IllegalArgumentException("Missing field.");return null;}
        if(!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isString())throw new IllegalArgumentException("Expected text field.");
        String value=e.getAsString();if(value.length()>50_000||required&&value.isBlank())throw new IllegalArgumentException("Invalid text length.");return value;
    }
    private static String optional(JsonObject j,String key){String v=string(j,key,false);return v==null?"":v;}
    private static LocalDate date(JsonObject j,String key,boolean required){String v=string(j,key,required);return v==null?null:LocalDate.parse(v);}
    private static boolean bool(JsonObject j,String key){var e=j.get(key);if(e==null||!e.isJsonPrimitive()||!e.getAsJsonPrimitive().isBoolean())throw new IllegalArgumentException("Expected boolean.");return e.getAsBoolean();}
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> rows=new ArrayList<>();List<String> row=new ArrayList<>();StringBuilder field=new StringBuilder();boolean quoted=false,closed=false;
        for(int i=0;i<text.length();i++) {
            char ch=text.charAt(i);
            if(quoted) {
                if(ch=='"'){if(i+1<text.length()&&text.charAt(i+1)=='"'){field.append('"');i++;}else{quoted=false;closed=true;}}
                else field.append(ch);
            } else if(ch=='"'){if(field.length()>0||closed)throw new IllegalArgumentException("Invalid CSV quote.");quoted=true;}
            else if(ch==','||ch=='\r'||ch=='\n') {
                row.add(field.toString());field.setLength(0);closed=false;
                if(ch!=','){rows.add(List.copyOf(row));row.clear();if(ch=='\r'&&i+1<text.length()&&text.charAt(i+1)=='\n')i++;}
            } else {if(closed)throw new IllegalArgumentException("Unexpected text after quoted field.");field.append(ch);}
            if(field.length()>50_000||rows.size()>100_001||row.size()>100)throw new IllegalArgumentException("CSV limit exceeded.");
        }
        if(quoted)throw new IllegalArgumentException("Unclosed CSV quote.");
        if(field.length()>0||!row.isEmpty()||closed){row.add(field.toString());rows.add(List.copyOf(row));}
        return rows;
    }
}
