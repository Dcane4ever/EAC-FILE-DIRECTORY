package ph.edu.eac.filedirectory.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.search.SearchQueryRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.CategoryRepository;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class HomeController {

    /**
     * Landing-page "Browse by School" picks a fixed, curated set of real
     * seeded departments (by code) rather than all 18 - matches the
     * reference design's 5-card layout. "View all" links to the full
     * /browse tree for the rest.
     */
    private static final List<String> FEATURED_DEPARTMENT_CODES =
            List.of("BUS", "ENGR", "EDUC", "NURS", "SAS");

    /** Fallback shown until real, site-wide search activity is enough to be meaningful - see buildPopularSearches. */
    private static final List<String> FALLBACK_POPULAR_SEARCHES =
            List.of("Thesis", "Capstone", "Marketing", "ICT", "Business");

    private static final int POPULAR_SEARCH_WINDOW_DAYS = 30;
    private static final int POPULAR_SEARCH_COUNT = 5;
    /** A term needs at least this many searches in the window to count as "popular" - keeps one person's one-off search from dominating. */
    private static final long POPULAR_SEARCH_MIN_COUNT = 2;

    private final FileRepository fileRepository;
    private final CategoryRepository categoryRepository;
    private final DepartmentRepository departmentRepository;
    private final SearchQueryRepository searchQueryRepository;

    public HomeController(FileRepository fileRepository,
                           CategoryRepository categoryRepository,
                           DepartmentRepository departmentRepository,
                           SearchQueryRepository searchQueryRepository) {
        this.fileRepository = fileRepository;
        this.categoryRepository = categoryRepository;
        this.departmentRepository = departmentRepository;
        this.searchQueryRepository = searchQueryRepository;
    }

    public record StatTile(String count, String label, String description, String icon) {
    }

    public record SchoolCard(Long departmentId, String name, long documentCount, String icon) {
    }

    @GetMapping("/")
    public String root(@AuthenticationPrincipal EacUserDetails principal, Model model) {
        if (principal != null) {
            return isStaff(principal) ? "redirect:/admin/home" : "redirect:/home";
        }
        model.addAttribute("stats", buildStats());
        model.addAttribute("schools", buildFeaturedSchools());
        return "landing";
    }

    @GetMapping("/home")
    public String home(@AuthenticationPrincipal EacUserDetails principal, Model model) {
        if (principal != null) {
            // Staff (MODERATOR/ADMIN) get their own landing page - see
            // AdminHomeController / RoleBasedLoginSuccessHandler. Keeps the
            // separation total: an admin account never sees the student
            // browse/upload experience, even by navigating here directly.
            if (isStaff(principal)) {
                return "redirect:/admin/home";
            }
            model.addAttribute("user", principal.getAppUser());
        }
        model.addAttribute("stats", buildStats());
        model.addAttribute("schools", buildFeaturedSchools());
        model.addAttribute("popularSearches", buildPopularSearches());
        return "home";
    }

    private boolean isStaff(EacUserDetails principal) {
        var role = principal.getAppUser().getRole();
        return role == ph.edu.eac.filedirectory.user.Role.ADMIN || role == ph.edu.eac.filedirectory.user.Role.MODERATOR;
    }

    /**
     * Site-wide trending search terms from the last POPULAR_SEARCH_WINDOW_DAYS
     * days, aggregated across every user (see SearchQueryRepository.topTerms) -
     * not personalized, not local-only. Falls back to a small curated list
     * until there's enough real search volume to be meaningful, so the
     * section is never empty on a fresh install.
     */
    private List<String> buildPopularSearches() {
        Instant since = Instant.now().minus(POPULAR_SEARCH_WINDOW_DAYS, ChronoUnit.DAYS);
        List<SearchQueryRepository.TrendingTerm> trending = searchQueryRepository.topTerms(
                since, POPULAR_SEARCH_MIN_COUNT, org.springframework.data.domain.PageRequest.of(0, POPULAR_SEARCH_COUNT));

        if (trending.size() < POPULAR_SEARCH_COUNT) {
            return FALLBACK_POPULAR_SEARCHES;
        }
        return trending.stream().map(SearchQueryRepository.TrendingTerm::getDisplayTerm).collect(Collectors.toList());
    }

    private List<StatTile> buildStats() {
        List<StatTile> stats = new ArrayList<>();
        stats.add(new StatTile(
                format(countForCategories("Research Paper")),
                "Research Papers", "Academic & student works", "file-text"));
        stats.add(new StatTile(
                format(countForCategories("Thesis", "Capstone Project")),
                "Theses & Dissertations", "Undergraduate & graduate", "book-open"));
        stats.add(new StatTile(
                format(countForCategories("Code / Software Project")),
                "Codes & Projects", "Open source & academic", "code"));
        stats.add(new StatTile(
                format(countForCategories("Presentation (PPTX)")),
                "Presentations", "Slides & reports", "monitor"));
        return stats;
    }

    private long countForCategories(String... names) {
        List<Long> ids = List.of(names).stream()
                .map(categoryRepository::findByName)
                .filter(java.util.Optional::isPresent)
                .map(o -> o.get().getId())
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return 0;
        }
        return fileRepository.countByStatusAndCategoryIdIn(FileStatus.APPROVED, ids);
    }

    private String format(long count) {
        return String.format("%,d", count);
    }

    private List<SchoolCard> buildFeaturedSchools() {
        List<SchoolCard> cards = new ArrayList<>();
        for (String code : FEATURED_DEPARTMENT_CODES) {
            departmentRepository.findByCode(code).ifPresent(dept -> {
                long count = fileRepository.countByStatusAndDepartmentId(FileStatus.APPROVED, dept.getId());
                cards.add(new SchoolCard(dept.getId(), displayName(dept), count, iconFor(code)));
            });
        }
        return cards;
    }

    /** Shorten the long official department names to match the reference card style. */
    private String displayName(Department dept) {
        return switch (dept.getCode()) {
            case "BUS" -> "School of Business";
            case "ENGR" -> "School of Computing";
            case "EDUC" -> "School of Education";
            case "NURS" -> "School of Health Sciences";
            case "SAS" -> "School of Arts & Sciences";
            default -> dept.getName();
        };
    }

    private String iconFor(String code) {
        return switch (code) {
            case "BUS" -> "landmark";
            case "ENGR" -> "cpu";
            case "EDUC" -> "book-open";
            case "NURS" -> "heart-pulse";
            case "SAS" -> "university";
            default -> "building";
        };
    }
}
