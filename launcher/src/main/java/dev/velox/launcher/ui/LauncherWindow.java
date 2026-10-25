package dev.velox.launcher.ui;

import dev.velox.core.config.Preset;
import dev.velox.core.config.PresetEngine;
import dev.velox.core.hw.HardwareProfile;
import dev.velox.core.jvm.JvmArgs;
import dev.velox.launcher.Endpoints;
import dev.velox.launcher.LauncherPaths;
import dev.velox.launcher.Log;
import dev.velox.launcher.Settings;
import dev.velox.launcher.VeloxLauncher;
import dev.velox.launcher.auth.MicrosoftAuth;
import dev.velox.launcher.hw.HardwareDetector;
import dev.velox.launcher.launch.GameProcess;
import dev.velox.launcher.launch.LaunchPlan;
import dev.velox.launcher.mods.ModCatalog;
import dev.velox.launcher.mods.ModResolver;
import dev.velox.launcher.net.DownloadQueue;
import dev.velox.launcher.net.Http;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The launcher window. Plain Swing with a small dark theme: no web runtime, no extra
 * dependencies, and nothing left running once the game starts except a thread forwarding its log.
 */
public final class LauncherWindow {
    static final Color BG = new Color(0x0F1115);
    static final Color PANEL = new Color(0x171A21);
    static final Color BORDER = new Color(0x262B35);
    static final Color TEXT = new Color(0xE6E6E6);
    static final Color MUTED = new Color(0x9AA3AD);
    static final Color ACCENT = new Color(0x4ADE80);

    private final LauncherPaths paths;
    private final VeloxLauncher launcher;
    private final Settings settings;
    private final HardwareProfile hw;

    private final JFrame frame = new JFrame("Velox");
    private final JComboBox<Preset> preset = new JComboBox<>(Preset.values());
    private final JComboBox<ModCatalog.Profile> profile = new JComboBox<>(ModCatalog.Profile.values());
    private final JComboBox<String> gc = new JComboBox<>(new String[]{"auto", "zgc", "g1"});
    private final JSpinner heap = new JSpinner(new SpinnerNumberModel(0, 0, 32768, 512));
    private final JTextField version = new JTextField();
    private final JTextField instance = new JTextField();
    private final JTextField gameDir = new JTextField();
    private final JTextField javaPath = new JTextField();
    private final JTextField jvmArgs = new JTextField();
    private final JTextField clientId = new JTextField();
    private final JLabel installed = new JLabel();
    private final JLabel recommendation = new JLabel();
    private final JTextArea log = new JTextArea();
    private final JProgressBar progress = new JProgressBar();
    private final List<JButton> actions = new ArrayList<>();

    private LauncherWindow(LauncherPaths paths, Endpoints endpoints) throws IOException {
        this.paths = paths;
        this.launcher = new VeloxLauncher(new Http(), endpoints, paths);
        this.settings = Settings.load(paths.settingsFile());
        this.hw = HardwareDetector.detect();
    }

