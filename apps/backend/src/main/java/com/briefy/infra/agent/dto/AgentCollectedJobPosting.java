package com.briefy.infra.agent.dto;

import java.util.List;

public record AgentCollectedJobPosting(
    String source,
    String sourceUrl,
    String companyName,
    String title,
    String position,
    String employmentType,
    String experienceLevel,
    String location,
    String deadline,
    List<String> skills,
    List<String> roles,
    String description,
    String postedAt,
    String contentHash,
    String sourceExternalId,
    String sourceRecordKey,
    String canonicalFingerprint) {}
