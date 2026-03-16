package com.eliteessentials.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Handles loading and saving greeting rules from greetings.json.
 *
 * Greeting rules are conditional welcome messages that fire on player events
 * (server join, world enter, respawn). Each rule has optional conditions
 * (groups, permissions, worlds, spawns, firstJoin) and a list of message lines.
 */
public class GreetingStorage {

    private static final Logger logger = Logger.getLogger("EliteEssentials");
    private static final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final File greetingFile;
    private final Object fileLock = new Object();
    private List<GreetingRule> rules;

    public GreetingStorage(File dataFolder) {
        this.greetingFile = new File(dataFolder, "greetings.json");
        this.rules = new ArrayList<>();
        load();
    }

    public void load() {
        if (!greetingFile.exists()) {
            createDefaults();
            save();
            return;
        }

        synchronized (fileLock) {
            try (FileReader reader = new FileReader(greetingFile, StandardCharsets.UTF_8)) {
                GreetingData data = gson.fromJson(reader, GreetingData.class);
                if (data != null && data.rules != null) {
                    rules = data.rules;
                } else {
                    createDefaults();
                }
            } catch (IOException e) {
                logger.warning("Could not load greetings.json: " + e.getMessage());
                createDefaults();
            }
        }
    }

    public void save() {
        synchronized (fileLock) {
            try (FileWriter writer = new FileWriter(greetingFile, StandardCharsets.UTF_8)) {
                GreetingData data = new GreetingData();
                data.rules = rules;
                gson.toJson(data, writer);
            } catch (IOException e) {
                logger.severe("Could not save greetings.json: " + e.getMessage());
            }
        }
    }

    public List<GreetingRule> getRules() {
        return rules;
    }

    private void createDefaults() {
        rules = new ArrayList<>();

        // Example: VIP welcome (disabled by default)
        GreetingRule vipRule = new GreetingRule();
        vipRule.id = "vip-welcome";
        vipRule.enabled = false;
        vipRule.trigger = "server_join";
        vipRule.conditions = new GreetingConditions();
        vipRule.conditions.groups = List.of("vip", "mvp");
        vipRule.messages = List.of(
                "&6&l★ VIP Welcome! ★",
                "&eWelcome back, {player}! Enjoy your VIP perks."
        );
        rules.add(vipRule);

        // Example: New player (disabled by default)
        GreetingRule newPlayer = new GreetingRule();
        newPlayer.id = "new-player";
        newPlayer.enabled = false;
        newPlayer.trigger = "server_join";
        newPlayer.conditions = new GreetingConditions();
        newPlayer.conditions.firstJoin = true;
        newPlayer.messages = List.of(
                "&a&lWelcome to the server, {player}!",
                "&7Type &e/help &7to get started.",
                "&7Join our Discord: &b/discord"
        );
        newPlayer.delaySeconds = 2;
        newPlayer.stopAfterMatch = true;
        rules.add(newPlayer);

        // Example: Arena world enter (disabled by default)
        GreetingRule arenaRule = new GreetingRule();
        arenaRule.id = "arena-enter";
        arenaRule.enabled = false;
        arenaRule.trigger = "world_enter";
        arenaRule.conditions = new GreetingConditions();
        arenaRule.conditions.worlds = List.of("pvp_arena", "arena*");
        arenaRule.messages = List.of(
                "&c⚔ Welcome to the Arena!",
                "&7PvP is enabled here. Good luck!"
        );
        rules.add(arenaRule);
    }

    // ==================== DATA CLASSES ====================

    private static class GreetingData {
        public List<GreetingRule> rules;
    }

    /**
     * A single greeting rule with trigger, conditions, and messages.
     */
    public static class GreetingRule {
        /** Unique ID for this rule */
        public String id = "";

        /** Whether this rule is active */
        public boolean enabled = true;

        /**
         * When to fire: "server_join", "world_enter", "respawn"
         */
        public String trigger = "server_join";

        /** Conditions that must all match (AND logic). Omitted = don't care. */
        public GreetingConditions conditions;

        /** Lines to send to the player */
        public List<String> messages = new ArrayList<>();

        /** Seconds to wait before sending (0 = immediate) */
        public int delaySeconds = 0;

        /**
         * If true, skip remaining rules after this one matches.
         * If false (default), continue evaluating more rules.
         */
        public boolean stopAfterMatch = false;

        /** If true, only show once per session even if triggered again */
        public boolean showOnce = false;
    }

    /**
     * All conditions are optional. When set, they use AND logic between
     * different condition types and OR logic within lists.
     */
    public static class GreetingConditions {
        /** Player must be in at least one of these LuckPerms groups (OR) */
        public List<String> groups;

        /** Player must have at least one of these permissions (OR) */
        public List<String> permissions;

        /** Current world must match at least one pattern (OR, supports * wildcards) */
        public List<String> worlds;

        /** Player arrived at one of these named spawn points (OR, respawn trigger) */
        public List<String> spawns;

        /** If non-null, must match first-join status */
        public Boolean firstJoin;
    }
}
