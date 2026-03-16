package com.eliteessentials.services;

import com.eliteessentials.EliteEssentials;
import com.eliteessentials.config.ConfigManager;
import com.eliteessentials.config.PluginConfig;
import com.eliteessentials.integration.HyperPermsIntegration;
import com.eliteessentials.integration.LuckPermsIntegration;
import com.eliteessentials.model.GroupChat;

import java.util.*;

/**
 * Service for syncing groups between LuckPerms/HyperPerms and EliteEssentials config.
 *
 * Directions:
 * - lp-to-ee / hp-to-ee: Copy groups from LuckPerms/HyperPerms into config.json
 * - ee-to-lp / ee-to-hp: Create missing groups in LuckPerms/HyperPerms from EE config
 */
public class GroupSyncService {

    private static final String DEFAULT_CHAT_FORMAT = "&7{player}&r: {message}";
    private static final int DEFAULT_PRIORITY = 0;
    private static final int DEFAULT_WARP_LIMIT = 5;

    private final ConfigManager configManager;

    public GroupSyncService(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Sync groups from LuckPerms into config.json.
     */
    public GroupSyncResult syncLuckPermsToEe() {
        return syncPermsToEe("LuckPerms", LuckPermsIntegration::isAvailable, LuckPermsIntegration::getLoadedGroupNames);
    }

    /**
     * Sync groups from HyperPerms into config.json.
     */
    public GroupSyncResult syncHyperPermsToEe() {
        return syncPermsToEe("HyperPerms", HyperPermsIntegration::isAvailable, HyperPermsIntegration::getLoadedGroupNames);
    }

    /**
     * Create missing EE config groups in LuckPerms.
     */
    public GroupSyncResult syncEeToLuckPerms() {
        return syncEeToPerms("LuckPerms", LuckPermsIntegration::isAvailable,
            LuckPermsIntegration::getLoadedGroupNames, LuckPermsIntegration::createGroup);
    }

    /**
     * Create missing EE config groups in HyperPerms.
     */
    public GroupSyncResult syncEeToHyperPerms() {
        return syncEeToPerms("HyperPerms", HyperPermsIntegration::isAvailable,
            HyperPermsIntegration::getLoadedGroupNames, HyperPermsIntegration::createGroup);
    }

    private GroupSyncResult syncPermsToEe(String backendName, java.util.function.Supplier<Boolean> isAvailable,
            java.util.function.Supplier<List<String>> getGroups) {
        GroupSyncResult result = new GroupSyncResult();
        result.backendName = backendName;
        if (!isAvailable.get()) {
            result.setError(backendName + " is not installed or not loaded.");
            return result;
        }

        List<String> permGroups = getGroups.get();
        if (permGroups.isEmpty()) {
            result.setError(backendName + " has no groups configured.");
            return result;
        }

        PluginConfig.ChatFormatConfig chatFormat = configManager.getConfig().chatFormat;
        PluginConfig.WarpsConfig warps = configManager.getConfig().warps;

        for (String groupName : permGroups) {
            String key = findExistingKey(chatFormat.groupFormats, groupName);
            if (key == null) {
                chatFormat.groupFormats.put(groupName, DEFAULT_CHAT_FORMAT);
                chatFormat.groupPriorities.put(groupName, DEFAULT_PRIORITY);
                result.addedFormats++;
            }

            key = findExistingKey(warps.groupLimits, groupName);
            if (key == null) {
                warps.groupLimits.put(groupName, DEFAULT_WARP_LIMIT);
                result.addedLimits++;
            }
        }

        configManager.saveConfig();
        result.success = true;
        result.permsToEe = true;
        return result;
    }

    private GroupSyncResult syncEeToPerms(String backendName, java.util.function.Supplier<Boolean> isAvailable,
            java.util.function.Supplier<List<String>> getLoadedGroups,
            java.util.function.Function<String, Boolean> createGroupFn) {
        GroupSyncResult result = new GroupSyncResult();
        result.backendName = backendName;
        if (!isAvailable.get()) {
            result.setError(backendName + " is not installed or not loaded.");
            return result;
        }

        Set<String> eeGroups = collectEeGroupNames();
        if (eeGroups.isEmpty()) {
            result.setError("No groups found in EE config (chatFormat.groupFormats, warps.groupLimits, or groupchats.json).");
            return result;
        }

        List<String> existingGroups = getLoadedGroups.get();
        Set<String> existingSet = new HashSet<>();
        for (String s : existingGroups) existingSet.add(s.toLowerCase());

        for (String groupName : eeGroups) {
            if (!existingSet.contains(groupName.toLowerCase())) {
                if (createGroupFn.apply(groupName)) {
                    result.createdGroups++;
                    existingSet.add(groupName.toLowerCase());
                } else {
                    result.errors.add("Failed to create group: " + groupName);
                }
            }
        }

        result.success = result.errors.isEmpty();
        result.permsToEe = false;
        return result;
    }

    private Set<String> collectEeGroupNames() {
        Set<String> names = new LinkedHashSet<>();
        PluginConfig config = configManager.getConfig();

        names.addAll(config.chatFormat.groupFormats.keySet());
        names.addAll(config.warps.groupLimits.keySet());

        GroupChatService gcs = EliteEssentials.getInstance().getGroupChatService();
        if (gcs != null) {
            for (GroupChat gc : gcs.getGroupChats()) {
                if (gc.isRequiresGroup()) {
                    names.add(gc.getGroupName());
                }
            }
        }
        return names;
    }

    private String findExistingKey(Map<String, ?> map, String groupName) {
        if (map.containsKey(groupName)) return groupName;
        for (String key : map.keySet()) {
            if (key.equalsIgnoreCase(groupName)) return key;
        }
        return null;
    }

    public static class GroupSyncResult {
        public boolean success;
        public int addedFormats;
        public int addedLimits;
        public int createdGroups;
        /** Name of backend used (LuckPerms or HyperPerms) */
        public String backendName;
        /** true = perms→ee (report addedFormats/addedLimits), false = ee→perms (report createdGroups) */
        public boolean permsToEe;
        public List<String> errors = new ArrayList<>();
        private String errorMessage;

        public void setError(String msg) {
            success = false;
            errorMessage = msg;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
