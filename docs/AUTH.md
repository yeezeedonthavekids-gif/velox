# Microsoft sign-in

Velox uses Microsoft's official **OAuth 2.0 device code flow**, the same chain every legitimate
launcher uses: Microsoft identity → Xbox Live → XSTS → Minecraft services.

- You sign in on Microsoft's own website (`microsoft.com/link`) by entering a short code. Velox
  never sees or handles your password.
- The Minecraft access token (valid ~24 h) is kept in memory only. It is passed to the game on
  its command line, as with every launcher. Velox redacts it from everything it logs.
- Only the Microsoft **refresh token** is persisted, and only through the operating system's
  protection:
  - Windows: DPAPI (CurrentUser), ciphertext in `%APPDATA%\.velox\account.dpapi`
  - macOS: login Keychain (`security`), service `dev.velox.launcher`
  - Linux: Secret Service (`secret-tool`; GNOME Keyring or KWallet)
  - None of these available: nothing is stored and you sign in each session. Set
    `"rememberAccount": false` in `launcher.json` to force this anywhere.
- Secrets are handed to those tools over stdin, never on a command line.
- `velox logout` deletes the stored token.

## Client ID (required)

Microsoft requires each launcher to identify itself with an Azure application (client) ID, and
Mojang requires that ID to be approved for the Minecraft API. Velox ships **no** client ID:
borrowing another launcher's would misrepresent who is signing in.

1. In the [Azure portal](https://portal.azure.com) → *App registrations* → *New registration*:
   - Supported account types: **Personal Microsoft accounts only**
   - Redirect URI: none needed
2. In the app's *Authentication* page, enable **Allow public client flows** (needed for device code).
3. Request Minecraft API access for the app ID through Mojang's form at
   <https://aka.ms/mce-reviewappid>. Until it is approved, the final step
   (`login_with_xbox`) is rejected even though Microsoft sign-in itself succeeds.
4. Give Velox the ID: `velox login --client-id <id> --save`, or paste it into the launcher window.

Error messages are mapped to plain explanations: no Xbox profile, a child account needing a
family group, region restrictions, an account that doesn't own Java Edition, or an expired
sign-in.
