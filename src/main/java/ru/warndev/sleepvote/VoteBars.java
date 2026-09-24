package ru.warndev.sleepvote;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class VoteBars {
    private final Server server;
    private final Map<UUID, Bar> bars = new HashMap<>();

    public VoteBars(Server server) {
        this.server = server;
    }

    public void show(World world, VoteSnapshot snapshot, VoteResult result, String title) {
        Bar bar = bars.computeIfAbsent(world.getUID(), key -> new Bar(BossBar.bossBar(Component.empty(),
                0, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS), new HashSet<>()));
        float progress = Math.min(1f, result.votes() / (float) Math.max(1, result.required()));
        bar.bossbar().name(Component.text(title)).progress(progress)
                .color(result.phase() == VoteResult.Phase.COUNTDOWN ? BossBar.Color.GREEN : BossBar.Color.BLUE);
        Set<UUID> viewers = snapshot.eligible();
        for (UUID old : Set.copyOf(bar.viewers())) {
            if (!viewers.contains(old)) {
                Player player = server.getPlayer(old);
                if (player != null) {
                    player.hideBossBar(bar.bossbar());
                }
                bar.viewers().remove(old);
            }
        }
        for (Player player : world.getPlayers()) {
            if (viewers.contains(player.getUniqueId()) && bar.viewers().add(player.getUniqueId())) {
                player.showBossBar(bar.bossbar());
            }
        }
    }

    public void hide(UUID world) {
        Bar bar = bars.remove(world);
        if (bar == null) {
            return;
        }
        for (UUID viewer : bar.viewers()) {
            Player player = server.getPlayer(viewer);
            if (player != null) {
                player.hideBossBar(bar.bossbar());
            }
        }
    }

    public void removePlayer(UUID playerId) {
        Player player = server.getPlayer(playerId);
        for (Bar bar : bars.values()) {
            if (bar.viewers().remove(playerId) && player != null) {
                player.hideBossBar(bar.bossbar());
            }
        }
    }

    public void clear() {
        for (UUID world : Set.copyOf(bars.keySet())) {
            hide(world);
        }
    }

    private record Bar(BossBar bossbar, Set<UUID> viewers) {
    }
}
