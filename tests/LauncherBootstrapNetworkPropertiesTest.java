import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LauncherBootstrapNetworkPropertiesTest {
    private static final String[] TEST_PROPERTIES = {
        "java.net.useSystemProxies",
        "https.proxyHost",
        "https.proxyPort",
        "http.nonProxyHosts",
        "javax.net.ssl.trustStore",
        "javax.net.ssl.trustStorePassword",
        "http.proxyPassword"
    };

    public static void main(String[] args) throws Exception {
        Map<String, String> previous = new LinkedHashMap<String, String>();
        for (String name : TEST_PROPERTIES) previous.put(name, System.getProperty(name));
        try {
            System.setProperty("java.net.useSystemProxies", "true");
            System.setProperty("https.proxyHost", "regional-proxy.invalid");
            System.setProperty("https.proxyPort", "8443");
            System.setProperty("http.nonProxyHosts", "localhost|*.lan");
            System.setProperty("javax.net.ssl.trustStore", "/tmp/regional trust.jks");
            System.setProperty("javax.net.ssl.trustStorePassword", "must-not-propagate");
            System.setProperty("http.proxyPassword", "must-not-propagate");

            List<String> command = new ArrayList<String>(Arrays.asList("java"));
            Method append = LauncherBootstrap.class.getDeclaredMethod(
                    "appendNetworkRelaunchProperties", List.class);
            append.setAccessible(true);
            append.invoke(null, command);
            command.add("-cp");
            command.add("launcher.jar");

            assertBeforeClasspath(command, "-Djava.net.useSystemProxies=true");
            assertBeforeClasspath(command, "-Dhttps.proxyHost=regional-proxy.invalid");
            assertBeforeClasspath(command, "-Dhttps.proxyPort=8443");
            assertBeforeClasspath(command, "-Dhttp.nonProxyHosts=localhost|*.lan");
            assertBeforeClasspath(command, "-Djavax.net.ssl.trustStore=/tmp/regional trust.jks");
            assertAbsent(command, "trustStorePassword");
            assertAbsent(command, "proxyPassword");
            System.out.println("BOOTSTRAP_NETWORK_PROPERTIES_OK");
        } finally {
            for (Map.Entry<String, String> entry : previous.entrySet()) {
                if (entry.getValue() == null) System.clearProperty(entry.getKey());
                else System.setProperty(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void assertBeforeClasspath(List<String> command, String argument) {
        int propertyIndex = command.indexOf(argument);
        int classpathIndex = command.indexOf("-cp");
        if (propertyIndex < 0 || propertyIndex >= classpathIndex) {
            throw new AssertionError("Expected preserved network property before -cp: " + argument
                    + " in " + command);
        }
    }

    private static void assertAbsent(List<String> command, String fragment) {
        for (String argument : command) {
            if (argument.indexOf(fragment) >= 0) {
                throw new AssertionError("Credential-bearing property leaked into relaunch command: " + argument);
            }
        }
    }
}
