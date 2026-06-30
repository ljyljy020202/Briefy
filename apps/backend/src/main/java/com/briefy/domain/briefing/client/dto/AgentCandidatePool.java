package com.briefy.domain.briefing.client.dto;

import java.util.List;

public record AgentCandidatePool(
    List<AgentCandidateJobPosting> jobPostings,
    List<Object> companyIssues,
    List<Object> industryIssues) {}
