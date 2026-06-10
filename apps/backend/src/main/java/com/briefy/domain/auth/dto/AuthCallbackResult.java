package com.briefy.domain.auth.dto;

import org.springframework.http.ResponseCookie;

public record AuthCallbackResult(ResponseCookie cookie, String redirectUrl) {}
