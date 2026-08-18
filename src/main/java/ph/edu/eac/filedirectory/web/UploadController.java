package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.file.*;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Controller
public class UploadController {

    /** Max number of tags a single upload may carry - see resolveTags() and upload.html's chip input, which enforces the same cap client-side. */
    private static final int MAX_TAGS = 5;

    private final DepartmentRepository departmentRepository;
    private final ProgramRepository programRepository;
    private final CourseRepository courseRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final FileRepository fileRepository;
    private final FileStorageService storageService;

    public UploadController(DepartmentRepository departmentRepository,
                             ProgramRepository programRepository,
                             CourseRepository courseRepository,
                             CategoryRepository categoryRepository,
                             TagRepository tagRepository,
                             FileRepository fileRepository,
                             FileStorageService storageService) {
        this.departmentRepository = departmentRepository;
        this.programRepository = programRepository;
        this.courseRepository = courseRepository;
        this.categoryRepository = categoryRepository;
        this.tagRepository = tagRepository;
        this.fileRepository = fileRepository;
        this.storageService = storageService;
    }

    @GetMapping("/upload")
    public String uploadForm(Model model) {
        model.addAttribute("departments", departmentRepository.findAll(Sort.by("name")));
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        return "upload";
    }

    @GetMapping("/my-uploads")
    public String myUploads(@AuthenticationPrincipal EacUserDetails principal,
                             @RequestParam(defaultValue = "0") int page,
                             Model model) {
        if (principal == null) {
            return "redirect:/login";
        }
        Pageable pageable = PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<FileEntity> files = fileRepository.findByUploaderOrderByCreatedAtDesc(principal.getAppUser(), pageable);
        model.addAttribute("files", files);
        return "my-uploads";
    }

    @GetMapping("/api/programs")
    @ResponseBody
    public List<Program> programsForDepartment(@RequestParam Long departmentId) {
        return programRepository.findByDepartmentId(departmentId);
    }

    @GetMapping("/api/courses")
    @ResponseBody
    public List<Course> coursesForDepartment(@RequestParam Long departmentId) {
        return courseRepository.findByDepartmentId(departmentId);
    }

    @PostMapping("/upload")
    public String submitUpload(@RequestParam String title,
                                @RequestParam(required = false) String description,
                                @RequestParam Long departmentId,
                                @RequestParam(required = false) Long programId,
                                @RequestParam(required = false) Long courseId,
                                @RequestParam Long categoryId,
                                @RequestParam(required = false) String tags,
                                @RequestParam("file") MultipartFile file,
                                @AuthenticationPrincipal EacUserDetails principal,
                                RedirectAttributes redirectAttributes) {

        if (principal == null) {
            return "redirect:/login";
        }
        AppUser uploader = principal.getAppUser();

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please choose a file to upload.");
            return "redirect:/upload";
        }

        if (countTags(tags) > MAX_TAGS) {
            redirectAttributes.addFlashAttribute("errorMessage", "You can add at most " + MAX_TAGS + " tags.");
            return "redirect:/upload";
        }

        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown department"));
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category"));
        Program program = programId != null ? programRepository.findById(programId).orElse(null) : null;
        Course course = courseId != null ? courseRepository.findById(courseId).orElse(null) : null;

        FileStorageService.StoredFile stored = storageService.store(file, department);

        FileEntity entity = new FileEntity(
                title, description, uploader, department, program, course, category,
                stored.relativePath(), file.getOriginalFilename() == null ? "file" : file.getOriginalFilename(),
                stored.size(), file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                stored.checksumSha256());
        entity.setTags(resolveTags(tags));

        if (department.isAutoApprove()) {
            // Department opted into skipping the moderation queue - see
            // AdminController's per-department toggle. approvedBy stays null
            // (no human reviewed it) so the file detail/queue can tell auto-
            // approved uploads apart from a moderator's decision.
            entity.setStatus(FileStatus.APPROVED);
            entity.setApprovedAt(Instant.now());
        }

        fileRepository.save(entity);

        redirectAttributes.addFlashAttribute("infoMessage",
                department.isAutoApprove()
                        ? "Upload received and published immediately - " + department.getName() + " has auto-approval enabled."
                        : "Upload received. It will appear in the directory once a moderator approves it.");
        return "redirect:/my-uploads";
    }

    /** Distinct (case-insensitive), trimmed, non-blank tag names from the raw comma-separated field - shared by countTags() and resolveTags() so the count that's validated is the count that's actually saved. */
    private Set<String> distinctTagNames(String rawTags) {
        Set<String> names = new LinkedHashSet<>();
        if (rawTags == null || rawTags.isBlank()) {
            return names;
        }
        for (String raw : rawTags.split(",")) {
            String name = raw.trim();
            if (name.isEmpty()) continue;
            names.removeIf(existing -> existing.equalsIgnoreCase(name));
            names.add(name);
        }
        return names;
    }

    /** Number of distinct tags a raw comma-separated field would resolve to - see MAX_TAGS. */
    private int countTags(String rawTags) {
        return distinctTagNames(rawTags).size();
    }

    private Set<Tag> resolveTags(String rawTags) {
        Set<Tag> result = new HashSet<>();
        for (String name : distinctTagNames(rawTags)) {
            Tag tag = tagRepository.findByNameIgnoreCase(name)
                    .orElseGet(() -> tagRepository.save(new Tag(name)));
            result.add(tag);
        }
        return result;
    }
}
