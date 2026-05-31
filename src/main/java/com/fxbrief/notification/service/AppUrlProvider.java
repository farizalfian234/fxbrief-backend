package com.fxbrief.notification.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Supplies the application base URL used for email links and the hosted logo.
 * Bound directly from {@code FRONTEND_BASE_URL} (the same value the auth flows
 * use as their link base) so the notification module stays self-contained and
 * does not depend on the auth module's properties.
 */
@Component
public class AppUrlProvider {

    private final String appUrl;

    public AppUrlProvider(@Value("${fxbrief.frontend.base-url}") String appUrl) {
        this.appUrl = appUrl;
    }

    public String appUrl() {
        return appUrl;
    }
}
