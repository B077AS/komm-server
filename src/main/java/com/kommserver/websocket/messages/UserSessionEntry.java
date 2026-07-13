package com.kommserver.websocket.messages;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.socket.WebSocketSession;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserSessionEntry {
    private UUID userId;
    private UUID serverId;
    private UUID channelId;
    @JsonIgnore
    private WebSocketSession session;
    private volatile boolean micEnabled;
    private volatile boolean speakerEnabled;
    private volatile boolean screenSharingEnabled;
    private volatile boolean serverMicEnabled = true;
    private volatile boolean serverSpeakerEnabled = true;
    private volatile boolean pokesEnabled = true;
}