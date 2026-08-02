package com.uragestudio.companion;

public record DashboardInstance(String name, String baseUrl, String certificateSha256) {
    @Override
    public String toString() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return name;
        }
        return name + "\n" + baseUrl;
    }
}
