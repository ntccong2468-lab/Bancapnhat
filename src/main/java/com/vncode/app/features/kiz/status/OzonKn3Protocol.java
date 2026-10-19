package com.vncode.app.features.kiz.status;

import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** Exact per-exemplar observations. `failed` alone means processing timeout in Ozon's contract. */
public final class OzonKn3Protocol {
    private OzonKn3Protocol(){}
    public static JsonObject result(JsonObject raw){return raw.has("result")&&raw.get("result").isJsonObject()?raw.getAsJsonObject("result"):raw;}
    public static List<Kn3Mark> parse(String posting,JsonObject raw)throws IOException {
        var root=result(raw);if(!posting.equals(text(root,"posting_number")))throw new IOException("OZON_KN3_FOREIGN_POSTING");
        var marks=new ArrayList<Kn3Mark>();var seen=new HashSet<String>();var products=new HashSet<String>();
        for(var p:array(root,"products")){var product=object(p);String sku=text(product,"product_id");if(!numeric(sku)||!products.add(sku))throw new IOException("OZON_KN3_INVALID_RESPONSE");
            for(var e:array(product,"exemplars")){var exemplar=object(e);String id=text(exemplar,"exemplar_id");if(!numeric(id)||!seen.add(sku+":"+id))throw new IOException("OZON_KN3_INVALID_RESPONSE");boolean mandatory=false;
                for(var m:marks(exemplar)){var mark=object(m);if(!"mandatory_mark".equals(text(mark,"mark_type")))continue;if(mandatory)throw new IOException("OZON_KN3_AMBIGUOUS_MARK");mandatory=true;
                    String code=text(mark,"mark"),status=text(mark,"check_status");var errors=new ArrayList<String>();
                    if(mark.has("error_codes"))for(var error:array(mark,"error_codes")){if(!error.isJsonPrimitive()||!error.getAsJsonPrimitive().isString())throw new IOException("OZON_KN3_INVALID_RESPONSE");String value=error.getAsString();if(!value.isBlank())errors.add(Kn3Mark.safe(value));}
                    var state=code.isBlank()?Kn3Mark.State.EMPTY:!errors.isEmpty()?Kn3Mark.State.REJECTED:"passed".equals(status)?Kn3Mark.State.ACCEPTED:Kn3Mark.State.PENDING;
                    marks.add(new Kn3Mark(posting,sku,id,code,state,!errors.isEmpty()?String.join(",",errors):"failed".equals(status)?"PROCESSING_FAILED":status));
                }
            }
        }return List.copyOf(marks);
    }
    /** Full writable projection; retain other marks, GTD, RNPT, weight and sibling exemplars. */
    public static JsonObject replacement(String posting,JsonObject raw,Kn3Mark target,String newCode)throws IOException {
        var root=result(raw);var observed=parse(posting,raw);var old=observed.stream().filter(m->m.productId().equals(target.productId())&&m.exemplarId().equals(target.exemplarId())).findFirst().orElseThrow(()->new IOException("OZON_KN3_CHANGED"));
        if(old.state()!=Kn3Mark.State.REJECTED||!old.fingerprint().equals(target.fingerprint()))throw new IOException("OZON_KN3_CHANGED");
        String status=text(root,"status");if(status.equals("update_not_available")||status.equals("validation_in_process"))throw new IOException("OZON_KN3_EDIT_UNAVAILABLE");
        var request=new JsonObject();request.addProperty("posting_number",posting);if(root.has("multi_box_qty"))request.add("multi_box_qty",root.get("multi_box_qty").deepCopy());var products=new JsonArray();request.add("products",products);
        for(var p:array(root,"products")){var source=object(p);var product=new JsonObject();product.add("product_id",source.get("product_id").deepCopy());var exemplars=new JsonArray();product.add("exemplars",exemplars);products.add(product);
            for(var e:array(source,"exemplars")){var original=object(e);var exemplar=new JsonObject();for(String field:List.of("exemplar_id","gtd","is_gtd_absent","is_rnpt_absent","rnpt","weight"))if(original.has(field))exemplar.add(field,original.get(field).deepCopy());var codes=new JsonArray();exemplar.add("marks",codes);exemplars.add(exemplar);
                for(var m:marks(original)){var current=object(m);var mark=new JsonObject();mark.addProperty("mark_type",text(current,"mark_type"));String code=text(current,"mark");if(text(source,"product_id").equals(target.productId())&&text(original,"exemplar_id").equals(target.exemplarId())&&text(current,"mark_type").equals("mandatory_mark"))code=newCode;mark.addProperty("mark",code);codes.add(mark);}
            }
        }return request;
    }
    private static boolean numeric(String value){return value.matches("[1-9][0-9]{0,18}");}
    private static JsonObject object(JsonElement value)throws IOException{if(!value.isJsonObject())throw new IOException("OZON_KN3_INVALID_RESPONSE");return value.getAsJsonObject();}
    private static JsonArray array(JsonObject value,String field)throws IOException{if(!value.has(field)||!value.get(field).isJsonArray())throw new IOException("OZON_KN3_INVALID_RESPONSE");return value.getAsJsonArray(field);}
    private static JsonArray marks(JsonObject value)throws IOException{return !value.has("marks")||value.get("marks").isJsonNull()?new JsonArray():array(value,"marks");}
    private static String text(JsonObject value,String field){var v=value.get(field);return v!=null&&v.isJsonPrimitive()?v.getAsString():"";}
}
