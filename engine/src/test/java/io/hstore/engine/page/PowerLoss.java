// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class PowerLoss implements AutoCloseable {

    public enum Tear {
        NOTHING,
        ALL,
        HALF_LAST,
        FLIP_LAST,
        RANDOM
    }

    public enum Kind {
        WRITE,
        TRUNCATE,
        FORCE,
        MAPPED_FORCE,
        DIRECTORY_FORCE
    }

    public record Event(Kind kind, Path file) {
    }

    public static final class Cut extends Error {
        Cut(Event event) {
            super("power lost before " + event.kind() + " of " + event.file(), null, false, false);
        }
    }

    @FunctionalInterface
    private interface Io<T> {
        T run() throws IOException;
    }

    private sealed interface Op {
    }

    private record Write(long position, byte[] bytes) implements Op {
    }

    private record Truncate(long size) implements Op {
    }

    private record Image(byte[] bytes, List<long[]> fresh) {
    }

    private static final class Tracked {
        private byte[] durable;
        private final List<Op> pending = new ArrayList<>();
        private boolean entryDurable;
        private boolean mapped;
        private byte[] current;

        Tracked(byte[] durable, boolean entryDurable) {
            this.durable = durable;
            this.entryDurable = entryDurable;
        }
    }

    private static final int BLOCK = 4096;
    private static final Map<Path, PowerLoss> WATCHED = new ConcurrentHashMap<>();

    static {
        FileHandle.channels = new FileHandle.Channels() {
            @Override
            public FileChannel open(Path path, OpenOption... options) throws IOException {
                PowerLoss owner = owner(path);
                return owner == null ? FileChannel.open(path, options) : owner.open(key(path), options);
            }

            @Override
            public void forced(Path path) {
                PowerLoss owner = owner(path);
                if (owner != null) {
                    owner.mappingForced(key(path));
                }
            }
        };
    }

    private final Path root;
    private final Set<Path> baseline;
    private final Map<Path, Tracked> files = new HashMap<>();
    private Predicate<Event> trigger = _ -> false;
    private long events;
    private boolean crashed;
    private Set<Path> atCrash;

    private PowerLoss(Path root, Set<Path> baseline) {
        this.root = root;
        this.baseline = baseline;
    }

    public static PowerLoss watch(Path root) {
        PowerLoss loss = new PowerLoss(key(root), list(key(root)));
        if (WATCHED.putIfAbsent(loss.root, loss) != null) {
            throw new IllegalStateException(root + " is already watched");
        }
        return loss;
    }

    public synchronized void crashAt(long event) {
        trigger = _ -> events == event;
    }

    public synchronized void crashOn(Predicate<Event> when) {
        trigger = when;
    }

    public synchronized long events() {
        return events;
    }

    public synchronized boolean crashed() {
        return crashed;
    }

    public synchronized void powerOff() {
        if (!crashed) {
            cut();
        }
    }

    public void restore(Tear tear, Random random) {
        restore(_ -> tear, random);
    }

    public synchronized void restore(Function<Path, Tear> tears, Random random) {
        powerOff();
        try {
            for (Path path : list(root)) {
                if (!atCrash.contains(path)) {
                    Files.delete(path);
                }
            }
            Map<Path, Image> images = new TreeMap<>();
            for (Path path : new TreeSet<>(atCrash)) {
                Tracked tracked = files.get(path);
                Tear tear = tears.apply(path);
                if (tracked == null) {
                    if (!baseline.contains(path) && !files.containsKey(path.resolveSibling(path.getFileName() + ".tmp"))) {
                        Files.write(path, new byte[0]);
                    }
                } else if (!tracked.entryDurable && (tear == Tear.NOTHING || random.nextBoolean())) {
                    Files.deleteIfExists(path);
                } else {
                    images.put(path, tracked.mapped ? mixed(tracked, tear, random) : replayed(tracked, tear, random));
                }
            }
            if (images.keySet().stream().anyMatch(path -> tears.apply(path) == Tear.RANDOM) && random.nextInt(3) == 0) {
                List<Image> candidates = images.values().stream().filter(image -> !image.fresh().isEmpty()).toList();
                if (!candidates.isEmpty()) {
                    Image chosen = candidates.get(random.nextInt(candidates.size()));
                    long[] range = chosen.fresh().get(random.nextInt(chosen.fresh().size()));
                    flip(chosen.bytes(), range[0] + random.nextLong(range[1]));
                }
            }
            for (Map.Entry<Path, Image> entry : images.entrySet()) {
                Files.write(entry.getKey(), entry.getValue().bytes());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() {
        WATCHED.remove(root, this);
    }

    private static Image replayed(Tracked tracked, Tear tear, Random random) {
        List<Op> ops = tracked.pending;
        Image image = new Image(tracked.durable.clone(), new ArrayList<>());
        int last = ops.size() - 1;
        int mode = random.nextInt(3);
        int prefix = random.nextInt(ops.size() + 1);
        for (int i = 0; i < ops.size(); i++) {
            Op op = ops.get(i);
            image = switch (tear) {
                case NOTHING -> image;
                case ALL -> apply(image, op, 1);
                case HALF_LAST -> i < last ? apply(image, op, 1) : apply(image, op, 0.5);
                case FLIP_LAST -> {
                    Image applied = apply(image, op, 1);
                    if (i == last && op instanceof Write(long position, byte[] bytes) && bytes.length > 0) {
                        flip(applied.bytes(), position + bytes.length / 2);
                    }
                    yield applied;
                }
                case RANDOM -> switch (mode) {
                    case 0 -> i < prefix ? apply(image, op, 1) : i == prefix ? apply(image, op, random.nextDouble()) : image;
                    case 1 -> switch (random.nextInt(3)) {
                        case 0 -> image;
                        case 1 -> apply(image, op, 1);
                        default -> apply(image, op, random.nextDouble());
                    };
                    default -> apply(image, op, 1);
                };
            };
        }
        return image;
    }

    private static Image apply(Image image, Op op, double fraction) {
        return switch (op) {
            case Truncate(long size) -> new Image(size < image.bytes().length ? Arrays.copyOf(image.bytes(), Math.toIntExact(size)) : image.bytes(),
                    image.fresh());
            case Write(long position, byte[] bytes) -> {
                int length = (int) Math.floor(bytes.length * fraction);
                if (length == 0) {
                    yield image;
                }
                int end = Math.toIntExact(position + length);
                byte[] grown = end > image.bytes().length ? Arrays.copyOf(image.bytes(), end) : image.bytes();
                System.arraycopy(bytes, 0, grown, Math.toIntExact(position), length);
                image.fresh().add(new long[]{position, length});
                yield new Image(grown, image.fresh());
            }
        };
    }

    private static Image mixed(Tracked tracked, Tear tear, Random random) {
        byte[] durable = tracked.durable;
        byte[] current = tracked.current == null ? durable : tracked.current;
        return switch (tear) {
            case NOTHING -> new Image(durable.clone(), List.of());
            case ALL, HALF_LAST, FLIP_LAST -> new Image(current.clone(), List.of());
            case RANDOM -> {
                byte[] mixed = new byte[random.nextBoolean() ? durable.length : current.length];
                for (int block = 0; block < mixed.length; block += BLOCK) {
                    byte[] source = random.nextBoolean() ? durable : current;
                    int length = Math.max(0, Math.min(BLOCK, Math.min(mixed.length, source.length) - block));
                    System.arraycopy(source, Math.min(block, source.length), mixed, block, length);
                }
                yield new Image(mixed, List.of());
            }
        };
    }

    private static void flip(byte[] bytes, long position) {
        if (position < bytes.length) {
            bytes[Math.toIntExact(position)] ^= (byte) 0x5A;
        }
    }

    private synchronized FileChannel open(Path path, OpenOption... options) throws IOException {
        if (Files.isDirectory(path)) {
            return new Recorded(this, path, FileChannel.open(path, options), true);
        }
        if (crashed) {
            return FileChannel.open(path, options);
        }
        boolean existed = Files.exists(path);
        Tracked tracked = files.get(path);
        if (!existed) {
            files.put(path, new Tracked(new byte[0], false));
        } else if (tracked == null) {
            files.put(path, new Tracked(Files.readAllBytes(path), true));
        }
        if (existed && Arrays.asList(options).contains(StandardOpenOption.TRUNCATE_EXISTING)) {
            event(Kind.TRUNCATE, path);
            files.get(path).pending.add(new Truncate(0));
        }
        return new Recorded(this, path, FileChannel.open(path, options), false);
    }

    private synchronized int write(Path file, long position, ByteBuffer source, Io<Integer> io) throws IOException {
        if (crashed) {
            return io.run();
        }
        event(Kind.WRITE, file);
        ByteBuffer copy = source.duplicate();
        int written = io.run();
        byte[] bytes = new byte[written];
        copy.get(bytes);
        files.get(file).pending.add(new Write(position, bytes));
        return written;
    }

    private synchronized void truncate(Path file, long size, Io<?> io) throws IOException {
        if (crashed) {
            io.run();
            return;
        }
        event(Kind.TRUNCATE, file);
        io.run();
        files.get(file).pending.add(new Truncate(size));
    }

    private synchronized void force(Path file, boolean directory, Io<?> io) throws IOException {
        if (crashed) {
            io.run();
            return;
        }
        event(directory ? Kind.DIRECTORY_FORCE : Kind.FORCE, file);
        io.run();
        if (directory) {
            files.forEach((path, tracked) -> tracked.entryDurable |= file.equals(path.getParent()));
        } else {
            Tracked tracked = files.get(file);
            Image image = new Image(tracked.durable, new ArrayList<>());
            for (Op op : tracked.pending) {
                image = apply(image, op, 1);
            }
            tracked.durable = image.bytes().clone();
            tracked.pending.clear();
        }
    }

    private synchronized void mapped(Path file) {
        Tracked tracked = files.get(file);
        if (tracked != null) {
            tracked.mapped = true;
        }
    }

    private synchronized void mappingForced(Path file) {
        if (crashed) {
            return;
        }
        event(Kind.MAPPED_FORCE, file);
        Tracked tracked = files.get(file);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            if (tracked.durable.length != channel.size()) {
                tracked.durable = new byte[Math.toIntExact(channel.size())];
            }
            ByteBuffer buffer = ByteBuffer.wrap(tracked.durable);
            while (buffer.hasRemaining()) {
                channel.read(buffer, buffer.position());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void event(Kind kind, Path file) {
        events++;
        Event event = new Event(kind, file);
        if (trigger.test(event)) {
            cut();
            throw new Cut(event);
        }
    }

    private void cut() {
        crashed = true;
        atCrash = list(root);
        files.forEach((path, tracked) -> {
            if (tracked.mapped && Files.exists(path)) {
                tracked.current = read(path);
            }
        });
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<Path> list(Path root) {
        if (!Files.exists(root)) {
            return Set.of();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile).map(PowerLoss::key).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path key(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static PowerLoss owner(Path path) {
        Path absolute = key(path);
        for (Map.Entry<Path, PowerLoss> watched : WATCHED.entrySet()) {
            if (absolute.startsWith(watched.getKey())) {
                return watched.getValue();
            }
        }
        return null;
    }

    private static final class Recorded extends FileChannel {
        private final PowerLoss owner;
        private final Path file;
        private final FileChannel delegate;
        private final boolean directory;

        Recorded(PowerLoss owner, Path file, FileChannel delegate, boolean directory) {
            this.owner = owner;
            this.file = file;
            this.delegate = delegate;
            this.directory = directory;
        }

        @Override
        public int read(ByteBuffer destination) throws IOException {
            return delegate.read(destination);
        }

        @Override
        public long read(ByteBuffer[] destinations, int offset, int length) throws IOException {
            return delegate.read(destinations, offset, length);
        }

        @Override
        public int read(ByteBuffer destination, long position) throws IOException {
            return delegate.read(destination, position);
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            return owner.write(file, delegate.position(), source, () -> delegate.write(source));
        }

        @Override
        public long write(ByteBuffer[] sources, int offset, int length) throws IOException {
            long total = 0;
            for (int i = offset; i < offset + length; i++) {
                total += write(sources[i]);
            }
            return total;
        }

        @Override
        public int write(ByteBuffer source, long position) throws IOException {
            return owner.write(file, position, source, () -> delegate.write(source, position));
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long position) throws IOException {
            delegate.position(position);
            return this;
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            owner.truncate(file, size, () -> delegate.truncate(size));
            return this;
        }

        @Override
        public void force(boolean metaData) throws IOException {
            owner.force(file, directory, () -> {
                delegate.force(metaData);
                return null;
            });
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long transferFrom(ReadableByteChannel source, long position, long count) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            if (mode == MapMode.READ_WRITE) {
                owner.mapped(file);
            }
            return delegate.map(mode, position, size);
        }

        @Override
        public MemorySegment map(MapMode mode, long offset, long size, Arena arena) throws IOException {
            if (mode == MapMode.READ_WRITE) {
                owner.mapped(file);
            }
            return delegate.map(mode, offset, size, arena);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
