package ph.edu.eac.filedirectory.security.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allowsUpToTheLimitThenBlocks() {
        RateLimiter limiter = new RateLimiter();
        String key = "test-key";

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.allow(key, 3, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.allow(key, 3, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void differentKeysAreTrackedIndependently() {
        RateLimiter limiter = new RateLimiter();

        for (int i = 0; i < 3; i++) {
            limiter.allow("key-a", 3, Duration.ofMinutes(1));
        }
        assertThat(limiter.allow("key-a", 3, Duration.ofMinutes(1))).isFalse();

        // key-b has never been touched - must not be affected by key-a's exhausted limit.
        assertThat(limiter.allow("key-b", 3, Duration.ofMinutes(1))).isTrue();
    }

    @Test
    void oldAttemptsAgeOutOfTheWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiter limiter = new RateLimiter(clock);
        String key = "test-key";
        Duration window = Duration.ofMinutes(15);

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.allow(key, 5, window)).isTrue();
        }
        assertThat(limiter.allow(key, 5, window)).isFalse();

        // Advance past the window - the earlier attempts should no longer count.
        clock.advance(Duration.ofMinutes(16));
        assertThat(limiter.allow(key, 5, window)).isTrue();
    }

    @Test
    void secondsUntilAllowed_isZeroWhenNotAtLimit() {
        RateLimiter limiter = new RateLimiter();
        assertThat(limiter.secondsUntilAllowed("unused-key", 5, Duration.ofMinutes(15))).isZero();
    }

    @Test
    void secondsUntilAllowed_isPositiveWhenAtLimit() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        RateLimiter limiter = new RateLimiter(clock);
        String key = "test-key";
        Duration window = Duration.ofMinutes(15);

        for (int i = 0; i < 5; i++) {
            limiter.allow(key, 5, window);
        }

        long secondsLeft = limiter.secondsUntilAllowed(key, 5, window);
        assertThat(secondsLeft).isGreaterThan(0).isLessThanOrEqualTo(window.getSeconds());
    }

    @Test
    void reset_clearsAllTrackedAttempts() {
        RateLimiter limiter = new RateLimiter();
        String key = "test-key";
        for (int i = 0; i < 3; i++) {
            limiter.allow(key, 3, Duration.ofMinutes(1));
        }
        assertThat(limiter.allow(key, 3, Duration.ofMinutes(1))).isFalse();

        limiter.reset();

        assertThat(limiter.allow(key, 3, Duration.ofMinutes(1))).isTrue();
    }

    /** Simple mutable Clock for testing window-expiry behavior deterministically. */
    private static class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
