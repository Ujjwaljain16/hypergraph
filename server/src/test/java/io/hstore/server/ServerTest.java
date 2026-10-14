// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.query.Session;
import io.hstore.engine.EngineOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTest {

    @TempDir
    Path directory;

    @Test
    void framingEscapesTerminatorLines() throws Exception {
        StringWriter buffer = new StringWriter();
        WireProtocol.send(buffer, "first\n.\n..dots\nlast");
        BufferedReader reader = new BufferedReader(new StringReader(buffer.toString()));
        assertEquals("first\n.\n..dots\nlast", WireProtocol.receive(reader).orElseThrow());
    }

    @Test
    void concurrentClientsShareOneDatabase() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("127.0.0.1", 0))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.port());
                try (Endpoint admin = new RemoteEndpoint(address, Optional.empty())) {
                    assertTrue(admin.describe().startsWith("hstore connection"));
                    admin.execute("CREATE NODE TYPE Item (n INT INDEXED); CREATE SET EDGE TYPE Bag;");
                    admin.execute("INSERT EDGE Bag AS $bag;");
                }
                try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Future<Integer>> results = clients.invokeAll(IntStream.range(0, 8).<Callable<Integer>>mapToObj(client -> () -> {
                        try (Endpoint endpoint = new RemoteEndpoint(address, Optional.empty())) {
                            int rejected = 0;
                            for (int i = 0; i < 10; i++) {
                                String reply = endpoint.execute("INSERT NODE Item 'i" + client + "-" + i + "' {n: " + i + "} AS $x; ADD $x TO @Bag:'missing';");
                                rejected += reply.contains("error") ? 1 : 0;
                            }
                            return rejected;
                        }
                    }).toList());
                    for (Future<Integer> result : results) {
                        assertEquals(10, result.get());
                    }
                }
                try (Endpoint check = new RemoteEndpoint(address, Optional.empty())) {
                    String counted = check.execute("MATCH NODE i:Item RETURN count(*);");
                    assertTrue(counted.contains("| 80 "), counted);
                    String indexed = check.execute("EXPLAIN MATCH NODE i:Item WHERE i.n = 3;");
                    assertTrue(indexed.startsWith("IndexRange(Item.n"), indexed);
                }
            }
            acceptor.join();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sigtermCheckpointsAndLogsTheCleanShutdown() throws Exception {
        String modules = System.getProperty("jdk.module.path");
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow()));
        command.addAll(modules == null ? List.of("-cp", System.getProperty("java.class.path"), Main.class.getName())
                : List.of("-p", modules, "-m", "io.hstore.server/" + Main.class.getName()));
        command.addAll(List.of("serve", directory.resolve("data").toString(), "--port", "0", "--studio", "off"));
        Path log = directory.resolve("serve.log");
        Process server = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!Files.readString(log).contains("listening on") && System.nanoTime() < deadline && server.isAlive()) {
                Thread.sleep(50);
            }
            assertTrue(Files.readString(log).contains("listening on"), Files.readString(log));
            server.destroy();
            assertTrue(server.waitFor(60, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertTrue(output.contains("received shutdown request"), output);
            assertTrue(output.contains("checkpoint complete"), output);
            assertTrue(output.contains("shut down cleanly"), output);
        } finally {
            server.destroyForcibly();
        }
    }

    static Path fixture(String name) throws Exception {
        return Path.of(ServerTest.class.getResource("/tls/" + name).toURI());
    }

    @Test
    void wireProtocolRunsOverTls() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        SSLContext server = Tls.server(fixture("certificate.pem"), fixture("key.pem"));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            Thread acceptor;
            try (Server tls = new Server(database, new InetSocketAddress("127.0.0.1", 0), Server.Policy.permissive().withTls(server))) {
                acceptor = Thread.ofVirtual().start(tls::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", tls.port());
                Tls.Client trusting = new Tls.Client(Tls.trusting(fixture("certificate.pem")), true);
                try (Endpoint endpoint = new RemoteEndpoint(address, Optional.empty(), Optional.of(trusting))) {
                    assertTrue(endpoint.describe().startsWith("hstore connection"));
                    assertTrue(endpoint.execute("CREATE NODE TYPE Person (name STRING); SHOW TYPES;").contains("Person"));
                }
                assertThrows(UncheckedIOException.class,
                        () -> new RemoteEndpoint(address, Optional.empty(), Optional.of(new Tls.Client(Tls.system(), true))));
                assertTimeoutPreemptively(Duration.ofSeconds(20),
                        () -> assertThrows(UncheckedIOException.class, () -> new RemoteEndpoint(address, Optional.empty())));
            }
            acceptor.join();
        }
    }

    @Test
    void nodeCacheIsSizedInMegabytesAndOldNamesAreRejected(@TempDir Path data) throws Exception {
        assertEquals(512L << 20, ServerConfig.load(Optional.empty(), Map.of("HSTORE_NODE_CACHE_MB", "512"), Map.of()).engineOptions().nodeCacheBytes());
        assertEquals(256L << 20, ServerConfig.load(Optional.empty(), Map.of(), Map.of()).engineOptions().nodeCacheBytes());
        List<IllegalArgumentException> failures = new ArrayList<>();
        for (String old : List.of("cache_nodes", "cache_mb")) {
            Files.writeString(data.resolve(ServerConfig.FILE), old + " = 4096\n");
            failures.add(assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(Optional.of(data), Map.of(), Map.of())));
            failures.add(assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of("HSTORE_" + old.toUpperCase(), "4096"), Map.of())));
            failures.add(assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of(), Map.of(old.replace('_', '-'), "4096"))));
        }
        for (String outOfRange : List.of("0", "-1", "99999999999999")) {
            failures.add(assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of("HSTORE_NODE_CACHE_MB", outOfRange), Map.of()).engineOptions()));
        }
        failures.forEach(failure -> assertTrue(failure.getMessage().contains("node_cache_mb"), failure.getMessage()));
    }

    @Test
    void openSegmentFilesComeFromTheFileTheEnvironmentOrAFlag(@TempDir Path data) throws Exception {
        assertEquals(256, ServerConfig.load(Optional.empty(), Map.of(), Map.of()).engineOptions().maxOpenSegmentFiles());
        Files.writeString(data.resolve(ServerConfig.FILE), "max_open_segment_files = 64\n");
        assertEquals(64, ServerConfig.load(Optional.of(data), Map.of(), Map.of()).engineOptions().maxOpenSegmentFiles());
        assertEquals(32, ServerConfig.load(Optional.of(data), Map.of("HSTORE_MAX_OPEN_SEGMENT_FILES", "32"), Map.of())
                .engineOptions().maxOpenSegmentFiles());
        assertEquals(16, ServerConfig.load(Optional.of(data), Map.of("HSTORE_MAX_OPEN_SEGMENT_FILES", "32"),
                Map.of("max-open-segment-files", "16")).engineOptions().maxOpenSegmentFiles());
        for (String outOfRange : List.of("0", "-3", "4294967296")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of("HSTORE_MAX_OPEN_SEGMENT_FILES", outOfRange), Map.of()).engineOptions());
            assertTrue(failure.getMessage().contains("max_open_segment_files"), failure.getMessage());
        }
    }

    @Test
    void checkpointWalMbIsAutomaticUnlessGivenInMegabytes() {
        assertEquals(EngineOptions.AUTOMATIC_CHECKPOINTS, ServerConfig.load(Optional.empty(), Map.of(), Map.of()).engineOptions().checkpointWalBytes());
        assertEquals(EngineOptions.AUTOMATIC_CHECKPOINTS,
                ServerConfig.load(Optional.empty(), Map.of("HSTORE_CHECKPOINT_WAL_MB", "AUTO"), Map.of()).engineOptions().checkpointWalBytes());
        assertEquals(64L << 20, ServerConfig.load(Optional.empty(), Map.of("HSTORE_CHECKPOINT_WAL_MB", "64"), Map.of()).engineOptions().checkpointWalBytes());
        assertEquals(512L << 20, ServerConfig.load(Optional.empty(), Map.of(), Map.of("checkpoint-wal-mb", "512")).engineOptions().checkpointWalBytes());
        for (String invalid : List.of("0", "-1", "99999999999999", "sometimes")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of("HSTORE_CHECKPOINT_WAL_MB", invalid), Map.of()).engineOptions());
            assertTrue(failure.getMessage().contains("checkpoint_wal_mb"), failure.getMessage());
        }
    }

    @Test
    void tlsWithoutACertificateIsRejectedAtStartup() {
        ServerConfig config = ServerConfig.load(Optional.empty(), Map.of(), Map.of("tls", "on"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::serverTls);
        assertTrue(failure.getMessage().contains("tls_certificate_file"));
    }

    @Test
    void serversRequireCredentialsOnceUsersExist() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            new Session(database).execute("CREATE USER admin PASSWORD 'pw' ROLE admin");
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("127.0.0.1", 0))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.port());
                try (Endpoint anonymous = new RemoteEndpoint(address, Optional.empty())) {
                    assertTrue(anonymous.describe().endsWith("authentication required"));
                    assertTrue(anonymous.execute("SHOW TYPES;").startsWith("error"));
                }
                assertThrows(IllegalArgumentException.class,
                        () -> new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "wrong"))));
                try (Endpoint admin = new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "pw")))) {
                    assertTrue(admin.execute("WHOAMI;").contains("ADMIN"));
                    assertTrue(admin.execute("FORMAT JSON; SHOW USERS;").contains("\"rows\":[[\"admin\""));
                }
            }
            acceptor.join();
        }
    }

    @Test
    void configurationLayersFileEnvironmentAndFlags(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve(ServerConfig.FILE), "port = 9000\nlog_statement = ddl\n");
        ServerConfig config = ServerConfig.load(Optional.of(data), Map.of("HSTORE_PORT", "9100", "HSTORE_DURABILITY", "async"),
                Map.of("port", "9200"));
        assertEquals(9200, config.integer(Setting.PORT));
        assertEquals("async", config.string(Setting.DURABILITY));
        assertEquals("ddl", config.string(Setting.LOG_STATEMENT));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(Optional.empty(), Map.of(), Map.of("no_such", "1")).string(Setting.of("no_such")));
    }
}
