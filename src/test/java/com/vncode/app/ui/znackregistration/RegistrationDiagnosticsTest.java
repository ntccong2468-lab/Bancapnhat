package com.vncode.app.ui.znackregistration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistrationDiagnosticsTest {
    @Test void oneRowReportContainsExactRemoteRequestAndReasonWithoutACredential(){
        String report=RegistrationDiagnostics.text("Fixture article","00000000000017","feed-123","Lỗi","23557: TN VED mismatch; token=fixture-secret");
        assertTrue(report.contains("feed_id: feed-123"));assertTrue(report.contains("00000000000017"));
        assertTrue(report.contains("23557: TN VED mismatch"));assertFalse(report.contains("fixture-secret"));
        assertFalse(report.contains("jobs.status"));
    }
}
