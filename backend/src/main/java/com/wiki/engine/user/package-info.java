/**
 * 사용자(User) 모듈.
 * 사용자 회원가입, 조회, 비밀번호 관리를 담당한다.
 * 외부 모듈에 의존하지 않는 독립 모듈이다.
 * auth 모듈에서 이 모듈에 의존하여 사용자 생성 및 조회를 수행한다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {}
)
package com.wiki.engine.user;
