package com.example.fraudcase.ui;

import com.example.fraudcase.application.FraudCaseService;
import com.example.fraudcase.application.InvestigationService;
import com.example.fraudcase.application.RecommendationValidationException;
import com.example.fraudcase.config.CapstoneProperties;
import com.example.fraudcase.domain.CardPossession;
import com.example.fraudcase.domain.FraudCase;
import com.example.fraudcase.domain.IssueType;
import com.example.fraudcase.domain.MerchantRecognition;
import com.example.fraudcase.domain.ReportChannel;
import com.example.fraudcase.domain.TransactionChannel;
import com.example.fraudcase.domain.TransactionStatus;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Clock;
import java.time.OffsetDateTime;

/**
 * Screen 1 — Case Intake. Thin MVC controller: validation comes from the
 * shared constraint annotations on {@link IntakeForm}; persistence and
 * investigation go through the same application services the JSON API
 * uses. No business logic lives here.
 */
@Controller
@RequestMapping("/ui/cases")
public class CaseIntakeUiController {

    private final FraudCaseService fraudCaseService;
    private final InvestigationService investigationService;
    private final CapstoneProperties properties;
    private final Clock clock;

    public CaseIntakeUiController(FraudCaseService fraudCaseService,
                                  InvestigationService investigationService,
                                  CapstoneProperties properties,
                                  Clock clock) {
        this.fraudCaseService = fraudCaseService;
        this.investigationService = investigationService;
        this.properties = properties;
        this.clock = clock;
    }

    @ModelAttribute
    void sharedEnums(Model model) {
        model.addAttribute("issueTypes", IssueType.values());
        model.addAttribute("reportChannels", ReportChannel.values());
        model.addAttribute("transactionStatuses", TransactionStatus.values());
        model.addAttribute("transactionChannels", TransactionChannel.values());
        model.addAttribute("cardPossessions", CardPossession.values());
        model.addAttribute("merchantRecognitions", MerchantRecognition.values());
        model.addAttribute("processorMode", properties.getProcessorMode());
        model.addAttribute("nav", java.util.List.of(
                new UiModels.NavItem("intake", "1 Case Intake",
                        "/ui/cases/new", "Current", true, true),
                new UiModels.NavItem("investigation", "2 Investigation",
                        "#", "Not available", false, false),
                new UiModels.NavItem("recommendation", "3 Recommendation",
                        "#", "Not available", false, false),
                new UiModels.NavItem("evidence", "4 Evidence",
                        "#", "Not available", false, false),
                new UiModels.NavItem("policy", "5 Policy Citations",
                        "#", "Not available", false, false),
                new UiModels.NavItem("review", "6 Human Review",
                        "#", "Not available", false, false)));
    }

    @GetMapping("/new")
    public String intakeForm(Model model) {
        if (!model.containsAttribute("intakeForm")) {
            model.addAttribute("intakeForm", new IntakeForm());
        }
        return "cases/intake";
    }

    /**
     * Creates the validated case, then optionally starts the bounded
     * investigation (the "Start Investigation" intent). A separate draft
     * state does not exist in the case model, so this is the only write
     * path — the template communicates that honestly.
     */
    @PostMapping
    public String submit(@Valid @ModelAttribute("intakeForm") IntakeForm form,
                         BindingResult binding,
                         RedirectAttributes redirectAttributes) {
        if (binding.hasErrors()) {
            return "cases/intake";
        }
        FraudCase fraudCase = fraudCaseService.createCase(
                form.toRequest(OffsetDateTime.now(clock)));
        if ("investigate".equals(form.getIntent())) {
            try {
                investigationService.investigate(fraudCase.caseId());
            } catch (RecommendationValidationException ex) {
                redirectAttributes.addFlashAttribute("investigationError",
                        "Investigation completed without a valid "
                                + "recommendation; the case needs manual "
                                + "follow-up.");
            }
            return "redirect:/ui/cases/" + fraudCase.caseId() + "/investigation";
        }
        return "redirect:/ui/cases/" + fraudCase.caseId() + "/investigation";
    }
}
