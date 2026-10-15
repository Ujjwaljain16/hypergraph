// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

final class WireProtocol {

    static final String TERMINATOR = ".";

    static final class TooLarge extends IOException {
        TooLarge(long limit) {
            super("message exceeds " + limit + " bytes");
        }
    }

    private static final Pattern REQUEST_LINES = Pattern.compile("\n");
    private static final Pattern RESPONSE_LINES = Pattern.compile("\r\n|\r|\n");
    private static final Pattern HTTP = Pattern.compile("^(GET|HEAD|POST|PUT|DELETE|PATCH|OPTIONS|CONNECT|TRACE|PRI) \\S+ HTTP/.*", Pattern.DOTALL);

    private WireProtocol() {
    }

    static void send(Writer out, String message) throws IOException {
        write(out, REQUEST_LINES, message);
    }

    static void respond(Writer out, String message) throws IOException {
        write(out, RESPONSE_LINES, message);
    }

    private static void write(Writer out, Pattern lines, String message) throws IOException {
        for (String line : lines.split(message, -1)) {
            out.write(line.startsWith(TERMINATOR) ? TERMINATOR + line : line);
            out.write('\n');
        }
        out.write(TERMINATOR);
        out.write('\n');
        out.flush();
    }

    static Optional<String> receive(InputStream in, long limit) throws IOException {
        StringBuilder message = new StringBuilder();
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        long read = 0;
        for (int next = in.read(); next != -1; next = in.read()) {
            if (++read > limit) {
                throw new TooLarge(limit);
            }
            if (next != '\n') {
                line.write(next);
                continue;
            }
            String text = line.toString(StandardCharsets.UTF_8);
            line.reset();
            if (text.equals(TERMINATOR) || text.equals(TERMINATOR + "\r")) {
                return Optional.of(message.isEmpty() ? "" : message.substring(0, message.length() - 1));
            }
            message.append(text.startsWith(TERMINATOR + TERMINATOR) ? text.substring(1) : text).append('\n');
        }
        return Optional.empty();
    }

    static boolean http(String request) {
        return HTTP.matcher(request).matches();
    }
}
