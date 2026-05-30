package com.fxbrief.feedback.service;

import com.fxbrief.common.constants.ErrorCodes;
import com.fxbrief.common.exception.DomainException;
import com.fxbrief.feedback.dto.AdminFeedbackListView;
import com.fxbrief.feedback.dto.AdminFeedbackReplyView;
import com.fxbrief.feedback.dto.AdminFeedbackRowView;
import com.fxbrief.feedback.dto.FeedbackSubmittedView;
import com.fxbrief.feedback.dto.ReplyFeedbackRequest;
import com.fxbrief.feedback.dto.SubmitFeedbackRequest;
import com.fxbrief.feedback.entity.Feedback;
import com.fxbrief.feedback.entity.FeedbackStatus;
import com.fxbrief.feedback.repository.FeedbackRepository;
import com.fxbrief.user.entity.User;
import com.fxbrief.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Feedback domain service: user submission plus the admin read/reply surface.
 *
 * <p>No email is sent in this phase. PRD §10 specifies a user thank-you, an
 * admin notification on submit, and a reply email to the user — all of these
 * are Phase 5A (email notifications) and are explicitly out of scope here. The
 * persisted {@code reply_content} / {@code replied_at} columns are the seam
 * Phase 5A will read from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    static final int PAGE_SIZE = 20;

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;

    @Transactional
    public FeedbackSubmittedView submit(Long userId, SubmitFeedbackRequest request) {
        Feedback feedback = new Feedback();
        feedback.setUser(userRepository.getReferenceById(userId));
        feedback.setContent(request.content().trim());
        feedback.setStatus(FeedbackStatus.PENDING);
        feedbackRepository.save(feedback);
        log.info("Feedback submitted id={} user={}", feedback.getId(), userId);
        return new FeedbackSubmittedView(feedback.getId(), feedback.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public AdminFeedbackListView list(int page) {
        int requestedPage = Math.max(page, 1);
        Pageable pageable = PageRequest.of(requestedPage - 1, PAGE_SIZE);
        Page<Feedback> result = feedbackRepository.findAllWithUser(pageable);

        List<AdminFeedbackRowView> items = result.getContent().stream()
                .map(FeedbackService::toRowView)
                .toList();

        return new AdminFeedbackListView(
                items,
                result.getTotalElements(),
                requestedPage,
                PAGE_SIZE,
                result.getTotalPages());
    }

    @Transactional
    public AdminFeedbackReplyView reply(Long feedbackId, ReplyFeedbackRequest request) {
        Feedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.FEEDBACK_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "Feedback not found"));

        if (feedback.getStatus() == FeedbackStatus.REPLIED) {
            throw new DomainException(
                    ErrorCodes.FEEDBACK_ALREADY_REPLIED,
                    HttpStatus.CONFLICT,
                    "Feedback has already been replied to");
        }

        feedback.setReplyContent(request.replyContent().trim());
        feedback.setRepliedAt(Instant.now());
        feedback.setStatus(FeedbackStatus.REPLIED);
        feedbackRepository.save(feedback);

        log.info("Feedback replied id={}", feedbackId);

        return new AdminFeedbackReplyView(
                feedback.getId(),
                true,
                feedback.getRepliedAt(),
                feedback.getReplyContent());
    }

    private static AdminFeedbackRowView toRowView(Feedback feedback) {
        User user = feedback.getUser();
        boolean replied = feedback.getStatus() == FeedbackStatus.REPLIED;
        return new AdminFeedbackRowView(
                feedback.getId(),
                user.getId(),
                user.getName(),
                user.getEmail(),
                feedback.getContent(),
                feedback.getCreatedAt(),
                replied,
                feedback.getRepliedAt(),
                feedback.getReplyContent());
    }
}
