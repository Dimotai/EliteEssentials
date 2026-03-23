package com.eliteessentials.storage;

import com.eliteessentials.model.Warp;

import java.util.*;

/**
 * Abstraction interface for server-wide data storage (warps, spawns).
 * Implemented by JSON file storage (WarpStorage) and SQL storage (SqlGlobalStorage).
 * 
 * Note: SpawnStorage's world-sync methods (syncSpawnToWorld, etc.) are NOT part of
 * this interface — those are Hytale runtime concerns, not storage concerns.
 */
public interface GlobalStorageProvider {

    // Warp operations
    Map<String, Warp> getAllWarps();
    Optional<Warp> getWarp(String name);
    void setWarp(Warp warp);
    boolean deleteWarp(String name);
    boolean hasWarp(String name);
    Set<String> getWarpNames();
    int getWarpCount();

    // Spawn data persistence
    void loadSpawns();
    void saveSpawns();
    Map<String, List<SpawnStorage.SpawnData>> getAllSpawns();
    void setAllSpawns(Map<String, List<SpawnStorage.SpawnData>> spawns);

    // First-join spawn
    SpawnStorage.SpawnData getFirstJoinSpawn();
    void saveFirstJoinSpawn(SpawnStorage.SpawnData spawn);
    void deleteFirstJoinSpawn();

    // Lifecycle
    void load();
    void save();
    void shutdown();
}
