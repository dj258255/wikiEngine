/**
 * 인증(Authentication) 모듈.
 * JWT 기반 인증/인가, 토큰 발급, 토큰 블랙리스트(로그아웃) 기능을 담당한다.
 * user 모듈에 의존하여 사용자 조회 및 생성을 위임한다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"user"}
)
package com.wiki.engine.auth;
