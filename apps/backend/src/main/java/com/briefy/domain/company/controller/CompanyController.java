package com.briefy.domain.company.controller;

import com.briefy.domain.company.dto.CompanySearchResult;
import com.briefy.domain.company.service.CompanyService;
import com.briefy.global.response.ApiResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/companies")
public class CompanyController {

  private final CompanyService companyService;

  public CompanyController(CompanyService companyService) {
    this.companyService = companyService;
  }

  @GetMapping("/search")
  public ResponseEntity<ApiResponse<List<CompanySearchResult>>> search(
      @RequestParam String q, @RequestParam(defaultValue = "10") int limit) {
    return ResponseEntity.ok(ApiResponse.success(companyService.search(q, limit)));
  }
}
