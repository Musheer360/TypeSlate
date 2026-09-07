package com.musheer360.swiftslate.model

/**
 * Catalog of the Gemini models SwiftSlate curates and how each is tuned. Mirrors
 * [GroqModels]: each curated model is defined once in [SPECS] together with its
 * thinking level, and the default and per-model thinking level both derive from
 * that single table. Since issue #148 the Settings dropdown no longer renders
 * from this table — it lists whatever the provider's /models endpoint returns
 * (see [ProviderModelsCache]); SPECS remains the source for defaults, retirement
 * migration, and thinking levels.
 *
 * Model ids are shown to the user verbatim, exactly as the provider reports them.
 * There is deliberately no id -> friendly-name mapping: a curated table can only
 * ever name the handful of models it knows about, so the dropdown rendered pretty
 * labels for two entries and raw ids for everything else the endpoint returned.
 * Showing the provider's own ids is self-maintaining and matches the Custom
 * provider, which has never had labels to map.
 *
 * Thinking control on Gemini 3.x is via generationConfig.thinkingConfig.thinkingLevel
 * (a string enum), kept per model here (spec-driven) rather than hardcoded in the
 * client — exactly like Groq's per-model reasoning params:
 *  - "minimal" keeps latency low for short, inline text transformations. Without
 *    it, gemini-3.6-flash defaults to "medium" thinking (~4-5s per request).
 */
object GeminiModels {

    /** One entry per offered model: its id + the thinking level to request. */
    private data class Spec(val id: String, val thinkingLevel: String)

    // Curated set, ordered cost-efficient -> higher quality.
    //
    // CONTRACT: thinkingLevel is sent verbatim as generationConfig.thinkingConfig and
    // must be a valid enum value for that model — an invalid value returns HTTP 400 for
    // EVERY request with that model selected, and there is no client-side fallback that
    // strips it (deliberately: silently re-sending a different request hid catalog bugs
    // and doubled latency on real 400s). Verify any new or edited entry against the live
    // API before shipping.
    private val SPECS: List<Spec> = listOf(
        Spec("gemini-3.5-flash-lite", "low"), // fastest/cheapest GA flash-lite; "low" = same latency as "minimal" on this model but slightly better reasoning
        Spec("gemini-3.6-flash", "minimal")            // higher quality; minimal thinking to stay fast
    )

    /** Default model = first spec entry, so it can never point outside the catalog. */
    val DEFAULT: String = SPECS.first().id

    /** Model IDs, in display order (used for validation and by the provider config). */
    val ALL: List<String> = SPECS.map { it.id }

    /**
     * Ids that Google has withdrawn and must migrate to [DEFAULT] rather than pass
     * through — requests with them fail for (at least) newer accounts, so keeping
     * one selected would break every command (issue #113 precedent). Unknown ids
     * that are NOT listed here belong to models fetched dynamically off the live
     * /models endpoint (issue #148) and are kept verbatim.
     */
    private val RETIRED_IDS: Set<String> = setOf("gemini-2.5-flash-lite")

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
     * Thinking level to request for [model] via generationConfig.thinkingConfig,
     * or null if the model isn't in the catalog (then send no thinkingConfig).
     */
    fun thinkingLevel(model: String): String? = SPECS.firstOrNull { it.id == model }?.thinkingLevel
}
