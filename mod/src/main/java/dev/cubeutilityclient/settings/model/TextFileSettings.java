package dev.cubeutilityclient.settings.model;

import dev.cubeutilityclient.CubeUtilityClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A config kept as a commented text file of {@code key: value} lines (BadOptimizations'). The
 * comment above each line becomes its description, true and false become switches, and saving
 * changes only the values, keeping the comments and layout as they were.
 */
final class TextFileSettings implements SettingsModel {
    private static final Pattern ENTRY = Pattern.compile("^(\\s*)([A-Za-z0-9_.-]+)(\\s*[:=]\\s*)(.*?)(\\s*)$");
    /** Words written as they should read. */
    private static final List<String[]> WORDS = List.of(new String[] {"f3", "F3"}, new String[] {"fov", "FOV"}, new String[] {"worldrenderer", "world renderer"},
            new String[] {"skycolor", "sky color"}, new String[] {"getskyangle", "sky angle"});

    private final Path file;
    private final List<String> lines;
    private final List<Page> pages;
    private boolean dirty;

    private TextFileSettings(Path file, List<String> lines, List<Page> pages) {
        this.file = file;
        this.lines = lines;
        this.pages = pages;
    }

    /**
     * Reads the file. Every setting in it needs a restart when {@code restart} is true. Settings
     * whose comment says they do nothing in this version of Minecraft, and the keys in
     * {@code hidden}, aren't shown.
     */
    static SettingsModel load(Path file, boolean restart, Set<String> hidden) {
        List<String> lines;
        try {
            lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException missing) {
            return null;
        }
        TextFileSettings model = new TextFileSettings(file, lines, new ArrayList<>());
        List<Section> sections = new ArrayList<>();
        List<Setting> current = new ArrayList<>();
        String title = "";
        List<String> comment = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#")) {
                String text = line.replaceFirst("^#+\\s?", "").trim();
                // A heading: "# Name" between two lines with only "#".
                if (text.isEmpty() && comment.size() == 1 && i >= 2 && lines.get(i - 2).trim().equals("#")) {
                    if (!current.isEmpty()) {
                        sections.add(new Section(title, List.copyOf(current)));
                        current.clear();
                    }
                    title = comment.get(0);
                    comment.clear();
                } else if (!text.isEmpty()) {
                    comment.add(text);
                }
                continue;
            }
            if (line.isEmpty()) {
                comment.clear();
                continue;
            }
            Matcher match = ENTRY.matcher(lines.get(i));
            if (!match.matches()) {
                comment.clear();
                continue;
            }
            String key = match.group(2), value = match.group(4);
            String description = String.join(" ", comment);
            comment.clear();
            if (hidden.contains(key) || description.toLowerCase(Locale.ROOT).contains("has no effect in")) {
                continue;
            }
            current.add(model.setting(i, key, value, description, restart));
        }
        if (!current.isEmpty()) {
            sections.add(new Section(title, List.copyOf(current)));
        }
        if (sections.isEmpty()) {
            return null;
        }
        model.pages.add(new Page("", List.copyOf(sections)));
        return model;
    }

    private Setting setting(int line, String key, String value, String description, boolean restart) {
        String name = label(key);
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
            return new Setting.Toggle(name, description, restart, null, () -> this.value(line).equalsIgnoreCase("true"), on -> this.write(line, on ? "true" : "false"));
        }
        boolean number = value.matches("-?\\d+(\\.\\d+)?");
        return new Setting.Text(name, description, restart, null, () -> this.value(line), text -> {
            String clean = text.trim();
            if (number ? !clean.matches("-?\\d+(\\.\\d+)?") : clean.isEmpty() || clean.contains("\n")) {
                return false;
            }
            this.write(line, clean);
            return true;
        });
    }

    /** "enable_lightmap_caching" → "Lightmap caching". */
    private static String label(String key) {
        String words = key.replaceFirst("^enable_", "").replace('_', ' ').trim();
        for (String[] word : WORDS) {
            words = words.replaceAll("\\b" + word[0] + "\\b", word[1]);
        }
        return words.isEmpty() ? key : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private String value(int line) {
        Matcher match = ENTRY.matcher(this.lines.get(line));
        return match.matches() ? match.group(4) : "";
    }

    private void write(int line, String value) {
        Matcher match = ENTRY.matcher(this.lines.get(line));
        if (match.matches() && !match.group(4).equals(value)) {
            this.lines.set(line, match.group(1) + match.group(2) + match.group(3) + value + match.group(5));
            this.dirty = true;
        }
    }

    @Override
    public List<Page> pages() {
        return this.pages;
    }

    @Override
    public void save() {
        if (!this.dirty) {
            return;
        }
        try {
            Files.write(this.file, this.lines, StandardCharsets.UTF_8);
            this.dirty = false;
        } catch (IOException error) {
            CubeUtilityClient.LOGGER.warn("Couldn't save {}", this.file, error);
        }
    }

    @Override
    public boolean complete() {
        return true;
    }
}
