package com.tuandev.fbsbarcode.shared;

import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProErrorCode;
import com.tuandev.fbsbarcode.integration.znack.signature.CryptoProException;
import com.tuandev.fbsbarcode.integration.znack.GtinNormalizer;
import com.tuandev.fbsbarcode.integration.znack.ZnackErrorMessages;
import com.tuandev.fbsbarcode.integration.znack.ZnackSafety;
import com.tuandev.fbsbarcode.integration.znack.ZnackSanitizer;

import java.text.MessageFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats exception stacktraces and raw backend error messages into localized i18n user alerts.
 */
public final class FriendlyErrorService {

    private static final Pattern NOT_ENOUGH_KIZ = Pattern.compile(
            "Not enough available KIZ for GTIN ([0-9]{14}): required (\\d+), available (\\d+)");

    private static final Pattern UNMAPPED_ORDER = Pattern.compile(
            "Order thứ (\\d+) cần KIZ nhưng nmId chưa được map: (.+)");

    private static final Pattern WB_DELIVER_BLOCKED = Pattern.compile(
            "WB не разрешает передачу поставки: не заполнены или неверны IMEI/UIN/SGTIN/GTIN для заказов (.+)");

    private FriendlyErrorService() {
    }

    public static String format(Throwable error) {
        if (error == null) return I18nService.getInstance().tr("znack.signature.error.failed");
        if (error instanceof CryptoProException crypto) {
            return formatCryptoPro(crypto);
        }
        String message = error.getMessage() == null ? "" : error.getMessage().trim();
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
