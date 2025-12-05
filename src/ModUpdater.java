import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-platform Java CLI updater for Prism/MultiMC instances.
 *
 * Features:
 * - Fetches latest GitHub release, selects asset by regex.
 * - Updates either mods directory or client jar (legacy installs).
 * - Creates backups of replaced files and supports non-interactive mode.
 *
 * Build (Java 8+):
 *   javac -encoding UTF-8 -d out src/ModUpdater.java
 *   jar cfe mod-updater.jar ModUpdater -C out .
 *
 * Example:
 *   java -jar mod-updater.jar --repo YourOrg/YourRepo \
 *     --assetRegex "YourMod-.*\\.jar" --mode mods --minecraftDir "%MC_DIR%" --yes
 */
public final class ModUpdater {

    private static final int HTTP_TIMEOUT_MS = 15000;
    private static final String GITHUB_API_LATEST = "https://api.github.com/repos/%s/releases/latest";

    public static void main(String[] args) {
        try {
            Map<String, String> opts = parseArgs(args);

            String repo = required(opts, "--repo");
            String assetRegex = required(opts, "--assetRegex");
            String mode = opts.getOrDefault("--mode", "mods");
            boolean yes = opts.containsKey("--yes") || opts.containsKey("-y") || opts.containsKey("--auto");
            boolean dryRun = opts.containsKey("--dryRun");

            Path minecraftDir = resolveMinecraftDir(
                opts.get("--minecraftDir"),
                opts.get("--instanceDir"),
                getenv("MC_DIR")
            );

            if (minecraftDir == null) {
                throw new IllegalArgumentException("Unable to resolve Minecraft directory. Provide --minecraftDir, --instanceDir, or MC_DIR env var.");
            }

            LatestRelease release = fetchLatestRelease(repo);
            ReleaseAsset asset = selectAsset(release.assets, assetRegex);
            if (asset == null) {
                throw new IllegalStateException("No asset matches regex '" + assetRegex + "' in latest release.");
            }

            log("Latest: " + release.tag);
            log("Asset:  " + asset.name);

            if ("mods".equalsIgnoreCase(mode)) {
                Path modsDir = minecraftDir.resolve("mods");
                ensureDir(modsDir);

                Path existing = findExistingMatching(modsDir, assetRegex);
                if (existing != null && existing.getFileName().toString().equals(asset.name)) {
                    log("Already up to date: " + existing);
                    return;
                }

                if (!yes) {
                    if (!confirm("Update mod in '" + modsDir + "'?")) return;
                }

                if (dryRun) {
                    log("[dry-run] Would download and install to: " + modsDir.resolve(asset.name));
                    return;
                }

                Path downloaded = downloadToTemp(asset.url, asset.name);

                if (existing != null) {
                    Path backup = withUniqueSuffix(existing, ".bak");
                    log("Backing up existing -> " + backup);
                    Files.move(existing, backup, StandardCopyOption.REPLACE_EXISTING);
                }

                Path dest = modsDir.resolve(asset.name);
                log("Installing -> " + dest);
                Files.move(downloaded, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                log("Done.");
            } else if ("clientJar".equalsIgnoreCase(mode)) {
                String clientJarArg = opts.get("--clientJarPath");
                Path clientJarPath = resolveClientJarPath(minecraftDir, clientJarArg);
                if (clientJarPath == null) {
                    throw new IllegalArgumentException("Cannot resolve client jar path. Pass --clientJarPath or ensure 'bin/minecraft.jar' exists under " + minecraftDir);
                }

                log("Target client jar: " + clientJarPath);

                if (!yes) {
                    if (!confirm("Replace client jar? A backup will be created.")) return;
                }

                if (dryRun) {
                    log("[dry-run] Would replace client jar at: " + clientJarPath);
                    return;
                }

                Path downloaded = downloadToTemp(asset.url, asset.name);
                Path backup = withUniqueSuffix(clientJarPath, ".bak");
                log("Backing up -> " + backup);
                Files.copy(clientJarPath, backup, StandardCopyOption.REPLACE_EXISTING);
                log("Updating client jar...");
                Files.move(downloaded, clientJarPath, StandardCopyOption.REPLACE_EXISTING);
                log("Client jar updated.");
            } else {
                throw new IllegalArgumentException("Unsupported --mode: " + mode);
            }
        } catch (Exception e) {
            logErr(e.getMessage());
            e.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--yes".equals(a) || "-y".equals(a) || "--dryRun".equals(a) || "--auto".equals(a)) {
                map.put(a, "true");
            } else if (a.startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + a);
                map.put(a, args[++i]);
            } else {
                throw new IllegalArgumentException("Unknown argument: " + a);
            }
        }
        return map;
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.trim().isEmpty()) throw new IllegalArgumentException("Missing required option: " + key);
        return v;
    }

    private static Path resolveMinecraftDir(String minecraftDirArg, String instanceDirArg, String mcDirEnv) {
        try {
            if (minecraftDirArg != null) {
                Path p = Paths.get(minecraftDirArg);
                if (Files.isDirectory(p)) return p.toAbsolutePath();
            }
            if (mcDirEnv != null && !mcDirEnv.trim().isEmpty()) {
                Path p = Paths.get(mcDirEnv);
                if (Files.isDirectory(p)) return p.toAbsolutePath();
            }
            if (instanceDirArg != null) {
                Path base = Paths.get(instanceDirArg);
                Path candidate1 = base.resolve(".minecraft");
                Path candidate2 = base.resolve("minecraft");
                if (Files.isDirectory(candidate1)) return candidate1.toAbsolutePath();
                if (Files.isDirectory(candidate2)) return candidate2.toAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Path resolveClientJarPath(Path minecraftDir, String clientJarArg) {
        try {
            if (clientJarArg != null) {
                Path p = Paths.get(clientJarArg);
                if (Files.isRegularFile(p)) return p.toAbsolutePath();
            }
            if (minecraftDir != null) {
                Path p = minecraftDir.resolve("bin").resolve("minecraft.jar");
                if (Files.isRegularFile(p)) return p.toAbsolutePath();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void ensureDir(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) Files.createDirectories(dir);
    }

    private static Path withUniqueSuffix(Path path, String suffix) {
        String name = path.getFileName().toString();
        String stamp = String.valueOf(System.currentTimeMillis());
        Path parent = path.getParent();
        return parent.resolve(name + suffix + "." + stamp);
    }

    private static Path findExistingMatching(Path dir, String assetRegex) throws IOException {
        final Pattern p = Pattern.compile(assetRegex);
        try {
            File[] files = dir.toFile().listFiles();
            if (files == null) return null;
            Path newest = null;
            long newestTs = Long.MIN_VALUE;
            for (File f : files) {
                if (f.isFile()) {
                    Matcher m = p.matcher(f.getName());
                    if (m.find()) {
                        long ts = f.lastModified();
                        if (ts > newestTs) {
                            newestTs = ts;
                            newest = f.toPath();
                        }
                    }
                }
            }
            return newest;
        } catch (SecurityException se) {
            return null;
        }
    }

    private static LatestRelease fetchLatestRelease(String repo) throws IOException {
        // Try /releases/latest first
        String url = String.format(GITHUB_API_LATEST, repo);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "ModUpdater/1.0");
        String token = getenv("GITHUB_TOKEN");
        if (token != null && !token.trim().isEmpty()) {
            conn.setRequestProperty("Authorization", "token " + token.trim());
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String body = readAll(in);
        
        // If /releases/latest returns 404, fall back to /releases and pick the first one
        if (code == 404) {
            String fallbackUrl = "https://api.github.com/repos/" + repo + "/releases";
            HttpURLConnection fallbackConn = (HttpURLConnection) new URL(fallbackUrl).openConnection();
            fallbackConn.setConnectTimeout(HTTP_TIMEOUT_MS);
            fallbackConn.setReadTimeout(HTTP_TIMEOUT_MS);
            fallbackConn.setRequestMethod("GET");
            fallbackConn.setRequestProperty("Accept", "application/vnd.github+json");
            fallbackConn.setRequestProperty("User-Agent", "ModUpdater/1.0");
            if (token != null && !token.trim().isEmpty()) {
                fallbackConn.setRequestProperty("Authorization", "token " + token.trim());
            }
            int fallbackCode = fallbackConn.getResponseCode();
            InputStream fallbackIn = fallbackCode >= 200 && fallbackCode < 300 ? fallbackConn.getInputStream() : fallbackConn.getErrorStream();
            String fallbackBody = readAll(fallbackIn);
            if (fallbackCode < 200 || fallbackCode >= 300) {
                throw new IOException("GitHub API error: HTTP " + fallbackCode + "\n" + fallbackBody);
            }
            return parseFirstReleaseFromArray(fallbackBody);
        }
        
        if (code < 200 || code >= 300) {
            throw new IOException("GitHub API error: HTTP " + code + "\n" + body);
        }
        return parseLatestRelease(body);
    }
    
    private static LatestRelease parseFirstReleaseFromArray(String json) {
        int start = json.indexOf('[');
        if (start < 0) return parseLatestRelease(json);
        int braceStart = json.indexOf('{', start);
        if (braceStart < 0) return new LatestRelease();
        int depth = 0;
        int braceEnd = -1;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    braceEnd = i;
                    break;
                }
            }
        }
        if (braceEnd < 0) return new LatestRelease();
        String firstRelease = json.substring(braceStart, braceEnd + 1);
        return parseLatestRelease(firstRelease);
    }

    private static String readAll(InputStream in) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) != -1) {
            sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static LatestRelease parseLatestRelease(String json) {
        LatestRelease r = new LatestRelease();
        r.tag = extractString(json, "\"tag_name\"\\s*:\\s*\"(.*?)\"");
        r.assets = extractAssets(json);
        return r;
    }

    private static String extractString(String text, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.DOTALL).matcher(text);
        if (m.find()) return unescapeJson(m.group(1));
        return null;
    }

    private static List<ReleaseAsset> extractAssets(String json) {
        List<ReleaseAsset> list = new ArrayList<ReleaseAsset>();
        // Find the assets array content
        int idx = json.indexOf("\"assets\"");
        if (idx < 0) return list;
        int startArray = json.indexOf('[', idx);
        if (startArray < 0) return list;
        int endArray = findMatchingBracket(json, startArray);
        if (endArray < 0) return list;
        String assetsArray = json.substring(startArray + 1, endArray);

        Pattern objPat = Pattern.compile("\\{(.*?)\\}", Pattern.DOTALL);
        Matcher m = objPat.matcher(assetsArray);
        while (m.find()) {
            String obj = m.group(1);
            String name = extractString(obj, "\"name\"\\s*:\\s*\"(.*?)\"");
            String url = extractString(obj, "\"browser_download_url\"\\s*:\\s*\"(.*?)\"");
            if (name != null && url != null) {
                ReleaseAsset a = new ReleaseAsset();
                a.name = name;
                a.url = url;
                list.add(a);
            }
        }
        return list;
    }

    private static int findMatchingBracket(String s, int openIdx) {
        int depth = 0;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String unescapeJson(String s) {
        // Minimal unescape for common sequences
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '"' || n == '\\' || n == '/') { out.append(n); i++; }
                else if (n == 'b') { out.append('\b'); i++; }
                else if (n == 'f') { out.append('\f'); i++; }
                else if (n == 'n') { out.append('\n'); i++; }
                else if (n == 'r') { out.append('\r'); i++; }
                else if (n == 't') { out.append('\t'); i++; }
                else if (n == 'u' && i + 5 < s.length()) {
                    String hex = s.substring(i + 2, i + 6);
                    try { out.append((char) Integer.parseInt(hex, 16)); } catch (NumberFormatException ignored) { out.append('?'); }
                    i += 5;
                } else { out.append(n); i++; }
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static ReleaseAsset selectAsset(List<ReleaseAsset> assets, String assetRegex) {
        Pattern p = Pattern.compile(assetRegex);
        for (ReleaseAsset a : assets) {
            if (p.matcher(a.name).find()) return a;
        }
        return null;
    }

    private static Path downloadToTemp(String url, String suggestedName) throws IOException {
        String tmpDir = System.getProperty("java.io.tmpdir");
        Path tmp = Paths.get(tmpDir, suggestedName);
        URL u = new URL(url);
        HttpURLConnection conn = (HttpURLConnection) u.openConnection();
        conn.setConnectTimeout(HTTP_TIMEOUT_MS);
        conn.setReadTimeout(HTTP_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "ModUpdater/1.0");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            InputStream err = conn.getErrorStream();
            String body = err != null ? readAll(err) : "";
            throw new IOException("Download failed: HTTP " + code + "\n" + body);
        }
        InputStream in = new BufferedInputStream(conn.getInputStream());
        FileOutputStream out = new FileOutputStream(tmp.toFile());
        byte[] buf = new byte[64 * 1024];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            try { in.close(); } catch (IOException ignored) {}
            try { out.close(); } catch (IOException ignored) {}
        }
        return tmp;
    }

    private static boolean confirm(String question) throws IOException {
        System.out.print(question + " [Y/n]: ");
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        String line = br.readLine();
        if (line == null || line.trim().isEmpty()) return true;
        String s = line.trim().toLowerCase();
        return s.startsWith("y");
    }

    private static String getenv(String key) {
        try { return System.getenv(key); } catch (SecurityException ignored) { return null; }
    }

    private static void log(String s) { System.out.println(s); }
    private static void logErr(String s) { if (s != null) System.err.println(s); }

    private static final class LatestRelease {
        String tag;
        List<ReleaseAsset> assets;
    }

    private static final class ReleaseAsset {
        String name;
        String url;
    }
}


