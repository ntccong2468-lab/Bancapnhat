package com.vncode.app.features.gs1;

import com.vncode.app.integration.znack.OperatorAgreement;
import com.vncode.app.models.Shop;
import java.util.List;

/** UI fixtures go through the real durable preparation boundary, with synthetic read-only inputs. */
public final class Gs1AgreementTestFixture {
    private Gs1AgreementTestFixture() {}
    public static Gs1AgreementService.Prepared prepare(Gs1Repository repository, Shop shop, OperatorAgreement agreement, byte[] pdf) throws Exception {
        var service = new Gs1AgreementService(repository, (s, settings, scope, guard) -> new Gs1AgreementService.Backend() {
            public List<OperatorAgreement> list() { return List.of(agreement); }
            public byte[] pdf(OperatorAgreement selected) { return pdf.clone(); }
            public String sign(byte[] bytes) { throw new AssertionError("Read-only UI fixture"); }
            public String submit(String id, String signature) { throw new AssertionError("Read-only UI fixture"); }
        });
        return service.prepare(shop, agreement);
    }
}
