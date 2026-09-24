package ru.warndev.sleepvote;

import java.util.UUID;
import org.bukkit.GameRules;
import org.bukkit.World;

public final class PaperWorldAccess implements LeaseManager.WorldAccess {
    private final World world;

    public PaperWorldAccess(World world) {
        this.world = world;
    }

    @Override
    public UUID id() {
        return world.getUID();
    }

    @Override
    public int sleepPercentage() {
        Integer value = world.getGameRuleValue(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
        if (value == null) {
            throw new IllegalStateException("Sleep gamerule unavailable");
        }
        return value;
    }

    @Override
    public boolean sleepPercentage(int value) {
        return world.setGameRule(GameRules.PLAYERS_SLEEPING_PERCENTAGE, value);
    }
}
