package com.briefy.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.company.dto.admin.CompanyAdminResponse;
import com.briefy.domain.company.dto.admin.CompanyAliasResponse;
import com.briefy.domain.company.dto.admin.CreateAliasRequest;
import com.briefy.domain.company.dto.admin.CreateCompanyRequest;
import com.briefy.domain.company.dto.admin.UpdateCompanyRequest;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.entity.CompanyAlias;
import com.briefy.domain.company.entity.CompanySource;
import com.briefy.domain.company.repository.CompanyAliasRepository;
import com.briefy.domain.company.repository.CompanyRepository;
import com.briefy.domain.company.repository.CompanySourceRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.PageResult;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CompanyAdminServiceTest {

  @Mock private CompanyRepository companyRepository;
  @Mock private CompanyAliasRepository companyAliasRepository;
  @Mock private CompanySourceRepository companySourceRepository;

  private CompanyAdminService service;

  @BeforeEach
  void setUp() {
    service =
        new CompanyAdminService(
            companyRepository,
            companyAliasRepository,
            companySourceRepository,
            new CompanyNameNormalizer(),
            new ObjectMapper());
  }

  // ── listCompanies ─────────────────────────────────────────────────────────

  @Test
  void listCompanies_returnsPaginatedResult() {
    Company company = mockCompany(1L, "네이버", "naver", true);
    Page<Company> page = new PageImpl<>(List.of(company), PageRequest.of(0, 20), 1);
    when(companyRepository.findAll(any(Pageable.class))).thenReturn(page);
    when(companyAliasRepository.findAllByCompany_IdIn(List.of(1L))).thenReturn(List.of());
    when(companySourceRepository.findAllByCompany_IdIn(List.of(1L))).thenReturn(List.of());

    PageResult<CompanyAdminResponse> result = service.listCompanies(PageRequest.of(0, 20));

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).canonicalName()).isEqualTo("네이버");
  }

  // ── getCompany ─────────────────────────────────────────────────────────────

  @Test
  void getCompany_returnsAdminResponse() {
    Company company = mockCompany(1L, "카카오", "kakao", true);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyAliasRepository.findAllByCompany_Id(1L)).thenReturn(List.of());
    when(companySourceRepository.findAllByCompany_Id(1L)).thenReturn(List.of());

    CompanyAdminResponse response = service.getCompany(1L);

    assertThat(response.id()).isEqualTo(1L);
    assertThat(response.canonicalName()).isEqualTo("카카오");
  }

  @Test
  void getCompany_notFound_throwsBusinessException() {
    when(companyRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getCompany(99L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMPANY_NOT_FOUND);
  }

  // ── createCompany ─────────────────────────────────────────────────────────

  @Test
  void createCompany_success() {
    when(companyRepository.existsByNormalizedName("토스")).thenReturn(false);
    Company saved = mockCompany(10L, "토스", "토스", true);
    when(companyRepository.save(any())).thenReturn(saved);
    when(companyAliasRepository.findAllByCompany_Id(10L)).thenReturn(List.of());
    when(companySourceRepository.findAllByCompany_Id(10L)).thenReturn(List.of());

    CompanyAdminResponse response =
        service.createCompany(new CreateCompanyRequest("토스", "스타트업", List.of("핀테크")));

    assertThat(response.id()).isEqualTo(10L);
    assertThat(response.canonicalName()).isEqualTo("토스");
  }

  @Test
  void createCompany_duplicateNormalizedName_throwsConflict() {
    when(companyRepository.existsByNormalizedName("toss")).thenReturn(true);

    assertThatThrownBy(() -> service.createCompany(new CreateCompanyRequest("Toss", null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_COMPANY_NORMALIZED_NAME);
  }

  // ── updateCompany ─────────────────────────────────────────────────────────

  @Test
  void updateCompany_success() {
    Company company = Company.create("네이버", "naver", null, null);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyRepository.existsByNormalizedNameAndIdNot(any(), eq(1L))).thenReturn(false);
    when(companyAliasRepository.findAllByCompany_Id(any())).thenReturn(List.of());
    when(companySourceRepository.findAllByCompany_Id(any())).thenReturn(List.of());

    CompanyAdminResponse response =
        service.updateCompany(1L, new UpdateCompanyRequest("NAVER Corp", null, null));

    assertThat(response.canonicalName()).isEqualTo("NAVER Corp");
    assertThat(response.normalizedName()).isEqualTo("naver corp");
  }

  @Test
  void updateCompany_notFound_throwsBusinessException() {
    when(companyRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateCompany(99L, new UpdateCompanyRequest(null, null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMPANY_NOT_FOUND);
  }

  @Test
  void updateCompany_duplicateNormalizedName_throwsConflict() {
    Company company = Company.create("네이버", "naver", null, null);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyRepository.existsByNormalizedNameAndIdNot("kakao", 1L)).thenReturn(true);

    assertThatThrownBy(
            () -> service.updateCompany(1L, new UpdateCompanyRequest("Kakao", null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_COMPANY_NORMALIZED_NAME);
  }

  @Test
  void updateCompany_blankCanonicalName_throwsValidationError() {
    Company company = Company.create("네이버", "naver", null, null);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));

    assertThatThrownBy(() -> service.updateCompany(1L, new UpdateCompanyRequest("   ", null, null)))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.VALIDATION_ERROR);
  }

  // ── activate / deactivate ─────────────────────────────────────────────────

  @Test
  void activateCompany_success() {
    Company company = Company.create("카카오", "kakao", null, null);
    company.deactivate();
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyAliasRepository.findAllByCompany_Id(any())).thenReturn(List.of());
    when(companySourceRepository.findAllByCompany_Id(any())).thenReturn(List.of());

    CompanyAdminResponse response = service.activateCompany(1L);

    assertThat(response.active()).isTrue();
  }

  @Test
  void deactivateCompany_success() {
    Company company = Company.create("카카오", "kakao", null, null);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyAliasRepository.findAllByCompany_Id(any())).thenReturn(List.of());
    when(companySourceRepository.findAllByCompany_Id(any())).thenReturn(List.of());

    CompanyAdminResponse response = service.deactivateCompany(1L);

    assertThat(response.active()).isFalse();
  }

  @Test
  void deactivateCompany_doesNotDeleteAliasesOrSources() {
    Company company = mockCompany(1L, "카카오", "카카오", true);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    CompanyAlias alias = mock(CompanyAlias.class);
    when(alias.getCompany()).thenReturn(company);
    CompanySource source = mock(CompanySource.class);
    when(source.getCompany()).thenReturn(company);
    when(companyAliasRepository.findAllByCompany_Id(1L)).thenReturn(List.of(alias));
    when(companySourceRepository.findAllByCompany_Id(1L)).thenReturn(List.of(source));

    service.deactivateCompany(1L);

    verify(companyAliasRepository, never()).delete(any());
    verify(companySourceRepository, never()).delete(any());
    verify(companyAliasRepository, never()).deleteAll(any());
  }

  // ── createAlias ───────────────────────────────────────────────────────────

  @Test
  void createAlias_success() {
    Company company = mockCompany(1L, "네이버", "naver", true);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyRepository.existsByNormalizedName("네이버corp")).thenReturn(false);
    when(companyAliasRepository.existsByNormalizedAlias("네이버corp")).thenReturn(false);
    CompanyAlias saved = mockAlias(10L, company, "네이버Corp", "네이버corp");
    when(companyAliasRepository.save(any())).thenReturn(saved);

    CompanyAliasResponse response = service.createAlias(1L, new CreateAliasRequest("네이버Corp"));

    assertThat(response.id()).isEqualTo(10L);
    assertThat(response.alias()).isEqualTo("네이버Corp");
  }

  @Test
  void createAlias_conflictsWithCompanyNormalizedName_throwsConflict() {
    Company company = mockCompany(1L, "네이버", "naver", true);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyRepository.existsByNormalizedName("naver")).thenReturn(true);

    assertThatThrownBy(() -> service.createAlias(1L, new CreateAliasRequest("NAVER")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_COMPANY_ALIAS);
  }

  @Test
  void createAlias_conflictsWithOtherCompanyNormalizedName_throwsConflict() {
    Company company = mockCompany(1L, "네이버", "naver", true);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyRepository.existsByNormalizedName("kakao")).thenReturn(true);

    assertThatThrownBy(() -> service.createAlias(1L, new CreateAliasRequest("Kakao")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_COMPANY_ALIAS);
  }

  @Test
  void createAlias_alreadyExistsInAliasTable_throwsConflict() {
    Company company = mockCompany(1L, "네이버", "naver", true);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyRepository.existsByNormalizedName("naver search")).thenReturn(false);
    when(companyAliasRepository.existsByNormalizedAlias("naver search")).thenReturn(true);

    assertThatThrownBy(() -> service.createAlias(1L, new CreateAliasRequest("Naver Search")))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.DUPLICATE_COMPANY_ALIAS);
  }

  @Test
  void createAlias_englishCaseNormalized() {
    Company company = mockCompany(1L, "네이버", "naver", true);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyRepository.existsByNormalizedName("naver labs")).thenReturn(false);
    when(companyAliasRepository.existsByNormalizedAlias("naver labs")).thenReturn(false);
    CompanyAlias saved = mockAlias(11L, company, "NAVER Labs", "naver labs");
    when(companyAliasRepository.save(any())).thenReturn(saved);

    CompanyAliasResponse response = service.createAlias(1L, new CreateAliasRequest("NAVER Labs"));

    assertThat(response.normalizedAlias()).isEqualTo("naver labs");
  }

  @Test
  void createAlias_trailingSpaceStripped() {
    Company company = mockCompany(1L, "네이버", "naver", true);
    when(companyRepository.findById(1L)).thenReturn(Optional.of(company));
    when(companyRepository.existsByNormalizedName("naver labs")).thenReturn(false);
    when(companyAliasRepository.existsByNormalizedAlias("naver labs")).thenReturn(false);
    CompanyAlias saved = mockAlias(11L, company, "  NAVER Labs  ", "naver labs");
    when(companyAliasRepository.save(any())).thenReturn(saved);

    service.createAlias(1L, new CreateAliasRequest("  NAVER Labs  "));

    verify(companyAliasRepository).existsByNormalizedAlias("naver labs");
  }

  // ── deleteAlias ───────────────────────────────────────────────────────────

  @Test
  void deleteAlias_success() {
    Company company = mockCompany(1L, "네이버", "naver", true);
    CompanyAlias alias = mockAlias(5L, company, "네이버파이낸셜", "네이버파이낸셜");
    when(companyAliasRepository.findById(5L)).thenReturn(Optional.of(alias));

    service.deleteAlias(5L);

    verify(companyAliasRepository).delete(alias);
  }

  @Test
  void deleteAlias_notFound_throwsBusinessException() {
    when(companyAliasRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.deleteAlias(99L))
        .isInstanceOf(BusinessException.class)
        .extracting(e -> ((BusinessException) e).getErrorCode())
        .isEqualTo(ErrorCode.COMPANY_ALIAS_NOT_FOUND);
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private Company mockCompany(Long id, String canonical, String normalized, boolean active) {
    Company c = mock(Company.class);
    when(c.getId()).thenReturn(id);
    when(c.getCanonicalName()).thenReturn(canonical);
    when(c.getNormalizedName()).thenReturn(normalized);
    when(c.isActive()).thenReturn(active);
    return c;
  }

  private CompanyAlias mockAlias(Long id, Company company, String alias, String normalized) {
    CompanyAlias a = mock(CompanyAlias.class);
    when(a.getId()).thenReturn(id);
    when(a.getCompany()).thenReturn(company);
    when(a.getAlias()).thenReturn(alias);
    when(a.getNormalizedAlias()).thenReturn(normalized);
    return a;
  }
}
