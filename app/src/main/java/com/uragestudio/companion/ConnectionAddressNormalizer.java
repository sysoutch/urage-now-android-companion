package com.uragestudio.companion;

import java.net.URI;

final class ConnectionAddressNormalizer {
    private ConnectionAddressNormalizer() {}

    static String normalize(String raw) {
        String normalized = String.valueOf(raw == null ? "" : raw).trim().replaceAll("/+$", "");
        if (normalized.isEmpty()) return "";
        if (!normalized.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) normalized = "http://" + normalized;
        try {
            return URI.create(normalized).getHost() == null ? "" : normalized;
        } catch (IllegalArgumentException ignored) {
            return "";
        }
    }
}
