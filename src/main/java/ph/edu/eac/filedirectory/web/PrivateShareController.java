package ph.edu.eac.filedirectory.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.share.PrivateShareService;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Controller
public class PrivateShareController {

    private final FileRepository fileRepository;
    private final PrivateShareService privateShareService;

    public PrivateShareController(FileRepository fileRepository, PrivateShareService privateShareService) {
        this.fileRepository = fileRepository;
        this.privateShareService = privateShareService;
    }

    @PostMapping("/files/{id}/private-shares")
    public String share(@PathVariable Long id, @RequestParam String recipientEmail,
                        @AuthenticationPrincipal EacUserDetails principal, RedirectAttributes redirectAttributes) {
        FileEntity file = fileRepository.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        PrivateShareService.Result result = privateShareService.share(file, requireUser(principal), recipientEmail);
        redirectAttributes.addFlashAttribute(result.changed() ? "infoMessage" : "errorMessage", result.message());
        return "redirect:/files/" + id;
    }

    @PostMapping("/files/{id}/private-shares/{shareId}/revoke")
    public String revoke(@PathVariable Long id, @PathVariable Long shareId,
                         @AuthenticationPrincipal EacUserDetails principal, RedirectAttributes redirectAttributes) {
        FileEntity file = fileRepository.findById(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND));
        PrivateShareService.Result result = privateShareService.revoke(file, shareId, requireUser(principal));
        redirectAttributes.addFlashAttribute(result.changed() ? "infoMessage" : "errorMessage", result.message());
        return "redirect:/files/" + id;
    }

    private ph.edu.eac.filedirectory.user.AppUser requireUser(EacUserDetails principal) {
        if (principal == null) {
            throw new org.springframework.security.access.AccessDeniedException("Sign-in required");
        }
        return principal.getAppUser();
    }
}
