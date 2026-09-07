package com.musheer360.swiftslate.model

/**
 * Single source of truth for the Groq models SwiftSlate curates and how each one
 * is driven with respect to reasoning ("thinking").
 *
 * Each curated model is defined once in [SPECS] together with the reasoning
 * parameters it needs. The default and the per-model reasoning params both derive
 * from that single table, so adding or removing a model is a one-line change that
 * forces you to state its reasoning behavior right there.
 * Since issue #148 the Settings dropdown no longer renders from this table — it
 * lists whatever Groq's /models endpoint returns (see [ProviderModelsCache]);
 * SPECS remains the source for defaults, retirement migration, and reasoning
 * params. Off-catalog models picked from that dynamic list send no reasoning
 * params ([reasoningParams] returns empty for unknown ids), which every model
 * accepts.
 *
 * Model ids are shown to the user verbatim, exactly as Groq reports them. There is
 * deliberately no id -> friendly-name mapping: a curated table can only ever name
 * the handful of models it knows about, so the dropdown rendered pretty labels for
 * two entries and raw ids for everything else the endpoint returned. Showing the
 * provider's own ids is self-maintaining and matches the Custom provider, which
 * has never had labels to map.
 *
 * Reasoning support on Groq is per-model and strictly validated by the API:
 * sending an unsupported reasoning parameter (or an unsupported value) returns
 * HTTP 400. There are three categories:
 *
 *  1. Non-reasoning models (e.g. llama-3.1-8b-instant): must NOT receive any
 *     reasoning parameters. They never emit reasoning tokens.
 *  2. GPT-OSS models: reasoning cannot be fully disabled. Lowest is
 *     reasoning_effort="low" ("none" returns 400). include_reasoning=false hides
 *     the reasoning from the response (it is still generated internally, so this
 *     only trims what we receive/parse).
 *  3. Qwen 3.x models: reasoning can be fully turned off with
 *     reasoning_effort="none" (zero reasoning tokens). "low"/"medium"/"high"
 *     return 400 for Qwen.
 *
 * Measured against the live API (2026-09), qwen/qwen3.6-27b on the same prompt:
 * reasoning_effort="none" returned in 249 ms using 16 output tokens, while sending
 * no reasoning params at all took 3018 ms and 1413 output tokens — and Groq then
 * reserves ~2048 output tokens per request, which exceeds the free tier's 1000 OTPM
 * limit and makes requests fail with HTTP 413 outright. That is why these values are
 * pinned rather than left to the provider default. openai/gpt-oss-120b is the
 * exception: it defaults to 465 ms / 78 tokens and returns reasoning in a separate
 * field, so it is unaffected either way.
 *
 * Models NOT in [SPECS] still get no reasoning params, because the valid values are
 * per-model and absent from /models — Groq's response exposes
 * supported_features ["reasoning"] but not which efforts are legal. Non-GPT-OSS
 * reasoning models therefore emit their chain of thought inline in the content, which
 * [ApiClientUtils.stripReasoningBlock] removes before it reaches the user.
 */
object GroqModels {

    /** One entry per offered model: its ID + the reasoning params to send (empty = none). */
    private data class Spec(val id: String, val reasoning: Map<String, Any>)

    // Ordered fast/cheap -> higher quality. Curated set (not every model Groq hosts).
    // Only models that reliably TRANSFORM (never answer/respond to) the user's text are
    // kept. llama-3.1-8b-instant and openai/gpt-oss-20b were removed after testing: they
    // consistently fulfilled requests embedded in the text (essays, tips, recipes) instead
    // of transforming them. Retired IDs (llama-3.3-70b-versatile, llama-4-scout) also gone.
    //
    // CONTRACT: the reasoning map is sent verbatim as top-level request params and
    // must be valid for that specific model (see the category notes above) — an
    // invalid key/value returns HTTP 400 for EVERY request with that model selected,
    // and there is no client-side fallback that strips them (deliberately: silently
    // re-sending a different request hid catalog bugs and doubled latency on real
    // 400s). Verify any new or edited entry against the live API before shipping.
    private val SPECS: List<Spec> = listOf(
        // GPT-OSS: cannot fully disable reasoning; "medium" balances quality and latency (~1s).
        // Deliberately NO max_completion_tokens: Groq pre-reserves that value against the
        // per-minute token budget (Requested = prompt_tokens + max_completion_tokens) and
        // this model's TPM limit is only 8,000 on the free/on-demand tier. Any value at or
        // near 8,000 makes every single request fail with HTTP 413 "Request too large"
        // before it reaches the model. Groq's own default is ample for medium effort.
        Spec("openai/gpt-oss-120b", mapOf("reasoning_effort" to "medium", "include_reasoning" to false)),
        // Qwen 3.x: fully disable reasoning (never "default" — that blows up latency/quota).
        // Fastest option (~250ms) with excellent system-prompt adherence.
        Spec("qwen/qwen3.6-27b", mapOf("reasoning_effort" to "none"))
    )

    /** Default model = first spec entry, so it can never point outside the catalog. */
    val DEFAULT: String = SPECS.first().id

    /** Model IDs, in display order (used for validation and by the provider config). */
    val ALL: List<String> = SPECS.map { it.id }

    /**
     * Ids Groq has decommissioned; they must migrate to [DEFAULT] rather than pass
     * through, because requests with them always fail. Unknown ids NOT listed here
     * come from the dynamic /models list (issue #148) and are kept verbatim.
     */
    private val RETIRED_IDS: Set<String> = setOf(
        "llama-3.3-70b-versatile",
        "llama-4-scout",
        "meta-llama/llama-4-scout-17b-16e-instruct"
    )

    /**
     * Groq's /models endpoint also serves entries that cannot run text-transforming
     * chat completions: Whisper transcription, PlayAI and Orpheus TTS, and Llama Guard
     * safety classifiers. Conservative substring exclusions, verified against the live
     * catalog; anything unrecognized stays listed (fail-open).
     *
     * Substrings are a stopgap. The live response carries output_modalities ("text",
     * "speech", "transcription"), which would classify these exactly instead of by name
     * — canopylabs/orpheus-* was only caught here after it slipped through and showed up
     * as a selectable model. Using it means threading per-model metadata through
     * ApiClientUtils.parseModelIds, which currently returns bare ids.
     */
    private val NON_CHAT_SUBSTRINGS = listOf("whisper", "-tts", "guard", "playai", "orpheus")

    fun isChatCandidate(id: String): Boolean =
        NON_CHAT_SUBSTRINGS.none { id.contains(it, ignoreCase = true) }

    /**
     * Normalize a stored/selected model value. Dynamic selection (issue #148) means
     * any id the API accepts may be stored, so unknown ids pass through trimmed;
     * only blank values and known-retired ids coerce to [DEFAULT].
     */
    fun sanitize(value: String?): String {
        val trimmed = value?.trim().orEmpty()
        return if (trimmed.isEmpty() || trimmed in RETIRED_IDS) DEFAULT else trimmed
    }

    /**
     * Reasoning parameters to merge into a Groq chat-completions request body for
     * [model]. Returns an empty map for non-reasoning models and unknown models
     * (send nothing, else the API returns HTTP 400).
     */
    fun reasoningParams(model: String): Map<String, Any> =
        SPECS.firstOrNull { it.id == model }?.reasoning ?: emptyMap()
}
