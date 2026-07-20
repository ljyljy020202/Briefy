package com.briefy.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.client.AgentClient;
import com.briefy.domain.company.dto.admin.CompanySourceAdminResponse;
import com.briefy.domain.company.dto.admin.CreateCompanySourceRequest;
import com.briefy.domain.company.dto.admin.UpdateCompanySourceRequest;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.entity.CompanySource;
import com.briefy.domain.company.repository.CompanyRepository;
import com.briefy.domain.company.repository.CompanySourceRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.PageResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanySourceAdminServiceTest {

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

  // ── listSources ───────────────────────────────────────────────────────────

  @Test
  void listSources_returnsPaginatedResult() {
    CompanySource source = mockSource(1L, "OFFICIAL_CAREER", "CUSTOM", "PENDING");
    Page<CompanySource> page = new PageImpl<>(List.of(source), PageRequest.of(0, 20), 1);
    when(companySourceRepository.findAll(any(Pageable.class))).thenReturn(page);

    PageResult<CompanySourceAdminResponse> result = service.listSources(PageRequest.of(0, 20));

    assertThat(result.content()).hasSize(1);
  }

  // ── getSource ─────────────────────────────────────────────────────────────

  @Test
  void getSource_returnsResponse() {
    CompanySource source = mockSource(1L, "OFFICIAL_CAREER", "CUSTOM", "PENDING");
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    CompanySourceAdminResponse response = service.getSource(1L);

    assertThat(response.id()).isEqualTo(1L);
  }

  @Test
  void getSource_notFound_throwsBusinessException() {
    when(companySourceRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getSource(99L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMPANY_SOURCE_NOT_FOUND);
  }

  // ── createSource ──────────────────────────────────────────────────────────

  @Test
  void createSource_sitemap_success() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrl(any(), any(), any()))
        .thenReturn(false);
    CompanySource saved = mockSource(10L, "OFFICIAL_CAREER", "SITEMAP", "PENDING");
    when(companySourceRepository.save(any())).thenReturn(saved);

    CompanySourceAdminResponse response =
        service.createSource(
            new CreateCompanySourceRequest(
                1L,
                "OFFICIAL_CAREER",
                "https://careers.example.com",
                "SITEMAP",
                "PENDING",
                Map.of("max_discover", 100)));

    assertThat(response.id()).isEqualTo(10L);
    assertThat(response.adapterType()).isEqualTo("SITEMAP");
  }

  @Test
  void createSource_rss_success() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrl(any(), any(), any()))
        .thenReturn(false);
    CompanySource saved = mockSource(11L, "OFFICIAL_CAREER", "RSS", "PENDING");
    when(companySourceRepository.save(any())).thenReturn(saved);

    CompanySourceAdminResponse response =
        service.createSource(
            new CreateCompanySourceRequest(
                1L,
                "OFFICIAL_CAREER",
                "https://careers.example.com/rss",
                "RSS",
                "PENDING",
                Map.of("max_items", 50)));

    assertThat(response.adapterType()).isEqualTo("RSS");
  }

  @Test
  void createSource_custom_parserKeyUppercased() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrl(any(), any(), any()))
        .thenReturn(false);
    CompanySource saved = mockSource(12L, "OFFICIAL_CAREER", "CUSTOM", "PENDING");
    when(saved.getConfigJson()).thenReturn("{\"parser_key\":\"NAVER_CAREERS\"}");
    when(companySourceRepository.save(any())).thenReturn(saved);

    CompanySourceAdminResponse response =
        service.createSource(
            new CreateCompanySourceRequest(
                1L,
                "OFFICIAL_CAREER",
                "https://recruit.navercorp.com",
                "CUSTOM",
                "PENDING",
                Map.of("parser_key", "naver_careers")));

    assertThat(response.config()).containsEntry("parser_key", "NAVER_CAREERS");
  }

  @Test
  void createSource_activeStatus_throwsInvalidStatus() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                service.createSource(
                    new CreateCompanySourceRequest(
                        1L, "OFFICIAL_CAREER", "https://example.com", "SITEMAP", "ACTIVE", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_SOURCE_STATUS);
  }

  @Test
  void createSource_invalidAdapterType_throwsInvalidAdapterType() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                service.createSource(
                    new CreateCompanySourceRequest(
                        1L, "OFFICIAL_CAREER", "https://example.com", "SCRAPER", "PENDING", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_ADAPTER_TYPE);
  }

  @Test
  void createSource_duplicateSource_throwsConflict() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrl(
            1L, "OFFICIAL_CAREER", "https://example.com"))
        .thenReturn(true);

    assertThatThrownBy(
            () ->
                service.createSource(
                    new CreateCompanySourceRequest(
                        1L, "OFFICIAL_CAREER", "https://example.com", "SITEMAP", "PENDING", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_COMPANY_SOURCE);
  }

  @Test
  void createSource_invalidSourceUrl_throwsInvalidSourceUrl() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                service.createSource(
                    new CreateCompanySourceRequest(
                        1L, "OFFICIAL_CAREER", "ftp://example.com", "SITEMAP", "PENDING", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_SOURCE_URL);
  }

  @Test
  void createSource_officialCareerWithoutSourceUrl_throwsValidationError() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                service.createSource(
                    new CreateCompanySourceRequest(
                        1L, "OFFICIAL_CAREER", null, "SITEMAP", "PENDING", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  @Test
  void createSource_customWithoutParserKey_throwsInvalidConfig() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                service.createSource(
                    new CreateCompanySourceRequest(
                        1L,
                        "OFFICIAL_CAREER",
                        "https://example.com",
                        "CUSTOM",
                        "PENDING",
                        Map.of("other_key", "value"))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_CONFIG);
  }

  @Test
  void createSource_invalidConfigShape_sitemap_throwsInvalidConfig() {
    Company company = mockCompany(1L);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(
            () ->
                service.createSource(
                    new CreateCompanySourceRequest(
                        1L,
                        "OFFICIAL_CAREER",
                        "https://example.com",
                        "SITEMAP",
                        "PENDING",
                        Map.of("max_discover", "not-a-number"))))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_CONFIG);
  }

  // ── updateSource ──────────────────────────────────────────────────────────

  @Test
  void updateSource_status_success() {
    CompanySource source = mockSource(1L, "OFFICIAL_CAREER", "SITEMAP", "PENDING");
    when(source.getSourceUrl()).thenReturn("https://example.com");
    when(source.getConfigJson()).thenReturn(null);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));
    when(companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrlAndIdNot(
            any(), any(), any(), any()))
        .thenReturn(false);

    service.updateSource(1L, new UpdateCompanySourceRequest(null, null, null, "INACTIVE", null));

    verify(source).update("OFFICIAL_CAREER", "https://example.com", "SITEMAP", "INACTIVE", null);
  }

  @Test
  void updateSource_activeStatus_throwsInvalidStatus() {
    CompanySource source = mockSource(1L, "OFFICIAL_CAREER", "SITEMAP", "PENDING");
    when(source.getSourceUrl()).thenReturn("https://example.com");
    when(source.getConfigJson()).thenReturn(null);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    assertThatThrownBy(
            () ->
                service.updateSource(
                    1L, new UpdateCompanySourceRequest(null, null, null, "ACTIVE", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_SOURCE_STATUS);
  }

  @Test
  void updateSource_patchActiveUppercase_throwsInvalidStatus() {
    CompanySource source = mockSource(1L, "OFFICIAL_CAREER", "SITEMAP", "PENDING");
    when(source.getSourceUrl()).thenReturn("https://example.com");
    when(source.getConfigJson()).thenReturn(null);
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    assertThatThrownBy(
            () ->
                service.updateSource(
                    1L, new UpdateCompanySourceRequest(null, null, null, "active", null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.INVALID_SOURCE_STATUS);
  }

  // ── deactivateSource ──────────────────────────────────────────────────────

  @Test
  void deactivateSource_success() {
    CompanySource source = mockSource(1L, "OFFICIAL_CAREER", "CUSTOM", "PENDING");
    when(companySourceRepository.findById(1L)).thenReturn(Optional.of(source));

    service.deactivateSource(1L);

    verify(source).deactivate();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Company mockCompany(Long id) {
    Company c = mock(Company.class);
    when(c.getId()).thenReturn(id);
    when(c.getCanonicalName()).thenReturn("TestCompany");
    return c;
  }

  private CompanySource mockSource(Long id, String sourceType, String adapterType, String status) {
    CompanySource s = mock(CompanySource.class);
    when(s.getId()).thenReturn(id);
    when(s.getSourceType()).thenReturn(sourceType);
    when(s.getAdapterType()).thenReturn(adapterType);
    when(s.getStatus()).thenReturn(status);
    Company company = mockCompany(1L);
    when(s.getCompany()).thenReturn(company);
    return s;
  }
}
