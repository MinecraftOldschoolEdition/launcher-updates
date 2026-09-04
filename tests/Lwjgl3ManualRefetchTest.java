import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class Lwjgl3ManualRefetchTest {
    private static final String REPO = "test/lwjgl3-patch";
    private static final String ASSET_NAME = "org.lwjgl.json";
    private static final String ASSET_REGEX = "org\\.lwjgl\\.json";
    private static final String RELEASE_TAG = "1.2.3";
    private static final String RELEASE_URL =
            "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String ASSET_URL =
            "https://downloads.example.test/releases/" + RELEASE_TAG + "/" + ASSET_NAME;
    private static final String RESTART_MESSAGE =
            "Exit the launcher and restart PrismMC for the changes to take effect";
    private static final byte[] PATCH_BYTES = validPatchJson().getBytes(StandardCharsets.UTF_8);

    private static final AtomicInteger releaseRequests = new AtomicInteger();
    private static final AtomicInteger assetRequests = new AtomicInteger();
    private static volatile boolean omitReleaseDigest;

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new FakeHandler() : null);

        verifyMatchingPatchIsForciblyRefetched();
        verifyMissingDigestPreservesInstalledPatch();
        System.out.println("LWJGL_MANUAL_REFETCH_OK");
    }

    private static void verifyMatchingPatchIsForciblyRefetched() throws Exception {
        omitReleaseDigest = false;
        releaseRequests.set(0);
        assetRequests.set(0);
        Path instanceRoot = Files.createTempDirectory("lwjgl-manual-refetch-");
        try {
            Path patch = instanceRoot.resolve("patches").resolve(ASSET_NAME);
            Files.createDirectories(patch.getParent());
            Files.write(patch, PATCH_BYTES);

            Path marker = patch.resolveSibling(ASSET_NAME + ".mcose.json");
            Files.write(marker, matchingInstalledMarker().getBytes(StandardCharsets.UTF_8));

            Path backup = patch.resolveSibling(ASSET_NAME + ".bak");
            if (Files.exists(backup)) {
                throw new AssertionError("Fixture unexpectedly started with an LWJGL backup.");
            }

            Class<?> updater = Class.forName("ModUpdaterGUI");
            assertRestartMessage(updater);
            invokeManualRefetch(updater, instanceRoot);

            if (releaseRequests.get() != 1) {
                throw new AssertionError("Manual re-fetch must freshly query the latest release exactly once; requests="
                        + releaseRequests.get());
            }
            if (assetRequests.get() != 1) {
                throw new AssertionError("Manual re-fetch must download the matching JSON even when it is already current; requests="
                        + assetRequests.get());
            }
            if (!Files.isRegularFile(patch) || !Arrays.equals(PATCH_BYTES, Files.readAllBytes(patch))) {
                throw new AssertionError("Manual re-fetch did not install the downloaded org.lwjgl.json payload.");
            }
            if (!Files.isRegularFile(backup) || !Arrays.equals(PATCH_BYTES, Files.readAllBytes(backup))) {
                throw new AssertionError("Manual re-fetch did not back up the already matching org.lwjgl.json.");
            }
            assertRewrittenMarker(marker);
            assertNoPendingPatch(patch.getParent());
        } finally {
            deleteTree(instanceRoot);
        }
    }

    private static void verifyMissingDigestPreservesInstalledPatch() throws Exception {
        omitReleaseDigest = true;
        releaseRequests.set(0);
        assetRequests.set(0);
        Path instanceRoot = Files.createTempDirectory("lwjgl-manual-refetch-no-digest-");
        try {
            Path patch = instanceRoot.resolve("patches").resolve(ASSET_NAME);
            Files.createDirectories(patch.getParent());
            Files.write(patch, PATCH_BYTES);
            Path marker = patch.resolveSibling(ASSET_NAME + ".mcose.json");
            byte[] markerBytes = matchingInstalledMarker().getBytes(StandardCharsets.UTF_8);
            Files.write(marker, markerBytes);

            try {
                Class<?> updater = Class.forName("ModUpdaterGUI");
                invokeManualRefetch(updater, instanceRoot);
                throw new AssertionError("Manual re-fetch accepted release metadata without a SHA-256 digest.");
            } catch (IOException expected) {
                if (expected.getMessage() == null
                        || expected.getMessage().indexOf("no valid GitHub SHA-256 digest") < 0) {
                    throw new AssertionError("Unexpected missing-digest failure: " + expected, expected);
                }
            }

            if (releaseRequests.get() != 1) {
                throw new AssertionError("Missing-digest case did not perform one fresh release query; requests="
                        + releaseRequests.get());
            }
            if (assetRequests.get() != 0) {
                throw new AssertionError("Missing-digest release downloaded an unverified asset; requests="
                        + assetRequests.get());
            }
            if (!Arrays.equals(PATCH_BYTES, Files.readAllBytes(patch))) {
                throw new AssertionError("Missing-digest failure changed the installed org.lwjgl.json.");
            }
            if (!Arrays.equals(markerBytes, Files.readAllBytes(marker))) {
                throw new AssertionError("Missing-digest failure changed the installed-release marker.");
            }
            if (Files.exists(patch.resolveSibling(ASSET_NAME + ".bak"))) {
                throw new AssertionError("Missing-digest failure created a backup despite not installing anything.");
            }
            assertNoPendingPatch(patch.getParent());
        } finally {
            deleteTree(instanceRoot);
            omitReleaseDigest = false;
        }
    }

    private static void assertRestartMessage(Class<?> updater) throws Exception {
        Field message = updater.getDeclaredField("LWJGL_RESTART_MESSAGE");
        message.setAccessible(true);
        Object actual = message.get(null);
        if (!RESTART_MESSAGE.equals(actual)) {
            throw new AssertionError("Unexpected LWJGL restart popup text: " + actual);
        }
    }

    private static void invokeManualRefetch(Class<?> updater, Path instanceRoot) throws Exception {
        Class<?> progressType = Class.forName("ModUpdaterGUI$ProgressUI");
        Object progress = Proxy.newProxyInstance(
                Lwjgl3ManualRefetchTest.class.getClassLoader(),
                new Class<?>[] { progressType },
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        return null;
                    }
                });
        Method refetch = updater.getDeclaredMethod(
                "refetchLwjgl3PatchUpdate",
                progressType,
                String.class,
                String.class,
                Path.class);
        refetch.setAccessible(true);
        try {
            Object result = refetch.invoke(null, progress, REPO, ASSET_REGEX, instanceRoot);
            if (result == null) {
                throw new AssertionError("Manual LWJGL re-fetch returned no refreshed release state.");
            }
            Field updateAvailable = result.getClass().getDeclaredField("updateAvailable");
            updateAvailable.setAccessible(true);
            if (updateAvailable.getBoolean(result)) {
                throw new AssertionError("Manual LWJGL re-fetch left the installed release marked as pending.");
            }
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    private static void assertRewrittenMarker(Path marker) throws IOException {
        if (!Files.isRegularFile(marker)) {
            throw new AssertionError("Manual re-fetch did not create the installed-release marker.");
        }
        String contents = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8);
        assertContains(contents, "\"tag\": \"" + RELEASE_TAG + "\"");
        assertContains(contents, "\"asset\": \"" + ASSET_NAME + "\"");
        assertContains(contents, "\"url\": \"" + ASSET_URL + "\"");
        assertContains(contents, "\"digest\": \"sha256:" + sha256(PATCH_BYTES) + "\"");
        if (contents.indexOf("\"installedAt\": 1\n") >= 0) {
            throw new AssertionError("Manual re-fetch left the preinstalled marker untouched.");
        }
    }

    private static void assertContains(String text, String expected) {
        if (text.indexOf(expected) < 0) {
            throw new AssertionError("Expected marker to contain " + expected + ", but was:\n" + text);
        }
    }

    private static void assertNoPendingPatch(Path patchesDir) throws IOException {
        java.nio.file.DirectoryStream<Path> entries = Files.newDirectoryStream(patchesDir, "org.lwjgl.*.pending");
        try {
            if (entries.iterator().hasNext()) {
                throw new AssertionError("Manual re-fetch left staged LWJGL metadata behind.");
            }
        } finally {
            entries.close();
        }
    }

    private static String matchingInstalledMarker() throws Exception {
        return "{\n"
                + "  \"tag\": \"" + RELEASE_TAG + "\",\n"
                + "  \"asset\": \"" + ASSET_NAME + "\",\n"
                + "  \"url\": \"https://downloads.example.test/already-installed\",\n"
                + "  \"digest\": \"sha256:" + sha256(PATCH_BYTES) + "\",\n"
                + "  \"installedAt\": 1\n"
                + "}\n";
    }

    private static String releaseJson() throws Exception {
        return "{"
                + "\"tag_name\":\"" + RELEASE_TAG + "\","
                + "\"assets\":[{"
                + "\"name\":\"" + ASSET_NAME + "\","
                + "\"browser_download_url\":\"" + ASSET_URL + "\","
                + (omitReleaseDigest ? "" : "\"digest\":\"sha256:" + sha256(PATCH_BYTES) + "\",")
                + "\"size\":" + PATCH_BYTES.length
                + "}]}";
    }

    private static String validPatchJson() {
        return "{\n"
                + "  \"formatVersion\": 1,\n"
                + "  \"uid\": \"org.lwjgl\",\n"
                + "  \"version\": \"3.4.3\",\n"
                + "  \"name\": \"LWJGL 3\",\n"
                + "  \"description\": \"Regression fixture padding: this text keeps the component metadata above the minimum accepted payload size while remaining valid Prism JSON for a deterministic manual re-fetch test.\",\n"
                + "  \"libraries\": [\n"
                + "    { \"name\": \"org.lwjgl:lwjgl:3.4.3\" },\n"
                + "    { \"name\": \"org.lwjgl:lwjgl-vulkan:3.4.3\" }\n"
                + "  ]\n"
                + "}\n";
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest(bytes)) {
                hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return hex.toString();
        } catch (Exception failure) {
            throw new IOException("SHA-256 unavailable", failure);
        }
    }

    private static Response responseFor(URL url) throws IOException {
        String value = url.toString();
        try {
            if (RELEASE_URL.equals(value)) {
                releaseRequests.incrementAndGet();
                return Response.ok(releaseJson().getBytes(StandardCharsets.UTF_8));
            }
            if (ASSET_URL.equals(value)) {
                assetRequests.incrementAndGet();
                return Response.ok(PATCH_BYTES);
            }
        } catch (Exception failure) {
            throw new IOException(failure);
        }
        return Response.error(404, "No fixture response for " + value);
    }

    private static void deleteTree(Path root) throws IOException {
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final class Response {
        final int status;
        final byte[] body;

        Response(int status, byte[] body) {
            this.status = status;
            this.body = body;
        }

        static Response ok(byte[] body) {
            return new Response(200, body);
        }

        static Response error(int status, String body) {
            return new Response(status, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class FakeHandler extends URLStreamHandler {
        protected URLConnection openConnection(URL url) throws IOException {
            return new FakeConnection(url, responseFor(url));
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        private final Response response;

        FakeConnection(URL url, Response response) {
            super(url);
            this.response = response;
        }

        public int getResponseCode() {
            return response.status;
        }

        public InputStream getInputStream() throws IOException {
            if (response.status < 200 || response.status >= 300) {
                throw new IOException("HTTP " + response.status);
            }
            return new ByteArrayInputStream(response.body);
        }

        public InputStream getErrorStream() {
            return new ByteArrayInputStream(response.body);
        }

        public long getContentLengthLong() {
            return response.body.length;
        }

        public int getContentLength() {
            return response.body.length;
        }

        public void connect() {}
        public void disconnect() {}
        public boolean usingProxy() { return false; }
    }
}
