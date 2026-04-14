package com.eliteessentials.listeners;

import com.eliteessentials.services.SpyService;
import com.eliteessentials.util.CommandSpyUtil;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.player.PlayerChatEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.logging.Logger;

/**
 * Listener that intercepts player chat events to detect command usage
 * and notify command spies.
 * 
 * In Hytale, PlayerChatEvent may fire for all player input including
 * commands (messages starting with /). This listener checks for command
 * input and notifies the SpyService before the command system processes it.
 * 
 * If PlayerChatEvent does not fire for commands in your Hytale version,
 * command spy will still work for commands that explicitly call
 * CommandSpyUtil.notify() in their execute method.
 */
public class CommandSpyListener {

    private static final Logger logger = Logger.getLogger("EliteEssentials");
    private final SpyService spyService;

    public CommandSpyListener(SpyService spyService) {
        this.spyService = spyService;
    }

    public void registerEvents(EventRegistry eventRegistry) {
        eventRegistry.registerGlobal(PlayerChatEvent.class, event -> {
            String content = event.getContent();
            if (content != null && content.startsWith("/")) {
                PlayerRef sender = event.getSender();
                if (sender != null && sender.isValid()) {
                    CommandSpyUtil.notify(sender.getUuid(), sender.getUsername(), content);
                }
            }
        });
        logger.info("Command spy listener registered");
    }
}
