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
import com.fxbrief.notification.service.NotificationService;
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
 * <p>On submit, a thank-you email is sent to the user and a notification email
 * to the admin; on reply, the admin's reply is emailed to the user. All sends
 * are non-critical and fire after the surrounding transaction commits.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    static final int PAGE_SIZE = 20;

    private final FeedbackRepository feedbackRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Transactional
    public FeedbackSubmittedView submit(Long userId, SubmitFeedbackRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new DomainException(
                        ErrorCodes.USER_NOT_FOUND,
                        HttpStatus.NOT_FOUND,
                        "User not found"));

        Feedback feedback = new Feedback();
        feedback.setUser(user);
        feedback.setContent(request.content().trim());
        feedback.setStatus(FeedbackStatus.PENDING);
        feedbackRepository.save(feedback);
        log.info("Feedback submitted id={} user={}", feedback.getId(), userId);

        notificationService.sendFeedbackThankYouEmail(user.getEmail(), user.getName());
        notificationService.sendFeedbackAdminNotification(
                user.getName(), user.getEmail(), feedback.getContent(), feedback.getCreatedAt());

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

        User user = feedback.getUser();
        notificationService.sendFeedbackReplyEmail(
                user.getEmail(), user.getName(), feedback.getReplyContent());

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
