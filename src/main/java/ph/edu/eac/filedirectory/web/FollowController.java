package ph.edu.eac.filedirectory.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.file.TagRepository;
import ph.edu.eac.filedirectory.follow.FollowService;
import ph.edu.eac.filedirectory.follow.FollowType;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.taxonomy.DepartmentRepository;
import ph.edu.eac.filedirectory.user.AppUser;
import ph.edu.eac.filedirectory.user.AppUserRepository;

@Controller
public class FollowController {

    private final FollowService followService;
    private final DepartmentRepository departmentRepository;
    private final TagRepository tagRepository;
    private final AppUserRepository userRepository;

    public FollowController(FollowService followService, DepartmentRepository departmentRepository,
                            TagRepository tagRepository, AppUserRepository userRepository) {
        this.followService = followService;
        this.departmentRepository = departmentRepository;
        this.tagRepository = tagRepository;
        this.userRepository = userRepository;
    }

    @PostMapping("/follow/{type}/{targetId}")
    public String follow(@PathVariable FollowType type, @PathVariable Long targetId,
                         @RequestParam(required = false) String returnTo,
                         @AuthenticationPrincipal EacUserDetails principal,
                         RedirectAttributes redirectAttributes) {
        AppUser follower = requireUser(principal);
        validateTarget(type, targetId, follower);
        boolean created = followService.follow(follower, type, targetId);
        redirectAttributes.addFlashAttribute("infoMessage", created ? "You are now following this." : "You are already following this.");
        return "redirect:" + safeReturnTo(returnTo, type, targetId);
    }

    @PostMapping("/unfollow/{type}/{targetId}")
    public String unfollow(@PathVariable FollowType type, @PathVariable Long targetId,
                           @RequestParam(required = false) String returnTo,
                           @AuthenticationPrincipal EacUserDetails principal,
                           RedirectAttributes redirectAttributes) {
        AppUser follower = requireUser(principal);
        followService.unfollow(follower, type, targetId);
        redirectAttributes.addFlashAttribute("infoMessage", "You are no longer following this.");
        return "redirect:" + safeReturnTo(returnTo, type, targetId);
    }

    @GetMapping("/following")
    public String following(@AuthenticationPrincipal EacUserDetails principal, Model model) {
        AppUser follower = requireUser(principal);
        model.addAttribute("follows", followService.followsFor(follower).stream()
                .map(follow -> FollowRow.from(follow, departmentRepository, tagRepository, userRepository))
                .filter(java.util.Objects::nonNull)
                .toList());
        return "following";
    }

    private void validateTarget(FollowType type, Long targetId, AppUser follower) {
        boolean exists = switch (type) {
            case DEPARTMENT -> departmentRepository.existsById(targetId);
            case TAG -> tagRepository.existsById(targetId);
            case UPLOADER -> userRepository.existsById(targetId);
        };
        if (!exists) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (type == FollowType.UPLOADER && follower.getId().equals(targetId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "You cannot follow yourself.");
        }
    }

    private String safeReturnTo(String returnTo, FollowType type, Long targetId) {
        if (returnTo != null && returnTo.matches("/(files|users)/\\d+|/following|/departments")) {
            return returnTo;
        }
        return switch (type) {
            case UPLOADER -> "/users/" + targetId;
            case DEPARTMENT, TAG -> "/following";
        };
    }

    private AppUser requireUser(EacUserDetails principal) {
        if (principal == null) {
            throw new AccessDeniedException("Sign-in required");
        }
        return principal.getAppUser();
    }

    public record FollowRow(FollowType type, Long targetId, String label, String description, String targetUrl) {
        static FollowRow from(ph.edu.eac.filedirectory.follow.Follow follow,
                              DepartmentRepository departments, TagRepository tags, AppUserRepository users) {
            return switch (follow.getType()) {
                case DEPARTMENT -> departments.findById(follow.getTargetId())
                        .map(department -> new FollowRow(FollowType.DEPARTMENT, department.getId(), department.getName(),
                                "Department", "/browse?departmentId=" + department.getId()))
                        .orElse(null);
                case TAG -> tags.findById(follow.getTargetId())
                        .map(tag -> new FollowRow(FollowType.TAG, tag.getId(), "#" + tag.getName(),
                                "Tag", "/browse?tag=" + tag.getName()))
                        .orElse(null);
                case UPLOADER -> users.findById(follow.getTargetId())
                        .map(user -> new FollowRow(FollowType.UPLOADER, user.getId(), user.getFullName(),
                                "Uploader", "/users/" + user.getId()))
                        .orElse(null);
            };
        }
    }
}
