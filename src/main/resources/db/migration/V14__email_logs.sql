-- Audit trail of every transactional email send attempt. One row per attempt,
-- written by the notification layer after the send is attempted. status is
-- SUCCESS when the provider accepted the message, FAILED otherwise; on failure
-- error_message carries the reason. Email is non-critical: a FAILED row never
-- blocks the user action that triggered the send.
CREATE TABLE email_logs (
    id            BIGSERIAL    PRIMARY KEY,
    recipient     VARCHAR(255) NOT NULL,
    subject       VARCHAR(255) NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    error_message TEXT,
    sent_at       TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL
);

-- Operational reads: "show recent sends" and "show every send to this address".
CREATE INDEX idx_email_logs_created_at ON email_logs (created_at DESC);
CREATE INDEX idx_email_logs_recipient ON email_logs (recipient);
