package ge.cynthia.openservice;

final class SessionLifetime {
    static final long MAX_AGE_MILLIS = 8 * 60 * 60 * 1000L;

    private SessionLifetime() {}

    static boolean valid(Object authenticatedAt, long now) {
        return authenticatedAt instanceof Long issued
                && issued >= 0
                && issued <= now
                && now - issued < MAX_AGE_MILLIS;
    }
}
