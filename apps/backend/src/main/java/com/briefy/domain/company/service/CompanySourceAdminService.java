package com.briefy.domain.company.service;

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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CompanySourceAdminService {

  private static final Set<String> ALLOWED_SOURCE_TYPES = Set.of("OFFICIAL_CAREER");
  private static final Set<String> ALLOWED_ADAPTER_TYPES = Set.of("SITEMAP", "RSS", "CUSTOM");
  private static final Set<String> ALLOWED_STATUSES = Set.of("PENDING", "INACTIVE");

  private final CompanyRepository companyRepository;
  private final CompanySourceRepository companySourceRepository;
  private final ObjectMapper objectMapper;

  public CompanySourceAdminService(
      CompanyRepository companyRepository,
      CompanySourceRepository companySourceRepository,
      ObjectMapper objectMapper) {
    this.companyRepository = companyRepository;
    this.companySourceRepository = companySourceRepository;
    this.objectMapper = objectMapper;
  }

  public PageResult<CompanySourceAdminResponse> listSources(Pageable pageable) {
    return PageResult.from(
        companySourceRepository
            .findAll(pageable)
            .map(s -> CompanySourceAdminResponse.from(s, objectMapper)));
  }

  public CompanySourceAdminResponse getSource(Long id) {
    return CompanySourceAdminResponse.from(findSourceOrThrow(id), objectMapper);
  }

  @Transactional
  public CompanySourceAdminResponse createSource(CreateCompanySourceRequest request) {
    Company company =
        companyRepository
            .findById(request.companyId())
            .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_NOT_FOUND));

    String sourceType = normalize(request.sourceType());
    String adapterType = normalize(request.adapterType());
    String status = normalize(request.status());

    validateSourceType(sourceType);
    validateAdapterType(adapterType);
    validateStatus(status);

    String sourceUrl = request.sourceUrl();
    if ("OFFICIAL_CAREER".equals(sourceType)) {
      requireSourceUrl(sourceUrl);
    }
    if (sourceUrl != null && !sourceUrl.isBlank()) {
      validateSourceUrl(sourceUrl);
    }

    Map<String, Object> config = validateAndNormalizeConfig(adapterType, request.config());

    if (companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrl(
        request.companyId(), sourceType, sourceUrl)) {
      throw new BusinessException(ErrorCode.DUPLICATE_COMPANY_SOURCE);
    }

    CompanySource source =
        CompanySource.create(
            company, sourceType, sourceUrl, adapterType, status, serializeConfig(config));
    return CompanySourceAdminResponse.from(companySourceRepository.save(source), objectMapper);
  }

  @Transactional
  public CompanySourceAdminResponse updateSource(Long id, UpdateCompanySourceRequest request) {
    CompanySource source = findSourceOrThrow(id);

    String sourceType =
        request.sourceType() != null ? normalize(request.sourceType()) : source.getSourceType();
    String adapterType =
        request.adapterType() != null ? normalize(request.adapterType()) : source.getAdapterType();
    String status = request.status() != null ? normalize(request.status()) : source.getStatus();
    String sourceUrl = request.sourceUrl() != null ? request.sourceUrl() : source.getSourceUrl();
    Map<String, Object> config =
        request.config() != null ? request.config() : deserializeConfig(source.getConfigJson());

    validateSourceType(sourceType);
    validateAdapterType(adapterType);
    validateStatus(status);

    if ("OFFICIAL_CAREER".equals(sourceType)) {
      requireSourceUrl(sourceUrl);
    }
    if (request.sourceUrl() != null && !request.sourceUrl().isBlank()) {
      validateSourceUrl(sourceUrl);
    }

    Map<String, Object> normalizedConfig = validateAndNormalizeConfig(adapterType, config);

    Long companyId = source.getCompany().getId();
    if (companySourceRepository.existsByCompany_IdAndSourceTypeAndSourceUrlAndIdNot(
        companyId, sourceType, sourceUrl, id)) {
      throw new BusinessException(ErrorCode.DUPLICATE_COMPANY_SOURCE);
    }

    source.update(sourceType, sourceUrl, adapterType, status, serializeConfig(normalizedConfig));
    return CompanySourceAdminResponse.from(source, objectMapper);
  }

  @Transactional
  public CompanySourceAdminResponse deactivateSource(Long id) {
    CompanySource source = findSourceOrThrow(id);
    source.deactivate();
    return CompanySourceAdminResponse.from(source, objectMapper);
  }

  private CompanySource findSourceOrThrow(Long id) {
    return companySourceRepository
        .findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.COMPANY_SOURCE_NOT_FOUND));
  }

  private String normalize(String value) {
    return value.strip().toUpperCase(Locale.ROOT);
  }

  private void validateSourceType(String sourceType) {
    if (!ALLOWED_SOURCE_TYPES.contains(sourceType)) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Invalid sourceType: " + sourceType);
    }
  }

  private void validateAdapterType(String adapterType) {
    if (!ALLOWED_ADAPTER_TYPES.contains(adapterType)) {
      throw new BusinessException(
          ErrorCode.INVALID_ADAPTER_TYPE, "Invalid adapterType: " + adapterType);
    }
  }

  private void validateStatus(String status) {
    if (!ALLOWED_STATUSES.contains(status)) {
      throw new BusinessException(
          ErrorCode.INVALID_SOURCE_STATUS,
          "Status must be PENDING or INACTIVE; ACTIVE may not be set via this API");
    }
  }

  private void requireSourceUrl(String sourceUrl) {
    if (sourceUrl == null || sourceUrl.isBlank()) {
      throw new BusinessException(
          ErrorCode.VALIDATION_ERROR, "sourceUrl is required for OFFICIAL_CAREER");
    }
  }

  private void validateSourceUrl(String sourceUrl) {
    try {
      URI uri = new URI(sourceUrl);
      String scheme = uri.getScheme();
      if (!"http".equals(scheme) && !"https".equals(scheme)) {
        throw new BusinessException(
            ErrorCode.INVALID_SOURCE_URL, "sourceUrl must be HTTP or HTTPS");
      }
    } catch (URISyntaxException e) {
      throw new BusinessException(ErrorCode.INVALID_SOURCE_URL, "sourceUrl is not a valid URI");
    }
  }

  private Map<String, Object> validateAndNormalizeConfig(
      String adapterType, Map<String, Object> config) {
    if ("CUSTOM".equals(adapterType)) {
      if (config == null || !config.containsKey("parser_key")) {
        throw new BusinessException(
            ErrorCode.INVALID_CONFIG, "CUSTOM adapter requires config.parser_key");
      }
      Object rawKey = config.get("parser_key");
      if (!(rawKey instanceof String s) || s.isBlank()) {
        throw new BusinessException(
            ErrorCode.INVALID_CONFIG, "config.parser_key must be a non-blank string");
      }
      Map<String, Object> normalized = new LinkedHashMap<>(config);
      normalized.put("parser_key", s.strip().toUpperCase(Locale.ROOT));
      return normalized;
    } else if ("SITEMAP".equals(adapterType)) {
      if (config != null) {
        validateIntegerConfigKey(config, "max_discover");
        validateIntegerConfigKey(config, "max_fetch");
      }
    } else if ("RSS".equals(adapterType)) {
      if (config != null) {
        validateIntegerConfigKey(config, "max_items");
      }
    }
    return config;
  }

  private void validateIntegerConfigKey(Map<String, Object> config, String key) {
    if (config.containsKey(key) && !(config.get(key) instanceof Number)) {
      throw new BusinessException(
          ErrorCode.INVALID_CONFIG, "config." + key + " must be an integer");
    }
  }

  private String serializeConfig(Map<String, Object> config) {
    if (config == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(config);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize config", e);
    }
  }

  private Map<String, Object> deserializeConfig(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return null;
    }
  }
}