    public static void open(LauncherPaths paths, Endpoints endpoints) {
        SwingUtilities.invokeLater(() -> {
            try {
                new LauncherWindow(paths, endpoints).build().setVisible(true);
            } catch (IOException e) {
                JOptionPane.showMessageDialog(null, e.getMessage(), "Velox", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /** Builds the window without showing it (used for screenshots and tests). */
    public static JFrame buildForPreview(LauncherPaths paths, Endpoints endpoints) throws IOException {
        return new LauncherWindow(paths, endpoints).build();
    }

    JFrame build() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        java.net.URL icon = LauncherWindow.class.getResource("/velox/icon.png");
        if (icon != null) frame.setIconImage(new ImageIcon(icon).getImage());
        JPanel root = new JPanel(new BorderLayout(0, 14));
        root.setBackground(BG);
        root.setBorder(new EmptyBorder(18, 20, 18, 20));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        JLabel title = new JLabel("Velox");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 26f));
        title.setForeground(TEXT);
        header.add(title, BorderLayout.WEST);
        installed.setForeground(MUTED);
        installed.setHorizontalAlignment(SwingConstants.RIGHT);
        header.add(installed, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        JPanel form = card(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 6, 4, 6);
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        row = field(form, c, row, "Preset", preset, "Max FPS · Competitive · Balanced · Custom (edited in game)");
        row = field(form, c, row, "Mod profile", profile, "Baseline = Fabric API + Velox only, for A/B benchmarks");
        row = field(form, c, row, "Minecraft", version, "\"auto\" = newest release that Sodium and Fabric API support");
        row = field(form, c, row, "Memory (MiB)", heap, "0 = recommended for this PC");
        row = field(form, c, row, "Garbage collector", gc, "auto: G1 for Max FPS; ZGC for stability (6+ threads)");
        row = field(form, c, row, "Instance", instance, "Folder name under instances/");
        row = field(form, c, row, "Game directory", gameDir, "Optional: overrides the instance folder");
        row = field(form, c, row, "Java", javaPath, "Optional: blank uses Mojang's official runtime");
        row = field(form, c, row, "Extra JVM args", jvmArgs, "Space separated");
        row = field(form, c, row, "Microsoft client ID", clientId, "Required for sign-in; see docs/AUTH.md");
        c.gridx = 0;
        c.gridy = row;
        c.gridwidth = 3;
        recommendation.setForeground(MUTED);
        form.add(recommendation, c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(button("Install / Update", false, this::doInstall));
        buttons.add(button("Sign in", false, this::doSignIn));
        buttons.add(button("Play", true, this::doLaunch));

        JPanel center = new JPanel(new BorderLayout(0, 10));
        center.setOpaque(false);
        center.add(form, BorderLayout.CENTER);
        center.add(buttons, BorderLayout.SOUTH);
        root.add(center, BorderLayout.CENTER);

        log.setEditable(false);
        log.setBackground(PANEL);
        log.setForeground(new Color(0xB8C0C9));
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBorder(new EmptyBorder(8, 10, 8, 10));
        JScrollPane sp = new JScrollPane(log);
        sp.setBorder(BorderFactory.createLineBorder(BORDER));
        sp.setPreferredSize(new Dimension(10, 150));
        progress.setStringPainted(true);
        progress.setString("Idle");
        progress.setForeground(ACCENT);
        progress.setBackground(PANEL);
        progress.setBorder(BorderFactory.createLineBorder(BORDER));
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setOpaque(false);
        bottom.add(sp, BorderLayout.CENTER);
        bottom.add(progress, BorderLayout.SOUTH);
        root.add(bottom, BorderLayout.SOUTH);

        preset.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                return super.getListCellRendererComponent(list, value instanceof Preset p ? p.displayName : value, index, selected, focus);
            }
        });
        profile.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected, boolean focus) {
                String label = value == ModCatalog.Profile.BASELINE ? "Baseline (benchmark)" : "Optimized";
                return super.getListCellRendererComponent(list, label, index, selected, focus);
            }
        });
        loadIntoForm();
        preset.addActionListener(e -> updateRecommendation());
        heap.addChangeListener(e -> updateRecommendation());
        gc.addActionListener(e -> updateRecommendation());
        Log.addListener(msg -> SwingUtilities.invokeLater(() -> {
            log.append(msg + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        }));
        Log.info("Detected: %s", hw.describe());

        frame.setContentPane(root);
        frame.setSize(860, 760);
        frame.setLocationRelativeTo(null);
        return frame;
    }

    private static JPanel card(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setBackground(PANEL);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), new EmptyBorder(10, 10, 10, 10)));
        return p;
    }

    private static int field(JPanel form, GridBagConstraints c, int row, String label, JComponent input, String hint) {
        JLabel l = new JLabel(label);
        l.setForeground(TEXT);
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.gridwidth = 1;
        form.add(l, c);
        style(input);
        c.gridx = 1; c.weightx = 1;
        form.add(input, c);
        JLabel h = new JLabel(hint);
        h.setForeground(MUTED);
        h.setFont(h.getFont().deriveFont(11f));
        c.gridx = 2; c.weightx = 0;
        form.add(h, c);
        return row + 1;
    }

    private static void style(JComponent input) {
        input.setBackground(BG);
        input.setForeground(TEXT);
        if (input instanceof JTextField tf) {
            tf.setCaretColor(TEXT);
            tf.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(BORDER), new EmptyBorder(4, 6, 4, 6)));
            tf.setColumns(22);
        }
        if (input instanceof JSpinner sp && sp.getEditor() instanceof JSpinner.DefaultEditor de) {
            de.getTextField().setBackground(BG);
            de.getTextField().setForeground(TEXT);
        }
    }

    private JButton button(String text, boolean primary, Runnable action) {
        JButton b = new JButton(text);
        b.setFocusPainted(false);
        b.setBackground(primary ? ACCENT : BORDER);
        b.setForeground(primary ? Color.BLACK : TEXT);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setBorder(new EmptyBorder(8, primary ? 26 : 14, 8, primary ? 26 : 14));
        b.setOpaque(true);
        b.addActionListener(e -> action.run());
        actions.add(b);
        return b;
    }

    private void loadIntoForm() {
        preset.setSelectedItem(settings.preset);
        profile.setSelectedItem(settings.profile);
        gc.setSelectedItem(settings.gc);
        heap.setValue(settings.heapMb);
        version.setText(settings.gameVersion);
        instance.setText(settings.instance);
        gameDir.setText(settings.gameDirOverride == null ? "" : settings.gameDirOverride);
        javaPath.setText(settings.javaPath == null ? "" : settings.javaPath);
        jvmArgs.setText(String.join(" ", settings.extraJvmArgs));
        clientId.setText(settings.msaClientId == null ? "" : settings.msaClientId);
        refreshInstalled();
        updateRecommendation();
    }

    private void saveFromForm() throws IOException {
        settings.preset = (Preset) preset.getSelectedItem();
        settings.profile = (ModCatalog.Profile) profile.getSelectedItem();
        settings.gc = (String) gc.getSelectedItem();
        settings.heapMb = (Integer) heap.getValue();
        if (settings.heapMb != 0 && settings.heapMb < 1024) throw new IOException("Memory below 1024 MiB will not run Minecraft");
        settings.gameVersion = version.getText().isBlank() ? "auto" : version.getText().trim();
        paths.instance(instance.getText().trim()); // validates
        settings.instance = instance.getText().trim();
        settings.gameDirOverride = blankToNull(gameDir.getText());
        settings.javaPath = blankToNull(javaPath.getText());
        settings.extraJvmArgs = jvmArgs.getText().isBlank() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(jvmArgs.getText().trim().split("\\s+")));
        settings.msaClientId = blankToNull(clientId.getText());
        settings.save(paths.settingsFile());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private void refreshInstalled() {
        try {
            VeloxLauncher.InstallState st = VeloxLauncher.readState(settings.gameDir(paths));
            if (st == null) {
                installed.setText("Not installed");
            } else {
                installed.setText(String.format(Locale.ROOT, "<html><div style='text-align:right'>Minecraft %s · Fabric %s · Java %d<br>%d mods (%s)</div></html>",
                        st.gameVersion, st.loaderVersion, st.javaMajor, st.mods.size(), st.profile.name().toLowerCase(Locale.ROOT)));
            }
        } catch (IOException | RuntimeException e) {
            installed.setText("Install state unreadable");
        }
    }

    private void updateRecommendation() {
        Preset p = (Preset) preset.getSelectedItem();
        var gs = PresetEngine.resolve(p, hw, null);
        int heapMb = (Integer) heap.getValue();
        int rec = JvmArgs.recommendedHeapMb(hw.totalRamMb(), gs.renderDistance);
        int effective = heapMb > 0 ? heapMb : rec;
        JvmArgs.Gc g = switch ((String) gc.getSelectedItem()) {
            case "zgc" -> JvmArgs.Gc.ZGC;
            case "g1" -> JvmArgs.Gc.G1;
            default -> JvmArgs.recommendedGc(hw, effective, 25, p == Preset.MAX_FPS);
        };
        String warn = heapMb > hw.totalRamMb() / 2 ? " · <b>more than half your RAM; not recommended</b>" : "";
        recommendation.setText(String.format(Locale.ROOT, "<html>%s → render distance %d, %d MiB heap (recommended %d), %s%s<br>Hardware tier: %s</html>",
                p.displayName, gs.renderDistance, effective, rec, g, warn, hw.tier()));
    }

    /** Runs {@code task} off the UI thread with the buttons disabled, reporting errors in a dialog. */
    private void background(String what, IoTask task) {
        try {
            saveFromForm();
        } catch (IOException | IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Velox", JOptionPane.WARNING_MESSAGE);
            return;
        }
        actions.forEach(b -> b.setEnabled(false));
        progress.setIndeterminate(true);
        progress.setString(what + "…");
        Thread t = new Thread(() -> {
            String result = "Done";
            try {
                task.run();
            } catch (IOException | RuntimeException e) {
                Log.error("%s", e.getMessage());
                result = "Failed";
                String msg = e.getMessage();
                SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, msg, what + " failed", JOptionPane.ERROR_MESSAGE));
            } finally {
                String r = result;
                SwingUtilities.invokeLater(() -> {
                    actions.forEach(b -> b.setEnabled(true));
                    progress.setIndeterminate(false);
                    progress.setValue(0);
                    progress.setString(r);
                    refreshInstalled();
                });
            }
        }, "velox-" + what.toLowerCase(Locale.ROOT));
        t.setDaemon(true);
        t.start();
    }

    private interface IoTask {
        void run() throws IOException;
    }

    private DownloadQueue.Progress progressBar() {
        return (done, total, bytes) -> SwingUtilities.invokeLater(() -> {
            progress.setIndeterminate(false);
            progress.setMaximum(Math.max(1, total));
            progress.setValue(done);
            progress.setString(String.format(Locale.ROOT, "%d / %d files · %.1f MiB", done, total, bytes / 1048576.0));
        });
    }

    private Path bundledMod() throws IOException {
        return VeloxLauncher.findBundledMod(paths.root().resolve("cache"));
    }

    private void doInstall() {
        background("Install", () -> launcher.install(settings, hw, bundledMod(), progressBar()));
    }

    private void doSignIn() {
        background("Sign in", () -> {
            MicrosoftAuth.Session s = launcher.signIn(settings, deviceCodeDialog(), true);
            Log.info("Signed in as %s", s.username());
        });
    }

    private void doLaunch() {
        background("Launch", () -> {
            Path dir = settings.gameDir(paths);
            VeloxLauncher.InstallState st = VeloxLauncher.readState(dir);
            if (st == null) st = launcher.install(settings, hw, bundledMod(), progressBar());
            else VeloxLauncher.applyPreset(settings, hw, dir, new ModResolver.Plan(st.gameVersion, st.mods, st.skipped));
            VeloxLauncher.JavaChoice java = launcher.java(settings, st, progressBar());
            MicrosoftAuth.Session s = launcher.signIn(settings, deviceCodeDialog(), false);
            LaunchPlan plan = launcher.plan(settings, hw, st, java,
                    new LaunchPlan.Identity(s.username(), s.uuid(), s.xuid(), s.accessToken(), settings.msaClientId), true);
            SwingUtilities.invokeLater(() -> frame.setState(Frame.ICONIFIED));
            GameProcess.Exit exit = GameProcess.run(plan, false);
            SwingUtilities.invokeLater(() -> frame.setState(Frame.NORMAL));
            if (exit.code() != 0) {
                throw new IOException("Minecraft exited with code " + exit.code()
                        + (exit.crashReport() != null ? "\nCrash report: " + exit.crashReport() : "")
                        + "\n\n" + lastLines(exit.tail(), 12));
            }
        });
    }

    private static String lastLines(String s, int n) {
        String[] lines = s.split("\n");
        return String.join("\n", Arrays.copyOfRange(lines, Math.max(0, lines.length - n), lines.length));
    }

    private Consumer<MicrosoftAuth.DeviceCode> deviceCodeDialog() {
        return dc -> SwingUtilities.invokeLater(() -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(dc.userCode()), null);
            Log.info("Sign in at %s with code %s (copied to clipboard)", dc.verificationUri(), dc.userCode());
            int choice = JOptionPane.showOptionDialog(frame,
                    "<html>Open <b>" + dc.verificationUri() + "</b> and enter:<br><br><span style='font-size:20px'><b>"
                            + dc.userCode() + "</b></span><br><br>The code is copied to your clipboard. Velox never sees your password.</html>",
                    "Sign in with Microsoft", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null,
                    new String[]{"Open browser", "Close"}, "Open browser");
            if (choice == 0 && Desktop.isDesktopSupported()) {
                try {
                    Desktop.getDesktop().browse(URI.create(dc.verificationUri()));
                } catch (IOException | RuntimeException e) {
                    Log.warn("Could not open a browser: %s", e.getMessage());
                }
            }
        });
    }
}
