package com.wiki.engine.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePostRequest(
        @NotBlank @Size(max = 512) String title,
        @NotBlank String content,
        Long categoryId
) {}
