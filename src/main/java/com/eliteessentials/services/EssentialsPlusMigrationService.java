package com.eliteessentials.services;

import com.eliteessentials.model.*;
import com.eliteessentials.storage.GlobalStorageProvider;
import com.eliteessentials.storage.PlayerFileStorage;
import com.eliteessentials.storage.SpawnStorage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Migrates data from fof1092's EssentialsPlus plugin to EliteEssentials.
 * 
 * Source: mods/fof1092_EssentialsPlus/
 * - kits/{kitname}.json (each kit is a separate file)
 * - homes.json (array of all player homes, grouped by uuid)
 * - warps.json (array of all warps)
 * - spawns.json (array of spawn points)
 * - users/{uuid}.json (per-player: balance, playtime, ipHistory, ignoredPlayers, etc.)
 */
public class EssentialsPlusMigrationService {
    
    private static final Logger logger = Logger.getLogger("EliteEssentials");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    private final File modsFolder;
    private final GlobalStorageProvider warpStorage;
    private final SpawnStorage spawnStorage;
    private final KitService kitService;
    private final PlayerFileStorage playerFileStorage;
    
    // Migration stats
    private int warpsImported = 0;
    private int kitsImported = 0;
    private int playersImported = 0;
    private int homesImported = 0;
    private int spawnsImported = 0;
    private int usersImported = 0;
    private final List<String> errors = new ArrayList<>();
    
    // Collected during kit migration: uuid -> (kitId -> lastUsed timestamp)
    private final Map<UUID, Map<String, Long>> kitCooldownsFromKits = new HashMap<>();
    
    public EssentialsPlusMigrationService(File dataFolder, GlobalStorageProvider warpStorage,
                                          SpawnStorage spawnStorage, KitService kitService,
                                          PlayerFileStorage playerFileStorage) {
        // Go up from EliteEssentials folder to mods folder
        this.modsFolder = dataFolder.getParentFile();
        this.warpStorage = warpStorage;
        this.spawnStorage = spawnStorage;
        this.kitService = kitService;
        this.playerFileStorage = playerFileStorage;
    }
    
    /**
     * Check if EssentialsPlus data exists.
     */
    public boolean hasEssentialsPlusData() {
        File essentialsFolder = new File(modsFolder, "fof1092_EssentialsPlus");
        return essentialsFolder.exists() && essentialsFolder.isDirectory();
    }
    
    /**
     * Get the EssentialsPlus folder path.
     */
    public File getEssentialsPlusFolder() {
        return new File(modsFolder, "fof1092_EssentialsPlus");
    }

    
    /**
     * Run the full migration.
     * @param force if true, overwrite existing warps, kits, homes, spawns, and player data
     * @return MigrationResult with stats and any errors
     */
    public MigrationResult migrate(boolean force) {
        // Reset stats and collected data
        warpsImported = 0;
        kitsImported = 0;
        playersImported = 0;
        homesImported = 0;
        spawnsImported = 0;
        usersImported = 0;
        errors.clear();
        kitCooldownsFromKits.clear();
        
        File essentialsFolder = getEssentialsPlusFolder();
        
        if (!essentialsFolder.exists()) {
            errors.add("EssentialsPlus folder not found at: " + essentialsFolder.getAbsolutePath());
            return new MigrationResult(false, warpsImported, kitsImported, playersImported,
                    homesImported, spawnsImported, usersImported, errors);
        }
        
        logger.info("[Migration] ========================================");
        logger.info("[Migration] Starting EssentialsPlus migration" + (force ? " (FORCE MODE)" : "") + "...");
        logger.info("[Migration] Source: " + essentialsFolder.getAbsolutePath());
        logger.info("[Migration] ========================================");
        
        // Migrate warps
        migrateWarps(essentialsFolder, force);
        
        // Migrate kits (also collects lastClaimed into kitCooldownsFromKits)
        migrateKits(essentialsFolder, force);
        
        // Migrate spawns
        migrateSpawns(essentialsFolder, force);
        
        // Migrate homes
        migrateHomes(essentialsFolder, force);
        
        // Migrate user data (balance, playtime, ipHistory, ignoredPlayers, kit cooldowns)
        migrateUserData(essentialsFolder, force);
        
        logger.info("[Migration] ========================================");
        logger.info("[Migration] EssentialsPlus migration complete!");
        logger.info("[Migration] - Warps: " + warpsImported);
        logger.info("[Migration] - Kits: " + kitsImported);
        logger.info("[Migration] - Spawns: " + spawnsImported);
        logger.info("[Migration] - Players: " + playersImported);
        logger.info("[Migration] - Homes: " + homesImported);
        logger.info("[Migration] - Users: " + usersImported);
        if (!errors.isEmpty()) {
            logger.info("[Migration] - Errors: " + errors.size());
        }
        logger.info("[Migration] ========================================");
        
        return new MigrationResult(errors.isEmpty(), warpsImported, kitsImported, playersImported,
                homesImported, spawnsImported, usersImported, errors);
    }
    
