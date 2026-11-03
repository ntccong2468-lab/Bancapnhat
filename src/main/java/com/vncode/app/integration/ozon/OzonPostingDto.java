package com.vncode.app.integration.ozon;

import java.util.List;

public record OzonPostingDto(
        String postingNumber,
        String orderId,
        String orderNumber,
        String status,
        String substatus,
        String warehouseId,
        String shipmentAt,
        String inProcessAt,
        String lowerBarcode,
        String upperBarcode,
        OzonRequirements requirements,
        List<String> availableActions,
        boolean shipAvailable,
        List<OzonPostingItemDto> items,
        String trackingNumber,
        String trackingOwner) {
    /** Compatibility for locally created and older persisted postings with no tracking observation. */
    public OzonPostingDto(String postingNumber, String orderId, String orderNumber, String status, String substatus,
            String warehouseId, String shipmentAt, String inProcessAt, String lowerBarcode, String upperBarcode,
            OzonRequirements requirements, List<String> availableActions, boolean shipAvailable, List<OzonPostingItemDto> items) {
        this(postingNumber, orderId, orderNumber, status, substatus, warehouseId, shipmentAt, inProcessAt,
                lowerBarcode, upperBarcode, requirements, availableActions, shipAvailable, items, null, null);
    }
    public OzonPostingDto {
        postingNumber = OzonApiClient.requireExternalId(postingNumber, "posting number");
        orderId = safe(orderId, 256);
        orderNumber = safe(orderNumber, 256);
        status = safe(status, 128);
        if (status.isEmpty()) status = "unknown";
        substatus = safe(substatus, 128);
        warehouseId = safe(warehouseId, 256);
        shipmentAt = safe(shipmentAt, 80);
        inProcessAt = safe(inProcessAt, 80);
        lowerBarcode = safe(lowerBarcode, 512);
        upperBarcode = safe(upperBarcode, 512);
        requirements = requirements == null ? new OzonRequirements(List.of(), List.of(), List.of()) : requirements;
        availableActions = availableActions == null ? List.of() : availableActions.stream()
                .filter(value -> value != null && value.matches("[A-Za-z0-9_./-]{1,128}"))
                .distinct()
                .limit(100)
                .toList();
        items = items == null ? List.of() : List.copyOf(items);
        if (trackingNumber != null) {
            if (trackingNumber.length() > 512 || trackingNumber.codePoints().anyMatch(Character::isISOControl)) trackingNumber = null;
            else trackingNumber = trackingNumber.strip();
        }
        if (trackingOwner == null || !trackingOwner.matches("[0-9a-f]{64}")) trackingOwner = null;
    }

    /** Bind a tracking observation to the exact credentials that produced the response. */
    public OzonPostingDto withTrackingOwner(String owner) {
        return new OzonPostingDto(postingNumber, orderId, orderNumber, status, substatus, warehouseId, shipmentAt,
                inProcessAt, lowerBarcode, upperBarcode, requirements, availableActions, shipAvailable, items,
                trackingNumber, owner);
    }

    public boolean isSinglePackageSupported() {
        return !items.isEmpty() && items.stream().allMatch(item -> item.quantity() > 0);
    }

    public boolean canShip() {
        return shipAvailable || availableActions.stream().anyMatch(action -> switch (action.toLowerCase(java.util.Locale.ROOT)) {
            case "ship", "fbs_ship", "posting_ship", "ship_available" -> true;
            default -> false;
        });
    }

    private static String safe(String value, int maximum) {
        if (value == null) return "";
        String result = value.replaceAll("\\p{Cntrl}", " ").strip();
        return result.length() <= maximum ? result : result.substring(0, maximum);
    }
}
