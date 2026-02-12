package com.wiki.engine.post;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 게시글-태그 연결 엔티티 (다대다 관계).
 * 하나의 게시글에 여러 태그를, 하나의 태그에 여러 게시글을 연결한다.
 */
@Entity
@Table(name = "post_tags",
        uniqueConstraints = @UniqueConstraint(name = "uk_post_tags_post_tag", columnNames = {"post_id", "tag_id"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PostTag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 게시글 ID
     */
    @Column(name = "post_id", nullable = false)
    private Long postId;

    /** 태그 ID */
    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    public PostTag(Long postId, Long tagId) {
        this.postId = postId;
        this.tagId = tagId;
    }
}