    /**
     * Migrate warps from EssentialsPlus warps.json.
     * Format: { "version": "1.0", "warps": [ { "name": "...", "position": {...}, "rotation": {...}, "world": "..." } ] }
     */
    private void migrateWarps(File essentialsFolder, boolean force) {
        File warpsFile = new File(essentialsFolder, "warps.json");
        if (!warpsFile.exists()) {
            logger.info("[Migration] No warps.json found, skipping warp migration.");
            return;
        }
        
        logger.info("[Migration] Migrating EssentialsPlus warps.json...");
        
        try (Reader reader = new InputStreamReader(new FileInputStream(warpsFile), StandardCharsets.UTF_8)) {
            EssentialsPlusWarpsFile warpsData = gson.fromJson(reader, EssentialsPlusWarpsFile.class);
            
            if (warpsData == null || warpsData.warps == null || warpsData.warps.isEmpty()) {
                logger.info("[Migration] - No warps found in file.");
                return;
            }
            
            for (EssentialsPlusWarp epWarp : warpsData.warps) {
                String warpName = epWarp.name;
                
                if (!force && warpStorage.hasWarp(warpName)) {
                    logger.info("[Migration] - Skipping warp '" + warpName + "' (already exists)");
                    continue;
                }
                
                // Convert to our format - use world UUID as world name
                Location location = new Location(
                    epWarp.world,
                    epWarp.position.x,
                    epWarp.position.y,
                    epWarp.position.z,
                    epWarp.rotation.y, // yaw
                    0f // pitch - set to 0 to avoid player tilt
                );
                
                Warp warp = new Warp(warpName, location, Warp.Permission.ALL, "EssentialsPlus Migration");
                warpStorage.setWarp(warp);
                warpsImported++;
                logger.info("[Migration] - Imported warp: " + warpName);
            }
            
        } catch (Exception e) {
            String error = "Failed to migrate EssentialsPlus warps: " + e.getMessage();
            logger.severe("[Migration] " + error);
            errors.add(error);
        }
    }

    
    /**
     * Migrate kits from EssentialsPlus kits/ folder.
     * Each kit is a separate JSON file named after the kit.
     * Format: { "version": "...", "name": "...", "cooldown": ms, "storage": {...}, "hotbar": {...}, "armor": {...}, "lastClaimed": { uuid: ts }, ... }
     */
    private void migrateKits(File essentialsFolder, boolean force) {
        File kitsFolder = new File(essentialsFolder, "kits");
        if (!kitsFolder.exists() || !kitsFolder.isDirectory()) {
            logger.info("[Migration] No kits folder found, skipping kit migration.");
            return;
        }
        
        logger.info("[Migration] Migrating EssentialsPlus kits...");
        
        File[] kitFiles = kitsFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (kitFiles == null || kitFiles.length == 0) {
            logger.info("[Migration] - No kit files found.");
            return;
        }
        
        for (File kitFile : kitFiles) {
            try {
                migrateKitFile(kitFile, force);
            } catch (Exception e) {
                String error = "Failed to migrate kit " + kitFile.getName() + ": " + e.getMessage();
                logger.warning("[Migration] " + error);
                errors.add(error);
            }
        }
    }
    
