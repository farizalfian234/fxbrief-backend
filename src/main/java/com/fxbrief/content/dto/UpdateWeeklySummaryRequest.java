package com.fxbrief.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin edit of a draft. {@code adminContent} is the reviewed, publishable
 * copy. {@code status} is optional; when present it must be one of DRAFT,
 * PUBLISHED, or ARCHIVED and is validated in the service against the allowed
 * transitions. Publish and archive have dedicated endpoints that also maintain
 * the sitemap, so this field is primarily for moving a row back to DRAFT or
 * leaving the status unchanged.
 */
public record UpdateWeeklySummaryRequest(
        @NotBlank
        @Size(max = 50000)
        String adminContent,

        String status
) {}
