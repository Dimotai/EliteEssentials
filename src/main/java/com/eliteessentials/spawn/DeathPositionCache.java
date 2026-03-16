package com.eliteessentials.spawn;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache of death positions and chosen spawns.
 * - Death position: captured when DeathComponent is added, consumed by NearestSpawnProvider
 * - Chosen spawn: set by provider when it selects a spawn, consumed by RespawnListener for messages/greetings
 */
public final class DeathPositionCache {

    private final Map<UUID, double[]> positions = new ConcurrentHashMap<>();
    private final Map<UUID, String> chosenSpawns = new ConcurrentHashMap<>();

    public void put(UUID playerId, double x, double z) {
        positions.put(playerId, new double[]{x, z});
    }

    /**
     * Peek death position without removing. Used by NearestSpawnProvider so repeated
     * getSpawnPoint calls (compass, etc.) don't consume it before the respawn call.
     */
    public double[] peek(UUID playerId) {
        return positions.get(playerId);
    }

    /**
     * Get and remove the death position for a player.
     */
    public double[] poll(UUID playerId) {
        return positions.remove(playerId);
    }

    /** Remove death position (cleanup when provider handled respawn). */
    public void remove(UUID playerId) {
        positions.remove(playerId);
    }

    public void putChosenSpawn(UUID playerId, String spawnName) {
        if (spawnName != null) {
            chosenSpawns.put(playerId, spawnName);
        }
    }

    /**
     * Get and remove the spawn name chosen by NearestSpawnProvider for messaging.
     */
    public String pollChosenSpawn(UUID playerId) {
        return chosenSpawns.remove(playerId);
    }
}
