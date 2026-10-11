package com.vncode.app.features.gs1;
import com.vncode.app.integration.gs1.Gs1Membership;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
public final class Gs1DashboardService {
    private final Gs1Repository repository;
    public Gs1DashboardService(){this(new Gs1Repository());}
    public Gs1DashboardService(Gs1Repository repository){this.repository=Objects.requireNonNull(repository);}
    public Optional<Gs1Membership> cached(String inn,String environment){return repository.membership(inn,environment);}
    public GtinAvailability availability(Gs1Membership membership,LocalDate date) {
        if(membership==null)return new GtinAvailability(OptionalLong.empty(),false,0,0);
        var active=membership.visiblePrefixes(date);var unique=new LinkedHashMap<String,Gs1Membership.Prefix>();var conflicting=new HashSet<String>();
        for(var prefix:active){var prior=unique.putIfAbsent(prefix.gcp(),prefix);if(prior!=null&&!Objects.equals(prior.gtinsLeft(),prefix.gtinsLeft()))conflicting.add(prefix.gcp());}
        long sum=0;int known=0,unknown=0;for(var prefix:unique.values())if(prefix.gtinsLeft()==null||conflicting.contains(prefix.gcp()))unknown++;else{sum=Math.addExact(sum,prefix.gtinsLeft());known++;}
        return new GtinAvailability(known==0?OptionalLong.empty():OptionalLong.of(sum),unknown==0&&known>0,unique.size(),unknown);
    }
    /** Catalog membership and ELK sessions are currently verified for production only. */
    public static String environment(String trueApiUrl) {
        try {var uri=URI.create(trueApiUrl);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getUserInfo()!=null||uri.getPort()!=-1&&uri.getPort()!=443)return "unsupported";
            if("markirovka.crpt.ru".equalsIgnoreCase(uri.getHost()))return "production";
            if("int01.gismt.crpt.tech".equalsIgnoreCase(uri.getHost()))return "sandbox";
        }catch(RuntimeException invalid){}return "unsupported";
    }
}
