package com.sentinelpay.kyc.dto;

import com.sentinelpay.kyc.domain.KycSubmission;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class KycSubmitRequest {

    @NotBlank
    @Email
    private String userEmail;

    @NotBlank
    @Size(max = 255)
    private String fullName;

    /** ISO 3166-1 alpha-2, e.g. "US", "IN". */
    @NotBlank
    @Size(min = 2, max = 2)
    private String countryCode;

    @NotNull
    private KycSubmission.DocumentType documentType;

    @NotBlank
    @Size(max = 100)
    private String documentNumber;

    /** Optional URL/key of an already-uploaded document image. */
    @Size(max = 512)
    private String documentUrl;
}
