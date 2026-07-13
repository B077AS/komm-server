package com.kommserver.model.dto.summary;

import com.kommserver.model.db.ServerSoundboard;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SoundboardSummary {
    private UUID soundboardId;
    private UUID serverId;
    private int slotIndex;
    private String name;
    private String emoji;
    private String fileName;
    private String fileType;
    private long fileSize;
    private UUID uploaderId;

    public static SoundboardSummary from(ServerSoundboard s) {
        return SoundboardSummary.builder()
                .soundboardId(s.getSoundboardId())
                .serverId(s.getServerId())
                .slotIndex(s.getSlotIndex())
                .name(s.getName())
                .emoji(s.getEmoji())
                .fileName(s.getFileName())
                .fileType(s.getFileType())
                .fileSize(s.getFileSize() != null ? s.getFileSize() : 0)
                .uploaderId(s.getUploaderId())
                .build();
    }
}
