package ru.warndev.sleepvote;

import java.io.IOException;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class SleepVotePlugin extends JavaPlugin {
    private SleepController controller;
    private BukkitTask ticker;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            SleepSettings settings = SleepSettings.load(getDataFolder().toPath().resolve("config.yml"));
            LeaseManager leases = new LeaseManager(new LeaseStore(getDataFolder().toPath().resolve("leases.dat")));
            controller = new SleepController(this, leases, settings, () -> System.nanoTime() / 1000000);
            var failures = controller.reload(settings);
            if (!failures.isEmpty()) {
                getLogger().warning("Some configured worlds cannot be managed; inspect /svote worlds");
            }
            SleepCommand commands = new SleepCommand(this, controller);
            var command = Objects.requireNonNull(getCommand("sleepvote"));
            command.setExecutor(commands);
            command.setTabCompleter(commands);
            getServer().getPluginManager().registerEvents(controller, this);
            getServer().getPluginManager().registerEvents(commands, this);
            ticker = getServer().getScheduler().runTaskTimer(this, controller::tick, 20, 20);
        } catch (IOException | RuntimeException error) {
            getLogger().severe("SleepVote cannot start; inspect config.yml and leases.dat. No automatic reset was performed");
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (ticker != null) {
            ticker.cancel();
        }
        if (controller != null) {
            controller.stop();
        }
    }
}
