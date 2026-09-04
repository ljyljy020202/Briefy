package com.briefy.domain.briefing.recommendation;

import com.briefy.domain.briefing.policy.ExperienceParser;
import com.briefy.domain.briefing.policy.ExperiencePolicy;
import com.briefy.domain.briefing.policy.JobRolePolicy;
import com.briefy.domain.briefing.policy.ParsedExperience;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.analysis.PostingScope;
import com.briefy.domain.candidatepool.entity.analysis.RecruitmentType;
import com.briefy.domain.company.entity.Company;
import com.briefy.global.util.UrlUtils;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Computes the user preference relevance score and match evidence for a job posting.
 *
 * <p>Only preference-based signals are included (role, company, skill, experience, industry,
 * location, employment type, company size). Time-based and editorial signals (recency, urgency
 * bonus, source type bonus) are intentionally excluded so that {@link
 * ScoreBreakdown#relevanceScore} remains a pure measure of how well the posting matches the user's
 * stated preferences.
 *
 * <p>This class is stateless; all methods are static.
 */
public final class RelevanceScorer {

  // ── Preference weights ────────────────────────────────────────────────────
  static final int SCORE_ROLE_MATCH = 25;
  static final int SCORE_ROLE_BROAD_IT_MATCH = 15;
  static final int SCORE_TARGET_COMPANY = 20;
  static final int SCORE_SKILL = 5;
  static final int SCORE_SKILLS_MAX = 25;
  static final int SCORE_EXPERIENCE = 15;
  static final int SCORE_INDUSTRY = 15;
  static final int SCORE_LOCATION = 10;
  static final int SCORE_EMPLOYMENT_TYPE = 10;
  static final int SCORE_COMPANY_SIZE = 15;

  // ── Classification-derived editorial bonus (대기업 공채) ──────────────────────
  // 분류 경로(score with eligibility)에서만 적용. 순수 키워드 스코어에는 반영하지 않는다.
  static final int SCORE_OPEN_RECRUITMENT = 10; // postingScope=OPEN_RECRUITMENT
  static final int SCORE_NEW_GRAD_HIRE = 5; // recruitmentType=NEW_GRAD_HIRE + 신입 사용자
  static final int SCORE_OPEN_RECRUITMENT_MAX = 12; // 두 가산점 합산 상한

  // ── Official source bonus ─────────────────────────────────────────────────
  // 공식 채용 사이트(애그리게이터 jasoseol/saramin·fixture 제외) 공고에 소폭 가산.
  // 모든 스코어 경로(SHADOW/ENFORCE)에 동일하게 적용된다.
  static final int SCORE_OFFICIAL_SOURCE = 5;
  private static final java.util.Set<String> AGGREGATOR_SOURCES =
      java.util.Set.of("jasoseol", "saramin", "fixture");

  // ── Exposure penalty thresholds ───────────────────────────────────────────
  static final int EXPOSURE_PENALTY_YESTERDAY = 25; // exposed within 1 day
  static final int EXPOSURE_PENALTY_RECENT = 15; // 2–3 days ago
  static final int EXPOSURE_PENALTY_STALE = 10; // 4–6 days ago

  private RelevanceScorer() {}

  /** Pair of scoring result and matched evidence. */
  public record ScoringResult(ScoreBreakdown breakdown, MatchEvidence evidence) {}

  /**
   * Computes the relevance score and match evidence for {@code posting} against {@code preference}.
   *
   * <p>Returns a {@link ScoringResult} whose {@link ScoreBreakdown} has {@code exposurePenalty=0}
   * and {@code adjustedScore=relevanceScore}. Callers must call {@link
   * ScoreBreakdown#withExposurePenalty(int)} to apply the exposure adjustment.
   */
  public static ScoringResult score(JobPosting posting, Map<String, Object> preference) {
    List<String> prefRoles = extractList(preference, "roles");
    List<String> prefCompanies = extractList(preference, "companies");
    List<String> prefSkills = extractList(preference, "skills");
    List<String> prefLocations = extractList(preference, "locations");
    List<String> prefExpLevels = extractList(preference, "experienceLevels");
    List<String> prefEmpTypes = extractList(preference, "employmentTypes");
    List<String> prefIndustries = extractList(preference, "industries");
    List<String> prefCompanySizes = extractList(preference, "companySizes");

    // ── 1. Role ──────────────────────────────────────────────────────────────
    int roleScore = 0;
    List<String> matchedRoles = List.of();
    JobRolePolicy.Verdict roleVerdict =
        JobRolePolicy.evaluate(prefRoles, posting.getTitle(), posting.getRoles());
    if (roleVerdict == JobRolePolicy.Verdict.MATCH) {
      roleScore = SCORE_ROLE_MATCH;
      matchedRoles = intersectRoles(prefRoles, posting.getTitle(), posting.getRoles());
    }

    // ── 2. Company ───────────────────────────────────────────────────────────
    int companyScore = 0;
    List<String> matchedCompanies = List.of();
    String companyLower = posting.getCompany() != null ? posting.getCompany().toLowerCase() : "";
    for (String prefCo : prefCompanies) {
      if (isCompanyMatch(companyLower, prefCo.toLowerCase())) {
        companyScore = SCORE_TARGET_COMPANY;
        matchedCompanies = List.of(posting.getCompany());
        break;
      }
    }

    // ── 3. Skills ────────────────────────────────────────────────────────────
    int skillScore = 0;
    List<String> matchedSkills = new ArrayList<>();
    String skillsStr = posting.getSkills() != null ? posting.getSkills().toLowerCase() : "";
    for (String skill : prefSkills) {
      if (skillsStr.contains(skill.toLowerCase())) {
        matchedSkills.add(skill);
        skillScore += SCORE_SKILL;
        if (skillScore >= SCORE_SKILLS_MAX) break;
      }
    }

    // ── 4. Experience ────────────────────────────────────────────────────────
    int experienceScore = 0;
    List<String> matchedExpLevels = List.of();
    ParsedExperience parsedExp = ExperienceParser.parse(posting.getExperienceLevel());
    ExperiencePolicy.Verdict expVerdict = ExperiencePolicy.evaluate(prefExpLevels, parsedExp);
    if (expVerdict == ExperiencePolicy.Verdict.PASS_FULL) {
      experienceScore = SCORE_EXPERIENCE;
      if (posting.getExperienceLevel() != null && !posting.getExperienceLevel().isBlank()) {
        matchedExpLevels = List.of(posting.getExperienceLevel());
      }
    }

    // ── 5. Industry ──────────────────────────────────────────────────────────
    int industryScore = 0;
    List<String> matchedIndustries = new ArrayList<>();
    Company linkedCompany = posting.getLinkedCompany();
    if (!prefIndustries.isEmpty()
        && linkedCompany != null
        && linkedCompany.getIndustryCodes() != null
        && !linkedCompany.getIndustryCodes().isBlank()) {
      List<String> codes =
          Arrays.stream(linkedCompany.getIndustryCodes().split(",")).map(String::trim).toList();
      for (String prefIndustry : prefIndustries) {
        if (codes.stream().anyMatch(c -> c.equalsIgnoreCase(prefIndustry))) {
          industryScore = SCORE_INDUSTRY;
          matchedIndustries.add(prefIndustry);
          break;
        }
      }
    }

    // ── 6. Location ──────────────────────────────────────────────────────────
    int locationScore = 0;
    List<String> matchedLocations = new ArrayList<>();
    String location = posting.getLocation() != null ? posting.getLocation() : "";
    for (String prefLoc : prefLocations) {
      if (location.contains(prefLoc) || prefLoc.contains(location)) {
        locationScore = SCORE_LOCATION;
        matchedLocations.add(prefLoc);
        break;
      }
    }

    // ── 7. Employment type ───────────────────────────────────────────────────
    int empTypeScore = 0;
    List<String> matchedEmpTypes = List.of();
    String empType = posting.getEmploymentType() != null ? posting.getEmploymentType() : "";
    for (String prefEmp : prefEmpTypes) {
      if (empType.equalsIgnoreCase(prefEmp)) {
        empTypeScore = SCORE_EMPLOYMENT_TYPE;
        matchedEmpTypes = List.of(posting.getEmploymentType());
        break;
      }
    }

    // ── 8. Company size ──────────────────────────────────────────────────────
    int companySizeScore = 0;
    List<String> matchedCompanySizes = List.of();
    if (!prefCompanySizes.isEmpty()
        && linkedCompany != null
        && linkedCompany.getCompanySize() != null
        && !linkedCompany.getCompanySize().isBlank()) {
      for (String prefSize : prefCompanySizes) {
        if (linkedCompany.getCompanySize().equalsIgnoreCase(prefSize)) {
          companySizeScore = SCORE_COMPANY_SIZE;
          matchedCompanySizes = List.of(linkedCompany.getCompanySize());
          break;
        }
      }
    }

    // ── 9. Official source bonus ─────────────────────────────────────────────
    int sourceScore = isOfficialSource(posting.getSource()) ? SCORE_OFFICIAL_SOURCE : 0;

    ScoreBreakdown breakdown =
        ScoreBreakdown.ofRelevance(
            roleScore,
            companyScore,
            skillScore,
            experienceScore,
            industryScore,
            locationScore,
            empTypeScore,
            companySizeScore,
            0,
            sourceScore);

    MatchEvidence evidence =
        MatchEvidence.of(
            matchedRoles,
            matchedCompanies,
            List.copyOf(matchedSkills),
            List.copyOf(matchedIndustries),
            List.copyOf(matchedLocations),
            matchedExpLevels,
            matchedEmpTypes,
            matchedCompanySizes);

    return new ScoringResult(breakdown, evidence);
  }

  /**
   * Classification-aware score overload.
   *
   * <p>When {@code eligibility} is non-null and not deferred, the role score and experience score
   * are derived from the classification result rather than keyword matching:
   *
   * <ul>
   *   <li>DIRECT_MATCH → roleScore = SCORE_ROLE_MATCH (25)
   *   <li>BROAD_IT_MATCH → roleScore = SCORE_ROLE_BROAD_IT_MATCH (15)
   *   <li>Experience FULL → experienceScore = SCORE_EXPERIENCE (15); PARTIAL/EXCLUDED/UNKNOWN → 0
   * </ul>
   *
   * <p>Additionally applies a classification-derived editorial bonus for large-enterprise open
   * recruitment (see {@link #computeOpenRecruitmentBonus}): OPEN_RECRUITMENT scope earns a flat
   * bonus, and NEW_GRAD_HIRE earns an extra bonus <em>only when the user is a new-grad</em>
   * (gating), capped at {@link #SCORE_OPEN_RECRUITMENT_MAX}.
   *
   * <p>All other dimensions (company, skills, industry, location, empType, companySize) retain the
   * keyword-based scores. Returns the same result as {@link #score(JobPosting, Map)} when
   * eligibility is null or deferred.
   */
  public static ScoringResult score(
      JobPosting posting, Map<String, Object> preference, AnalysisEligibility eligibility) {

    ScoringResult keywordResult = score(posting, preference);
    if (eligibility == null || eligibility.isDeferred()) {
      return keywordResult;
    }

    int classRoleScore =
        switch (eligibility.roleMatch()) {
          case DIRECT_MATCH -> SCORE_ROLE_MATCH;
          case BROAD_IT_MATCH -> SCORE_ROLE_BROAD_IT_MATCH;
          case MISMATCH, UNKNOWN -> 0;
        };

    int classExpScore =
        eligibility.experienceMatch() == ExperienceMatchType.FULL ? SCORE_EXPERIENCE : 0;

    int openRecruitmentScore = computeOpenRecruitmentBonus(preference, eligibility);

    ScoreBreakdown orig = keywordResult.breakdown();
    ScoreBreakdown updated =
        ScoreBreakdown.ofRelevance(
            classRoleScore,
            orig.companyScore(),
            orig.skillScore(),
            classExpScore,
            orig.industryScore(),
            orig.locationScore(),
            orig.employmentTypeScore(),
            orig.companySizeScore(),
            openRecruitmentScore,
            orig.sourceScore());

    MatchEvidence origEv = keywordResult.evidence();
    MatchEvidence updatedEv =
        new MatchEvidence(
            origEv.matchedRoles(),
            origEv.matchedCompanies(),
            origEv.matchedSkills(),
            origEv.matchedIndustries(),
            origEv.matchedLocations(),
            origEv.matchedExperienceLevels(),
            origEv.matchedEmploymentTypes(),
            origEv.matchedCompanySizes(),
            eligibility.roleMatch().name());

    return new ScoringResult(updated, updatedEv);
  }

  /**
   * Computes the large-enterprise open-recruitment editorial bonus from the classification result.
   *
   * <ul>
   *   <li>postingScope = OPEN_RECRUITMENT → +{@link #SCORE_OPEN_RECRUITMENT}
   *   <li>recruitmentType = NEW_GRAD_HIRE <b>AND the user is a new-grad</b> → +{@link
   *       #SCORE_NEW_GRAD_HIRE} (gated so experienced users are not pushed new-grad 공채)
   * </ul>
   *
   * <p>The sum is capped at {@link #SCORE_OPEN_RECRUITMENT_MAX}.
   */
  static int computeOpenRecruitmentBonus(
      Map<String, Object> preference, AnalysisEligibility eligibility) {
    int bonus = 0;
    if (eligibility.postingScope() == PostingScope.OPEN_RECRUITMENT) {
      bonus += SCORE_OPEN_RECRUITMENT;
    }
    if (eligibility.recruitmentType() == RecruitmentType.NEW_GRAD_HIRE
        && ExperiencePolicy.isNewGradUser(extractList(preference, "experienceLevels"))) {
      bonus += SCORE_NEW_GRAD_HIRE;
    }
    return Math.min(bonus, SCORE_OPEN_RECRUITMENT_MAX);
  }

  /**
   * True when the posting comes from an official company career site.
   *
   * <p>Aggregators (jasoseol, saramin) and test fixtures are excluded; every other non-blank source
   * (e.g. {@code naver_careers}, {@code toss_careers}, {@code company_*}) is treated as official.
   */
  static boolean isOfficialSource(String source) {
    if (source == null || source.isBlank()) return false;
    return !AGGREGATOR_SOURCES.contains(source.toLowerCase());
  }

  /**
   * Computes the exposure penalty for {@code url} based on how recently it appeared in the user's
   * briefings.
   *
   * <p>The URL is canonicalized before lookup so that query-string variants match the stored key.
   * Returns 0 when the URL has no exposure within the lookback window.
   */
  public static int computeExposurePenalty(
      String url, Map<String, LocalDate> exposureMap, LocalDate today) {
    String canonical = UrlUtils.canonicalize(url);
    if (canonical == null) return 0;
    LocalDate lastExposed = exposureMap.get(canonical);
    if (lastExposed == null) return 0;

    long daysSince = ChronoUnit.DAYS.between(lastExposed, today);
    if (daysSince <= 1) return EXPOSURE_PENALTY_YESTERDAY;
    if (daysSince <= 3) return EXPOSURE_PENALTY_RECENT;
    if (daysSince <= 6) return EXPOSURE_PENALTY_STALE;
    return 0;
  }

  /**
   * True when posting company matches a preference entry using prefix-aware comparison.
   *
   * <p>Both arguments must already be lowercase. Matches when either string is a prefix of the
   * other so "토스" matches "토스인컴", "토스뱅크", etc.
   */
  static boolean isCompanyMatch(String postingCompanyLower, String prefCompanyLower) {
    if (postingCompanyLower.isEmpty() || prefCompanyLower.isEmpty()) return false;
    return postingCompanyLower.equals(prefCompanyLower)
        || postingCompanyLower.startsWith(prefCompanyLower)
        || prefCompanyLower.startsWith(postingCompanyLower);
  }

  @SuppressWarnings("unchecked")
  static List<String> extractList(Map<String, Object> pref, String key) {
    Object val = pref.get(key);
    if (val instanceof List<?> list) {
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return List.of();
  }

  /**
   * Returns the subset of user role preferences that were responsible for a MATCH verdict. Used to
   * populate {@link MatchEvidence#matchedRoles()}.
   */
  private static List<String> intersectRoles(
      List<String> prefRoles, String postingTitle, String postingRolesJson) {
    String text = JobRolePolicy.buildPrimaryText(postingTitle, postingRolesJson);
    return prefRoles.stream().filter(r -> text.contains(r.toLowerCase())).toList();
  }
}
