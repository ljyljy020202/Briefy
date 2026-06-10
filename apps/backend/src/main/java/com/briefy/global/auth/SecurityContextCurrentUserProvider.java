package com.briefy.global.auth;

import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class SecurityContextCurrentUserProvider implements CurrentUserProvider {

  @Override
  public AuthenticatedUser getCurrentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !authentication.isAuthenticated()
        || "anonymousUser".equals(authentication.getPrincipal())) {
      throw new BusinessException(ErrorCode.UNAUTHORIZED);
    }
    if (authentication.getPrincipal() instanceof JwtPrincipal principal) {
      return new AuthenticatedUser(principal.userId());
    }
    throw new BusinessException(ErrorCode.UNAUTHORIZED);
  }
}
