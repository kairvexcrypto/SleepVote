package ru.warndev.sleepvote;

import java.util.UUID;

public record VoteResult(UUID world, Phase phase, int eligible, int votes, int required, long remainingMillis,
                         boolean skip) {
    public enum Phase {
        DAY,
        INSUFFICIENT_PLAYERS,
        VOTING,
        COUNTDOWN,
        COMPLETED
    }
}
