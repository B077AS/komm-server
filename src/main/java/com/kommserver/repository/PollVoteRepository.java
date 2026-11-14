package com.kommserver.repository;

import com.kommserver.model.db.PollVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PollVoteRepository extends JpaRepository<PollVote, PollVote.PollVoteId> {

    @Query("SELECT v FROM PollVote v WHERE v.id.messageId IN :messageIds ORDER BY v.votedAt ASC")
    List<PollVote> findByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);

    @Modifying
    @Query("DELETE FROM PollVote v WHERE v.id.messageId = :messageId AND v.id.userId = :userId")
    void deleteByMessageIdAndUserId(@Param("messageId") UUID messageId, @Param("userId") UUID userId);

    @Modifying
    @Query("DELETE FROM PollVote v WHERE v.id.messageId IN :messageIds")
    void deleteByMessageIdIn(@Param("messageIds") Collection<UUID> messageIds);
}
