package com.briefy.domain.briefing.client.dto;

import java.util.List;

public record AgentBriefingRequest(Long userId, List<TopicInput> topics, String date, String tone) {

  public record TopicInput(String name, List<String> keywords) {}
}
