package ru.warndev.sleepvote;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class VoteEngineTest {
    private final UUID world = new UUID(1, 1);
    private final UUID first = new UUID(2, 2);
    private final UUID second = new UUID(3, 3);
    private final WorldRule rule = new WorldRule("world", 50, 2, 1, 5, true, true, true, 12542, 23459, 0);

    private VoteSnapshot snapshot(long time, Set<UUID> eligible, Set<UUID> sleeping) {
        return new VoteSnapshot(world, time, eligible, sleeping);
    }

    @Test
    void waitsForContinuousQuorumThenSkipsExactlyOnce() {
        VoteEngine engine = new VoteEngine();
        var snapshot = snapshot(14000, Set.of(first, second), Set.of(first));
        assertFalse(engine.evaluate(snapshot, rule, 0).skip());
        assertEquals(1, engine.evaluate(snapshot, rule, 4999).remainingMillis());
        assertTrue(engine.evaluate(snapshot, rule, 5000).skip());
        assertFalse(engine.evaluate(snapshot, rule, 6000).skip());
    }

    @Test
    void lossOfQuorumResetsCountdown() {
        VoteEngine engine = new VoteEngine();
        var sleeping = snapshot(14000, Set.of(first, second), Set.of(first));
        var awake = snapshot(14000, Set.of(first, second), Set.of());
        engine.evaluate(sleeping, rule, 0);
        assertEquals(VoteResult.Phase.VOTING, engine.evaluate(awake, rule, 4000).phase());
        assertEquals(5000, engine.evaluate(sleeping, rule, 5000).remainingMillis());
        assertFalse(engine.evaluate(sleeping, rule, 9999).skip());
        assertTrue(engine.evaluate(sleeping, rule, 10000).skip());
    }

    @Test
    void minimumPlayersOverridesQuorum() {
        VoteEngine engine = new VoteEngine();
        var snapshot = snapshot(14000, Set.of(first), Set.of(first));
        assertEquals(VoteResult.Phase.INSUFFICIENT_PLAYERS, engine.evaluate(snapshot, rule, 0).phase());
        assertFalse(engine.evaluate(snapshot, rule, 100000).skip());
    }

    @Test
    void combinesBedAndVirtualVotesWithoutDoubleCounting() {
        VoteEngine engine = new VoteEngine();
        var snapshot = snapshot(14000, Set.of(first, second), Set.of(first));
        assertTrue(engine.vote(snapshot, rule, first));
        assertFalse(engine.vote(snapshot, rule, first));
        assertEquals(1, engine.evaluate(snapshot, rule, 0).votes());
        assertTrue(engine.vote(snapshot, rule, second));
        assertEquals(2, engine.evaluate(snapshot, rule, 100).votes());
    }

    @Test
    void rejectsIneligibleAndDayVotes() {
        VoteEngine engine = new VoteEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.vote(snapshot(1000, Set.of(first), Set.of()), rule, first));
        assertThrows(IllegalArgumentException.class, () -> engine.vote(snapshot(14000, Set.of(first), Set.of()), rule, second));
    }

    @Test
    void departingPlayerLosesVote() {
        VoteEngine engine = new VoteEngine();
        var snapshot = snapshot(14000, Set.of(first, second), Set.of());
        engine.vote(snapshot, rule, first);
        engine.removePlayer(first);
        assertEquals(0, engine.evaluate(snapshot, rule, 0).votes());
    }

    @Test
    void spectatorOrExcludedPlayerCannotRetainVote() {
        VoteEngine engine = new VoteEngine();
        var snapshot = snapshot(14000, Set.of(first, second), Set.of());
        engine.vote(snapshot, rule, first);
        assertEquals(0, engine.evaluate(snapshot(14000, Set.of(second), Set.of()), rule, 0).votes());
        assertEquals(0, engine.evaluate(snapshot, rule, 1000).votes());
    }

    @Test
    void aNewDayOrNightClearsVirtualVotes() {
        VoteEngine engine = new VoteEngine();
        var night = snapshot(14000, Set.of(first, second), Set.of());
        engine.vote(night, rule, first);
        assertEquals(VoteResult.Phase.DAY, engine.evaluate(snapshot(24000, Set.of(first, second), Set.of()), rule, 1).phase());
        assertEquals(0, engine.evaluate(snapshot(38000, Set.of(first, second), Set.of()), rule, 2).votes());
    }

    @Test
    void nightIdentityChangesEvenIfDayWasNotObserved() {
        VoteEngine engine = new VoteEngine();
        engine.vote(snapshot(14000, Set.of(first, second), Set.of()), rule, first);
        assertEquals(0, engine.evaluate(snapshot(38000, Set.of(first, second), Set.of()), rule, 0).votes());
    }

    @Test
    void worldsHaveIndependentVotes() {
        VoteEngine engine = new VoteEngine();
        engine.vote(snapshot(14000, Set.of(first, second), Set.of()), rule, first);
        var other = new VoteSnapshot(new UUID(4, 4), 14000, Set.of(first, second), Set.of());
        assertEquals(0, engine.evaluate(other, rule, 0).votes());
    }

    @Test
    void revokeRemovesOnlyVirtualVote() {
        VoteEngine engine = new VoteEngine();
        var night = snapshot(14000, Set.of(first, second), Set.of(first));
        engine.vote(night, rule, first);
        assertTrue(engine.revoke(world, first));
        assertFalse(engine.revoke(world, first));
        assertEquals(1, engine.evaluate(night, rule, 0).votes());
    }

    @Test
    void disabledVirtualVotesCannotBeSubmitted() {
        WorldRule bedsOnly = new WorldRule("world", 50, 1, 1, 5, false, true, true, 12542, 23459, 0);
        VoteEngine engine = new VoteEngine();
        assertThrows(IllegalArgumentException.class, () -> engine.vote(snapshot(14000, Set.of(first), Set.of()), bedsOnly, first));
    }

    @Test
    void resetAndBackwardMonotonicClockDoNotSkipEarly() {
        VoteEngine engine = new VoteEngine();
        var night = snapshot(14000, Set.of(first, second), Set.of(first));
        engine.evaluate(night, rule, 10000);
        assertEquals(5000, engine.evaluate(night, rule, 9000).remainingMillis());
        engine.clear();
        assertFalse(engine.evaluate(night, rule, 20000).skip());
    }
}
