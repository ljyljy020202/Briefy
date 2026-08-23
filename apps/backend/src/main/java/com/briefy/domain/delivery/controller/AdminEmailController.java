package com.briefy.domain.delivery.controller;

import com.briefy.domain.delivery.dto.TestEmailRequest;
import com.briefy.global.email.EmailMessage;
import com.briefy.global.email.EmailSendResult;
import com.briefy.global.email.EmailSender;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 이메일", description = "테스트 이메일 발송 API (ROLE_ADMIN 필요)")
@RestController
@RequestMapping("/api/admin/emails")
public class AdminEmailController {

  private final EmailSender emailSender;

  public AdminEmailController(EmailSender emailSender) {
    this.emailSender = emailSender;
  }

  @Operation(
      summary = "이메일 테스트 발송",
      description = "현재 email.mode 설정에 따라 테스트 이메일을 발송합니다. 로컬에서는 FakeEmailSender가 사용됩니다.")
  @PostMapping("/test")
  public ResponseEntity<ApiResponse<EmailSendResult>> sendTestEmail(
      @RequestBody @Valid TestEmailRequest request) {
    EmailSendResult result =
        emailSender.send(new EmailMessage(request.to(), request.subject(), request.content()));
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
