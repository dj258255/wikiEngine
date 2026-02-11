/**
 * 사용자(User) 모듈.
 * 사용자 회원가입, 로그인, 조회, 비밀번호 관리를 담당한다.
 * auth 모듈의 JWT 인프라에 의존하여 토큰 발급 및 검증을 수행한다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"auth", "common"}
)
package com.wiki.engine.user;
