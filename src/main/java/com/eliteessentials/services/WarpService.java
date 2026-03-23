package com.eliteessentials.services;

import com.eliteessentials.config.ConfigManager;
import com.eliteessentials.integration.HyperPermsIntegration;
import com.eliteessentials.integration.LuckPermsIntegration;
import com.eliteessentials.model.Location;
import com.eliteessentials.model.Warp;
import com.eliteessentials.permissions.PermissionService;
import com.eliteessentials.permissions.Permissions;
import com.eliteessentials.storage.GlobalStorageProvider;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for managing server warps.
 */
public class WarpService {

    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");
    private static final int MAX_NAME_LENGTH = 32;

    private final GlobalStorageProvider storage;
    private ConfigManager configManager;

    public WarpService(GlobalStorageProvider storage) {
        this.storage = storage;
    }
    
    /**
     * Set the config manager for warp limits.
     */
    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * Validate a warp name.
     * @return error message if invalid, null if valid
     */
    public String validateWarpName(String name) {
        if (name == null || name.isEmpty()) {
            return "Warp name cannot be empty.";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            return "Warp name cannot be longer than " + MAX_NAME_LENGTH + " characters.";
        }
        if (!VALID_NAME_PATTERN.matcher(name).matches()) {
            return "Warp name must be alphanumeric (letters, numbers, underscore, dash).";
        }
        return null;
    }

    /**
     * Create or update a warp.
     * @return error message if failed, null if successful
     */
    public String setWarp(String name, Location location, Warp.Permission permission, String createdBy) {
        return setWarp(name, location, permission, createdBy, "");
    }

    /**
     * Create or update a warp with description.
     * @return error message if failed, null if successful
     */
    public String setWarp(String name, Location location, Warp.Permission permission, String createdBy, String description) {
        String validationError = validateWarpName(name);
        if (validationError != null) {
            return validationError;
        }

        Warp warp = new Warp(name, location, permission, createdBy, description);
        storage.setWarp(warp);
        return null;
    }

    /**
     * Update a warp's description.
     * @return true if updated, false if warp not found
     */
    public boolean updateWarpDescription(String name, String description) {
        Optional<Warp> warpOpt = storage.getWarp(name);
        if (warpOpt.isEmpty()) {
            return false;
        }
        Warp warp = warpOpt.get();
        warp.setDescription(description);
        storage.setWarp(warp);
        return true;
    }

    /**
     * Get a warp by name.
     */
    public Optional<Warp> getWarp(String name) {
        return storage.getWarp(name);
    }

    /**
     * Delete a warp.
     * @return true if deleted, false if not found
     */
    public boolean deleteWarp(String name) {
        return storage.deleteWarp(name);
    }

    /**
     * Check if a warp exists.
     */
    public boolean warpExists(String name) {
        return storage.hasWarp(name);
    }

    /**
     * Get all warps as a Map.
     */
    public Map<String, Warp> getAllWarps() {
        return storage.getAllWarps();
    }

    /**
     * Get all warps as a List.
     */
    public List<Warp> getAllWarpsList() {
        return new ArrayList<>(storage.getAllWarps().values());
    }

    /**
     * Get warps accessible to a player (based on OP status).
     * Uses traditional loop instead of streams for better performance in hot paths.
     */
    public List<Warp> getAccessibleWarps(boolean isOp) {
        List<Warp> result = new ArrayList<>();
        for (Warp warp : storage.getAllWarps().values()) {
            if (isOp || warp.getPermission() == Warp.Permission.ALL) {
                result.add(warp);
            }
        }
        result.sort(Comparator.comparing(Warp::getName));
        return result;
    }

    /**
     * Get warp names accessible to a player.
     * Uses traditional loop instead of streams for better performance.
     */
    public Set<String> getAccessibleWarpNames(boolean isOp) {
        Set<String> result = new HashSet<>();
        for (Warp warp : storage.getAllWarps().values()) {
            if (isOp || warp.getPermission() == Warp.Permission.ALL) {
                result.add(warp.getName());
            }
        }
        return result;
    }

    /**
     * Check if a player can use a specific warp.
     */
    public boolean canUseWarp(Warp warp, boolean isOp) {
        if (warp == null) return false;
        return isOp || warp.getPermission() == Warp.Permission.ALL;
    }

    /**
     * Update warp permission level.
     */
    public boolean updateWarpPermission(String name, Warp.Permission permission) {
        Optional<Warp> warpOpt = storage.getWarp(name);
        if (warpOpt.isEmpty()) {
            return false;
        }
        Warp warp = warpOpt.get();
        warp.setPermission(permission);
        storage.setWarp(warp);
        return true;
    }

    /**
     * Save warps to disk.
     */
    public void save() {
        storage.save();
    }
    
