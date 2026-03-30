package com.eliteessentials.services;

import com.eliteessentials.model.*;
import com.eliteessentials.storage.PlayerStorageProvider;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Migrates data from HomesPlus plugin to EliteEssentials.
 * 
 * Source: mods/HomesPlus_HomesPlus/
 * - homes.json (UUID -> { homeName -> location })
 */
public class HomesPlusMigrationService {
    
    private static final Logger logger = Logger.getLogger("EliteEssentials");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    private final File modsFolder;
    private final PlayerStorageProvider playerFileStorage;
    
    // Migration stats
    private int playersImported = 0;
    private int homesImported = 0;
    private final List<String> errors = new ArrayList<>();
    
    public HomesPlusMigrationService(File dataFolder, PlayerStorageProvider playerFileStorage) {
        this.modsFolder = dataFolder.getParentFile();
        this.playerFileStorage = playerFileStorage;
    }
    
    /**
     * Check if HomesPlus data exists.
     */
    public boolean hasHomesPlusData() {
        File homesPlusFolder = new File(modsFolder, "HomesPlus_HomesPlus");
        return homesPlusFolder.exists() && homesPlusFolder.isDirectory();
    }
    
    /**
     * Get the HomesPlus folder path.
     */
    public File getHomesPlusFolder() {
        return new File(modsFolder, "HomesPlus_HomesPlus");
    }
    
    /**
     * Run the full migration.
     * @return MigrationResult with stats and any errors
     */
    public MigrationResult migrate() {
        playersImported = 0;
        homesImported = 0;
        errors.clear();
        
        File homesPlusFolder = getHomesPlusFolder();
        
        if (!homesPlusFolder.exists()) {
            errors.add("HomesPlus folder not found at: " + homesPlusFolder.getAbsolutePath());
            return new MigrationResult(false, playersImported, homesImported, errors);
        }
        
        logger.info("[Migration] ========================================");
        logger.info("[Migration] Starting HomesPlus migration...");
        logger.info("[Migration] Source: " + homesPlusFolder.getAbsolutePath());
        logger.info("[Migration] ========================================");
        
        migrateHomes(homesPlusFolder);
        
        logger.info("[Migration] ========================================");
        logger.info("[Migration] HomesPlus migration complete!");
        logger.info("[Migration] - Players: " + playersImported);
        logger.info("[Migration] - Homes: " + homesImported);
        if (!errors.isEmpty()) {
            logger.info("[Migration] - Errors: " + errors.size());
        }
        logger.info("[Migration] ========================================");
        
        return new MigrationResult(errors.isEmpty(), playersImported, homesImported, errors);
    }
    
    /**
     * Migrate homes from HomesPlus homes.json.
     * Format: { "uuid": { "homeName": { "worldName": "...", "x": ..., "y": ..., "z": ..., "yaw": ..., "pitch": ... } } }
     */
    private void migrateHomes(File homesPlusFolder) {
        File homesFile = new File(homesPlusFolder, "homes.json");
        if (!homesFile.exists()) {
            logger.info("[Migration] No homes.json found, skipping home migration.");
            return;
        }
        
        logger.info("[Migration] Migrating HomesPlus homes.json...");
        
        try (Reader reader = new InputStreamReader(new FileInputStream(homesFile), StandardCharsets.UTF_8)) {
            Type type = new TypeToken<Map<String, Map<String, HomesPlusLocation>>>(){}.getType();
            Map<String, Map<String, HomesPlusLocation>> hpHomes = gson.fromJson(reader, type);
            
            if (hpHomes == null || hpHomes.isEmpty()) {
                logger.info("[Migration] - No homes found in file.");
                return;
            }
            
            for (Map.Entry<String, Map<String, HomesPlusLocation>> playerEntry : hpHomes.entrySet()) {
                String uuidStr = playerEntry.getKey();
                Map<String, HomesPlusLocation> playerHomes = playerEntry.getValue();
                
                if (playerHomes == null || playerHomes.isEmpty()) {
                    continue;
                }
                
                try {
                    UUID uuid = UUID.fromString(uuidStr);
                    
                    PlayerFile ourPlayer = playerFileStorage.getPlayer(uuid);
                    if (ourPlayer == null) {
                        ourPlayer = playerFileStorage.getPlayer(uuid, "Unknown");
                    }
                    
                    int homesForPlayer = 0;
                    for (Map.Entry<String, HomesPlusLocation> homeEntry : playerHomes.entrySet()) {
                        String homeName = homeEntry.getKey();
                        HomesPlusLocation hpLoc = homeEntry.getValue();
                        
                        if (ourPlayer.hasHome(homeName)) {
                            logger.info("[Migration] - Skipping home '" + homeName + "' for " + uuidStr + " (already exists)");
                            continue;
                        }
                        
                        Location location = new Location(
                            hpLoc.worldName,
                            hpLoc.x,
                            hpLoc.y,
                            hpLoc.z,
                            hpLoc.yaw,
                            0f // pitch set to 0 to avoid player tilt
                        );
                        
                        Home home = new Home(homeName, location);
                        ourPlayer.setHome(home);
                        homesForPlayer++;
                        homesImported++;
                    }
                    
                    if (homesForPlayer > 0) {
                        playerFileStorage.saveAndMarkDirty(uuid);
                        playersImported++;
                        logger.info("[Migration] - Imported " + homesForPlayer + " home(s) for player " + uuidStr);
                    }
                    
                } catch (IllegalArgumentException e) {
                    logger.warning("[Migration] - Skipping invalid UUID: " + uuidStr);
                }
            }
            
        } catch (Exception e) {
            String error = "Failed to migrate HomesPlus homes: " + e.getMessage();
            logger.severe("[Migration] " + error);
            errors.add(error);
        }
        
        logger.info("[Migration] - Migrated " + homesImported + " homes for " + playersImported + " players");
    }
    
    // ==================== Inner Classes ====================
    
    private static class HomesPlusLocation {
        String worldName;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
    }
    
    // ==================== Migration Result ====================
    
    public static class MigrationResult {
        private final boolean success;
        private final int playersImported;
        private final int homesImported;
        private final List<String> errors;
        
        public MigrationResult(boolean success, int playersImported, int homesImported, List<String> errors) {
            this.success = success;
            this.playersImported = playersImported;
            this.homesImported = homesImported;
            this.errors = new ArrayList<>(errors);
        }
        
        public boolean isSuccess() { return success; }
        public int getPlayersImported() { return playersImported; }
        public int getHomesImported() { return homesImported; }
        public List<String> getErrors() { return errors; }
    }
}
