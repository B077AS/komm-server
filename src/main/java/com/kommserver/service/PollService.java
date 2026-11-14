package com.kommserver.service;

import com.google.gson.Gson;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Message;
import com.kommserver.model.db.Permission;
import com.kommserver.model.db.Poll;
import com.kommserver.model.db.PollOption;
import com.kommserver.model.db.PollVote;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.MessageRepository;
import com.kommserver.repository.PollOptionRepository;
import com.kommserver.repository.PollRepository;
import com.kommserver.repository.PollVoteRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.MessageSentPayload;
import com.kommserver.websocket.messages.payloads.PollCreatePayload;
import com.kommserver.websocket.messages.payloads.PollSummary;
import com.kommserver.websocket.messages.payloads.PollUpdatedPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Channel polls: a {@link Message} of type {@code POLL} whose {@code content} is the question,
 * plus a {@link Poll} row (settings), {@link PollOption}s and {@link PollVote}s.
 *
 * <p>A vote replaces the voter's whole selection, so single and multiple choice are handled the
 * same way and a repeated request changes nothing. Votes on one poll are serialized by locking
 * its {@link Poll} row; the resulting state is broadcast after the transaction commits, so it
 * includes every vote committed before it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PollService {

    public static final int MAX_QUESTION_LENGTH = 300;
    public static final int MAX_OPTION_LENGTH = 100;
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 10;
    /** Longest allowed duration: 30 days. */
    public static final int MAX_DURATION_MINUTES = 30 * 24 * 60;

    private final Gson gson;
    private final PollRepository pollRepository;
    private final PollOptionRepository pollOptionRepository;
    private final PollVoteRepository pollVoteRepository;
    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final PermissionService permissionService;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ClientMessageSender clientMessageSender;

    // ── Create ────────────────────────────────────────────────────────────────

    /**
     * Trimmed, de-duplicated options if {@code sent} is a valid poll, otherwise null (the reason
     * is logged).
     */
    public List<String> validate(UUID userId, MessageSentPayload sent) {
        String question = sent.getContent() == null ? "" : sent.getContent().trim();
        PollCreatePayload poll = sent.getPoll();
        if (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH) {
            log.warn("Poll denied: question empty or longer than {} chars (userId={})", MAX_QUESTION_LENGTH, userId);
            return null;
        }
        if (poll == null || poll.getOptions() == null) {
            log.warn("Poll denied: no options (userId={})", userId);
            return null;
        }
        if (sent.isHasAttachments()) {
            log.warn("Poll denied: polls cannot carry attachments (userId={})", userId);
            return null;
        }
        LinkedHashMap<String, String> unique = new LinkedHashMap<>();
        for (String raw : poll.getOptions()) {
            if (raw == null) continue;
            String text = raw.trim();
            if (text.isEmpty()) continue;
            if (text.length() > MAX_OPTION_LENGTH) {
                log.warn("Poll denied: option longer than {} chars (userId={})", MAX_OPTION_LENGTH, userId);
                return null;
            }
            unique.putIfAbsent(text.toLowerCase(Locale.ROOT), text);
        }
        if (unique.size() < MIN_OPTIONS || unique.size() > MAX_OPTIONS) {
            log.warn("Poll denied: {} distinct options, need {}..{} (userId={})", unique.size(), MIN_OPTIONS, MAX_OPTIONS, userId);
            return null;
        }
        Integer duration = poll.getDurationMinutes();
        if (duration != null && duration > MAX_DURATION_MINUTES) {
            log.warn("Poll denied: duration {} min exceeds {} (userId={})", duration, MAX_DURATION_MINUTES, userId);
            return null;
        }
        return List.copyOf(unique.values());
    }

    /** Saves the poll message and its settings/options in one transaction. {@code options} come from {@link #validate}. */
    @Transactional
    public Message createPollMessage(UUID userId, UUID serverId, MessageSentPayload sent, List<String> options) {
        Message saved = messageRepository.save(Message.builder()
                .senderId(userId)
                .serverId(serverId)
                .channelId(sent.getChannelId())
                .content(sent.getContent().trim())
                .sentAt(LocalDateTime.now())
                .isEdited(false)
                .hasAttachments(false)
                .repliedToId(sent.getRepliedToId())
                .messageType(Message.MessageType.POLL)
                .build());

        PollCreatePayload spec = sent.getPoll();
        Integer duration = spec.getDurationMinutes();
        pollRepository.save(Poll.builder()
                .messageId(saved.getMessageId())
                .allowMultiple(spec.isAllowMultiple())
                .anonymous(spec.isAnonymous())
                .closesAt(duration != null && duration > 0 ? saved.getSentAt().plusMinutes(duration) : null)
                .build());

        List<PollOption> rows = new ArrayList<>(options.size());
        for (int i = 0; i < options.size(); i++) {
            rows.add(PollOption.builder()
                    .messageId(saved.getMessageId())
                    .position((short) i)
                    .text(options.get(i))
                    .build());
        }
        pollOptionRepository.saveAll(rows);
        return saved;
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Summaries for the polls among {@code messageIds} (non-poll ids are ignored), in three
     * queries regardless of how many polls there are. {@code viewerId} fills
     * {@link PollSummary#getMyOptionIds()}; null leaves it null.
     */
    @Transactional(readOnly = true)
    public Map<UUID, PollSummary> summaries(Collection<UUID> messageIds, UUID viewerId) {
        if (messageIds.isEmpty()) return Map.of();
        List<Poll> polls = pollRepository.findByMessageIdIn(messageIds);
        if (polls.isEmpty()) return Map.of();

        List<UUID> pollIds = polls.stream().map(Poll::getMessageId).toList();
        Map<UUID, List<PollOption>> optionsByPoll = pollOptionRepository.findByMessageIdInOrderByPositionAsc(pollIds)
                .stream().collect(Collectors.groupingBy(PollOption::getMessageId));
        Map<UUID, List<PollVote>> votesByPoll = pollVoteRepository.findByMessageIdIn(pollIds)
                .stream().collect(Collectors.groupingBy(v -> v.getId().getMessageId()));

        LocalDateTime now = LocalDateTime.now();
        Map<UUID, PollSummary> result = new HashMap<>();
        for (Poll poll : polls) {
            List<PollVote> votes = votesByPoll.getOrDefault(poll.getMessageId(), List.of());
            Map<UUID, List<UUID>> votersByOption = new HashMap<>();
            Set<UUID> voters = new HashSet<>();
            List<UUID> mine = viewerId == null ? null : new ArrayList<>();
            for (PollVote v : votes) {
                votersByOption.computeIfAbsent(v.getId().getOptionId(), k -> new ArrayList<>()).add(v.getId().getUserId());
                voters.add(v.getId().getUserId());
                if (mine != null && v.getId().getUserId().equals(viewerId)) mine.add(v.getId().getOptionId());
            }

            List<PollSummary.Option> options = optionsByPoll.getOrDefault(poll.getMessageId(), List.of()).stream()
                    .map(o -> {
                        List<UUID> optionVoters = votersByOption.getOrDefault(o.getOptionId(), List.of());
                        return PollSummary.Option.builder()
                                .optionId(o.getOptionId())
                                .text(o.getText())
                                .votes(optionVoters.size())
                                .voterIds(poll.isAnonymous() ? null : List.copyOf(optionVoters))
                                .build();
                    })
                    .toList();

            result.put(poll.getMessageId(), PollSummary.builder()
                    .allowMultiple(poll.isAllowMultiple())
                    .anonymous(poll.isAnonymous())
                    .closesAt(poll.getClosesAt())
                    .closed(poll.isClosedAt(now))
                    .totalVoters(voters.size())
                    .options(options)
                    .myOptionIds(mine)
                    .build());
        }
        return result;
    }

    // ── Vote / close ──────────────────────────────────────────────────────────

    /**
     * Replaces {@code userId}'s choices on the poll with {@code optionIds}. Returns the poll's
     * message on success (pass it to {@link #broadcastUpdate}), null when denied.
     */
    @Transactional
    public Message vote(UUID userId, UUID serverId, UUID messageId, List<UUID> optionIds) {
        Message message = accessiblePollMessage(userId, serverId, messageId, "vote");
        if (message == null) return null;

        Poll poll = pollRepository.findForUpdate(messageId).orElse(null);
        if (poll == null) {
            log.warn("Poll vote denied: no poll row for messageId={}", messageId);
            return null;
        }
        if (poll.isClosedAt(LocalDateTime.now())) {
            log.warn("Poll vote denied: poll {} is closed (userId={})", messageId, userId);
            return null;
        }

        Set<UUID> chosen = optionIds == null ? Set.of() : new LinkedHashSet<>(optionIds);
        if (!poll.isAllowMultiple() && chosen.size() > 1) {
            log.warn("Poll vote denied: {} options on single-choice poll {} (userId={})", chosen.size(), messageId, userId);
            return null;
        }
        Set<UUID> valid = pollOptionRepository.findByMessageIdInOrderByPositionAsc(List.of(messageId))
                .stream().map(PollOption::getOptionId).collect(Collectors.toSet());
        if (!valid.containsAll(chosen)) {
            log.warn("Poll vote denied: option not in poll {} (userId={})", messageId, userId);
            return null;
        }

        pollVoteRepository.deleteByMessageIdAndUserId(messageId, userId);
        pollVoteRepository.flush();
        if (!chosen.isEmpty()) {
            pollVoteRepository.saveAll(chosen.stream()
                    .map(optionId -> PollVote.builder()
                            .id(new PollVote.PollVoteId(messageId, optionId, userId))
                            .build())
                    .toList());
        }
        return message;
    }

    /** Closes the poll if {@code userId} wrote it or may delete others' messages. Returns the message, or null when denied. */
    @Transactional
    public Message close(UUID userId, UUID serverId, UUID messageId) {
        Message message = accessiblePollMessage(userId, serverId, messageId, "close");
        if (message == null) return null;

        boolean isAuthor = message.getSenderId().equals(userId);
        if (!isAuthor && !permissionService.hasInChannel(userId, serverId, message.getChannelId(), Permission.DELETE_OTHERS_MSGS)) {
            log.warn("Poll close denied: userId={} is neither author nor moderator of poll {}", userId, messageId);
            return null;
        }

        Poll poll = pollRepository.findForUpdate(messageId).orElse(null);
        if (poll == null || poll.getClosedAt() != null) return null;
        LocalDateTime now = LocalDateTime.now();
        // Already expired: record the scheduled end rather than "now".
        poll.setClosedAt(poll.getClosesAt() != null && poll.getClosesAt().isBefore(now) ? poll.getClosesAt() : now);
        pollRepository.save(poll);
        return message;
    }

    /** Marks polls whose end time passed as closed and pushes their final state to clients. */
    @Scheduled(fixedDelayString = "${komm.polls.close-interval-ms:30000}", initialDelayString = "${komm.polls.close-initial-delay-ms:15000}")
    public void closeExpiredPolls() {
        List<Poll> expired = pollRepository.findExpiredOpen(LocalDateTime.now());
        for (Poll poll : expired) {
            try {
                poll.setClosedAt(poll.getClosesAt());
                pollRepository.save(poll);
                messageRepository.findById(poll.getMessageId()).ifPresent(m -> broadcastUpdate(m, null));
            } catch (Exception e) {
                log.warn("Failed to close expired poll {}: {}", poll.getMessageId(), e.getMessage());
            }
        }
    }

    /**
     * Sends the poll's current state to everyone who can see the channel. When {@code voterId} is
     * given, that user additionally gets a copy carrying their own choices.
     */
    public void broadcastUpdate(Message message, UUID voterId) {
        Channel channel = channelRepository.findById(message.getChannelId()).orElse(null);
        if (channel == null) return;
        PollSummary summary = summaries(List.of(message.getMessageId()), voterId).get(message.getMessageId());
        if (summary == null) return;

        List<UUID> mine = summary.getMyOptionIds();
        summary.setMyOptionIds(null);
        WsMessage everyone = updateMessage(message, summary);

        if (channel.getChannelType() == Channel.ChannelType.TEXT) {
            clientMessageSender.broadcastToServer(message.getServerId(), gson.toJson(everyone));
        } else {
            webrtcRoomsManager.broadcastToChannel(message.getServerId(), message.getChannelId(), everyone);
        }

        if (voterId != null) {
            summary.setMyOptionIds(mine == null ? List.of() : mine);
            clientMessageSender.sendToUser(message.getServerId(), voterId, gson.toJson(updateMessage(message, summary)));
        }
    }

    private WsMessage updateMessage(Message message, PollSummary summary) {
        return WsMessage.builder()
                .type(WsMessageType.CHANNEL_POLL_UPDATED)
                .payload(PollUpdatedPayload.builder()
                        .messageId(message.getMessageId())
                        .channelId(message.getChannelId())
                        .serverId(message.getServerId())
                        .poll(summary)
                        .build())
                .build();
    }

    /** The poll message if it exists in the session's server and {@code userId} may see and use its channel. */
    private Message accessiblePollMessage(UUID userId, UUID serverId, UUID messageId, String action) {
        if (messageId == null) return null;
        Message message = messageRepository.findById(messageId).orElse(null);
        if (message == null || message.getMessageType() != Message.MessageType.POLL
                || !message.getServerId().equals(serverId)) {
            log.warn("Poll {} denied: messageId={} is not a poll in serverId={} (userId={})", action, messageId, serverId, userId);
            return null;
        }
        if (!serverMemberRepository.existsByServerIdAndUserId(serverId, userId)) {
            log.warn("Poll {} denied: userId={} is not a member of serverId={}", action, userId, serverId);
            return null;
        }
        if (!permissionService.hasInChannel(userId, serverId, message.getChannelId(), Permission.VIEW_CHANNEL)) {
            log.warn("Poll {} denied: userId={} cannot view channelId={}", action, userId, message.getChannelId());
            return null;
        }
        Channel channel = channelRepository.findById(message.getChannelId()).orElse(null);
        if (channel == null) return null;
        if (channel.getChannelType() != Channel.ChannelType.TEXT
                && !webrtcRoomsManager.isUserInChannel(serverId, message.getChannelId(), userId)) {
            log.warn("Poll {} denied: userId={} is not connected to voice channelId={}", action, userId, message.getChannelId());
            return null;
        }
        return message;
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /** Removes poll data for these messages (no-op for non-polls). Call before or with the message delete. */
    @Transactional
    public void deleteForMessages(Collection<UUID> messageIds) {
        if (messageIds.isEmpty()) return;
        pollVoteRepository.deleteByMessageIdIn(messageIds);
        pollOptionRepository.deleteByMessageIdIn(messageIds);
        pollRepository.deleteByMessageIdIn(messageIds);
    }
}
