package com.briefy.domain.candidatepool.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record CollectedJobPostingData(
    String title,
    String company,
    String source,
    String url,
    String location,
    LocalDate deadline,
    String description,
    String roles,
    String skills,
    String employmentType,
    String experienceLevel,
    String contentHash,
    LocalDateTime publishedAt,
    String sourceRecordKey,
    String sourceExternalId,
    String canonicalFingerprint,
    Boolean descriptionTruncated) {}
