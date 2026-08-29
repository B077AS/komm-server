package com.kommserver.bot;

import com.kommserver.model.db.Bot;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Collects every {@link BotBehavior} bean, keyed by the {@link Bot.BotType} it implements. */
@Component
public class BotRegistry {

    private final Map<Bot.BotType, BotBehavior> behaviors;

    public BotRegistry(List<BotBehavior> behaviors) {
        this.behaviors = behaviors.stream()
                .collect(Collectors.toMap(BotBehavior::getType, b -> b));
    }

    public BotBehavior get(Bot.BotType type) {
        return behaviors.get(type);
    }
}
