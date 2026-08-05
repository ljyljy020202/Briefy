package com.briefy.domain.auth.exception;

public class OAuthProviderConflictException extends RuntimeException {

  private final String existingProvider;

  public OAuthProviderConflictException(String existingProvider) {
    super("Account already exists with provider: " + existingProvider);
    this.existingProvider = existingProvider;
  }

  public String getExistingProvider() {
    return existingProvider;
  }
}
