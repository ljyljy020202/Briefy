package com.briefy.domain.candidatepool.service;

import com.briefy.domain.candidatepool.entity.JobPosting;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 분류 입력 스냅샷의 SHA-256 해시를 계산하는 유틸리티.
 *
 * <p>Spring이 해시 권위를 가진다. Agent는 요청 hash를 에코하며 직접 계산하지 않는다.
 *
 * <p>정규화 규칙:
 *
 * <ul>
 *   <li>문자열: null → "", toLowerCase, 연속 whitespace → " ", strip
 *   <li>roles 배열: JSON array 파싱 → 각 요소 정규화 → 알파벳순 정렬 → "," 연결
 *   <li>descriptionTruncated: null → "?", true → "1", false → "0"
 *   <li>수집 시각, 소스 식별자 등 의미 없는 필드는 포함하지 않는다
 * </ul>
 *
 * <p>입력 전처리 방식 변경 시 classifierVersion을 갱신한다.
 */
public final class AnalysisInputHashCalculator {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

  private AnalysisInputHashCalculator() {}

  public static String compute(JobPosting posting, Boolean descriptionTruncated) {
    String title = norm(posting.getTitle());
    String desc = norm(posting.getDescription());
    String roles = normRoles(posting.getRoles());
    String exp = norm(posting.getExperienceLevel());
    String emp = norm(posting.getEmploymentType());
    String hasDesc =
        (posting.getDescription() != null && !posting.getDescription().isBlank()) ? "1" : "0";
    String trunc = descriptionTruncated == null ? "?" : (descriptionTruncated ? "1" : "0");

    String raw =
        title + "|" + desc + "|" + roles + "|" + exp + "|" + emp + "|" + hasDesc + "|" + trunc;
    return sha256hex(raw);
  }

  private static String norm(String s) {
    if (s == null || s.isBlank()) return "";
    return s.trim().toLowerCase().replaceAll("\\s+", " ");
  }

  private static String normRoles(String rolesJson) {
    if (rolesJson == null || rolesJson.isBlank()) return "";
    try {
      List<String> parsed = MAPPER.readValue(rolesJson, STRING_LIST_TYPE);
      return parsed.stream()
          .map(AnalysisInputHashCalculator::norm)
          .filter(s -> !s.isEmpty())
          .sorted()
          .collect(Collectors.joining(","));
    } catch (Exception e) {
      // JSON 파싱 실패 시 원문 정규화로 폴백
      return norm(rolesJson);
    }
  }

  private static String sha256hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
