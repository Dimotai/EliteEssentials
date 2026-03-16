package com.eliteessentials.services;

import com.eliteessentials.config.ConfigManager;
import com.eliteessentials.config.PluginConfig;
import com.eliteessentials.integration.LuckPermsIntegration;
import com.eliteessentials.integration.PAPIIntegration;
import com.eliteessentials.permissions.PermissionService;
import com.eliteessentials.storage.GreetingStorage;
import com.eliteessentials.storage.GreetingStorage.GreetingConditions;
import com.eliteessentials.storage.GreetingStorage.GreetingRule;
import com.eliteessentials.util.MessageFormatter;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Evaluates greeting rules against player events and sends matching messages.
 *
 * Triggers:
 *  - server_join:  player connects to the server
 *  - world_enter:  player enters/changes to a world
 *  - respawn:      player respawns after death
 *
 * Conditions are AND between types, OR within lists.
 */
public class GreetingService {

    private static final Logger logger = Logger.getLogger("EliteEssentials");

    private final GreetingStorage greetingStorage;
    private final ConfigManager configManager;
    private final ScheduledExecutorService scheduler;

    /** Track showOnce rules: playerId -> set of rule IDs already shown this session */
    private final ConcurrentHashMap<UUID, Set<String>> shownRules = new ConcurrentHashMap<>();

    public GreetingService(GreetingStorage greetingStorage, ConfigManager configManager) {
        this.greetingStorage = greetingStorage;
        this.configManager = configManager;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EliteEssentials-Greetings");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Evaluate and fire greeting rules for a trigger event.
     *
     * @param playerRef   the player to send messages to
     * @param trigger     "server_join", "world_enter", or "respawn"
     * @param worldName   current world name
     * @param isFirstJoin whether this is the player's first join
     * @param spawnName   spawn point name (nullable, for respawn trigger)
     */
    public void evaluate(PlayerRef playerRef, String trigger, String worldName,
                         boolean isFirstJoin, String spawnName) {
        if (playerRef == null || trigger == null) return;

        PluginConfig config = configManager.getConfig();
        if (!config.greetings.enabled) return;

        UUID playerId = playerRef.getUuid();
        List<GreetingRule> rules = greetingStorage.getRules();

        for (GreetingRule rule : rules) {
            if (!rule.enabled) continue;
            if (!trigger.equalsIgnoreCase(rule.trigger)) continue;

            // Check showOnce: skip if already shown this session
            if (rule.showOnce) {
                Set<String> shown = shownRules.get(playerId);
                if (shown != null && shown.contains(rule.id)) continue;
            }

            if (!matchesConditions(rule.conditions, playerId, playerRef, worldName, isFirstJoin, spawnName)) {
                continue;
            }

            // Rule matches — mark showOnce
            if (rule.showOnce) {
                shownRules.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet())
                        .add(rule.id);
            }

            // Send messages (with optional delay)
            if (rule.delaySeconds > 0) {
                final GreetingRule matchedRule = rule;
                scheduler.schedule(() -> sendMessages(playerRef, matchedRule, worldName, spawnName),
                        rule.delaySeconds, TimeUnit.SECONDS);
            } else {
                sendMessages(playerRef, rule, worldName, spawnName);
            }

            if (rule.stopAfterMatch) {
                break;
            }
        }
    }

    /**
     * Clear session state when a player disconnects.
     */
    public void onPlayerQuit(UUID playerId) {
        shownRules.remove(playerId);
    }

    /**
     * Reload greeting rules from disk.
     */
    public void reload() {
        greetingStorage.load();
    }

    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== CONDITION MATCHING ====================

