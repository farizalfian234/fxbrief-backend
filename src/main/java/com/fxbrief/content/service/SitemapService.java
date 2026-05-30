package com.fxbrief.content.service;

import com.fxbrief.content.dto.SitemapEntryView;
import com.fxbrief.content.entity.SitemapEntry;
import com.fxbrief.content.repository.SitemapEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Maintains the public sitemap registry. Published content upserts a row keyed
 * by its public path; archived content removes it. Phase 4D reuses this for
 * articles.
 */
@Service
@RequiredArgsConstructor
public class SitemapService {

    private static final String DEFAULT_CHANGE_FREQ = "weekly";
    private static final BigDecimal DEFAULT_PRIORITY = new BigDecimal("0.7");

    private final SitemapEntryRepository sitemapEntryRepository;

    @Transactional
    public void upsert(String path, Instant lastModified) {
        SitemapEntry entry = sitemapEntryRepository.findByPath(path)
                .orElseGet(SitemapEntry::new);
        if (entry.getId() == null) {
            entry.setPath(path);
            entry.setChangeFreq(DEFAULT_CHANGE_FREQ);
            entry.setPriority(DEFAULT_PRIORITY);
        }
        entry.setLastModified(lastModified);
        sitemapEntryRepository.save(entry);
    }

    @Transactional
    public void remove(String path) {
        sitemapEntryRepository.deleteByPath(path);
    }

    @Transactional(readOnly = true)
    public List<SitemapEntryView> listAll() {
        return sitemapEntryRepository.findAllByOrderByPathAsc().stream()
                .map(e -> new SitemapEntryView(
                        e.getPath(),
                        e.getLastModified(),
                        e.getChangeFreq(),
                        e.getPriority()))
                .toList();
    }
}
