package com.example.fraudcase.api;

import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.domain.FraudCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Fraud-case intake and summary endpoints. The controller contains no
 * model-specific logic; validation is declarative on the request DTO and
 * server-managed fields are owned by the service layer.
 */
@RestController
@RequestMapping("/api/cases")
public class FraudCaseController {

    private final FraudCaseService fraudCaseService;

    public FraudCaseController(FraudCaseService fraudCaseService) {
        this.fraudCaseService = fraudCaseService;
    }

    @PostMapping
    public ResponseEntity<FraudCaseIntakeResponse> createCase(
            @Valid @RequestBody FraudCaseIntakeRequest request) {
        FraudCase created = fraudCaseService.createCase(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{caseId}")
                .buildAndExpand(created.caseId())
                .toUri();
        return ResponseEntity.created(location).body(new FraudCaseIntakeResponse(
                created.caseId(),
                created.state(),
                created.createdAt(),
                created.analystId()));
    }

    /** Read-only analyst worklist, newest first — masked summaries only. */
    @GetMapping
    public List<CaseSummaryResponse> listCases() {
        return fraudCaseService.listCases().stream()
                .map(FraudCaseController::toSummary)
                .toList();
    }

    @GetMapping("/{caseId}")
    public CaseSummaryResponse getCase(@PathVariable String caseId) {
        return toSummary(fraudCaseService.getCase(caseId));
    }

    private static CaseSummaryResponse toSummary(FraudCase fraudCase) {
        return new CaseSummaryResponse(
                fraudCase.caseId(),
                fraudCase.state(),
                fraudCase.reportedAt(),
                fraudCase.customer().maskedCustomerRef(),
                fraudCase.claim().issueType(),
                fraudCase.transaction().maskedMerchant());
    }
}
