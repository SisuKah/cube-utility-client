package dev.cubeutilityclient.render;

/** A value that eases towards a target over time, like a CSS transition. */
public final class Motion {
    private float value;
    private long last = System.nanoTime();

    /** Moves towards {@code target}, taking about {@code seconds} to get there. */
    public float towards(float target, float seconds) {
        long now = System.nanoTime();
        float dt = Math.min(0.1f, (now - this.last) / 1e9f);
        this.last = now;
        float k = 1f - (float) Math.exp(-dt * 5f / seconds);
        this.value += (target - this.value) * k;
        return this.value;
    }
}
