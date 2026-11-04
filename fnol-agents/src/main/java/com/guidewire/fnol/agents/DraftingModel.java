package com.guidewire.fnol.agents;

import java.util.List;

/** The LLM behind the drafting agent: given the conversation so far, it asks for one read-only tool or gives its final text. */
public interface DraftingModel {

    /** One read-only tool the model may ask for. */
    record ToolSpec(String name, String description) {}

    /** A step so far: the model asked for {@code tool}, and it returned {@code result}. */
    record Step(String tool, String result) {}

    /** Either a tool to call, or the final text. */
    record Turn(String toolCall, String text) {
        public static Turn call(String tool) {
            return new Turn(tool, null);
        }

        public static Turn finish(String text) {
            return new Turn(null, text);
        }
    }

    Turn next(String instructions, List<ToolSpec> tools, List<Step> steps);
}
