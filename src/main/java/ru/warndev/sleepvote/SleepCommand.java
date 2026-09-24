package ru.warndev.sleepvote;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class SleepCommand implements TabExecutor, Listener {
    private final SleepVotePlugin plugin;
    private final SleepController controller;
    private final Map<UUID, Long> lastVote = new HashMap<>();

    public SleepCommand(SleepVotePlugin plugin, SleepController controller) {
        this.plugin = plugin;
        this.controller = controller;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String permission = switch (action) {
            case "reload", "worlds", "leases" -> "sleepvote.admin";
            case "vote", "revoke" -> "sleepvote.vote";
            default -> "sleepvote.status";
        };
        if (!sender.hasPermission(permission)) {
            say(sender, "Нет доступа: " + permission, true);
            return true;
        }
        try {
            switch (action) {
                case "help" -> {
                    say(sender, "/svote vote | revoke | status [мир]", false);
                    if (sender.hasPermission("sleepvote.admin")) {
                        say(sender, "/svote reload | worlds | leases", false);
                    }
                }
                case "vote", "revoke" -> {
                    if (args.length != 1 || !(sender instanceof Player player)) {
                        throw new IllegalArgumentException("Команда доступна игроку без дополнительных аргументов");
                    }
                    long now = System.nanoTime() / 1000000;
                    Long previous = lastVote.get(player.getUniqueId());
                    if (previous != null && now - previous < 1000) {
                        throw new IllegalArgumentException("Подождите секунду перед следующим изменением голоса");
                    }
                    lastVote.put(player.getUniqueId(), now);
                    boolean changed = action.equals("vote") ? controller.vote(player) : controller.revoke(player);
                    say(sender, changed ? (action.equals("vote") ? "Голос принят" : "Голос отозван")
                            : "Голос не изменился", false);
                }
                case "status" -> status(sender, args);
                case "worlds" -> worlds(sender);
                case "leases" -> leases(sender, args);
                case "reload" -> reload(sender, args);
                default -> throw new IllegalArgumentException("Неизвестная команда. /svote help");
            }
        } catch (IllegalArgumentException error) {
            say(sender, error.getMessage(), true);
        } catch (IOException error) {
            say(sender, "Конфигурация не загружена; прежние правила сохранены", true);
        }
        return true;
    }

    private void status(CommandSender sender, String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("/svote status [мир]");
        }
        World world;
        if (args.length == 2) {
            if (!sender.hasPermission("sleepvote.admin")) {
                throw new IllegalArgumentException("Просмотр другого мира требует sleepvote.admin");
            }
            world = plugin.getServer().getWorld(args[1]);
        } else {
            world = sender instanceof Player player ? player.getWorld() : null;
        }
        if (world == null) {
            throw new IllegalArgumentException("Укажите загруженный мир: /svote status <мир>");
        }
        if (!controller.managed(world)) {
            say(sender, controller.settings().message("disabled"), true);
            return;
        }
        VoteResult result = controller.result(world);
        if (result == null) {
            say(sender, "Ожидается ближайший цикл проверки", false);
            return;
        }
        say(sender, world.getName() + ": " + result.phase() + " · " + controller.settings().progress(result), false);
        say(sender, "Пропущено ночей после запуска: " + controller.skipped(world), false);
        if (result.phase() == VoteResult.Phase.COUNTDOWN) {
            say(sender, "До пропуска: " + (result.remainingMillis() + 999) / 1000 + " с", false);
        }
    }

    private void worlds(CommandSender sender) {
        say(sender, "Настроено миров: " + controller.settings().worlds().size(), false);
        for (String name : controller.settings().worlds().keySet().stream().sorted().toList()) {
            World world = plugin.getServer().getWorld(name);
            String status = world == null ? "не загружен" : controller.managed(world) ? "управляется" : "не управляется";
            say(sender, name + " — " + status, false);
        }
    }

    private void leases(CommandSender sender, String[] args) {
        if (args.length > 2) {
            throw new IllegalArgumentException("/svote leases [страница]");
        }
        List<Map.Entry<UUID, Integer>> entries = controller.leaseSnapshot().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList();
        int pages = Math.max(1, (entries.size() + 7) / 8);
        int page;
        try {
            page = args.length == 2 ? Integer.parseInt(args[1]) : 1;
        } catch (NumberFormatException error) {
            throw new IllegalArgumentException("Страница должна быть целым числом");
        }
        if (page < 1 || page > pages) {
            throw new IllegalArgumentException("Допустимые страницы: 1.." + pages);
        }
        say(sender, "Восстановление gamerule " + page + "/" + pages + "; записей: " + entries.size(), false);
        int start = (page - 1) * 8;
        for (var entry : entries.subList(start, Math.min(start + 8, entries.size()))) {
            World world = plugin.getServer().getWorld(entry.getKey());
            String name = world == null ? "не загружен" : world.getName();
            String current = world == null ? "—" : Integer.toString(new PaperWorldAccess(world).sleepPercentage());
            say(sender, entry.getKey() + " · " + name + " · исходное=" + entry.getValue() + " · сейчас=" + current, false);
        }
    }

    private void reload(CommandSender sender, String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("/svote reload");
        }
        SleepSettings replacement = SleepSettings.load(plugin.getDataFolder().toPath().resolve("config.yml"));
        List<String> failures = controller.reload(replacement);
        if (failures.isEmpty()) {
            say(sender, "Правила загружены; текущие голоса сброшены", false);
        } else {
            say(sender, "Правила прочитаны, но есть ошибки gamerule в мирах: " + String.join(", ", failures), true);
        }
    }

    private static void say(CommandSender sender, String message, boolean error) {
        sender.sendMessage(Component.text("[SleepVote] ", NamedTextColor.AQUA)
                .append(Component.text(message, error ? NamedTextColor.RED : NamedTextColor.GRAY)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("help", "vote", "revoke", "status", "worlds", "leases", "reload").stream()
                    .filter(action -> switch (action) {
                        case "reload", "worlds", "leases" -> sender.hasPermission("sleepvote.admin");
                        case "vote", "revoke" -> sender.hasPermission("sleepvote.vote");
                        default -> sender.hasPermission("sleepvote.status");
                    }).filter(action -> action.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("status") && sender.hasPermission("sleepvote.admin")) {
            return plugin.getServer().getWorlds().stream().map(World::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastVote.remove(event.getPlayer().getUniqueId());
    }
}
