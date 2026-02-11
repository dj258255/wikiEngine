/**
 * 카테고리(Category) 모듈.
 * 게시글 분류를 위한 계층형 카테고리를 관리한다.
 */
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"auth", "common"}
)
package com.wiki.engine.category;
