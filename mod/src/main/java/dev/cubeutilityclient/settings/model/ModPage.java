package dev.cubeutilityclient.settings.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything one mod's page shows, the same way for every mod: what the mod does, then its
 * settings. Mods whose settings the page can read get them here as switches, sliders and
 * choices; for the others a row says where their settings are (their own screen, or another
 * mod's page) or that there is nothing to set up.
 */
public final class ModPage implements SettingsModel {
    private final SettingsModel settings;
    private final List<Page> pages;

    private ModPage(SettingsModel settings, List<Page> pages) {
        this.settings = settings;
        this.pages = pages;
    }

    /**
     * @param about what the mod does, one point per entry (may be empty)
     * @param settings the mod's settings as the page reads them, or null
     * @param instead what shows where settings would be when there are none to draw (a note, or a
     *     button to the mod's own screen)
     */
    public static ModPage of(List<String> about, SettingsModel settings, List<Setting> instead) {
        Section aboutSection = about.isEmpty() ? null : new Section("What it does", about.stream().map(point -> (Setting) new Setting.Note(point, true)).toList());
        List<Page> pages = new ArrayList<>();
        if (settings != null && !settings.pages().isEmpty()) {
            List<Page> theirs = settings.pages();
            for (int i = 0; i < theirs.size(); i++) {
                Page page = theirs.get(i);
                if (i == 0 && aboutSection != null) {
                    List<Section> sections = new ArrayList<>();
                    sections.add(aboutSection);
                    List<Section> rest = page.sections();
                    for (int j = 0; j < rest.size(); j++) {
                        Section section = rest.get(j);
                        // Under "What it does", the first untitled group needs a heading of its own.
                        sections.add(j == 0 && section.title().isBlank() ? new Section(theirs.size() > 1 ? page.title() : "Settings", section.settings()) : section);
                    }
                    pages.add(new Page(page.title(), List.copyOf(sections), page.complete()));
                } else {
                    pages.add(page);
                }
            }
            return new ModPage(settings, List.copyOf(pages));
        }
        List<Section> sections = new ArrayList<>();
        if (aboutSection != null) {
            sections.add(aboutSection);
        }
        if (!instead.isEmpty()) {
            sections.add(new Section("Settings", List.copyOf(instead)));
        }
        pages.add(new Page("", List.copyOf(sections)));
        return new ModPage(null, List.copyOf(pages));
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    @Override
    public void save() {
        if (this.settings != null) {
            this.settings.save();
        }
    }

    @Override
    public boolean complete() {
        return this.settings == null || this.settings.complete();
    }

    @Override
    public void close() {
        if (this.settings != null) {
            this.settings.close();
        }
    }
}
