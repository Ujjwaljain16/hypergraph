// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

final class Logging {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS zzz").withZone(ZoneId.systemDefault());
    private static final long PID = ProcessHandle.current().pid();

    private static volatile Sinks sinks = new Sinks(Level.INFO, List.of(console(Level.INFO, new PostgresStyle())));

    private Logging() {
    }

    static void configure(ServerConfig config) {
        configure(level(config.string(Setting.LOG_LEVEL)), config.string(Setting.LOG_DIRECTORY),
                config.integer(Setting.LOG_ROTATION_MB), config.integer(Setting.LOG_FILE_COUNT));
    }

    static synchronized void configure(Level level, String directory, int rotationMegabytes, int files) {
        Formatter formatter = new PostgresStyle();
        List<Handler> handlers = new ArrayList<>();
        handlers.add(console(level, formatter));
        if (!directory.isBlank()) {
            try {
                Files.createDirectories(Path.of(directory));
                FileHandler file = new FileHandler(Path.of(directory, "hstore-%g.log").toString(), rotationMegabytes << 20, files, true);
                file.setFormatter(formatter);
                file.setLevel(level);
                handlers.add(file);
            } catch (IOException e) {
                handlers.forEach(Handler::close);
                throw new UncheckedIOException("cannot open log directory " + directory, e);
            }
        }
        List<Handler> previous = sinks.handlers();
        sinks = new Sinks(level, List.copyOf(handlers));
        previous.forEach(Handler::close);
    }

    static synchronized void close() {
        List<Handler> previous = sinks.handlers();
        sinks = new Sinks(sinks.level(), List.of());
        previous.forEach(Handler::close);
    }

    static Level level(String name) {
        return switch (name.toLowerCase()) {
            case "debug" -> Level.FINE;
            case "info" -> Level.INFO;
            case "warning" -> Level.WARNING;
            case "error" -> Level.SEVERE;
            default -> throw new IllegalArgumentException("log_level must be debug, info, warning or error");
        };
    }

    private static Handler console(Level level, Formatter formatter) {
        Handler console = new ConsoleHandler();
        console.setFormatter(formatter);
        console.setLevel(level);
        return console;
    }

    private static Level level(System.Logger.Level level) {
        return switch (level) {
            case ALL -> Level.ALL;
            case TRACE -> Level.FINER;
            case DEBUG -> Level.FINE;
            case INFO -> Level.INFO;
            case WARNING -> Level.WARNING;
            case ERROR -> Level.SEVERE;
            case OFF -> Level.OFF;
        };
    }

    private record Sinks(Level level, List<Handler> handlers) {
        boolean accepts(Level candidate) {
            return candidate != Level.OFF && level != Level.OFF && candidate.intValue() >= level.intValue();
        }
    }

    public static final class Finder extends System.LoggerFinder {
        @Override
        public System.Logger getLogger(String name, Module module) {
            return new Sink(name);
        }
    }

    private record Sink(String name) implements System.Logger {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isLoggable(Level level) {
            return sinks.accepts(level(level));
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String message, Throwable thrown) {
            publish(level, bundle, message, thrown, null);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... parameters) {
            publish(level, bundle, format, null, parameters);
        }

        private void publish(Level level, ResourceBundle bundle, String message, Throwable thrown, Object[] parameters) {
            Sinks current = sinks;
            var converted = level(level);
            if (!current.accepts(converted)) {
                return;
            }
            LogRecord record = new LogRecord(converted, message);
            record.setLoggerName(name);
            record.setResourceBundle(bundle);
            record.setThrown(thrown);
            record.setParameters(parameters);
            current.handlers().forEach(handler -> handler.publish(record));
        }
    }

    private static final class PostgresStyle extends Formatter {
        @Override
        public String format(LogRecord record) {
            StringBuilder line = new StringBuilder(160)
                    .append(TIMESTAMP.format(Instant.ofEpochMilli(record.getMillis())))
                    .append(" [").append(PID).append("] ")
                    .append(severity(record.getLevel())).append(":  ")
                    .append(component(record.getLoggerName()))
                    .append(formatMessage(record))
                    .append(System.lineSeparator());
            if (record.getThrown() != null) {
                line.append("DETAIL:  ").append(record.getThrown()).append(System.lineSeparator());
                for (StackTraceElement frame : record.getThrown().getStackTrace()) {
                    line.append("\tat ").append(frame).append(System.lineSeparator());
                }
            }
            return line.toString();
        }

        private static String severity(Level level) {
            if (level.intValue() >= Level.SEVERE.intValue()) {
                return "ERROR";
            }
            if (level.intValue() >= Level.WARNING.intValue()) {
                return "WARNING";
            }
            if (level.intValue() >= Level.INFO.intValue()) {
                return "LOG";
            }
            return "DEBUG";
        }

        private static String component(String logger) {
            if (logger == null || logger.isEmpty()) {
                return "";
            }
            int dot = logger.lastIndexOf('.');
            return "[" + (dot < 0 ? logger : logger.substring(dot + 1)) + "] ";
        }
    }
}
