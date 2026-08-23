package com.briefy.infra.agent.dto;

import java.util.List;

public record AgentSourcePreflightResponse(
    Boolean reachable,
    Boolean adapterSupported,
    Boolean parserRegistered,
    Integer discoveredCount,
    Integer sampleParsedCount,
    Boolean requiredFieldsValid,
    List<String> warnings,
    String failureCode,
    String failureMessage) {}
