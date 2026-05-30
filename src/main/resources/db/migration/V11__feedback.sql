-- Phase 4B — user feedback.
--
-- One row per feedback submission. Created PENDING by the user-side submit
-- endpoint; an admin reply flips status to REPLIED and stamps replied_at and
-- reply_content. No email is sent this phase — the user thank-you, admin
-- notification, and reply email are Phase 5A concerns; the columns here are
-- the seam those flows will read from.
--
-- content and reply_content are TEXT (free-form, unbounded user/admin prose).
-- status is stored as the FeedbackStatus enum name (PENDING / REPLIED).
CREATE TABLE feedback (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL,
    content       TEXT         NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    replied_at    TIMESTAMPTZ,
    reply_content TEXT,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT fk_feedback_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Admin list orders newest-first and pages 20 at a time.
CREATE INDEX idx_feedback_created_at
    ON feedback (created_at DESC);
