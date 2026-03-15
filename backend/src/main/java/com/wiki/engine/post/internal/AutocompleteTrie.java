package com.wiki.engine.post.internal;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 인메모리 Trie 자료구조 (원본 + 자모 분해).
 * Copy-on-Write 패턴: 새 Trie를 별도로 빌드한 뒤 volatile 참조를 교체한다.
 * 읽기는 락 없이 volatile read로 수행.
 *
 * 두 개의 Trie를 관리:
 * - 원본 Trie: 완성된 음절 기반 검색 ("삼성" → "삼성전자")
 * - 자모 Trie: 자모 분해 기반 검색 ("ㅅㅏㅁㅅ" → "삼성전자")
 *
 * 검색 시 입력에 자모(ㄱ~ㅎ, ㅏ~ㅣ)가 포함되면 자모 Trie에서,
 * 완성된 음절이면 원본 Trie에서 검색한다.
 */
@Component
public class AutocompleteTrie {

    private volatile TrieNode root = new TrieNode();
    private volatile TrieNode jamoRoot = new TrieNode();

    /**
     * 원본 Trie에 삽입 — O(m).
     */
    void insert(TrieNode root, String word, double score) {
        TrieNode current = root;
        for (char c : word.toCharArray()) {
            current = current.addChild(c);
        }
        current.setEndOfWord(true);
        current.setWord(word);
        current.setScore(score);
    }

    /**
     * 원본 + 자모 분해 + 초성을 한 번에 삽입.
     * word = 원본 단어 (예: "삼성전자")
     * score = 인기도 점수
     *
     * 삽입되는 것:
     * 1. 원본 Trie: "삼성전자" → word="삼성전자"
     * 2. 자모 Trie: "ㅅㅏㅁㅅㅓㅇㅈㅓㄴㅈㅏ" → word="삼성전자" (원본으로 매핑)
     * 3. 자모 Trie: "ㅅㅅㅈㅈ" (초성) → word="삼성전자" (2자 이상만)
     */
    void insertWithJamo(TrieNode originalRoot, TrieNode jamoRoot, String word, double score) {
        // 1. 원본 삽입
        insert(originalRoot, word, score);

        // 2. 자모 분해 삽입 (원본 단어로 매핑)
        String decomposed = JamoDecomposer.decompose(word);
        insertMapped(jamoRoot, decomposed, word, score);

        // 3. 초성 삽입 (2자 이상)
        String choseong = JamoDecomposer.extractChoseong(word);
        if (choseong.length() >= 2) {
            insertMapped(jamoRoot, choseong, word, score);
        }
    }

    /**
     * Prefix로 시작하는 단어 검색.
     * 입력에 자모가 포함되면 자모 Trie, 아니면 원본 Trie에서 검색.
     */
    /**
     * Prefix로 시작하는 단어 검색.
     *
     * 검색 전략:
     * 1. 입력에 자모(ㄱ~ㅎ, ㅏ~ㅣ)가 포함 → 전체를 자모 분해 → 자모 Trie 검색
     *    예: "삼ㅅ" → "ㅅㅏㅁㅅ" → 자모 Trie에서 "삼성전자" 매칭
     *    예: "ㅅㅅ" → 그대로 → 자모 Trie에서 초성 매칭
     * 2. 완성된 음절만 → 원본 Trie 검색 → 없으면 자모 분해 → 자모 Trie fallback
     *    예: "삼성" → 원본 Trie에서 "삼성전자" 매칭
     */
    public List<String> search(String prefix, int limit) {
        if (JamoDecomposer.containsJamo(prefix)) {
            // 자모 포함: 전체를 분해하여 자모 Trie 검색
            // "삼ㅅ" → "ㅅㅏㅁㅅ", "ㅅㅅ" → "ㅅㅅ" (이미 자모)
            String decomposed = JamoDecomposer.decompose(prefix);
            return searchInTrie(jamoRoot, decomposed, limit);
        }
        // 완성 음절만: 원본 Trie 우선
        List<String> results = searchInTrie(root, prefix, limit);
        if (!results.isEmpty()) {
            return results;
        }
        // 원본에 없으면 자모 분해 후 자모 Trie fallback
        String decomposed = JamoDecomposer.decompose(prefix);
        return searchInTrie(jamoRoot, decomposed, limit);
    }

    void swapRoots(TrieNode newRoot, TrieNode newJamoRoot) {
        this.root = newRoot;
        this.jamoRoot = newJamoRoot;
    }

    // 하위 호환 (Phase 2 코드 유지)
    void swapRoot(TrieNode newRoot) {
        this.root = newRoot;
    }

    private List<String> searchInTrie(TrieNode trieRoot, String prefix, int limit) {
        TrieNode current = trieRoot;

        for (char c : prefix.toCharArray()) {
            current = current.getChild(c);
            if (current == null) {
                return List.of();
            }
        }

        PriorityQueue<ScoredWord> heap = new PriorityQueue<>(
                Comparator.comparingDouble(ScoredWord::score));

        collectWords(current, heap, limit);

        List<ScoredWord> results = new ArrayList<>(heap);
        results.sort(Comparator.comparingDouble(ScoredWord::score).reversed());
        return results.stream().map(ScoredWord::word).toList();
    }

    /**
     * 자모 Trie 전용 삽입: key로 탐색하되 word(원본)를 저장.
     * "ㅅㅏㅁㅅㅓㅇ" 경로로 내려가지만, 노드에는 "삼성" 원본을 저장.
     */
    private void insertMapped(TrieNode root, String key, String originalWord, double score) {
        TrieNode current = root;
        for (char c : key.toCharArray()) {
            current = current.addChild(c);
        }
        // 점수가 더 높은 것만 갱신 (같은 자모 경로에 여러 단어가 매핑될 수 있음)
        if (!current.isEndOfWord() || score > current.getScore()) {
            current.setEndOfWord(true);
            current.setWord(originalWord);
            current.setScore(score);
        }
    }

    private void collectWords(TrieNode node, PriorityQueue<ScoredWord> heap, int limit) {
        if (node.isEndOfWord()) {
            if (heap.size() < limit) {
                heap.offer(new ScoredWord(node.getWord(), node.getScore()));
            } else if (node.getScore() > heap.peek().score()) {
                heap.poll();
                heap.offer(new ScoredWord(node.getWord(), node.getScore()));
            }
        }
        for (TrieNode child : node.getChildren().values()) {
            collectWords(child, heap, limit);
        }
    }

    private record ScoredWord(String word, double score) {}
}
