package com.briefy.domain.delivery.controller;

import com.briefy.domain.delivery.dto.DeliverReportResponse;
import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.service.EmailDeliveryService;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 이메일 발송", description = "브리핑 보고서 이메일 발송 API (ROLE_ADMIN 필요)")
@RestController
@RequestMapping("/api/admin/briefing-reports")
public class AdminDeliveryController {

  private final EmailDeliveryService emailDeliveryService;

  public AdminDeliveryController(EmailDeliveryService emailDeliveryService) {
    this.emailDeliveryService = emailDeliveryService;
  }

  @Operation(
      summary = "브리핑 리포트 이메일 발송",
      description =
          """
          저장된 브리핑 리포트를 이메일로 발송합니다. (ADMIN 전용)
          멱등적으로 동작합니다:
          - DeliveryLog 없음: PENDING 생성 후 발송
          - PENDING/FAILED: SENDING 선점 후 발송 (일시 오류 시 최대 2회 재시도)
          - SENDING: 이미 처리 중 (중복 발송 없음)
          - SENT: 재발송 없이 기존 성공 결과 반환
          local(mode=log)에서는 FakeEmailSender가 사용됩니다.\
          """)
  @PostMapping("/{reportId}/deliver-email")
  public ResponseEntity<ApiResponse<DeliverReportResponse>> deliverEmail(
      @PathVariable Long reportId) {
    DeliveryLog log = emailDeliveryService.deliverBriefingReport(reportId);
    return ResponseEntity.ok(ApiResponse.success(DeliverReportResponse.from(reportId, log)));
  }
}
