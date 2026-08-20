package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.access.AccessDecisionPolicy;
import ph.edu.eac.filedirectory.access.AccessRequestRepository;
import ph.edu.eac.filedirectory.access.AccessRequestStatus;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.*;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.security.ratelimit.RateLimiter;
import ph.edu.eac.filedirectory.settings.SystemSettingService;
import ph.edu.eac.filedirectory.taxonomy.*;
import ph.edu.eac.filedirectory.user.AppUser;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Controller
public class UploadController {

    /** Max number of tags a single upload may carry - see resolveTags() and upload.html's chip input, which enforces the same cap client-side. */
    private static final int MAX_TAGS = 5;

    /** Per-account upload rate limit - generous enough for a legitimate burst (e.g. submitting several papers at once) while stopping automated flooding. */
    private static final int MAX_UPLOADS_PER_WINDOW = 20;
    private static final Duration UPLOAD_RATE_WINDOW = Duration.ofHours(1);

    private final DepartmentRepository departmentRepository;
    private final ProgramRepository programRepository;
    private final CourseRepository courseRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final FileRepository fileRepository;
    private final FileStorageService storageService;
    private final FileTypeValidator fileTypeValidator;
    private final RateLimiter rateLimiter;
    private final AuditService auditService;
    private final SystemSettingService systemSettingService;
    private final AccessRequestRepository accessRequestRepository;

    public UploadController(DepartmentRepository departmentRepository,
                             ProgramRepository programRepository,
                             CourseRepository courseRepository,
                             CategoryRepository categoryRepository,
                             TagRepository tagRepository,
                             FileRepository fileRepository,
                             FileStorageService storageService,
                             FileTypeValidator fileTypeValidator,
                             RateLimiter rateLimiter,
                             AuditService auditService,
                             SystemSettingService systemSettingService,
                             AccessRequestRepository accessRequestRepository) {
        this.departmentRepository = departmentRepository;
        this.programRepository = programRepository;
        this.courseRepository = courseRepository;
        this.categoryRepository = categoryRepository;
        this.tagRepository = tagRepository;
        this.fileRepository = fileRepository;
        this.storageService = storageService;
        this.fileTypeValidator = fileTypeValidator;
        this.rateLimiter = rateLimiter;
        this.auditService = auditService;
        this.systemSettingService = systemSettingService;
        this.accessRequestRepository = accessRequestRepository;
    }

    @GetMapping("/upload")
    public String uploadForm(Model model) {
        addFormAttributes(model);
        return "upload";
    }

