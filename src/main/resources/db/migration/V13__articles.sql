-- Admin-managed article system. One row per article. Markdown content is
-- stored as-is and rendered by the frontend. Articles join the content/
-- module introduced in Phase 4C and reuse the existing sitemap_entries
-- registry (no schema change to that table).
--
-- Status lifecycle: DRAFT -> SCHEDULED -> PUBLISHED -> ARCHIVED. A scheduled
-- publish job promotes SCHEDULED rows whose scheduled_publish_at has passed.
CREATE TABLE articles (
    id                    BIGSERIAL    PRIMARY KEY,
    title                 VARCHAR(255) NOT NULL,
    slug                  VARCHAR(255) NOT NULL,
    content               TEXT         NOT NULL,
    excerpt               VARCHAR(500),
    category              VARCHAR(64)  NOT NULL,
    tags                  TEXT[],
    featured_image_url    VARCHAR(500),
    reading_time_minutes  SMALLINT,
    seo_title             VARCHAR(255),
    seo_description       VARCHAR(500),
    status                VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    scheduled_publish_at  TIMESTAMPTZ,
    published_at          TIMESTAMPTZ,
    author_id             BIGINT       NOT NULL REFERENCES users (id),
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_articles_slug UNIQUE (slug)
);

-- Public list pages PUBLISHED articles newest-first; admin list filters by
-- status. Both are served by the composite index below.
CREATE INDEX idx_articles_status_published_at
    ON articles (status, published_at DESC);

-- Public and admin list filters narrow by category.
CREATE INDEX idx_articles_category
    ON articles (category);

-- The scheduled-publish job scans for SCHEDULED rows whose time has come.
CREATE INDEX idx_articles_scheduled_publish_at
    ON articles (scheduled_publish_at)
    WHERE status = 'SCHEDULED';
