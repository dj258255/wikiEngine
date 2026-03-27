package com.wiki.engine.post.internal.rag;

import com.wiki.engine.post.dto.PostSearchResponse;
import com.wiki.engine.post.internal.filter.ContentFilterService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 요약 트리거 조건 판단 서비스.
 *
 * Google AI Overviews, 네이버 Cue: 등 현업 검색엔진의 패턴을 참고하여,
 * 쿼리 의도(informational/navigational/transactional)와 검색 결과 품질을 기반으로
 * AI 요약 생성 여부를 결정한다.
 *
 * Google 실측: 전체 쿼리 중 AI Overview 표시율 7~15%, 질문형 쿼리는 25~30%.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSummaryDecisionService {

    private final ContentFilterService contentFilterService;

    /** 검색 결과 최소 건수 — 이보다 적으면 AI 요약 스킵 */
    private static final int MIN_HITS = 3;

    /** 쿼리 최소 길이 (자) — 1글자 검색은 AI 요약 불필요 */
    private static final int MIN_QUERY_LENGTH = 2;

    /** 질문형 패턴 — "~란", "~무엇", "~방법", "~원리", "~차이", "~비교", "~어떻게", "~왜" */
    private static final Pattern INFORMATIONAL_PATTERN = Pattern.compile(
            ".*(이란|란\\s|무엇|방법|원리|차이|비교|어떻게|왜|how|what|why|vs|versus|difference).*",
            Pattern.CASE_INSENSITIVE
    );

    /** 거래 의도 키워드 — 구매, 가격, 할인 등 */
    private static final Set<String> TRANSACTIONAL_KEYWORDS = Set.of(
            "구매", "가격", "할인", "예약", "배송", "최저가", "주문", "결제",
            "buy", "price", "order", "cheap", "discount", "shop"
    );

    /** 네비게이션 의도 — URL 패턴, 유명 사이트명 */
    private static final Set<String> NAVIGATIONAL_KEYWORDS = Set.of(
            "네이버", "다음", "구글", "유튜브", "쿠팡", "인스타그램", "카카오톡",
            "naver", "google", "youtube", "facebook", "instagram", "twitter"
    );

    private static final Pattern URL_PATTERN = Pattern.compile(
            ".*\\.(com|net|co\\.kr|org|io|dev).*", Pattern.CASE_INSENSITIVE
    );

    /**
     * AI 요약을 생성해야 하는지 판단한다.
     *
     * @param query   사용자 검색어
     * @param results 검색 결과 (이미 조회된 상태)
     * @return AI 요약 생성 여부 + 스킵 사유
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
        if (results.size() < MIN_HITS) {
            return Decision.skip("검색 결과 부족 (%d건)".formatted(results.size()));
        }

        // 6. 통과 — AI 요약 생성
        boolean isQuestion = INFORMATIONAL_PATTERN.matcher(lower).matches();
        log.debug("AI 요약 트리거: query='{}', questionPattern={}, resultCount={}",
                trimmed, isQuestion, results.size());
        return Decision.proceed();
    }

    public record Decision(boolean shouldGenerate, String skipReason) {
        static Decision proceed() { return new Decision(true, null); }
        static Decision skip(String reason) { return new Decision(false, reason); }
    }
}
