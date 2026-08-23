package com.briefy.global.email;

public interface EmailSender {

  EmailSendResult send(EmailMessage message);
}
