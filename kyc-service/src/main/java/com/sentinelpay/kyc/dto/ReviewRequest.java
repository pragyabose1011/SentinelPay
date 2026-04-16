package com.sentinelpay.kyc.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ReviewRequest {

    /** Required only when rejecting a submission. */
    @Size(max = 500)
    private String reason;
}
