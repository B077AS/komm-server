package com.kommserver.model.db;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public enum Permission {
    DELETE_SERVER,
    EDIT_SERVER_INFO,
    EDIT_SERVER_PERMS,
    VIEW_SERVER_SETTINGS,
    CREATE_CHANNELS,
    DELETE_CHANNELS,
    EDIT_CHANNELS,
    EDIT_CHANNEL_PERMS,
    // Declared before BAN_USERS on purpose: the MODERATOR default below grants every permission
    // whose ordinal is >= BAN_USERS, so keeping DELETE_INVITES earlier excludes moderators by default
    // (it ends up owner + admin only).
    DELETE_INVITES,
    BAN_USERS,
    KICK_USERS,
    MUTE_USERS,
    DEAFEN_USERS,
    INVITE_USERS,
    POKE_USERS,
    CHECK_PING,
    SEND_MESSAGES,
    SEND_GIFS,
    SEND_ATTACHMENTS,
    ADD_REACTIONS,
    DELETE_OTHERS_MSGS,
    JOIN_VOICE,
    SCREEN_SHARE,
    USE_SOUNDBOARD,
    MANAGE_SERVER_SOUNDBOARD,
    MOVE_MEMBERS,
    VIEW_CHANNEL;

    public static boolean containedIn(List<String> permissions, Permission p) {
        return permissions != null && permissions.contains(p.name());
    }

    public static List<String> allNames() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.toList());
    }

    public static List<String> defaultPermissionsFor(ServerMember.Role role) {
        return switch (role) {
            case OWNER -> allNames();
            case ADMIN -> Arrays.stream(values())
                    .filter(p -> p != DELETE_SERVER)
                    .map(Enum::name).collect(Collectors.toList());
            case MODERATOR -> Arrays.stream(values())
                    .filter(p -> p.ordinal() >= BAN_USERS.ordinal() || p == VIEW_SERVER_SETTINGS)
                    .map(Enum::name).collect(Collectors.toList());
            case MEMBER -> List.of(
                    INVITE_USERS.name(), POKE_USERS.name(), CHECK_PING.name(),
                    SEND_MESSAGES.name(), SEND_GIFS.name(),
                    SEND_ATTACHMENTS.name(), ADD_REACTIONS.name(), JOIN_VOICE.name(),
                    SCREEN_SHARE.name(), USE_SOUNDBOARD.name(), VIEW_CHANNEL.name());
            // MOVE_MEMBERS is deliberately excluded from MEMBER defaults
        };
    }
}
