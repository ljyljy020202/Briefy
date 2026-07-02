package com.briefy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
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
                        + "**로그인**: `GET /api/oauth2/authorize/google` 으로 이동하면 Google 인증 페이지로 리다이렉트됩니다.\n\n"
                        + "**관리자 API** (`/api/admin/**`)는 `ROLE_ADMIN` 권한이 필요합니다.")
                .version("v1"))
        .tags(
            List.of(
                new Tag().name("관리자").description("관리자 전용 API (ROLE_ADMIN 필요)"),
                new Tag().name("인증").description("Google OAuth 로그인 및 로그아웃"),
                new Tag().name("사용자").description("내 정보 조회 및 온보딩 설정"),
                new Tag().name("브리핑").description("브리핑 조회"),
                new Tag().name("브리핑 선호도").description("사용자 브리핑 선호도 관리"),
                new Tag().name("브리핑 카테고리").description("브리핑 카테고리 목록 조회"),
                new Tag().name("대시보드").description("대시보드 요약 데이터")))
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
