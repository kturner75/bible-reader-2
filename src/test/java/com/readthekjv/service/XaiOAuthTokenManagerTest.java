package com.readthekjv.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Port of the important classic-chat-reader XaiOAuthTokenManager cases:
 * file load vs seed override, rotation persist, empty on invalid_grant.
 */
class XaiOAuthTokenManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void getAccessToken_notConfigured_returnsEmptyWithoutNetworkCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("", true, countingHttpClient(calls, 200, tokenResponse("access-token", 3600, null)));

        assertFalse(manager.isConfigured());
        assertEquals(Optional.empty(), manager.getAccessToken());
        assertEquals(0, calls.get());
    }

    @Test
    void getAccessToken_disabled_returnsEmptyWithoutNetworkCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", false, countingHttpClient(calls, 200, tokenResponse("access-token", 3600, null)));

        assertFalse(manager.isConfigured());
        assertEquals(Optional.empty(), manager.getAccessToken());
        assertEquals(0, calls.get());
    }

    @Test
    void getAccessToken_configured_refreshesAndCachesToken() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingHttpClient(calls, 200, tokenResponse("access-token", 3600, null)));

        assertTrue(manager.isConfigured());
        assertEquals(Optional.of("access-token"), manager.getAccessToken());
        assertEquals(Optional.of("access-token"), manager.getAccessToken());
        // Second call must be served from cache, not a second network round-trip.
        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_shortLivedToken_stillCachesInsteadOfImmediatelyExpiring() throws Exception {
        // Regression: a flat 1-hour refresh skew would make a 3600s token immediately expired.
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingHttpClient(calls, 200, tokenResponse("access-token", 3600, null)));

        manager.getAccessToken();
        manager.getAccessToken();
        manager.getAccessToken();

        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_refreshFails_returnsEmptyAndDoesNotThrow() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingHttpClient(calls, 200, errorResponse()));

        Optional<String> result = manager.getAccessToken();

        assertEquals(Optional.empty(), result);
        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_invalidGrant_returnsEmptyAndDoesNotThrow() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingHttpClient(calls, 400, errorResponse()));

        assertEquals(Optional.empty(), manager.getAccessToken());
        assertEquals(1, calls.get());
    }

    @Test
    void getAccessToken_refreshFails_logsStatusNotResponseBody() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(XaiOAuthTokenManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String secretBody = "{\"error\":\"invalid_grant\",\"refresh_token\":\"SECRET_TOKEN_DO_NOT_LOG\"}";
            XaiOAuthTokenManager manager = manager("refresh-token", true, countingHttpClient(new AtomicInteger(), 400, secretBody));

            assertEquals(Optional.empty(), manager.getAccessToken());

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("status=400"), logged);
            assertFalse(logged.contains("SECRET_TOKEN_DO_NOT_LOG"), logged);
            assertFalse(logged.contains(secretBody), logged);
            assertFalse(logged.contains("body="), logged);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void hasSingleProductionConstructor() {
        Constructor<?>[] ctors = XaiOAuthTokenManager.class.getDeclaredConstructors();
        assertEquals(1, ctors.length);
        assertEquals(3, ctors[0].getParameterCount());
    }

    @Test
    void defaultRefreshTokenFileIsOnDurableDataDir() throws Exception {
        assertEquals("/data/xai-oauth-refresh-token", XaiOAuthTokenManager.DEFAULT_REFRESH_TOKEN_FILE);
        Path defaultPath = Path.of(XaiOAuthTokenManager.DEFAULT_REFRESH_TOKEN_FILE);
        assertTrue(defaultPath.isAbsolute(), defaultPath.toString());
        assertEquals(Path.of("/data"), defaultPath.getParent());

        var props = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/application.properties")) {
            props.load(in);
        }
        String configured = props.getProperty("ai.xai.oauth.refresh-token-file");
        assertEquals(XaiOAuthTokenManager.DEFAULT_REFRESH_TOKEN_FILE, configured);
        assertFalse(configured.startsWith("./"), configured);
    }

    @Test
    void getAccessToken_afterFailure_respectsCooldownBeforeRetrying() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingHttpClient(calls, 400, errorResponse()));

        manager.getAccessToken();
        manager.getAccessToken();

        // Second call within the cooldown window should not re-hit the network.
        assertEquals(1, calls.get());
    }

    @Test
    void invalidate_forcesNextCallToRefresh() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager("refresh-token", true, countingHttpClient(calls, 200, tokenResponse("access-token", 3600, null)));

        manager.getAccessToken();
        manager.invalidate();
        manager.getAccessToken();

        assertEquals(2, calls.get());
    }

    @Test
    void refresh_rotatedRefreshToken_isPersistedToFile() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(calls, 200, tokenResponse("access-token", 3600, "rotated-refresh-token")));

        manager.getAccessToken();

        assertEquals("rotated-refresh-token", readPersistedCurrentToken(tokenFile));
    }

    @Test
    void newManagerInstance_loadsRotatedRefreshTokenFromFile_whenConfiguredTokenUnchanged() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        writePersistedState(tokenFile, "stale-original-token-in-env-var", "previously-rotated-token");

        XaiOAuthTokenManager manager = manager(
                "stale-original-token-in-env-var", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-token", 3600, null)));

        assertEquals("previously-rotated-token", manager.currentRefreshTokenForTesting());
    }

    @Test
    void newManagerInstance_prefersFreshlyConfiguredToken_whenItDiffersFromPersistedSeed() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        writePersistedState(tokenFile, "old-configured-token", "old-rotated-token");

        XaiOAuthTokenManager manager = manager(
                "newly-configured-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-token", 3600, null)));

        assertEquals("newly-configured-token", manager.currentRefreshTokenForTesting());
    }

    @Test
    void refresh_rotatedRefreshToken_persistsFileWithOwnerOnlyPermissions() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        XaiOAuthTokenManager manager = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-token", 3600, "rotated-refresh-token")));
        manager.fileMover = (tmp, dest, atomic) -> {
            if (!atomic) {
                throw new AssertionError("atomic path must not fall back");
            }
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.setPosixFilePermissions(dest, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX — dest-0600 assertion below is a no-op.
            }
        };

        manager.getAccessToken();

        assertOwnerOnlyPermissions(tokenFile);
    }

    @Test
    void refresh_responseWithoutRotatedToken_leavesFileUntouched() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        AtomicInteger calls = new AtomicInteger();
        XaiOAuthTokenManager manager = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(calls, 200, tokenResponse("access-token", 3600, null)));

        manager.getAccessToken();

        assertFalse(Files.exists(tokenFile));
    }

    @Test
    void persist_atomicMoveUnsupported_fallsBackAndWritesFile() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        AtomicInteger atomicAttempts = new AtomicInteger();
        XaiOAuthTokenManager manager = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-token", 3600, "rotated-refresh-token")));
        manager.fileMover = (tmp, dest, atomic) -> {
            if (atomic) {
                atomicAttempts.incrementAndGet();
                throw new AtomicMoveNotSupportedException(tmp.toString(), dest.toString(), "no atomic move");
            }
            // EXDEV / copy+delete: dest is created at umask 0644, not tmp's 0600.
            Files.copy(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.setPosixFilePermissions(dest, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX — dest-0600 assertion below is a no-op.
            }
            Files.delete(tmp);
        };

        manager.getAccessToken();

        assertEquals(1, atomicAttempts.get());
        assertEquals("rotated-refresh-token", readPersistedCurrentToken(tokenFile));
        assertOwnerOnlyPermissions(tokenFile);
    }

    @Test
    void persist_permissionsFailure_doesNotPublishFile() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        Path tmp = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
        XaiOAuthTokenManager manager = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-token", 3600, "rotated-refresh-token")));
        manager.restrictedFileCreator = path -> {
            throw new UnsupportedOperationException("non-posix");
        };

        Optional<String> access = manager.getAccessToken();

        assertEquals(Optional.of("access-token"), access);
        assertEquals("rotated-refresh-token", manager.currentRefreshTokenForTesting());
        assertFalse(Files.exists(tokenFile));
        assertFalse(Files.exists(tmp));
    }

    @Test
    void persist_destPermissionsFailure_deletesWorldReadableDest() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        Path tmp = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
        XaiOAuthTokenManager manager = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-token", 3600, "rotated-refresh-token")));
        manager.permissionRestrictor = path -> {
            if (path.equals(tokenFile)) {
                throw new IOException("cannot chmod dest");
            }
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        };

        Optional<String> access = manager.getAccessToken();

        assertEquals(Optional.of("access-token"), access);
        assertEquals("rotated-refresh-token", manager.currentRefreshTokenForTesting());
        assertFalse(Files.exists(tokenFile));
        assertFalse(Files.exists(tmp));
    }

    @Test
    void persist_tempFileCreatedWithOwnerOnlyPermissions_notChmodAfterWrite() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        XaiOAuthTokenManager manager = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-token", 3600, "rotated-refresh-token")));
        manager.fileMover = (tmp, dest, atomic) -> {
            assertOwnerOnlyPermissions(tmp);
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        };
        manager.permissionRestrictor = path -> {
            assertFalse(path.getFileName().toString().endsWith(".tmp"),
                    "tmp must be created 0600, not chmod after write");
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        };

        manager.getAccessToken();

        assertOwnerOnlyPermissions(tokenFile);
    }

    @Test
    void refresh_invalidGrant_reloadsPersistedTokenRotatedByOtherInstance() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        AtomicInteger calls = new AtomicInteger();
        List<String> forms = new ArrayList<>();
        HttpClient http = httpClient(req -> {
            String form = requestForm(req);
            forms.add(form);
            int n = calls.incrementAndGet();
            if (n == 1) {
                try {
                    writePersistedState(tokenFile, "original-refresh-token", "rotated-by-other");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return httpResponse(400, errorResponse());
            }
            return httpResponse(200, tokenResponse("access-token", 3600, "rotated-again"));
        });
        XaiOAuthTokenManager manager = manager("original-refresh-token", true, tokenFile.toString(), http);

        assertEquals(Optional.of("access-token"), manager.getAccessToken());
        assertEquals(2, calls.get());
        assertTrue(forms.get(0).contains("refresh_token=original-refresh-token"), forms.get(0));
        assertTrue(forms.get(1).contains("refresh_token=rotated-by-other"), forms.get(1));
        assertEquals("rotated-again", manager.currentRefreshTokenForTesting());
    }

    @Test
    void refresh_reloadsPersistedTokenBeforeRequest_whenOtherInstanceAlreadyRotated() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        XaiOAuthTokenManager winner = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200,
                        tokenResponse("winner-access", 3600, "rotated-by-other")));
        AtomicInteger loserCalls = new AtomicInteger();
        List<String> forms = new ArrayList<>();
        XaiOAuthTokenManager loser = manager(
                "original-refresh-token", true, tokenFile.toString(),
                httpClient(req -> {
                    loserCalls.incrementAndGet();
                    forms.add(requestForm(req));
                    return httpResponse(200, tokenResponse("loser-access", 3600, "rotated-again"));
                }));

        assertEquals(Optional.of("winner-access"), winner.getAccessToken());
        assertEquals("original-refresh-token", loser.currentRefreshTokenForTesting());

        assertEquals(Optional.of("loser-access"), loser.getAccessToken());
        assertEquals(1, loserCalls.get());
        assertTrue(forms.get(0).contains("refresh_token=rotated-by-other"), forms.get(0));
        assertFalse(forms.get(0).contains("refresh_token=original-refresh-token"), forms.get(0));
        assertEquals("rotated-again", loser.currentRefreshTokenForTesting());
    }

    @Test
    void persist_nonAtomicFallbackMove_doesNotUseCopyAttributes() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        XaiOAuthTokenManager manager = manager(
                "original-refresh-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-token", 3600, null)));
        Path tmp = tempDir.resolve("payload.tmp");
        Path dest = tempDir.resolve("payload");
        Files.writeString(tmp, "rotated-payload");

        manager.fileMover.move(tmp, dest, false);

        assertEquals("rotated-payload", Files.readString(dest));
        assertFalse(Files.exists(tmp));
    }

    @Test
    void persist_newSeed_takesOverFileOwnedByPreviousSeed() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        writePersistedState(tokenFile, "old-seed-token", "old-rotated-token");
        XaiOAuthTokenManager newer = manager(
                "new-seed-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200,
                        tokenResponse("new-access", 3600, "new-rotated-token")));

        assertEquals(Optional.of("new-access"), newer.getAccessToken());
        assertEquals("new-rotated-token", readPersistedCurrentToken(tokenFile));
        assertEquals("new-seed-token", readPersistedSeed(tokenFile));
        assertEquals(List.of("old-seed-token"), readPersistedSuperseded(tokenFile));
    }

    @Test
    void persist_staleSeedInstance_doesNotOverwriteForeignSeed() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        writePersistedState(tokenFile, "old-seed-token", "old-rotated-token");
        XaiOAuthTokenManager stale = manager(
                "old-seed-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200,
                        tokenResponse("stale-access", 3600, "stale-rotated-token")));
        XaiOAuthTokenManager newer = manager(
                "new-seed-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200,
                        tokenResponse("new-access", 3600, "new-rotated-token")));

        assertEquals(Optional.of("new-access"), newer.getAccessToken());
        stale.invalidate();
        assertEquals(Optional.of("stale-access"), stale.getAccessToken());

        assertEquals("new-rotated-token", readPersistedCurrentToken(tokenFile));
        assertEquals("new-seed-token", readPersistedSeed(tokenFile));
        assertEquals(List.of("old-seed-token"), readPersistedSuperseded(tokenFile));
        assertEquals("stale-rotated-token", stale.currentRefreshTokenForTesting());
    }

    @Test
    void persist_supersededSeedRestart_doesNotOverwriteForeignSeed() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        Files.writeString(tokenFile, """
                {"seedToken":"new-seed-token","currentToken":"new-rotated-token","supersededSeed":"old-seed-token"}
                """);
        XaiOAuthTokenManager staleRestart = manager(
                "old-seed-token", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200,
                        tokenResponse("stale-access", 3600, "stale-rotated-token")));

        assertEquals("old-seed-token", staleRestart.currentRefreshTokenForTesting());
        assertEquals(Optional.of("stale-access"), staleRestart.getAccessToken());
        assertEquals("new-rotated-token", readPersistedCurrentToken(tokenFile));
        assertEquals("new-seed-token", readPersistedSeed(tokenFile));
    }

    @Test
    void persist_generationA_doesNotOverwriteC_afterAToBToCOverlap() throws Exception {
        Path tokenFile = tempDir.resolve("refresh-token");
        writePersistedState(tokenFile, "seed-a", "current-a");
        XaiOAuthTokenManager generationA = manager(
                "seed-a", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-a", 3600, "rot-a")));
        XaiOAuthTokenManager generationB = manager(
                "seed-b", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-b", 3600, "rot-b")));
        XaiOAuthTokenManager generationC = manager(
                "seed-c", true, tokenFile.toString(),
                countingHttpClient(new AtomicInteger(), 200, tokenResponse("access-c", 3600, "rot-c")));

        assertEquals(Optional.of("access-b"), generationB.getAccessToken());
        assertEquals(Optional.of("access-c"), generationC.getAccessToken());
        generationA.invalidate();
        assertEquals(Optional.of("access-a"), generationA.getAccessToken());

        assertEquals("rot-c", readPersistedCurrentToken(tokenFile));
        assertEquals("seed-c", readPersistedSeed(tokenFile));
        assertEquals(List.of("seed-a", "seed-b"), readPersistedSuperseded(tokenFile));
    }

    @Test
    void wasOAuthBearer_sharedHelper_treatsNonKeyAsOAuth() {
        assertTrue(XaiOAuthTokenManager.wasOAuthBearer("oauth-token", "xai-key"));
        assertTrue(XaiOAuthTokenManager.wasOAuthBearer("oauth-token", ""));
        assertFalse(XaiOAuthTokenManager.wasOAuthBearer("xai-key", "xai-key"));
        assertFalse(XaiOAuthTokenManager.wasOAuthBearer("", "xai-key"));
        assertFalse(XaiOAuthTokenManager.wasOAuthBearer(null, "xai-key"));
    }

    @Test
    void retryXaiBearer_sharedHelper_prefersRefreshedTokenThenApiKey() {
        XaiOAuthTokenManager oauth = mock(XaiOAuthTokenManager.class);
        when(oauth.getAccessToken()).thenReturn(Optional.of("fresh-oauth"));
        assertEquals("fresh-oauth", XaiOAuthTokenManager.retryXaiBearer(oauth, "dead-oauth", "xai-key"));

        when(oauth.getAccessToken()).thenReturn(Optional.empty());
        assertEquals("xai-key", XaiOAuthTokenManager.retryXaiBearer(oauth, "dead-oauth", "xai-key"));

        assertEquals(null, XaiOAuthTokenManager.retryXaiBearer(oauth, "dead-oauth", "dead-oauth"));
    }

    private XaiOAuthTokenManager manager(String refreshToken, boolean enabled, HttpClient httpClient) {
        return manager(refreshToken, enabled, null, httpClient);
    }

    private XaiOAuthTokenManager manager(String refreshToken, boolean enabled, String filePath, HttpClient httpClient) {
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(refreshToken, enabled, filePath);
        ReflectionTestUtils.setField(manager, "httpClient", httpClient);
        return manager;
    }

    private void assertOwnerOnlyPermissions(Path tokenFile) throws IOException {
        if (!Files.exists(tokenFile)) {
            // Non-POSIX: persist aborts rather than publishing without 0600.
            return;
        }
        try {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(tokenFile));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows CI) — nothing to verify.
        }
    }

    private void writePersistedState(Path tokenFile, String seedToken, String currentToken) throws Exception {
        Files.writeString(tokenFile, """
                {"seedToken":"%s","currentToken":"%s"}
                """.formatted(seedToken, currentToken));
    }

    private String readPersistedCurrentToken(Path tokenFile) throws Exception {
        return readPersistedField(tokenFile, "currentToken");
    }

    private String readPersistedSeed(Path tokenFile) throws Exception {
        return readPersistedField(tokenFile, "seedToken");
    }

    private List<String> readPersistedSuperseded(Path tokenFile) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(tokenFile));
        List<String> superseded = new ArrayList<>();
        com.fasterxml.jackson.databind.JsonNode arr = node.get("supersededSeeds");
        if (arr != null && arr.isArray()) {
            arr.forEach(item -> {
                String value = item.asText(null);
                if (value != null && !value.isBlank()) {
                    superseded.add(value);
                }
            });
        }
        String legacy = node.path("supersededSeed").asText(null);
        if (legacy != null && !legacy.isBlank() && !superseded.contains(legacy)) {
            superseded.add(legacy);
        }
        return superseded;
    }

    private String readPersistedField(Path tokenFile, String field) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(tokenFile));
        String value = node.path(field).asText(null);
        return (value != null && value.isBlank()) ? null : value;
    }

    private String tokenResponse(String accessToken, int expiresInSeconds, String rotatedRefreshToken) {
        String refreshTokenField = rotatedRefreshToken != null
                ? ",\"refresh_token\":\"" + rotatedRefreshToken + "\""
                : "";
        return """
                {"access_token":"%s","expires_in":%d,"token_type":"Bearer"%s}
                """.formatted(accessToken, expiresInSeconds, refreshTokenField);
    }

    @SuppressWarnings("unchecked")
    private HttpClient countingHttpClient(AtomicInteger calls, int status, String jsonBody) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(jsonBody);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            calls.incrementAndGet();
            return response;
        });
        return http;
    }

    @SuppressWarnings("unchecked")
    private HttpClient httpClient(Function<HttpRequest, HttpResponse<String>> handler) throws Exception {
        HttpClient http = mock(HttpClient.class);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(inv -> handler.apply(inv.getArgument(0)));
        return http;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> httpResponse(int status, String jsonBody) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(jsonBody);
        return response;
    }

    private static String requestForm(HttpRequest request) {
        return request.bodyPublisher()
                .map(XaiOAuthTokenManagerTest::publisherToString)
                .orElse("");
    }

    private static String publisherToString(HttpRequest.BodyPublisher publisher) {
        CompletableFuture<String> done = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                buf.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                done.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                done.complete(buf.toString(StandardCharsets.UTF_8));
            }
        });
        return done.join();
    }

    private String errorResponse() {
        return "{\"error\":\"invalid_grant\"}";
    }
}
