package com.briefy.infra.agent.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AgentCollectionOptionsSerializationTest {

  private final ObjectMapper om = new ObjectMapper();

  @Test
  void serializes_exactlyFiveExpectedFields() throws Exception {
    AgentCollectionOptions opts = new AgentCollectionOptions(7, 300, 100, 100, 500);
    JsonNode node = om.readTree(om.writeValueAsString(opts));

    assertThat(node.has("lookbackDays")).isTrue();
    assertThat(node.has("discoveryLimitPerSource")).isTrue();
    assertThat(node.has("detailFetchLimitPerSource")).isTrue();
    assertThat(node.has("maxResultsPerSource")).isTrue();
    assertThat(node.has("maxTotalResults")).isTrue();
  }

  @Test
  void doesNotSerialize_removedFields() throws Exception {
    AgentCollectionOptions opts = new AgentCollectionOptions(7, 300, 100, 100, 500);
    JsonNode node = om.readTree(om.writeValueAsString(opts));

    assertThat(node.has("maxItemsPerSource")).isFalse();
    assertThat(node.has("deadlineWithinDays")).isFalse();
  }

  @Test
  void values_roundTrip() throws Exception {
    AgentCollectionOptions opts = new AgentCollectionOptions(5, 200, 80, 80, 400);
    JsonNode node = om.readTree(om.writeValueAsString(opts));

    assertThat(node.get("lookbackDays").asInt()).isEqualTo(5);
    assertThat(node.get("discoveryLimitPerSource").asInt()).isEqualTo(200);
    assertThat(node.get("detailFetchLimitPerSource").asInt()).isEqualTo(80);
    assertThat(node.get("maxResultsPerSource").asInt()).isEqualTo(80);
    assertThat(node.get("maxTotalResults").asInt()).isEqualTo(400);
  }

  @Test
  void agentStats_deserializesFromNewFields() throws Exception {
    String json =
        """
        {"discoveredCount":100,"fetchedCount":50,"parsedCount":45,
         "duplicateCount":5,"filteredCount":2,"truncatedCount":10,"finalCount":28}
        """;
    AgentCollectionStats stats = om.readValue(json, AgentCollectionStats.class);

    assertThat(stats.discoveredCount()).isEqualTo(100);
    assertThat(stats.fetchedCount()).isEqualTo(50);
    assertThat(stats.parsedCount()).isEqualTo(45);
    assertThat(stats.duplicateCount()).isEqualTo(5);
    assertThat(stats.filteredCount()).isEqualTo(2);
    assertThat(stats.truncatedCount()).isEqualTo(10);
    assertThat(stats.finalCount()).isEqualTo(28);
  }
}
