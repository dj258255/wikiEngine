package com.wiki.engine.post.internal.rag;

import com.wiki.engine.post.dto.PostSearchResponse;
import com.wiki.engine.post.internal.filter.ContentFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 요약 트리거 조건 판단 + Rate Limiting 서비스.
 *
 * Google AI Overviews, 네이버 Cue: 등 현업 검색엔진의 패턴을 참고하여,
 * 쿼리 의도(informational/navigational/transactional)와 검색 결과 품질,
 * 그리고 Gemini 무료 티어 rate limit(15 RPM)을 기반으로 AI 요약 생성 여부를 결정한다.
 *
 * Rate Limiting: Redis 기반 Token Bucket — 서버 2대 이상에서 전역 제한 공유.
 * 현업 표준: Stripe, AWS API Gateway, Spring Cloud Gateway 모두 Token Bucket 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryDecisionService {

    private final ContentFilterService contentFilterService;
    private final StringRedisTemplate redisTemplate;

    /** 검색 결과 최소 건수 (일반 쿼리) */
    private static final int MIN_HITS = 3;

    /** 검색 결과 최소 건수 (물음표 질문 — "자바 GC?" 등 명시적 질문은 결과 1건이라도 답변) */
    private static final int MIN_HITS_QUESTION = 1;

    /** 쿼리 최소 길이 (자) */
    private static final int MIN_QUERY_LENGTH = 2;

    /** 전역 Rate Limit — Gemini 무료 15 RPM, 여유 두고 10 RPM */
    private static final long GLOBAL_MAX_PER_MINUTE = 10;

    /** Redis rate limit 키 */
    private static final String RATE_KEY = "rag:ratelimit:global";

    /** Rate limit 윈도우 */
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    /** 질문형 패턴 */
    private static final Pattern INFORMATIONAL_PATTERN = Pattern.compile(
            ".*(이란|란\\s|무엇|방법|원리|차이|비교|어떻게|왜|how|what|why|vs|versus|difference).*",
            Pattern.CASE_INSENSITIVE
    );

    /** 거래 의도 키워드 */
    private static final Set<String> TRANSACTIONAL_KEYWORDS = Set.of(
            "구매", "가격", "할인", "예약", "배송", "최저가", "주문", "결제",
            "buy", "price", "order", "cheap", "discount", "shop"
    );

    /** 네비게이션 의도 */
    private static final Set<String> NAVIGATIONAL_KEYWORDS = Set.of(
            "네이버", "다음", "구글", "유튜브", "쿠팡", "인스타그램", "카카오톡",
            "naver", "google", "youtube", "facebook", "instagram", "twitter"
    );

    private static final Pattern URL_PATTERN = Pattern.compile(
            ".*\\.(com|net|co\\.kr|org|io|dev).*", Pattern.CASE_INSENSITIVE
    );

    /**
     * AI 요약을 생성해야 하는지 판단한다.
     */
    public Decision decide(String query, List<PostSearchResponse> results) {
        String trimmed = query.trim();

        // 1. 쿼리 길이 체크
        if (trimmed.length() < MIN_QUERY_LENGTH) {
            return Decision.skip("쿼리가 너무 짧음 (%d자)".formatted(trimmed.length()));
        }

        // 2. 금칙어 체크
        List<String> filtered = contentFilterService.filterSuggestions(List.of(trimmed));
        if (filtered.isEmpty()) {
            return Decision.skip("금칙어 포함 쿼리");
        }

        // 3. 네비게이션 의도 체크
        String lower = trimmed.toLowerCase();
        for (String nav : NAVIGATIONAL_KEYWORDS) {
            if (lower.equals(nav)) {
                return Decision.skip("네비게이션 의도 (사이트 이동)");
            }
        }
        if (URL_PATTERN.matcher(lower).matches()) {
            return Decision.skip("URL 패턴 감지");
        }

        // 4. 거래 의도 체크
        for (String tx : TRANSACTIONAL_KEYWORDS) {
            if (lower.contains(tx)) {
                return Decision.skip("거래 의도 (구매/가격)");
            }
        }

        // 5. 검색 결과 건수 체크
        //    물음표 질문("자바 GC?", "블록체인이란??")은 명시적 질문 의도 → 결과 1건이라도 AI 답변
        boolean isExplicitQuestion = lower.contains("?");
        int minHits = isExplicitQuestion ? MIN_HITS_QUESTION : MIN_HITS;
        if (results.size() < minHits) {
            return Decision.skip("검색 결과 부족 (%d건, 최소 %d건 필요)".formatted(results.size(), minHits));
        }

        // 6. Rate limit 체크 (Redis Token Bucket — 서버 2대 전역 공유)
        if (!tryAcquireRateLimit()) {
            return Decision.skip("Rate limit 초과 (분당 %d회 제한)".formatted(GLOBAL_MAX_PER_MINUTE));
        }

        // 7. 통과
        boolean isQuestion = INFORMATIONAL_PATTERN.matcher(lower).matches();
        log.debug("AI 요약 트리거: query='{}', questionPattern={}, resultCount={}",
                trimmed, isQuestion, results.size());
        return Decision.proceed();
    }

    /**
     * Redis 기반 Token Bucket Rate Limiter — Lua 스크립트로 INCR + EXPIRE를 atomic 실행.
     *
     * <p>이전 구현(INCR 후 count==1이면 EXPIRE)은 INCR과 EXPIRE 사이에 크래시하면
     * 키가 TTL 없이 영구 존재하여 rate limit이 영구 차단됨.
     * Lua 스크립트는 Redis 서버에서 단일 원자적 연산으로 실행되어 이 문제를 방지.
     *
     * <p>현업 표준: Stripe, AWS API Gateway, Spring Cloud Gateway 모두 Token Bucket 사용.
     */
    private static final String RATE_LIMIT_LUA = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    private boolean tryAcquireRateLimit() {
        try {
            var script = org.springframework.data.redis.core.script.RedisScript.of(
                    RATE_LIMIT_LUA, Long.class);
            Long count = redisTemplate.execute(script,
                    List.of(RATE_KEY),
                    String.valueOf(RATE_WINDOW.toSeconds()));
            return count != null && count <= GLOBAL_MAX_PER_MINUTE;
        } catch (Exception e) {
            // Redis 장애 시 rate limit 통과 (Gemini가 자체 429로 방어)
            log.debug("Redis rate limit 조회 실패 (통과 허용): {}", e.getMessage());
            return true;
        }
    }

    public record Decision(boolean shouldGenerate, String skipReason) {
        static Decision proceed() { return new Decision(true, null); }
        static Decision skip(String reason) { return new Decision(false, reason); }
    }
}
