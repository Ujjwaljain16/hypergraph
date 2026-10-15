// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.security;

import io.hstore.engine.HStoreException;

public record Principal(String user, int tenant, Role role, int home) {

    public static final int DEFAULT_TENANT = 0;
    public static final Principal SYSTEM = new Principal("system", DEFAULT_TENANT, Role.ADMIN);

    public Principal(String user, int tenant, Role role) {
        this(user, tenant, role, tenant);
    }

    public boolean global() {
        return role.canAdminister() && home == DEFAULT_TENANT;
    }

    public boolean governs(int other) {
        return global() || role.canAdminister() && other == home;
    }

    public Principal inTenant(int other) {
        if (other != tenant && !global()) {
            throw HStoreException.invalid("user " + user + " may not switch tenants");
        }
        return new Principal(user, other, role, home);
    }

    public void requireWrite() {
        if (!role.canWrite()) {
            throw HStoreException.invalid("user " + user + " has read-only access");
        }
    }

    public void requireAdmin() {
        if (!role.canAdminister()) {
            throw HStoreException.invalid("user " + user + " lacks the ADMIN role required for this statement");
        }
    }

    public void requireGlobal() {
        if (!global()) {
            throw HStoreException.invalid("user " + user + " lacks the ADMIN role in the default tenant required for this statement");
        }
    }
}
