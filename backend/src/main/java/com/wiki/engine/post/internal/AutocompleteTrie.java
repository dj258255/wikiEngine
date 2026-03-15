package com.wiki.engine.post.internal;

import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 인메모리 Trie 자료구조.
 * Copy-on-Write 패턴: 새 Trie를 별도로 빌드한 뒤 volatile 참조를 교체한다.
 * 읽기는 락 없이 volatile read로 수행.
 */
@Component
public class AutocompleteTrie {

    private volatile TrieNode root = new TrieNode();

    /**
     * 단어 삽입 — O(m). 배치 빌드 전용.
     * 새 TrieNode root에 삽입한 뒤 swapRoot()로 교체해야 한다.
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
     * Prefix로 시작하는 단어 검색 — O(m + k).
     * m = prefix 길이, k = 결과 수.
     * 읽기 전용 — volatile reference로 안전.
     */
    public List<String> search(String prefix, int limit) {
        TrieNode current = root;

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
     * 배치 빌드 완료 후 새 root로 교체.
     * volatile write로 모든 읽기 스레드에 즉시 가시.
     */
    void swapRoot(TrieNode newRoot) {
        this.root = newRoot;
    }

    int size() {
        return countWords(root);
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

    private int countWords(TrieNode node) {
        int count = node.isEndOfWord() ? 1 : 0;
        for (TrieNode child : node.getChildren().values()) {
            count += countWords(child);
        }
        return count;
    }

    private record ScoredWord(String word, double score) {}
}
