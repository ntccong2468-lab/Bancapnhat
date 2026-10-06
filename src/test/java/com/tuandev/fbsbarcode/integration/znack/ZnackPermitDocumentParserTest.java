package com.tuandev.fbsbarcode.integration.znack;

import com.google.gson.JsonParser;
import com.tuandev.fbsbarcode.integration.znack.ZnackModels.GoodsDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZnackPermitDocumentParserTest {
    @Test
    void productCardReturnsEverySupportedPermitDocument() {
        var card = JsonParser.parseString("""
                {
                  "good_attrs": [
                    {
                      "attr_id": 23557,
                      "attr_name": "Декларация о соответствии",
                      "certificate_number": "ЕАЭС N RU Д-RU.РА01.В.00001/26",
                      "certificate_issued_date": "2026-02-10"
                    },
                    {
                      "attr_id": 23561,
                      "attr_name": "Сертификат соответствия",
                      "attr_value": "RU С-RU.АБ01.В.00002/26:::2026-03-11"
                    },
                    {
                      "attr_id": 23765,
                      "attr_name": "Свидетельство о государственной регистрации",
                      "certificate_number": "RU.77.99.88.003.R.000003.04.26",
                      "certificate_issued_date": "2026-04-12"
                    },
                    {"attr_id": 13933, "attr_name": "Код ТНВЭД", "attr_value": "6201000000"},
                    {"attr_id": 23557, "certificate_number": "INCOMPLETE"}
                  ]
                }
                """).getAsJsonObject();

        List<GoodsDocument> documents = ZnackPermitDocumentParser.fromProductCard(card);

        assertEquals(List.of(
                new GoodsDocument("CONFORMITY_DECLARATION", "ЕАЭС N RU Д-RU.РА01.В.00001/26", "2026-02-10"),
                new GoodsDocument("CONFORMITY_CERTIFICATE", "RU С-RU.АБ01.В.00002/26", "2026-03-11"),
                new GoodsDocument("STATE_REGISTRATION_CERTIFICATE", "RU.77.99.88.003.R.000003.04.26", "2026-04-12")
        ), documents);
    }

    @Test
    void catalogUiCardReadsDisplayedCertificateInsteadOfInternalValue() {
        var card = JsonParser.parseString("""
                {
                  "gtin": "4627877922363",
                  "status": "published",
                  "businessLayer": {
                    "attrGroup": [{
                      "name": "Разрешительная документация",
                      "attributes": [
                        {"id": 23557, "name": "Декларация о соответствии", "value": null},
                        {
                          "id": 23561,
                          "name": "Сертификат соответствия",
                          "value": "11555611",
                          "valueId": 12048866169,
                          "showValue": {
                            "number": "ЕАЭС RU С-CN.АБ47.В.03492/24",
                            "dateFrom": "2024-01-26"
                          }
                        }
                      ]
                    }]
                  }
                }
                """).getAsJsonObject();

        assertEquals(List.of(new GoodsDocument(
                        "CONFORMITY_CERTIFICATE", "ЕАЭС RU С-CN.АБ47.В.03492/24", "2024-01-26")),
                ZnackPermitDocumentParser.fromProductCard(card));
    }

    @Test
    void registryReturnsOnlyDistinctDocumentsThatAreActiveForIntroduction() {
        var response = JsonParser.parseString("""
                {
                  "apiversion": 4,
                  "result": {
                    "documents": [
                      {
                        "attr_id": 23557,
                        "number": "ACTIVE-GROUP",
                        "from_date": "2026-01-10",
                        "status_group": 1,
                        "status": "Действует"
                      },
                      {
                        "attr_id": 23557,
                        "number": "ACTIVE-GROUP",
                        "from_date": "2026-01-10",
                        "status_group": 1
                      },
                      {
                        "attr_id": 23561,
                        "number": "ACTIVE-STATUS",
                        "from_date": "2026-02-11",
                        "status": "Возобновлен"
                      },
                      {
                        "attr_id": 23765,
                        "number": "ACTIVE-FLAG",
                        "from_date": "2026-03-12",
                        "active": true
                      },
                      {
                        "attr_id": 23557,
                        "number": "SUSPENDED",
                        "from_date": "2025-01-01",
                        "status_group": 2,
                        "status": "Приостановлен"
                      },
                      {
                        "attr_id": 23765,
                        "number": "INACTIVE-FLAG-WINS",
                        "from_date": "2026-01-01",
                        "status_group": 1,
                        "active": false
                      },
                      {
                        "attr_id": 23561,
                        "number": "UNKNOWN",
                        "from_date": "2026-01-01"
                      }
                    ]
                  }
                }
                """);

        List<GoodsDocument> documents = ZnackPermitDocumentParser.activeFromRegistry(response);

        assertEquals(List.of(
                new GoodsDocument("CONFORMITY_DECLARATION", "ACTIVE-GROUP", "2026-01-10"),
                new GoodsDocument("CONFORMITY_CERTIFICATE", "ACTIVE-STATUS", "2026-02-11"),
                new GoodsDocument("STATE_REGISTRATION_CERTIFICATE", "ACTIVE-FLAG", "2026-03-12")
        ), documents);
    }

    @Test
    void registryLookupCodes18And19MeanPendingRatherThanMissingDocuments() {
        assertTrue(ZnackPermitDocumentParser.registryLookupPending(JsonParser.parseString("""
                {"result":{"documents":[],"errors":[{"error_code":"18"}]}}
                """)));
        assertTrue(ZnackPermitDocumentParser.registryLookupPending(JsonParser.parseString("""
                {"errors":[{"code":19}]}
                """)));
        assertFalse(ZnackPermitDocumentParser.registryLookupPending(JsonParser.parseString("""
                {"result":{"documents":[],"errors":[{"error_code":"09"}]}}
                """)));
    }

    @Test
    void circulationPrefersDeclarationsAndFallsBackToConformityCertificates() {
        GoodsDocument certificate = new GoodsDocument(
                "CONFORMITY_CERTIFICATE", "CERTIFICATE-1", "2026-03-11");
        GoodsDocument declaration = new GoodsDocument(
                "CONFORMITY_DECLARATION", "DECLARATION-1", "2026-02-10");
        GoodsDocument stateRegistration = new GoodsDocument(
                "STATE_REGISTRATION_CERTIFICATE", "STATE-1", "2026-04-12");

        assertEquals(List.of(declaration), ZnackPermitDocumentParser.selectForCirculation(
                List.of(certificate, declaration)));
        assertEquals(List.of(certificate), ZnackPermitDocumentParser.selectForCirculation(
                List.of(certificate)));
        assertEquals(List.of(stateRegistration), ZnackPermitDocumentParser.selectForCirculation(
                List.of(stateRegistration)));
    }
}