    private void migrateKitFile(File kitFile, boolean force) throws Exception {
        try (Reader reader = new InputStreamReader(new FileInputStream(kitFile), StandardCharsets.UTF_8)) {
            EssentialsPlusKit epKit = gson.fromJson(reader, EssentialsPlusKit.class);
            
            if (epKit == null || epKit.name == null) {
                logger.warning("[Migration] - Invalid kit file: " + kitFile.getName());
                return;
            }
            
            String kitId = epKit.name.toLowerCase();
            
            // Collect lastClaimed for kit cooldown migration
            if (epKit.lastClaimed != null && !epKit.lastClaimed.isEmpty()) {
                for (Map.Entry<String, Long> entry : epKit.lastClaimed.entrySet()) {
                    try {
                        UUID uuid = UUID.fromString(entry.getKey());
                        kitCooldownsFromKits.computeIfAbsent(uuid, k -> new HashMap<>()).put(kitId, entry.getValue());
                    } catch (IllegalArgumentException ignored) { /* skip invalid uuid */ }
                }
            }
            
            if (!force && kitService.getKit(kitId) != null) {
                logger.info("[Migration] - Skipping kit '" + kitId + "' (already exists)");
                return;
            }
            
            // Convert items from all sections
            List<KitItem> items = new ArrayList<>();
            
            // Process hotbar items
            if (epKit.hotbar != null && epKit.hotbar.items != null) {
                for (Map.Entry<String, EssentialsPlusItem> entry : epKit.hotbar.items.entrySet()) {
                    int slot = parseIntSafe(entry.getKey(), 0);
                    EssentialsPlusItem epItem = entry.getValue();
                    items.add(new KitItem(epItem.itemId, epItem.quantity, "hotbar", slot));
                }
            }
            
            // Process storage items
            if (epKit.storage != null && epKit.storage.items != null) {
                for (Map.Entry<String, EssentialsPlusItem> entry : epKit.storage.items.entrySet()) {
                    int slot = parseIntSafe(entry.getKey(), 0);
                    EssentialsPlusItem epItem = entry.getValue();
                    items.add(new KitItem(epItem.itemId, epItem.quantity, "storage", slot));
                }
            }
            
            // Process armor items
            if (epKit.armor != null && epKit.armor.items != null) {
                for (Map.Entry<String, EssentialsPlusItem> entry : epKit.armor.items.entrySet()) {
                    int slot = parseIntSafe(entry.getKey(), 0);
                    EssentialsPlusItem epItem = entry.getValue();
                    items.add(new KitItem(epItem.itemId, epItem.quantity, "armor", slot));
                }
            }
            
            // Process utility items
            if (epKit.utility != null && epKit.utility.items != null) {
                for (Map.Entry<String, EssentialsPlusItem> entry : epKit.utility.items.entrySet()) {
                    int slot = parseIntSafe(entry.getKey(), 0);
                    EssentialsPlusItem epItem = entry.getValue();
                    items.add(new KitItem(epItem.itemId, epItem.quantity, "utility", slot));
                }
            }
            
            // Convert cooldown from milliseconds to seconds
            int cooldownSeconds = (int) (epKit.cooldown / 1000);
            
            // Create kit
            Kit kit = new Kit(
                kitId,
                epKit.name, // displayName
                "Imported from EssentialsPlus",
                null, // icon
                cooldownSeconds,
                false, // replaceInventory - EssentialsPlus adds items
                false, // onetime
                kitId.equalsIgnoreCase("starter"), // starterKit if named "starter"
                items
            );
            
            kitService.saveKit(kit);
            kitsImported++;
            logger.info("[Migration] - Imported kit: " + kitId + " (" + items.size() + " items, " + cooldownSeconds + "s cooldown)");
        }
    }
    
