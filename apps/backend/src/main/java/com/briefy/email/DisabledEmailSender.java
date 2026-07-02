package com.briefy.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "briefy.email.mode", havingValue = "disabled")
public class DisabledEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(DisabledEmailSender.class);

  @Override
  public EmailSendResult send(EmailMessage message) {
    log.info("[Email] Sending is disabled — skipping message to {}", message.to());
    return EmailSendResult.ok("DISABLED");
  }
}