    /**
     * Get the warp limit for a player based on their permissions/groups.
     * Returns -1 for unlimited.
     * 
     * Custom limit values require LuckPerms. Without LuckPerms, config defaults are used.
     */
    public int getWarpLimit(UUID playerId) {
        if (configManager == null) {
            return -1; // No limit if config not set
        }
        
        var config = configManager.getConfig().warps;
        
        // Check if advanced permissions mode
        if (configManager.getConfig().advancedPermissions) {
            // Check for unlimited permission
            if (PermissionService.get().hasPermission(playerId, Permissions.WARP_LIMIT_UNLIMITED)) {
                return -1;
            }
            
            // Try to get custom limit from LuckPerms (any value)
            if (LuckPermsIntegration.isAvailable()) {
                int lpLimit = getHighestLuckPermsLimit(playerId, Permissions.WARP_LIMIT_PREFIX);
                if (lpLimit > 0) {
                    return lpLimit;
                }
            }
            
            // Try HyperPerms as fallback
            if (HyperPermsIntegration.isAvailable()) {
                int hpLimit = HyperPermsIntegration.getHighestPermissionValue(playerId, Permissions.WARP_LIMIT_PREFIX);
                if (hpLimit > 0) {
                    return hpLimit;
                }
            }
            
            // Check group-based limits from config (try both LP and HP for group data)
            Set<String> groups = new HashSet<>();
            if (LuckPermsIntegration.isAvailable()) {
                groups.addAll(LuckPermsIntegration.getGroups(playerId));
            } else if (HyperPermsIntegration.isAvailable()) {
                groups.addAll(HyperPermsIntegration.getGroups(playerId));
            }
            int groupLimit = getHighestGroupLimit(groups, config.groupLimits);
            if (groupLimit != Integer.MIN_VALUE) {
                return groupLimit;
            }
        }
        
        // Fall back to global config limit
        return config.maxWarps;
    }
    
    /**
     * Get the highest limit value from LuckPerms permissions.
     * Scans for permissions matching the prefix and returns the highest number found.
     */
    private int getHighestLuckPermsLimit(UUID playerId, String permissionPrefix) {
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            java.lang.reflect.Method getMethod = providerClass.getMethod("get");
            Object luckPerms = getMethod.invoke(null);
            
            if (luckPerms == null) return -1;
            
            java.lang.reflect.Method getUserManagerMethod = luckPerms.getClass().getMethod("getUserManager");
            Object userManager = getUserManagerMethod.invoke(luckPerms);
            
            java.lang.reflect.Method getUserMethod = userManager.getClass().getMethod("getUser", UUID.class);
            Object user = getUserMethod.invoke(userManager, playerId);
            
            if (user == null) return -1;
            
            // Get CachedData -> PermissionData -> PermissionMap
            java.lang.reflect.Method getCachedDataMethod = user.getClass().getMethod("getCachedData");
            Object cachedData = getCachedDataMethod.invoke(user);
            
            java.lang.reflect.Method getPermissionDataMethod = cachedData.getClass().getMethod("getPermissionData");
            Object permissionData = getPermissionDataMethod.invoke(cachedData);
            
            java.lang.reflect.Method getPermissionMapMethod = permissionData.getClass().getMethod("getPermissionMap");
            @SuppressWarnings("unchecked")
            Map<String, Boolean> permMap = (Map<String, Boolean>) getPermissionMapMethod.invoke(permissionData);
            
            int highestValue = -1;
            
            for (Map.Entry<String, Boolean> entry : permMap.entrySet()) {
                if (entry.getValue() && entry.getKey().startsWith(permissionPrefix)) {
                    String valuePart = entry.getKey().substring(permissionPrefix.length());
                    try {
                        int value = Integer.parseInt(valuePart);
                        if (value > highestValue) {
                            highestValue = value;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            return highestValue;
            
        } catch (Exception e) {
            return -1;
        }
    }
    
    /**
     * Get the highest warp limit from a player's groups.
     * Returns Integer.MIN_VALUE if no group limit found.
     */
    private int getHighestGroupLimit(Set<String> playerGroups, Map<String, Integer> groupLimits) {
        int highest = Integer.MIN_VALUE;
        
        for (String group : playerGroups) {
            // Check case-insensitive
            for (Map.Entry<String, Integer> entry : groupLimits.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(group)) {
                    int limit = entry.getValue();
                    // -1 means unlimited, which is always highest
                    if (limit == -1) {
                        return -1;
                    }
                    if (limit > highest) {
                        highest = limit;
                    }
                }
            }
        }
        
        return highest;
    }
    
    /**
     * Check if a player can create more warps.
     * @return true if player can create more warps, false if at limit
     */
    public boolean canCreateWarp(UUID playerId) {
        int limit = getWarpLimit(playerId);
        if (limit == -1) {
            return true; // Unlimited
        }
        
        int currentCount = getWarpCount();
        return currentCount < limit;
    }
    
    /**
     * Get the total number of warps.
     */
    public int getWarpCount() {
        return storage.getAllWarps().size();
    }
}
