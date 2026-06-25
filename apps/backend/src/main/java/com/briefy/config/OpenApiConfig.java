package com.briefy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI openAPI(JwtProperties jwtProperties) {
    String cookieSchemeName = "cookieAuth";

    return new OpenAPI()
        .info(
            new Info()
                .title("Briefy API")
                .description(
                    "Briefy 백엔드 REST API 문서입니다.\n\n"
                        + "인증이 필요한 API는 Google OAuth 로그인 후 발급되는 "
                        + "`"
                        + jwtProperties.cookieName()
                        + "` HttpOnly 쿠키로 인증합니다.\n\n"
                        + "**로그인**: `GET /api/oauth2/authorize/google` 로 이동하면 Google 인증 페이지로 리다이렉트됩니다.")
                .version("v1"))
        .addSecurityItem(new SecurityRequirement().addList(cookieSchemeName))
        .components(
            new Components()
                .addSecuritySchemes(
                    cookieSchemeName,
                    new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.COOKIE)
                        .name(jwtProperties.cookieName())
                        .description("Google OAuth 로그인 후 자동 발급되는 JWT 쿠키")));
  }
}