    private int parseIntSafe(String value, int defaultValue) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    
    /**
     * Migrate spawns from EssentialsPlus spawns.json.
     * Format: { "version": "1.0", "spawns": [ { "position": {...}, "rotation": {...}, "world": "...", "mainSpawn": bool } ] }
     */
    private void migrateSpawns(File essentialsFolder, boolean force) {
        File spawnsFile = new File(essentialsFolder, "spawns.json");
        if (!spawnsFile.exists()) {
            logger.info("[Migration] No spawns.json found, skipping spawn migration.");
            return;
        }
        
        logger.info("[Migration] Migrating EssentialsPlus spawns.json...");
        
        try (Reader reader = new InputStreamReader(new FileInputStream(spawnsFile), StandardCharsets.UTF_8)) {
            EssentialsPlusSpawnsFile spawnsData = gson.fromJson(reader, EssentialsPlusSpawnsFile.class);
            
            if (spawnsData == null || spawnsData.spawns == null || spawnsData.spawns.isEmpty()) {
                logger.info("[Migration] - No spawns found in file.");
                return;
            }
            
            // Group spawns by world to assign primary and names per world
            Map<String, List<EssentialsPlusSpawn>> byWorld = new LinkedHashMap<>();
            for (EssentialsPlusSpawn ep : spawnsData.spawns) {
                if (ep == null || ep.world == null || ep.position == null || ep.rotation == null) continue;
                byWorld.computeIfAbsent(ep.world, k -> new ArrayList<>()).add(ep);
            }
            
            for (Map.Entry<String, List<EssentialsPlusSpawn>> entry : byWorld.entrySet()) {
                String world = entry.getKey();
                List<EssentialsPlusSpawn> worldSpawns = entry.getValue();
                
                if (!force && spawnStorage.hasSpawn(world)) {
                    logger.info("[Migration] - Skipping spawn for world '" + world + "' (already exists)");
                    continue;
                }
                
                if (force && spawnStorage.hasSpawn(world)) {
                    for (SpawnStorage.SpawnData existing : new ArrayList<>(spawnStorage.getSpawns(world))) {
                        if (existing.name != null) {
                            spawnStorage.removeSpawn(world, existing.name);
                        }
                    }
                }
                
                EssentialsPlusSpawn mainSpawn = worldSpawns.stream().filter(s -> s.mainSpawn).findFirst().orElse(worldSpawns.get(0));
                int idx = 0;
                for (EssentialsPlusSpawn epSpawn : worldSpawns) {
                    boolean primary = (epSpawn == mainSpawn);
                    String name = primary ? "main" : ("spawn" + idx);
                    idx++;
                    
                    SpawnStorage.SpawnData result = spawnStorage.addSpawn(
                        world, name,
                        epSpawn.position.x, epSpawn.position.y, epSpawn.position.z,
                        epSpawn.rotation.y, epSpawn.rotation.x,
                        primary, true,
                        -1
                    );
                    if (result != null) {
                        spawnsImported++;
                        logger.info("[Migration] - Imported spawn for world: " + world + " (" + name + ")");
                    }
                }
            }
        } catch (Exception e) {
            String error = "Failed to migrate EssentialsPlus spawns: " + e.getMessage();
            logger.severe("[Migration] " + error);
            errors.add(error);
        }
    }
    
