package com.wiki.engine.post.internal;

/**
 * 한글 자모 분해 유틸리티.
 *
 * 한글 음절(U+AC00~U+D7AF)을 초성/중성/종성으로 분해한다.
 * 유니코드 한글 음절 공식: (초성 × 21 + 중성) × 28 + 종성 + 0xAC00
 *
 * 용도:
 * - 자모 분해: "삼성" → "ㅅㅏㅁㅅㅓㅇ" (Trie에 삽입하여 중간 입력 매칭)
 * - 초성 추출: "삼성" → "ㅅㅅ" (초성 검색)
 * - 입력 판별: 자모 문자 포함 여부로 자모 Trie vs 원본 Trie 선택
 */
final class JamoDecomposer {

    private static final int HANGUL_BASE = 0xAC00;
    private static final int HANGUL_END = 0xD7AF;
    private static final int JONGSEONG_COUNT = 28;
    private static final int JUNGSEONG_COUNT = 21;

    // 초성 19개
    private static final char[] CHOSEONG = {
            'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ',
            'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    // 종성 28개 (0번 = 종성 없음)
    private static final char[] JONGSEONG = {
            0, 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ',
            'ㄺ', 'ㄻ', 'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ',
            'ㅄ', 'ㅅ', 'ㅆ', 'ㅇ', 'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    };

    private JamoDecomposer() {
    }

    /**
     * 한글 문자열을 자모 시퀀스로 분해.
     * "삼성" → "ㅅㅏㅁㅅㅓㅇ"
     * 비한글 문자는 그대로 유지.
     */
    static String decompose(String text) {
        StringBuilder sb = new StringBuilder(text.length() * 3);
        for (char c : text.toCharArray()) {
            if (isHangulSyllable(c)) {
                int code = c - HANGUL_BASE;
                int cho = code / (JUNGSEONG_COUNT * JONGSEONG_COUNT);
                int jung = (code % (JUNGSEONG_COUNT * JONGSEONG_COUNT)) / JONGSEONG_COUNT;
                int jong = code % JONGSEONG_COUNT;

                sb.append(CHOSEONG[cho]);
                sb.append((char) (0x314F + jung)); // 중성: ㅏ=0x314F
                if (jong > 0) {
                    sb.append(JONGSEONG[jong]);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 초성만 추출.
     * "삼성" → "ㅅㅅ"
     * 이미 자모인 문자(ㄱ~ㅎ)는 그대로 포함.
     */
    static String extractChoseong(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (isHangulSyllable(c)) {
                int code = c - HANGUL_BASE;
                sb.append(CHOSEONG[code / (JUNGSEONG_COUNT * JONGSEONG_COUNT)]);
            } else if (isChoseong(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 입력에 자모(ㄱ~ㅎ, ㅏ~ㅣ)가 포함되어 있는지 판별.
     * 자모가 포함되면 자모 Trie에서 검색, 없으면 원본 Trie에서 검색.
     */
    static boolean containsJamo(String text) {
        for (char c : text.toCharArray()) {
            if (isChoseong(c) || isJungseong(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isHangulSyllable(char c) {
        return c >= HANGUL_BASE && c <= HANGUL_END;
    }

    private static boolean isChoseong(char c) {
        return c >= 'ㄱ' && c <= 'ㅎ';
    }

    private static boolean isJungseong(char c) {
        return c >= 'ㅏ' && c <= 'ㅣ';
    }
}
