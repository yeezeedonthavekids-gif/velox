package dev.velox.launcher;

import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Minimal logging: stderr plus optional listeners (the GUI log pane). */
public final class Log {
    private static final CopyOnWriteArrayList<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile boolean quiet;

    private Log() {}

    public static void addListener(Consumer<String> l) { LISTENERS.add(l); }
    public static void removeListener(Consumer<String> l) { LISTENERS.remove(l); }
    public static void setQuiet(boolean q) { quiet = q; }

    public static void info(String fmt, Object... args) { emit("", fmt, args); }
    public static void warn(String fmt, Object... args) { emit("WARN: ", fmt, args); }
    public static void error(String fmt, Object... args) { emit("ERROR: ", fmt, args); }

    private static void emit(String prefix, String fmt, Object... args) {
        String msg = prefix + (args.length == 0 ? fmt : String.format(Locale.ROOT, fmt, args));
        if (!quiet) System.err.println(msg);
        for (Consumer<String> l : LISTENERS) l.accept(msg);
    }
}
