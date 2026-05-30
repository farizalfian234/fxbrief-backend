package com.fxbrief.content.repository;

import com.fxbrief.content.entity.SitemapEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SitemapEntryRepository extends JpaRepository<SitemapEntry, Long> {

    Optional<SitemapEntry> findByPath(String path);

    List<SitemapEntry> findAllByOrderByPathAsc();

    void deleteByPath(String path);
}
