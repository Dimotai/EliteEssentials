package com.eliteessentials.services;

import com.eliteessentials.model.Home;
import com.eliteessentials.model.Location;
import com.eliteessentials.model.PlayerData;
import com.eliteessentials.model.PlayerFile;
import com.eliteessentials.storage.PlayerStorageProvider;
import com.eliteessentials.storage.PlayTimeRewardStorage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Logger;

/**
 * Handles one-time migration from old monolithic JSON files to per-player files.
 * 
 * Migrates:
 * - homes.json -> players/{uuid}.json (homes)
 * - players.json -> players/{uuid}.json (name, wallet, playtime, etc.)
 * - back_locations.json -> players/{uuid}.json (backHistory)
 * - kit_claims.json -> players/{uuid}.json (kitClaims)
 * - playtime_claims.json -> players/{uuid}.json (playtimeClaims)
 * - first_join.json -> redundant (uses firstJoin timestamp)
 * 
 * After migration, old files are moved to backup/ folder.
 */
public class DataMigrationService {
    
    private static final Logger logger = Logger.getLogger("EliteEssentials");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    
    private final File dataFolder;
    private final PlayerStorageProvider playerFileStorage;
    
    // Files to migrate
    private static final String[] OLD_FILES = {
        "homes.json",
        "players.json", 
        "back_locations.json",
        "kit_claims.json",
        "playtime_claims.json",
        "first_join.json"
    };
    
    public DataMigrationService(File dataFolder, PlayerStorageProvider playerFileStorage) {
        this.dataFolder = dataFolder;
        this.playerFileStorage = playerFileStorage;
    }
    
