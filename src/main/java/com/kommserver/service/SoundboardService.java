package com.kommserver.service;

import com.kommserver.model.db.ServerSoundboard;
import com.kommserver.model.dto.response.ErrorResponse;
import com.kommserver.model.dto.summary.SoundboardSummary;
import com.kommserver.repository.ServerSoundboardRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoundboardService {

    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final ServerSoundboardRepository serverRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @Value("${komm.soundboards.base-path}")
    private String soundboardsBasePath;

    public record Details(
            UUID   soundboardId,
            UUID   serverId,
            String filePath,
            String fileName,
            String fileType
    ) {}

    public List<SoundboardSummary> listServer(UUID serverId) {
        return serverRepo.findByServerIdOrderBySlotIndex(serverId).stream()
                .map(SoundboardSummary::from).collect(Collectors.toList());
    }

    public Optional<Details> find(UUID serverId, UUID soundboardId) {
        return serverRepo.findById(soundboardId)
                .filter(s -> s.getServerId().equals(serverId))
                .map(s -> new Details(s.getSoundboardId(), s.getServerId(),
                        s.getFilePath(), s.getFileName(), s.getFileType()));
    }

    @Transactional
    public SoundboardSummary upload(UUID serverId, UUID callerId, int slotIndex,
                                    String name, String emoji, String fileName, String fileType,
                                    String contentBase64) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(contentBase64);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Soundboard file exceeds the 20 MB limit");
        }

        UUID id = UUID.randomUUID();
        String ext = fileName != null && fileName.contains(".")
                ? fileName.substring(fileName.lastIndexOf('.')) : "";
        String resolvedName = name == null || name.isBlank()
                ? (fileName != null ? fileName : "Sound") : name.trim();
        String resolvedEmoji = emoji != null && !emoji.isBlank() ? emoji.trim() : null;
        String resolvedFileType = fileType != null ? fileType : "application/octet-stream";
        String resolvedFileName = fileName != null ? fileName : "sound";

        serverRepo.findByServerIdOrderBySlotIndex(serverId).stream()
                .filter(s -> s.getSlotIndex() == slotIndex).findFirst()
                .ifPresent(old -> deleteFile(old.getFilePath()));
        serverRepo.deleteByServerIdAndSlotIndex(serverId, slotIndex);
        entityManager.flush();
        entityManager.clear();

        Path dir = Paths.get(soundboardsBasePath, serverId.toString());
        Files.createDirectories(dir);
        Path filePath = dir.resolve(id + ext);
        Files.write(filePath, bytes);

        ServerSoundboard sb = ServerSoundboard.builder()
                .serverId(serverId).slotIndex(slotIndex).name(resolvedName).emoji(resolvedEmoji)
                .filePath(filePath.toString()).fileName(resolvedFileName)
                .fileType(resolvedFileType).fileSize((long) bytes.length)
                .uploaderId(callerId).createdAt(LocalDateTime.now()).build();
        entityManager.persist(sb);
        log.info("[Soundboard] Stored sound '{}' slot={} server={}", resolvedName, slotIndex, serverId);
        return SoundboardSummary.from(sb);
    }

    /**
     * Edits a sound's name/emoji and optionally replaces its audio file. A file
     * replacement is modeled as delete + re-insert with a fresh id (keeping slot,
     * uploader and creation time), because clients cache downloaded files by
     * soundboard id and rely on ids being immutable per file.
     */
    @Transactional
    public SoundboardSummary edit(UUID serverId, UUID soundboardId,
                                  String name, String emoji,
                                  String fileName, String fileType,
                                  String contentBase64) throws IOException {
        ServerSoundboard existing = serverRepo.findById(soundboardId)
                .filter(s -> s.getServerId().equals(serverId))
                .orElseThrow(() -> new IllegalArgumentException("Soundboard not found"));

        String resolvedName = name == null || name.isBlank() ? existing.getName() : name.trim();
        String resolvedEmoji = emoji != null && !emoji.isBlank() ? emoji.trim() : null;

        if (contentBase64 == null || contentBase64.isBlank()) {
            existing.setName(resolvedName);
            existing.setEmoji(resolvedEmoji);
            ServerSoundboard saved = serverRepo.save(existing);
            log.info("[Soundboard] Renamed sound {} to '{}' server={}", soundboardId, resolvedName, serverId);
            return SoundboardSummary.from(saved);
        }

        byte[] bytes = Base64.getDecoder().decode(contentBase64);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("Soundboard file exceeds the 20 MB limit");
        }

        UUID id = UUID.randomUUID();
        String resolvedFileName = fileName != null ? fileName : existing.getFileName();
        String resolvedFileType = fileType != null ? fileType : existing.getFileType();
        String ext = resolvedFileName != null && resolvedFileName.contains(".")
                ? resolvedFileName.substring(resolvedFileName.lastIndexOf('.')) : "";

        deleteFile(existing.getFilePath());
        serverRepo.deleteById(soundboardId);
        entityManager.flush();
        entityManager.clear();

        Path dir = Paths.get(soundboardsBasePath, serverId.toString());
        Files.createDirectories(dir);
        Path filePath = dir.resolve(id + ext);
        Files.write(filePath, bytes);

        ServerSoundboard sb = ServerSoundboard.builder()
                .serverId(serverId).slotIndex(existing.getSlotIndex())
                .name(resolvedName).emoji(resolvedEmoji)
                .filePath(filePath.toString()).fileName(resolvedFileName)
                .fileType(resolvedFileType).fileSize((long) bytes.length)
                .uploaderId(existing.getUploaderId()).createdAt(existing.getCreatedAt()).build();
        entityManager.persist(sb);
        log.info("[Soundboard] Replaced file of sound '{}' slot={} server={} (new id={})",
                resolvedName, existing.getSlotIndex(), serverId, sb.getSoundboardId());
        return SoundboardSummary.from(sb);
    }

    @Transactional
    public void delete(UUID serverId, UUID soundboardId) {
        find(serverId, soundboardId).ifPresent(d -> {
            deleteFile(d.filePath());
            serverRepo.deleteById(soundboardId);
        });
    }

    public ResponseEntity<?> getFileResource(UUID serverId, UUID soundboardId) {
        Details d = find(serverId, soundboardId).orElse(null);
        if (d == null) return ErrorResponse.of(HttpStatus.NOT_FOUND, "Soundboard not found");

        Path filePath = Paths.get(d.filePath());
        if (!Files.exists(filePath) || !Files.isReadable(filePath)) {
            log.error("[Soundboard] File missing or unreadable: {}", filePath);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Soundboard file unavailable");
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            String mime = d.fileType() != null ? d.fileType() : "application/octet-stream";
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.fileName() + "\"")
                    .contentType(MediaType.parseMediaType(mime))
                    .contentLength(bytes.length)
                    .body(bytes);
        } catch (IOException e) {
            log.error("[Soundboard] Failed to read file {}", soundboardId, e);
            return ErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read soundboard");
        }
    }

    private void deleteFile(String path) {
        try { Files.deleteIfExists(Paths.get(path)); }
        catch (IOException e) { log.warn("[Soundboard] Could not delete file: {}", e.getMessage()); }
    }
}
