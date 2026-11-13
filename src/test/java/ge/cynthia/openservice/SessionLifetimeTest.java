package ge.cynthia.openservice;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SessionLifetimeTest {
    @Test
    void absoluteLifetimeHasAnExclusiveBoundaryAndFailsClosed() {
        long issued = 1000;
        assertTrue(SessionLifetime.valid(issued, issued));
        assertTrue(SessionLifetime.valid(issued, issued + SessionLifetime.MAX_AGE_MILLIS - 1));
        assertFalse(SessionLifetime.valid(issued, issued + SessionLifetime.MAX_AGE_MILLIS));
        assertFalse(SessionLifetime.valid(issued, issued + SessionLifetime.MAX_AGE_MILLIS + 1));
        assertFalse(SessionLifetime.valid(issued, issued - 1));
        assertFalse(SessionLifetime.valid(-1L, issued));
        assertFalse(SessionLifetime.valid(Long.MIN_VALUE, issued));
        assertFalse(SessionLifetime.valid(Long.MAX_VALUE, issued));
        assertFalse(SessionLifetime.valid(null, issued));
        assertFalse(SessionLifetime.valid("1000", issued));
    }
}
