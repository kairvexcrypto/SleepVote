package ru.warndev.sleepvote;

import java.util.Set;
import java.util.UUID;

public record VoteSnapshot(UUID world, long fullTime, Set<UUID> eligible, Set<UUID> sleeping) {
    public VoteSnapshot {
        eligible = Set.copyOf(eligible);
        sleeping = Set.copyOf(sleeping);
        if (!eligible.containsAll(sleeping) || eligible.size() > 10000) {
            throw new IllegalArgumentException("Неверный снимок участников");
        }
    }

    public long nightId() {
        return Math.floorDiv(fullTime, 24000);
    }
}
