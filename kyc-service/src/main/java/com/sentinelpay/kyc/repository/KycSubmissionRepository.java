package com.sentinelpay.kyc.repository;

import com.sentinelpay.kyc.domain.KycSubmission;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface KycSubmissionRepository extends JpaRepository<KycSubmission, UUID> {

    Optional<KycSubmission> findTopByUserIdOrderByCreatedAtDesc(UUID userId);

    List<KycSubmission> findByUserId(UUID userId);

    Page<KycSubmission> findByStatusOrderByCreatedAtAsc(KycSubmission.Status status, Pageable pageable);

    boolean existsByUserIdAndStatus(UUID userId, KycSubmission.Status status);
}
