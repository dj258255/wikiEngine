package com.wiki.engine.post.internal.filter;

import jakarta.persistence.*;

@Entity
@Table(name = "banned_words")
public class BannedWord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String word;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    public enum Category {
        PROFANITY, HATE_SPEECH, SPAM, ADULT, PERSONAL_INFO
    }

    public String getWord() { return word; }
    public Category getCategory() { return category; }
    public void setWord(String word) { this.word = word; }
    public void setCategory(Category category) { this.category = category; }
}
