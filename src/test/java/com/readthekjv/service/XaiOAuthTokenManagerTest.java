package com.readthekjv.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
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

        manager.getAccessToken();

        try {
            var permissions = Files.getPosixFilePermissions(tokenFile);
            assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"), permissions);
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows CI) — nothing to verify.
        }
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

    private XaiOAuthTokenManager manager(String refreshToken, boolean enabled, HttpClient httpClient) {
        return manager(refreshToken, enabled, null, httpClient);
    }

    private XaiOAuthTokenManager manager(String refreshToken, boolean enabled, String filePath, HttpClient httpClient) {
        XaiOAuthTokenManager manager = new XaiOAuthTokenManager(refreshToken, enabled, filePath);
        ReflectionTestUtils.setField(manager, "httpClient", httpClient);
        return manager;
    }

    private void writePersistedState(Path tokenFile, String seedToken, String currentToken) throws Exception {
        Files.writeString(tokenFile, """
                {"seedToken":"%s","currentToken":"%s"}
                """.formatted(seedToken, currentToken));
    }

    private String readPersistedCurrentToken(Path tokenFile) throws Exception {
        com.fasterxml.jackson.databind.JsonNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(tokenFile));
        return node.path("currentToken").asText(null);
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

    private String errorResponse() {
        return "{\"error\":\"invalid_grant\"}";
    }
}
