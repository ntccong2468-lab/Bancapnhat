package com.vncode.app.integration.ozon;

import com.itextpdf.barcodes.Barcode128;
import com.itextpdf.kernel.colors.Color;
import com.itextpdf.kernel.colors.DeviceCmyk;
import com.itextpdf.kernel.colors.DeviceGray;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.geom.Line;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.geom.Vector;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.canvas.parser.EventType;
import com.itextpdf.kernel.pdf.canvas.parser.PdfCanvasProcessor;
import com.itextpdf.kernel.pdf.canvas.parser.data.*;
import com.itextpdf.kernel.pdf.canvas.parser.listener.IEventListener;
import java.util.*;

/** Fast positive recognition of a vector Code 128 product page; all other graphics use the decoder. */
final class OzonProductBarcodePage {
    private final Set<String> patterns;

    OzonProductBarcodePage(Set<String> barcodes) {
        patterns=new HashSet<>();
        for(String code:barcodes)try {
            byte[] bars=Barcode128.getBarsCode128Raw(Barcode128.getRawText(code,false,Barcode128.Barcode128CodeSet.AUTO));
            var pattern=new StringBuilder();for(byte bar:bars)if(bar>0)pattern.append(bar);
            patterns.add(pattern.toString());
        }catch(RuntimeException unsupported){/* Raster recognition handles unsupported encodings. */}
    }

    boolean matches(PdfPage page) {
        var shapes=new Bars();
        try { new PdfCanvasProcessor(shapes).processPageContent(page); }
        catch(RuntimeException unsupported){return false;}
        if(!shapes.onlyBars||shapes.rectangles.size()<10)return false;
        var bars=shapes.rectangles;bars.sort(Comparator.comparingDouble(Rectangle::getLeft));
        Rectangle first=bars.getFirst();
        if(first.getHeight()<first.getWidth()*3)return false;
        var widths=new ArrayList<Float>();float right=first.getLeft();
        for(var bar:bars){
            if(Math.abs(bar.getBottom()-first.getBottom())>.05||Math.abs(bar.getTop()-first.getTop())>.05)return false;
            if(!widths.isEmpty()){float gap=bar.getLeft()-right;if(gap<=0)return false;widths.add(gap);}
            if(bar.getWidth()<=0)return false;widths.add(bar.getWidth());right=bar.getRight();
        }
        float unit=Collections.min(widths);var pattern=new StringBuilder();
        for(float width:widths){int modules=Math.round(width/unit);
            if(modules<1||modules>4||Math.abs(width-modules*unit)>Math.max(.02f,unit*.04f))return false;
            pattern.append(modules);
        }
        return patterns.contains(pattern.toString());
    }

    private static final class Bars implements IEventListener {
        final List<Rectangle> rectangles=new ArrayList<>();boolean onlyBars=true;
        @Override public Set<EventType> getSupportedEvents(){return Set.of(EventType.RENDER_PATH,EventType.RENDER_IMAGE);}
        @Override public void eventOccurred(IEventData event,EventType type){
            if(!onlyBars)return;
            if(event instanceof ImageRenderInfo){onlyBars=false;return;}
            if(!(event instanceof PathRenderInfo path))return;
            int operation=path.getOperation();
            if(operation==PathRenderInfo.NO_OP)return;
            if((operation&PathRenderInfo.STROKE)!=0){onlyBars=false;return;}
            if(white(path.getFillColor()))return;
            if(!black(path.getFillColor())){onlyBars=false;return;}
            for(var subpath:path.getPath().getSubpaths()){
                if(subpath.getSegments().isEmpty())continue; // The PDF path builder leaves an empty cursor after re/close.
                if(!subpath.isClosed()||subpath.getSegments().size()<3||subpath.getSegments().size()>4){onlyBars=false;return;}
                var points=new ArrayList<Vector>();
                for(var segment:subpath.getSegments()){
                    if(!(segment instanceof Line)){onlyBars=false;return;}
                    var ends=segment.getBasePoints();
                    Vector a=new Vector((float)ends.getFirst().getX(),(float)ends.getFirst().getY(),1).cross(path.getCtm());
                    Vector b=new Vector((float)ends.getLast().getX(),(float)ends.getLast().getY(),1).cross(path.getCtm());
                    if(Math.abs(a.get(0)-b.get(0))>.02&&Math.abs(a.get(1)-b.get(1))>.02){onlyBars=false;return;}
                    points.add(a);points.add(b);
                }
                float minX=Float.POSITIVE_INFINITY,minY=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX;
                for(var p:points){minX=Math.min(minX,p.get(0));minY=Math.min(minY,p.get(1));maxX=Math.max(maxX,p.get(0));maxY=Math.max(maxY,p.get(1));}
                if(maxX>minX&&maxY>minY)rectangles.add(new Rectangle(minX,minY,maxX-minX,maxY-minY));
            }
        }
        private static boolean white(Color color){
            if(color==null)return false;float[] v=color.getColorValue();
            if(color instanceof DeviceGray)return v[0]==1;
            if(color instanceof DeviceRgb)return v[0]==1&&v[1]==1&&v[2]==1;
            if(color instanceof DeviceCmyk)return v[0]==0&&v[1]==0&&v[2]==0&&v[3]==0;
            return false;
        }
        private static boolean black(Color color){
            if(color==null)return false;float[] v=color.getColorValue();
            if(color instanceof DeviceGray)return v[0]==0;
            if(color instanceof DeviceRgb)return v[0]==0&&v[1]==0&&v[2]==0;
            if(color instanceof DeviceCmyk)return v[0]==0&&v[1]==0&&v[2]==0&&v[3]==1;
            return false;
        }
    }
}
