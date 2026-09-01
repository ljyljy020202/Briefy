package com.briefy.domain.briefing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.infra.agent.dto.AgentCandidateJobPosting;
import com.briefy.infra.agent.dto.AgentMatchEvidence;
import com.briefy.infra.agent.dto.AgentScoreBreakdown;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the JSON wire format of the Backend→Agent contract.
 *
 * <p>Legacy fields (preScore, preScoreComputed, candidateType, position, contentHash) must be
 * absent. New fields (rank, isNew, isUrgent, scoreBreakdown, matchEvidence) must be present and
 * correctly serialized.
 */
class BriefingServiceContractTest {

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
  }

  // ── Legacy fields must be absent ─────────────────────────────────────────

  @Test
  void serialized_doesNotContain_preScore() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("preScore")).isFalse();
  }

  @Test
  void serialized_doesNotContain_preScoreComputed() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("preScoreComputed")).isFalse();
  }

  @Test
  void serialized_doesNotContain_candidateType() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("candidateType")).isFalse();
  }

  @Test
  void serialized_doesNotContain_position() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("position")).isFalse();
  }

  @Test
  void serialized_doesNotContain_contentHash() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("contentHash")).isFalse();
  }

  @Test
  void serialized_doesNotContain_postedAt() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("postedAt")).isFalse();
  }

  // ── New required fields must be present ──────────────────────────────────

  @Test
  void serialized_contains_rank() throws Exception {
    AgentCandidateJobPosting posting = samplePostingWithRank(3);
    String json = objectMapper.writeValueAsString(posting);
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("rank")).isTrue();
    assertThat(node.get("rank").asInt()).isEqualTo(3);
  }

  @Test
  void serialized_contains_isNew() throws Exception {
    String json = objectMapper.writeValueAsString(samplePostingWithFlags(true, false));
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("isNew")).isTrue();
    assertThat(node.get("isNew").asBoolean()).isTrue();
  }

  @Test
  void serialized_contains_isUrgent() throws Exception {
    String json = objectMapper.writeValueAsString(samplePostingWithFlags(false, true));
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("isUrgent")).isTrue();
    assertThat(node.get("isUrgent").asBoolean()).isTrue();
  }

  @Test
  void serialized_contains_scoreBreakdown() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("scoreBreakdown")).isTrue();
    JsonNode bd = node.get("scoreBreakdown");
    assertThat(bd.has("roleScore")).isTrue();
    assertThat(bd.has("companyScore")).isTrue();
    assertThat(bd.has("skillScore")).isTrue();
    assertThat(bd.has("experienceScore")).isTrue();
    assertThat(bd.has("industryScore")).isTrue();
    assertThat(bd.has("locationScore")).isTrue();
    assertThat(bd.has("employmentTypeScore")).isTrue();
    assertThat(bd.has("companySizeScore")).isTrue();
    assertThat(bd.has("relevanceScore")).isTrue();
    assertThat(bd.has("exposurePenalty")).isTrue();
    assertThat(bd.has("adjustedScore")).isTrue();
  }

  @Test
  void serialized_contains_matchEvidence() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("matchEvidence")).isTrue();
    JsonNode ev = node.get("matchEvidence");
    assertThat(ev.has("matchedRoles")).isTrue();
    assertThat(ev.has("matchedCompanies")).isTrue();
    assertThat(ev.has("matchedSkills")).isTrue();
    assertThat(ev.has("matchedIndustries")).isTrue();
    assertThat(ev.has("matchedLocations")).isTrue();
    assertThat(ev.has("matchedExperienceLevels")).isTrue();
    assertThat(ev.has("matchedEmploymentTypes")).isTrue();
    assertThat(ev.has("matchedCompanySizes")).isTrue();
  }

  @Test
  void serialized_contains_publishedAt_not_postedAt() throws Exception {
    String json = objectMapper.writeValueAsString(samplePosting());
    JsonNode node = objectMapper.readTree(json);
    assertThat(node.has("publishedAt")).isTrue();
    assertThat(node.has("postedAt")).isFalse();
  }

  // ── Round-trip: isNew and isUrgent both true ──────────────────────────────

  @Test
  void roundTrip_isNewAndIsUrgentBothTrue() throws Exception {
    AgentCandidateJobPosting original = samplePostingWithFlags(true, true);
    String json = objectMapper.writeValueAsString(original);
    AgentCandidateJobPosting restored =
        objectMapper.readValue(json, AgentCandidateJobPosting.class);

    assertThat(restored.isNew()).isTrue();
    assertThat(restored.isUrgent()).isTrue();
  }

  @Test
  void roundTrip_scoreBreakdown_valuesPreserved() throws Exception {
    AgentScoreBreakdown bd = new AgentScoreBreakdown(30, 20, 10, 5, 3, 2, 2, 1, 73, 15, 58);
    AgentCandidateJobPosting posting =
        new AgentCandidateJobPosting(
            1L,
            1,
            "원티드",
            "https://example.com/1",
            "네이버",
            "백엔드 개발자",
            "정규직",
            "신입",
            "서울",
            "2026-09-30",
            List.of("Java", "Spring"),
            List.of("백엔드 개발자"),
            "직무 설명",
            "2026-08-01",
            "2026-08-10",
            true,
            false,
            bd,
            emptyEvidence());

    String json = objectMapper.writeValueAsString(posting);
    AgentCandidateJobPosting restored =
        objectMapper.readValue(json, AgentCandidateJobPosting.class);

    assertThat(restored.scoreBreakdown().roleScore()).isEqualTo(30);
    assertThat(restored.scoreBreakdown().companyScore()).isEqualTo(20);
    assertThat(restored.scoreBreakdown().relevanceScore()).isEqualTo(73);
    assertThat(restored.scoreBreakdown().exposurePenalty()).isEqualTo(15);
    assertThat(restored.scoreBreakdown().adjustedScore()).isEqualTo(58);
  }

  @Test
  void roundTrip_matchEvidence_valuesPreserved() throws Exception {
    AgentMatchEvidence ev =
        new AgentMatchEvidence(
            List.of("백엔드 개발자"),
            List.of("네이버"),
            List.of("Java"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);

    AgentCandidateJobPosting posting =
        new AgentCandidateJobPosting(
            1L,
            1,
            "원티드",
            "https://example.com/1",
            "네이버",
            "백엔드 개발자",
            null,
            null,
            null,
            null,
            List.of(),
            List.of(),
            null,
            null,
            null,
            false,
            false,
            emptyBreakdown(),
            ev);

    String json = objectMapper.writeValueAsString(posting);
    AgentCandidateJobPosting restored =
        objectMapper.readValue(json, AgentCandidateJobPosting.class);

    assertThat(restored.matchEvidence().matchedRoles()).containsExactly("백엔드 개발자");
    assertThat(restored.matchEvidence().matchedCompanies()).containsExactly("네이버");
    assertThat(restored.matchEvidence().matchedSkills()).containsExactly("Java");
    assertThat(restored.matchEvidence().matchedIndustries()).isEmpty();
  }

  // ── adjustedScore invariant ───────────────────────────────────────────────

  @Test
  void scoreBreakdown_adjustedScore_equalsRelevanceMinusPenalty() throws Exception {
    AgentScoreBreakdown bd = new AgentScoreBreakdown(30, 0, 10, 0, 0, 0, 0, 0, 40, 25, 15);
    assertThat(bd.adjustedScore()).isEqualTo(bd.relevanceScore() - bd.exposurePenalty());
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private AgentCandidateJobPosting samplePosting() {
    return new AgentCandidateJobPosting(
        1L,
        1,
        "원티드",
        "https://example.com/1",
        "네이버",
        "백엔드 개발자",
        "정규직",
        "신입",
        "서울",
        "2026-09-30",
        List.of("Java"),
        List.of("백엔드 개발자"),
        "설명",
        "2026-08-01",
        "2026-08-10",
        false,
        false,
        emptyBreakdown(),
        emptyEvidence());
  }

  private AgentCandidateJobPosting samplePostingWithRank(int rank) {
    return new AgentCandidateJobPosting(
        1L,
        rank,
        "원티드",
        "https://example.com/" + rank,
        "네이버",
        "백엔드 개발자",
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null,
        false,
        false,
        emptyBreakdown(),
        emptyEvidence());
  }

  private AgentCandidateJobPosting samplePostingWithFlags(boolean isNew, boolean isUrgent) {
    return new AgentCandidateJobPosting(
        1L,
        1,
        "원티드",
        "https://example.com/1",
        "네이버",
        "백엔드 개발자",
        null,
        null,
        null,
        null,
        List.of(),
        List.of(),
        null,
        null,
        null,
        isNew,
        isUrgent,
        emptyBreakdown(),
        emptyEvidence());
  }

  private AgentScoreBreakdown emptyBreakdown() {
    return new AgentScoreBreakdown(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
  }

  private AgentMatchEvidence emptyEvidence() {
    return new AgentMatchEvidence(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        null);
  }
}
