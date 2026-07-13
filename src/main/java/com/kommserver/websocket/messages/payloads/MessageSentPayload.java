package com.kommserver.websocket.messages.payloads;

import com.kommserver.model.db.Message;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class MessageSentPayload {

    private UUID channelId;
    private String content;
    private UUID repliedToId;
    private boolean hasAttachments;
    private UUID attachmentId;
    private Message.MessageType messageType;
    private String codeLanguage;
}
