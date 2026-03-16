package com.eliteessentials.integration;

import com.eliteessentials.permissions.Permissions;

import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Logger;

/**
 * Integration with HyperPerms permission plugin.
 * Uses reflection to avoid compile-time dependency, same pattern as LuckPermsIntegration.
 * 
 * HyperPerms exposes getResolvedPermissions(UUID) which returns all granted permissions
 * including inherited ones from groups — perfect for scanning numeric permission values.
 */
public class HyperPermsIntegration {

    private static final Logger logger = Logger.getLogger("EliteEssentials");
    private static boolean registered = false;

    private HyperPermsIntegration() {}

    // ==================== REGISTRATION ====================

    /**
     * Schedule permission registration with HyperPerms.
     * Since HyperPerms may load after EliteEssentials, we delay registration
     * with increasing retry intervals (same pattern as LuckPerms).
     */
    public static void registerPermissions() {
        Thread registrationThread = new Thread(() -> {
            for (int attempt = 1; attempt <= 10; attempt++) {
                try {
                    Thread.sleep(1000 * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }

                if (tryRegisterPermissions()) {
                    return;
                }
            }

            logger.info("[HyperPerms] HyperPerms not detected, skipping permission registration.");
        }, "EliteEssentials-HyperPerms-Registration");

        registrationThread.setDaemon(true);
        registrationThread.start();
    }

    /**
     * Try to register permissions with HyperPerms' PermissionRegistry.
     * @return true if successful, false if HyperPerms not ready
     */
    private static boolean tryRegisterPermissions() {
        if (registered) {
            return true;
        }

        try {
            // Check if HyperPerms is loaded
            Class<?> hpClass = Class.forName("com.hyperperms.HyperPerms");
            Method getInstanceMethod = hpClass.getMethod("getInstance");
            Object hpInstance = getInstanceMethod.invoke(null);

            if (hpInstance == null) {
                return false;
            }

            // Get the PermissionRegistry singleton
            Class<?> registryClass = Class.forName("com.hyperperms.registry.PermissionRegistry");
            Method getRegistryInstance = registryClass.getMethod("getInstance");
            Object registry = getRegistryInstance.invoke(null);

            if (registry == null) {
                return false;
            }

            // Register our permissions using register(permission, description, category, plugin)
            Method registerMethod = registryClass.getMethod("register",
                    String.class, String.class, String.class, String.class);

            List<String> allPermissions = LuckPermsIntegration.getAllPermissions();
            int count = 0;
            for (String perm : allPermissions) {
                try {
                    Object result = registerMethod.invoke(registry, perm,
                            "EliteEssentials permission", "eliteessentials", "EliteEssentials");
                    if (Boolean.TRUE.equals(result)) {
                        count++;
                    }
                } catch (Exception ignored) {
                    // Permission may already exist
                }
            }

            logger.info("[HyperPerms] Registered " + count + " permissions with HyperPerms.");
            registered = true;
            return true;

        } catch (ClassNotFoundException e) {
            // HyperPerms not installed yet
            return false;
        } catch (IllegalStateException e) {
            // HyperPerms not ready yet
            return false;
        } catch (Exception e) {
            logger.warning("[HyperPerms] Error registering permissions: " + e.getMessage());
            return false;
        }
    }

    // ==================== AVAILABILITY CHECK ====================

    /**
     * Check if HyperPerms is available on the server.
     */
    public static boolean isAvailable() {
        try {
            Class<?> hpClass = Class.forName("com.hyperperms.HyperPerms");
            Method getInstanceMethod = hpClass.getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            return instance != null;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== RESOLVED PERMISSIONS ====================

    /**
     * Get all resolved (inherited) granted permissions for a player.
     * This is the key method — HyperPerms resolves group inheritance for us
     * and returns a flat Set of all granted permission strings.
     * 
     * @param playerId Player UUID
     * @return Set of granted permission strings, or empty set if unavailable
     */
    @SuppressWarnings("unchecked")
    public static Set<String> getResolvedPermissions(UUID playerId) {
        try {
            Class<?> hpClass = Class.forName("com.hyperperms.HyperPerms");
            Method getInstanceMethod = hpClass.getMethod("getInstance");
            Object hpInstance = getInstanceMethod.invoke(null);

            if (hpInstance == null) {
                return Collections.emptySet();
            }

            // HyperPermsAPI.getResolvedPermissions(UUID) returns Set<String>
            Method getResolvedMethod = hpInstance.getClass().getMethod("getResolvedPermissions", UUID.class);
            Object result = getResolvedMethod.invoke(hpInstance, playerId);

            if (result instanceof Set) {
                return (Set<String>) result;
            }

            return Collections.emptySet();
        } catch (Exception e) {
            return Collections.emptySet();
        }
    }

    // ==================== PERMISSION VALUE EXTRACTION ====================

    /**
     * Get the lowest numeric value from resolved permissions matching a prefix.
     * Used for cooldowns/warmups where lower is better (most favorable to player).
     * 
     * Example: prefix "eliteessentials.command.misc.heal.cooldown." finds
     * "eliteessentials.command.misc.heal.cooldown.30" and returns 30.
     * 
     * @param playerId Player UUID
     * @param permissionPrefix Permission prefix to search for
     * @return The lowest value found, or -1 if not found
     */
    public static int getInheritedPermissionValue(UUID playerId, String permissionPrefix) {
        Set<String> permissions = getResolvedPermissions(playerId);
        if (permissions.isEmpty()) {
            return -1;
        }

        int lowestValue = Integer.MAX_VALUE;
        boolean found = false;

        for (String perm : permissions) {
            if (perm.startsWith(permissionPrefix)) {
                String valuePart = perm.substring(permissionPrefix.length());
                try {
                    int value = Integer.parseInt(valuePart);
                    if (value < lowestValue) {
                        lowestValue = value;
                        found = true;
                    }
                } catch (NumberFormatException ignored) {
                    // Not a number suffix, skip
                }
            }
        }

        return found ? lowestValue : -1;
    }

    /**
     * Get the highest numeric value from resolved permissions matching a prefix.
     * Used for limits where higher is better (e.g., max homes, max warps).
     * 
     * Example: prefix "eliteessentials.command.home.limit." finds
     * "eliteessentials.command.home.limit.5" and returns 5.
     * 
     * @param playerId Player UUID
     * @param permissionPrefix Permission prefix to search for
     * @return The highest value found, or -1 if not found
     */
    public static int getHighestPermissionValue(UUID playerId, String permissionPrefix) {
        Set<String> permissions = getResolvedPermissions(playerId);
        if (permissions.isEmpty()) {
            return -1;
        }

        int highestValue = -1;

        for (String perm : permissions) {
            if (perm.startsWith(permissionPrefix)) {
                String valuePart = perm.substring(permissionPrefix.length());
                try {
                    int value = Integer.parseInt(valuePart);
                    if (value > highestValue) {
                        highestValue = value;
                    }
                } catch (NumberFormatException ignored) {
                    // Not a number suffix, skip
                }
            }
        }

        return highestValue;
    }

    // ==================== USER DATA ====================

    /**
     * Get the primary group for a player.
     * @param playerId Player UUID
     * @return Primary group name, or null if not found
     */
    public static String getPrimaryGroup(UUID playerId) {
        try {
            Object user = getHyperPermsUser(playerId);
            if (user == null) {
                return null;
            }

            Method getPrimaryGroupMethod = user.getClass().getMethod("getPrimaryGroup");
            return (String) getPrimaryGroupMethod.invoke(user);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get all groups for a player (including inherited groups).
     * @param playerId Player UUID
     * @return List of group names, or empty list if not found
     */
    public static List<String> getGroups(UUID playerId) {
        List<String> groups = new ArrayList<>();
        try {
            Object user = getHyperPermsUser(playerId);
            if (user == null) {
                return groups;
            }

            // User.getInheritedGroups() returns Set<String> of group names
            Method getInheritedGroupsMethod = user.getClass().getMethod("getInheritedGroups");
            Object result = getInheritedGroupsMethod.invoke(user);

            if (result instanceof Set) {
                @SuppressWarnings("unchecked")
                Set<String> groupSet = (Set<String>) result;
                groups.addAll(groupSet);
            }
        } catch (Exception e) {
            logger.warning("[HyperPerms] Error getting groups: " + e.getMessage());
        }
        return groups;
    }

    // ==================== PREFIX/SUFFIX ====================

    /**
     * Get the player's effective prefix from HyperPerms.
     * Uses ChatAPI.getPrefix() which resolves group prefix with proper priority/inheritance,
     * falling back to the user's custom prefix only if no group prefix is found.
     *
     * @param playerId Player UUID
     * @return The prefix string, or empty string if not set
     */
    public static String getPrefix(UUID playerId) {
        try {
            // Use ChatAPI.getPrefix() — resolves group prefix with priority/inheritance
            Class<?> chatApiClass = Class.forName("com.hyperperms.api.ChatAPI");
            Method getPrefixMethod = chatApiClass.getMethod("getPrefix", UUID.class);
            String prefix = (String) getPrefixMethod.invoke(null, playerId);
            return prefix != null ? prefix : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Get the player's effective suffix from HyperPerms.
     * Uses ChatAPI.getSuffix() which resolves group suffix with proper priority/inheritance.
     *
     * @param playerId Player UUID
     * @return The suffix string, or empty string if not set
     */
    public static String getSuffix(UUID playerId) {
        try {
            // Use ChatAPI.getSuffix() — resolves group suffix with priority/inheritance
            Class<?> chatApiClass = Class.forName("com.hyperperms.api.ChatAPI");
            Method getSuffixMethod = chatApiClass.getMethod("getSuffix", UUID.class);
            String suffix = (String) getSuffixMethod.invoke(null, playerId);
            return suffix != null ? suffix : "";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Get the player's primary group display name.
     * @param playerId Player UUID
     * @return The primary group name, or empty string if not found
     */
    public static String getPrimaryGroupDisplay(UUID playerId) {
        String group = getPrimaryGroup(playerId);
        return group != null ? group : "";
    }

    // ==================== GROUP SYNC ====================

    /**
     * Get all group names from HyperPerms.
     * @return List of group names, or empty list if unavailable
     */
    public static List<String> getLoadedGroupNames() {
        List<String> names = new ArrayList<>();
        try {
            Object api = getHyperPermsApi();
            if (api == null) return names;

            Method getGroupManagerMethod = api.getClass().getMethod("getGroupManager");
            Object groupManager = getGroupManagerMethod.invoke(api);
            if (groupManager == null) return names;

            Method getGroupsMethod = groupManager.getClass().getMethod("getGroups");
            Object groups = getGroupsMethod.invoke(groupManager);
            if (!(groups instanceof java.util.Collection)) return names;

            for (Object group : (java.util.Collection<?>) groups) {
                Method getNameMethod = group.getClass().getMethod("getName");
                String name = (String) getNameMethod.invoke(group);
                if (name != null) names.add(name);
            }
        } catch (Exception e) {
            logger.warning("[HyperPerms] Error getting groups: " + e.getMessage());
        }
        return names;
    }

    /**
     * Create a group in HyperPerms if it does not exist.
     * @param groupName Name of the group to create
     * @return true if group was created or already exists, false on error
     */
    public static boolean createGroup(String groupName) {
        if (groupName == null || groupName.isBlank()) return false;
        try {
            Object api = getHyperPermsApi();
            if (api == null) return false;

            Method getGroupManagerMethod = api.getClass().getMethod("getGroupManager");
            Object groupManager = getGroupManagerMethod.invoke(api);
            if (groupManager == null) return false;

            // Check if group already exists
            Method getGroupMethod = groupManager.getClass().getMethod("getGroup", String.class);
            Object existing = getGroupMethod.invoke(groupManager, groupName);
            if (existing != null) return true;

            Method createMethod = groupManager.getClass().getMethod("createGroup", String.class);
            Object group = createMethod.invoke(groupManager, groupName);
            return group != null;
        } catch (Exception e) {
            logger.warning("[HyperPerms] Error creating group '" + groupName + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Get HyperPerms API/instance. Tries getApi() first, then getInstance().
     */
    private static Object getHyperPermsApi() {
        try {
            Class<?> hpClass = Class.forName("com.hyperperms.HyperPerms");
            try {
                Method getApiMethod = hpClass.getMethod("getApi");
                Object api = getApiMethod.invoke(null);
                if (api != null) return api;
            } catch (NoSuchMethodException ignored) {}
            Method getInstanceMethod = hpClass.getMethod("getInstance");
            return getInstanceMethod.invoke(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== INTERNAL HELPERS ====================

    /**
     * Get a HyperPerms User object for a player via reflection.
     * Tries cached user first, then loads from storage if needed.
     * 
     * @param playerId Player UUID
     * @return The User object, or null if not found
     */
    private static Object getHyperPermsUser(UUID playerId) {
        try {
            Class<?> hpClass = Class.forName("com.hyperperms.HyperPerms");
            Method getInstanceMethod = hpClass.getMethod("getInstance");
            Object hpInstance = getInstanceMethod.invoke(null);

            if (hpInstance == null) {
                return null;
            }

            // Get UserManager
            Method getUserManagerMethod = hpInstance.getClass().getMethod("getUserManager");
            Object userManager = getUserManagerMethod.invoke(hpInstance);

            // Try getUser(UUID) for cached user first
            Method getUserMethod = userManager.getClass().getMethod("getUser", UUID.class);
            Object user = getUserMethod.invoke(userManager, playerId);

            if (user == null) {
                // Try loading from storage
                try {
                    Method loadUserMethod = userManager.getClass().getMethod("loadUser", UUID.class);
                    Object future = loadUserMethod.invoke(userManager, playerId);

                    // CompletableFuture<Optional<User>> — call join() then get()
                    Method joinMethod = future.getClass().getMethod("join");
                    Object optional = joinMethod.invoke(future);

                    Method isPresentMethod = optional.getClass().getMethod("isPresent");
                    if (Boolean.TRUE.equals(isPresentMethod.invoke(optional))) {
                        Method getMethod = optional.getClass().getMethod("get");
                        user = getMethod.invoke(optional);
                    }
                } catch (Exception loadEx) {
                    // Could not load, try getOrCreateUser as last resort
                    try {
                        Method getOrCreateMethod = userManager.getClass().getMethod("getOrCreateUser", UUID.class);
                        user = getOrCreateMethod.invoke(userManager, playerId);
                    } catch (Exception ignored) {}
                }
            }

            return user;
        } catch (Exception e) {
            return null;
        }
    }
}
