package com.fxbrief.feedback.repository;

import com.fxbrief.feedback.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    /**
     * Paginated newest-first list with the submitting user fetched eagerly so
     * the admin projection can render name + email without an N+1 lookup.
     */
    @Query(value = "SELECT f FROM Feedback f JOIN FETCH f.user u ORDER BY f.createdAt DESC, f.id DESC",
            countQuery = "SELECT COUNT(f) FROM Feedback f")
    Page<Feedback> findAllWithUser(Pageable pageable);
}
