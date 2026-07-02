package com.briefy.email;

public interface EmailSender {

  EmailSendResult send(EmailMessage message);
}
