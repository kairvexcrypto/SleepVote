package ru.warndev.sleepvote;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class LeaseManager {
    public static final int OWNED_VALUE = 101;
    private final LeaseStore store;
    private Map<UUID, Integer> leases;

    public LeaseManager(LeaseStore store) throws IOException {
        this.store = store;
        this.leases = new LinkedHashMap<>(store.load());
    }

    public void acquire(WorldAccess world) throws IOException {
        UUID id = world.id();
        int current = world.sleepPercentage();
        Integer recorded = leases.get(id);
        if (recorded != null && current == OWNED_VALUE) {
            return;
        }
        Map<UUID, Integer> replacement = new LinkedHashMap<>(leases);
        replacement.put(id, current);
        store.save(replacement);
        leases = replacement;
        if (!world.sleepPercentage(OWNED_VALUE)) {
            throw new IOException("Server rejected sleep gamerule");
        }
    }

    public void release(WorldAccess world) throws IOException {
        Integer original = leases.get(world.id());
        if (original == null) {
            return;
        }
        if (world.sleepPercentage() == OWNED_VALUE && !world.sleepPercentage(original)) {
            throw new IOException("Server rejected gamerule restoration");
        }
        Map<UUID, Integer> replacement = new LinkedHashMap<>(leases);
        replacement.remove(world.id());
        store.save(replacement);
        leases = replacement;
    }

    public boolean owns(WorldAccess world) {
        return leases.containsKey(world.id()) && world.sleepPercentage() == OWNED_VALUE;
    }

    public Map<UUID, Integer> pending() {
        return Map.copyOf(leases);
    }

    public interface WorldAccess {
        UUID id();
        int sleepPercentage();
        boolean sleepPercentage(int value);
    }
}
