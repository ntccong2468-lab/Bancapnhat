package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.shared.*;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ZnackDownloadRecoveryMessagesTest {
    @TempDir Path directory;
    private AppLanguage previous;
    @BeforeEach void setup(){
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        previous=I18nService.getInstance().getCurrentLanguage();
    }
    @AfterEach void cleanup(){
        try{I18nService.getInstance().setLanguage(previous);}
        finally{System.clearProperty("vncode.appdata.dir");}
    }
    @ParameterizedTest @EnumSource(AppLanguage.class)
    void uncertainDownloadExplainsRecoveryWithoutHidingItBehindTheOriginalNetworkFailure(AppLanguage language){
        var i18n=I18nService.getInstance();i18n.setLanguage(language);
        String marker="SUZ_CODE_DOWNLOAD_UNCERTAIN",key="znack.error.suz_code_download_uncertain";
        String expected=i18n.tr(key);assertNotEquals(key,expected,"Recovery needs a real translation in every supported language");
        assertEquals(expected,ZnackErrorMessages.display(marker));
        assertEquals(expected,ZnackErrorMessages.display("  "+marker+"  "));
        String shown=FriendlyErrorService.format(new IllegalStateException(marker,new SocketTimeoutException("synthetic transport failure")));
        assertTrue(shown.contains(expected));assertTrue(shown.contains("["+marker+"]"));
        assertFalse(shown.contains("VN_NETWORK"));assertFalse(shown.contains("synthetic transport failure"));
    }
}
