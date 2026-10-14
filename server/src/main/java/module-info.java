// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

module io.hstore.server {
    requires io.hstore.db;
    requires java.logging;
    requires jdk.httpserver;

    provides System.LoggerFinder with io.hstore.server.Logging.Finder;
}
