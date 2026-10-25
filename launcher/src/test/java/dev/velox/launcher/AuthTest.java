package dev.velox.launcher;

import dev.velox.launcher.auth.MicrosoftAuth;
import dev.velox.launcher.net.Http;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthTest {
    MockServer mock;
    final AtomicInteger polls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        mock = new MockServer();
        mock.json("/msa/devicecode", """
                {"device_code":"DEV","user_code":"ABCD-1234","verification_uri":"https://microsoft.com/link","expires_in":900,"interval":5}""");
        mock.route("/msa/token", r -> {
            if (r.body().contains("grant_type=refresh_token")) {
                return r.body().contains("refresh_token=GOOD")
                        ? MockServer.Reply.ok("{\"access_token\":\"MSA2\",\"refresh_token\":\"GOOD2\",\"expires_in\":3600}")
                        : MockServer.Reply.status(400, "{\"error\":\"invalid_grant\",\"error_description\":\"AADSTS70000: expired\\nTrace\"}");
            }
            int n = polls.incrementAndGet();
            if (n == 1) return MockServer.Reply.status(400, "{\"error\":\"authorization_pending\"}");
            if (n == 2) return MockServer.Reply.status(400, "{\"error\":\"slow_down\"}");
            return MockServer.Reply.ok("{\"access_token\":\"MSA\",\"refresh_token\":\"REFRESH\",\"expires_in\":3600}");
        });
        mock.json("/xbl/authenticate", "{\"Token\":\"XBL\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"UHS1\"}]}}");
        mock.json("/xsts/authorize", "{\"Token\":\"XSTS\",\"DisplayClaims\":{\"xui\":[{\"uhs\":\"UHS1\",\"xid\":\"2535400000\"}]}}");
        mock.json("/mc/login_with_xbox", "{\"access_token\":\"MCTOKEN\",\"expires_in\":86400}");
        mock.route("/mc/profile", r -> "Bearer MCTOKEN".equals(r.headers().get("Authorization").get(0))
                ? MockServer.Reply.ok("{\"id\":\"0123456789abcdef0123456789abcdef\",\"name\":\"Steve\"}")
                : MockServer.Reply.status(401, ""));
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void deviceCodeFlowCompletesTheWholeChain() throws Exception {
        List<Long> sleeps = new ArrayList<>();
        MicrosoftAuth auth = new MicrosoftAuth(new Http(1), Endpoints.under(mock.base()), "my-client", sleeps::add);
        List<MicrosoftAuth.DeviceCode> shown = new ArrayList<>();
        MicrosoftAuth.Session s = auth.loginWithDeviceCode(shown::add);

        assertEquals("ABCD-1234", shown.get(0).userCode());
        assertEquals(List.of(5000L, 5000L, 10000L), sleeps, "slow_down adds 5 s to the interval");
        assertEquals("Steve", s.username());
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", s.uuid());
        assertEquals("2535400000", s.xuid());
        assertEquals("MCTOKEN", s.accessToken());
        assertEquals("REFRESH", s.refreshToken());
        assertFalse(s.toString().contains("MCTOKEN"), "tokens never appear in toString");

        String xbl = body("/xbl/authenticate");
        assertTrue(xbl.contains("\"RpsTicket\":\"d=MSA\""));
        assertTrue(body("/xsts/authorize").contains("rp://api.minecraftservices.com/"));
        assertTrue(body("/mc/login_with_xbox").contains("XBL3.0 x=UHS1;XSTS"));
        assertTrue(body("/msa/devicecode").contains("scope=XboxLive.signin+offline_access"));
    }

    @Test
    void refreshTokenFlow() throws Exception {
        MicrosoftAuth auth = new MicrosoftAuth(new Http(1), Endpoints.under(mock.base()), "c", ms -> {});
        assertEquals("GOOD2", auth.refresh("GOOD").refreshToken());
        var e = assertThrows(MicrosoftAuth.AuthException.class, () -> auth.refresh("BAD"));
        assertTrue(e.getMessage().contains("sign in again"));
        assertTrue(e.getMessage().contains("AADSTS70000: expired") && !e.getMessage().contains("Trace"));
    }

    @Test
    void xstsErrorsAreExplained() {
        mock.route("/xsts/authorize", r -> MockServer.Reply.status(401, "{\"XErr\":2148916238}"));
        MicrosoftAuth auth = new MicrosoftAuth(new Http(1), Endpoints.under(mock.base()), "c", ms -> {});
        var e = assertThrows(MicrosoftAuth.AuthException.class, () -> auth.refresh("GOOD"));
        assertTrue(e.getMessage().contains("child account"), e.getMessage());
    }

    @Test
    void accountWithoutGameIsExplained() {
        mock.route("/mc/profile", r -> MockServer.Reply.status(404, "{\"error\":\"NOT_FOUND\"}"));
        MicrosoftAuth auth = new MicrosoftAuth(new Http(1), Endpoints.under(mock.base()), "c", ms -> {});
        var e = assertThrows(MicrosoftAuth.AuthException.class, () -> auth.refresh("GOOD"));
        assertTrue(e.getMessage().contains("does not own Minecraft"));
    }

    @Test
    void declinedSignIn() {
        mock.route("/msa/token", r -> MockServer.Reply.status(400, "{\"error\":\"authorization_declined\"}"));
        MicrosoftAuth auth = new MicrosoftAuth(new Http(1), Endpoints.under(mock.base()), "c", ms -> {});
        assertThrows(MicrosoftAuth.AuthException.class, () -> auth.loginWithDeviceCode(dc -> {}));
    }

    @Test
    void missingClientIdIsAClearError() {
        var e = assertThrows(IllegalArgumentException.class, () -> new MicrosoftAuth(new Http(1), Endpoints.under(mock.base()), " "));
        assertTrue(e.getMessage().contains("docs/AUTH.md"));
    }

    private String body(String path) {
        return mock.requests.stream().filter(r -> r.path().equals(path)).findFirst().orElseThrow().body();
    }
}
