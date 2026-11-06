// SPDX-License-Identifier: Apache-2.0
package com.mirakyux.export4j.components;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicExportTest {
    @TempDir Path directory;

    @Test
    void flushesSmallWritesAndTheFinalTailBeforeReplacingTheTarget() throws IOException {
        var target = directory.resolve("report.bin");
        Files.writeString(target, "old");
        byte[] expected = new byte[150_003];
        new Random(42).nextBytes(expected);
        assertEquals("done", DocumentEngine.writeAtomically(target, output -> {
            for (byte value : expected) { output.write(value); }
            assertEquals("old", Files.readString(target));
            return "done";
        }));
        assertArrayEquals(expected, Files.readAllBytes(target));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test
    void partialOutputFailureKeepsTheOldTargetAndDeletesTheTemporaryFile() throws IOException {
        var target = directory.resolve("report.bin");
        Files.writeString(target, "old");
        var rejected = new IOException("export failed");
        assertSame(rejected, assertThrows(IOException.class, () -> DocumentEngine.writeAtomically(target, output -> {
            output.write(new byte[70_000]); output.write(42);
            throw rejected;
        })));
        assertEquals("old", Files.readString(target));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
}
