package ph.edu.eac.filedirectory.web;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.file.Tag;
import ph.edu.eac.filedirectory.file.TagRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.Category;
import ph.edu.eac.filedirectory.taxonomy.CategoryRepository;
import ph.edu.eac.filedirectory.taxonomy.Course;
import ph.edu.eac.filedirectory.taxonomy.CourseRepository;
import ph.edu.eac.filedirectory.taxonomy.Department;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.taxonomy.Program;
import ph.edu.eac.filedirectory.taxonomy.ProgramRepository;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.Role;

import java.time.Year;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class FileMetadataController {

    private static final int MAX_TAGS = 5;

    private final FileRepository fileRepository;
    private final DepartmentRepository departmentRepository;
    private final ProgramRepository programRepository;
    private final CourseRepository courseRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;

    public FileMetadataController(FileRepository fileRepository,
                                  DepartmentRepository departmentRepository,
                                  ProgramRepository programRepository,
                                  CourseRepository courseRepository,
                                  CategoryRepository categoryRepository,
                                  TagRepository tagRepository) {
        this.fileRepository = fileRepository;
        this.departmentRepository = departmentRepository;
        this.programRepository = programRepository;
        this.courseRepository = courseRepository;
        this.categoryRepository = categoryRepository;
        this.tagRepository = tagRepository;
    }

    @GetMapping("/files/{id}/edit")
    public String editForm(@PathVariable Long id,
                           @AuthenticationPrincipal EacUserDetails principal,
                           Model model) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        requireCanEdit(file, principal);
        addFormAttributes(model, file);
        return "file-edit";
    }

    @PostMapping("/files/{id}/edit")
    @Transactional
    public String update(@PathVariable Long id,
                         @RequestParam String title,
                         @RequestParam(required = false) String description,
                         @RequestParam(required = false) String authors,
                         @RequestParam(required = false) String adviser,
                         @RequestParam(required = false) Integer academicYear,
                         @RequestParam Long departmentId,
                         @RequestParam(required = false) Long programId,
                         @RequestParam(required = false) Long courseId,
                         @RequestParam Long categoryId,
                         @RequestParam(required = false) String tags,
                         @AuthenticationPrincipal EacUserDetails principal,
                         RedirectAttributes redirectAttributes,
                         Model model) {
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        requireCanEdit(file, principal);

        if (title == null || title.isBlank()) {
            return reject(model, file, "Title is required.");
        }
        if (countTags(tags) > MAX_TAGS) {
            return reject(model, file, "You can add at most " + MAX_TAGS + " tags.");
        }
        if (academicYear != null) {
            int nextYear = Year.now().getValue() + 1;
            if (academicYear < 1900 || academicYear > nextYear) {
                return reject(model, file, "Academic year must be between 1900 and " + nextYear + ".");
            }
        }

        Department department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        Program program = programId != null ? programRepository.findById(programId).orElse(null) : null;
        Course course = courseId != null ? courseRepository.findById(courseId).orElse(null) : null;

        if (program != null && !program.getDepartment().getId().equals(department.getId())) {
            return reject(model, file, "Selected program does not belong to the selected department.");
        }
        if (course != null && !course.getDepartment().getId().equals(department.getId())) {
            return reject(model, file, "Selected course does not belong to the selected department.");
        }

        file.setTitle(title.trim());
        file.setDescription(blankToNull(description));
        file.setAuthors(blankToNull(authors));
        file.setAdviser(blankToNull(adviser));
        file.setAcademicYear(academicYear);
        file.setDepartment(department);
        file.setProgram(program);
        file.setCourse(course);
        file.setCategory(category);
        file.setTags(resolveTags(tags));
        fileRepository.save(file);

        redirectAttributes.addFlashAttribute("infoMessage", "Metadata updated for \"" + file.getTitle() + "\".");
        return "redirect:/files/" + file.getId();
    }

    private String reject(Model model, FileEntity file, String message) {
        model.addAttribute("errorMessage", message);
        addFormAttributes(model, file);
        return "file-edit";
    }

    private void addFormAttributes(Model model, FileEntity file) {
        model.addAttribute("file", file);
        model.addAttribute("departments", departmentRepository.findAll(Sort.by("name")));
        model.addAttribute("categories", categoryRepository.findAll(Sort.by("name")));
        model.addAttribute("programs", file.getDepartment() != null
                ? programRepository.findByDepartmentId(file.getDepartment().getId())
                : java.util.List.of());
        model.addAttribute("courses", file.getDepartment() != null
                ? courseRepository.findByDepartmentId(file.getDepartment().getId())
                : java.util.List.of());
        model.addAttribute("tagsValue", file.getTags().stream()
                .map(Tag::getName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(java.util.stream.Collectors.joining(", ")));
    }

    private void requireCanEdit(FileEntity file, EacUserDetails principal) {
        if (principal == null) {
            throw new AccessDeniedException("Sign-in required");
        }
        AppUser user = principal.getAppUser();
        boolean isOwner = user.getId().equals(file.getUploader().getId());
        boolean isStaff = user.getRole() == Role.MODERATOR || user.getRole() == Role.ADMIN;
        if (file.getStatus() == FileStatus.ARCHIVED && !isStaff) {
            throw new AccessDeniedException("Archived files can only be edited by staff.");
        }
        if (!isOwner && !isStaff) {
            throw new AccessDeniedException("You can only edit metadata for your own uploads.");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

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
