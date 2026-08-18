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
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.search.SearchQuery;
import ph.edu.eac.filedirectory.search.SearchQueryRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.List;

/**
 * Public browse/search surface: department -> program -> course tree,
 * category filter, tag/text search - all scoped to APPROVED files only.
 * Every submitted text search is logged (see SearchQueryRepository) to
 * power the site-wide "Popular searches" on the home page.
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

    public BrowseController(DepartmentRepository departmentRepository,
                             ProgramRepository programRepository,
                             CourseRepository courseRepository,
                             CategoryRepository categoryRepository,
                             FileRepository fileRepository,
                             SearchQueryRepository searchQueryRepository) {
        this.departmentRepository = departmentRepository;
        this.programRepository = programRepository;
        this.courseRepository = courseRepository;
        this.categoryRepository = categoryRepository;
        this.fileRepository = fileRepository;
        this.searchQueryRepository = searchQueryRepository;
    }

    @GetMapping("/browse")
    public String browse(@RequestParam(required = false) Long departmentId,
                          @RequestParam(required = false) Long programId,
                          @RequestParam(required = false) Long courseId,
                          @RequestParam(required = false) Long categoryId,
                          @RequestParam(required = false) String q,
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

        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FileEntity> files;
        if (q != null && !q.isBlank()) {
            String trimmed = q.trim();
            files = fileRepository.search(FileStatus.APPROVED, trimmed, pageable);
            // Log only the first page of a search, not every page flip through
            // the same query, so pagination doesn't inflate a term's count.
            if (page == 0) {
                logSearch(trimmed, principal);
            }
        } else if (courseId != null) {
            files = fileRepository.findByStatusAndCourseId(FileStatus.APPROVED, courseId, pageable);
        } else if (programId != null) {
            files = fileRepository.findByStatusAndProgramId(FileStatus.APPROVED, programId, pageable);
        } else if (categoryId != null) {
            files = fileRepository.findByStatusAndCategoryId(FileStatus.APPROVED, categoryId, pageable);
        } else if (departmentId != null) {
            files = fileRepository.findByStatusAndDepartmentId(FileStatus.APPROVED, departmentId, pageable);
        } else {
            files = fileRepository.findByStatus(FileStatus.APPROVED, pageable);
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
        model.addAttribute("q", q);

        return "browse";
    }

    private void logSearch(String rawTerm, EacUserDetails principal) {
        String normalized = rawTerm.toLowerCase();
        AppUser searcher = principal != null ? principal.getAppUser() : null;
        searchQueryRepository.save(new SearchQuery(rawTerm, normalized, searcher));
    }
}
