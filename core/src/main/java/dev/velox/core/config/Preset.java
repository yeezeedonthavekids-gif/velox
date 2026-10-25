package dev.velox.core.config;

public enum Preset {
    /** Highest frame rate; accepts visible quality loss but keeps gameplay information intact. */
    MAX_FPS("Max FPS"),
    /** Stable frame times and clear visibility; keeps anything that shows players or hazards. */
    COMPETITIVE("Competitive"),
    /** Good performance with most of the vanilla look. */
    BALANCED("Balanced"),
    /** Balanced as a base, then the player's own overrides. */
    CUSTOM("Custom");

    public final String displayName;

    Preset(String displayName) {
        this.displayName = displayName;
    }
}
