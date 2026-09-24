package ru.warndev.sleepvote;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

public final class LeaseStore {
    private final Path file;

    public LeaseStore(Path file) {
        this.file = file;
    }

    public Map<UUID, Integer> load() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return Map.of();
        }
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8192) {
            throw new IOException("Invalid lease file");
        }
        byte[] bytes;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = stream.readNBytes(8193);
        }
        if (bytes.length < 20 || bytes.length > 8192) {
            throw new IOException("Invalid lease length");
        }
        CRC32 checksum = new CRC32();
        checksum.update(bytes, 0, bytes.length - 8);
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0x57534C50 || input.readInt() != 1) {
                throw new IOException("Unsupported lease format");
            }
            int count = input.readInt();
            if (count < 0 || count > 128 || bytes.length != 20 + count * 20) {
                throw new IOException("Invalid lease count");
            }
            Map<UUID, Integer> leases = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                UUID id = new UUID(input.readLong(), input.readLong());
                int value = input.readInt();
                if (value < 0 || leases.put(id, value) != null) {
                    throw new IOException("Invalid lease entry");
                }
            }
            if (input.readLong() != checksum.getValue()) {
                throw new IOException("Lease checksum mismatch");
            }
            return Map.copyOf(leases);
        }
    }

    public void save(Map<UUID, Integer> leases) throws IOException {
        if (leases.size() > 128) {
            throw new IOException("Too many leases");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(0x57534C50);
            output.writeInt(1);
            output.writeInt(leases.size());
            for (var entry : leases.entrySet()) {
                if (entry.getValue() < 0) {
                    throw new IOException("Negative gamerule");
                }
                output.writeLong(entry.getKey().getMostSignificantBits());
                output.writeLong(entry.getKey().getLeastSignificantBits());
                output.writeInt(entry.getValue());
            }
            CRC32 checksum = new CRC32();
            checksum.update(bytes.toByteArray());
            output.writeLong(checksum.getValue());
        }
        Path directory = file.toAbsolutePath().getParent();
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file)) {
            throw new IOException("Symbolic lease path not allowed");
        }
        Path temporary = Files.createTempFile(directory, ".sleep-lease-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
