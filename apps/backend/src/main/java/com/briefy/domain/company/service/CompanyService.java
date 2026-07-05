package com.briefy.domain.company.service;

import com.briefy.domain.company.dto.CompanySearchResult;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.repository.CompanyRepository;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CompanyService {

  static final int MAX_LIMIT = 20;

  private final CompanyRepository companyRepository;

  public CompanyService(CompanyRepository companyRepository) {
    this.companyRepository = companyRepository;
  }

  public List<CompanySearchResult> search(String q, int limit) {
    if (q == null || q.isBlank()) {
      return List.of();
    }
    int clampedLimit = Math.min(Math.max(1, limit), MAX_LIMIT);
    return companyRepository.searchByQuery(q.strip(), PageRequest.of(0, clampedLimit)).stream()
        .map(this::toResult)
        .toList();
  }

  private CompanySearchResult toResult(Company c) {
    List<String> codes =
        (c.getIndustryCodes() == null || c.getIndustryCodes().isBlank())
            ? List.of()
            : Arrays.stream(c.getIndustryCodes().split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    return new CompanySearchResult(c.getId(), c.getCanonicalName(), c.getCompanySize(), codes);
  }
}
