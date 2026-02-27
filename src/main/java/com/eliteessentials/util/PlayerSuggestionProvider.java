package com.eliteessentials.util;

import com.hypixel.hytale.server.core.NameMatching;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionProvider;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import java.util.Collection;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Reusable suggestion provider that suggests online player names with fuzzy matching.
 * Also provides a static findPlayer() helper that uses NameMatching.DEFAULT
 * (STARTS_WITH_IGNORE_CASE) so partial names like "eli" resolve to "EliteAdna".
 * 
 * Usage:
 *   withRequiredArg("player", "desc", ArgTypes.STRING)
 *       .suggest(PlayerSuggestionProvider.INSTANCE);
 * 
 *   PlayerRef target = PlayerSuggestionProvider.findPlayer(name);
 */
public final class PlayerSuggestionProvider implements SuggestionProvider {

    public static final PlayerSuggestionProvider INSTANCE = new PlayerSuggestionProvider();

    private PlayerSuggestionProvider() {}

    @Override
    public void suggest(@Nonnull CommandSender sender, @Nonnull String input, int numParams,
                        @Nonnull SuggestionResult result) {
        result.fuzzySuggest(input, Universe.get().getPlayers(), PlayerRef::getUsername);
    }

    /**
     * Find an online player by partial or full name using NameMatching.DEFAULT
     * (STARTS_WITH_IGNORE_CASE). Typing "eli" will match "EliteAdna".
     */
    @Nullable
    public static PlayerRef findPlayer(@Nonnull String name) {
        Collection<PlayerRef> players = Universe.get().getPlayers();
        return NameMatching.DEFAULT.find(players, name, PlayerRef::getUsername);
    }
}
