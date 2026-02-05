package com.wiki.engine.category.internal;

import com.wiki.engine.category.Category;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 카테고리 JPA 레포지토리.
 * internal 패키지에 위치하여 모듈 외부에서 직접 접근할 수 없다.
 * 같은 모듈의 CategoryService를 통해서만 접근 가능하다.
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {

    /** 이름으로 카테고리 조회 */
    Optional<Category> findByName(String name);

    /** 이름 중복 확인 */
    boolean existsByName(String name);

    /** 특정 상위 카테고리의 하위 카테고리 목록 조회 */
    List<Category> findByParentId(Long parentId);

    /** 최상위(루트) 카테고리 목록 조회 (parentId가 null) */
    List<Category> findByParentIdIsNull();

    /**
     * 게시글 수를 원자적으로 1 증가시킨다.
     * JPQL UPDATE 쿼리로 DB 레벨에서 처리하여 동시성 안전을 보장한다.
     */
    @Modifying
    @Query("UPDATE Category c SET c.postCount = c.postCount + 1 WHERE c.id = :id")
    void incrementPostCount(@Param("id") Long id);

    /**
     * 게시글 수를 원자적으로 1 감소시킨다.
     * postCount > 0 조건으로 음수가 되지 않도록 보호한다.
     */
    @Modifying
    @Query("UPDATE Category c SET c.postCount = c.postCount - 1 WHERE c.id = :id AND c.postCount > 0")
    void decrementPostCount(@Param("id") Long id);
}
