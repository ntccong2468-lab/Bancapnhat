package com.vncode.app.ui.gs1;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Required keys observed in the public Catalog frontend, not real enterprise information. */
class Gs1JoinFormValidationContractTest {
    private static final String INN="7701234567";
    @Test void applicantNeedsALegalForm(){var form=validForm();form.getAsJsonObject("applicant").remove("opf");assertFailure(form,0,"opf");}
    @Test void applicantNeedsTheSignersPosition(){var form=validForm();form.getAsJsonObject("applicant").remove("position");assertFailure(form,0,"position");}
    @Test void contactsNeedTheirPosition(){var form=validForm();form.getAsJsonArray("contacts").get(0).getAsJsonObject().remove("position");assertFailure(form,4,"position");}
    @Test void aBusinessNeedsAtLeastOneAccountType(){var form=validForm();form.getAsJsonObject("companyDetails").add("accountType",new JsonArray());assertFailure(form,3,"accountType");}
    @Test void governmentParticipationNeedsAnExplicitCheckedChoice(){var form=validForm();form.getAsJsonObject("companyDetails").getAsJsonArray("partisipationInGovernmentProjects").get(0).getAsJsonObject().addProperty("checked",false);assertFailure(form,3,"partisipationInGovernmentProjects");}
    @Test void sellingMethodsNeedAnExplicitCheckedChoice(){var form=validForm();form.getAsJsonObject("companyDetails").getAsJsonObject("implementationMethods").getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("checked",false);assertFailure(form,3,"implementationMethods");}
    @Test void distributionNeedsAnExplicitCheckedChoice(){var form=validForm();form.getAsJsonObject("companyDetails").getAsJsonObject("distributionChannels").getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("checked",false);assertFailure(form,3,"distributionChannels");}
    @Test void otherSellingMethodsNeedAnExplanation(){var form=validForm();var group=form.getAsJsonObject("companyDetails").getAsJsonObject("implementationMethods");group.getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("value","ENT_SELLW_OTHRES");assertFailure(form,3,"implementationMethods");}
    @Test void otherDistributionNeedsAnExplanation(){var form=validForm();var group=form.getAsJsonObject("companyDetails").getAsJsonObject("distributionChannels");group.getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("value","ENT_DISTCH_OTHRES");assertFailure(form,3,"distributionChannels");}
    @Test void stringTrueCannotStandInForSellerSelection(){var form=validForm();form.getAsJsonObject("companyDetails").getAsJsonArray("partisipationInGovernmentProjects").get(0).getAsJsonObject().addProperty("checked","true");assertFailure(form,3,"partisipationInGovernmentProjects");}
    @Test void aLegacyCheckedIdWithoutCanonicalEvidenceNeedsReview(){var form=validForm();var value=form.getAsJsonObject("companyDetails").getAsJsonArray("partisipationInGovernmentProjects").get(0).getAsJsonObject();value.addProperty("id",value.remove("value").getAsString());value.addProperty("text",value.remove("label").getAsString());assertFailure(form,3,"partisipationInGovernmentProjects");}
    @Test void malformedAccountTypesCannotPassAsASelection(){var form=validForm();form.getAsJsonObject("companyDetails").addProperty("accountType","FIXTURE_ROLE");assertFailure(form,3,"accountType");}
    @Test void markingMustBeAnExplicitLiteralBooleanRatherThanAGuess(){
        for(JsonElement supplied:new JsonElement[]{null,JsonNull.INSTANCE,new JsonPrimitive("false"),new JsonPrimitive("true"),new JsonPrimitive(0)}){
            var form=validForm();if(supplied==null)form.getAsJsonObject("applicant").remove("isMarked");else form.getAsJsonObject("applicant").add("isMarked",supplied);assertFailure(form,0,"isMarked");
        }
    }
    @Test void bothLiteralMarkingChoicesRemainAvailableToTheSeller(){for(boolean supplied:new boolean[]{true,false}){var form=validForm();form.getAsJsonObject("applicant").addProperty("isMarked",supplied);assertTrue(Gs1JoinFormValidation.first(form,INN).isEmpty());}}
    @Test void aCompleteObservedFormIsValidatedWithoutChangingItsIdentityOrUnknownFields(){var form=validForm();var before=form.deepCopy();assertTrue(Gs1JoinFormValidation.first(form,INN).isEmpty());assertEquals(before,form);}
    @Test void suppliedOtherExplanationsRemainPartOfTheReviewedForm(){var form=validForm();var business=form.getAsJsonObject("companyDetails");for(String key:new String[]{"implementationMethods","distributionChannels"}){var group=business.getAsJsonObject(key);group.getAsJsonArray("fields").get(0).getAsJsonObject().addProperty("value",key.equals("implementationMethods")?"ENT_SELLW_OTHRES":"ENT_DISTCH_OTHRES");group.addProperty("moreText","Reviewed synthetic explanation");}assertTrue(Gs1JoinFormValidation.first(form,INN).isEmpty());}
    private static void assertFailure(JsonObject form,int step,String field){var failure=Gs1JoinFormValidation.first(form,INN).orElseThrow(()->new AssertionError("Missing required "+field+" was accepted"));assertEquals(step,failure.step());assertEquals(field,failure.field());}
    static JsonObject validForm(){return JsonParser.parseString("""
        {"applicant":{"inn":"7701234567","companyName":"Synthetic company","companyNameShort":"Synthetic","countryId":"RU","firstName":"Test","lastName":"Seller","email":"fixture@example.invalid","phone":"+70000000000","ogrn":"1234567890123","kpp":"770101001","okved2":"47.91","opf":"FIXTURE_OPF","position":"FIXTURE_POSITION","isMarked":true},
         "bankDetails":{"name":"Synthetic bank","bik":"123456789","settlementAccount":"12345678901234567890","noCorrespondentAccount":true},
         "addresses":{"legalAddress":{"countryId":"RU","fullAddress":"Synthetic verified address","fullGuid":"synthetic-legal-guid"},"physicalAddress":{"countryId":"RU","fullAddress":"Synthetic physical address","fullGuid":"synthetic-physical-guid"},"productionAddresses":[]},
         "companyDetails":{"enterpriseSector":"FIXTURE_SECTOR","mainProductGCP":"FIXTURE_PRODUCTS","obtainingDocumentsMethod":"PAPER","productsCountToMark":"less1000","accountType":["FIXTURE_ROLE"],"partisipationInGovernmentProjects":[{"value":"FIXTURE_MARKING","label":"Synthetic marking","checked":true}],"implementationMethods":{"fields":[{"value":"FIXTURE_SELLING","label":"Synthetic selling","checked":true}],"moreText":""},"distributionChannels":{"fields":[{"value":"FIXTURE_DISTRIBUTION","label":"Synthetic distribution","checked":true}],"moreText":""}},
         "contacts":[{"lastName":"Seller","firstName":"Test","email":"fixture@example.invalid","phone":"+70000000000","position":"FIXTURE_POSITION"}],"retainedServerField":{"value":"unchanged"}}
        """).getAsJsonObject();}
}
