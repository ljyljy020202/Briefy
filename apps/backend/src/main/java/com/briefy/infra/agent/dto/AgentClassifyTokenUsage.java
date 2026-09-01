package com.briefy.infra.agent.dto;

public record AgentClassifyTokenUsage(int promptTokens, int completionTokens, int totalTokens) {}
