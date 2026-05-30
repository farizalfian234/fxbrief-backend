package com.fxbrief.content.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One public sitemap entry. The Phase 6A SSR build consumes the full list to
 * generate sitemap.xml.
 */
public record SitemapEntryView(
        String path,
        Instant lastModified,
        String changeFreq,
        BigDecimal priority
) {}
