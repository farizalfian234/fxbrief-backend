package com.fxbrief.notification.service;

import org.springframework.stereotype.Component;

/**
 * Renders the HTML and plain-text bodies for every FX–Brief transactional email.
 *
 * <p>All emails share a common shell: a deep-navy header carrying the FX–Brief
 * logo (hosted at {@code APP_URL/assets/images/logo-white.png}), a white-background
 * body, and a footer with the legal links. Buttons are deep-navy with white text.
 * Layout is inline-styled and mobile-first so it renders consistently across mail
 * clients, and every HTML email ships with a plain-text alternative.
 *
 * <p>Subject lines and body copy are fixed by the FX–Brief Email Templates spec.
 * The {@code appUrl} passed in is the application base URL (the same value the
 * auth flows build their links from); all links and the logo image resolve
 * against it.
 */
@Component
public class EmailTemplates {

    private static final String NAVY = "#0A1F44";
    private static final String LOGO_PATH = "/assets/images/logo-white.png";

    // ---- Email verification -------------------------------------------------

    public String verificationSubject() {
        return "Verify your FX–Brief account";
    }

    public String verificationHtml(String appUrl, String name, String verificationUrl) {
        String body = paragraph("Hi " + esc(name) + ",")
                + paragraph("Welcome to FX–Brief! Before you can start receiving your daily market "
                    + "briefing, we need to verify your email address.")
                + paragraph("Click the button below to activate your account. This link expires in 24 hours.")
                + button(verificationUrl, "Verify My Email")
                + paragraph("If the button doesn’t work, copy and paste this link into your browser: "
                    + link(verificationUrl, verificationUrl))
                + paragraph("Didn’t create an account? You can safely ignore this email.");
        return shell(appUrl, body);
    }

    public String verificationText(String name, String verificationUrl) {
        return "Hi " + name + ",\n\n"
                + "Welcome to FX–Brief! Before you can start receiving your daily market briefing, "
                + "we need to verify your email address.\n\n"
                + "Click the link below to activate your account. This link expires in 24 hours.\n\n"
                + verificationUrl + "\n\n"
                + "Didn’t create an account? You can safely ignore this email.\n\n"
                + footerText();
    }

    // ---- Welcome ------------------------------------------------------------

    public String welcomeSubject(String name) {
        return "Welcome to FX–Brief, " + name + "!";
    }

    public String welcomeHtml(String appUrl, String name) {
        String body = paragraph("Hi " + esc(name) + ",")
                + paragraph("Your account is ready. You have 3 free reports to get started — no credit "
                    + "card needed.")
                + paragraph("Every trading day, click Generate and FX–Brief filters the market for you — "
                    + "structured analysis on the pairs that matter, combining technical structure, key "
                    + "zones, and fundamental context.")
                + paragraph("Your free reports are ready to use whenever you are.")
                + button(appUrl + "/dashboard", "Generate My First Report")
                + paragraph("Questions? Use the feedback button on your Account page — we read every message.");
        return shell(appUrl, body);
    }

    public String welcomeText(String appUrl, String name) {
        return "Hi " + name + ",\n\n"
                + "Your account is ready. You have 3 free reports to get started — no credit card needed.\n\n"
                + "Every trading day, click Generate and FX–Brief filters the market for you — structured "
                + "analysis on the pairs that matter, combining technical structure, key zones, and "
                + "fundamental context.\n\n"
                + "Your free reports are ready to use whenever you are.\n\n"
                + "Generate your first report: " + appUrl + "/dashboard\n\n"
                + "Questions? Use the feedback button on your Account page — we read every message.\n\n"
                + footerText();
    }

    // ---- Reports exhausted --------------------------------------------------

    public String reportsExhaustedSubject() {
        return "You’ve used all your reports";
    }

    public String reportsExhaustedHtml(String appUrl, String name) {
        String body = paragraph("Hi " + esc(name) + ",")
                + paragraph("You’ve used all your available reports.")
                + paragraph("To keep generating daily reports, top up anytime from inside the app. Your "
                    + "remaining reports never expire once topped up — use them at your own pace.")
                + button(appUrl + "/account", "Top Up Now")
                + paragraph("Questions? Use the feedback button on your Account page — we read every message.");
        return shell(appUrl, body);
    }

    public String reportsExhaustedText(String appUrl, String name) {
        return "Hi " + name + ",\n\n"
                + "You’ve used all your available reports.\n\n"
                + "To keep generating daily reports, top up anytime from inside the app. Your remaining "
                + "reports never expire once topped up — use them at your own pace.\n\n"
                + "Top up now: " + appUrl + "/account\n\n"
                + "Questions? Use the feedback button on your Account page — we read every message.\n\n"
                + footerText();
    }

    // ---- Feedback thank-you (to user) ---------------------------------------

    public String feedbackThankYouSubject() {
        return "Thanks for your feedback";
    }

    public String feedbackThankYouHtml(String appUrl, String name) {
        String body = paragraph("Hi " + esc(name) + ",")
                + paragraph("We received your feedback and we appreciate you taking the time to share it. "
                    + "We read every message.")
                + paragraph("If anything comes up or you have more to share, the feedback button is always "
                    + "there on your Account page.");
        return shell(appUrl, body);
    }

