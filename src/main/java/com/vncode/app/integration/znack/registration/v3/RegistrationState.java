package com.vncode.app.integration.znack.registration.v3;

import java.util.Set;

/** Business outcomes; an accepted HTTP request is never a publication outcome. */
public enum RegistrationState {
    NEW("Mới"), PRODUCT_IMPORTED("Đã nhập sản phẩm"), GTIN_ALLOCATING("Đang xin cấp GTIN"),
    GTIN_RESOLVED("Đã xác định GTIN"), ATTRIBUTES_READY("Đã lưu thông tin thẻ"),
    VALIDATED("Đã kiểm tra"), READY_FOR_SUBMISSION("Sẵn sàng gửi"),
    SUBMISSION_PENDING_CONFIRMATION("Chờ xác nhận gửi"), SUBMITTING("Đang gửi thẻ"),
    SUBMITTED("Đã tiếp nhận yêu cầu"), UNDER_MODERATION("Đang kiểm duyệt"),
    MODERATION_REJECTED("Kiểm duyệt từ chối"), NEEDS_CORRECTION("Cần sửa thẻ"),
    APPROVED_FOR_SIGNING("Đủ điều kiện ký"), SIGNING_PENDING("Chờ duyệt tài liệu ký"),
    SIGNING("Đang ký và gửi chữ ký"), SIGNATURE_SENT("Đã gửi chữ ký; chưa xác minh công bố"),
    VERIFYING_PUBLICATION("Đang xác minh công bố"), PUBLISHED_VERIFIED("Đã xác minh công bố"),
    WB_SYNC_PENDING("Chờ đồng bộ WB"), WB_SYNC_VERIFIED("Đã đọc lại xác minh WB"),
    COMPLETED("Hoàn thành"), VALIDATION_FAILED("Thông tin chưa hợp lệ"),
    AUTH_FAILED("Xác thực thất bại"), PERMISSION_DENIED("Không đủ quyền"),
    RATE_LIMITED("Đang giới hạn tốc độ"), TEMPORARY_FAILURE("Lỗi tạm thời"),
    REMOTE_RESULT_UNKNOWN("Chưa rõ kết quả; cần đối chiếu"), MANUAL_REVIEW_REQUIRED("Cần kiểm tra thủ công");

    private final String label;
    RegistrationState(String label) { this.label = label; }
    public String label() { return label; }
    public boolean published() { return Set.of(PUBLISHED_VERIFIED, WB_SYNC_PENDING, WB_SYNC_VERIFIED, COMPLETED).contains(this); }
    public boolean permits(RegistrationState next) {
        if (next == this) return true;
        if (this == COMPLETED) return false;
        if (this == WB_SYNC_PENDING && next == REMOTE_RESULT_UNKNOWN) return true;
        if (!published() && Set.of(VALIDATION_FAILED, AUTH_FAILED, PERMISSION_DENIED, RATE_LIMITED,
                TEMPORARY_FAILURE, REMOTE_RESULT_UNKNOWN, MANUAL_REVIEW_REQUIRED).contains(next)) return true;
        return switch (this) {
            case NEW -> next == PRODUCT_IMPORTED;
            case PRODUCT_IMPORTED -> Set.of(GTIN_ALLOCATING, GTIN_RESOLVED).contains(next);
            case GTIN_ALLOCATING -> next == GTIN_RESOLVED;
            case GTIN_RESOLVED -> Set.of(ATTRIBUTES_READY, PUBLISHED_VERIFIED, APPROVED_FOR_SIGNING, UNDER_MODERATION, MODERATION_REJECTED).contains(next);
            case ATTRIBUTES_READY -> Set.of(VALIDATED, GTIN_RESOLVED).contains(next);
            case VALIDATED -> Set.of(READY_FOR_SUBMISSION, ATTRIBUTES_READY).contains(next);
            case READY_FOR_SUBMISSION -> Set.of(SUBMISSION_PENDING_CONFIRMATION, ATTRIBUTES_READY).contains(next);
            case SUBMISSION_PENDING_CONFIRMATION -> Set.of(SUBMITTING, ATTRIBUTES_READY).contains(next);
            case SUBMITTING -> next == SUBMITTED;
            case SUBMITTED, UNDER_MODERATION, SIGNATURE_SENT, VERIFYING_PUBLICATION -> Set.of(
                    UNDER_MODERATION, MODERATION_REJECTED, APPROVED_FOR_SIGNING, VERIFYING_PUBLICATION, PUBLISHED_VERIFIED).contains(next);
            case MODERATION_REJECTED -> next == NEEDS_CORRECTION;
            case NEEDS_CORRECTION, VALIDATION_FAILED -> Set.of(GTIN_RESOLVED, ATTRIBUTES_READY).contains(next);
            case APPROVED_FOR_SIGNING -> next == SIGNING_PENDING;
            case SIGNING_PENDING -> Set.of(SIGNING, APPROVED_FOR_SIGNING).contains(next);
            case SIGNING -> next == SIGNATURE_SENT;
            case PUBLISHED_VERIFIED -> next == WB_SYNC_PENDING;
            case WB_SYNC_PENDING -> next == WB_SYNC_VERIFIED;
            case WB_SYNC_VERIFIED -> next == COMPLETED;
            case AUTH_FAILED, PERMISSION_DENIED, RATE_LIMITED, TEMPORARY_FAILURE,
                    REMOTE_RESULT_UNKNOWN, MANUAL_REVIEW_REQUIRED -> Set.of(PRODUCT_IMPORTED, GTIN_RESOLVED, ATTRIBUTES_READY,
                    SUBMITTED, UNDER_MODERATION, MODERATION_REJECTED, APPROVED_FOR_SIGNING,
                    VERIFYING_PUBLICATION, PUBLISHED_VERIFIED, WB_SYNC_VERIFIED).contains(next);
            default -> false;
        };
    }
}
