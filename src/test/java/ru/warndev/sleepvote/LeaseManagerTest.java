package ru.warndev.sleepvote;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeaseManagerTest {
    @TempDir Path root;

    @Test
    void recordsBeforeChangingAndRestoresOriginal() throws Exception {
        LeaseStore store = new LeaseStore(root.resolve("leases.dat"));
        LeaseManager manager = new LeaseManager(store);
        FakeWorld world = new FakeWorld(37);
        manager.acquire(world);
        assertEquals(101, world.value);
        assertEquals(37, store.load().get(world.id));
        manager.release(world);
        assertEquals(37, world.value);
        assertTrue(store.load().isEmpty());
    }

    @Test
    void restartRetainsOriginalInsteadOfCapturing101() throws Exception {
        LeaseStore store = new LeaseStore(root.resolve("leases.dat"));
        FakeWorld world = new FakeWorld(45);
        new LeaseManager(store).acquire(world);
        LeaseManager restarted = new LeaseManager(store);
        restarted.acquire(world);
        assertEquals(45, restarted.pending().get(world.id));
        restarted.release(world);
        assertEquals(45, world.value);
    }

    @Test
    void doesNotOverrideAdministratorsChangedGameruleOnRelease() throws Exception {
        LeaseManager manager = new LeaseManager(new LeaseStore(root.resolve("leases.dat")));
        FakeWorld world = new FakeWorld(100);
        manager.acquire(world);
        world.value = 70;
        assertFalse(manager.owns(world));
        manager.release(world);
        assertEquals(70, world.value);
    }

    @Test
    void reacquireAfterAdministrativeChangeUsesNewBaseline() throws Exception {
        LeaseManager manager = new LeaseManager(new LeaseStore(root.resolve("leases.dat")));
        FakeWorld world = new FakeWorld(100);
        manager.acquire(world);
        world.value = 70;
        manager.acquire(world);
        manager.release(world);
        assertEquals(70, world.value);
    }

    @Test
    void failedDiskWriteCannotChangeGamerule() throws Exception {
        Path file = root.resolve("leases.dat");
        LeaseManager manager = new LeaseManager(new LeaseStore(file));
        Files.createDirectory(file);
        FakeWorld world = new FakeWorld(100);
        assertThrows(IOException.class, () -> manager.acquire(world));
        assertEquals(100, world.value);
        assertTrue(manager.pending().isEmpty());
    }

    @Test
    void failedServerChangeRetainsRecoveryRecord() throws Exception {
        LeaseStore store = new LeaseStore(root.resolve("leases.dat"));
        LeaseManager manager = new LeaseManager(store);
        FakeWorld world = new FakeWorld(100);
        world.reject = true;
        assertThrows(IOException.class, () -> manager.acquire(world));
        assertEquals(100, world.value);
        assertEquals(100, store.load().get(world.id));
        assertFalse(manager.owns(world));
    }

    @Test
    void failedRestorationRetainsRecord() throws Exception {
        LeaseStore store = new LeaseStore(root.resolve("leases.dat"));
        LeaseManager manager = new LeaseManager(store);
        FakeWorld world = new FakeWorld(100);
        manager.acquire(world);
        world.reject = true;
        assertThrows(IOException.class, () -> manager.release(world));
        assertEquals(100, store.load().get(world.id));
    }

    @Test
    void corruptedChecksumAndSymlinkAreRejected() throws Exception {
        Path file = root.resolve("leases.dat");
        LeaseStore store = new LeaseStore(file);
        store.save(Map.of(new UUID(1, 1), 100));
        byte[] bytes = Files.readAllBytes(file);
        bytes[15] ^= 1;
        Files.write(file, bytes);
        assertThrows(IOException.class, store::load);
        Path link = root.resolve("link.dat");
        Files.createSymbolicLink(link, file);
        assertThrows(IOException.class, () -> new LeaseStore(link).load());
    }

    @Test
    void roundTripsEmptyAndManyLeases() throws Exception {
        LeaseStore store = new LeaseStore(root.resolve("leases.dat"));
        store.save(Map.of());
        assertTrue(store.load().isEmpty());
        Map<UUID, Integer> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 128; i++) {
            values.put(new UUID(0, i), i);
        }
        store.save(values);
        assertEquals(values, store.load());
        values.put(new UUID(0, 129), 100);
        assertThrows(IOException.class, () -> store.save(values));
    }

    private static final class FakeWorld implements LeaseManager.WorldAccess {
        private final UUID id = UUID.randomUUID();
        private int value;
        private boolean reject;

        private FakeWorld(int value) {
            this.value = value;
        }

        public UUID id() {
            return id;
        }

        public int sleepPercentage() {
            return value;
        }

        public boolean sleepPercentage(int value) {
            if (reject) {
                return false;
            }
            this.value = value;
            return true;
        }
    }
}
