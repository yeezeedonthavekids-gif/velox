package dev.velox.launcher.fake;

import java.nio.file.Files;
import java.nio.file.Path;

/** Stands in for Minecraft in launch tests: records its arguments and echoes them to stdout. */
public final class FakeGame {
    public static void main(String[] args) throws Exception {
        Files.writeString(Path.of("args.txt"), String.join("\n", args) + "\nmaxHeapMb=" + Runtime.getRuntime().maxMemory() / 1048576);
        System.out.println("fake game started with " + String.join(" ", args));
        if (java.util.Arrays.asList(args).contains("--crash")) System.exit(3);
    }
}
