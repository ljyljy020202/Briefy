package com.briefy.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.company.dto.CompanySearchResult;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.repository.CompanyRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

@ExtendWith(MockitoExtension.class)
class CompanyServiceTest {

  @Mock private CompanyRepository companyRepository;

  @InjectMocks private CompanyService companyService;

  @Test
  void search_returnsMatchingCompanies() {
    Company kakao = companyWithIndustryCodes(1L, "카카오", null, "IT/소프트웨어,핀테크");
    when(companyRepository.searchByQuery(eq("카카오"), any())).thenReturn(List.of(kakao));

    List<CompanySearchResult> results = companyService.search("카카오", 10);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).id()).isEqualTo(1L);
    assertThat(results.get(0).canonicalName()).isEqualTo("카카오");
    assertThat(results.get(0).industryCodes()).containsExactly("IT/소프트웨어", "핀테크");
  }

  @Test
  void search_returnsEmpty_whenQueryIsBlank() {
    List<CompanySearchResult> results = companyService.search("   ", 10);

    assertThat(results).isEmpty();
    verify(companyRepository, never()).searchByQuery(any(), any());
  }

  @Test
  void search_returnsEmpty_whenQueryIsNull() {
    List<CompanySearchResult> results = companyService.search(null, 10);

    assertThat(results).isEmpty();
    verify(companyRepository, never()).searchByQuery(any(), any());
  }

  @Test
  void search_stripsQueryBeforePassingToRepository() {
    when(companyRepository.searchByQuery(eq("네이버"), any())).thenReturn(List.of());

    companyService.search("  네이버  ", 10);

    verify(companyRepository).searchByQuery(eq("네이버"), any());
  }

  @Test
  void search_clampsLimitToMax() {
    when(companyRepository.searchByQuery(any(), eq(PageRequest.of(0, CompanyService.MAX_LIMIT))))
        .thenReturn(List.of());

    companyService.search("토스", 999);

    verify(companyRepository)
        .searchByQuery(eq("토스"), eq(PageRequest.of(0, CompanyService.MAX_LIMIT)));
  }

  @Test
  void search_clampsLimitToOne_whenZeroGiven() {
    when(companyRepository.searchByQuery(any(), eq(PageRequest.of(0, 1)))).thenReturn(List.of());

    companyService.search("토스", 0);

    verify(companyRepository).searchByQuery(eq("토스"), eq(PageRequest.of(0, 1)));
  }

  @Test
  void search_returnsEmptyIndustryCodes_whenFieldIsNull() {
    Company c = companyWithIndustryCodes(2L, "스타트업A", null, null);
    when(companyRepository.searchByQuery(any(), any())).thenReturn(List.of(c));

    List<CompanySearchResult> results = companyService.search("스타트업", 5);

    assertThat(results.get(0).industryCodes()).isEmpty();
  }

  @Test
  void search_returnsEmptyIndustryCodes_whenFieldIsBlank() {
    Company c = companyWithIndustryCodes(3L, "스타트업B", "스타트업", "  ");
    when(companyRepository.searchByQuery(any(), any())).thenReturn(List.of(c));

    List<CompanySearchResult> results = companyService.search("스타트업", 5);

    assertThat(results.get(0).industryCodes()).isEmpty();
  }

  @Test
  void search_parsesCommaSeparatedIndustryCodes() {
    Company c = companyWithIndustryCodes(4L, "크래프톤", "대기업", " 게임 , IT/소프트웨어 ");
    when(companyRepository.searchByQuery(any(), any())).thenReturn(List.of(c));

    List<CompanySearchResult> results = companyService.search("크래프톤", 5);

    assertThat(results.get(0).industryCodes()).containsExactly("게임", "IT/소프트웨어");
  }

  private Company companyWithIndustryCodes(
      Long id, String canonicalName, String companySize, String industryCodes) {
    Company c = mock(Company.class);
    when(c.getId()).thenReturn(id);
    when(c.getCanonicalName()).thenReturn(canonicalName);
    when(c.getCompanySize()).thenReturn(companySize);
    when(c.getIndustryCodes()).thenReturn(industryCodes);
    return c;
  }
}
