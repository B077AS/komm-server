package com.kommserver.bot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kommserver.model.db.Bot;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Message;
import com.kommserver.service.MessageService;
import com.kommserver.websocket.messages.WsMessage;
import com.kommserver.websocket.messages.WsMessageType;
import com.kommserver.websocket.messages.payloads.MessageReceivedPayload;
import com.kommserver.websocket.messages.payloads.MessageSentPayload;
import com.kommserver.websocket.senders.ClientMessageSender;
import com.kommserver.websocket.managers.WebrtcRoomsManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * v1: no interaction/claim mechanic — just posts a random waifu.im image as a normal bot message
 * every so often, purely reacting to channel activity (a random-chance roll on each user message,
 * with a per-channel cooldown so a busy channel doesn't get flooded).
 *
 * Posts the image as a {@code MessageType.URL_IMAGE} message (content = the image URL) — a bot
 * post, not a real upload, so it doesn't download/store the file as a {@code MessageAttachment}.
 * It's deliberately its own message type rather than reusing {@code GIF}: these aren't GIFs, and
 * piggybacking on the GIF type gave them the GIF-specific context menu ("Copy GIF URL", etc).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnimeWaifuBot implements BotBehavior {

    private static final String IMAGES_ENDPOINT = "https://api.waifu.im/images?included_tags=waifu";
    private static final Duration COOLDOWN = Duration.ofSeconds(20);
    private static final double DEFAULT_SPAWN_CHANCE_PERCENT = 2.0;

    private final RestTemplate restTemplate;
    private final MessageService messageService;
    private final ClientMessageSender clientMessageSender;
    private final WebrtcRoomsManager webrtcRoomsManager;
    private final Gson gson;

    private final Map<UUID, Instant> lastSpawnByChannel = new ConcurrentHashMap<>();

    @Override
    public Bot.BotType getType() {
        return Bot.BotType.ANIME_WAIFU;
    }

    @Override
    public void onMessage(Channel channel, Message message, Bot bot) {
        double spawnChancePercent = DEFAULT_SPAWN_CHANCE_PERCENT;
        boolean sfwOnly = true;
        if (bot.getConfig() != null && !bot.getConfig().isBlank()) {
            try {
                JsonObject config = JsonParser.parseString(bot.getConfig()).getAsJsonObject();
                if (config.has("spawnChancePercent")) {
                    spawnChancePercent = config.get("spawnChancePercent").getAsDouble();
                }
                if (config.has("sfwOnly")) {
                    sfwOnly = config.get("sfwOnly").getAsBoolean();
                }
            } catch (Exception e) {
                log.warn("Bot id={} has malformed config, using defaults: {}", bot.getBotId(), e.getMessage());
            }
        }

        Instant last = lastSpawnByChannel.get(channel.getChannelId());
        if (last != null && Duration.between(last, Instant.now()).compareTo(COOLDOWN) < 0) {
            return;
        }
        if (ThreadLocalRandom.current().nextDouble(100.0) >= spawnChancePercent) {
            return;
        }

        String imageUrl = fetchWaifuImageUrl(sfwOnly);
        if (imageUrl == null) return;

        lastSpawnByChannel.put(channel.getChannelId(), Instant.now());
        postImage(bot, channel, imageUrl);
    }

    private String fetchWaifuImageUrl(boolean sfwOnly) {
        try {
            String url = sfwOnly ? IMAGES_ENDPOINT + "&is_nsfw=false" : IMAGES_ENDPOINT;
            String body = restTemplate.getForObject(url, String.class);
            if (body == null) return null;

            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray items = root.has("items") ? root.getAsJsonArray("items") : null;
            if (items == null || items.isEmpty()) return null;

            JsonObject first = items.get(0).getAsJsonObject();
            return first.has("url") ? first.get("url").getAsString() : null;
        } catch (Exception e) {
            log.warn("Failed to fetch a waifu.im image: {}", e.getMessage());
            return null;
        }
    }

    private void postImage(Bot bot, Channel channel, String imageUrl) {
        MessageSentPayload sent = MessageSentPayload.builder()
                .channelId(channel.getChannelId())
                .content(imageUrl)
                .messageType(Message.MessageType.URL_IMAGE)
                .hasAttachments(false)
                .build();

        Message saved = messageService.save(bot.getBotId(), bot.getServerId(), sent);

        MessageReceivedPayload received = MessageReceivedPayload.builder()
                .messageId(saved.getMessageId())
                .senderId(saved.getSenderId())
                .channelId(saved.getChannelId())
                .serverId(saved.getServerId())
                .content(saved.getContent())
                .sentAt(saved.getSentAt())
                .edited(false)
                .hasAttachments(false)
                .messageType(saved.getMessageType())
                .build();

        WsMessage msg = WsMessage.builder()
                .type(WsMessageType.CHANNEL_MESSAGE_RECEIVED)
                .payload(received)
                .build();

        // Text channels broadcast server-wide (matches ChannelMessageSentHandler); voice channels
        // (which also carry a text chat) only reach clients currently connected to that room.
        if (channel.getChannelType() == Channel.ChannelType.TEXT) {
            clientMessageSender.broadcastToServer(bot.getServerId(), gson.toJson(msg));
        } else {
            webrtcRoomsManager.broadcastToChannel(bot.getServerId(), channel.getChannelId(), msg);
        }
    }
}
