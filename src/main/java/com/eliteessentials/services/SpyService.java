package com.eliteessentials.services;

import com.eliteessentials.config.ConfigManager;
import com.eliteessentials.config.PluginConfig;
import com.eliteessentials.util.MessageFormatter;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Centralized service for all spy functionality.
 * 
 * Manages three spy modes that admins can toggle independently:
 * - gchat: See all group chat messages (migrated from GroupChatService)
 * - dm: See all private messages (/msg, /reply)
 * - command: See all commands executed by players
 */
public class SpyService {

    private static final Logger logger = Logger.getLogger("EliteEssentials");

    /** Players spying on group chat channels */
    private final Set<UUID> gchatSpies = ConcurrentHashMap.newKeySet();

    /** Players spying on private messages */
    private final Set<UUID> dmSpies = ConcurrentHashMap.newKeySet();

    /** Players spying on commands */
    private final Set<UUID> commandSpies = ConcurrentHashMap.newKeySet();

    private final ConfigManager configManager;

    public SpyService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    // ==================== TOGGLE METHODS ====================

    /**
     * Toggle group chat spy for a player.
     * @return true if spy is now enabled, false if disabled
     */
    public boolean toggleGchatSpy(UUID playerId) {
        if (gchatSpies.contains(playerId)) {
            gchatSpies.remove(playerId);
            return false;
        } else {
            gchatSpies.add(playerId);
            return true;
        }
    }

    /**
     * Toggle DM spy for a player.
     * @return true if spy is now enabled, false if disabled
     */
    public boolean toggleDmSpy(UUID playerId) {
        if (dmSpies.contains(playerId)) {
            dmSpies.remove(playerId);
            return false;
        } else {
            dmSpies.add(playerId);
            return true;
        }
    }

    /**
     * Toggle command spy for a player.
     * @return true if spy is now enabled, false if disabled
     */
    public boolean toggleCommandSpy(UUID playerId) {
        if (commandSpies.contains(playerId)) {
            commandSpies.remove(playerId);
            return false;
        } else {
            commandSpies.add(playerId);
            return true;
        }
    }

    // ==================== CHECK METHODS ====================

    public boolean isGchatSpying(UUID playerId) {
        return gchatSpies.contains(playerId);
    }

    public boolean isDmSpying(UUID playerId) {
        return dmSpies.contains(playerId);
    }

    public boolean isCommandSpying(UUID playerId) {
        return commandSpies.contains(playerId);
    }

    // ==================== CLEANUP ====================

    /**
     * Remove a player from all spy sets (on disconnect).
     */
    public void removePlayer(UUID playerId) {
        gchatSpies.remove(playerId);
        dmSpies.remove(playerId);
        commandSpies.remove(playerId);
    }

    /**
     * Remove a player from group chat spy only.
     */
    public void removeGchatSpy(UUID playerId) {
        gchatSpies.remove(playerId);
    }

    // ==================== NOTIFICATION METHODS ====================

    /**
     * Notify DM spies about a private message.
     * Sends the spy message to all admins with DM spy enabled,
     * excluding the sender and receiver.
     */
    public void notifyDmSpy(UUID senderId, UUID receiverId, String senderName, String receiverName, String message) {
        PluginConfig.SpyConfig spyConfig = configManager.getConfig().spy;
        if (!spyConfig.enabled || !spyConfig.dmSpyEnabled || dmSpies.isEmpty()) {
            return;
        }

        String spyFormat = spyConfig.dmSpyFormat
                .replace("{sender}", senderName)
                .replace("{receiver}", receiverName)
                .replace("{message}", message);
        Message formatted = MessageFormatter.format(spyFormat);

        Universe universe = Universe.get();
        if (universe == null) return;

        for (PlayerRef player : universe.getPlayers()) {
            UUID playerId = player.getUuid();
            if (playerId.equals(senderId) || playerId.equals(receiverId)) continue;
            if (dmSpies.contains(playerId)) {
                player.sendMessage(formatted);
            }
        }
    }

    /**
     * Notify command spies about a command execution.
     * Sends the spy message to all admins with command spy enabled,
     * excluding the player who executed the command.
     */
    public void notifyCommandSpy(UUID executorId, String executorName, String command) {
        PluginConfig.SpyConfig spyConfig = configManager.getConfig().spy;
        if (!spyConfig.enabled || !spyConfig.commandSpyEnabled || commandSpies.isEmpty()) {
            return;
        }

        String spyFormat = spyConfig.commandSpyFormat
                .replace("{player}", executorName)
                .replace("{command}", command);
        Message formatted = MessageFormatter.format(spyFormat);

        Universe universe = Universe.get();
        if (universe == null) return;

        for (PlayerRef player : universe.getPlayers()) {
            UUID playerId = player.getUuid();
            if (playerId.equals(executorId)) continue;
            if (commandSpies.contains(playerId)) {
                player.sendMessage(formatted);
            }
        }
    }

    /**
     * Get the set of group chat spies (for GroupChatService compatibility).
     */
    public Set<UUID> getGchatSpies() {
        return Collections.unmodifiableSet(gchatSpies);
    }

    /**
     * Check if there are any active group chat spies.
     */
    public boolean hasGchatSpies() {
        return !gchatSpies.isEmpty();
    }

    /**
     * Get spy status summary for a player.
     * @return list of enabled spy modes
     */
    public List<String> getActiveSpyModes(UUID playerId) {
        List<String> modes = new ArrayList<>();
        if (gchatSpies.contains(playerId)) modes.add("gchat");
        if (dmSpies.contains(playerId)) modes.add("dm");
        if (commandSpies.contains(playerId)) modes.add("command");
        return modes;
    }
}
