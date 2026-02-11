package com.wiki.engine.category;

import com.wiki.engine.auth.CurrentUser;
import com.wiki.engine.auth.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 카테고리 REST API 컨트롤러.
 * WebConfig에 의해 /api/v1.0/categories 경로로 매핑된다.
 * 카테고리 조회는 인증 없이 허용, 생성은 인증 필요.
 */
@RestController
@RequestMapping(path = "/categories", version = "1.0")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryService categoryService;

    /** 전체 카테고리 목록 조회 */
    @GetMapping
    public List<Category> getCategories() {
        return categoryService.findAll();
    }

    /** 최상위 카테고리 목록 조회 (parentId가 null인 것들) */
    @GetMapping("/root")
    public List<Category> getRootCategories() {
        return categoryService.findRootCategories();
    }

    /** 하위 카테고리 목록 조회 */
    @GetMapping("/{parentId}/children")
    public List<Category> getChildCategories(@PathVariable Long parentId) {
        return categoryService.findByParentId(parentId);
    }

    /** 카테고리 생성 (인증 필요) */
    @PostMapping
    public ResponseEntity<Category> createCategory(
            @RequestParam String name,
            @RequestParam(required = false) Long parentId,
            @CurrentUser UserPrincipal currentUser) {

        Category category = categoryService.createCategory(name, parentId);
        return ResponseEntity.status(HttpStatus.CREATED).body(category);
    }
}
