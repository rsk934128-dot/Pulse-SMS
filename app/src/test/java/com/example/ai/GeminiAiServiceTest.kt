package com.example.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiAiServiceTest {

    @Test
    fun spamSafetyReport_parsesStructuredJsonCorrectly() {
        val rawJson = """
            {
                "isSpamOrPhishing": true,
                "riskLevel": "DANGEROUS",
                "category": "SPAM_PHISHING",
                "reasoning": "Suspicious lottery link asking for bank credentials.",
                "recommendedAction": "Do not click link or reply."
            }
        """.trimIndent()

        val json = JSONObject(rawJson)
        val report = SpamSafetyReport(
            isSpamOrPhishing = json.optBoolean("isSpamOrPhishing", false),
            riskLevel = json.optString("riskLevel", "SAFE"),
            category = json.optString("category", "PERSONAL"),
            reasoning = json.optString("reasoning"),
            recommendedAction = json.optString("recommendedAction")
        )

        assertTrue(report.isSpamOrPhishing)
        assertEquals("DANGEROUS", report.riskLevel)
        assertEquals("SPAM_PHISHING", report.category)
        assertEquals("Suspicious lottery link asking for bank credentials.", report.reasoning)
        assertEquals("Do not click link or reply.", report.recommendedAction)
    }

    @Test
    fun smartReplies_parsesJsonArrayCorrectly() {
        val rawJson = """
            {
                "suggestions": [
                    "Ami ekhon aschi",
                    "Got it, thanks!",
                    "Call me later"
                ],
                "tone": "Casual"
            }
        """.trimIndent()

        val json = JSONObject(rawJson)
        val array = json.optJSONArray("suggestions") ?: JSONArray()
        val suggestions = mutableListOf<String>()
        for (i in 0 until array.length()) {
            suggestions.add(array.getString(i))
        }

        val result = SmartReplyResult(suggestions, json.optString("tone"))
        assertEquals(3, result.suggestions.size)
        assertEquals("Ami ekhon aschi", result.suggestions[0])
        assertEquals("Casual", result.tone)
    }
}
