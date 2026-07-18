package com.kommserver.service;

import com.google.gson.Gson;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Message;
import com.kommserver.model.db.MessageReaction;
import com.kommserver.repository.ChannelRepository;
import com.kommserver.repository.MessageReactionRepository;
import com.kommserver.repository.MessageRepository;
import com.kommserver.repository.ServerMemberRepository;
import com.kommserver.model.db.Permission;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.ChannelMessageReactionAdd;
import com.kommserver.websocket.messages.payloads.ChannelMessageReactionRemove;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReactionService {

    private final Gson gson;
    private final MessageReactionRepository reactionRepository;
    private final MessageRepository messageRepository;
    private final ServerMemberRepository serverMemberRepository;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final ChannelRepository channelRepository;
    private final PermissionService permissionService;

    @Transactional
    public void addReaction(UUID messageId, UUID userId, String emoji) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));

        if (!serverMemberRepository.existsByServerIdAndUserId(message.getServerId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not a member of this server");
        }

        if (!permissionService.hasInChannel(userId, message.getServerId(), message.getChannelId(), Permission.ADD_REACTIONS)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing permission: ADD_REACTIONS");
        }

        Channel channel = channelRepository.findById(message.getChannelId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        boolean isTextChannel = channel.getChannelType() == Channel.ChannelType.TEXT;

        if (!isTextChannel && !webrtcRoomsManager.isUserInChannel(message.getServerId(), message.getChannelId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not connected to the voice channel");
        }

        MessageReaction.MessageReactionId reactionId =
                new MessageReaction.MessageReactionId(messageId, userId, emoji);

        // Duplicate add (double-click, retry): the row already exists, so don't
        // broadcast again — clients count events, and a re-broadcast would inflate
        // their counters even though nothing changed.
        if (reactionRepository.existsById(reactionId)) {
            return;
        }
        reactionRepository.save(MessageReaction.builder().id(reactionId).message(message).build());

        ChannelMessageReactionAdd payload = ChannelMessageReactionAdd.builder()
                .messageId(messageId)
                .channelId(message.getChannelId())
                .serverId(message.getServerId())
                .userId(userId)
                .emoji(emoji)
                .build();

        WsMessage wsMessage = WsMessage.builder()
                .type(WsMessageType.CHANNEL_MESSAGE_REACTION_ADD)
                .payload(payload)
                .build();

        if (isTextChannel) {
            clientMessageSender.broadcastToServer(message.getServerId(), gson.toJson(wsMessage));
        } else {
            webrtcRoomsManager.broadcastToChannel(message.getServerId(), message.getChannelId(), wsMessage);
        }
    }

    @Transactional
    public void removeReaction(UUID messageId, UUID userId, String emoji) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));

        if (!serverMemberRepository.existsByServerIdAndUserId(message.getServerId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not a member of this server");
        }

        Channel channel = channelRepository.findById(message.getChannelId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Channel not found"));

        boolean isTextChannel = channel.getChannelType() == Channel.ChannelType.TEXT;

        if (!isTextChannel && !webrtcRoomsManager.isUserInChannel(message.getServerId(), message.getChannelId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not connected to the voice channel");
        }

        MessageReaction.MessageReactionId reactionId =
                new MessageReaction.MessageReactionId(messageId, userId, emoji);

        // Same idempotency rule as addReaction: if there is nothing to delete,
        // don't broadcast — receivers would decrement a counter that never changed.
        if (!reactionRepository.existsById(reactionId)) {
            return;
        }
        reactionRepository.deleteById(reactionId);

        ChannelMessageReactionRemove payload = ChannelMessageReactionRemove.builder()
                .messageId(messageId)
                .channelId(message.getChannelId())
                .serverId(message.getServerId())
                .userId(userId)
                .emoji(emoji)
                .build();

        WsMessage wsMessage = WsMessage.builder()
                .type(WsMessageType.CHANNEL_MESSAGE_REACTION_REMOVE)
                .payload(payload)
                .build();

        if (isTextChannel) {
            clientMessageSender.broadcastToServer(message.getServerId(), gson.toJson(wsMessage));
        } else {
            webrtcRoomsManager.broadcastToChannel(message.getServerId(), message.getChannelId(), wsMessage);
        }
    }
}