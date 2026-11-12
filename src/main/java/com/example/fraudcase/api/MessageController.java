package com.example.fraudcase.api;

import com.example.fraudcase.api.MessageResponses.CaseMessageResponse;
import com.example.fraudcase.api.MessageResponses.CaseMessageView;
import com.example.fraudcase.application.CaseMessageService;
import com.example.fraudcase.application.CaseMessageService.MessageResult;
import com.example.fraudcase.domain.FollowUpAnswer;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Case-scoped conversational follow-up endpoints (Phase 5).
 *
 * <p>Case scope comes exclusively from the URL path — the application
 * resolves and verifies the case, then uses that trusted scope for every
 * memory operation. Nothing client- or model-supplied can redirect memory
 * to another case.
 */
@RestController
@RequestMapping("/api/cases/{caseId}/messages")
public class MessageController {

    private final CaseMessageService messageService;

    public MessageController(CaseMessageService messageService) {
        this.messageService = messageService;
    }

    @PostMapping
    public ResponseEntity<CaseMessageResponse> postMessage(
            @PathVariable String caseId,
            @Valid @RequestBody CaseMessageRequest request) {
        MessageResult result = messageService.submit(caseId, request);
        FollowUpAnswer answer = result.answer();
        return ResponseEntity.ok(new CaseMessageResponse(
                result.assistantMessage().messageId(),
                result.userMessage().messageId(),
                caseId,
                answer.answer(),
                answer.evidenceIds(),
                answer.policyCitations(),
                answer.missingInformation(),
                answer.humanReviewRequired(),
                result.answerSource(),
                result.idempotentReplay(),
                result.assistantMessage().createdAt()));
    }

    /**
     * Safe read of the case's persisted conversation — user-visible turns
     * only, deterministic order, strictly case-scoped.
     */
    @GetMapping
    public List<CaseMessageView> listMessages(@PathVariable String caseId) {
        return messageService.history(caseId).stream()
                .map(m -> new CaseMessageView(
                        m.messageId(), m.caseId(), m.role(), m.content(),
                        m.sequenceNumber(), m.createdAt()))
                .toList();
    }
}
