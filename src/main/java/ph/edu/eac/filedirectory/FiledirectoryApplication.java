package ph.edu.eac.filedirectory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import ph.edu.eac.filedirectory.onlyoffice.OnlyOfficeProperties;

// @EnableScheduling backs the first (and, as of Phase 9, only) scheduled
// job in this codebase - AccessRequestSweepJob, which flips stale
// APPROVED access requests to EXPIRED. See that class for what it does and
// why nothing else here needed scheduling before now.
//
// @EnableConfigurationProperties(OnlyOfficeProperties.class) - the first
// typed @ConfigurationProperties class in this codebase (everything else
// reads individual values via @Value) - see OnlyOfficeProperties for why a
// grouped record made more sense here than several separate @Value fields.
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OnlyOfficeProperties.class)
public class FiledirectoryApplication {

	public static void main(String[] args) {
		SpringApplication.run(FiledirectoryApplication.class, args);
	}

}
