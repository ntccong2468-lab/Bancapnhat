package com.vncode.app.shared;

import com.vncode.app.integration.znack.signature.CryptoProErrorCode;
import com.vncode.app.integration.znack.signature.CryptoProException;
import com.vncode.app.integration.znack.GtinNormalizer;
import com.vncode.app.integration.znack.ZnackErrorMessages;
import com.vncode.app.integration.znack.ZnackSafety;
import com.vncode.app.integration.znack.ZnackSanitizer;

import java.text.MessageFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats exception stacktraces and raw backend error messages into localized i18n user alerts.
 */
public final class FriendlyErrorService {

    private static final java.util.Set<String> REGISTRATION_MODEL_ERRORS = java.util.Set.of(
            "CATALOG_REGISTRATION_SCHEMA_UNAVAILABLE", "CATALOG_COUNTRY_DICTIONARY_UNAVAILABLE",
            "CATALOG_COUNTRY_DICTIONARY_INVALID", "GTIN_VARIANT_IDENTITY_UNVERIFIED",
            "unverified_catalog_gtin", "UNVERIFIED_CATALOG_GTIN", "MARKETPLACE_REQUEST_REJECTED");

    private static final Pattern NOT_ENOUGH_KIZ = Pattern.compile(
            "Not enough available KIZ for GTIN ([0-9]{14}): required (\\d+), available (\\d+)");

    private static final Pattern STOCK_SHORTAGE = Pattern.compile("KIZ_INVENTORY_SHORTAGE: GTIN ([0-9]{14}); required=(\\d+); available=(\\d+)");

    private static final Pattern UNMAPPED_ORDER = Pattern.compile(
            "Order thứ (\\d+) cần KIZ nhưng nmId chưa được map: (.+)");

    private static final Pattern WB_DELIVER_BLOCKED = Pattern.compile(
            "WB не разрешает передачу поставки: не заполнены или неверны IMEI/UIN/SGTIN/GTIN для заказов (.+)");

    private FriendlyErrorService() {
    }

    public static String format(Throwable error) {
        if(com.vncode.app.config.SqliteWriteTransaction.isBusy(error))
            return I18nService.getInstance().tr("error.database_busy")+"\n[SYNC_STORAGE_BUSY]";
        var countryFailure=CatalogReadFailure.country(error);
        if(countryFailure.isPresent())return countryFailure.get().message()+"\n["+countryFailure.get().code()+"]";
        String code="VN_LOCAL";
        Throwable cause=error;
        for(int depth=0;cause!=null&&depth<8;depth++,cause=cause.getCause()) {
            if("SUZ_ORDER_RECONCILIATION_INVALID".equals(cause.getMessage())){code="SUZ_ORDER_RECONCILIATION_INVALID";break;}
            if("PRODUCT_PROPOSAL_CHANGED".equals(cause.getMessage())){code="PRODUCT_PROPOSAL_CHANGED";break;}
            if(REGISTRATION_MODEL_ERRORS.contains(cause.getMessage()==null?"":cause.getMessage())){code=cause.getMessage();break;}
            String bufferIssue = ZnackErrorMessages.kizOrderBufferIssueCode(cause.getMessage());
            if (!bufferIssue.isBlank()) { code = bufferIssue; break; }
            if(cause instanceof com.vncode.app.features.gtinsync.RegisteredGtinProof.UnverifiedOwnerException owner){code="CATALOG_OWNER_"+owner.reason().name();break;}
            if(cause instanceof com.vncode.app.integration.wb.WbApiException wb){code="WB_HTTP_"+wb.getStatusCode();break;}
            if(cause instanceof com.vncode.app.integration.ozon.OzonApiException ozon){code="OZON_HTTP_"+ozon.statusCode();break;}
            if(cause instanceof com.vncode.app.features.kiz.KizAssignmentException kiz){code=kiz.code();break;}
            if(cause.getMessage()!=null&&cause.getMessage().matches("KIZ_[A-Z0-9_]{1,70}")){code=cause.getMessage();break;}
            if(cause instanceof CryptoProException crypto){code="CRYPTOPRO_"+crypto.code().name();break;}
            if("SYNC_STORAGE".equals(cause.getMessage()))code="SYNC_STORAGE";
            if(cause instanceof java.net.UnknownHostException || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.SocketException || cause instanceof java.net.SocketTimeoutException){code="VN_NETWORK";break;}
        }
        String detail="VN_NETWORK".equals(code)?I18nService.getInstance().tr("error.network"):
                "SYNC_STORAGE".equals(code)?I18nService.getInstance().tr("error.database_storage"):formatDetail(error);
        if(error instanceof com.vncode.app.features.sync.ZnackGtinBackgroundService.PreparationException)
            detail=I18nService.getInstance().tr("gtin.background.prepare_failed")+"\n"+detail;
        return detail+"\n["+code+"]";
    }

