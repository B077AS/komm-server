package com.kommserver.bootstrap;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.kommserver.config.DataDir;
import com.kommserver.model.dto.request.InstallationValidationRequest;
import com.kommserver.model.dto.response.InstallationValidationResponse;
import com.kommserver.security.InstallationKeys;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

/**
 * Activates a new installation against the hub <em>before</em> Spring starts, so the server's first start is already its
 * real one: the hub-issued certificate and the ports chosen in the Komm client are on disk when Tomcat is configured,
 * and it comes up as HTTPS on the right port straight away. Nothing is served over plain HTTP first and nothing has to
 * restart (no supervisor is needed either: {@code java -jar} on its own works).
 * <p>
 * It only acts when a verification code is at hand and the installation has no certificate yet (see {@link #run()}). The
 * code comes from {@code setup-token.txt} in the data folder, or from the {@code KOMM_SETUP_TOKEN} environment variable.
 * The hub's answer is written to {@value #PENDING_FILE} together with the certificate and the ports; once Spring and the
 * database are up, {@code StartupValidator} turns it into the installation record.
 * <p>
 * When it cannot finish it does not exit, which would only make a service manager start it again in a loop:
 * <ul>
 *   <li>The hub rejected the code (a mistyped or already used one): it says so once, and waits. A new code in the token
 *       file is picked up within a second, with no restart.</li>
 *   <li>The hub cannot be reached, or is busy: it tries again with a growing pause (5 s up to a minute).</li>
 * </ul>
 * While waiting it keeps {@value #STATUS_FILE} up to date for the {@code kommserver} tool, and since Tomcat is not
 * running, nothing answers on the port: a container health check or {@code kommserver start} sees that it is not up.
 */
@Slf4j
public class PreBootActivation {

    public static final String SETUP_TOKEN_ENV = "KOMM_SETUP_TOKEN";
    /** What the hub said and what is being done about it while the server waits; removed once activated. */
    public static final String STATUS_FILE = "activation.status";
    /** The hub's answer, from activation until the installation record exists. */
    public static final String PENDING_FILE = "activation-pending.json";

    /**
     * The common name of the certificate request. The hub reads only the public key from it and issues the certificate
     * as CN=<installationId>; the verification code travels in the request body. It used to be the code itself, but the
     * hub's codes are 86 characters and a CN may not exceed 64 (RFC 5280, enforced by BouncyCastle), so a real
     * activation failed with "Failed to generate CSR".
     */
    private static final String CSR_COMMON_NAME = "komm-server";

    private static final Gson GSON = new Gson();
    private static final long FIRST_RETRY_MS = 5_000;
    private static final long MAX_RETRY_MS = 60_000;
    private static final long POLL_MS = 1_000;
    private static final long HEARTBEAT_MS = 5_000;

    /**
     * Activates if there is something to activate and a code to do it with; otherwise does nothing. "Something to
     * activate": no hub certificate on disk yet and no earlier answer waiting to be imported. Installations that
     * already ran have the certificate, so this is a no-op for them; with no code at all the normal start decides
     * (an existing installation boots, a new one is told how to give a code).
     */
    public static void run() {
        if (Files.exists(certFile()) || Files.exists(pendingFile())) {
            deleteQuietly(statusFile());
            return;
        }
        if (readToken() == null) {
            return;
        }
        try {
            activate();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting to be activated", e);
        } catch (Exception e) {
            throw new IllegalStateException("Activation failed: " + e.getMessage(), e);
        }
    }

    // ── The hub's answer, for StartupValidator ────────────────────────────────

