package com.kommserver.websocket.messages.payloads;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Current state of a poll as sent to clients (history pages, new messages, {@code CHANNEL_POLL_UPDATED}). */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PollSummary {
    private boolean allowMultiple;
    private boolean anonymous;
    private LocalDateTime closesAt;
    private boolean closed;
    /** Distinct users who voted. */
    private int totalVoters;
    private List<Option> options;
    /**
     * The receiving user's own choices. Null in broadcasts (the same message goes to everyone,
     * so receivers keep what they have); set in history pages and in the copy sent to the voter.
     */
    private List<UUID> myOptionIds;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Option {
        private UUID optionId;
        private String text;
        private int votes;
        /** Who picked this option, oldest first; null for anonymous polls. */
        private List<UUID> voterIds;
    }
}
