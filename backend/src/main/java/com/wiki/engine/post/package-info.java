/**
 * 게시글(Post) 모듈.
 * 게시글 CRUD, 좋아요, 조회수 관리를 담당한다.
 * 위키피디아 데이터(약 2,700만 건)가 이 모듈의 posts 테이블에 저장된다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"auth", "common"}
)
package com.wiki.engine.post;
