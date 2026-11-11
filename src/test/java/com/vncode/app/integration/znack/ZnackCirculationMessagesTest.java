package com.vncode.app.integration.znack;

import com.vncode.app.config.Database;
import com.vncode.app.shared.AppLanguage;
import com.vncode.app.shared.I18nService;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Local presentation only: no seller API, circulation submission or status mutation. */
class ZnackCirculationMessagesTest {
    @TempDir Path directory;
    private AppLanguage previousLanguage;

    @BeforeEach void setup(){
        System.setProperty("vncode.appdata.dir",directory.toString());Database.initDatabase();
        previousLanguage=I18nService.getInstance().getCurrentLanguage();
    }
    @AfterEach void cleanup(){
        try{I18nService.getInstance().setLanguage(previousLanguage);}
        finally{System.clearProperty("vncode.appdata.dir");}
    }

    @ParameterizedTest @EnumSource(AppLanguage.class)
    void knownReconciliationReasonsAreLocalizedButUnknownDetailsRemainVisible(AppLanguage language){
        var i18n=I18nService.getInstance();i18n.setLanguage(language);var messages=new HashSet<String>();
        for(String suffix:List.of("ORIGINAL_PROFILE_MISSING","CODES_INCOMPLETE","IDENTITY_UNVERIFIED","OWNER_UNVERIFIED","STATUS_UNVERIFIED")){
            String code="KIZ_CIRCULATION_"+suffix;
            String key="znack.error.circulation."+suffix.toLowerCase(java.util.Locale.ROOT);
            String message=ZnackErrorMessages.display(code);
            assertFalse(message.isBlank());assertNotEquals(code,message);assertNotEquals(key,message);
            assertEquals(i18n.getBundle().getString(key),message);
            assertEquals(message,ZnackErrorMessages.display("  "+code+"  "));
            assertEquals(message,ZnackErrorMessages.displayForPipeline("WAITING_INTRODUCTION_READINESS",code),
                    "A reconciliation blocker must remain visible instead of being hidden as routine progress");
            messages.add(message);
        }
        assertEquals(5,messages.size(),"Missing profile, codes, identity, owner and status need distinct explanations");
        for(String unknown:List.of("KIZ_CIRCULATION_UNKNOWN","detail KIZ_CIRCULATION_STATUS_UNVERIFIED", "KIZ_CIRCULATION_STATUS_UNVERIFIED: additional detail")){
            assertEquals(unknown,ZnackErrorMessages.display(unknown),"Only the exact persisted codes may replace raw details");
        }
    }
}
