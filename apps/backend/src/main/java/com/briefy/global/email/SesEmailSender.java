package com.briefy.global.email;

import com.briefy.config.EmailProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SendEmailResponse;
import software.amazon.awssdk.services.ses.model.SesException;

@Component
@ConditionalOnProperty(name = "briefy.email.mode", havingValue = "ses")
public class SesEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);

  private final SesClient sesClient;
  private final EmailProperties emailProperties;

  public SesEmailSender(EmailProperties emailProperties) {
    this.emailProperties = emailProperties;
    this.sesClient = SesClient.builder().region(Region.of(emailProperties.awsRegion())).build();
  }

  @Override
  public EmailSendResult send(EmailMessage message) {
    try {
      SendEmailRequest request =
          SendEmailRequest.builder()
              .destination(Destination.builder().toAddresses(message.to()).build())
              .message(
                  Message.builder()
                      .subject(Content.builder().data(message.subject()).charset("UTF-8").build())
                      .body(
                          Body.builder()
                              .html(
                                  Content.builder()
                                      .data(message.content())
                                      .charset("UTF-8")
                                      .build())
                              .build())
                      .build())
              .source(emailProperties.from())
              .build();
      SendEmailResponse response = sesClient.sendEmail(request);
      log.info("[SesEmail] Sent to {} messageId={}", message.to(), response.messageId());
      return EmailSendResult.ok(response.messageId());
    } catch (SdkClientException e) {
      // 네트워크 오류, 타임아웃 → 재시도 가능
      log.warn(
          "[SesEmail] Transient error (SdkClientException) to {}: {}",
          message.to(),
          e.getMessage());
      return EmailSendResult.transientFail("connection_error");
    } catch (SesException e) {
      int statusCode = e.statusCode();
      String errorCode = e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : "unknown";
      if (statusCode == 429 || statusCode >= 500 || "Throttling".equals(errorCode)) {
        log.warn(
            "[SesEmail] Transient SES error ({}) errorCode={} to {}",
            statusCode,
            errorCode,
            message.to());
        return EmailSendResult.transientFail("ses_throttling_or_server_error:" + statusCode);
      }
      // MessageRejectedException, MailFromDomainNotVerifiedException 등 영구 오류
      log.error(
          "[SesEmail] Permanent SES error ({}) errorCode={} to {}",
          statusCode,
          errorCode,
          message.to());
      return EmailSendResult.fail("ses_permanent:" + errorCode);
    } catch (Exception e) {
      log.error("[SesEmail] Unexpected error sending to {}: {}", message.to(), e.getMessage(), e);
      return EmailSendResult.fail("unexpected:" + e.getClass().getSimpleName());
    }
  }
}
