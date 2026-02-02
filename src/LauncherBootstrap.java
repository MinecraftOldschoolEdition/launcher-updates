// Minimal bootstrap entrypoint that runs before any Swing/AWT classes load.
//
// This exists specifically to work around Steam Deck Game Mode (gamescope)
// rendering issues where Swing windows show up as a blank white surface when
// the process is started under the default Wayland backend. We need to switch
// to X11 *before* AWT initializes, which requires relaunching the JVM with the
// correct environment. Doing this here keeps ModUpdaterGUI's static
// initializers from touching AWT prior to the relaunch.
public final class LauncherBootstrap {

    public static void main(String[] args) {
        // If we are on Steam Deck / gamescope and not already using X11, relaunch
        // the JVM with GDK_BACKEND=x11 so Swing renders correctly.
        if (relaunchForSteamDeck(args)) {
            return; // child process takes over
        }

        // Once the environment is correct, continue into the real launcher.
        ModUpdaterGUI.main(args);
    }

    /**
     * On Linux with gamescope (Steam Deck game mode), we need GDK_BACKEND=x11
     * set BEFORE Java starts. This method detects if we're missing it and
     * relaunches with the correct environment.
     */
    private static boolean relaunchForSteamDeck(String[] args) {
        // Only applies to Linux
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) return false;

        // Check if GDK_BACKEND is already set to x11
        String gdkBackend = System.getenv("GDK_BACKEND");
        if ("x11".equals(gdkBackend)) return false;

        // Check if we're likely in gamescope (Steam Deck game mode)
        String xdgSession = System.getenv("XDG_SESSION_TYPE");
        String waylandDisplay = System.getenv("WAYLAND_DISPLAY");
        boolean possiblyGamescope = waylandDisplay != null || "wayland".equals(xdgSession);

        // Also check for SteamOS or steam-runtime
        String steamRuntime = System.getenv("STEAM_RUNTIME");
        String steamDeck = System.getenv("SteamDeck");
        boolean isSteamEnvironment = steamRuntime != null || "1".equals(steamDeck);

        if (!possiblyGamescope && !isSteamEnvironment) return false;

        // We need to relaunch with GDK_BACKEND=x11
        try {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";
            String classpath = System.getProperty("java.class.path");

            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-cp");
            cmd.add(classpath);
            cmd.add(LauncherBootstrap.class.getName());
            for (String arg : args) cmd.add(arg);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            // Set the critical environment variable
            pb.environment().put("GDK_BACKEND", "x11");
            pb.environment().put("_JAVA_AWT_WM_NONREPARENTING", "1");

            System.out.println("[Launcher] Relaunching with GDK_BACKEND=x11 for Steam Deck compatibility...");
            Process p = pb.start();
            // Wait for the child process to complete before exiting
            // This ensures Prism Launcher waits for the updater to finish
            int exitCode = p.waitFor();
            System.exit(exitCode);
            return true; // Never reached, but keeps compiler happy
        } catch (Exception e) {
            System.err.println("[Launcher] Failed to relaunch: " + e.getMessage());
            return false;
        }
    }
}

