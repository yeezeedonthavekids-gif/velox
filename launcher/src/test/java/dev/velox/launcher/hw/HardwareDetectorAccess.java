package dev.velox.launcher.hw;

/** Test access to the package-private parsers. */
public final class HardwareDetectorAccess {
    private HardwareDetectorAccess() {}

    public record G(String name, long vramMb) {}

    public static G smi(String s) {
        var g = HardwareDetector.parseNvidiaSmi(s);
        return new G(g.name(), g.vramMb());
    }

    public static G wmiPrimary(String json) {
        var g = HardwareDetector.pickPrimary(HardwareDetector.parseWmi(json));
        return new G(g.name(), g.vramMb());
    }

    public static G lspciPrimary(String out) {
        var g = HardwareDetector.pickPrimary(HardwareDetector.parseLspci(out));
        return new G(g.name(), g.vramMb());
    }
}
