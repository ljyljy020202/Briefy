package com.briefy.global.email;

import com.briefy.config.EmailProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "briefy.email.mode", havingValue = "log", matchIfMissing = true)
public class FakeEmailSender implements EmailSender {

  private static final Logger log = LoggerFactory.getLogger(FakeEmailSender.class);
  private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private final EmailProperties emailProperties;

  public FakeEmailSender(EmailProperties emailProperties) {
    this.emailProperties = emailProperties;
  }

  @Override
  public EmailSendResult send(EmailMessage message) {
    String messageId = "FAKE-" + UUID.randomUUID();
    log.info(
        "[FakeEmail] to={} subject={} messageId={}", message.to(), message.subject(), messageId);
    try {
      writeToFile(message, messageId);
    } catch (IOException e) {
      log.warn("[FakeEmail] Failed to write to file: {}", e.getMessage());
    }
    return EmailSendResult.ok(messageId);
  }

  private void writeToFile(EmailMessage message, String messageId) throws IOException {
    Path dir = Paths.get(emailProperties.fakeOutputDir());
    Files.createDirectories(dir);
    String filename = LocalDateTime.now().format(FILE_TS) + "_" + messageId + ".html";
    Path file = dir.resolve(filename);
    String body =
        "To: "
            + message.to()
            + "\nSubject: "
            + message.subject()
            + "\nMessage-Id: "
            + messageId
            + "\n---\n"
            + message.content();
    Files.writeString(file, body);
    log.info("[FakeEmail] Written to {}", file.toAbsolutePath());
  }
}
