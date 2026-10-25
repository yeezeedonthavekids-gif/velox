package dev.velox.launcher.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Stores the Microsoft refresh token using the operating system's protection, never as plain
 * text. Secrets always pass to helper tools over stdin, never on a command line where other
 * processes could read them.
 * <ul>
 *   <li>Windows: DPAPI (CurrentUser scope) via PowerShell; ciphertext saved in the launcher dir</li>
 *   <li>macOS: the login Keychain via {@code security -i}</li>
 *   <li>Linux: the Secret Service (GNOME Keyring / KWallet) via {@code secret-tool}</li>
 * </ul>
 * Without any of these, {@link #none()} is used: nothing is persisted and the user signs in
 * each session.
 */
public interface SecretStore {
    String name();

    void put(String secret) throws IOException;

    Optional<String> get() throws IOException;

    void clear() throws IOException;

    static SecretStore forCurrentOs(Path launcherRoot) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return new Dpapi(launcherRoot.resolve("account.dpapi"));
        if (os.contains("mac")) return new MacKeychain();
        if (Helper.exists("secret-tool")) return new SecretTool();
        return none();
    }

    static SecretStore none() {
        return new SecretStore() {
            public String name() { return "none (sign in every session)"; }
            public void put(String secret) { }
            public Optional<String> get() { return Optional.empty(); }
            public void clear() { }
        };
    }

    final class Dpapi implements SecretStore {
        private final Path file;

        Dpapi(Path file) {
            this.file = file;
        }

        public String name() { return "Windows DPAPI"; }

        private static final String PROTECT = "Add-Type -AssemblyName System.Security;"
                + "$s=[Console]::In.ReadToEnd();"
                + "$b=[Security.Cryptography.ProtectedData]::Protect([Text.Encoding]::UTF8.GetBytes($s),$null,'CurrentUser');"
                + "[Console]::Out.Write([Convert]::ToBase64String($b))";
        private static final String UNPROTECT = "Add-Type -AssemblyName System.Security;"
                + "$s=[Console]::In.ReadToEnd();"
                + "$b=[Security.Cryptography.ProtectedData]::Unprotect([Convert]::FromBase64String($s),$null,'CurrentUser');"
                + "[Console]::Out.Write([Text.Encoding]::UTF8.GetString($b))";

        public void put(String secret) throws IOException {
            String cipher = Helper.run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", PROTECT), secret);
            Files.createDirectories(file.getParent());
            Files.writeString(file, cipher.trim(), StandardCharsets.US_ASCII);
        }

        public Optional<String> get() throws IOException {
            if (!Files.exists(file)) return Optional.empty();
            String cipher = Files.readString(file, StandardCharsets.US_ASCII);
            return Optional.of(Helper.run(List.of("powershell", "-NoProfile", "-NonInteractive", "-Command", UNPROTECT), cipher));
        }

        public void clear() throws IOException { Files.deleteIfExists(file); }
    }

    final class MacKeychain implements SecretStore {
        private static final String SERVICE = "dev.velox.launcher";
        public String name() { return "macOS Keychain"; }

        public void put(String secret) throws IOException {
            if (secret.contains("\"") || secret.contains("\n")) throw new IOException("Unexpected characters in token");
            // `security -i` reads commands from stdin, keeping the secret off the process list.
            Helper.run(List.of("security", "-i"),
                    "add-generic-password -U -a msa -s " + SERVICE + " -w \"" + secret + "\"\n");
        }

        public Optional<String> get() throws IOException {
            try {
                String s = Helper.run(List.of("security", "find-generic-password", "-a", "msa", "-s", SERVICE, "-w"), "");
                return s.isBlank() ? Optional.empty() : Optional.of(s.trim());
            } catch (IOException e) {
                return Optional.empty(); // not found
            }
        }

        public void clear() throws IOException {
            try {
                Helper.run(List.of("security", "delete-generic-password", "-a", "msa", "-s", SERVICE), "");
            } catch (IOException ignored) {
                // nothing stored
            }
        }
    }

    final class SecretTool implements SecretStore {
        public String name() { return "Secret Service (secret-tool)"; }

        public void put(String secret) throws IOException {
            Helper.run(List.of("secret-tool", "store", "--label=Velox launcher sign-in", "service", "dev.velox.launcher", "account", "msa"), secret);
        }

        public Optional<String> get() throws IOException {
            try {
                String s = Helper.run(List.of("secret-tool", "lookup", "service", "dev.velox.launcher", "account", "msa"), "");
                return s.isBlank() ? Optional.empty() : Optional.of(s.trim());
            } catch (IOException e) {
                return Optional.empty();
            }
        }

        public void clear() throws IOException {
            try {
                Helper.run(List.of("secret-tool", "clear", "service", "dev.velox.launcher", "account", "msa"), "");
            } catch (IOException ignored) {
                // nothing stored
            }
        }
    }

    final class Helper {
        private Helper() {}

        static boolean exists(String tool) {
            String path = System.getenv("PATH");
            if (path == null) return false;
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (Files.isExecutable(Path.of(dir, tool))) return true;
            }
            return false;
        }

        static String run(List<String> cmd, String stdin) throws IOException {
            Process p = new ProcessBuilder(cmd).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write(stdin.getBytes(StandardCharsets.UTF_8));
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                if (!p.waitFor(30, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    throw new IOException(cmd.get(0) + " timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
            if (p.exitValue() != 0) {
                // stderr may describe the failure but never contains the secret we sent on stdin.
                throw new IOException(cmd.get(0) + " failed (exit " + p.exitValue() + "): " + err.strip());
            }
            return out;
        }
    }
}
