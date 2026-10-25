package dev.velox.launcher.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.velox.launcher.Endpoints;
import dev.velox.launcher.net.Http;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Microsoft account sign-in using the OAuth 2.0 device code flow. The user signs in on
 * Microsoft's own page in their browser, so the launcher never sees a password:
 * <ol>
 *   <li>Microsoft identity platform: device code, then an access + refresh token
 *       (scope {@code XboxLive.signin offline_access})</li>
 *   <li>Xbox Live user token, then an XSTS token for {@code rp://api.minecraftservices.com/}</li>
 *   <li>Minecraft services: game access token, entitlement check, profile</li>
 * </ol>
 * Requires an Azure application (public client) whose ID Mojang has approved for the Minecraft
 * API; see docs/AUTH.md. Velox ships no client ID; the user configures their own.
 */
public final class MicrosoftAuth {
    private static final String SCOPE = "XboxLive.signin offline_access";

    private final Http http;
    private final Endpoints ep;
    private final String clientId;
    private final Sleeper sleeper;

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public MicrosoftAuth(Http http, Endpoints ep, String clientId) {
        this(http, ep, clientId, Thread::sleep);
    }

    public MicrosoftAuth(Http http, Endpoints ep, String clientId, Sleeper sleeper) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("No Microsoft client ID configured. See docs/AUTH.md for how to register one.");
        }
        this.http = http;
        this.ep = ep;
        this.clientId = clientId;
        this.sleeper = sleeper;
    }

    public static final class AuthException extends IOException {
        public AuthException(String message) {
            super(message);
        }
    }

    /** What to show the user: "open {@code verificationUri} and enter {@code userCode}". */
    public record DeviceCode(String userCode, String verificationUri, String message) {}

    /**
     * A signed-in game session. {@code accessToken} lives only in memory and goes to the game
     * process. {@code refreshToken} is the long-lived MSA token, kept only in a {@link SecretStore}.
     */
    public record Session(String username, String uuid, String xuid, String accessToken, Instant expiresAt,
                          String refreshToken) {
        @Override
        public String toString() {
            return "Session[" + username + ", " + uuid + "]"; // never print tokens
        }
    }

    public Session loginWithDeviceCode(Consumer<DeviceCode> prompt) throws IOException {
        Http.Response r = http.postForm(ep.msaDeviceCode(), Map.of("client_id", clientId, "scope", SCOPE));
        if (r.status() != 200) throw new AuthException("Microsoft refused the device-code request (HTTP " + r.status() + "): " + oauthError(r));
        JsonObject dc = r.json().getAsJsonObject();
        String deviceCode = dc.get("device_code").getAsString();
        long intervalMs = dc.has("interval") ? dc.get("interval").getAsLong() * 1000 : 5000;
        long deadline = System.currentTimeMillis() + dc.get("expires_in").getAsLong() * 1000;
        prompt.accept(new DeviceCode(dc.get("user_code").getAsString(), dc.get("verification_uri").getAsString(),
                dc.has("message") ? dc.get("message").getAsString() : null));

        while (true) {
            if (System.currentTimeMillis() > deadline) throw new AuthException("Sign-in code expired; please try again");
            try {
                sleeper.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AuthException("Sign-in cancelled");
            }
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "urn:ietf:params:oauth:grant-type:device_code");
            form.put("client_id", clientId);
            form.put("device_code", deviceCode);
            Http.Response t = http.postForm(ep.msaToken(), form);
            if (t.status() == 200) return finish(t.json().getAsJsonObject());
            String err = errorCode(t);
            switch (err) {
                case "authorization_pending" -> { }
                case "slow_down" -> intervalMs += 5000;
                case "authorization_declined" -> throw new AuthException("Sign-in was declined");
                case "expired_token" -> throw new AuthException("Sign-in code expired; please try again");
                default -> throw new AuthException("Microsoft sign-in failed: " + oauthError(t));
            }
        }
    }

    public Session refresh(String refreshToken) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("refresh_token", refreshToken);
        form.put("scope", SCOPE);
        Http.Response t = http.postForm(ep.msaToken(), form);
        if (t.status() != 200) throw new AuthException("Saved sign-in is no longer valid; please sign in again (" + oauthError(t) + ")");
        return finish(t.json().getAsJsonObject());
    }

    private Session finish(JsonObject msa) throws IOException {
        String msaToken = msa.get("access_token").getAsString();
        String refresh = msa.has("refresh_token") ? msa.get("refresh_token").getAsString() : null;

        JsonObject xblReq = new JsonObject();
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msaToken);
        xblReq.add("Properties", props);
        xblReq.addProperty("RelyingParty", "http://auth.xboxlive.com");
        xblReq.addProperty("TokenType", "JWT");
        JsonObject xbl = http.postJson(ep.xblAuth(), xblReq, Map.of()).getAsJsonObject();
        String xblToken = xbl.get("Token").getAsString();
        String uhs = firstXui(xbl).get("uhs").getAsString();

        JsonObject xstsReq = new JsonObject();
        JsonObject xp = new JsonObject();
        xp.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);
        xp.add("UserTokens", userTokens);
        xstsReq.add("Properties", xp);
        xstsReq.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        xstsReq.addProperty("TokenType", "JWT");
        JsonObject xsts;
        try {
            xsts = http.postJson(ep.xstsAuth(), xstsReq, Map.of()).getAsJsonObject();
        } catch (Http.HttpStatusException e) {
            throw new AuthException(xstsMessage(e));
        }
        String xstsToken = xsts.get("Token").getAsString();
        JsonObject xui = firstXui(xsts);
        String xuid = xui.has("xid") ? xui.get("xid").getAsString() : null;

        JsonObject mcReq = new JsonObject();
        mcReq.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        JsonObject mc = http.postJson(ep.mcLogin(), mcReq, Map.of()).getAsJsonObject();
        String mcToken = mc.get("access_token").getAsString();
        Instant expires = Instant.now().plusSeconds(mc.has("expires_in") ? mc.get("expires_in").getAsLong() : 3600);
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + mcToken);

        JsonObject profile;
        try {
            profile = com.google.gson.JsonParser.parseString(http.getString(ep.mcProfile(), bearer)).getAsJsonObject();
        } catch (Http.HttpStatusException e) {
            if (e.status == 404) {
                throw new AuthException("This Microsoft account does not own Minecraft: Java Edition (or has not created a profile yet)");
            }
            throw e;
        }
        return new Session(profile.get("name").getAsString(), dashed(profile.get("id").getAsString()), xuid,
                mcToken, expires, refresh);
    }

    private static JsonObject firstXui(JsonObject xboxResponse) throws AuthException {
        JsonElement claims = xboxResponse.get("DisplayClaims");
        if (claims == null) throw new AuthException("Xbox Live response had no user claims");
        JsonArray xui = claims.getAsJsonObject().getAsJsonArray("xui");
        if (xui == null || xui.isEmpty()) throw new AuthException("Xbox Live response had no user claims");
        return xui.get(0).getAsJsonObject();
    }

    static String xstsMessage(Http.HttpStatusException e) {
        String code = "";
        try {
            JsonObject o = com.google.gson.JsonParser.parseString(e.body).getAsJsonObject();
            if (o.has("XErr")) code = o.get("XErr").getAsString();
        } catch (RuntimeException ignored) {
            // body was not JSON
        }
        return switch (code) {
            case "2148916233" -> "This Microsoft account has no Xbox profile. Sign in once at xbox.com to create one, then retry.";
            case "2148916235" -> "Xbox Live is not available in this account's country/region.";
            case "2148916236", "2148916237" -> "This account needs adult verification on xbox.com before it can sign in.";
            case "2148916238" -> "This is a child account; an adult must add it to a Microsoft family group first.";
            default -> "Xbox Live authorization failed (HTTP " + e.status + (code.isEmpty() ? "" : ", XErr " + code) + ")";
        };
    }

    private static String errorCode(Http.Response r) {
        try {
            JsonObject o = r.json().getAsJsonObject();
            return o.has("error") ? o.get("error").getAsString() : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String oauthError(Http.Response r) {
        try {
            JsonObject o = r.json().getAsJsonObject();
            String e = o.has("error") ? o.get("error").getAsString() : "unknown_error";
            String d = o.has("error_description") ? o.get("error_description").getAsString().lines().findFirst().orElse("") : "";
            return d.isEmpty() ? e : e + ": " + d;
        } catch (RuntimeException ex) {
            return "HTTP " + r.status();
        }
    }

    static String dashed(String uuid) {
        if (uuid.length() != 32) return uuid;
        return uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16) + "-"
                + uuid.substring(16, 20) + "-" + uuid.substring(20);
    }
}
