package ru.warndev.sleepvote;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

public record SleepSettings(Map<String, WorldRule> worlds, Map<String, String> messages) {
    private static final Set<String> WORLD_KEYS = Set.of("percentage", "minimum-players", "minimum-votes", "quorum-seconds",
            "virtual-votes", "clear-weather", "show-bossbar", "night-start", "night-end", "morning-time");
    private static final Set<String> MESSAGE_KEYS = Set.of("progress", "skipped", "disabled", "day");

    public SleepSettings {
        worlds = Map.copyOf(worlds);
        messages = Map.copyOf(messages);
    }

    public static SleepSettings load(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 65536) {
            throw new IOException("Invalid config path or size");
        }
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(65537);
        }
        if (bytes.length > 65536) {
            throw new IOException("Configuration too large");
        }
        try {
            String content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            var yaml = new YamlConfiguration();
            yaml.loadFromString(content);
            if (!yaml.getKeys(false).equals(Set.of("worlds", "messages"))) {
                throw new IllegalArgumentException("Нужны разделы worlds и messages без посторонних параметров");
            }
            ConfigurationSection worlds = section(yaml, "worlds");
            if (worlds.getKeys(false).size() > 64) {
                throw new IllegalArgumentException("Допускается до 64 настроенных миров");
            }
            Map<String, WorldRule> rules = new LinkedHashMap<>();
            for (String name : worlds.getKeys(false)) {
                ConfigurationSection values = section(worlds, name);
                if (!values.getKeys(false).equals(WORLD_KEYS)) {
                    throw new IllegalArgumentException("Неверный набор параметров мира");
                }
                rules.put(name, new WorldRule(name, integer(values, "percentage"), integer(values, "minimum-players"),
                        integer(values, "minimum-votes"), integer(values, "quorum-seconds"), bool(values, "virtual-votes"),
                        bool(values, "clear-weather"), bool(values, "show-bossbar"), integer(values, "night-start"),
                        integer(values, "night-end"), integer(values, "morning-time")));
            }
            ConfigurationSection messages = section(yaml, "messages");
            if (!messages.getKeys(false).equals(MESSAGE_KEYS)) {
                throw new IllegalArgumentException("Неверный набор сообщений");
            }
            Map<String, String> texts = new LinkedHashMap<>();
            for (String key : MESSAGE_KEYS) {
                Object raw = messages.get(key);
                if (!(raw instanceof String text) || text.isBlank() || text.length() > 240
                        || text.codePoints().anyMatch(code -> Character.isISOControl(code)
                        || Character.getType(code) == Character.FORMAT || code == 167)) {
                    throw new IllegalArgumentException("Некорректное сообщение");
                }
                texts.put(key, text);
            }
            return new SleepSettings(rules, texts);
        } catch (InvalidConfigurationException | IllegalArgumentException error) {
            throw new IOException("Invalid SleepVote configuration", error);
        }
    }

    public String message(String key) {
        return messages.getOrDefault(key, key);
    }

    public String progress(VoteResult result) {
        return message("progress").replace("{votes}", Integer.toString(result.votes()))
                .replace("{required}", Integer.toString(result.required()))
                .replace("{eligible}", Integer.toString(result.eligible()));
    }

    private static ConfigurationSection section(ConfigurationSection section, String key) {
        ConfigurationSection value = section.getConfigurationSection(key);
        if (value == null) {
            throw new IllegalArgumentException("Ожидается раздел конфигурации");
        }
        return value;
    }

    private static int integer(ConfigurationSection section, String key) {
        Object value = section.get(key);
        if (!(value instanceof Integer integer)) {
            throw new IllegalArgumentException("Ожидается целое число: " + key);
        }
        return integer;
    }

    private static boolean bool(ConfigurationSection section, String key) {
        if (!(section.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException("Ожидается boolean: " + key);
        }
        return value;
    }
}
