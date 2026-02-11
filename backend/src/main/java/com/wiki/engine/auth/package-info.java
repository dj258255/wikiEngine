/**
 * 인증 인프라 모듈.
 * JWT 토큰 발급/검증, 토큰 블랙리스트(로그아웃), 인증 필터를 담당한다.
 * 사용자 도메인 로직은 user 모듈에서 처리한다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"common"}
)
package com.wiki.engine.auth;
