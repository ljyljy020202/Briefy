package com.briefy.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.briefy.config.EmailProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FakeEmailSenderTest {

  @TempDir Path tempDir;

  @Mock private EmailProperties emailProperties;

  @InjectMocks private FakeEmailSender fakeEmailSender;

  @BeforeEach
  void setUp() {
    when(emailProperties.fakeOutputDir()).thenReturn(tempDir.toString());
  }

  @Test
  void send_returnsSuccessWithFakePrefix() {
    EmailMessage message = new EmailMessage("user@example.com", "Test Subject", "<p>Hello</p>");

    EmailSendResult result = fakeEmailSender.send(message);

    assertThat(result.success()).isTrue();
    assertThat(result.providerMessageId()).startsWith("FAKE-");
    assertThat(result.errorMessage()).isNull();
  }

  @Test
  void send_writesFileToOutputDir() {
    EmailMessage message = new EmailMessage("user@example.com", "Subject", "Content");

    fakeEmailSender.send(message);

    assertThat(tempDir.toFile().listFiles()).isNotEmpty();
  }

  @Test
  void send_doesNotCallExternalService() {
    EmailMessage message = new EmailMessage("a@b.com", "S", "C");

    EmailSendResult result = fakeEmailSender.send(message);

    assertThat(result.success()).isTrue();
  }
}
