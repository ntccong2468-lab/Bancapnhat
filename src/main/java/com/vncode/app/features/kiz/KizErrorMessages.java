package com.vncode.app.features.kiz;

import com.vncode.app.shared.I18nService;

public final class KizErrorMessages {
    private KizErrorMessages(){}
    public static String message(String code){
        String known=switch(code==null?"":code){
            case "KIZ_HTTP_401","KIZ_HTTP_403"->"access";
            case "KIZ_HTTP_400","KIZ_HTTP_422"->"invalid";
            case "KIZ_ORDER_STATUS","KIZ_HTTP_409"->"order_status";
            case "KIZ_HTTP_429"->"rate_limit";
            case "KIZ_RECONCILIATION_REQUIRED"->"uncertain";
            case "KIZ_OTHER_CODE_PRESENT"->"other_code";
            case "KIZ_GTIN_MAPPING_MISSING"->"mapping";
            case "KIZ_INVENTORY_SHORTAGE"->"shortage";
            case "KIZ_PURCHASE_PENDING"->"purchase_pending";
            case "KIZ_NOT_VERIFIED"->"not_verified";
            case "KIZ_CATEGORY_REQUIRED"->"category_required";
            case "KIZ_NOT_IN_CIRCULATION"->"not_circulating";
            case "KIZ_OWNER_MISMATCH","KIZ_OWNER_CHANGED"->"owner";
            case "KIZ_GTIN_MISMATCH","KIZ_GTIN_INVALID"->"gtin";
            case "KIZ_VERIFY_INCOMPLETE","KIZ_VERIFY_DUPLICATE_RESPONSE","KIZ_VERIFY_LOOKUP_FAILED","KIZ_VERIFY_RESPONSE_INVALID"->"verification";
            case "KIZ_CRYPTO_MISSING","KIZ_SERIAL_INVALID","KIZ_FORMAT_INVALID"->"format";
            case "KIZ_FILE_FORMAT_UNSUPPORTED","KIZ_ARCHIVE_FORMAT_UNSUPPORTED","KIZ_NESTED_ARCHIVE_UNSUPPORTED"->"file_format";
            case "KIZ_CODE_COLUMN_REQUIRED","KIZ_JSON_ARRAY_REQUIRED","KIZ_JSON_CODE_REQUIRED","KIZ_TEXT_CELL_REQUIRED","KIZ_ONE_SHEET_REQUIRED"->"column";
            case "KIZ_FILE_SIZE_LIMIT","KIZ_CODE_COUNT_LIMIT","KIZ_ARCHIVE_LIMIT","KIZ_PDF_LIMIT","KIZ_IMAGE_SIZE_LIMIT"->"size";
            case "KIZ_DATAMATRIX_NOT_FOUND","KIZ_IMAGE_INVALID"->"decode";
            case "KIZ_IDENTITY_CONFLICT"->"identity";
            case "KIZ_GTIN_PRODUCT_MISSING"->"product_missing";
            case "SHOP_CREDENTIALS_CHANGED","SHOP_CREDENTIALS_UNAVAILABLE"->"shop";
            case "LICENSE_REQUIRED"->"license";
            default->"failed";
        };
        String safe=code!=null&&code.matches("[A-Z][A-Z0-9_]{1,79}")?code:"KIZ_FAILED";
        return I18nService.getInstance().tr("kiz.error."+known)+" ["+safe+"]";
    }
}
