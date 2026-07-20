package com.briefy.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public GroupedOpenApi serviceApi() {
    return GroupedOpenApi.builder()
        .group("service")
        .displayName("서비스 API")
        .pathsToMatch("/api/**")
        .pathsToExclude("/api/admin/**")
        .build();
  }

  @Bean
  public GroupedOpenApi adminApi() {
    return GroupedOpenApi.builder()
        .group("admin")
        .displayName("관리자 API")
        .pathsToMatch("/api/admin/**")
        .build();
  }

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
                        + "**로그인**: `GET /api/oauth2/authorize/google` 으로 이동하면 "
                        + "Google 인증 페이지로 리다이렉트됩니다.\n\n"
                        + "**관리자 API** (`/api/admin/**`)는 `ROLE_ADMIN` 권한이 필요합니다. "
                        + "상단 드롭다운에서 **관리자 API** 그룹을 선택하면 관리자 전용 엔드포인트를 "
                        + "별도로 확인할 수 있습니다.")
                .version("v1"))
        .tags(
            List.of(
                // 서비스 API
                new Tag().name("인증").description("Google OAuth 로그인 및 로그아웃"),
                new Tag().name("사용자").description("내 정보 조회 및 온보딩 설정"),
                new Tag().name("브리핑").description("브리핑 조회"),
                new Tag().name("브리핑 선호도").description("사용자 브리핑 선호도 관리"),
                new Tag().name("브리핑 카테고리").description("브리핑 카테고리 목록 조회"),
                new Tag().name("대시보드").description("대시보드 요약 데이터"),
                new Tag().name("기업 검색").description("기업명 자동완성 및 검색"),
                // 관리자 API
                new Tag().name("관리자 - 기업").description("기업 Registry 관리 (ROLE_ADMIN 필요)"),
                new Tag().name("관리자 - 채용 소스").description("기업 채용 소스 관리 (ROLE_ADMIN 필요)"),
                new Tag().name("관리자 - 브리핑").description("브리핑 생성 및 배포 관리 (ROLE_ADMIN 필요)"),
                new Tag().name("관리자 - 컬렉션").description("일간 콘텐츠 수집 제어 (ROLE_ADMIN 필요)"),
                new Tag().name("관리자 - 배송").description("브리핑 보고서 이메일 발송 (ROLE_ADMIN 필요)"),
                new Tag().name("관리자 - 이메일").description("테스트 이메일 발송 (ROLE_ADMIN 필요)")))
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
