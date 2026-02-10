package com.wiki.engine.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Optional;

/**
 * Swagger(OpenAPI) 설정.
 * springdoc-openapi를 사용하여 REST API 문서를 자동 생성한다.
 * 인증은 HttpOnly 쿠키 기반이므로 Swagger UI에서 별도 토큰 입력이 불필요하다.
 * Swagger UI 접근 경로: /swagger-ui.html 또는 /swagger-ui/index.html
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wiki Engine API")
                        .version("v1.0")
                        .description("위키 엔진 게시판 REST API. 인증은 HttpOnly 쿠키(JWT)로 처리된다."));
    }

    @Bean
    public GroupedOpenApi v1Api() {
        return GroupedOpenApi.builder()
                .group("API v1")
                .addOpenApiMethodFilter(method -> {
                    RequestMapping mapping = method.getDeclaringClass().getAnnotation(RequestMapping.class);
                    String version = Optional.ofNullable(mapping)
                            .map(RequestMapping::version)
                            .filter(v -> !v.isEmpty())
                            .orElse("1.0");
                    return version.startsWith("1.");
                })
                .build();
    }
}
