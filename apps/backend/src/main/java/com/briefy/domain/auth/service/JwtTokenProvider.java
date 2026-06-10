package com.briefy.domain.auth.service;

import com.briefy.config.JwtProperties;
import com.briefy.global.auth.JwtPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

@Component
public class JwtTokenProvider {

  private static final String CLAIM_USER_ID = "userId";
  private static final String CLAIM_EMAIL = "email";
  private static final String CLAIM_ROLE = "role";

  private final SecretKey secretKey;
  private final long expirationSeconds;

  public JwtTokenProvider(JwtProperties jwtProperties) {
    this.secretKey = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    this.expirationSeconds = jwtProperties.expirationSeconds();
  }

  public String createToken(Long userId, String email, String role) {
    Date now = new Date();
    Date expiry = new Date(now.getTime() + expirationSeconds * 1000L);
    return Jwts.builder()
        .claim(CLAIM_USER_ID, userId)
        .claim(CLAIM_EMAIL, email)
        .claim(CLAIM_ROLE, role)
        .issuedAt(now)
        .expiration(expiry)
        .signWith(secretKey)
        .compact();
  }

  public JwtPrincipal parseToken(String token) {
    Claims claims =
        Jwts.parser().verifyWith(secretKey).build().parseSignedClaims(token).getPayload();

    // JSON numbers can deserialize as Integer; use Number to safely coerce to Long
    Number userIdNum = claims.get(CLAIM_USER_ID, Number.class);
    Long userId = userIdNum.longValue();
    String email = claims.get(CLAIM_EMAIL, String.class);
    String role = claims.get(CLAIM_ROLE, String.class);
    return new JwtPrincipal(userId, email, role);
  }

  public boolean isValid(String token) {
    try {
      parseToken(token);
      return true;
    } catch (JwtException | IllegalArgumentException e) {
      return false;
    }
  }
}
