package ph.edu.eac.filedirectory.web;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.audit.AuditService;
import ph.edu.eac.filedirectory.file.Tag;
import ph.edu.eac.filedirectory.file.TagRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.*;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

@Controller
@RequestMapping("/admin/taxonomy")
public class AdminTaxonomyController {

    private final DepartmentRepository departmentRepository;
    private final ProgramRepository programRepository;
    private final CourseRepository courseRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final AuditService auditService;

    public AdminTaxonomyController(DepartmentRepository departmentRepository,
                                   ProgramRepository programRepository,
                                   CourseRepository courseRepository,
                                   CategoryRepository categoryRepository,
                                   TagRepository tagRepository,
                                   AuditService auditService) {
        this.departmentRepository = departmentRepository;
        this.programRepository = programRepository;
        this.courseRepository = courseRepository;
        this.categoryRepository = categoryRepository;
        this.tagRepository = tagRepository;
        this.auditService = auditService;
    }

    @GetMapping
    public String index(Model model) {
        model.addAttribute("departments", departmentRepository.findAll(Sort.by("name")));
        model.addAttribute("programs", programRepository.findAll(Sort.by("name")));
        model.addAttribute("courses", courseRepository.findAll(Sort.by("title")));
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        model.addAttribute("tags", tagRepository.findAll(Sort.by("name")));
        return "admin/taxonomy";
    }