    private static String formatDetail(Throwable error) {
        if (error == null) return I18nService.getInstance().tr("znack.signature.error.failed");
        if(error instanceof com.vncode.app.features.sync.ZnackGtinBackgroundService.RunException&&error.getCause()!=null)return formatDetail(error.getCause());
        Throwable cause=error;
        for(int depth=0;cause!=null&&depth<8;depth++,cause=cause.getCause()) {
            if("SUZ_ORDER_RECONCILIATION_INVALID".equals(cause.getMessage()))return I18nService.getInstance().tr("znack.error.suz_order_reconciliation_invalid");
            if("PRODUCT_PROPOSAL_CHANGED".equals(cause.getMessage()))return I18nService.getInstance().tr("bulk.proposal.changed");
            if(REGISTRATION_MODEL_ERRORS.contains(cause.getMessage()==null?"":cause.getMessage()))
                return I18nService.getInstance().tr("znack.registration.reason."+cause.getMessage());
            if (!ZnackErrorMessages.kizOrderBufferIssueCode(cause.getMessage()).isBlank())
                return ZnackErrorMessages.display(cause.getMessage());
            if(cause instanceof com.vncode.app.features.kiz.KizStockShortageException shortage)
                return com.vncode.app.features.kiz.KizErrorMessages.message(shortage.getMessage())+"\n"+shortage.missing().stream()
                    .map(row->MessageFormat.format(I18nService.getInstance().tr("kiz.shipment.missing_order"),row.order(),row.product(),row.category().isBlank()?"—":row.category()))
                    .collect(java.util.stream.Collectors.joining("\n"))+"\n"+MessageFormat.format(I18nService.getInstance().tr("kiz.shipment.missing_count"),shortage.missing().size());
            if(cause instanceof com.vncode.app.features.kiz.KizAssignmentException kiz)
                return com.vncode.app.features.kiz.KizErrorMessages.message(kiz.code())+"\n"+java.text.MessageFormat.format(I18nService.getInstance().tr("kiz.error.order_product"),kiz.order(),kiz.product());
            if(cause.getMessage()!=null&&cause.getMessage().matches("KIZ_[A-Z0-9_]{1,70}"))return com.vncode.app.features.kiz.KizErrorMessages.message(cause.getMessage());
            if(cause instanceof com.vncode.app.features.gtinsync.RegisteredGtinProof.UnverifiedOwnerException owner)
                return I18nService.getInstance().tr("error.catalog_owner_unverified")+"\n"+I18nService.getInstance().tr("error.catalog_owner."+owner.reason().name().toLowerCase(java.util.Locale.ROOT));
            if("unverified_catalog_owner".equals(cause.getMessage())||"catalog_owner_changed".equals(cause.getMessage()))
                return I18nService.getInstance().tr("error.catalog_owner_unverified");
            if(cause instanceof CryptoProException crypto)return formatCryptoPro(crypto);
        }
        if(error instanceof com.vncode.app.features.sync.ZnackGtinBackgroundService.PreparationException && error.getCause()!=null)
            return formatDetail(error.getCause());
        if (error instanceof com.vncode.app.features.license.LicenseGate.LicenseDeniedException) return I18nService.getInstance().tr(error.getMessage().equals("LICENSE_SHOP_LIMIT")?"license.account.shop_limit":"license.account.required");
        String message = error.getMessage() == null ? "" : error.getMessage().trim();
        for(String preflightCode:new String[]{"ZNACK_PREFLIGHT_READ_FAILED","ZNACK_REVIEWED_VALUES_CHANGED"})
            if(message.contains(preflightCode))return I18nService.getInstance().tr("znack.registration.reason."+preflightCode);
        if("REGISTRATION_REVIEW_TOO_LARGE".equals(message))
            return I18nService.getInstance().tr("znack.registration.preflight.review_too_large");
        if("registration_selection_limit".equals(message)||"SYNC_REPORT_LIMIT".equals(message))
            return I18nService.getInstance().tr("znack.registration.selection_limit");
        if(message.contains("CATALOG_NEW_GTIN_REQUIRES_REJECTED_FEED"))return I18nService.getInstance().tr("znack.registration.new_gtin_requires_rejection");
        if(message.contains("CATALOG_READBACK_RETRY_PENDING"))return I18nService.getInstance().tr("znack.registration.readback_retry_pending");
        if(message.contains("CATALOG_CARD_NOT_CREATED_AFTER_WAIT"))return I18nService.getInstance().tr("znack.registration.wait_expired");
        if(message.contains("CATALOG_FEED_COMPLETED_WITHOUT_CARD"))return I18nService.getInstance().tr("znack.registration.completed_without_card");
        if ("GTIN_NO_ELIGIBLE_PUBLISHED_CARDS".equals(message))return I18nService.getInstance().tr("gtin.background.no_eligible");
        if ("GTIN_TNVED_GROUP_CHANGED".equals(message))return I18nService.getInstance().tr("gtin.tnved_group_changed");
        if ("GTIN_SYNC_CANCELLED".equals(message))return I18nService.getInstance().tr("gtin.background.cancelled");
        if (message.isBlank()) {
            return I18nService.getInstance().tr("znack.signature.error.failed");
        }

        I18nService i18n = I18nService.getInstance();
        if (ZnackSafety.UNVERIFIED_SIGNATURE.equals(message)) {
            return i18n.tr("znack.signature.not_verified");
        }
        if (ZnackSafety.MISSING_SHOP_CONFIGURATION.equals(message)) {
            return i18n.tr("znack.error.shop_configuration");
        }
        if (ZnackErrorMessages.isSuzAuthError(message)) {
            return i18n.tr("znack.error.suz_auth_invalid");
        }
        if (message.startsWith("A KIZ purchase pipeline is already active")) {
            return i18n.tr("supply.gtin_inventory.error.pipeline_active");
        }
        if ("omsId is required before buying KIZ.".equals(message)) {
            return i18n.tr("supply.gtin_inventory.error.oms_id");
        }
        if (GtinNormalizer.TECHNICAL_GTIN_PURCHASE_UNSUPPORTED.equals(message)) {
            return i18n.tr("supply.gtin_inventory.error.technical_gtin");
        }
        if (message.startsWith("Products requiring KIZ are not mapped:")) {
            String details = message.substring("Products requiring KIZ are not mapped:".length()).trim();
            return i18n.tr("kiz.error.products_unmapped") + (details.isBlank() ? "" : "\n" + details);
        }
        if ("Сначала распечатайте этикетки для поставки.".equals(message)) {
            return i18n.tr("packing.error.print_labels_first");
        }
        if ("В поставке есть товары с обязательной маркировкой без KIZ.".equals(message)) {
            return i18n.tr("packing.error.missing_kiz");
        }
        if ("Нельзя смешивать B2B и B2C заказы в одной поставке.".equals(message)) {
            return i18n.tr("packing.error.mix_b2b_b2c");
        }
        if ("Тип B2B/B2C заказов не совпадает с выбранной поставкой.".equals(message)) {
            return i18n.tr("packing.error.type_mismatch");
        }
        if (message.contains("declaration or certificate") && (message.contains("no active") || message.contains("missing"))) {
            return i18n.tr("znack.error.missing_permit");
        }

        Matcher stockShortage=STOCK_SHORTAGE.matcher(message);
        if(stockShortage.find())return MessageFormat.format(i18n.tr("znack.error.insufficient_kiz"),stockShortage.group(1),stockShortage.group(2),stockShortage.group(3));
        Matcher notEnoughMatch = NOT_ENOUGH_KIZ.matcher(message);
        if (notEnoughMatch.find()) {
            return MessageFormat.format(i18n.tr("znack.error.insufficient_kiz"),
                    notEnoughMatch.group(1), notEnoughMatch.group(2), notEnoughMatch.group(3));
        }

        Matcher unmappedOrderMatch = UNMAPPED_ORDER.matcher(message);
        if (unmappedOrderMatch.find()) {
            return MessageFormat.format(i18n.tr("order.error.unmapped_nm_id"),
                    unmappedOrderMatch.group(1), unmappedOrderMatch.group(2));
        }

        Matcher wbDeliverMatch = WB_DELIVER_BLOCKED.matcher(message);
        if (wbDeliverMatch.find()) {
            return MessageFormat.format(i18n.tr("packing.error.wb_deliver_blocked"), wbDeliverMatch.group(1));
        }

        return message;
    }

    private static String formatCryptoPro(CryptoProException crypto) {
        I18nService i18n = I18nService.getInstance();
        String message = i18n.tr("znack.signature.error." + switch (crypto.code()) {
            case CRYPTOPRO_MISSING -> "cryptopro_missing";
            case CRYPTCP_MISSING -> "cryptcp_missing";
            case CRYPTCP_LICENSE_INVALID -> "cryptcp_license";
            case CERTMGR_MISSING -> "certmgr_missing";
            case CADESCOM_MISSING -> "cadescom_missing";
            case TOKEN_OR_CERTIFICATE_ABSENT -> "certificate_absent";
            case PRIVATE_KEY_UNAVAILABLE -> "private_key";
            case CERTIFICATE_EXPIRED -> "expired";
            case CANCELLED -> "cancelled";
            case TIMEOUT -> "timeout";
            case INVALID_SIGNATURE_OUTPUT -> "invalid_output";
            default -> "failed";
        });
        String details = ZnackSanitizer.message(crypto.getMessage());
        return crypto.code() == CryptoProErrorCode.SIGNING_FAILED && !details.isBlank()
                ? message + "\n\n" + i18n.tr("znack.signature.error.details") + ": " + details : message;
    }
}