    /**
     * Check if migration is needed (any old files exist).
     */
    public boolean needsMigration() {
        for (String filename : OLD_FILES) {
            File file = new File(dataFolder, filename);
            if (file.exists()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Run the migration process.
     * Returns true if migration was successful.
     */
    public boolean migrate() {
        if (!needsMigration()) {
            logger.info("[Migration] No old data files found, skipping migration.");
            return true;
        }
        
        logger.info("[Migration] ========================================");
        logger.info("[Migration] Starting data migration to per-player files...");
        logger.info("[Migration] ========================================");
        
        // Track all players we encounter
        Map<UUID, PlayerFile> playerFiles = new HashMap<>();
        
        int totalHomes = 0;
        int totalPlayers = 0;
        int totalBackLocations = 0;
        int totalKitClaims = 0;
        int totalPlaytimeClaims = 0;
        
        try {
            // 1. Migrate players.json first (has core player data)
            File playersFile = new File(dataFolder, "players.json");
            if (playersFile.exists()) {
                logger.info("[Migration] Migrating players.json...");
                int count = migratePlayersJson(playersFile, playerFiles);
                totalPlayers = count;
                logger.info("[Migration] - Migrated " + count + " player records");
            }
            
            // 2. Migrate homes.json
            File homesFile = new File(dataFolder, "homes.json");
            if (homesFile.exists()) {
                logger.info("[Migration] Migrating homes.json...");
                int count = migrateHomesJson(homesFile, playerFiles);
                totalHomes = count;
                logger.info("[Migration] - Migrated " + count + " homes");
            }
            
            // 3. Migrate back_locations.json
            File backFile = new File(dataFolder, "back_locations.json");
            if (backFile.exists()) {
                logger.info("[Migration] Migrating back_locations.json...");
                int count = migrateBackLocationsJson(backFile, playerFiles);
                totalBackLocations = count;
                logger.info("[Migration] - Migrated " + count + " back location entries");
            }
            
            // 4. Migrate kit_claims.json
            File kitClaimsFile = new File(dataFolder, "kit_claims.json");
            if (kitClaimsFile.exists()) {
                logger.info("[Migration] Migrating kit_claims.json...");
                int count = migrateKitClaimsJson(kitClaimsFile, playerFiles);
                totalKitClaims = count;
                logger.info("[Migration] - Migrated " + count + " kit claim entries");
            }
            
            // 5. Migrate playtime_claims.json
            File playtimeClaimsFile = new File(dataFolder, "playtime_claims.json");
            if (playtimeClaimsFile.exists()) {
                logger.info("[Migration] Migrating playtime_claims.json...");
                int count = migratePlaytimeClaimsJson(playtimeClaimsFile, playerFiles);
                totalPlaytimeClaims = count;
                logger.info("[Migration] - Migrated " + count + " playtime claim entries");
            }
            
            // 6. Save all player files
            logger.info("[Migration] Saving " + playerFiles.size() + " player files...");
            for (PlayerFile pf : playerFiles.values()) {
                playerFileStorage.savePlayerDirect(pf);
            }
            
            // 7. Move old files to backup folder
            logger.info("[Migration] Moving old files to backup folder...");
            moveOldFilesToBackup();
            
            logger.info("[Migration] ========================================");
            logger.info("[Migration] Migration complete!");
            logger.info("[Migration] - Players: " + totalPlayers);
            logger.info("[Migration] - Homes: " + totalHomes);
            logger.info("[Migration] - Back locations: " + totalBackLocations);
            logger.info("[Migration] - Kit claims: " + totalKitClaims);
            logger.info("[Migration] - Playtime claims: " + totalPlaytimeClaims);
            logger.info("[Migration] - Total player files created: " + playerFiles.size());
            logger.info("[Migration] ========================================");
            
            return true;
            
        } catch (Exception e) {
            logger.severe("[Migration] Migration failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    
    /**
     * Migrate players.json (UUID -> PlayerData).
     */
    private int migratePlayersJson(File file, Map<UUID, PlayerFile> playerFiles) throws Exception {
        Type type = new TypeToken<Map<UUID, PlayerData>>(){}.getType();
        
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<UUID, PlayerData> data = gson.fromJson(reader, type);
            if (data == null) return 0;
            
            for (Map.Entry<UUID, PlayerData> entry : data.entrySet()) {
                UUID uuid = entry.getKey();
                PlayerData old = entry.getValue();
                
                PlayerFile pf = playerFiles.computeIfAbsent(uuid, 
                    id -> new PlayerFile(id, old.getName()));
                
                pf.setName(old.getName());
                pf.setFirstJoin(old.getFirstJoin());
                pf.setLastSeen(old.getLastSeen());
                pf.setWallet(old.getWallet());
                pf.setPlayTime(old.getPlayTime());
            }
            
            return data.size();
        }
    }
    
    /**
     * Migrate homes.json (UUID -> Map<String, Home>).
     */
    private int migrateHomesJson(File file, Map<UUID, PlayerFile> playerFiles) throws Exception {
        Type type = new TypeToken<Map<UUID, Map<String, Home>>>(){}.getType();
        
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<UUID, Map<String, Home>> data = gson.fromJson(reader, type);
            if (data == null) return 0;
            
            int totalHomes = 0;
            for (Map.Entry<UUID, Map<String, Home>> entry : data.entrySet()) {
                UUID uuid = entry.getKey();
                Map<String, Home> homes = entry.getValue();
                
                PlayerFile pf = playerFiles.computeIfAbsent(uuid, 
                    id -> new PlayerFile(id, "Unknown"));
                
                pf.setHomes(homes);
                totalHomes += homes.size();
            }
            
            return totalHomes;
        }
    }
    
    /**
     * Migrate back_locations.json (UUID -> List<Location>).
     */
    private int migrateBackLocationsJson(File file, Map<UUID, PlayerFile> playerFiles) throws Exception {
        Type type = new TypeToken<Map<UUID, List<Location>>>(){}.getType();
        
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<UUID, List<Location>> data = gson.fromJson(reader, type);
            if (data == null) return 0;
            
            int count = 0;
            for (Map.Entry<UUID, List<Location>> entry : data.entrySet()) {
                UUID uuid = entry.getKey();
                List<Location> history = entry.getValue();
                
                PlayerFile pf = playerFiles.computeIfAbsent(uuid, 
                    id -> new PlayerFile(id, "Unknown"));
                
                pf.setBackHistory(history);
                count++;
            }
            
            return count;
        }
    }
    
    /**
     * Migrate kit_claims.json (UUID string -> List<String>).
     */
    private int migrateKitClaimsJson(File file, Map<UUID, PlayerFile> playerFiles) throws Exception {
        Type type = new TypeToken<Map<String, List<String>>>(){}.getType();
        
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<String, List<String>> data = gson.fromJson(reader, type);
            if (data == null) return 0;
            
            int count = 0;
            for (Map.Entry<String, List<String>> entry : data.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    List<String> claims = entry.getValue();
                    
                    PlayerFile pf = playerFiles.computeIfAbsent(uuid, 
                        id -> new PlayerFile(id, "Unknown"));
                    
                    pf.setKitClaims(new HashSet<>(claims));
                    count++;
                } catch (IllegalArgumentException e) {
                    logger.warning("[Migration] Invalid UUID in kit_claims.json: " + entry.getKey());
                }
            }
            
            return count;
        }
    }
    
    /**
     * Migrate playtime_claims.json (UUID -> PlayerRewardData).
     */
    private int migratePlaytimeClaimsJson(File file, Map<UUID, PlayerFile> playerFiles) throws Exception {
        Type type = new TypeToken<Map<UUID, PlayTimeRewardStorage.PlayerRewardData>>(){}.getType();
        
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            Map<UUID, PlayTimeRewardStorage.PlayerRewardData> data = gson.fromJson(reader, type);
            if (data == null) return 0;
            
            int count = 0;
            for (Map.Entry<UUID, PlayTimeRewardStorage.PlayerRewardData> entry : data.entrySet()) {
                UUID uuid = entry.getKey();
                PlayTimeRewardStorage.PlayerRewardData old = entry.getValue();
                
                PlayerFile pf = playerFiles.computeIfAbsent(uuid, 
                    id -> new PlayerFile(id, "Unknown"));
                
                PlayerFile.PlaytimeClaims claims = pf.getPlaytimeClaims();
                if (old.claimedMilestones != null) {
                    claims.claimedMilestones.addAll(old.claimedMilestones);
                }
                if (old.repeatableCounts != null) {
                    claims.repeatableCounts.putAll(old.repeatableCounts);
                }
                count++;
            }
            
            return count;
        }
    }
    
