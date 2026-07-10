package org.learingspring.upimesh.controller;

import lombok.RequiredArgsConstructor;
import org.learingspring.upimesh.service.InsightService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/insights")
@RequiredArgsConstructor
public class InsightController {

    private final InsightService insightService;

    /**
     * Preview the computed numbers for an account - useful for debugging,
     * and for showing the frontend "here's the ground truth" before/alongside
     * whatever the LLM says.
     */
    @GetMapping("/context")
    public InsightService.InsightContext getContext(
            @RequestParam String vpa,
            @RequestParam(defaultValue = "7") int days) {
        return insightService.buildContext(vpa, days);
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        InsightService.InsightContext ctx = insightService.buildContext(
                request.vpa, request.days == null ? 7 : request.days);

        String answer = insightService.askQuestion(request.question, ctx);

        return new AskResponse(answer, ctx);
    }

    public static class AskRequest {
        public String vpa;
        public String question;
        public Integer days;
    }

    public record AskResponse(String answer, InsightService.InsightContext context) {}
}