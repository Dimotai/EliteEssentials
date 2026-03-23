package com.eliteessentials.services;

import com.eliteessentials.model.PlayerFile;
import com.eliteessentials.model.Warp;
import com.eliteessentials.storage.GlobalStorageProvider;
import com.eliteessentials.storage.PlayerStorageProvider;
import com.eliteessentials.storage.SpawnStorage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Migrates existing JSON file data (players, warps, spawns) into the configured SQL database.
 * Used by the /eemigration sql command when switching from JSON to SQL storage.
 */
public class SqlMigrationService {

    private static final Logger logger = Logger.getLogger("EliteEssentials");
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type WARP_MAP_TYPE = new TypeToken<Map<String, Warp>>() {}.getType();
    private static final Type SPAWN_LIST_MAP_TYPE = new TypeToken<Map<String, List<SpawnStorage.SpawnData>>>() {}.getType();

    private final PlayerStorageProvider sqlPlayerStorage;
    private final GlobalStorageProvider sqlGlobalStorage;
    private final File dataFolder;

    public SqlMigrationService(PlayerStorageProvider sqlPlayerStorage,
                               GlobalStorageProvider sqlGlobalStorage,
                               File dataFolder) {
        this.sqlPlayerStorage = sqlPlayerStorage;
        this.sqlGlobalStorage = sqlGlobalStorage;
        this.dataFolder = dataFolder;
    }

    /**
     * Check if the target SQL tables already contain data.
     * @return true if players or warps already exist in SQL
     */
    public boolean hasExistingData() {
        return sqlPlayerStorage.getPlayerCount() > 0 || sqlGlobalStorage.getWarpCount() > 0;
    }