    /**
     * Migrate homes from EssentialsPlus homes.json.
     * Format: { "version": "1.0", "homes": [ { "uuid": "...", "name": "...", "position": {...}, "rotation": {...}, "world": "..." } ] }
     */
    private void migrateHomes(File essentialsFolder, boolean force) {
        File homesFile = new File(essentialsFolder, "homes.json");
        if (!homesFile.exists()) {
            logger.info("[Migration] No homes.json found, skipping home migration.");
            return;
        }
        
        logger.info("[Migration] Migrating EssentialsPlus homes.json...");
        
        try (Reader reader = new InputStreamReader(new FileInputStream(homesFile), StandardCharsets.UTF_8)) {
            EssentialsPlusHomesFile homesData = gson.fromJson(reader, EssentialsPlusHomesFile.class);
            
            if (homesData == null || homesData.homes == null || homesData.homes.isEmpty()) {
                logger.info("[Migration] - No homes found in file.");
                return;
            }
            
            // Group homes by player UUID
            Map<UUID, List<EssentialsPlusHome>> homesByPlayer = new HashMap<>();
            for (EssentialsPlusHome epHome : homesData.homes) {
                try {
                    UUID uuid = UUID.fromString(epHome.uuid);
                    homesByPlayer.computeIfAbsent(uuid, k -> new ArrayList<>()).add(epHome);
                } catch (IllegalArgumentException e) {
                    logger.warning("[Migration] - Skipping home with invalid UUID: " + epHome.uuid);
                }
            }
            
            // Process each player's homes
            for (Map.Entry<UUID, List<EssentialsPlusHome>> entry : homesByPlayer.entrySet()) {
                UUID uuid = entry.getKey();
                List<EssentialsPlusHome> playerHomes = entry.getValue();
                
                // Get or create our player file
                PlayerFile ourPlayer = playerFileStorage.getPlayer(uuid);
                if (ourPlayer == null) {
                    // Create new player with unknown name (will update when they join)
                    ourPlayer = playerFileStorage.getPlayer(uuid, "Unknown");
                }
                
                int homesForPlayer = 0;
                for (EssentialsPlusHome epHome : playerHomes) {
                    String homeName = epHome.name;
                    
                    if (!force && ourPlayer.hasHome(homeName)) {
                        logger.info("[Migration] - Skipping home '" + homeName + "' for " + uuid + " (already exists)");
                        continue;
                    }
                    
                    // Convert to our format - use world UUID as world name
                    Location location = new Location(
                        epHome.world,
                        epHome.position.x,
                        epHome.position.y,
                        epHome.position.z,
                        epHome.rotation.y, // yaw
                        0f // pitch - set to 0 to avoid player tilt
                    );
                    
                    Home home = new Home(homeName, location);
                    ourPlayer.setHome(home);
                    homesForPlayer++;
                    homesImported++;
                }
                
                if (homesForPlayer > 0) {
                    playerFileStorage.saveAndMarkDirty(uuid);
                    playersImported++;
                    logger.info("[Migration] - Imported " + homesForPlayer + " home(s) for player " + uuid);
                }
            }
            
        } catch (Exception e) {
            String error = "Failed to migrate EssentialsPlus homes: " + e.getMessage();
            logger.severe("[Migration] " + error);
            errors.add(error);
        }
        
        logger.info("[Migration] - Migrated " + homesImported + " homes for " + playersImported + " players");
    }
    
