package com.vncode.app.integration.update;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class UpdateServiceTest {
    static class Api extends UpdateApiClient {
        UpdateInfo info;IOException failure;
        public UpdateInfo fetchLatestVersion()throws IOException{if(failure!=null)throw failure;return info;}
    }
    @Test void manualCheckFindsSkippedReleaseAndNetworkFailureNeverReportsCurrent(){
        var api=new Api();api.info=new UpdateInfo();api.info.setVersion("1.4.4");
        var service=new UpdateService(api,()->"1.4.3",()->"1.4.4");
        assertEquals(UpdateService.Status.SKIPPED,service.check(false).status());
        assertEquals(UpdateService.Status.AVAILABLE,service.check(true).status());
        api.info.setMandatory(true);assertEquals(UpdateService.Status.AVAILABLE,service.check(false).status());
        api.failure=new IOException("UPDATE_HTTP_429");var failed=service.check(true);
        assertEquals(UpdateService.Status.FAILED,failed.status());assertEquals("UPDATE_HTTP_429",failed.errorCode());
    }
    @Test void noPublishedReleaseAndUpToDateAreDifferentStates(){
        var api=new Api();var service=new UpdateService(api,()->"1.4.3",()->"");
        assertEquals(UpdateService.Status.NO_RELEASE,service.check(true).status());
        api.info=new UpdateInfo();api.info.setVersion("1.4.3");assertEquals(UpdateService.Status.CURRENT,service.check(true).status());
        api.info.setVersion("1.4.3-license-test.1");assertEquals(UpdateService.Status.FAILED,service.check(false).status());
    }
}
