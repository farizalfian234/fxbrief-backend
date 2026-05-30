-- Weekly market summary and the public sitemap registry.
--
-- weekly_summaries holds one row per market week. The scheduler inserts a row
-- in DRAFT status with the Claude-generated draft in claude_draft; an admin
-- edits admin_content and publishes. Public endpoints expose admin_content
-- only — claude_draft is never returned to unauthenticated callers.
--
-- sitemap_entries is the registry the Phase 6A SSR build reads to generate
-- sitemap.xml. A weekly summary contributes a row on publish (keyed by its
-- public path) and the row is removed on archive. Articles (Phase 4D) reuse
-- the same table.
CREATE TABLE weekly_summaries (
    id            BIGSERIAL    PRIMARY KEY,
    week_start    DATE         NOT NULL,
    week_end      DATE         NOT NULL,
    title         VARCHAR(255) NOT NULL,
    slug          VARCHAR(255) NOT NULL,
    claude_draft  TEXT,
    admin_content TEXT,
    status        VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    published_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_weekly_summaries_slug UNIQUE (slug)
);

-- Public list pages PUBLISHED summaries newest-first; admin list filters by
-- status. Both are served by the indexed columns below.
CREATE INDEX idx_weekly_summaries_status_published_at
    ON weekly_summaries (status, published_at DESC);

CREATE TABLE sitemap_entries (
    id            BIGSERIAL    PRIMARY KEY,
    path          VARCHAR(500) NOT NULL,
    last_modified TIMESTAMPTZ  NOT NULL,
    change_freq   VARCHAR(20)  NOT NULL DEFAULT 'weekly',
    priority      DECIMAL(2,1) NOT NULL DEFAULT 0.7,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_sitemap_entries_path UNIQUE (path)
);
