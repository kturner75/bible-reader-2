package com.readthekjv.util;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RequestZoneTest {

    @Test
    void resolvesAValidIanaZone() {
        assertEquals(ZoneId.of("Asia/Tokyo"), RequestZone.resolve("Asia/Tokyo"));
        assertEquals(ZoneId.of("America/Chicago"), RequestZone.resolve("  America/Chicago  "));
    }

    @Test
    void fallsBackToTheServerZoneRatherThanFailing() {
        // A missing or malformed header degrades the day boundary; it must never
        // turn a dashboard load or a review submission into an error.
        assertEquals(ZoneId.systemDefault(), RequestZone.resolve(null));
        assertEquals(ZoneId.systemDefault(), RequestZone.resolve(""));
        assertEquals(ZoneId.systemDefault(), RequestZone.resolve("   "));
        assertEquals(ZoneId.systemDefault(), RequestZone.resolve("Not/AZone"));
        assertEquals(ZoneId.systemDefault(), RequestZone.resolve("'; DROP TABLE users;--"));
    }
}
