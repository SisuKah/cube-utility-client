package dev.cubeutilityclient.settings.model;

import java.util.List;

/**
 * A mod's settings, read from the library it builds them with, so the settings page can draw
 * every mod's settings the same way.
 */
public interface SettingsModel {
    /** Settings under a heading; the heading is empty for settings that have none. */
    record Section(String title, List<Setting> settings) {
    }

    /**
     * One of the mod's categories (Sodium's General, Quality...), shown as a chip above the list.
     * {@code complete} is false when some of its settings only show on the mod's own screen.
     */
    record Page(String title, List<Section> sections, boolean complete) {
        public Page(String title, List<Section> sections) {
            this(title, sections, true);
        }
    }

    List<Page> pages();

    /** Writes what changed the way the mod's own Save or Apply button would. Safe to call often. */
    void save();

    /** False when some of the mod's settings couldn't be shown and only its own screen has them. */
    boolean complete();
}
