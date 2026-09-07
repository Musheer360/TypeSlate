package com.musheer360.swiftslate.ui

import com.musheer360.swiftslate.model.GeminiModels
import com.musheer360.swiftslate.model.GroqModels
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers the first-run model auto-selection. This used to be `models.first()`, which
 * trusted provider list order and picked models that cannot serve a request.
 */
class PreferredModelTest {

    @Test
    fun prefers_the_curated_default_over_list_order() {
        // Real Gemini /models order: 2.5-flash comes first but is closed to new keys
        // (NOT_FOUND "no longer available to new users"), so list order must not win.
        val live = listOf(
            "gemini-2.5-flash",
            "gemini-2.5-pro",
            "gemini-3.5-flash-lite",
            "gemini-3.6-flash"
        )
        assertEquals(GeminiModels.DEFAULT, preferredModel(live, GeminiModels.DEFAULT))
    }

    @Test
    fun prefers_curated_default_for_groq_over_list_order() {
        // Real Groq ordering surfaced qwen3.8-27b first, a reasoning model.
        val live = listOf("qwen/qwen3.8-27b", "openai/gpt-oss-120b", "qwen/qwen3.6-27b")
        assertEquals(GroqModels.DEFAULT, preferredModel(live, GroqModels.DEFAULT))
    }

    @Test
    fun falls_back_to_first_when_default_withdrawn() {
        val live = listOf("gemini-9-ultra", "gemini-9-nano")
        assertEquals("gemini-9-ultra", preferredModel(live, GeminiModels.DEFAULT))
    }

    @Test
    fun single_entry_list_is_used_as_is() {
        assertEquals("only-model", preferredModel(listOf("only-model"), GeminiModels.DEFAULT))
    }
}
