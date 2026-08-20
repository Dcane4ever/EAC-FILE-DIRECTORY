package ph.edu.eac.filedirectory.security.ratelimit;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory, per-key sliding-window rate limiter - no Redis/Bucket4j, this
 * app runs as a single instance on local/LAN infrastructure so a shared
 * external store would be unjustified complexity (see roadmap's "avoid
 * speculative complexity" rule). Deliberately simple: each key (e.g.
 * "login:<email>" or "upload:<ip>") gets its own deque of recent-attempt
 * timestamps; a new attempt is allowed only if fewer than `limit` timestamps
 * remain within the trailing `window`.
 *
 * Not persisted - a restart clears all limits, which is fine for what this
 * protects against (brute-force/spam bursts within a single running
 * session, not a long-term ban list). Memory is bounded in practice: each
 * key holds at most `limit` timestamps, and keys naturally stop growing
 * once an actor stops attempting.
 */
@Component
public class RateLimiter {

    private final Map<String, Deque<Instant>> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    // Package-private constructor for tests that need to control time.
    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Records an attempt for `key` and reports whether it's allowed under
     * the given limit/window. Always records the attempt regardless of the
     * result, so a caller can't "peek" without it counting.
     */
    public synchronized boolean allow(String key, int limit, Duration window) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);

        Deque<Instant> recent = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
            recent.pollFirst();
        }

        if (recent.size() >= limit) {
            return false;
        }
        recent.addLast(now);
        return true;
    }

    /** How many seconds until the oldest attempt in the current window ages out and a new attempt would be allowed - for building a user-facing "try again in Ns" message. */
    public synchronized long secondsUntilAllowed(String key, int limit, Duration window) {
        Deque<Instant> recent = attempts.get(key);
        if (recent == null || recent.size() < limit) {
            return 0;
        }
        Instant now = clock.instant();
        Instant oldestExpiresAt = recent.peekFirst().plus(window);
        long seconds = Duration.between(now, oldestExpiresAt).getSeconds();
        return Math.max(seconds, 0);
    }

    /**
     * Clears all recorded attempts. This bean is a singleton shared across
     * the whole Spring context - without this, integration tests that POST
     * repeatedly to a rate-limited endpoint (even across different test
     * classes, since @SpringBootTest reuses a cached context) would bleed
     * limiter state into each other, since MockMvc requests all share the
     * same "IP". Only intended to be called from test setup.
     */
    public synchronized void reset() {
        attempts.clear();
    }
}
