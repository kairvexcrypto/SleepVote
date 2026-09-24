package ru.warndev.sleepvote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

public final class SleepController implements Listener {
    private final SleepVotePlugin plugin;
    private final LeaseManager leases;
    private final VoteEngine engine = new VoteEngine();
    private final VoteBars bars;
    private final LongSupplier clock;
    private final Map<UUID, VoteResult> results = new HashMap<>();
    private final Map<UUID, Long> totals = new HashMap<>();
    private final Set<UUID> failed = new HashSet<>();
    private SleepSettings settings;

    public SleepController(SleepVotePlugin plugin, LeaseManager leases, SleepSettings settings, LongSupplier clock) {
        this.plugin = plugin;
        this.leases = leases;
        this.settings = settings;
        this.clock = clock;
        this.bars = new VoteBars(plugin.getServer());
    }

    public List<String> reload(SleepSettings replacement) {
        settings = replacement;
        engine.clear();
        bars.clear();
        results.clear();
        failed.clear();
        List<String> problems = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            if (!reconcile(world)) {
                problems.add(world.getName());
            }
        }
        return List.copyOf(problems);
    }

    public SleepSettings settings() {
        return settings;
    }

    public boolean managed(World world) {
        return settings.worlds().containsKey(world.getName()) && world.getEnvironment() == World.Environment.NORMAL
                && !failed.contains(world.getUID()) && leases.owns(new PaperWorldAccess(world));
    }

    private boolean reconcile(World world) {
        try {
            if (settings.worlds().containsKey(world.getName()) && world.getEnvironment() == World.Environment.NORMAL) {
                leases.acquire(new PaperWorldAccess(world));
            } else {
                leases.release(new PaperWorldAccess(world));
            }
            failed.remove(world.getUID());
            return true;
        } catch (IOException | RuntimeException error) {
            failed.add(world.getUID());
            plugin.getLogger().warning("Sleep gamerule unavailable for world " + world.getUID() + "; voting disabled there");
            return false;
        }
    }

    public VoteSnapshot snapshot(World world) {
        Set<UUID> eligible = new HashSet<>();
        Set<UUID> sleeping = new HashSet<>();
        for (Player player : world.getPlayers()) {
            if ((player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE)
                    && !player.isDead() && !player.isSleepingIgnored() && !player.hasPermission("sleepvote.exclude")) {
                eligible.add(player.getUniqueId());
                if (player.isSleeping()) {
                    sleeping.add(player.getUniqueId());
                }
            }
        }
        return new VoteSnapshot(world.getUID(), world.getFullTime(), eligible, sleeping);
    }

    public boolean vote(Player player) {
        World world = player.getWorld();
        if (!managed(world)) {
            throw new IllegalArgumentException(settings.message("disabled"));
        }
        WorldRule rule = settings.worlds().get(world.getName());
        VoteSnapshot snapshot = snapshot(world);
        if (!rule.night(snapshot.fullTime())) {
            throw new IllegalArgumentException(settings.message("day"));
        }
        return engine.vote(snapshot, rule, player.getUniqueId());
    }

    public boolean revoke(Player player) {
        return engine.revoke(player.getWorld().getUID(), player.getUniqueId());
    }

    public VoteResult result(World world) {
        return results.get(world.getUID());
    }

    public long skipped(World world) {
        return totals.getOrDefault(world.getUID(), 0L);
    }

    public Map<UUID, Integer> leaseSnapshot() {
        return leases.pending();
    }

    public void tick() {
        long now = clock.getAsLong();
        for (World world : plugin.getServer().getWorlds()) {
            if (!managed(world)) {
                bars.hide(world.getUID());
                results.remove(world.getUID());
                engine.forget(world.getUID());
                continue;
            }
            WorldRule rule = settings.worlds().get(world.getName());
            VoteSnapshot snapshot = snapshot(world);
            VoteResult result = engine.evaluate(snapshot, rule, now);
            results.put(world.getUID(), result);
            if (result.skip()) {
                skip(world, rule);
                bars.hide(world.getUID());
            } else if (rule.showBossbar() && result.phase() != VoteResult.Phase.DAY
                    && result.phase() != VoteResult.Phase.COMPLETED && !snapshot.eligible().isEmpty()) {
                String message = settings.progress(result);
                if (result.phase() == VoteResult.Phase.COUNTDOWN) {
                    message += " · " + (result.remainingMillis() + 999) / 1000 + " с";
                }
                bars.show(world, snapshot, result, message);
            } else {
                bars.hide(world.getUID());
            }
        }
    }

    private void skip(World world, WorldRule rule) {
        world.setTime(rule.morningTime());
        if (world.getTime() != rule.morningTime()) {
            failed.add(world.getUID());
            plugin.getLogger().warning("Time change was rejected in " + world.getUID() + "; voting paused until reload");
            try {
                leases.release(new PaperWorldAccess(world));
            } catch (IOException error) {
                plugin.getLogger().warning("Deferred sleep gamerule restoration for " + world.getUID());
            }
            return;
        }
        if (rule.clearWeather()) {
            world.setStorm(false);
            world.setThundering(false);
        }
        totals.merge(world.getUID(), 1L, Long::sum);
        Component message = Component.text(settings.message("skipped"));
        for (Player player : world.getPlayers()) {
            if (player.isSleeping()) {
                player.wakeup(false);
            }
            player.sendMessage(message);
        }
    }

    @EventHandler
    public void onLoad(WorldLoadEvent event) {
        reconcile(event.getWorld());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(WorldUnloadEvent event) {
        World world = event.getWorld();
        bars.hide(world.getUID());
        engine.forget(world.getUID());
        results.remove(world.getUID());
        totals.remove(world.getUID());
        failed.remove(world.getUID());
        try {
            leases.release(new PaperWorldAccess(world));
        } catch (IOException | RuntimeException error) {
            plugin.getLogger().warning("Deferred gamerule restoration for " + world.getUID());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        remove(event.getPlayer());
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        remove(event.getPlayer());
    }

    private void remove(Player player) {
        engine.removePlayer(player.getUniqueId());
        bars.removePlayer(player.getUniqueId());
    }

    public void stop() {
        bars.clear();
        engine.clear();
        results.clear();
        for (World world : plugin.getServer().getWorlds()) {
            try {
                leases.release(new PaperWorldAccess(world));
            } catch (IOException | RuntimeException error) {
                plugin.getLogger().severe("Could not restore sleeping gamerule for " + world.getUID()
                        + "; keep leases.dat for recovery");
            }
        }
    }
}
