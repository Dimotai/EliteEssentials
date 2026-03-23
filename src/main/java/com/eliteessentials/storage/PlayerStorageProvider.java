package com.eliteessentials.storage;

import com.eliteessentials.model.PlayerFile;

import java.io.File;
import java.util.*;

/**
 * Abstraction interface for per-player data storage.
 * Implemented by JSON file storage (PlayerFileStorage) and SQL storage (SqlPlayerStorage).
 */
public interface PlayerStorageProvider {

    // Core player access
    PlayerFile getPlayer(UUID uuid, String name);
    PlayerFile getPlayer(UUID uuid);
    PlayerFile getPlayerByName(String name);
    Optional<UUID> getUuidByName(String name);
    boolean hasPlayer(UUID uuid);

    // Persistence
    void savePlayer(UUID uuid);
    void saveAndMarkDirty(UUID uuid);
    void markDirty(UUID uuid);
    void saveAll();
    void saveAllDirty();

    // Cache lifecycle
    void unloadPlayer(UUID uuid);
    Collection<PlayerFile> getCachedPlayers();

    // Queries
    Collection<UUID> getAllPlayerUuids();
    List<PlayerFile> getAllPlayersSorted(Comparator<PlayerFile> comparator);
    List<PlayerFile> getPlayersByWallet();
    List<PlayerFile> getPlayersByPlayTime();
    List<PlayerFile> getPlayersByLastSeen();
    int getPlayerCount();

    // Index
    void reload();

    // Migration support
    void savePlayerDirect(PlayerFile data);
    File getPlayersFolder();
}
