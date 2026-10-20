package com.vncode.app.integration.update;

import com.vncode.app.BuildConfig;
import com.vncode.app.shared.ConfigService;
import java.io.IOException;
import java.util.function.Supplier;

public class UpdateService {

    public enum Status { AVAILABLE, CURRENT, NO_RELEASE, SKIPPED, FAILED }
    public record CheckResult(Status status,UpdateInfo info,String errorCode) {}
    private final UpdateApiClient apiClient;
    private final Supplier<String> currentVersion,skippedVersion;
    public UpdateService(){this(new UpdateApiClient(),BuildConfig::getAppVersion,ConfigService::getSkippedVersion);}
    UpdateService(UpdateApiClient api,Supplier<String> current,Supplier<String> skipped){apiClient=api;currentVersion=current;skippedVersion=skipped;}
    public CheckResult check(boolean manual) {
        try {
            UpdateInfo info = apiClient.fetchLatestVersion();
            if(info==null)return new CheckResult(Status.NO_RELEASE,null,"");
            if(info.getVersion()==null||!info.getVersion().matches("[0-9]{1,5}\\.[0-9]{1,5}\\.[0-9]{1,5}"))throw new IOException("UPDATE_INVALID_RESPONSE");
            if(VersionComparator.compare(info.getVersion(),currentVersion.get())<=0)return new CheckResult(Status.CURRENT,null,"");
            if(!manual&&!info.isMandatory()&&info.getVersion().equals(skippedVersion.get()))return new CheckResult(Status.SKIPPED,info,"");
            return new CheckResult(Status.AVAILABLE,info,"");
        } catch (Exception failure) {
            String code=failure.getMessage();
            if(code==null||!code.matches("UPDATE_[A-Z0-9_]{1,80}"))code="UPDATE_NETWORK";
            return new CheckResult(Status.FAILED,null,code);
        }
    }
    public UpdateInfo checkForUpdate(){var result=check(false);return result.status()==Status.AVAILABLE?result.info():null;}
}