    public String feedbackThankYouText(String name) {
        return "Hi " + name + ",\n\n"
                + "We received your feedback and we appreciate you taking the time to share it. We read "
                + "every message.\n\n"
                + "If anything comes up or you have more to share, the feedback button is always there on "
                + "your Account page.\n\n"
                + footerText();
    }

    // ---- Feedback notification (to admin) -----------------------------------

    public String feedbackAdminSubject(String name) {
        return "New feedback from " + name;
    }

    public String feedbackAdminHtml(String appUrl, String name, String email,
                                    String feedbackContent, String timestamp) {
        String body = paragraph("New feedback received from " + esc(name) + " (" + esc(email) + ").")
                + paragraph(esc(feedbackContent))
                + paragraph("Submitted at " + esc(timestamp));
        return shell(appUrl, body);
    }

    public String feedbackAdminText(String name, String email, String feedbackContent, String timestamp) {
        return "New feedback received from " + name + " (" + email + ").\n\n"
                + feedbackContent + "\n\n"
                + "Submitted at " + timestamp + "\n\n"
                + footerText();
    }

    // ---- Account deletion request -------------------------------------------

    public String deletionSubject() {
        return "Your account deletion request";
    }

    public String deletionHtml(String appUrl, String name, String date) {
        String body = paragraph("Hi " + esc(name) + ",")
                + paragraph("We received your request to delete your FX–Brief account. Your account is "
                    + "scheduled for permanent deletion on " + esc(date) + ".")
                + paragraph("Changed your mind? Simply log back in before " + esc(date) + " and you’ll have "
                    + "the option to cancel the deletion request and keep your account.")
                + paragraph("If you didn’t make this request, please log in immediately and secure your account.");
        return shell(appUrl, body);
    }

    public String deletionText(String name, String date) {
        return "Hi " + name + ",\n\n"
                + "We received your request to delete your FX–Brief account. Your account is scheduled for "
                + "permanent deletion on " + date + ".\n\n"
                + "Changed your mind? Simply log back in before " + date + " and you’ll have the option to "
                + "cancel the deletion request and keep your account.\n\n"
                + "If you didn’t make this request, please log in immediately and secure your account.\n\n"
                + footerText();
    }

    // ---- Feedback reply (admin -> user) -------------------------------------

    public String feedbackReplySubject() {
        return "Re: Your feedback on FX–Brief";
    }

    public String feedbackReplyHtml(String appUrl, String name, String replyContent) {
        String body = paragraph("Hi " + esc(name) + ",")
                + paragraph("We’re following up on your recent feedback.")
                + paragraph(esc(replyContent));
        return shell(appUrl, body);
    }

    public String feedbackReplyText(String name, String replyContent) {
        return "Hi " + name + ",\n\n"
                + "We’re following up on your recent feedback.\n\n"
                + replyContent + "\n\n"
                + footerText();
    }

    // ---- Shared shell -------------------------------------------------------

    private String shell(String appUrl, String bodyContent) {
        String logoUrl = stripTrailingSlash(appUrl) + LOGO_PATH;
        return "<!DOCTYPE html>"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "</head>"
                + "<body style=\"margin:0;padding:0;background-color:#f4f5f7;"
                + "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;"
                + "color:#1a1a1a;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"max-width:600px;margin:0 auto;\">"
                + "<tr><td style=\"background-color:" + NAVY + ";padding:24px;text-align:center;\">"
                + "<img src=\"" + logoUrl + "\" alt=\"FX–Brief\" height=\"32\" "
                + "style=\"height:32px;display:inline-block;\">"
                + "</td></tr>"
                + "<tr><td style=\"background-color:#ffffff;padding:32px 24px;\">"
                + bodyContent
                + "</td></tr>"
                + "<tr><td style=\"padding:20px 24px;text-align:center;font-size:12px;color:#8a8f98;\">"
                + footerHtml(appUrl)
                + "</td></tr>"
                + "</table></body></html>";
    }

    private String paragraph(String content) {
        return "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.6;\">" + content + "</p>";
    }

    private String button(String url, String label) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" "
                + "style=\"margin:8px 0 24px;\"><tr><td "
                + "style=\"background-color:" + NAVY + ";border-radius:6px;\">"
                + "<a href=\"" + url + "\" style=\"display:inline-block;padding:12px 28px;"
                + "color:#ffffff;text-decoration:none;font-size:15px;font-weight:600;\">"
                + esc(label) + "</a></td></tr></table>";
    }

    private String link(String url, String text) {
        return "<a href=\"" + url + "\" style=\"color:" + NAVY + ";\">" + esc(text) + "</a>";
    }

    private String footerHtml(String appUrl) {
        String base = stripTrailingSlash(appUrl);
        String linkStyle = "color:#8a8f98;text-decoration:underline;";
        String privacy = "<a href=\"" + base + "/privacy\" style=\"" + linkStyle + "\">Privacy Policy</a>";
        String terms = "<a href=\"" + base + "/terms\" style=\"" + linkStyle + "\">Terms of Service</a>";
        String support = "<a href=\"" + base + "/support\" style=\"" + linkStyle + "\">Support</a>";
        return "© 2026 FX–Brief · " + privacy + " · " + terms + " · " + support;
    }

    private String footerText() {
        return "© 2026 FX–Brief · Privacy Policy · Terms of Service · Support";
    }

    private String stripTrailingSlash(String base) {
        if (base == null || base.isBlank()) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private String esc(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
