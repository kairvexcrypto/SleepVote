package ru.warndev.sleepvote;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SleepSettingsTest {
    @TempDir Path root;

    private Path config() throws IOException {
        Path file = root.resolve("config.yml");
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            Files.write(file, input.readAllBytes());
        }
        return file;
    }

    @Test
    void loadsBundledConfiguration() throws Exception {
        SleepSettings settings = SleepSettings.load(config());
        assertEquals(50, settings.worlds().get("world").percentage());
        assertTrue(settings.worlds().get("world").virtualVotes());
        assertThrows(UnsupportedOperationException.class, () -> settings.worlds().clear());
    }

    @Test
    void rejectsMissingAndUnknownKeys() throws Exception {
        Path file = config();
        String original = Files.readString(file);
        Files.writeString(file, original.replace("    percentage: 50\n", ""));
        assertThrows(IOException.class, () -> SleepSettings.load(file));
        Files.writeString(file, original + "typo: true\n");
        assertThrows(IOException.class, () -> SleepSettings.load(file));
    }

    @Test
    void rejectsWrongTypesAndOutOfRangeValues() throws Exception {
        Path file = config();
        String original = Files.readString(file);
        Files.writeString(file, original.replace("percentage: 50", "percentage: '50'"));
        assertThrows(IOException.class, () -> SleepSettings.load(file));
        Files.writeString(file, original.replace("percentage: 50", "percentage: 101"));
        assertThrows(IOException.class, () -> SleepSettings.load(file));
    }

    @Test
    void rejectsMalformedUtf8AndOversizedInput() throws Exception {
        Path file = root.resolve("config.yml");
        Files.write(file, new byte[]{(byte) 0xc3, 0x28});
        assertThrows(IOException.class, () -> SleepSettings.load(file));
        Files.write(file, new byte[65537]);
        assertThrows(IOException.class, () -> SleepSettings.load(file));
    }
}