    private void addFormAttributes(Model model) {
        model.addAttribute("departments", departmentRepository.findAll(Sort.by("name")));
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));

        List<String> sortedExtensions = FileTypeValidator.ALLOWED_EXTENSIONS.stream().sorted().toList();
        model.addAttribute("allowedExtensionsLabel",
                sortedExtensions.stream().map(String::toUpperCase).collect(java.util.stream.Collectors.joining(", ")));
        model.addAttribute("acceptAttribute",
                sortedExtensions.stream().map(ext -> "." + ext).collect(java.util.stream.Collectors.joining(",")));
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
        model.addAttribute("pendingAccessRequestCount",
                accessRequestRepository.countByFile_UploaderAndStatus(principal.getAppUser(), AccessRequestStatus.PENDING));
        return "my-uploads";
    }

    /**
     * Lets the uploader change a file's access-request policy after the
     * fact - upload.html only sets it once, at upload time, which was a
     * one-way decision until now. Same MANUAL/AUTO_APPROVE/AUTO_REJECT
     * options as upload time (see AccessDecisionPolicy) - this only ever
     * affects requests made AFTER the change; AccessRequestService.request()
     * reads the file's current policy fresh on every new request, so an
     * already-PENDING request from before the change is untouched and still
     * needs a manual decision (or the moderator fallback), never silently
     * resolved by a policy flip after the fact.
     */
    @PostMapping("/my-uploads/{id}/access-policy")
    public String updateAccessPolicy(@PathVariable Long id,
                                      @RequestParam AccessDecisionPolicy accessDecisionPolicy,
                                      @AuthenticationPrincipal EacUserDetails principal,
                                      RedirectAttributes redirectAttributes) {
        if (principal == null) {
            return "redirect:/login";
        }
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!file.getUploader().getId().equals(principal.getAppUser().getId())) {
            throw new AccessDeniedException("You can only change the access policy on your own uploads.");
        }

        AccessDecisionPolicy previous = file.getAccessDecisionPolicy();
        if (previous != accessDecisionPolicy) {
            file.setAccessDecisionPolicy(accessDecisionPolicy);
            fileRepository.save(file);
            auditService.fileAccessPolicyChanged(principal.getAppUser(), file.getId(), file.getTitle(),
                    previous.name(), accessDecisionPolicy.name());
        }

        redirectAttributes.addFlashAttribute("infoMessage",
                "Access policy for \"" + file.getTitle() + "\" updated.");
        return "redirect:/my-uploads";
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
                                @RequestParam(required = false, defaultValue = "false") boolean confirmDuplicate,
                                @RequestParam(required = false, defaultValue = "MANUAL") AccessDecisionPolicy accessDecisionPolicy,
                                @AuthenticationPrincipal EacUserDetails principal,
                                RedirectAttributes redirectAttributes,
                                Model model) {

        if (principal == null) {
            return "redirect:/login";
        }
        AppUser uploader = principal.getAppUser();

        if (!rateLimiter.allow("upload:" + uploader.getId(), MAX_UPLOADS_PER_WINDOW, UPLOAD_RATE_WINDOW)) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "You've hit the upload limit for now (" + MAX_UPLOADS_PER_WINDOW + " per hour). Please try again later.");
            return "redirect:/upload";
        }

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please choose a file to upload.");
            return "redirect:/upload";
        }

        if (countTags(tags) > MAX_TAGS) {
            redirectAttributes.addFlashAttribute("errorMessage", "You can add at most " + MAX_TAGS + " tags.");
            return "redirect:/upload";
        }

        FileTypeValidator.ValidationResult typeCheck = fileTypeValidator.validate(file);
        if (!typeCheck.valid()) {
            redirectAttributes.addFlashAttribute("errorMessage", typeCheck.reason());
            return "redirect:/upload";
        }

        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown department"));
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category"));
        Program program = programId != null ? programRepository.findById(programId).orElse(null) : null;
        Course course = courseId != null ? courseRepository.findById(courseId).orElse(null) : null;

        // Duplicate detection (see FileRepository.findFirstByChecksum,
        // SystemSetting.allowDuplicateUploads): hash the upload before
        // writing anything to disk, so a duplicate is never stored at all
        // unless the uploader explicitly confirms. The form is re-rendered
        // directly (not a redirect) so the entered title/description/etc.
        // survive the round trip - only the file input itself can't be
        // preserved (browsers never let a server pre-fill a file input), so
        // the uploader has to re-attach the same file to confirm.
        if (!confirmDuplicate && !systemSettingService.isAllowDuplicateUploads()) {
            String checksum = storageService.computeChecksum(file);
            FileEntity existing = fileRepository.findFirstByChecksum(checksum).orElse(null);
            if (existing != null) {
                addFormAttributes(model);
                model.addAttribute("duplicateOf", existing);
                model.addAttribute("title", title);
                model.addAttribute("description", description);
                model.addAttribute("selectedDepartmentId", departmentId);
                model.addAttribute("selectedCategoryId", categoryId);
                model.addAttribute("selectedProgramId", programId);
                model.addAttribute("selectedCourseId", courseId);
                model.addAttribute("tags", tags);
                model.addAttribute("selectedAccessDecisionPolicy", accessDecisionPolicy);
                return "upload";
            }
        }

        FileStorageService.StoredFile stored = storageService.store(file, department);

        FileEntity entity = new FileEntity(
                title, description, uploader, department, program, course, category,
                stored.relativePath(), file.getOriginalFilename() == null ? "file" : file.getOriginalFilename(),
                stored.size(), file.getContentType() == null ? "application/octet-stream" : file.getContentType(),
                stored.checksumSha256());
        entity.setTags(resolveTags(tags));
        entity.setAccessDecisionPolicy(accessDecisionPolicy);

        if (department.isAutoApprove()) {
            // Department opted into skipping the moderation queue - see
            // AdminController's per-department toggle. approvedBy stays null
            // (no human reviewed it) so the file detail/queue can tell auto-
            // approved uploads apart from a moderator's decision.
            entity.setStatus(FileStatus.APPROVED);
            entity.setApprovedAt(Instant.now());
        }

        fileRepository.save(entity);

        auditService.fileUploaded(uploader, entity.getId(), entity.getTitle());

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
