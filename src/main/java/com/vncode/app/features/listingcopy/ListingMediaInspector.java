package com.vncode.app.features.listingcopy;

import com.vncode.app.features.products.ProductMedia;
import java.io.*;
import java.util.Iterator;
import javax.imageio.*;
@FunctionalInterface
public interface ListingMediaInspector {
    record Inspected(String sha256,int width,int height,String mimeType,String visualHash){public Inspected(String sha256,int width,int height,String mimeType){this(sha256,width,height,mimeType,null);}}
    Inspected inspect(ProductMedia media)throws IOException;
    static ListingMediaInspector fullSize(){return media->{
        try(var file=new ListingMediaReader().fetch(media.uri(),media.mimeType());var in=ImageIO.createImageInputStream(file.temporaryFile().toFile())){
            Iterator<ImageReader> readers=ImageIO.getImageReaders(in);if(!readers.hasNext())throw new IOException("Unsupported listing image");var r=readers.next();
            try{r.setInput(in,true,true);int w=r.getWidth(0),h=r.getHeight(0);if((long)w*h>40_000_000)throw new IOException("Image pixel limit");var param=r.getDefaultReadParam();int sample=Math.max(1,Math.max(w/256,h/256));param.setSourceSubsampling(sample,sample,0,0);var decoded=r.read(0,param);var small=new java.awt.image.BufferedImage(9,8,java.awt.image.BufferedImage.TYPE_BYTE_GRAY);var graphics=small.createGraphics();try{graphics.drawImage(decoded,0,0,9,8,null);}finally{graphics.dispose();decoded.flush();}long bits=0;for(int y=0;y<8;y++)for(int x=0;x<8;x++)bits=(bits<<1)|(small.getRaster().getSample(x,y,0)>small.getRaster().getSample(x+1,y,0)?1:0);return new Inspected(file.sha256(),w,h,file.mimeType(),String.format("%016x",bits));}finally{r.dispose();}
        }
    };}
}
