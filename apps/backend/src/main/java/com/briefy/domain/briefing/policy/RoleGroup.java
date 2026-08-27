package com.briefy.domain.briefing.policy;

import java.util.List;
import java.util.Set;

public enum RoleGroup {
  BACKEND(
      List.of(
          "백엔드",
          "backend",
          "back-end",
          "server engineer",
          "서버 개발",
          "java developer",
          "spring developer",
          "api developer",
          "platform backend",
          "플랫폼 서버",
          "서버 엔지니어")),
  FRONTEND(
      List.of(
          "프론트엔드", "frontend", "front-end", "react developer", "web frontend", "웹 프론트", "ui 개발")),
  FULLSTACK(List.of("풀스택", "fullstack", "full-stack", "full stack")),
  DATA(
      List.of(
          "데이터 엔지니어",
          "data engineer",
          "analytics engineer",
          "데이터 분석",
          "data analyst",
          "데이터 사이언티스트",
          "data scientist")),
  AI_ML(
      List.of(
          "ai engineer",
          "ml engineer",
          "machine learning",
          "머신러닝",
          "인공지능",
          "deep learning",
          "딥러닝",
          "nlp engineer")),
  MOBILE(List.of("android", "ios", "mobile developer", "모바일 개발", "모바일 엔지니어")),
  DEVOPS_INFRA(
      List.of(
          "devops",
          "sre",
          "infrastructure engineer",
          "cloud engineer",
          "인프라 엔지니어",
          "platform engineer",
          "reliability engineer")),
  NON_DEV(
      List.of(
          // 마케팅
          "마케팅",
          "marketing",
          // 영업
          "영업 담당",
          "영업 사원",
          "영업직",
          "영업직무",
          "세일즈",
          "영업 대표",
          // 고객 서비스 / CX
          "고객 상담",
          "상담사",
          "고객 서비스",
          "고객센터",
          "콜센터",
          "customer service",
          "customer support",
          "customer success",
          // 인사
          "인사 담당",
          "인사팀",
          "hr manager",
          "human resource",
          // 재무 / 회계
          "재무 담당",
          "재무팀",
          "finance manager",
          "회계 담당",
          "회계사",
          "accounting",
          // 기획 / 콘텐츠 / 법무
          "product manager",
          "서비스 기획",
          "콘텐츠 에디터",
          "content writer",
          "content editor",
          "법무 담당",
          "법무팀",
          "legal counsel",
          // 디자인
          "graphic designer",
          "ui designer",
          "ux designer"));

  private final List<String> keywords;

  RoleGroup(List<String> keywords) {
    this.keywords = keywords;
  }

  public boolean matches(String text) {
    if (text == null || text.isBlank()) return false;
    String lower = text.toLowerCase();
    return keywords.stream().anyMatch(lower::contains);
  }

  /**
   * Posting groups compatible with this group as a user preference. BACKEND users can also apply to
   * FULLSTACK postings; FULLSTACK users can apply to either.
   */
  public Set<RoleGroup> compatiblePostingGroups() {
    return switch (this) {
      case BACKEND -> Set.of(BACKEND, FULLSTACK);
      case FRONTEND -> Set.of(FRONTEND, FULLSTACK);
      case FULLSTACK -> Set.of(FULLSTACK, BACKEND, FRONTEND);
      default -> Set.of(this);
    };
  }
}
