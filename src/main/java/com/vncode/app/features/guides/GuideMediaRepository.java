package com.vncode.app.features.guides;

import com.google.gson.Gson;
import com.vncode.app.shared.AppPaths;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Optional media selected by the desktop user, stored locally with no account credentials. */
public final class GuideMediaRepository {
    public enum Kind { IMAGE, VIDEO }
    public record Media(String guide,Kind kind,URI uri) {
        @Override public String toString(){return "GuideMedia[guide="+guide+", kind="+kind+"]";}
    }
    private final Path file;
    private final Gson json=new Gson();
    public GuideMediaRepository(){this(AppPaths.appDataDir().resolve("guide-media.json"));}
    public GuideMediaRepository(Path file){this.file=file;}
    public List<Media> list() {
        try {
            if(!Files.isRegularFile(file)||Files.size(file)>65536)return List.of();
            var values=json.fromJson(Files.readString(file),Media[].class);if(values==null||values.length>24)return List.of();
            return Arrays.stream(values).filter(Objects::nonNull).filter(GuideMediaRepository::valid).toList();
        }catch(Exception unavailable){return List.of();}
    }
    public void save(Media media)throws java.io.IOException {
        if(!valid(media))throw new IllegalArgumentException("Invalid guide media");
        var values=new ArrayList<>(list());values.removeIf(m->m.guide().equals(media.guide())&&m.kind()==media.kind());values.add(media);
        if(values.size()>24)throw new IllegalArgumentException("Too many guide media entries");
        Files.createDirectories(file.toAbsolutePath().getParent());Path staging=Files.createTempFile(file.toAbsolutePath().getParent(),"guide-media-",".json");
        try{Files.writeString(staging,json.toJson(values),StandardCharsets.UTF_8);Files.move(staging,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
        catch(AtomicMoveNotSupportedException unsupported){Files.move(staging,file,StandardCopyOption.REPLACE_EXISTING);}
        finally{Files.deleteIfExists(staging);}
    }
    private static boolean valid(Media m) {
        if(m.guide()==null||!Set.of("wb","ozon","labels","gtin","update","license","gs1","batch").contains(m.guide())||m.kind()==null||m.uri()==null||m.uri().getUserInfo()!=null||m.uri().getQuery()!=null||m.uri().getFragment()!=null)return false;
        if("file".equals(m.uri().getScheme()))return m.uri().getHost()==null||m.uri().getHost().isEmpty();
        return "https".equals(m.uri().getScheme())&&"vncode.id.vn".equals(m.uri().getHost())&&m.uri().getPort()==-1;
    }
}
