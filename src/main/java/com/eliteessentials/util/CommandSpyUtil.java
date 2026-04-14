package com.eliteessentials.util;

import com.eliteessentials.EliteEssentials;
import com.eliteessentials.services.SpyService;

import java.util.UUID;

/**
 * Utility for notifying command spies about command executions.
 * 
 * Since Hytale does not have a CommandEvent, command spy is triggered
 * by calling {@link #notify(UUID, String, String)} from individual commands
 * or from a chat event listener that intercepts command input.
 * 
 * Usage in commands:
 * <pre>
 * CommandSpyUtil.notify(player.getUuid(), player.getUsername(), ctx.getInputString());
 * </pre>
 */
public final class CommandSpyUtil {

    private CommandSpyUtil() {} // Utility class

    /**
     * Notify command spies about a command execution.
     * Safe to call from any thread. No-ops if spy service is unavailable.
     * 
     * @param executorId UUID of the player who executed the command
     * @param executorName Display name of the executor
     * @param rawCommand The full command string (with or without leading /)
     */
    public static void notify(UUID executorId, String executorName, String rawCommand) {
        SpyService spyService = EliteEssentials.getInstance().getSpyService();
        if (spyService == null) return;

        String command = rawCommand;
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        spyService.notifyCommandSpy(executorId, executorName, command);
    }
}