    /** The answer written by {@link #run()} that has not been turned into an installation record yet, or null. */
    public static InstallationValidationResponse readPending() {
        Path file = pendingFile();
        if (!Files.isReadable(file)) {
            return null;
        }
        try {
            return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), InstallationValidationResponse.class);
        } catch (IOException | JsonParseException e) {
            log.error("Cannot read {}: {}", file, e.getMessage());
            return null;
        }
    }

    public static void clearPending() {
        deleteQuietly(pendingFile());
    }

    /**
     * Writes the ports chosen in the Komm client where {@code PortEnvironmentPostProcessor} reads them at boot.
     *
     * @return true if the file was (re)written, i.e. a boot that did not have them is bound to the wrong ports
     */
    public static boolean writePorts(InstallationValidationResponse response) {
        if (response.getPort() == null) {
            return false; // an older hub sent none: the defaults stay
        }
        Properties props = new Properties();
        props.setProperty("server.port", String.valueOf(response.getPort()));
        // No sfu.signal-port: LiveKit's signal port is loopback-only (proxied at /rtc) and not chosen in the client.
        if (response.getTcpPort() != null) props.setProperty("sfu.tcp-port", String.valueOf(response.getTcpPort()));
        if (response.getMediaPort() != null) props.setProperty("sfu.media-port", String.valueOf(response.getMediaPort()));

        Path path = portsFile();
        try {
            if (Files.exists(path) && props.equals(loadProperties(path))) {
                return false;
            }
            try (var out = Files.newOutputStream(path)) {
                props.store(out, "Ports chosen in the Komm client at installation creation - do not edit by hand");
            }
            log.info("Port configuration written to {}", path);
            return true;
        } catch (IOException e) {
            log.error("Failed to write port configuration: {}", e.getMessage());
            return false;
        }
    }

    // ── Activation ────────────────────────────────────────────────────────────

    private static void activate() throws Exception {
        KeyPair keys = InstallationKeys.loadOrGenerate(keysDir(), privateKeyFile(), publicKeyFile());
        String hubUrl = hubUrl();
        // HTTP/1.1: one small POST gains nothing from HTTP/2, and by default the JDK client offers an h2c upgrade on
        // plain http:// URLs, which some proxies and dev servers answer badly.
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("New installation: activating with the hub at {}", hubUrl);

        long retryMs = FIRST_RETRY_MS;
        while (true) {
            String token = readToken();
            if (token == null) {
                // The code was taken away while waiting (the token file was deleted): wait for the next one.
                String message = "No verification code. Put the one from the Komm client in " + tokenFile()
                        + " or set " + SETUP_TOKEN_ENV + ".";
                log.error(message);
                writeStatus("rejected", "SETUP_CODE_MISSING", message);
                awaitNewToken(null, Long.MAX_VALUE, "rejected", "SETUP_CODE_MISSING", message);
                continue;
            }

            Outcome outcome = submit(client, hubUrl, token, InstallationKeys.csrPem(keys, CSR_COMMON_NAME));
            switch (outcome) {
                case Accepted accepted -> {
                    finish(accepted.response());
                    return;
                }
                case Rejected rejected -> {
                    log.error("The hub rejected the verification code{}: {}",
                            rejected.code() == null ? "" : " (" + rejected.code() + ")", rejected.message());
                    log.error("Nothing is served until the server is activated. Put a new code in {}: it is picked up "
                            + "within a second, no restart needed. (With {} set instead, change it and start the "
                            + "server again.)", tokenFile(), SETUP_TOKEN_ENV);
                    writeStatus("rejected", rejected.code(), rejected.message());
                    awaitNewToken(token, Long.MAX_VALUE, "rejected", rejected.code(), rejected.message());
                    retryMs = FIRST_RETRY_MS;
                }
                case Retry retry -> {
                    long waitMs = Math.max(retry.retryAfterMs(), retryMs);
                    log.warn("Activation not possible yet: {}. Trying again in {} s.", retry.message(), waitMs / 1000);
                    writeStatus("unreachable", null, retry.message());
                    awaitNewToken(token, waitMs, "unreachable", null, retry.message());
                    retryMs = Math.min(retryMs * 2, MAX_RETRY_MS);
                }
            }
        }
    }

    /** Everything the hub gave us goes to disk at once; the certificate last, since its presence means "activated". */
    private static void finish(InstallationValidationResponse response) throws IOException {
        writeAtomic(pendingFile(), GSON.toJson(response));
        writePorts(response);
        writeAtomic(certFile(), response.getCertificate());
        deleteQuietly(tokenFile());
        deleteQuietly(statusFile());
        log.info("Registration complete: installation '{}', certificate issued by the hub CA{}.",
                response.getInstallationName(),
                response.getPort() == null ? "" : ", serving HTTPS on port " + response.getPort());
    }

    private sealed interface Outcome permits Accepted, Rejected, Retry {
    }

    private record Accepted(InstallationValidationResponse response) implements Outcome {
    }

    /** Permanent until the code changes. */
    private record Rejected(String code, String message) implements Outcome {
    }

    /** Transient: the hub is down, busy, or the network is. */
    private record Retry(String message, long retryAfterMs) implements Outcome {
    }

    private static Outcome submit(HttpClient client, String hubUrl, String token, String csr) {
        InstallationValidationRequest body = InstallationValidationRequest.builder().setupToken(token).csr(csr).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(hubUrl + "/api/installations/validate"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                InstallationValidationResponse answer = GSON.fromJson(response.body(), InstallationValidationResponse.class);
                return isComplete(answer) ? new Accepted(answer) : new Retry("the hub's answer is incomplete", 0);
            }
            String[] error = errorOf(response.body());
            if (status == 408 || status == 429 || status >= 500) {
                return new Retry("the hub answered HTTP " + status, retryAfterMs(response));
            }
            String message = error[1] != null ? error[1]
                    : status == 404 || status == 405
                    ? "The hub answered HTTP " + status + ": is api.url (" + hubUrl + ") really a Komm hub?"
                    : "The hub answered HTTP " + status + ".";
            return new Rejected(error[0], message);
        } catch (IOException | JsonParseException e) {
            return new Retry("cannot reach the hub at " + hubUrl + " (" + e.getMessage() + ")", 0);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while activating", e);
        }
    }

    private static boolean isComplete(InstallationValidationResponse r) {
        return r != null && r.getCertificate() != null && !r.getCertificate().isBlank()
                && r.getHubPublicKey() != null && r.getInstallationId() != null
                && r.getInstallationName() != null && r.getOwnerId() != null;
    }

    /** The hub's {@code code} and {@code message} fields, null where it did not send them (an older hub sends no code). */
    private static String[] errorOf(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            return new String[]{text(json, "code"), text(json, "message")};
        } catch (RuntimeException e) {
            return new String[]{null, null};
        }
    }

    private static String text(JsonObject json, String field) {
        return json.has(field) && json.get(field).isJsonPrimitive() ? json.get(field).getAsString() : null;
    }

    private static long retryAfterMs(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(value -> {
            try {
                return Math.min(Long.parseLong(value.trim()), 300) * 1000;
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    // ── Waiting ───────────────────────────────────────────────────────────────

    /** Sleeps up to {@code maxMs}, returning early as soon as the code differs from {@code current}. */
    private static void awaitNewToken(String current, long maxMs, String state, String code, String message)
            throws InterruptedException {
        long waited = 0;
        long sinceHeartbeat = 0;
        while (waited < maxMs) {
            Thread.sleep(POLL_MS);
            waited += POLL_MS;
            sinceHeartbeat += POLL_MS;
            if (!Objects.equals(readToken(), current)) {
                return;
            }
            if (sinceHeartbeat >= HEARTBEAT_MS) {
                // Lets `kommserver start` tell a server that is waiting right now from a stale file.
                writeStatus(state, code, message);
                sinceHeartbeat = 0;
            }
        }
    }

    // ── Files ─────────────────────────────────────────────────────────────────

    /** The code from {@code setup-token.txt}, else from {@code KOMM_SETUP_TOKEN}; null when there is none. */
    static String readToken() {
        Path file = tokenFile();
        try {
            if (Files.isReadable(file)) {
                String value = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (IOException e) {
            log.warn("Cannot read {}: {}", file, e.getMessage());
        }
        String fromEnv = System.getenv(SETUP_TOKEN_ENV);
        return fromEnv == null || fromEnv.isBlank() ? null : fromEnv.trim();
    }

    private static void writeStatus(String state, String code, String message) {
        Properties props = new Properties();
        props.setProperty("state", state);
        if (code != null) props.setProperty("code", code);
        if (message != null) props.setProperty("message", message);
        props.setProperty("updated", String.valueOf(System.currentTimeMillis()));
        try (var out = new java.io.StringWriter()) {
            props.store(out, null);
            writeAtomic(statusFile(), out.toString());
        } catch (IOException e) {
            log.debug("Cannot write {}: {}", statusFile(), e.getMessage());
        }
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Properties loadProperties(Path path) {
        Properties props = new Properties();
        try (var in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException ignored) {
        }
        return props;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Cannot delete {}: {}", path, e.getMessage());
        }
    }

    // ── Locations (the same settings, and defaults, the Spring side uses) ─────

    private static String hubUrl() {
        String url = BootConfig.get("api.url", "http://localhost:8085");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static Path tokenFile() {
        return DataDir.resolve(BootConfig.get("komm.setup-token.file", "setup-token.txt"));
    }

    private static Path certFile() {
        return DataDir.resolve(BootConfig.get("komm.tls.cert-file", "keys/tls-cert.pem"));
    }

    private static Path portsFile() {
        return DataDir.resolve(BootConfig.get("komm.ports-file", "installation-ports.properties"));
    }

    private static Path keysDir() {
        return DataDir.resolve(BootConfig.get("jwt.keys.directory", "keys"));
    }

    private static Path privateKeyFile() {
        return DataDir.resolve(BootConfig.get("jwt.keys.private", "keys/komm-private.pem"));
    }

    private static Path publicKeyFile() {
        return DataDir.resolve(BootConfig.get("jwt.keys.public", "keys/komm-public.pem"));
    }

    private static Path pendingFile() {
        return DataDir.resolve(PENDING_FILE);
    }

    private static Path statusFile() {
        return DataDir.resolve(STATUS_FILE);
    }
}
