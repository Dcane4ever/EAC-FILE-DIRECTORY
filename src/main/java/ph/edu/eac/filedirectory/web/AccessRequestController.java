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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import ph.edu.eac.filedirectory.access.AccessGrantToken;
import ph.edu.eac.filedirectory.access.AccessGrantTokenRepository;
import ph.edu.eac.filedirectory.access.AccessRequest;
import ph.edu.eac.filedirectory.access.AccessRequestRepository;
import ph.edu.eac.filedirectory.access.AccessRequestService;
import ph.edu.eac.filedirectory.access.AccessRequestStatus;
import ph.edu.eac.filedirectory.file.FileEntity;
import ph.edu.eac.filedirectory.file.FileRepository;
import ph.edu.eac.filedirectory.file.FileStatus;
import ph.edu.eac.filedirectory.security.EacUserDetails;
import ph.edu.eac.filedirectory.user.AppUser;

import java.util.HashMap;
import java.util.Map;

/**
 * The access-request HTTP surface - see AccessRequestService for the actual
 * state machine this drives. Requesting access is only meaningful for an
 * APPROVED file the requester doesn't already have full access to (their
 * own upload, or as a moderator/admin) - see FileDetailController's
 * requireViewable for the existing visibility rule this builds on top of.
 */
@Controller
public class AccessRequestController {

    private final AccessRequestService accessRequestService;
    private final AccessRequestRepository accessRequestRepository;
    private final AccessGrantTokenRepository accessGrantTokenRepository;
    private final FileRepository fileRepository;

    public AccessRequestController(AccessRequestService accessRequestService,
                                    AccessRequestRepository accessRequestRepository,
                                    AccessGrantTokenRepository accessGrantTokenRepository,
                                    FileRepository fileRepository) {
        this.accessRequestService = accessRequestService;
        this.accessRequestRepository = accessRequestRepository;
        this.accessGrantTokenRepository = accessGrantTokenRepository;
        this.fileRepository = fileRepository;
    }

    @PostMapping("/files/{id}/request-access")
    public String requestAccess(@PathVariable Long id,
                                 @AuthenticationPrincipal EacUserDetails principal,
                                 RedirectAttributes redirectAttributes) {
        AppUser requester = requireSelf(principal);
        FileEntity file = fileRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (file.getStatus() != FileStatus.APPROVED) {
            redirectAttributes.addFlashAttribute("errorMessage", "This file isn't published yet.");
            return "redirect:/files/" + id;
        }

        AccessRequestService.RequestResult result = accessRequestService.request(file, requester);
        redirectAttributes.addFlashAttribute(result.created() ? "infoMessage" : "errorMessage",
                result.created() ? "Access request sent to the uploader." : result.errorMessage());
        return "redirect:/files/" + id;
    }

    @GetMapping("/my-requests")
    public String myRequests(@AuthenticationPrincipal EacUserDetails principal,
                              @RequestParam(defaultValue = "0") int page,
                              Model model) {
        AppUser requester = requireSelf(principal);
        Pageable pageable = PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AccessRequest> requests = accessRequestRepository.findByRequesterOrderByCreatedAtDesc(requester, pageable);
        model.addAttribute("requests", requests);

        // Grant tokens are looked up separately (one-to-one from the token
        // side, not a field on AccessRequest itself - see AccessGrantToken)
        // and keyed by request id so the template can find each request's
        // download link without an N+1 query per row.
        Map<Long, AccessGrantToken> grantTokensByRequestId = new HashMap<>();
        for (AccessRequest req : requests.getContent()) {
            if (req.getStatus() == AccessRequestStatus.APPROVED) {
                accessGrantTokenRepository.findByAccessRequest(req)
                        .ifPresent(token -> grantTokensByRequestId.put(req.getId(), token));
            }
        }
        model.addAttribute("grantTokensByRequestId", grantTokensByRequestId);
        return "my-requests";
    }

    @GetMapping("/my-uploads/access-requests")
    public String accessRequestsOnMyUploads(@AuthenticationPrincipal EacUserDetails principal,
                                             @RequestParam(defaultValue = "0") int page,
                                             Model model) {
        AppUser uploader = requireSelf(principal);
        Pageable pageable = PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AccessRequest> requests = accessRequestRepository.findByFile_UploaderOrderByCreatedAtDesc(uploader, pageable);
        model.addAttribute("requests", requests);
        model.addAttribute("pendingCount", accessRequestRepository.countByFile_UploaderAndStatus(uploader, AccessRequestStatus.PENDING));
        return "my-uploads-access-requests";
    }

    @PostMapping("/files/access-requests/{id}/approve")
    public String approve(@PathVariable Long id,
                           @AuthenticationPrincipal EacUserDetails principal,
                           RedirectAttributes redirectAttributes) {
        AppUser actor = requireSelf(principal);
        AccessRequest accessRequest = accessRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        AccessRequestService.RequestResult result = accessRequestService.approve(accessRequest, actor);
        redirectAttributes.addFlashAttribute(result.created() ? "infoMessage" : "errorMessage",
                result.created() ? "Access approved." : result.errorMessage());
        return "redirect:/my-uploads/access-requests";
    }

    @PostMapping("/files/access-requests/{id}/deny")
    public String deny(@PathVariable Long id,
                        @RequestParam(required = false) String reason,
                        @AuthenticationPrincipal EacUserDetails principal,
                        RedirectAttributes redirectAttributes) {
        AppUser actor = requireSelf(principal);
        AccessRequest accessRequest = accessRequestRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        AccessRequestService.RequestResult result = accessRequestService.deny(accessRequest, actor, reason);
        redirectAttributes.addFlashAttribute(result.created() ? "infoMessage" : "errorMessage",
                result.created() ? "Access denied." : result.errorMessage());
        return "redirect:/my-uploads/access-requests";
    }

    private AppUser requireSelf(EacUserDetails principal) {
        if (principal == null) {
            throw new AccessDeniedException("Sign-in required");
        }
        return principal.getAppUser();
    }
}
