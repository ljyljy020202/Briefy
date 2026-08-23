package com.briefy.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.briefy.domain.company.dto.admin.CompanySourceAdminResponse;
import com.briefy.domain.company.dto.admin.CompanySourcePreflightResponse;
import com.briefy.domain.company.dto.admin.UpdateCompanySourceRequest;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.entity.CompanySource;
import com.briefy.domain.company.repository.CompanyRepository;
import com.briefy.domain.company.repository.CompanySourceRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentSourcePreflightResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanySourcePreflightServiceTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private CompanySourceRepository companySourceRepository;
  @Mock private AgentClient agentClient;

  private CompanySourceAdminService service;

  @BeforeEach
  void setUp() {
    service =
        new CompanySourceAdminService(
            companyRepository, companySourceRepository, new ObjectMapper(), agentClient);
  }

  // ── preflight success → VERIFIED ─────────────────────────────────────────

  @Test
  void preflight_success_setsVerifiedAndLastVerifiedAt() {
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    AgentSourcePreflightResponse agentResp =
        new AgentSourcePreflightResponse(true, true, false, 5, 1, true, List.of(), null, null);
    when(agentClient.preflight(any())).thenReturn(agentResp);

    CompanySourcePreflightResponse result = service.runPreflight(1L);

    assertThat(result.verificationStatus()).isEqualTo("VERIFIED");
    assertThat(result.lastVerifiedAt()).isNotNull();
    assertThat(result.failureCode()).isNull();
    assertThat(source.getVerificationStatus()).isEqualTo("VERIFIED");
    assertThat(source.getLastVerifiedAt()).isNotNull();
  }

  @Test
  void preflight_failure_setsFailed_andClearsLastVerifiedAt() {
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    AgentSourcePreflightResponse agentResp =
        new AgentSourcePreflightResponse(
            false, true, false, 0, 0, false, List.of(), "URL_UNREACHABLE", "HTTP 404");
    when(agentClient.preflight(any())).thenReturn(agentResp);

    CompanySourcePreflightResponse result = service.runPreflight(1L);

    assertThat(result.verificationStatus()).isEqualTo("FAILED");
    assertThat(result.lastVerifiedAt()).isNull();
    assertThat(result.failureCode()).isEqualTo("URL_UNREACHABLE");
    assertThat(source.getVerificationStatus()).isEqualTo("FAILED");
    assertThat(source.getLastVerifiedAt()).isNull();
  }

  @Test
  void preflight_agentTimeout_recordsFailed() {
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    when(agentClient.preflight(any()))
        .thenThrow(new BusinessException(ErrorCode.AGENT_SERVER_ERROR));

    CompanySourcePreflightResponse result = service.runPreflight(1L);

    assertThat(source.getVerificationStatus()).isEqualTo("FAILED");
    assertThat(result.verificationStatus()).isEqualTo("FAILED");
  }

  @Test
  void preflight_agentError_recordsFailed() {
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "RSS", null);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    when(agentClient.preflight(any())).thenThrow(new RuntimeException("connection refused"));

    CompanySourcePreflightResponse result = service.runPreflight(1L);

    assertThat(source.getVerificationStatus()).isEqualTo("FAILED");
  }

  @Test
  void preflight_sourceNotFound_throws() {
    when(companySourceRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.runPreflight(99L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMPANY_SOURCE_NOT_FOUND);
  }

  // ── activate ─────────────────────────────────────────────────────────────

  @Test
  void activate_verified_succeeds() {
    Company company = activeCompany();
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    source.markVerified(java.time.LocalDateTime.now(), "ok");
    setCompany(source, company);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    CompanySourceAdminResponse response = service.activateSource(1L);

    assertThat(response.status()).isEqualTo("ACTIVE");
    assertThat(source.getStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void activate_unverified_throws() {
    Company company = activeCompany();
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    setCompany(source, company);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    assertThatThrownBy(() -> service.activateSource(1L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SOURCE_NOT_VERIFIED);
  }

  @Test
  void activate_failed_throws() {
    Company company = activeCompany();
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    source.markVerificationFailed("unreachable");
    setCompany(source, company);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    assertThatThrownBy(() -> service.activateSource(1L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SOURCE_NOT_VERIFIED);
  }

  @Test
  void activate_alreadyActive_throws() {
    Company company = activeCompany();
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    source.markVerified(java.time.LocalDateTime.now(), "ok");
    source.activate();
    setCompany(source, company);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    assertThatThrownBy(() -> service.activateSource(1L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.SOURCE_ALREADY_ACTIVE);
  }

  @Test
  void activate_companyInactive_throws() {
    Company company = inactiveCompany();
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    source.markVerified(java.time.LocalDateTime.now(), "ok");
    setCompany(source, company);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    assertThatThrownBy(() -> service.activateSource(1L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMPANY_INACTIVE);
  }

  // ── deactivate after activate: verificationStatus preserved ─────────────

  @Test
  void deactivate_preservesVerificationStatus() {
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    source.markVerified(java.time.LocalDateTime.now(), "ok");
    source.activate();
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    service.deactivateSource(1L);

    assertThat(source.getStatus()).isEqualTo("INACTIVE");
    assertThat(source.getVerificationStatus()).isEqualTo("VERIFIED");
  }

  // ── updateSource: core field change resets verification ──────────────────

  @Test
  void update_sourceUrlChange_resetsVerification() {
    CompanySource source =
        realSource("OFFICIAL_CAREER", "https://old.example.com", "SITEMAP", null);
    source.markVerified(java.time.LocalDateTime.now(), "ok");
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrlAndIdNot(
            any(), any(), any(), any()))
        .thenReturn(false);

    service.updateSource(
        1L, new UpdateCompanySourceRequest(null, "https://new.example.com", null, null, null));

    assertThat(source.getStatus()).isEqualTo("PENDING");
    assertThat(source.getVerificationStatus()).isEqualTo("UNVERIFIED");
    assertThat(source.getLastVerifiedAt()).isNull();
    assertThat(source.getVerificationMessage()).isNull();
  }

  @Test
  void update_adapterTypeChange_resetsVerification() {
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    source.markVerified(java.time.LocalDateTime.now(), "ok");
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrlAndIdNot(
            any(), any(), any(), any()))
        .thenReturn(false);

    service.updateSource(1L, new UpdateCompanySourceRequest(null, null, "RSS", null, null));

    assertThat(source.getVerificationStatus()).isEqualTo("UNVERIFIED");
  }

  @Test
  void update_sameValues_doesNotResetVerification() {
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    source.markVerified(java.time.LocalDateTime.now(), "ok");
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrlAndIdNot(
            any(), any(), any(), any()))
        .thenReturn(false);

    // PATCH with same sourceUrl and adapterType (no change)
    service.updateSource(
        1L, new UpdateCompanySourceRequest(null, "https://example.com", "SITEMAP", null, null));

    assertThat(source.getVerificationStatus()).isEqualTo("VERIFIED");
    assertThat(source.getLastVerifiedAt()).isNotNull();
  }

  @Test
  void update_statusOnlyChange_doesNotResetVerification() {
    CompanySource source = realSource("OFFICIAL_CAREER", "https://example.com", "SITEMAP", null);
    source.markVerified(java.time.LocalDateTime.now(), "ok");
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrlAndIdNot(
            any(), any(), any(), any()))
        .thenReturn(false);

    // PATCH only status (PENDING → INACTIVE) — not a core field
    service.updateSource(1L, new UpdateCompanySourceRequest(null, null, null, "INACTIVE", null));

    assertThat(source.getVerificationStatus()).isEqualTo("VERIFIED");
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private CompanySource realSource(
      String sourceType, String sourceUrl, String adapterType, String configJson) {
    Company company = activeCompany();
    CompanySource s =
        CompanySource.create(company, sourceType, sourceUrl, adapterType, "PENDING", configJson);
    return s;
  }

  private Company activeCompany() {
    Company c = mock(Company.class);
    when(c.getId()).thenReturn(1L);
    when(c.getCanonicalName()).thenReturn("테스트기업");
    when(c.isActive()).thenReturn(true);
    return c;
  }

  private Company inactiveCompany() {
    Company c = mock(Company.class);
    when(c.getId()).thenReturn(1L);
    when(c.getCanonicalName()).thenReturn("테스트기업");
    when(c.isActive()).thenReturn(false);
    return c;
  }

  private void setCompany(CompanySource source, Company company) {
    try {
      var field = CompanySource.class.getDeclaredField("company");
      field.setAccessible(true);
      field.set(source, company);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
