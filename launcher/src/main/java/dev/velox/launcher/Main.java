package dev.velox.launcher;

import java.awt.GraphicsEnvironment;

public final class Main {
    private Main() {}

    public static void main(String[] args) {
        LauncherPaths paths = LauncherPaths.defaultRoot();
        boolean gui = (args.length == 0 && !GraphicsEnvironment.isHeadless()) || (args.length > 0 && args[0].equals("gui"));
        if (gui) {
            dev.velox.launcher.ui.LauncherWindow.open(paths, Endpoints.OFFICIAL);
            return;
        }
        System.exit(new Cli(System.out, paths, Endpoints.OFFICIAL).run(args));
    }
}
