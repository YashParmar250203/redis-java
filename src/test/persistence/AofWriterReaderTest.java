package com.example.redis.persistence;

import com.example.redis.protocol.RespCommandEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AofWriterReaderTest {

    @Test
    void writtenCommandsReplayInOrder(@TempDir Path tempDir) throws IOException {
        Path aofFile = tempDir.resolve("test.aof");
        AofWriter writer = new AofWriter(aofFile.toString(), "always");
        writer.open();
        writer.append(RespCommandEncoder.encode(new String[]{"SET", "name", "Yash"}));
        writer.append(RespCommandEncoder.encode(new String[]{"SET", "city", "Delhi"}));
        writer.append(RespCommandEncoder.encode(new String[]{"DEL", "city"}));
        writer.close();

        AofReader.ReadResult result = AofReader.readAll(aofFile);

        assertFalse(result.stoppedEarlyDueToCorruption());
        assertEquals(3, result.commands().size());
        assertArrayEquals(new String[]{"SET", "name", "Yash"}, result.commands().get(0));
        assertArrayEquals(new String[]{"SET", "city", "Delhi"}, result.commands().get(1));
        assertArrayEquals(new String[]{"DEL", "city"}, result.commands().get(2));
    }

    @Test
    void valuesWithSpacesRoundTripCorrectly(@TempDir Path tempDir) throws IOException {
        Path aofFile = tempDir.resolve("test.aof");
        AofWriter writer = new AofWriter(aofFile.toString(), "always");
        writer.open();
        writer.append(RespCommandEncoder.encode(new String[]{"SET", "name", "Yash Kumar Sharma"}));
        writer.close();

        AofReader.ReadResult result = AofReader.readAll(aofFile);
        assertEquals("Yash Kumar Sharma", result.commands().get(0)[2]);
    }

    @Test
    void missingFileReplaysAsEmptyWithNoError() throws IOException {
        AofReader.ReadResult result = AofReader.readAll(Path.of("this/path/does/not/exist.aof"));
        assertTrue(result.commands().isEmpty());
        assertFalse(result.stoppedEarlyDueToCorruption());
    }

    @Test
    void truncatedTrailingRecordIsDroppedNotMisinterpreted(@TempDir Path tempDir) throws IOException {
        Path aofFile = tempDir.resolve("test.aof");
        String complete = RespCommandEncoder.encode(new String[]{"SET", "a", "1"});
        // Declares a 3-byte bulk string but only provides 2 bytes and no
        // trailing \r\n - simulates a process crashing mid-write.
        String truncatedSecondCommand = "*3\r\n$3\r\nSET\r\n$1\r\nb\r\n$3\r\n12";
        Files.writeString(aofFile, complete + truncatedSecondCommand, StandardCharsets.UTF_8);

        AofReader.ReadResult result = AofReader.readAll(aofFile);

        assertEquals(1, result.commands().size());
        assertArrayEquals(new String[]{"SET", "a", "1"}, result.commands().get(0));
        assertTrue(result.stoppedEarlyDueToCorruption());
    }

    @Test
    void genuinelyMalformedRecordStopsReplayAtThatPoint(@TempDir Path tempDir) throws IOException {
        Path aofFile = tempDir.resolve("test.aof");
        String complete = RespCommandEncoder.encode(new String[]{"SET", "a", "1"});
        String malformed = "*notanumber\r\n";
        Files.writeString(aofFile, complete + malformed, StandardCharsets.UTF_8);

        AofReader.ReadResult result = AofReader.readAll(aofFile);

        assertEquals(1, result.commands().size());
        assertTrue(result.stoppedEarlyDueToCorruption());
    }
}