    /**
     * Migrate user data from EssentialsPlus users/{uuid}.json.
     * Format: { uuid, username, balance, playtime, firstJoinTimestamp, lastJoinTimestamp, ipHistory, ignoredPlayers }
     * Also applies kit cooldowns from lastClaimed (collected during kit migration).
     */
    private void migrateUserData(File essentialsFolder, boolean force) {
        File usersFolder = new File(essentialsFolder, "users");
        if (!usersFolder.exists() || !usersFolder.isDirectory()) {
            logger.info("[Migration] No users folder found, skipping user data migration.");
            return;
        }
        
        logger.info("[Migration] Migrating EssentialsPlus user data...");
        
        File[] userFiles = usersFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".json"));
        if (userFiles == null || userFiles.length == 0) {
            logger.info("[Migration] - No user files found.");
            return;
        }
        
        for (File userFile : userFiles) {
            try {
                String baseName = userFile.getName();
                if (baseName.endsWith(".json")) baseName = baseName.substring(0, baseName.length() - 5);
                UUID uuid;
                try {
                    uuid = UUID.fromString(baseName);
                } catch (IllegalArgumentException e) {
                    logger.warning("[Migration] - Skipping invalid user file (bad UUID): " + userFile.getName());
                    continue;
                }
                
                migrateUserFile(userFile, uuid, force);
            } catch (Exception e) {
                String error = "Failed to migrate user " + userFile.getName() + ": " + e.getMessage();
                logger.warning("[Migration] " + error);
                errors.add(error);
            }
        }
    }
    
    private void migrateUserFile(File userFile, UUID uuid, boolean force) throws Exception {
        try (Reader reader = new InputStreamReader(new FileInputStream(userFile), StandardCharsets.UTF_8)) {
            EssentialsPlusUser epUser = gson.fromJson(reader, EssentialsPlusUser.class);
            if (epUser == null) return;
            
            PlayerFile ourPlayer = playerFileStorage.getPlayer(uuid);
            if (ourPlayer == null) {
                ourPlayer = playerFileStorage.getPlayer(uuid, epUser.username != null ? epUser.username : "Unknown");
            } else if (!force) {
                // Only migrate if we have new data and force is set, or player is basically empty
                boolean hasData = ourPlayer.getWallet() != 0 || ourPlayer.getPlayTime() != 0
                        || !ourPlayer.getIpHistory().isEmpty() || !ourPlayer.getIgnoredPlayers().isEmpty();
                if (hasData) {
                    logger.info("[Migration] - Skipping user " + uuid + " (already has data)");
                    return;
                }
            }
            
            boolean changed = false;
            
            // Username / name
            if (epUser.username != null && !epUser.username.isEmpty() && (force || ourPlayer.getName() == null || "Unknown".equals(ourPlayer.getName()))) {
                ourPlayer.setName(epUser.username);
                changed = true;
            }
            
            // Balance
            if (epUser.balance != 0.0 && (force || ourPlayer.getWallet() == 0.0)) {
                ourPlayer.setWallet(epUser.balance);
                changed = true;
            }
            
            // Playtime - EP uses milliseconds, we use seconds
            if (epUser.playtime != 0 && (force || ourPlayer.getPlayTime() == 0)) {
                long playtimeSeconds = epUser.playtime / 1000;
                ourPlayer.setPlayTime(playtimeSeconds);
                changed = true;
            }
            
            // First join
            if (epUser.firstJoinTimestamp != 0 && (force || ourPlayer.getFirstJoin() == 0)) {
                ourPlayer.setFirstJoin(epUser.firstJoinTimestamp);
                changed = true;
            }
            
            // Last seen
            if (epUser.lastJoinTimestamp != 0 && (force || ourPlayer.getLastSeen() == 0)) {
                ourPlayer.setLastSeen(epUser.lastJoinTimestamp);
                changed = true;
            }
            
            // IP history
            if (epUser.ipHistory != null && !epUser.ipHistory.isEmpty() && (force || ourPlayer.getIpHistory().isEmpty())) {
                List<PlayerFile.IpHistoryEntry> entries = new ArrayList<>();
                for (EssentialsPlusIpEntry ipEntry : epUser.ipHistory) {
                    if (ipEntry != null && ipEntry.ip != null && !ipEntry.ip.isBlank()) {
                        entries.add(new PlayerFile.IpHistoryEntry(ipEntry.ip, ipEntry.lastUsed));
                    }
                }
                if (!entries.isEmpty()) {
                    ourPlayer.setIpHistory(entries);
                    changed = true;
                }
            }
            
            // Ignored players - EP stores as array of UUID strings (or usernames; assume UUIDs)
            if (epUser.ignoredPlayers != null && !epUser.ignoredPlayers.isEmpty()) {
                if (force || ourPlayer.getIgnoredPlayers().isEmpty()) {
                    if (force) ourPlayer.clearIgnored();
                    for (String ignored : epUser.ignoredPlayers) {
                        if (ignored == null || ignored.isBlank()) continue;
                        try {
                            UUID ignoredUuid = UUID.fromString(ignored);
                            ourPlayer.addIgnored(ignoredUuid);
                            changed = true;
                        } catch (IllegalArgumentException ignoredEx) {
                            // Might be username - we can't resolve, skip
                        }
                    }
                }
            }
            
            // Kit cooldowns from lastClaimed (collected during kit migration)
            Map<String, Long> cooldowns = kitCooldownsFromKits.get(uuid);
            if (cooldowns != null && !cooldowns.isEmpty()) {
                for (Map.Entry<String, Long> entry : cooldowns.entrySet()) {
                    String kitId = entry.getKey();
                    long lastUsed = entry.getValue();
                    if (force || ourPlayer.getKitLastUsed(kitId) == 0L) {
                        ourPlayer.getKitCooldowns().put(kitId, lastUsed);
                        changed = true;
                    }
                }
            }
            
            if (changed) {
                playerFileStorage.saveAndMarkDirty(uuid);
                usersImported++;
                logger.info("[Migration] - Imported user data for " + uuid);
            }
        }
    }

    
    // ==================== Inner Classes for EssentialsPlus Format ====================
    
    /**
     * EssentialsPlus position format (x, y, z).
     */
    private static class EssentialsPlusPosition {
        double x;
        double y;
        double z;
    }
    
    /**
     * EssentialsPlus rotation format (x=pitch, y=yaw, z=roll).
     */
    private static class EssentialsPlusRotation {
        float x; // pitch
        float y; // yaw
        float z; // roll
    }
    
    /**
     * EssentialsPlus warps.json file format.
     */
    private static class EssentialsPlusWarpsFile {
        String version;
        List<EssentialsPlusWarp> warps;
    }
    
    /**
     * EssentialsPlus warp entry.
     */
    private static class EssentialsPlusWarp {
        String name;
        EssentialsPlusPosition position;
        EssentialsPlusRotation rotation;
        String world;
    }
    
    /**
     * EssentialsPlus homes.json file format.
     */
    private static class EssentialsPlusHomesFile {
        String version;
        List<EssentialsPlusHome> homes;
    }
    
    /**
     * EssentialsPlus home entry.
     */
    private static class EssentialsPlusHome {
        String uuid;
        String name;
        EssentialsPlusPosition position;
        EssentialsPlusRotation rotation;
        String world;
    }
    
    /**
     * EssentialsPlus spawns.json file format.
     */
    private static class EssentialsPlusSpawnsFile {
        String version;
        List<EssentialsPlusSpawn> spawns;
    }
    
    /**
     * EssentialsPlus spawn entry.
     */
    private static class EssentialsPlusSpawn {
        EssentialsPlusPosition position;
        EssentialsPlusRotation rotation;
        String world;
        boolean mainSpawn;
    }
    
    /**
     * EssentialsPlus users/{uuid}.json format.
     */
    private static class EssentialsPlusUser {
        String uuid;
        String username;
        boolean frozen;
        long firstJoinTimestamp;
        long lastJoinTimestamp;
        long playtime;  // milliseconds
        long currentSessionStart;
        double balance;
        List<EssentialsPlusIpEntry> ipHistory;
        List<String> ignoredPlayers;
    }
    
    /**
     * EssentialsPlus IP history entry.
     */
    private static class EssentialsPlusIpEntry {
        String ip;
        long lastUsed;
    }
    
    /**
     * EssentialsPlus kit format.
     */
    private static class EssentialsPlusKit {
        String version;
        String name;
        long cooldown; // in milliseconds
        EssentialsPlusItemContainer storage;
        EssentialsPlusItemContainer armor;
        EssentialsPlusItemContainer hotbar;
        EssentialsPlusItemContainer utility;
        Map<String, Long> lastClaimed;
    }
    
    /**
     * EssentialsPlus item container (storage, hotbar, armor, utility).
     */
    private static class EssentialsPlusItemContainer {
        int capacity;
        Map<String, EssentialsPlusItem> items;
    }
    
    /**
     * EssentialsPlus item format.
     */
    private static class EssentialsPlusItem {
        String itemId;
        int quantity;
        double durability;
        double maxDurability;
        boolean overrideDroppedItemAnimation;
        // cachedPacket is ignored - it's just a duplicate
    }
    
    // ==================== Migration Result ====================
    
    /**
     * Result of a migration operation.
     */
    public static class MigrationResult {
        private final boolean success;
        private final int warpsImported;
        private final int kitsImported;
        private final int playersImported;
        private final int homesImported;
        private final int spawnsImported;
        private final int usersImported;
        private final List<String> errors;
        
        public MigrationResult(boolean success, int warpsImported, int kitsImported,
                              int playersImported, int homesImported, int spawnsImported,
                              int usersImported, List<String> errors) {
            this.success = success;
            this.warpsImported = warpsImported;
            this.kitsImported = kitsImported;
            this.playersImported = playersImported;
            this.homesImported = homesImported;
            this.spawnsImported = spawnsImported;
            this.usersImported = usersImported;
            this.errors = new ArrayList<>(errors);
        }
        
        public boolean isSuccess() { return success; }
        public int getWarpsImported() { return warpsImported; }
        public int getKitsImported() { return kitsImported; }
        public int getPlayersImported() { return playersImported; }
        public int getHomesImported() { return homesImported; }
        public int getSpawnsImported() { return spawnsImported; }
        public int getUsersImported() { return usersImported; }
        public List<String> getErrors() { return errors; }
        
        public int getTotalImported() {
            return warpsImported + kitsImported + homesImported + spawnsImported + usersImported;
        }
    }
}
