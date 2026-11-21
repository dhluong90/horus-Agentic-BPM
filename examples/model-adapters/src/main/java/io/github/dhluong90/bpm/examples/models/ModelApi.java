package io.github.dhluong90.bpm.examples.models;

/** The two request shapes the example adapters speak. */
public enum ModelApi {

    /**
     * OpenAI-compatible Chat Completions, {@code POST <base-url>/chat/completions}: hosted vendors, and self-hosted
     * servers such as vLLM or Ollama that offer the same endpoint.
     */
    OPENAI_COMPATIBLE,

    /** Anthropic Messages, {@code POST <base-url>/v1/messages}. */
    ANTHROPIC_MESSAGES
}
