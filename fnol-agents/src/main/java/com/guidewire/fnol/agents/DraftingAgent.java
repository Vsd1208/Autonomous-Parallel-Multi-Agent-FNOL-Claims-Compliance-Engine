package com.guidewire.fnol.agents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The one LLM-planned agent: it chooses which read-only tools to read and drafts text for a person to review. It has no tool
 * that writes anything, its tool calls and time are capped, its draft is checked against what the tools returned, and on any
 * failure the caller gets the template text instead. Only with {@code fnol.drafting-agent.enabled=true}; outside the intake path.
 */
@Component
@ConditionalOnProperty(name = "fnol.drafting-agent.enabled", havingValue = "true")
public class DraftingAgent {

    private static final Logger log = LoggerFactory.getLogger(DraftingAgent.class);

    /** A tool's description and what it returns for this claim. Read-only: its output was computed before the model is asked. */
    public record Tool(String description, String output) {}

    /** The text for a person to review, whether an LLM wrote it, why not if it did not, and the tools it read. */
    public record Draft(String text, boolean fromModel, String fallbackReason, List<String> toolCalls) {}

    private final DraftingModel model;
    private final int maxToolCalls;
    private final long maxMillis;

    public DraftingAgent(DraftingModel model,
                         @Value("${fnol.drafting-agent.max-tool-calls:6}") int maxToolCalls,
                         @Value("${fnol.drafting-agent.max-seconds:8}") long maxSeconds) {
        this.model = model;
        this.maxToolCalls = maxToolCalls;
        this.maxMillis = TimeUnit.SECONDS.toMillis(maxSeconds);
    }

    public Draft draft(String instructions, Map<String, Tool> tools, String template) {
        List<String> calls = new ArrayList<>();
        try {
            List<DraftingModel.ToolSpec> specs = new ArrayList<>();
            tools.forEach((name, tool) -> specs.add(new DraftingModel.ToolSpec(name, tool.description())));
            List<DraftingModel.Step> steps = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(maxMillis);
            while (true) {
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    return template(template, "the time limit was reached", calls);
                }
                List<DraftingModel.Step> soFar = List.copyOf(steps);
                DraftingModel.Turn turn = CompletableFuture.supplyAsync(() -> model.next(instructions, specs, soFar))
                        .get(remaining, TimeUnit.MILLISECONDS);
                if (turn == null) {
                    return template(template, "the model gave no answer", calls);
                }
                if (turn.toolCall() == null) {
                    return finish(turn.text(), tools, template, calls);
                }
                if (calls.size() >= maxToolCalls) {
                    return template(template, "the tool-call limit of " + maxToolCalls + " was reached", calls);
                }
                Tool tool = tools.get(turn.toolCall());
                if (tool == null) {
                    return template(template, "the model asked for a tool it does not have", calls);
                }
                calls.add(turn.toolCall());
                steps.add(new DraftingModel.Step(turn.toolCall(), tool.output()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return template(template, "the model call was interrupted", calls);
        } catch (Exception e) {
            log.warn("[Drafting] Model call failed, using the template: {}", e.getMessage());
            return template(template, e instanceof java.util.concurrent.TimeoutException ? "the time limit was reached" : "the model call failed", calls);
        }
    }

    private Draft finish(String text, Map<String, Tool> tools, String template, List<String> calls) {
        if (text == null || text.isBlank()) {
            return template(template, "the model gave no text", calls);
        }
        // Only what the model actually read counts as a source for its dates and amounts.
        Map<String, Tool> read = new LinkedHashMap<>(tools);
        read.keySet().retainAll(calls);
        List<String> problems = DraftGuard.problems(text, read.values().stream().map(Tool::output).toList());
        if (!problems.isEmpty()) {
            log.warn("[Drafting] Draft rejected: {}", problems);
            return template(template, "the guardrail rejected the draft: " + String.join("; ", problems), calls);
        }
        return new Draft(text.strip(), true, null, List.copyOf(calls));
    }

    private static Draft template(String template, String why, List<String> calls) {
        return new Draft(template, false, why, List.copyOf(calls));
    }
}
