package com.tuandev.fbsbarcode.integration.wb;

import java.util.Collections;
import java.util.List;

public class WbOrderMetaDetailsResponse {
    private List<OrderMetadata> orders;

    public List<OrderMetadata> getOrders() {
        return orders == null ? Collections.emptyList() : orders;
    }

    public static class OrderMetadata {
        private Long orderId;
        private Long id;
        private List<String> requiredMeta;
        private List<String> optionalMeta;
        private List<MetaDetail> metaDetails;

        public Long getOrderId() {
            return orderId == null ? id : orderId;
        }

        public List<String> getRequiredMeta() {
            return requiredMeta == null ? Collections.emptyList() : requiredMeta;
        }

        public List<String> getOptionalMeta() {
            return optionalMeta == null ? Collections.emptyList() : optionalMeta;
        }

        public List<MetaDetail> getMetaDetails() {
            return metaDetails == null ? Collections.emptyList() : metaDetails;
        }
    }

    public static class MetaDetail {
        private String key;
        private String name;
        private String type;
        private String requirementType;
        private Boolean required;
        private Boolean filled;
        private Boolean valid;
        private String status;
        private String value;
        private String error;

        public String getKey() {
            return key == null || key.isBlank() ? name : key;
        }

        public String getType() {
            return type;
        }

        public String getRequirementType() {
            return requirementType;
        }

        public Boolean getRequired() {
            return required;
        }

        public Boolean getFilled() {
            return filled;
        }

        public Boolean getValid() {
            return valid;
        }

        public String getStatus() {
            return status;
        }

        public String getValue() {
            return value;
        }

        public String getError() {
            return error;
        }
    }
}