    @PostMapping("/departments")
    @Transactional
    public String createDepartment(@RequestParam String code,
                                   @RequestParam String name,
                                   @RequestParam(defaultValue = "false") boolean autoApprove,
                                   @AuthenticationPrincipal EacUserDetails principal,
                                   RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Department department = new Department(null, clean(code).toUpperCase(), clean(name));
            department.setAutoApprove(autoApprove);
            departmentRepository.save(department);
            auditService.systemSettingChanged(admin, "Department created", null, department.getCode() + " - " + department.getName());
            redirectAttributes.addFlashAttribute("infoMessage", "Department created.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Department was not saved. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/departments/{id}")
    @Transactional
    public String updateDepartment(@PathVariable Long id,
                                   @RequestParam String code,
                                   @RequestParam String name,
                                   @RequestParam(defaultValue = "false") boolean autoApprove,
                                   @AuthenticationPrincipal EacUserDetails principal,
                                   RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Department department = departmentRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            String previous = department.getCode() + " - " + department.getName() + " / autoApprove=" + department.isAutoApprove();
            department.setCode(clean(code).toUpperCase());
            department.setName(clean(name));
            department.setAutoApprove(autoApprove);
            departmentRepository.save(department);
            auditService.systemSettingChanged(admin, "Department updated", previous, department.getCode() + " - " + department.getName() + " / autoApprove=" + department.isAutoApprove());
            redirectAttributes.addFlashAttribute("infoMessage", "Department updated.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Department was not updated. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/programs")
    @Transactional
    public String createProgram(@RequestParam String code,
                                @RequestParam String name,
                                @RequestParam Long departmentId,
                                @RequestParam(required = false) String level,
                                @RequestParam(required = false) Integer durationYears,
                                @AuthenticationPrincipal EacUserDetails principal,
                                RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Department department = departmentRepository.findById(departmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Program program = new Program(null, clean(code).toUpperCase(), clean(name), department, optional(level), durationYears);
            programRepository.save(program);
            auditService.systemSettingChanged(admin, "Program created", null, program.getCode() + " - " + program.getName());
            redirectAttributes.addFlashAttribute("infoMessage", "Program created.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Program was not saved. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/programs/{id}")
    @Transactional
    public String updateProgram(@PathVariable Long id,
                                @RequestParam String code,
                                @RequestParam String name,
                                @RequestParam Long departmentId,
                                @RequestParam(required = false) String level,
                                @RequestParam(required = false) Integer durationYears,
                                @AuthenticationPrincipal EacUserDetails principal,
                                RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Program program = programRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Department department = departmentRepository.findById(departmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            String previous = program.getCode() + " - " + program.getName();
            program.setCode(clean(code).toUpperCase());
            program.setName(clean(name));
            program.setDepartment(department);
            program.setLevel(optional(level));
            program.setDurationYears(durationYears);
            programRepository.save(program);
            auditService.systemSettingChanged(admin, "Program updated", previous, program.getCode() + " - " + program.getName());
            redirectAttributes.addFlashAttribute("infoMessage", "Program updated.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Program was not updated. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/courses")
    @Transactional
    public String createCourse(@RequestParam String code,
                               @RequestParam String title,
                               @RequestParam Long departmentId,
                               @RequestParam(required = false) String description,
                               @AuthenticationPrincipal EacUserDetails principal,
                               RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Department department = departmentRepository.findById(departmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Course course = new Course(null, clean(code).toUpperCase(), clean(title), department, optional(description));
            courseRepository.save(course);
            auditService.systemSettingChanged(admin, "Course created", null, course.getCode() + " - " + course.getTitle());
            redirectAttributes.addFlashAttribute("infoMessage", "Course created.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Course was not saved. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/courses/{id}")
    @Transactional
    public String updateCourse(@PathVariable Long id,
                               @RequestParam String code,
                               @RequestParam String title,
                               @RequestParam Long departmentId,
                               @RequestParam(required = false) String description,
                               @AuthenticationPrincipal EacUserDetails principal,
                               RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Course course = courseRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            Department department = departmentRepository.findById(departmentId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            String previous = course.getCode() + " - " + course.getTitle();
            course.setCode(clean(code).toUpperCase());
            course.setTitle(clean(title));
            course.setDepartment(department);
            course.setDescription(optional(description));
            courseRepository.save(course);
            auditService.systemSettingChanged(admin, "Course updated", previous, course.getCode() + " - " + course.getTitle());
            redirectAttributes.addFlashAttribute("infoMessage", "Course updated.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Course was not updated. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/categories")
    @Transactional
    public String createCategory(@RequestParam String name,
                                 @RequestParam(required = false) String icon,
                                 @AuthenticationPrincipal EacUserDetails principal,
                                 RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Category category = new Category(clean(name), optional(icon));
            categoryRepository.save(category);
            auditService.systemSettingChanged(admin, "Category created", null, category.getName());
            redirectAttributes.addFlashAttribute("infoMessage", "Category created.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Category was not saved. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/categories/{id}")
    @Transactional
    public String updateCategory(@PathVariable Long id,
                                 @RequestParam String name,
                                 @RequestParam(required = false) String icon,
                                 @AuthenticationPrincipal EacUserDetails principal,
                                 RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Category category = categoryRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            String previous = category.getName();
            category.setName(clean(name));
            category.setIcon(optional(icon));
            categoryRepository.save(category);
            auditService.systemSettingChanged(admin, "Category updated", previous, category.getName());
            redirectAttributes.addFlashAttribute("infoMessage", "Category updated.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Category was not updated. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/tags")
    @Transactional
    public String createTag(@RequestParam String name,
                            @AuthenticationPrincipal EacUserDetails principal,
                            RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Tag tag = new Tag(clean(name).toLowerCase());
            tagRepository.save(tag);
            auditService.systemSettingChanged(admin, "Tag created", null, tag.getName());
            redirectAttributes.addFlashAttribute("infoMessage", "Tag created.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Tag was not saved. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    @PostMapping("/tags/{id}")
    @Transactional
    public String updateTag(@PathVariable Long id,
                            @RequestParam String name,
                            @AuthenticationPrincipal EacUserDetails principal,
                            RedirectAttributes redirectAttributes) {
        AppUser admin = requireAdmin(principal);
        try {
            Tag tag = tagRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            String previous = tag.getName();
            tag.setName(clean(name).toLowerCase());
            tagRepository.save(tag);
            auditService.systemSettingChanged(admin, "Tag updated", previous, tag.getName());
            redirectAttributes.addFlashAttribute("infoMessage", "Tag updated.");
        } catch (IllegalArgumentException | DataIntegrityViolationException e) {
            redirectAttributes.addFlashAttribute("errorMessage", "Tag was not updated. Check for blank or duplicate values.");
        }
        return "redirect:/admin/taxonomy";
    }

    private String clean(String value) {
        if (value == null || value.trim().isBlank()) {
            throw new IllegalArgumentException("Required value is blank");
        }
        return value.trim();
    }

    private String optional(String value) {
        return value == null || value.trim().isBlank() ? null : value.trim();
    }

    private AppUser requireAdmin(EacUserDetails principal) {
        if (principal == null || principal.getAppUser().getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return principal.getAppUser();
    }
}
