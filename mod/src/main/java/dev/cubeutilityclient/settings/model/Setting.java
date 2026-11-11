package dev.cubeutilityclient.settings.model;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * One setting of a mod, as the settings page shows it: a name, what it does, and one control.
 * Every kind reads and changes the mod's own value, so the mod's own rules and saving apply.
 */
public abstract class Setting {
    public final String name;
    public final String description;
    /** Takes effect after Minecraft restarts. */
    public final boolean restart;
    private final BooleanSupplier enabled;

    Setting(String name, String description, boolean restart, BooleanSupplier enabled) {
        this.name = name;
        this.description = description == null ? "" : description.replaceAll("\\s+", " ").trim();
        this.restart = restart;
        this.enabled = enabled;
    }

    /** Some settings only apply while another is on; the page greys them out meanwhile. */
    public boolean enabled() {
        try {
            return this.enabled == null || this.enabled.getAsBoolean();
        } catch (Throwable error) {
            return true;
        }
    }

    public static final class Toggle extends Setting {
        private final BooleanSupplier get;
        private final Consumer<Boolean> set;

        public Toggle(String name, String description, boolean restart, BooleanSupplier enabled, BooleanSupplier get, Consumer<Boolean> set) {
            super(name, description, restart, enabled);
            this.get = get;
            this.set = set;
        }

        public boolean on() {
            return this.get.getAsBoolean();
        }

        public void set(boolean on) {
            this.set.accept(on);
        }
    }

    /** A number between two ends, in steps. */
    public static final class Slider extends Setting {
        public final double min, max, step;
        private final DoubleSupplier get;
        private final DoubleConsumer set;
        private final Supplier<String> shown;

        public Slider(String name, String description, boolean restart, BooleanSupplier enabled, double min, double max, double step,
                DoubleSupplier get, DoubleConsumer set, Supplier<String> shown) {
            super(name, description, restart, enabled);
            this.min = min;
            this.max = Math.max(min, max);
            this.step = step;
            this.get = get;
            this.set = set;
            this.shown = shown;
        }

        public double value() {
            return this.get.getAsDouble();
        }

        /** Sets the value nearest to a point along the slider, 0 to 1. */
        public void setFraction(double fraction) {
            double value = this.min + Math.max(0, Math.min(1, fraction)) * (this.max - this.min);
            if (this.step > 0) {
                value = this.min + Math.round((value - this.min) / this.step) * this.step;
            }
            this.set.accept(Math.max(this.min, Math.min(this.max, value)));
        }

        public double fraction() {
            return this.max > this.min ? (this.value() - this.min) / (this.max - this.min) : 0;
        }

        /** The value as the mod writes it ("12 chunks", "75%"). */
        public String shown() {
            return this.shown.get();
        }
    }

    /** One of a few named options, stepped through with arrows. */
    public static final class Choice extends Setting {
        private final IntSupplier index;
        private final IntConsumer select;
        private final IntSupplier count;
        private final Supplier<String> shown;

        public Choice(String name, String description, boolean restart, BooleanSupplier enabled, IntSupplier index, IntConsumer select, IntSupplier count, Supplier<String> shown) {
            super(name, description, restart, enabled);
            this.index = index;
            this.select = select;
            this.count = count;
            this.shown = shown;
        }

        /** A choice from a fixed list of names. */
        public static Choice ofNames(String name, String description, boolean restart, BooleanSupplier enabled, IntSupplier index, IntConsumer select, IntFunction<String> names, int count) {
            return new Choice(name, description, restart, enabled, index, select, () -> count, () -> names.apply(Math.max(0, Math.min(count - 1, index.getAsInt()))));
        }

        public void step(int by) {
            int count = this.count.getAsInt();
            if (count > 0) {
                this.select.accept(Math.floorMod(this.index.getAsInt() + by, count));
            }
        }

        public String shown() {
            return this.shown.get();
        }
    }

    /** Typed text or a number. */
    public static final class Text extends Setting {
        private final Supplier<String> get;
        private final Predicate<String> set;

        public Text(String name, String description, boolean restart, BooleanSupplier enabled, Supplier<String> get, Predicate<String> set) {
            super(name, description, restart, enabled);
            this.get = get;
            this.set = set;
        }

        public String value() {
            return this.get.get();
        }

        /** Returns whether the mod accepted it. */
        public boolean set(String value) {
            return this.set.test(value);
        }
    }

    /** A button: opens more of the mod's settings on their own screen, or resets a group. */
    public static final class Action extends Setting {
        /** The button's label, in capitals: "OPEN", "RESET". */
        public final String button;
        private final Runnable run;

        public Action(String name, String description, BooleanSupplier enabled, Runnable run) {
            this(name, description, enabled, "OPEN", run);
        }

        public Action(String name, String description, BooleanSupplier enabled, String button, Runnable run) {
            super(name, description, false, enabled);
            this.button = button;
            this.run = run;
        }

        public void run() {
            this.run.run();
        }
    }

    /** A line of text among the settings, with nothing to change. */
    public static final class Note extends Setting {
        public Note(String text) {
            super(text, "", false, null);
        }
    }
}
