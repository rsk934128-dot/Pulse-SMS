package com.example.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "GeminiAiService"
private const val TIMEOUT_MS = 60_000L

data class SpamSafetyReport(
    val isSpamOrPhishing: Boolean,
    val riskLevel: String,
    val category: String,
    val reasoning: String,
    val recommendedAction: String
)

data class SmartReplyResult(
    val suggestions: List<String>,
    val tone: String
)

/**
 * GeminiAiService powers real-time AI capabilities in Pulse SMS using Firebase AI Logic.
 * Utilizes gemini-3.8-flash with structured outputs, streaming, and realistic coroutine timeouts.
 */
class GeminiAiService {

    // General-use generative model for summarization and message drafting (lazy initialization)
    private val generalModel by lazy {
        Firebase.ai.generativeModel(modelName = "gemini-3.8-flash")
    }

    // Structured model for Spam & Phishing Safety Analysis
    private val spamAnalysisModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "isSpamOrPhishing" to Schema.boolean(description = "Whether the message is spam, phishing, or financial fraud"),
                "riskLevel" to Schema.enumeration(
                    values = listOf("SAFE", "SUSPICIOUS", "DANGEROUS"),
                    description = "Risk assessment level"
                ),
                "category" to Schema.enumeration(
                    values = listOf("PERSONAL", "TRANSACTION", "OTP", "PROMOTION", "SPAM_PHISHING"),
                    description = "Classified SMS category"
                ),
                "reasoning" to Schema.string(description = "Concise explanation of the analysis"),
                "recommendedAction" to Schema.string(description = "Action advice for the user")
            )
        )
        val config = generationConfig {
            responseMimeType = "application/json"
            responseSchema = schema
        }
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config
        )
    }

    // Structured model for Contextual Smart Replies
    private val smartRepliesModel by lazy {
        val schema = Schema.obj(
            properties = mapOf(
                "suggestions" to Schema.array(
                    items = Schema.string(description = "Short reply snippet under 10 words"),
                    description = "Three contextual quick reply options in the language of the conversation"
                ),
                "tone" to Schema.string(description = "Tone of the conversation, e.g. Formal, Casual, Urgent")
            )
        )
        val config = generationConfig {
            responseMimeType = "application/json"
            responseSchema = schema
        }
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = config
        )
    }

    /**
     * Analyzes an SMS message for fraud, phishing, spam, and security risks.
     */
    suspend fun analyzeMessageSafety(sender: String, messageText: String): Result<SpamSafetyReport> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Analyze the following incoming SMS for spam, fraudulent links, phishing attempts, or safety hazards.
                Sender: $sender
                Message Body: $messageText
            """.trimIndent()

            val response = withTimeout(TIMEOUT_MS) {
                spamAnalysisModel.generateContent(prompt)
            }
            val jsonStr = response.text ?: throw IllegalStateException("Model returned empty analysis payload")
            val json = JSONObject(jsonStr)

            val report = SpamSafetyReport(
                isSpamOrPhishing = json.optBoolean("isSpamOrPhishing", false),
                riskLevel = json.optString("riskLevel", "SAFE"),
                category = json.optString("category", "PERSONAL"),
                reasoning = json.optString("reasoning", "Analyzed by Gemini AI."),
                recommendedAction = json.optString("recommendedAction", "No action needed.")
            )
            Result.success(report)
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Safety analysis timed out", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to analyze message safety", e)
            Result.failure(e)
        }
    }

    /**
     * Generates 3 contextual quick-reply options for a given conversation.
     */
    suspend fun generateSmartReplies(conversationContext: String, lastMessage: String): Result<SmartReplyResult> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                You are a smart SMS assistant in Pulse SMS.
                Provide 3 contextual, natural, and concise replies (under 10 words each) suitable for SMS.
                If the user message is in Bengali, provide Bengali replies. If in English, provide English replies.
                
                Recent Context: $conversationContext
                Last Received Message: $lastMessage
            """.trimIndent()

            val response = withTimeout(TIMEOUT_MS) {
                smartRepliesModel.generateContent(prompt)
            }
            val jsonStr = response.text ?: throw IllegalStateException("Model returned empty smart reply payload")
            val json = JSONObject(jsonStr)

            val jsonArray = json.optJSONArray("suggestions")
            val suggestions = mutableListOf<String>()
            if (jsonArray != null) {
                for (i in 0 until jsonArray.length()) {
                    suggestions.add(jsonArray.getString(i))
                }
            }

            Result.success(
                SmartReplyResult(
                    suggestions = if (suggestions.isNotEmpty()) suggestions else listOf("OK", "Got it", "Thanks"),
                    tone = json.optString("tone", "Casual")
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Smart replies timed out", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate smart replies", e)
            Result.failure(e)
        }
    }

    /**
     * Rephrases or polishes a draft message into a specific tone (e.g. Formal, Polite, Concise, Casual).
     */
    suspend fun polishDraftMessage(draft: String, tone: String, targetLanguage: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                Rewrite the following draft SMS to make it sound $tone in language $targetLanguage.
                Keep it concise and ready to send as an SMS. Return only the rewritten message text without preamble or quotes.
                
                Draft: $draft
            """.trimIndent()

            val response = withTimeout(TIMEOUT_MS) {
                generalModel.generateContent(prompt)
            }
            val text = response.text?.trim()
            if (!text.isNullOrBlank()) {
                Result.success(text)
            } else {
                Result.failure(IllegalStateException("Gemini returned empty text"))
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Draft polishing timed out", e)
            Result.failure(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to polish draft message", e)
            Result.failure(e)
        }
    }

    /**
     * Summarizes an SMS thread into key takeaways (streaming response).
     */
    fun streamThreadSummary(threadSummaryPrompt: String): Flow<String> = flow {
        val prompt = """
            Summarize this SMS conversation thread concisely in 2-3 bullet points.
            Highlight any pending actions, OTPs, amounts, or meeting times.
            
            Thread:
            $threadSummaryPrompt
        """.trimIndent()

        withTimeout(TIMEOUT_MS) {
            generalModel.generateContentStream(prompt).collect { chunk ->
                chunk.text?.let { emit(it) }
            }
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: GeminiAiService? = null

        fun getInstance(): GeminiAiService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: GeminiAiService().also { INSTANCE = it }
            }
        }
    }
}
