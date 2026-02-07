package com.wiki.engine.category;

import com.wiki.engine.category.internal.CategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 카테고리 비즈니스 로직 서비스.
 * 카테고리의 CRUD 및 게시글 수 관리를 담당한다.
 * 기본적으로 읽기 전용 트랜잭션이며, 쓰기 작업은 별도 @Transactional로 관리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CategoryService {

    private final CategoryRepository categoryRepository;

    /**
     * 새 카테고리를 생성한다.
     * 이름 중복 시 예외를 던진다.
     *
     * @param name 카테고리 이름
     * @param parentId 상위 카테고리 ID (최상위면 null)
     * @return 생성된 카테고리 엔티티
     */
    @Transactional
    public Category createCategory(String name, Long parentId) {
        if (categoryRepository.existsByName(name)) {
            throw new IllegalArgumentException("Category already exists: " + name);
        }

        Category category = Category.builder()
                .name(name)
                .parentId(parentId)
                .build();

        return categoryRepository.save(category);
    }

    /** ID로 카테고리를 조회한다. */
    public Optional<Category> findById(Long id) {
        return categoryRepository.findById(id);
    }

    /** 이름으로 카테고리를 조회한다. */
    public Optional<Category> findByName(String name) {
        return categoryRepository.findByName(name);
    }

    /** 전체 카테고리 목록을 조회한다. */
    public List<Category> findAll() {
        return categoryRepository.findAll();
    }

    /** 특정 상위 카테고리의 하위 카테고리 목록을 조회한다. */
    public List<Category> findByParentId(Long parentId) {
        return categoryRepository.findByParentId(parentId);
    }

    /** 최상위(루트) 카테고리 목록을 조회한다. (parentId가 null인 것들) */
    public List<Category> findRootCategories() {
        return categoryRepository.findByParentIdIsNull();
    }
}
