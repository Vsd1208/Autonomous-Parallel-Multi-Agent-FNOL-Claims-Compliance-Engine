package com.guidewire.fnol.agents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The drafting agent's bounds, with a mock model and no network. */
class DraftingAgentTest {

    private static final String TEMPLATE = "Template letter.";
    private static final Map<String, DraftingAgent.Tool> TOOLS = new LinkedHashMap<>(Map.of(
            "claim_clock", new DraftingAgent.Tool("clock", "{\"acknowledgeBy\":\"2026-11-20\",\"decisionNote\":\"40 calendar days from the complete proof of loss.\"}"),
            "claim_summary", new DraftingAgent.Tool("summary", "{\"recommendedReserve\":\"3200\"}")));

    private static DraftingModel script(DraftingModel.Turn... turns) {
        Deque<DraftingModel.Turn> queue = new ArrayDeque<>(List.of(turns));
        return (instructions, tools, steps) -> queue.isEmpty() ? DraftingModel.Turn.finish("done") : queue.poll();
    }

    private static DraftingAgent.Draft run(DraftingModel model) {
        return new DraftingAgent(model, 6, 2).draft("rules", TOOLS, TEMPLATE);
    }

    @Test
    @DisplayName("A clean draft built on what the tools returned is shown, with its tool calls listed")
    void cleanDraft() {
        DraftingAgent.Draft d = run(script(DraftingModel.Turn.call("claim_clock"), DraftingModel.Turn.call("claim_summary"),
                DraftingModel.Turn.finish("Please acknowledge by 2026-11-20. The recommended reserve is $3,200, within 40 calendar days of proof of loss.")));
        assertThat(d.fromModel()).isTrue();
        assertThat(d.toolCalls()).containsExactly("claim_clock", "claim_summary");
    }

    @Test
    @DisplayName("Only the tools given can be called: a tool that writes does not exist, and asking for one falls back")
    void onlyReadOnlyTools() {
        for (String attempt : List.of("post_reserve", "set_status", "record_decision", "assign_claim")) {
            DraftingAgent.Draft d = run(script(DraftingModel.Turn.call(attempt), DraftingModel.Turn.finish("Posted.")));
            assertThat(d.fromModel()).isFalse();
            assertThat(d.text()).isEqualTo(TEMPLATE);
            assertThat(d.fallbackReason()).contains("does not have");
            assertThat(d.toolCalls()).doesNotContain(attempt);
        }
    }

    @Test
    @DisplayName("The tool-call limit holds")
    void toolCallCap() {
        DraftingModel forever = (i, t, s) -> DraftingModel.Turn.call("claim_summary");
        DraftingAgent.Draft d = run(forever);
        assertThat(d.fromModel()).isFalse();
        assertThat(d.fallbackReason()).contains("tool-call limit");
        assertThat(d.toolCalls()).hasSize(6);
    }

    @Test
    @DisplayName("The time limit holds, even if the model never answers")
    void timeCap() {
        DraftingModel slow = (i, t, s) -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return DraftingModel.Turn.finish("late");
        };
        long started = System.currentTimeMillis();
        DraftingAgent.Draft d = new DraftingAgent(slow, 6, 1).draft("rules", TOOLS, TEMPLATE);
        assertThat(System.currentTimeMillis() - started).isLessThan(5_000);
        assertThat(d.fromModel()).isFalse();
        assertThat(d.fallbackReason()).contains("time limit");
    }

    @Test
    @DisplayName("Every failure falls back to the template: an error, no answer, empty text")
    void failuresFallBack() {
        assertThat(run((i, t, s) -> {
            throw new IllegalStateException("no Gemini key");
        }).text()).isEqualTo(TEMPLATE);
        assertThat(run((i, t, s) -> null).text()).isEqualTo(TEMPLATE);
        assertThat(run(script(DraftingModel.Turn.finish("   "))).text()).isEqualTo(TEMPLATE);
    }

    @Test
    @DisplayName("The guardrail rejects an invented deadline, amount or period, even one a tool holds but the model did not read")
    void inventedFacts() {
        assertThat(run(script(DraftingModel.Turn.call("claim_clock"), DraftingModel.Turn.finish("Decide by 2026-12-25."))).fallbackReason()).contains("2026-12-25");
        assertThat(run(script(DraftingModel.Turn.call("claim_clock"), DraftingModel.Turn.finish("Decide by December 25, 2026."))).fallbackReason()).contains("December 25, 2026");
        assertThat(run(script(DraftingModel.Turn.call("claim_summary"), DraftingModel.Turn.finish("Reserve $9,999."))).fallbackReason()).contains("$9,999");
        assertThat(run(script(DraftingModel.Turn.call("claim_clock"), DraftingModel.Turn.finish("Within 10 business days."))).fallbackReason()).contains("10 business days");
        assertThat(run(script(DraftingModel.Turn.call("claim_summary"), DraftingModel.Turn.finish("Ack by 2026-11-20."))).fromModel()).isFalse();
    }

    @Test
    @DisplayName("The guardrail rejects a payment promise, a decision, a liability finding and personal data")
    void forbiddenStatements() {
        for (String bad : List.of("We will pay your claim.", "You will receive a payment soon.", "Your claim is approved.", "The claim has been denied.",
                "Your vehicle is covered.", "The other driver is at fault.", "Reach Avery at avery@example.com.", "Call 415-555-0100.", "SSN 123-45-6789.", "Born 01/02/1980.")) {
            assertThat(DraftGuard.problems(bad, List.of("{}"))).as(bad).isNotEmpty();
            assertThat(run(script(DraftingModel.Turn.call("claim_clock"), DraftingModel.Turn.finish(bad))).fromModel()).as(bad).isFalse();
        }
        assertThat(DraftGuard.problems("We will tell you in writing whether the claim is accepted.", List.of("{}"))).isEmpty();
    }
}
