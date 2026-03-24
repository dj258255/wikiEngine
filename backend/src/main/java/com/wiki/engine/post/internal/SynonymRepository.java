package com.wiki.engine.post.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SynonymRepository extends JpaRepository<Synonym, Long> {

    List<Synonym> findByTermIgnoreCase(String term);
}
