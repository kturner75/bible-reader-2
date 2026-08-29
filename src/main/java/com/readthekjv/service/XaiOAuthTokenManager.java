package com.readthekjv.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exchanges a long-lived xAI OAuth refresh token (obtained out-of-band via
 * {@code scripts/xai_oauth_login.sh} against a SuperGrok / X Premium+
 * subscription) for short-lived access tokens, so xAI API calls (VOTD, STT,
 * upcoming TTS) can draw against subscription quota instead of pay-per-token
 * API billing.
 *
 * <p>This is <em>not</em> user sign-in and must not be mixed with Google
 * OAuth. SuperGrok OAuth is strictly for server-side xAI API calls.
 *
 * <p><strong>Do not share one refresh token with classic-chat-reader (CCR)
 * prod.</strong> xAI rotates refresh tokens on every use; a shared token
 * would invalidate the other deployment. Mint a separate token for rkj.
 *
 * <p>xAI rotates refresh tokens on every use: each {@code refresh_token}
 * grant response carries a new refresh_token that invalidates the one just
 * used. This class tracks the current refresh token in memory and persists
 * it to a local file so a rotated token survives process restarts — without
 * that, every restart would resend the original (already-invalidated) token
 * from the env var and permanently fail with {@code invalid_grant}.
 *
 * <p>Callers must treat a missing token (empty Optional) as "fall back to
 * {@code XAI_API_KEY}" — this class never throws for an unconfigured or
 * dead refresh token. Protocol matches
 * {@code com.classicchatreader.service.llm.XaiOAuthTokenManager}:
 * {@code POST https://auth.x.ai/oauth2/token} with
 * {@code grant_type=refresh_token} and the same CLI {@code client_id}.
 */
@Service
public class XaiOAuthTokenManager {

    private static final Logger log = LoggerFactory.getLogger(XaiOAuthTokenManager.class);
    private static final String TOKEN_URL = "https://auth.x.ai/oauth2/token";
    private static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
    // Refresh a bit before expiry so in-flight requests never race a live token, but bound the
    // skew to a fraction of the token's own lifetime — a flat skew larger than a short-lived
    // token's expires_in would make the cache permanently expired and force a synchronous
    // refresh (and endpoint round-trip) on every single call.
    private static final Duration MAX_REFRESH_SKEW = Duration.ofMinutes(5);
    // If a refresh attempt fails, don't hammer the endpoint on every subsequent call.
    private static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(1);

    /**
     * Production default: absolute path on the durable {@code /data} volume,
     * not {@code ./data} under the container working directory (that dies on
     * redeploy after the seed refresh token has already been rotated).
     */
    public static final String DEFAULT_REFRESH_TOKEN_FILE = "/data/xai-oauth-refresh-token";

    private record CachedToken(String accessToken, Instant expiresAt) {
    }

    // seedToken is the configured (env var) value the persisted currentToken was derived
    // from. Keying the cache on it lets an operator override a stale/bad persisted token
    // simply by rotating XAI_OAUTH_REFRESH_TOKEN and redeploying — without this, a persisted
    // token that goes bad (revoked, corrupted, copied from another deployment) would be
    // stuck forever, since it always wins over the configured value.
    private record PersistedState(String seedToken, String currentToken) {
    }

    private HttpClient httpClient;
    private final AtomicReference<String> refreshToken = new AtomicReference<>();
    private final String seedRefreshToken;
    private final Path refreshTokenFile;
    private final boolean enabled;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
    private volatile Instant lastFailureAt;

    @FunctionalInterface
    interface FileMover {
        void move(Path tmp, Path dest, boolean atomic) throws IOException;
    }

    // Test seam (ReflectionTestUtils) — not a second constructor. Defaults to Files.move.
    FileMover fileMover = (tmp, dest, atomic) -> {
        if (atomic) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } else {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    };

    @FunctionalInterface
    interface PermissionRestrictor {
        void restrictToOwnerOnly(Path path) throws IOException;
    }

    // Test seam — not a second constructor. POSIX 0600 or throw (do not persist).
    PermissionRestrictor permissionRestrictor = path ->
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));

    public XaiOAuthTokenManager(
            @Value("${ai.xai.oauth.refresh-token:}") String refreshToken,
            @Value("${ai.xai.oauth.enabled:true}") boolean enabled,
            @Value("${ai.xai.oauth.refresh-token-file:" + DEFAULT_REFRESH_TOKEN_FILE + "}") String refreshTokenFilePath) {
        this.enabled = enabled;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.seedRefreshToken = refreshToken;
        this.refreshTokenFile = (refreshTokenFilePath != null && !refreshTokenFilePath.isBlank())
                ? Path.of(refreshTokenFilePath)
                : null;

        String initialToken = refreshToken;
        PersistedState persisted = readPersistedState();
        if (persisted != null && Objects.equals(persisted.seedToken(), refreshToken)) {
            initialToken = persisted.currentToken();
            log.info("event=xai_oauth_refresh_token_loaded_from_file");
        } else if (persisted != null) {
            log.info("event=xai_oauth_configured_token_overrides_stale_file");
        }
        this.refreshToken.set(initialToken);

        if (enabled && initialToken != null && !initialToken.isBlank()) {
            log.info("event=xai_oauth_configured (SuperGrok subscription auth enabled)");
        }
    }

    /**
     * Returns a valid access token if OAuth is configured and healthy, otherwise empty.
     * Empty means the caller should fall back to its xAI API key.
     */
    public synchronized Optional<String> getAccessToken() {
        if (!isConfigured()) {
            return Optional.empty();
        }

        CachedToken current = cachedToken.get();
        if (current != null && Instant.now().isBefore(current.expiresAt())) {
            return Optional.of(current.accessToken());
        }

        if (lastFailureAt != null && Instant.now().isBefore(lastFailureAt.plus(FAILURE_COOLDOWN))) {
            return Optional.empty();
        }

        return refresh();
    }

    /** Forces the next {@link #getAccessToken()} call to mint a fresh token. */
    public synchronized void invalidate() {
        cachedToken.set(null);
    }

    /** True if a refresh token is configured and enabled, without making a network call. */
    public boolean isConfigured() {
        String token = refreshToken.get();
        return enabled && token != null && !token.isBlank();
    }

    // Visible for testing: exposes which refresh token would actually be sent, so tests can
    // verify the seed-vs-persisted-cache selection logic without inspecting network traffic.
    String currentRefreshTokenForTesting() {
        return refreshToken.get();
    }

    private Optional<String> refresh() {
        String currentRefreshToken = refreshToken.get();
        String form = "grant_type=refresh_token"
                + "&refresh_token=" + URLEncoder.encode(currentRefreshToken, StandardCharsets.UTF_8)
                + "&client_id=" + URLEncoder.encode(CLIENT_ID, StandardCharsets.UTF_8);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TOKEN_URL))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .timeout(Duration.ofSeconds(15))
                    .build();

            HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (httpResponse.statusCode() / 100 != 2) {
                lastFailureAt = Instant.now();
                cachedToken.set(null);
                log.warn("event=xai_oauth_refresh_failed status={}", httpResponse.statusCode());
                return Optional.empty();
            }

            JsonNode node = objectMapper.readTree(httpResponse.body());
            String accessToken = node.path("access_token").asText(null);
            int expiresInSeconds = node.path("expires_in").asInt(0);
            if (accessToken == null || expiresInSeconds <= 0) {
                throw new IllegalStateException("xAI OAuth token response missing access_token/expires_in");
            }

            // xAI rotates refresh tokens on every use — persist the new one so the next
            // process restart doesn't retry the now-invalidated token from config.
            String rotatedRefreshToken = node.path("refresh_token").asText(null);
            if (rotatedRefreshToken != null && !rotatedRefreshToken.isBlank()
                    && !rotatedRefreshToken.equals(currentRefreshToken)) {
                refreshToken.set(rotatedRefreshToken);
                persistRefreshToken(rotatedRefreshToken);
                log.info("event=xai_oauth_refresh_token_rotated");
            }

            Duration lifetime = Duration.ofSeconds(expiresInSeconds);
            Duration skew = lifetime.dividedBy(10).compareTo(MAX_REFRESH_SKEW) < 0
                    ? lifetime.dividedBy(10)
                    : MAX_REFRESH_SKEW;
            Instant expiresAt = Instant.now().plus(lifetime).minus(skew);
            cachedToken.set(new CachedToken(accessToken, expiresAt));
            lastFailureAt = null;
            log.info("event=xai_oauth_refreshed expires_in={}s", expiresInSeconds);
            return Optional.of(accessToken);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            lastFailureAt = Instant.now();
            cachedToken.set(null);
            log.warn("event=xai_oauth_refresh_failed error={}", e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            lastFailureAt = Instant.now();
            cachedToken.set(null);
            log.warn("event=xai_oauth_refresh_failed error={}", e.getMessage());
            return Optional.empty();
        }
    }

    private PersistedState readPersistedState() {
        if (refreshTokenFile == null || !Files.exists(refreshTokenFile)) {
            return null;
        }
        try {
            String raw = Files.readString(refreshTokenFile, StandardCharsets.UTF_8).trim();
            if (raw.isBlank()) {
                return null;
            }
            JsonNode node = objectMapper.readTree(raw);
            String seed = node.path("seedToken").asText(null);
            String current = node.path("currentToken").asText(null);
            if (current == null || current.isBlank()) {
                return null;
            }
            return new PersistedState(seed, current);
        } catch (IOException e) {
            log.warn("event=xai_oauth_refresh_token_file_read_failed error={}", e.getMessage());
            return null;
        }
    }

    private void persistRefreshToken(String token) {
        if (refreshTokenFile == null) {
            return;
        }
        Path tmp = null;
        try {
            if (refreshTokenFile.getParent() != null) {
                Files.createDirectories(refreshTokenFile.getParent());
            }
            String json = objectMapper.writeValueAsString(new PersistedState(seedRefreshToken, token));
            tmp = refreshTokenFile.resolveSibling(refreshTokenFile.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            // 0600 or abort: never movePersistedFile with inherited/default permissions.
            // In-memory rotated token stays for this process; next restart uses seed/file.
            try {
                permissionRestrictor.restrictToOwnerOnly(tmp);
            } catch (UnsupportedOperationException | IOException e) {
                log.warn("event=xai_oauth_refresh_token_permissions_failed error={}", e.getMessage());
                deleteQuietly(tmp);
                return;
            }
            movePersistedFile(tmp, refreshTokenFile);
        } catch (IOException e) {
            log.warn("event=xai_oauth_refresh_token_persist_failed error={}", e.getMessage());
            deleteQuietly(tmp);
        }
    }

    /**
     * Prefer ATOMIC_MOVE so a crash cannot leave a half-written dest. Some
     * mounts (network FS, Docker volume bind on certain hosts) throw
     * {@link AtomicMoveNotSupportedException}; retry without ATOMIC_MOVE so
     * the durable file still updates instead of leaving only the in-memory
     * rotated token.
     */
    void movePersistedFile(Path tmp, Path dest) throws IOException {
        try {
            fileMover.move(tmp, dest, true);
        } catch (AtomicMoveNotSupportedException e) {
            log.warn("event=xai_oauth_refresh_token_atomic_move_unsupported");
            fileMover.move(tmp, dest, false);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("event=xai_oauth_refresh_token_tmp_delete_failed error={}", e.getMessage());
        }
    }
}
