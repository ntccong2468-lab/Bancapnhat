package com.vncode.app.integration.ozon;

import com.google.gson.*;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.vncode.app.features.fbo.*;
import com.vncode.app.features.license.LicenseModels.Permission;
import com.vncode.app.features.license.LicenseRuntime;
import com.vncode.app.models.Order;
import com.vncode.app.shared.AtomicFilePublisher;
import java.io.*;
import java.util.*;

/** Approved Ozon FBO pages with a frozen, independently recoverable print-history contract. */
public final class OzonFboBarcodePdfExporter {
    public static final String LAYOUT_KIND="OZON_FBO_COMBINED_V1";
    public static final String TEMPLATE_NAME="Ozon · KIZ + barcode · 58×40";
    public void exportPlan(FboPrintPlan plan,File target,Runnable beforePublish)throws IOException {
        LicenseRuntime.require(Permission.PRINT_NEW);var orders=toOrders(plan.pages());String layout=frozenLayout(plan);
        File staging=AtomicFilePublisher.stagingFile(target,".pdf");
        try{
            renderFrozen(layout,orders,staging);
            LicenseRuntime.require(Permission.PRINT_NEW);if(beforePublish!=null)beforePublish.run();
            AtomicFilePublisher.publish(staging,target);
        }finally{AtomicFilePublisher.deleteQuietly(staging);}
    }
    public List<Order> toOrders(List<FboPrintPage> pages){return new FboBarcodePdfExporter().toOrders(pages);}
    public static String frozenLayout(FboPrintPlan plan){
        var layout=new JsonObject();layout.addProperty("renderer",LAYOUT_KIND);layout.addProperty("version",1);var labels=new JsonArray();
        for(var page:plan.pages()){
            if(page.kind()!=FboPrintPage.Kind.COMBINED&&page.kind()!=FboPrintPage.Kind.BARCODE)throw new IllegalArgumentException("OZON_FBO_LAYOUT_MISMATCH");
            if(page.kind()==FboPrintPage.Kind.COMBINED&&(page.kizCode()==null||page.kizCode().isBlank()))throw new IllegalArgumentException("OZON_FBO_KIZ_BINDING_MISMATCH");
            var product=page.product();var identity=new JsonObject();identity.addProperty("productId",product.productId());identity.addProperty("sku",product.catalogSku());identity.addProperty("offerId",product.variantId());identity.addProperty("marked",page.kind()==FboPrintPage.Kind.COMBINED);labels.add(identity);
        }
        layout.add("labels",labels);return layout.toString();
    }
    public static boolean isFrozenLayout(String layout){
        try{var value=JsonParser.parseString(layout).getAsJsonObject();return value.has("renderer")&&LAYOUT_KIND.equals(value.get("renderer").getAsString())&&value.has("version")&&value.get("version").getAsInt()==1;}
        catch(RuntimeException malformed){return false;}
    }
    /** Exact frozen recovery: no Catalog/network lookup, inventory allocation, or new-print license. */
    public static void renderFrozen(String layout,List<Order> orders,File target)throws IOException {
        if(!isFrozenLayout(layout))throw new IOException("OZON_FBO_LAYOUT_MISMATCH");
        var labels=JsonParser.parseString(layout).getAsJsonObject().getAsJsonArray("labels");
        if(labels==null||orders==null||labels.size()!=orders.size()||orders.isEmpty()||orders.size()>200000)throw new IOException("OZON_FBO_LAYOUT_MISMATCH");
        try(var document=new PdfDocument(new PdfWriter(target))){
            for(int i=0;i<orders.size();i++){
                var order=orders.get(i);var identity=labels.get(i).getAsJsonObject();String productId=identity.get("productId").getAsString(),sku=identity.get("sku").getAsString(),offer=identity.get("offerId").getAsString();boolean marked=identity.get("marked").getAsBoolean();
                if(marked&&(order.getKiz()==null||order.getKiz().isBlank())||!marked&&order.getKiz()!=null&&!order.getKiz().isBlank()||order.getBarcode()==null||order.getBarcode().isBlank())throw new IOException("OZON_FBO_LAYOUT_MISMATCH");
                var expected=new OzonPostingItemDto(i,productId,sku,offer,order.getName(),1,"","");
                var product=new OzonProductDto(productId,offer,sku,order.getName(),"",order.getArticle(),order.getColor(),order.getSize(),order.getSubjectName(),"",false,"",List.of(order.getBarcode()),"","","",order.getRuSize());
                OzonCombinedLabelRenderer.append(document,expected,product,order.getKiz());
            }
        }catch(IOException error){throw error;}catch(RuntimeException malformed){throw new IOException("OZON_FBO_LAYOUT_MISMATCH",malformed);}
    }
}
