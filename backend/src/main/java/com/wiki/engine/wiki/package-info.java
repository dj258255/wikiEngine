/**
 * 위키(Wiki) 모듈.
 * 위키피디아 덤프 XML 파일을 파싱하여 DB에 임포트하는 기능을 담당한다.
 * StAX 스트리밍 파서와 JdbcTemplate 배치 INSERT로 대량 데이터를 처리한다.
 * 외부 모듈에 의존하지 않는 독립 모듈이다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {}
)
package com.wiki.engine.wiki;
