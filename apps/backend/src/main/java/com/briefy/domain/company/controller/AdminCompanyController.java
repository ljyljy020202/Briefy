package com.briefy.domain.company.controller;

import com.briefy.domain.company.dto.admin.CompanyAdminResponse;
import com.briefy.domain.company.dto.admin.CompanyAliasResponse;
import com.briefy.domain.company.dto.admin.CreateAliasRequest;
import com.briefy.domain.company.dto.admin.CreateCompanyRequest;
import com.briefy.domain.company.dto.admin.UpdateCompanyRequest;
import com.briefy.domain.company.service.CompanyAdminService;
import com.briefy.global.response.ApiResponse;
import com.briefy.global.response.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 기업", description = "기업 Registry 관리 API (ROLE_ADMIN 필요)")
@RestController
@RequestMapping("/api/admin")
public class AdminCompanyController {

  private final CompanyAdminService companyAdminService;

  public AdminCompanyController(CompanyAdminService companyAdminService) {
    this.companyAdminService = companyAdminService;
  }

  @Operation(summary = "기업 목록 조회")
  @GetMapping("/companies")
  public ResponseEntity<ApiResponse<PageResult<CompanyAdminResponse>>> listCompanies(
      @ParameterObject
          @PageableDefault(size = 20, sort = "canonicalName", direction = Sort.Direction.ASC)
          Pageable pageable) {
    return ResponseEntity.ok(ApiResponse.success(companyAdminService.listCompanies(pageable)));
  }

  @Operation(summary = "기업 단건 조회")
  @GetMapping("/companies/{id}")
  public ResponseEntity<ApiResponse<CompanyAdminResponse>> getCompany(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(companyAdminService.getCompany(id)));
  }

  @Operation(summary = "기업 등록")
  @PostMapping("/companies")
  public ResponseEntity<ApiResponse<CompanyAdminResponse>> createCompany(
      @RequestBody @Valid CreateCompanyRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success(companyAdminService.createCompany(request)));
  }

  @Operation(summary = "기업 수정")
  @PatchMapping("/companies/{id}")
  public ResponseEntity<ApiResponse<CompanyAdminResponse>> updateCompany(
      @PathVariable Long id, @RequestBody UpdateCompanyRequest request) {
    return ResponseEntity.ok(ApiResponse.success(companyAdminService.updateCompany(id, request)));
  }

  @Operation(summary = "기업 활성화")
  @PostMapping("/companies/{id}/activate")
  public ResponseEntity<ApiResponse<CompanyAdminResponse>> activateCompany(@PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(companyAdminService.activateCompany(id)));
  }

  @Operation(summary = "기업 비활성화")
  @PostMapping("/companies/{id}/deactivate")
  public ResponseEntity<ApiResponse<CompanyAdminResponse>> deactivateCompany(
      @PathVariable Long id) {
    return ResponseEntity.ok(ApiResponse.success(companyAdminService.deactivateCompany(id)));
  }

  @Operation(summary = "기업 alias 등록")
  @PostMapping("/companies/{companyId}/aliases")
  public ResponseEntity<ApiResponse<CompanyAliasResponse>> createAlias(
      @PathVariable Long companyId, @RequestBody @Valid CreateAliasRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success(companyAdminService.createAlias(companyId, request)));
  }

  @Operation(summary = "기업 alias 삭제")
  @DeleteMapping("/company-aliases/{aliasId}")
  public ResponseEntity<Void> deleteAlias(@PathVariable Long aliasId) {
    companyAdminService.deleteAlias(aliasId);
    return ResponseEntity.noContent().build();
  }
}
