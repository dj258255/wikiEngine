package com.wiki.engine.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring MVC 웹 설정.
 * Spring Boot 4의 네이티브 API 버전 관리 기능을 활용한다.
 * 모든 @RestController에 "/api/v{version}" 접두사를 자동으로 붙여준다.
 *
 * 예시:
 * - @RequestMapping("/auth") → /api/v1.0/auth
 * - @RequestMapping("/posts") → /api/v1.0/posts
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * API 버전 관리 설정.
     * URL 경로의 첫 번째 세그먼트를 버전으로 사용하며, 기본 버전은 "1.0"이다.
     * @RequestMapping의 version 속성으로 특정 버전을 지정할 수 있다.
     */
    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer.usePathSegment(1)
                  .setDefaultVersion("1.0");
    }

    /**
     * 경로 매칭 설정.
     * @RestController가 붙은 클래스에 "/api/v{version}" 접두사를 자동 추가한다.
     * {version}은 위의 API 버전 설정과 연동된다.
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v{version}",
                HandlerTypePredicate.forAnnotation(RestController.class));
    }
}
