package com.wiki.engine.config;

import com.wiki.engine.auth.CurrentUserArgumentResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.accept.ApiVersionParser;
import org.springframework.web.accept.ApiVersionResolver;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.ApiVersionConfigurer;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spring MVC 웹 설정.
 * Spring Boot 4의 네이티브 API 버전 관리 기능을 활용한다.
 * /api/v{N}/... 경로에서만 버전을 추출하며, Swagger 등 비-API 경로는 버전 파싱을 건너뛴다.
 *
 * 예시:
 * - @RequestMapping("/auth") → /api/v1.0/auth
 * - @RequestMapping("/posts") → /api/v1.0/posts
 * - /swagger-ui/index.html → 버전 파싱 없이 그대로 통과
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }

    @Override
    public void configureApiVersioning(ApiVersionConfigurer configurer) {
        configurer
                .useVersionResolver(new ApiPathVersionResolver())
                .setDefaultVersion("1.0")
                .setVersionRequired(false)
                .setVersionParser(new SimpleVersionParser());
    }

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix("/api/v{version}",
                HandlerTypePredicate.forAnnotation(RestController.class)
                        .and(HandlerTypePredicate.forBasePackage("com.wiki.engine")));
    }

    /**
     * /api/v{N}/... 경로에서만 버전을 추출하는 resolver.
     * Swagger, Actuator 등 비-API 경로는 null을 반환하여 버전 파싱을 건너뛴다.
     */
    private static class ApiPathVersionResolver implements ApiVersionResolver {

        private static final Pattern VERSION_PATTERN = Pattern.compile("^/api/v([\\d.]+)/.+");

        @Override
        public String resolveVersion(HttpServletRequest request) {
            Matcher matcher = VERSION_PATTERN.matcher(request.getRequestURI());
            return matcher.matches() ? matcher.group(1) : null;
        }
    }

    /**
     * 버전 문자열을 정규화하는 파서.
     * "v1" → "1.0", "1" → "1.0", "1.0" → "1.0"
     */
    private static class SimpleVersionParser implements ApiVersionParser<String> {

        @Override
        public String parseVersion(String version) {
            if (version == null) {
                return null;
            }
            if (version.startsWith("v") || version.startsWith("V")) {
                version = version.substring(1);
            }
            if (!version.contains(".")) {
                version = version + ".0";
            }
            return version;
        }
    }
}
