package ru.warndev.sleepvote;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class VoteEngine {
    private final Map<UUID, Session> sessions = new HashMap<>();

    public boolean vote(VoteSnapshot snapshot, WorldRule rule, UUID player) {
        if (!rule.virtualVotes()) {
            throw new IllegalArgumentException("В этом мире учитывается только сон в кровати");
        }
        if (!rule.night(snapshot.fullTime())) {
            throw new IllegalArgumentException("Голосование доступно только ночью");
        }
        if (!snapshot.eligible().contains(player)) {
            throw new IllegalArgumentException("Вы не участвуете в голосовании этого мира");
        }
        Session session = session(snapshot);
        if (session.completed) {
            throw new IllegalArgumentException("Голосование этой ночи уже завершено");
        }
        return session.virtual.add(player);
    }

    public boolean revoke(UUID world, UUID player) {
        Session session = sessions.get(world);
        return session != null && session.virtual.remove(player);
    }

    public void removePlayer(UUID player) {
        sessions.values().forEach(session -> session.virtual.remove(player));
    }

    public VoteResult evaluate(VoteSnapshot snapshot, WorldRule rule, long monotonicMillis) {
        int eligible = snapshot.eligible().size();
        int required = rule.required(eligible);
        if (!rule.night(snapshot.fullTime())) {
            sessions.remove(snapshot.world());
            return new VoteResult(snapshot.world(), VoteResult.Phase.DAY, eligible, 0, required, 0, false);
        }
        Session session = session(snapshot);
        session.virtual.retainAll(snapshot.eligible());
        Set<UUID> voters = new HashSet<>(snapshot.sleeping());
        if (rule.virtualVotes()) {
            voters.addAll(session.virtual);
        }
        int votes = voters.size();
        if (session.completed) {
            return new VoteResult(snapshot.world(), VoteResult.Phase.COMPLETED, eligible, votes, required, 0, false);
        }
        if (eligible < rule.minimumPlayers()) {
            session.quorumSince = null;
            return new VoteResult(snapshot.world(), VoteResult.Phase.INSUFFICIENT_PLAYERS, eligible, votes, required, 0, false);
        }
        if (votes < required) {
            session.quorumSince = null;
            return new VoteResult(snapshot.world(), VoteResult.Phase.VOTING, eligible, votes, required, 0, false);
        }
        if (session.quorumSince == null || monotonicMillis < session.quorumSince) {
            session.quorumSince = monotonicMillis;
        }
        long duration = rule.quorumSeconds() * 1000L;
        long elapsed = monotonicMillis - session.quorumSince;
        if (elapsed >= duration) {
            session.completed = true;
            return new VoteResult(snapshot.world(), VoteResult.Phase.COMPLETED, eligible, votes, required, 0, true);
        }
        return new VoteResult(snapshot.world(), VoteResult.Phase.COUNTDOWN, eligible, votes, required, duration - elapsed, false);
    }

    public void forget(UUID world) {
        sessions.remove(world);
    }

    public void clear() {
        sessions.clear();
    }

    private Session session(VoteSnapshot snapshot) {
        Session current = sessions.get(snapshot.world());
        if (current == null || current.night != snapshot.nightId()) {
            current = new Session(snapshot.nightId());
            sessions.put(snapshot.world(), current);
        }
        return current;
    }

    private static final class Session {
        private final long night;
        private final Set<UUID> virtual = new HashSet<>();
        private Long quorumSince;
        private boolean completed;

        private Session(long night) {
            this.night = night;
        }
    }
}
