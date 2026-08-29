package com.kommserver.bot;

import com.kommserver.model.db.Bot;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Message;

/**
 * A bot type's implementation, discovered via {@link BotRegistry}. Adding a new bot type means
 * implementing this (as a {@code @Component}) and registering a new {@link Bot.BotType} — no
 * changes to the message pipeline that dispatches into it.
 */
public interface BotBehavior {

    Bot.BotType getType();

    /**
     * Called for every user message sent in a channel this bot is assigned to (after the message
     * has already been saved/broadcast). Runs off the WS-handling thread — implementations are
     * free to block (e.g. on an external HTTP call).
     */
    void onMessage(Channel channel, Message message, Bot bot);
}
