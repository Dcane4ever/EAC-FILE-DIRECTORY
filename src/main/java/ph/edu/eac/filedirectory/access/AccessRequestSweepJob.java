package ph.edu.eac.filedirectory.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs AccessRequestService.expireStaleGrants() periodically, so an
 * approved-but-never-downloaded request's status catches up with reality
 * (its grant token has expired) without a human having to notice. The
 * first @Scheduled job in this codebase (see FiledirectoryApplication's
 * @EnableScheduling) - every other repeated-check need so far (rate
 * limits, cooldowns) has been handled inline at request time instead,
 * which works for those but not here: nobody visits "My Requests" to
 * trigger the check on a request they've forgotten about, so this has to
 * run on its own.
 */
@Component
public class AccessRequestSweepJob {

    private static final Logger log = LoggerFactory.getLogger(AccessRequestSweepJob.class);

    private final AccessRequestService accessRequestService;

    public AccessRequestSweepJob(AccessRequestService accessRequestService) {
        this.accessRequestService = accessRequestService;
    }

    @Scheduled(fixedRate = 30, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void run() {
        int expired = accessRequestService.expireStaleGrants();
        if (expired > 0) {
            log.info("Expired {} stale access request(s).", expired);
        }
    }
}
