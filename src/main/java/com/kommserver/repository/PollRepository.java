package com.kommserver.repository;

import com.kommserver.model.db.Poll;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PollRepository extends JpaRepository<Poll, UUID> {

    /** Row lock that serializes votes/closing on one poll for the rest of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Poll p WHERE p.messageId = :messageId")
    Optional<Poll> findForUpdate(@Param("messageId") UUID messageId);

    List<Poll> findByMessageIdIn(Collection<UUID> messageIds);

    @Query("SELECT p FROM Poll p WHERE p.closedAt IS NULL AND p.closesAt IS NOT NULL AND p.closesAt <= :now")
    List<Poll> findExpiredOpen(@Param("now") LocalDateTime now);

    @Modifying
    @Query("DELETE FROM Poll p WHERE p.messageId IN :messageIds")
    void deleteByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);
}
