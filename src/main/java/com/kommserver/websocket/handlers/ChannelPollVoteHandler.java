package com.kommserver.websocket.handlers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.kommserver.model.db.Message;
import com.kommserver.service.PollService;
import com.kommserver.websocket.interfaces.ClientInboundMessageHandler;
import com.kommserver.websocket.managers.ClientSessionManager;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.PollVotePayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class ChannelPollVoteHandler implements ClientInboundMessageHandler {

    private final Gson gson;
    private final PollService pollService;

    @Override
    public WsMessageType getType() {
        return WsMessageType.CHANNEL_POLL_VOTE;
    }

    @Override
    public void handle(WebSocketSession session, JsonObject payload) {
        UUID userId = ClientSessionManager.userId(session);
        UUID serverId = ClientSessionManager.serverId(session);
        PollVotePayload vote = gson.fromJson(payload, PollVotePayload.class);

        // Committed first, broadcast after: the pushed state then includes this vote.
        Message message = pollService.vote(userId, serverId, vote.getMessageId(), vote.getOptionIds());
        if (message != null) pollService.broadcastUpdate(message, userId);
    }
}
