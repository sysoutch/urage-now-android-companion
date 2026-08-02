package com.uragestudio.companion;

import android.content.Context;
import android.content.SharedPreferences;

/** Owns the one connection route used by every mobile Studio workflow. */
final class ConnectionRouteStore {
    static final String LAN = "lan";
    static final String MATRIX = "matrix";
    private static final String KEY_ROUTE = "active_workflow_route";
    private final SharedPreferences preferences;

    ConnectionRouteStore(Context context) {
        preferences = context.getSharedPreferences("connection_route", Context.MODE_PRIVATE);
    }

    String activeRoute() {
        String value = preferences.getString(KEY_ROUTE, LAN);
        return MATRIX.equals(value) ? MATRIX : LAN;
    }

    void useLan() {
        preferences.edit().putString(KEY_ROUTE, LAN).apply();
    }

    void useMatrix() {
        preferences.edit().putString(KEY_ROUTE, MATRIX).apply();
    }

    boolean usesMatrix() {
        return MATRIX.equals(activeRoute());
    }
}