    private boolean matchesConditions(GreetingConditions cond, UUID playerId,
                                       PlayerRef playerRef, String worldName,
                                       boolean isFirstJoin, String spawnName) {
        if (cond == null) return true;

        // firstJoin check
        if (cond.firstJoin != null && cond.firstJoin != isFirstJoin) {
            return false;
        }

        // Groups check (OR within list)
        if (cond.groups != null && !cond.groups.isEmpty()) {
            List<String> playerGroups = getPlayerGroups(playerId);
            boolean anyMatch = false;
            for (String requiredGroup : cond.groups) {
                for (String playerGroup : playerGroups) {
                    if (playerGroup.equalsIgnoreCase(requiredGroup)) {
                        anyMatch = true;
                        break;
                    }
                }
                if (anyMatch) break;
            }
            if (!anyMatch) return false;
        }

        // Permissions check (OR within list)
        if (cond.permissions != null && !cond.permissions.isEmpty()) {
            PermissionService perms = PermissionService.get();
            boolean anyMatch = false;
            for (String perm : cond.permissions) {
                if (perms.hasPermission(playerId, perm)) {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch) return false;
        }

        // Worlds check (OR within list, supports * wildcards)
        if (cond.worlds != null && !cond.worlds.isEmpty() && worldName != null) {
            boolean anyMatch = false;
            for (String pattern : cond.worlds) {
                if (worldMatchesPattern(worldName, pattern)) {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch) return false;
        }

        // Spawns check (OR within list)
        if (cond.spawns != null && !cond.spawns.isEmpty()) {
            if (spawnName == null) return false;
            boolean anyMatch = false;
            for (String s : cond.spawns) {
                if (s.equalsIgnoreCase(spawnName)) {
                    anyMatch = true;
                    break;
                }
            }
            if (!anyMatch) return false;
        }

        return true;
    }

    // ==================== MESSAGE SENDING ====================

    private void sendMessages(PlayerRef playerRef, GreetingRule rule,
                               String worldName, String spawnName) {
        if (rule.messages == null || rule.messages.isEmpty()) return;
        if (!playerRef.isValid()) return;

        PluginConfig config = configManager.getConfig();
        String playerName = playerRef.getUsername();
        String serverName = config.motd.serverName;
        String group = getPrimaryGroup(playerRef.getUuid());
        int playerCount = getVisiblePlayerCount();

        for (String line : rule.messages) {
            if (line == null) continue;

            String processed = line
                    .replace("{player}", playerName)
                    .replace("{server}", serverName)
                    .replace("{world}", worldName != null ? worldName : "")
                    .replace("{playercount}", String.valueOf(playerCount))
                    .replace("{group}", group != null ? group : "")
                    .replace("{spawn}", spawnName != null ? spawnName : "");

            if (config.chatFormat.placeholderapi) {
                processed = PAPIIntegration.setPlaceholders(playerRef, processed);
            }

            playerRef.sendMessage(MessageFormatter.format(processed));
        }

        if (configManager.isDebugEnabled()) {
            logger.info("[Greetings] Fired rule '" + rule.id + "' for " + playerRef.getUsername());
        }
    }

    // ==================== HELPERS ====================

    private List<String> getPlayerGroups(UUID playerId) {
        try {
            return LuckPermsIntegration.getGroups(playerId);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String getPrimaryGroup(UUID playerId) {
        try {
            return LuckPermsIntegration.getPrimaryGroup(playerId);
        } catch (Exception e) {
            return null;
        }
    }

    private int getVisiblePlayerCount() {
        try {
            var universe = com.hypixel.hytale.server.core.universe.Universe.get();
            int total = universe.getPlayers().size();
            var vanishService = com.eliteessentials.EliteEssentials.getInstance().getVanishService();
            if (vanishService != null) {
                return total - vanishService.getVanishedCount();
            }
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean worldMatchesPattern(String worldName, String pattern) {
        if (!pattern.contains("*")) {
            return pattern.equalsIgnoreCase(worldName);
        }
        String[] parts = pattern.split("\\*", -1);
        StringBuilder sb = new StringBuilder("(?i)");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(".*");
            sb.append(java.util.regex.Pattern.quote(parts[i]));
        }
        return worldName.matches(sb.toString());
    }
}