    /**
     * Run the full JSON-to-SQL migration.
     *
     * @param progressCallback receives progress messages during migration
     * @return result summary with counts and errors
     */
    public MigrationResult migrate(Consumer<String> progressCallback) {
        long startTime = System.currentTimeMillis();

        int playersMigrated = 0;
        int playersFailed = 0;
        int warpsMigrated = 0;
        int spawnsMigrated = 0;
        int firstJoinSpawnMigrated = 0;
        List<String> errors = new ArrayList<>();

        // --- Migrate player files ---
        File playersFolder = new File(dataFolder, "players");
        if (playersFolder.exists() && playersFolder.isDirectory()) {
            File[] playerFiles = playersFolder.listFiles((dir, name) -> name.endsWith(".json"));
            if (playerFiles != null && playerFiles.length > 0) {
                progressCallback.accept("Migrating " + playerFiles.length + " player file(s)...");
                int count = 0;
                for (File pf : playerFiles) {
                    String uuidStr = pf.getName().replace(".json", "");
                    try {
                        PlayerFile data;
                        try (Reader reader = new InputStreamReader(new FileInputStream(pf), StandardCharsets.UTF_8)) {
                            data = gson.fromJson(reader, PlayerFile.class);
                        }
                        if (data == null) {
                            errors.add("Player file empty/null: " + uuidStr);
                            playersFailed++;
                            continue;
                        }
                        // Ensure UUID is set (some old files may not have it serialized)
                        if (data.getUuid() == null) {
                            try {
                                data.setUuid(UUID.fromString(uuidStr));
                            } catch (IllegalArgumentException e) {
                                errors.add("Invalid UUID filename: " + pf.getName());
                                playersFailed++;
                                continue;
                            }
                        }
                        sqlPlayerStorage.savePlayerDirect(data);
                        playersMigrated++;
                        count++;
                        if (count % 50 == 0) {
                            progressCallback.accept("  Players: " + count + "/" + playerFiles.length + " processed...");
                        }
                    } catch (Exception e) {
                        logger.severe("[SqlMigration] Failed to migrate player " + uuidStr + ": " + e.getMessage());
                        errors.add("Player " + uuidStr + ": " + e.getMessage());
                        playersFailed++;
                    }
                }
                progressCallback.accept("  Players: " + playersMigrated + " migrated, " + playersFailed + " failed.");
            } else {
                progressCallback.accept("No player files found in players/ folder.");
            }
        } else {
            progressCallback.accept("No players/ folder found, skipping player migration.");
        }

        // --- Migrate warps ---
        File warpsFile = new File(dataFolder, "warps.json");
        if (warpsFile.exists()) {
            try {
                Map<String, Warp> warps;
                try (Reader reader = new InputStreamReader(new FileInputStream(warpsFile), StandardCharsets.UTF_8)) {
                    warps = gson.fromJson(reader, WARP_MAP_TYPE);
                }
                if (warps != null && !warps.isEmpty()) {
                    progressCallback.accept("Migrating " + warps.size() + " warp(s)...");
                    for (Warp warp : warps.values()) {
                        try {
                            sqlGlobalStorage.setWarp(warp);
                            warpsMigrated++;
                        } catch (Exception e) {
                            String warpName = warp != null ? warp.getName() : "unknown";
                            logger.severe("[SqlMigration] Failed to migrate warp " + warpName + ": " + e.getMessage());
                            errors.add("Warp " + warpName + ": " + e.getMessage());
                        }
                    }
                    progressCallback.accept("  Warps: " + warpsMigrated + " migrated.");
                } else {
                    progressCallback.accept("warps.json is empty, skipping.");
                }
            } catch (Exception e) {
                logger.severe("[SqlMigration] Failed to read warps.json: " + e.getMessage());
                errors.add("warps.json: " + e.getMessage());
            }
        } else {
            progressCallback.accept("No warps.json found, skipping warp migration.");
        }

        // --- Migrate spawns ---
        File spawnFile = new File(dataFolder, "spawn.json");
        if (spawnFile.exists()) {
            try {
                Map<String, List<SpawnStorage.SpawnData>> spawns = readSpawnFile(spawnFile);
                if (spawns != null && !spawns.isEmpty()) {
                    int totalSpawns = spawns.values().stream().mapToInt(List::size).sum();
                    progressCallback.accept("Migrating " + totalSpawns + " spawn point(s)...");
                    sqlGlobalStorage.setAllSpawns(spawns);
                    sqlGlobalStorage.saveSpawns();
                    spawnsMigrated = totalSpawns;
                    progressCallback.accept("  Spawns: " + spawnsMigrated + " migrated.");
                } else {
                    progressCallback.accept("spawn.json is empty, skipping.");
                }
            } catch (Exception e) {
                logger.severe("[SqlMigration] Failed to read spawn.json: " + e.getMessage());
                errors.add("spawn.json: " + e.getMessage());
            }
        } else {
            progressCallback.accept("No spawn.json found, skipping spawn migration.");
        }

        // --- Migrate first-join spawn ---
        File firstJoinFile = new File(dataFolder, "firstjoinspawn.json");
        if (firstJoinFile.exists()) {
            try {
                SpawnStorage.SpawnData fjSpawn;
                try (Reader reader = new InputStreamReader(new FileInputStream(firstJoinFile), StandardCharsets.UTF_8)) {
                    fjSpawn = gson.fromJson(reader, SpawnStorage.SpawnData.class);
                }
                if (fjSpawn != null) {
                    sqlGlobalStorage.saveFirstJoinSpawn(fjSpawn);
                    firstJoinSpawnMigrated = 1;
                    progressCallback.accept("  First-join spawn migrated.");
                }
            } catch (Exception e) {
                logger.severe("[SqlMigration] Failed to read firstjoinspawn.json: " + e.getMessage());
                errors.add("firstjoinspawn.json: " + e.getMessage());
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        return new MigrationResult(
                errors.isEmpty(),
                playersMigrated,
                playersFailed,
                warpsMigrated,
                spawnsMigrated,
                firstJoinSpawnMigrated,
                elapsed,
                errors
        );
    }

    /**
     * Read spawn.json, handling both v1 (object) and v2 (array) formats.
     */
    private Map<String, List<SpawnStorage.SpawnData>> readSpawnFile(File file) throws IOException {
        try (Reader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            String content = readFully(reader);
            JsonElement root = JsonParser.parseString(content);
            if (!root.isJsonObject()) return null;

            JsonObject obj = root.getAsJsonObject();
            if (obj.size() == 0) return null;

            // Detect format: array values = v2, object values = v1
            boolean isV2 = false;
            for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                if (entry.getValue().isJsonArray()) {
                    isV2 = true;
                    break;
                }
            }

            if (isV2) {
                return gson.fromJson(content, SPAWN_LIST_MAP_TYPE);
            } else {
                // v1: { "worldName": { world, x, y, z, yaw, pitch } } -> convert to v2
                Map<String, List<SpawnStorage.SpawnData>> result = new HashMap<>();
                for (Map.Entry<String, JsonElement> entry : obj.entrySet()) {
                    if (!entry.getValue().isJsonObject()) continue;
                    SpawnStorage.SpawnData spawn = gson.fromJson(entry.getValue(), SpawnStorage.SpawnData.class);
                    if (spawn != null) {
                        spawn.name = "main";
                        spawn.primary = true;
                        if (spawn.world == null) spawn.world = entry.getKey();
                        result.put(entry.getKey(), new ArrayList<>(Collections.singletonList(spawn)));
                    }
                }
                return result;
            }
        }
    }

    private String readFully(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int read;
        while ((read = reader.read(buf)) != -1) {
            sb.append(buf, 0, read);
        }
        return sb.toString();
    }

    // ==================== Result ====================

    /**
     * Summary of a JSON-to-SQL migration run.
     */
    public static class MigrationResult {
        private final boolean success;
        private final int playersMigrated;
        private final int playersFailed;
        private final int warpsMigrated;
        private final int spawnsMigrated;
        private final int firstJoinSpawnMigrated;
        private final long elapsedMs;
        private final List<String> errors;

        public MigrationResult(boolean success, int playersMigrated, int playersFailed,
                               int warpsMigrated, int spawnsMigrated, int firstJoinSpawnMigrated,
                               long elapsedMs, List<String> errors) {
            this.success = success;
            this.playersMigrated = playersMigrated;
            this.playersFailed = playersFailed;
            this.warpsMigrated = warpsMigrated;
            this.spawnsMigrated = spawnsMigrated;
            this.firstJoinSpawnMigrated = firstJoinSpawnMigrated;
            this.elapsedMs = elapsedMs;
            this.errors = new ArrayList<>(errors);
        }

        public boolean isSuccess() { return success; }
        public int getPlayersMigrated() { return playersMigrated; }
        public int getPlayersFailed() { return playersFailed; }
        public int getWarpsMigrated() { return warpsMigrated; }
        public int getSpawnsMigrated() { return spawnsMigrated; }
        public int getFirstJoinSpawnMigrated() { return firstJoinSpawnMigrated; }
        public long getElapsedMs() { return elapsedMs; }
        public List<String> getErrors() { return errors; }

        public int getTotalMigrated() {
            return playersMigrated + warpsMigrated + spawnsMigrated + firstJoinSpawnMigrated;
        }

        public String getElapsedFormatted() {
            long seconds = elapsedMs / 1000;
            if (seconds < 1) return elapsedMs + "ms";
            return seconds + "." + (elapsedMs % 1000) / 100 + "s";
        }
    }
}
