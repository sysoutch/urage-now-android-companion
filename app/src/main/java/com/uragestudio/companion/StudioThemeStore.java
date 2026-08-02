package com.uragestudio.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import java.util.List;

/** Persists Android palettes that mirror the dashboard Studio theme families. */
final class StudioThemeStore {
    record Palette(
        String id, String label, boolean light,
        int background, int surface, int surfaceHigh, int border,
        int accent, int accentStrong, int accentContainer,
        int text, int textMuted, int danger
    ) {}

    private static final String PREFERENCES = "studio_theme";
    private static final String LEGACY_ACTIVE_THEME = "active_theme";
    private static final String MODE = "theme_mode";
    private static final String LOCAL_THEME = "local_theme";
    private static final String DASHBOARD_THEME = "dashboard_theme";
    private static final String MODE_DASHBOARD = "dashboard";
    private static final String MODE_LOCAL = "local";
    private static final String DEFAULT_LOCAL_THEME = "crystal";
    private static final String DEFAULT_DASHBOARD_THEME = "fire";
    private static final List<Palette> PALETTES = List.of(
        palette("fire", "Fire", false, "#100605", "#13090A", "#24100D", "#5C2C20", "#FF6A35", "#FFB266", "#482017", "#FFF7EE", "#CDB4A7", "#FF766E"),
        palette("light", "Light", true, "#F8FAFC", "#FFFFFF", "#EDF3FA", "#A9BED4", "#2E70AD", "#5B96C8", "#DDEAF6", "#172B40", "#4E647B", "#C23C55"),
        palette("smoke", "Smoke", false, "#13161A", "#1B1F24", "#242930", "#454B55", "#8B93A0", "#C4CCD8", "#343A43", "#F3F5F8", "#A9AFB8", "#FF8A9A"),
        palette("blood", "Blood", false, "#070104", "#110306", "#20050A", "#5C101D", "#D31938", "#FF8A8F", "#3A0812", "#FFF2F3", "#CBA5AC", "#FF596B"),
        palette("love", "Love", false, "#10030D", "#1B0717", "#2A0B23", "#65204E", "#EC4CA8", "#FF9BD8", "#49203E", "#FFF1FA", "#D1A6C2", "#FF7FB9"),
        palette("water", "Water", false, "#06111D", "#091827", "#10263B", "#245377", "#3FB5FF", "#84DCFF", "#173C59", "#EFF9FF", "#A4C1D4", "#FF8A9A"),
        palette("crystal", "Crystal", false, "#060917", "#0A1129", "#121A38", "#43236A", "#A836E6", "#D16AFF", "#33204E", "#F8F1FF", "#B9AED0", "#FF8A9A"),
        palette("nature", "Nature", false, "#071008", "#0B1B0D", "#142A17", "#2D5A34", "#56C96B", "#B0F08F", "#24472A", "#F2FFF3", "#A9C8AD", "#FF8A9A"),
        palette("rock", "Rock", false, "#0F0E0C", "#141210", "#23201C", "#514A40", "#9F9686", "#D8D3C6", "#37322C", "#FAF7F0", "#BDB5A8", "#FF8A9A")
    );

    private final SharedPreferences preferences;

    StudioThemeStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        migrateLegacyPreference();
    }

    List<Palette> palettes() {
        return PALETTES;
    }

    Palette active() {
        return usesDashboardTheme()
            ? find(preferences.getString(DASHBOARD_THEME, DEFAULT_DASHBOARD_THEME), DEFAULT_DASHBOARD_THEME)
            : find(preferences.getString(LOCAL_THEME, DEFAULT_LOCAL_THEME), DEFAULT_LOCAL_THEME);
    }

    boolean usesDashboardTheme() {
        return MODE_DASHBOARD.equals(preferences.getString(MODE, MODE_DASHBOARD));
    }

    void useDashboardTheme() {
        preferences.edit().putString(MODE, MODE_DASHBOARD).apply();
    }

    void useLocalTheme(String id) {
        preferences.edit()
            .putString(MODE, MODE_LOCAL)
            .putString(LOCAL_THEME, find(id, DEFAULT_LOCAL_THEME).id())
            .apply();
    }

    boolean cacheDashboardTheme(String id) {
        String normalized = find(id, DEFAULT_DASHBOARD_THEME).id();
        String before = active().id();
        preferences.edit().putString(DASHBOARD_THEME, normalized).apply();
        return usesDashboardTheme() && !before.equals(normalized);
    }

    Palette dashboardTheme() {
        return find(preferences.getString(DASHBOARD_THEME, DEFAULT_DASHBOARD_THEME), DEFAULT_DASHBOARD_THEME);
    }

    private void migrateLegacyPreference() {
        if (preferences.contains(MODE) || !preferences.contains(LEGACY_ACTIVE_THEME)) return;
        String legacy = find(preferences.getString(LEGACY_ACTIVE_THEME, DEFAULT_LOCAL_THEME), DEFAULT_LOCAL_THEME).id();
        preferences.edit()
            .putString(MODE, MODE_LOCAL)
            .putString(LOCAL_THEME, legacy)
            .remove(LEGACY_ACTIVE_THEME)
            .apply();
    }

    private static Palette find(String id, String fallbackId) {
        return PALETTES.stream().filter(theme -> theme.id().equals(id)).findFirst()
            .orElseGet(() -> PALETTES.stream().filter(theme -> theme.id().equals(fallbackId)).findFirst().orElseThrow());
    }

    private static Palette palette(
        String id, String label, boolean light, String background, String surface,
        String surfaceHigh, String border, String accent, String accentStrong,
        String accentContainer, String text, String textMuted, String danger
    ) {
        return new Palette(id, label, light, color(background), color(surface), color(surfaceHigh),
            color(border), color(accent), color(accentStrong), color(accentContainer),
            color(text), color(textMuted), color(danger));
    }

    private static int color(String value) {
        return Color.parseColor(value);
    }
}
