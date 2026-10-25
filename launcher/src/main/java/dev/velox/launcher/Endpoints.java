package dev.velox.launcher;

/**
 * Every remote service the launcher talks to. They are only overridden in tests, which point
 * them at a local mock server. All are official first-party endpoints (Mojang, Microsoft,
 * Xbox Live, Fabric, Modrinth); the launcher never downloads from anywhere else.
 */
public record Endpoints(
        String versionManifest,
        String javaRuntimes,
        String resources,
        String fabricMeta,
        String modrinthApi,
        String msaDeviceCode,
        String msaToken,
        String xblAuth,
        String xstsAuth,
        String mcLogin,
        String mcEntitlements,
        String mcProfile) {

    public static final Endpoints OFFICIAL = new Endpoints(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json",
            "https://piston-meta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json",
            "https://resources.download.minecraft.net",
            "https://meta.fabricmc.net",
            "https://api.modrinth.com",
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
            "https://user.auth.xboxlive.com/user/authenticate",
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            "https://api.minecraftservices.com/authentication/login_with_xbox",
            "https://api.minecraftservices.com/entitlements/mcstore",
            "https://api.minecraftservices.com/minecraft/profile");

    /** All endpoints rooted at {@code base}, for tests against a single local mock server. */
    public static Endpoints under(String base) {
        return new Endpoints(
                base + "/mc/game/version_manifest_v2.json",
                base + "/java-runtime/all.json",
                base + "/resources",
                base + "/fabric",
                base + "/modrinth",
                base + "/msa/devicecode",
                base + "/msa/token",
                base + "/xbl/authenticate",
                base + "/xsts/authorize",
                base + "/mc/login_with_xbox",
                base + "/mc/entitlements",
                base + "/mc/profile");
    }
}
