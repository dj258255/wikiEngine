package com.wiki.engine.post.internal.filter;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BannedWordRepository extends JpaRepository<BannedWord, Long> {

    @Query("SELECT b.word FROM BannedWord b")
    List<String> findAllWords();
}
