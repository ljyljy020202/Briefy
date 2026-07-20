package com.briefy.domain.company.service;

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
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CompanyAdminService {

  private final CompanyRepository companyRepository;
  private final CompanyAliasRepository companyAliasRepository;
  private final CompanySourceRepository companySourceRepository;
  private final CompanyNameNormalizer normalizer;
  private final ObjectMapper objectMapper;

  public CompanyAdminService(
      CompanyRepository companyRepository,
      CompanyAliasRepository companyAliasRepository,
      CompanySourceRepository companySourceRepository,
      CompanyNameNormalizer normalizer,
      ObjectMapper objectMapper) {
    this.companyRepository = companyRepository;
    this.companyAliasRepository = companyAliasRepository;
    this.companySourceRepository = companySourceRepository;
    this.normalizer = normalizer;
    this.objectMapper = objectMapper;
  }

  public PageResult<CompanyAdminResponse> listCompanies(Pageable pageable) {
    Page<Company> page = companyRepository.findAll(pageable);
    List<Long> ids = page.getContent().stream().map(Company::getId).toList();

    Map<Long, List<CompanyAlias>> aliasesByCompany =
        companyAliasRepository.findAllByCompany_IdIn(ids).stream()
            .collect(Collectors.groupingBy(a -> a.getCompany().getId()));

    Map<Long, List<CompanySource>> sourcesByCompany =
        companySourceRepository.findAllByCompany_IdIn(ids).stream()
            .collect(Collectors.groupingBy(s -> s.getCompany().getId()));

    Page<CompanyAdminResponse> responsePage =
        page.map(
            c ->
                CompanyAdminResponse.from(
                    c,
                    aliasesByCompany.getOrDefault(c.getId(), List.of()),
                    sourcesByCompany.getOrDefault(c.getId(), List.of()),
                    objectMapper));

    return PageResult.from(responsePage);
  }

  public CompanyAdminResponse getCompany(Long id) {
    Company company = findCompanyOrThrow(id);
    return toAdminResponse(company);
  }

  @Transactional
  public CompanyAdminResponse createCompany(CreateCompanyRequest request) {
    String normalized = normalizer.normalize(request.canonicalName());
    if (companyRepository.existsByNormalizedName(normalized)) {
      throw new BusinessException(ErrorCode.DUPLICATE_COMPANY_NORMALIZED_NAME);
    }
    Company company =
        Company.create(
            request.canonicalName(),
            normalized,
            request.companySize(),
            joinIndustryCodes(request.industryCodes()));
    return toAdminResponse(companyRepository.save(company));
  }

  @Transactional
  public CompanyAdminResponse updateCompany(Long id, UpdateCompanyRequest request) {
    Company company = findCompanyOrThrow(id);

    String newCanonical =
        request.canonicalName() != null ? request.canonicalName() : company.getCanonicalName();
    if (request.canonicalName() != null && request.canonicalName().isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "canonicalName must not be blank");
    }

    String newNormalized = normalizer.normalize(newCanonical);
    if (!newNormalized.equals(company.getNormalizedName())
        && companyRepository.existsByNormalizedNameAndIdNot(newNormalized, id)) {
      throw new BusinessException(ErrorCode.DUPLICATE_COMPANY_NORMALIZED_NAME);
    }

    String newSize =
        request.companySize() != null ? request.companySize() : company.getCompanySize();
    String newCodes =
        request.industryCodes() != null
            ? joinIndustryCodes(request.industryCodes())
            : company.getIndustryCodes();

    company.update(newCanonical, newNormalized, newSize, newCodes);
    return toAdminResponse(company);
  }

  @Transactional
  public CompanyAdminResponse activateCompany(Long id) {
    Company company = findCompanyOrThrow(id);
    company.activate();
    return toAdminResponse(company);
  }

  @Transactional
  public CompanyAdminResponse deactivateCompany(Long id) {
    Company company = findCompanyOrThrow(id);
    company.deactivate();
    return toAdminResponse(company);
  }

  @Transactional
  public CompanyAliasResponse createAlias(Long companyId, CreateAliasRequest request) {
    Company company = findCompanyOrThrow(companyId);

    String normalizedAlias = normalizer.normalize(request.alias());

    if (companyRepository.existsByNormalizedName(normalizedAlias)) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_COMPANY_ALIAS, "Alias conflicts with a company's normalized name");
    }

    if (companyAliasRepository.existsByNormalizedAlias(normalizedAlias)) {
      throw new BusinessException(
          ErrorCode.DUPLICATE_COMPANY_ALIAS, "Alias already registered for another company");
    }

    CompanyAlias alias = CompanyAlias.create(company, request.alias(), normalizedAlias);
    return CompanyAliasResponse.from(companyAliasRepository.save(alias));
  }

  @Transactional
  public void deleteAlias(Long aliasId) {
    CompanyAlias alias =
        companyAliasRepository
            .findById(aliasId)
            .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_ALIAS_NOT_FOUND));
    companyAliasRepository.delete(alias);
  }

  private Company findCompanyOrThrow(Long id) {
    return companyRepository
        .findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));
  }

  private CompanyAdminResponse toAdminResponse(Company company) {
    List<CompanyAlias> aliases = companyAliasRepository.findAllByCompany_Id(company.getId());
    List<CompanySource> sources = companySourceRepository.findAllByCompany_Id(company.getId());
    return CompanyAdminResponse.from(company, aliases, sources, objectMapper);
  }

  private String joinIndustryCodes(List<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return null;
    }
    return String.join(",", codes);
  }
}
