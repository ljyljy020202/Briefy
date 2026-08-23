package com.briefy.domain.preference.controller;

import com.briefy.domain.preference.dto.BriefingPreferenceResponse;
import com.briefy.domain.preference.dto.PatchBriefingPreferenceRequest;
import com.briefy.domain.preference.dto.UpsertBriefingPreferenceRequest;
import com.briefy.domain.preference.service.UserBriefingPreferenceService;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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

@Tag(name = "브리핑 선호도", description = "사용자 브리핑 선호도 관리")
@RestController
@RequestMapping("/api/me/briefing-preferences")
public class UserBriefingPreferenceController {

  private final UserBriefingPreferenceService preferenceService;
  private final CurrentUserProvider currentUserProvider;

  public UserBriefingPreferenceController(
      UserBriefingPreferenceService preferenceService, CurrentUserProvider currentUserProvider) {
    this.preferenceService = preferenceService;
    this.currentUserProvider = currentUserProvider;
  }

  @Operation(summary = "내 브리핑 선호도 목록 조회", description = "현재 로그인한 사용자의 모든 브리핑 선호도를 반환합니다.")
  @GetMapping
  public ResponseEntity<ApiResponse<List<BriefingPreferenceResponse>>> getMyPreferences() {
    Long userId = currentUserProvider.getCurrentUser().userId();
    return ResponseEntity.ok(ApiResponse.success(preferenceService.getMyPreferences(userId)));
  }

  @Operation(summary = "브리핑 선호도 생성", description = "카테고리별 선호도를 생성합니다. 같은 카테고리가 이미 존재하면 덮어씁니다.")
  @PostMapping
  public ResponseEntity<ApiResponse<BriefingPreferenceResponse>> createPreference(
      @RequestBody @Valid UpsertBriefingPreferenceRequest request) {
    Long userId = currentUserProvider.getCurrentUser().userId();
    BriefingPreferenceResponse response = preferenceService.upsertPreference(userId, request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
  }

  @Operation(
      summary = "브리핑 선호도 부분 수정",
      description = "선호도 항목을 부분 수정합니다. 전달한 필드만 업데이트되며 나머지는 유지됩니다.")
  @PatchMapping("/{id}")
  public ResponseEntity<ApiResponse<BriefingPreferenceResponse>> patchPreference(
      @PathVariable Long id, @RequestBody @Valid PatchBriefingPreferenceRequest request) {
    Long userId = currentUserProvider.getCurrentUser().userId();
    return ResponseEntity.ok(
        ApiResponse.success(preferenceService.patchPreference(userId, id, request)));
  }

  @Operation(summary = "브리핑 선호도 삭제", description = "선호도를 삭제합니다. 해당 카테고리 브리핑은 더 이상 생성되지 않습니다.")
  @DeleteMapping("/{id}")
  public ResponseEntity<ApiResponse<Void>> deletePreference(@PathVariable Long id) {
    Long userId = currentUserProvider.getCurrentUser().userId();
    preferenceService.deletePreference(userId, id);
    return ResponseEntity.ok(ApiResponse.success(null));
  }
}
