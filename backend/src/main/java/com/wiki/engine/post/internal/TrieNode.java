package com.wiki.engine.post.internal;

import java.util.HashMap;
import java.util.Map;

class TrieNode {

    private final Map<Character, TrieNode> children = new HashMap<>();
    private boolean endOfWord;
    private String word;
    private double score;

    TrieNode getChild(char c) {
        return children.get(c);
    }

    TrieNode addChild(char c) {
        return children.computeIfAbsent(c, k -> new TrieNode());
    }

    Map<Character, TrieNode> getChildren() {
        return children;
    }

    boolean isEndOfWord() {
        return endOfWord;
    }

    void setEndOfWord(boolean endOfWord) {
        this.endOfWord = endOfWord;
    }

    String getWord() {
        return word;
    }

    void setWord(String word) {
        this.word = word;
    }

    double getScore() {
        return score;
    }

    void setScore(double score) {
        this.score = score;
    }
}
