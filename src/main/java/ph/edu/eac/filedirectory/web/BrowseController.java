package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ph.edu.eac.filedirectory.file.*;
import ph.edu.eac.filedirectory.search.SearchQuery;
import ph.edu.eac.filedirectory.search.SearchQueryRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.*;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

import java.time.Year;
import java.util.List;

/**
 * Public browse/search surface: department -> program -> course tree,
 * category/tag/year/file-type/uploader filters, and free-text search - all
 * scoped to APPROVED files only, and all AND-combinable via
 * FileSpecifications (a department + a query + a year together narrow to
 * files matching all three, not just the most specific one - see
 * FileSpecifications' class comment for the precedence-chain behavior this
 * replaced). Every submitted text search is logged (see
 * SearchQueryRepository) to power the site-wide "Popular searches" on the
 * home page.
 */
@Controller
public class BrowseController {

    private static final int PAGE_SIZE = 20;

    private final DepartmentRepository departmentRepository;
    private final ProgramRepository programRepository;
    private final CourseRepository courseRepository;
    private final CategoryRepository categoryRepository;
    private final FileRepository fileRepository;
    private final SearchQueryRepository searchQueryRepository;
    private final AppUserRepository userRepository;

    public BrowseController(DepartmentRepository departmentRepository,
                             ProgramRepository programRepository,
                             CourseRepository courseRepository,
                             CategoryRepository categoryRepository,
                             FileRepository fileRepository,
                             SearchQueryRepository searchQueryRepository,
                             AppUserRepository userRepository) {
        this.departmentRepository = departmentRepository;
        this.programRepository = programRepository;
        this.courseRepository = courseRepository;
        this.categoryRepository = categoryRepository;
        this.fileRepository = fileRepository;
        this.searchQueryRepository = searchQueryRepository;
        this.userRepository = userRepository;
    }

    @GetMapping("/browse")
    public String browse(@RequestParam(required = false) Long departmentId,
                          @RequestParam(required = false) Long programId,
                          @RequestParam(required = false) Long courseId,
                          @RequestParam(required = false) Long categoryId,
                          @RequestParam(required = false) String tag,
                          @RequestParam(required = false) Integer year,
                          @RequestParam(required = false) String fileType,
                          @RequestParam(required = false) Long uploaderId,
                          @RequestParam(required = false) String q,
                          @RequestParam(required = false) String sort,
                          @RequestParam(defaultValue = "0") int page,
                          @AuthenticationPrincipal EacUserDetails principal,
                          Model model) {

        List<Department> departments = departmentRepository.findAll(Sort.by("name"));
        List<Category> categories = categoryRepository.findAll(Sort.by("name"));
        List<Program> programs = departmentId != null
                ? programRepository.findByDepartmentId(departmentId)
                : List.of();
        List<Course> courses = departmentId != null
                ? courseRepository.findByDepartmentId(departmentId)
                : List.of();

        String trimmedQuery = q != null && !q.isBlank() ? q.trim() : null;
        FileSearchCriteria criteria = new FileSearchCriteria(
                trimmedQuery, departmentId, programId, courseId, categoryId, tag, year, fileType, uploaderId);

        FileSortOption sortOption = FileSortOption.fromParam(sort);
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, sortOption.toSort());

        Page<FileEntity> files = fileRepository.findAll(
                FileSpecifications.matching(FileStatus.APPROVED, criteria), pageable);

        // Log only the first page of a search, not every page flip/sort
        // change through the same query, so pagination doesn't inflate a
        // term's recorded count.
        if (trimmedQuery != null && page == 0) {
            logSearch(trimmedQuery, principal);
        }

        model.addAttribute("departments", departments);
        model.addAttribute("categories", categories);
        model.addAttribute("programs", programs);
        model.addAttribute("courses", courses);
        model.addAttribute("files", files);
        model.addAttribute("selectedDepartmentId", departmentId);
        model.addAttribute("selectedProgramId", programId);
        model.addAttribute("selectedCourseId", courseId);
        model.addAttribute("selectedCategoryId", categoryId);
        model.addAttribute("tag", tag);
        model.addAttribute("year", year);
        model.addAttribute("fileType", fileType);
        model.addAttribute("uploaderId", uploaderId);
        model.addAttribute("uploaderName", uploaderId != null
                ? userRepository.findById(uploaderId).map(AppUser::getFullName).orElse(null)
                : null);
        model.addAttribute("q", q);
        model.addAttribute("sort", sortOption.name());
        model.addAttribute("sortOptions", FileSortOption.values());
        model.addAttribute("fileTypeOptions", FileTypeValidator.ALLOWED_EXTENSIONS.stream().sorted().toList());
        model.addAttribute("yearOptions", recentYears());

        return "browse";
    }

    private List<Integer> recentYears() {
        int currentYear = Year.now().getValue();
        return java.util.stream.IntStream.rangeClosed(currentYear - 9, currentYear)
                .boxed()
                .sorted(java.util.Collections.reverseOrder())
                .toList();
    }

    private void logSearch(String rawTerm, EacUserDetails principal) {
        String normalized = rawTerm.toLowerCase();
        AppUser searcher = principal != null ? principal.getAppUser() : null;
        searchQueryRepository.save(new SearchQuery(rawTerm, normalized, searcher));
    }
}
