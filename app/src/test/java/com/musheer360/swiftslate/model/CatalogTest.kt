package com.musheer360.swiftslate.model

import org.junit.Assert.*
import org.junit.Test

/** Pure unit tests for the Groq/Gemini model catalogs. No Android deps. */
class CatalogTest {

    // ---------- GroqModels ----------

    @Test
    fun groq_all_nonEmpty_and_default_in_catalog() {
        assertTrue(GroqModels.ALL.isNotEmpty())
        assertTrue("DEFAULT must be a real catalog entry", GroqModels.DEFAULT in GroqModels.ALL)
    }

    @Test
    fun groq_sanitize_blank_and_retired_to_default_unknown_passes_through() {
        assertEquals(GroqModels.DEFAULT, GroqModels.sanitize(null))
        assertEquals(GroqModels.DEFAULT, GroqModels.sanitize(""))
        assertEquals(GroqModels.DEFAULT, GroqModels.sanitize("   "))
        // Decommissioned by Groq: requests always fail, so migrate.
        assertEquals(GroqModels.DEFAULT, GroqModels.sanitize("llama-3.3-70b-versatile"))
        assertEquals(
            GroqModels.DEFAULT,
            GroqModels.sanitize(" meta-llama/llama-4-scout-17b-16e-instruct ")
        )
        // Dynamic selection (issue #148): off-catalog but live ids pass through trimmed.
        assertEquals("llama-3.1-8b-instant", GroqModels.sanitize("llama-3.1-8b-instant"))
        assertEquals("custom/new-model", GroqModels.sanitize(" custom/new-model "))
    }

    @Test
    fun groq_sanitize_keeps_valid_models() {
        for (id in GroqModels.ALL) assertEquals(id, GroqModels.sanitize(id))
    }

    @Test
    fun groq_reasoningParams_per_family() {
        assertEquals(
            mapOf("reasoning_effort" to "medium", "include_reasoning" to false),
            GroqModels.reasoningParams("openai/gpt-oss-120b")
        )
        assertEquals(mapOf("reasoning_effort" to "none"), GroqModels.reasoningParams("qwen/qwen3.6-27b"))
        // Unknown / removed models must send nothing (else the API 400s).
        assertEquals(emptyMap<String, Any>(), GroqModels.reasoningParams("openai/gpt-oss-20b"))
        assertEquals(emptyMap<String, Any>(), GroqModels.reasoningParams("llama-3.1-8b-instant"))
        assertEquals(emptyMap<String, Any>(), GroqModels.reasoningParams("something-else"))
    }

    @Test
    fun groq_isChatCandidate_excludes_non_chat_entries() {
        assertTrue(GroqModels.isChatCandidate("llama-3.1-8b-instant"))
        assertTrue(GroqModels.isChatCandidate("openai/gpt-oss-120b"))
        assertTrue(GroqModels.isChatCandidate("qwen/qwen3.6-27b"))
        assertFalse(GroqModels.isChatCandidate("whisper-large-v3"))
        assertFalse(GroqModels.isChatCandidate("distil-whisper-en"))
        assertFalse(GroqModels.isChatCandidate("playai-tts"))
        assertFalse(GroqModels.isChatCandidate("groq/playai-tts-qwen"))
        assertFalse(GroqModels.isChatCandidate("llama-guard-3-8b-8192"))
        assertFalse(GroqModels.isChatCandidate("meta-llama/llama-guard-4-12b"))
        // Present in the live catalog with output_modalities "speech".
        assertFalse(GroqModels.isChatCandidate("canopylabs/orpheus-v1-english"))
        assertFalse(GroqModels.isChatCandidate("canopylabs/orpheus-arabic-saudi"))
        // Live chat models that must survive the filter.
        assertTrue(GroqModels.isChatCandidate("qwen/qwen3.8-27b"))
        assertTrue(GroqModels.isChatCandidate("groq/compound"))
        assertTrue(GroqModels.isChatCandidate("allam-2-7b"))
    }

    // ---------- GeminiModels ----------

    @Test
    fun gemini_all_nonEmpty_and_default_in_catalog() {
        assertTrue(GeminiModels.ALL.isNotEmpty())
        assertTrue(GeminiModels.DEFAULT in GeminiModels.ALL)
    }

    @Test
    fun gemini_sanitize_blank_and_retired_to_default_unknown_passes_through() {
        assertEquals(GeminiModels.DEFAULT, GeminiModels.sanitize(null))
        assertEquals(GeminiModels.DEFAULT, GeminiModels.sanitize("  "))
        assertEquals(GeminiModels.DEFAULT, GeminiModels.sanitize("gemini-2.5-flash-lite")) // retired (issue #113)
        // Dynamic selection (issue #148): unknown-but-live ids pass through trimmed.
        assertEquals("gemini-3.7-pro-preview", GeminiModels.sanitize(" gemini-3.7-pro-preview "))
    }

    @Test
    fun gemini_thinkingLevel_for_catalog_null_for_unknown() {
        // flash-lite uses "low", flash uses "minimal"
        assertEquals("low", GeminiModels.thinkingLevel("gemini-3.5-flash-lite"))
        assertEquals("minimal", GeminiModels.thinkingLevel("gemini-3.6-flash"))
        assertNull(GeminiModels.thinkingLevel("gemini-9-ultra"))
    }
}
