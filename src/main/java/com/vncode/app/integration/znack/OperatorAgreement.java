package com.vncode.app.integration.znack;

import java.util.Objects;

/** True API 3.7.1: CRM status and EDO status are different state machines. */
public record OperatorAgreement(String id, String name, String fileName, String createdAt,
                                int status, int signType, Integer edoStatus, String edoDocumentId) {
    public OperatorAgreement {
        if (id == null || !id.matches("[0-9]{1,64}") || id.chars().allMatch(c -> c == '0')
                || status < 0 || status > 2 || signType < 1 || signType > 4)
            throw new IllegalArgumentException("GS1_AGREEMENT_RESPONSE_INVALID");
        name = safe(name); fileName = safe(fileName); createdAt = safe(createdAt);
        edoDocumentId = Objects.requireNonNullElse(edoDocumentId, "");
        if (!edoDocumentId.isEmpty() && !edoDocumentId.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("GS1_AGREEMENT_RESPONSE_INVALID");
    }
    public OperatorAgreement(String id, String name, String fileName, String createdAt, int status, int signType, Integer edoStatus) {
        this(id, name, fileName, createdAt, status, signType, edoStatus, "");
    }
    private static String safe(String value) {
        String text = Objects.requireNonNullElse(value, "");
        if (text.length() > 500 || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("GS1_AGREEMENT_RESPONSE_INVALID");
        return text;
    }
    public boolean awaitingSignature() { return status == 1 && (signType == 1 || signType == 2); }
    public boolean sellerSigned() { return status == 2; }
    public boolean operatorPending() { return sellerSigned() && signType == 1 && !Integer.valueOf(4).equals(edoStatus); }
    public OperatorAgreement withEdoStatus(int value, String edoId) { return new OperatorAgreement(id, name, fileName, createdAt, status, signType, value, edoId); }
    public String title() { return !name.isBlank() ? name : !fileName.isBlank() ? fileName : id; }
    @Override public String toString() { return "OperatorAgreement[status=" + status + ", signType=" + signType + "]"; }
}
