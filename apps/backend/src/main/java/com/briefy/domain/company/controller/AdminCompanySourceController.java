package com.briefy.domain.company.controller;

import com.briefy.domain.company.dto.admin.CompanySourceAdminResponse;
import com.briefy.domain.company.dto.admin.CreateCompanySourceRequest;
import com.briefy.domain.company.dto.admin.UpdateCompanySourceRequest;
import com.briefy.domain.company.service.CompanySourceAdminService;
import com.briefy.global.response.ApiResponse;
import com.briefy.global.response.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 채용 소스", description = "기업 채용 소스 관리 API (ROLE_ADMIN 필요)")
@RestController
@RequestMapping("/api/admin/company-sources")
public class AdminCompanySourceController {

  private final CompanySourceAdminService companySourceAdminService;

  public AdminCompanySourceController(CompanySourceAdminService companySourceAdminService) {
    this.companySourceAdminService = companySourceAdminService;
  }

  @Operation(summary = "채용 소스 목록 조회")
  @GetMapping
  public ResponseEntity<ApiResponse<PageResult<CompanySourceAdminResponse>>> listSources(
      @PageableDefault(size = 20) Pageable pageable) {
    return ResponseEntity.ok(ApiResponse.success(companySourceAdminService.listSources(pageable)));
  }

  @Operation(summary = "채용 소스 단건 조회")
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<CompanySourceAdminResponse>> getSource(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(companySourceAdminService.getSource(id)));
  }

  @Operation(summary = "채용 소스 등록")
  @PostMapping
  public ResponseEntity<ApiResponse<CompanySourceAdminResponse>> createSource(
      @RequestBody @Valid CreateCompanySourceRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success(companySourceAdminService.createSource(request)));
  }

  @Operation(summary = "채용 소스 수정")
  @PatchMapping("/{id}")
  public ResponseEntity<ApiResponse<CompanySourceAdminResponse>> updateSource(
      @PathVariable Long id, @RequestBody UpdateCompanySourceRequest request) {
    return ResponseEntity.ok(
        ApiResponse.success(companySourceAdminService.updateSource(id, request)));
  }

  @Operation(summary = "채용 소스 비활성화")
  @PostMapping("/{id}/deactivate")
  public ResponseEntity<ApiResponse<CompanySourceAdminResponse>> deactivateSource(
      @PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(companySourceAdminService.deactivateSource(id)));
  }
}
