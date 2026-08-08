package com.readthekjv.util;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Resolves the caller's time zone from the {@code X-Time-Zone} header.
 *
 * <p>Anything the app calls "today" — a due passage, a scheduled review, a heatmap
 * square, the lane the dashboard leads with — has to mean today *where the reader
 * is*. The server's own zone is an accident of deployment; on a UTC host it is
 * wrong for nearly every user, and wrong in opposite directions either side of the
 * meridian. Clients therefore send their IANA zone and the server derives calendar
 * days from it.
 *
 * <p>Lives here rather than on any one service because three of them need it, and a
 * per-service copy is how the client-side boundary drifted apart in the first place.
 */
public final class RequestZone {

    private RequestZone() {}

    /**
     * @param timeZone IANA zone id from {@code X-Time-Zone}, possibly null or junk
     * @return the caller's zone, or the server's when absent or unparseable —
     *         a bad header degrades the day boundary, it never fails the request
     */
    public static ZoneId resolve(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) return ZoneId.systemDefault();
        try {
            return ZoneId.of(timeZone.trim());
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }
}
