package com.wiki.engine.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 인증 필터.
 * 모든 HTTP 요청마다 한 번씩 실행되며(OncePerRequestFilter),
 * 쿠키에서 JWT 토큰을 추출하여 유효성을 검증한다.
 * 유효한 토큰이면 SecurityContext에 인증 정보를 설정하여 이후 요청에서 인증된 사용자로 처리한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklist tokenBlacklist;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);

        // 토큰이 존재하고, 유효하며, 블랙리스트에 없는 경우에만 인증 처리
        if (token != null && jwtTokenProvider.validateToken(token) && !tokenBlacklist.isBlacklisted(token)) {
            Long userId = jwtTokenProvider.getUserId(token);
            String username = jwtTokenProvider.getUsername(token);

            // principal에 userId를 설정하여 컨트롤러에서 authentication.getPrincipal()로 꺼낼 수 있도록 한다
            // 권한(Role)은 사용하지 않으므로 빈 리스트를 전달한다
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, List.of());

            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /**
     * HTTP 요청의 쿠키에서 "token" 이름의 JWT 토큰을 추출한다.
     *
     * @param request HTTP 요청
     * @return JWT 토큰 문자열, 없으면 null
     */
    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if ("token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
