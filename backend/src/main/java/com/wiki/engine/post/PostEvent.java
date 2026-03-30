package com.wiki.engine.post;

/**
 * PostService 쓰기 작업의 도메인 이벤트.
 * AFTER_COMMIT 리스너에서 소비되어 Read Model(Lucene, 캐시)을 갱신한다.
 *
 * <p>Spring ApplicationEvent 기반. in-process이므로 Post 엔티티를 직접 전달.
 * Outbox 패턴에서는 필요한 필드만 JSON 직렬화로 전환.
 */
public sealed interface PostEvent {

    Long postId();

    record Created(Long postId, Post post) implements PostEvent {}

    record Updated(Long postId, Post post) implements PostEvent {}

    record Deleted(Long postId) implements PostEvent {}

    record LikeChanged(Long postId) implements PostEvent {}
}
