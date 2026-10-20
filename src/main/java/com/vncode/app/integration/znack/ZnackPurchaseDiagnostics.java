package com.vncode.app.integration.znack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Diagnostic timestamps describe an actual scheduled check, never an invented retry promise. */
public final class ZnackPurchaseDiagnostics {
    private ZnackPurchaseDiagnostics(){}
    public static String text(String reason,String stage,Instant next){
        var i=com.vncode.app.shared.I18nService.getInstance();
        String code=ZnackErrorMessages.errorCode(reason);
        return i.tr("diagnostics.stage")+": "+(stage==null?"—":stage)
                +"\n"+i.tr("diagnostics.signature_code")+": "+(code.isBlank()?"—":code)
                +"\n"+i.tr("diagnostics.purchase_reason")+": "+(reason==null||reason.isBlank()?"—":ZnackErrorMessages.display(reason))
                +"\n"+i.tr("diagnostics.next_check")+": "+(next==null?i.tr("diagnostics.no_scheduled_check"):DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z").withZone(ZoneId.systemDefault()).format(next));
    }
}
