package com.wiki.engine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger(OpenAPI) 설정.
 * springdoc-openapi를 사용하여 REST API 문서를 자동 생성한다.
 * JWT Bearer 토큰 인증 방식을 Swagger UI에서 사용할 수 있도록 설정한다.
 * Swagger UI 접근 경로: /swagger-ui.html 또는 /swagger-ui/index.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        String securitySchemeName = "Bearer Token";

        return new OpenAPI()
                // API 기본 정보
                .info(new Info()
                        .title("Wiki Engine API")
                        .version("v1.0")
                        .description("위키 엔진 게시판 REST API"))
                // 모든 API에 JWT 인증을 기본 적용
                .addSecurityItem(new SecurityRequirement().addList(securitySchemeName))
                // JWT Bearer 토큰 인증 스키마 정의
                .components(new Components()
                        .addSecuritySchemes(securitySchemeName,
                                new SecurityScheme()
                                        .name(securitySchemeName)
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")));
    }
}
