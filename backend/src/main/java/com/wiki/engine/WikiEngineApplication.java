package com.wiki.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Wiki Engine 애플리케이션 진입점.
 * Spring Boot 4.0.1 + Spring Modulith 기반의 위키 엔진 게시판 시스템.
 * 약 2,700만 건의 위키피디아 데이터를 활용한 대규모 CRUD 성능 최적화 프로젝트이다.
 */
@SpringBootApplication
public class WikiEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(WikiEngineApplication.class, args);
    }
}