    /**
     * Move old files to backup folder.
     */
    private void moveOldFilesToBackup() {
        // Create backup folder with timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File backupFolder = new File(dataFolder, "backup/migration_" + timestamp);
        backupFolder.mkdirs();
        
        for (String filename : OLD_FILES) {
            File oldFile = new File(dataFolder, filename);
            if (oldFile.exists()) {
                File backupFile = new File(backupFolder, filename);
                try {
                    Files.move(oldFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    logger.info("[Migration] Moved " + filename + " to backup folder");
                } catch (IOException e) {
                    logger.warning("[Migration] Failed to move " + filename + " to backup: " + e.getMessage());
                }
            }
        }
        
        // Also move joined_players.txt if it exists (redundant with player files)
        File joinedPlayersFile = new File(dataFolder, "joined_players.txt");
        if (joinedPlayersFile.exists()) {
            try {
                Files.move(joinedPlayersFile.toPath(), 
                    new File(backupFolder, "joined_players.txt").toPath(), 
                    StandardCopyOption.REPLACE_EXISTING);
                logger.info("[Migration] Moved joined_players.txt to backup folder");
            } catch (IOException e) {
                logger.warning("[Migration] Failed to move joined_players.txt: " + e.getMessage());
            }
        }
        
        logger.info("[Migration] Old files backed up to: " + backupFolder.getPath());
    }
}
