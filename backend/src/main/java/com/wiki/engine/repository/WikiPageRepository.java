package com.wiki.engine.repository;

import com.wiki.engine.entity.WikiPage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WikiPageRepository extends JpaRepository<WikiPage, Long> {
}
