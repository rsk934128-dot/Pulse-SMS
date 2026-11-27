package com.example.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.provider.ContactsContract
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.repository.CallRecordingUploadItem
import com.example.data.repository.QrPairingManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

data class CapturedDeviceLocation(
    val latitude: Double,
    val longitude: Double,
    val label: String?
)

data class CallRecordingFinishResult(
    val success: Boolean,
    val recordingId: String? = null,
    val phoneNumber: String = "",
    val contactName: String? = null,
    val durationSec: Long = 0L,
    val hasLocation: Boolean = false,
    val locationLabel: String? = null,
    val message: String = ""
)

/**
 * Coordinates Android call audio recording (auto call-state when opted in, or manual/remote
 * on demand), attaches least-privilege real device location (ACCESS_COARSE_LOCATION), and
 * uploads Base64 audio + number + coordinates to the paired Firestore session.
 */
object CallRecorderManager {
    private const val TAG = "CallRecorderManager"
    private const val PREFS_NAME = "pulse_call_recorder_prefs"
    private const val KEY_AUTO_RECORD = "auto_call_recording_enabled"
    private const val KEY_RECORDED_COUNT = "call_recordings_uploaded_count"
    private const val MAX_AAC_BYTES = 510_000L // ~680 KB base64, safely under 750,000 char rule limit
    private const val MAX_WAV_PCM_BYTES = 480_000 // ~30 sec 8kHz 16-bit mono WAV <= 640 KB base64

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _elapsedSec = MutableStateFlow(0L)
    val elapsedSec: StateFlow<Long> = _elapsedSec.asStateFlow()

    private val _activeNumber = MutableStateFlow("")
    val activeNumber: StateFlow<String> = _activeNumber.asStateFlow()

    private val _activeCallType = MutableStateFlow("MANUAL")
    val activeCallType: StateFlow<String> = _activeCallType.asStateFlow()

    private val _uploadedCount = MutableStateFlow(0)
    val uploadedCount: StateFlow<Int> = _uploadedCount.asStateFlow()

    private val lock = Any()
    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startTimestampMs: Long = 0L
    private var usingWavFallback = false
    private var audioRecord: AudioRecord? = null
    private var wavThread: Thread? = null
    private var wavStream: ByteArrayOutputStream? = null
    @Volatile
    private var wavCapturing = false
    private var activeWavSampleRate = 8000

    fun isAutoCallRecordingEnabled(context: Context): Boolean {
        return try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_RECORD, false)
        } catch (_: Throwable) {
            false
        }
    }

    fun setAutoCallRecordingEnabled(context: Context, enabled: Boolean) {
        try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_AUTO_RECORD, enabled)
                .apply()
        } catch (_: Throwable) {
        }
    }

    fun getUploadedCount(context: Context): Int {
        return try {
            val saved = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_RECORDED_COUNT, 0)
            _uploadedCount.value = saved
            saved
        } catch (_: Throwable) {
            0
        }
    }

    private fun incrementUploadedCount(context: Context): Int {
        val next = getUploadedCount(context) + 1
        try {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_RECORDED_COUNT, next)
                .apply()
        } catch (_: Throwable) {
        }
        _uploadedCount.value = next
        return next
    }

    fun hasMicPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasPhoneStatePermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun updateActiveNumber(number: String?, callType: String? = null) {
        val cleaned = number?.trim()?.takeIf { it.isNotBlank() } ?: return
        _activeNumber.value = cleaned.take(100)
        if (!callType.isNullOrBlank()) {
            _activeCallType.value = normalizeCallType(callType)
        }
    }

    fun updateElapsedTick() {
        if (_isRecording.value && startTimestampMs > 0L) {
            _elapsedSec.value = ((System.currentTimeMillis() - startTimestampMs) / 1000L).coerceAtLeast(0L)
        }
    }

    fun isCurrentlyRecording(): Boolean = _isRecording.value

    @Suppress("DEPRECATION")
    fun startRecording(
        context: Context,
        phoneNumber: String,
        callType: String = "MANUAL"
    ): Boolean {
        val appContext = context.applicationContext ?: context
        if (!hasMicPermission(appContext)) {
            Log.w(TAG, "Cannot start recording without RECORD_AUDIO permission")
            return false
        }

        synchronized(lock) {
            releaseActiveRecorderLocked()

            val safeNumber = phoneNumber.trim().ifBlank { "Unknown" }.take(100)
            val safeCallType = normalizeCallType(callType)
            val recDir = File(appContext.cacheDir, "call_recordings").apply { mkdirs() }
            val file = File(recDir, "rec_${System.currentTimeMillis()}.m4a")

            var started = false
            val sources = listOf(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC
            )

            for (source in sources) {
                var mr: MediaRecorder? = null
                try {
                    mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        MediaRecorder(appContext)
                    } else {
                        MediaRecorder()
                    }
                    mr.setAudioSource(source)
                    mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    mr.setAudioSamplingRate(16000)
                    mr.setAudioEncodingBitRate(24000)
                    mr.setAudioChannels(1)
                    mr.setMaxFileSize(MAX_AAC_BYTES)
                    mr.setOutputFile(file.absolutePath)
                    mr.prepare()
                    mr.start()

                    mediaRecorder = mr
                    outputFile = file
                    usingWavFallback = false
                    started = true
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "MediaRecorder source=$source failed: ${t.message}")
                    try {
                        mr?.release()
                    } catch (_: Throwable) {
                    }
                }
            }

            if (!started) {
                started = startWavFallbackLocked()
            }

            if (started) {
                startTimestampMs = System.currentTimeMillis()
                _activeNumber.value = safeNumber
                _activeCallType.value = safeCallType
                _elapsedSec.value = 0L
                _isRecording.value = true
                return true
            }

            return false
        }
    }

    @SuppressLint("MissingPermission")
    private fun startWavFallbackLocked(): Boolean {
        val sampleRates = intArrayOf(8000, 16000)
        val sources = intArrayOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        for (sr in sampleRates) {
            val minBuf = AudioRecord.getMinBufferSize(
                sr,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) continue
            for (src in sources) {
                var ar: AudioRecord? = null
                try {
                    ar = AudioRecord(
                        src,
                        sr,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        (minBuf * 2).coerceAtLeast(4096)
                    )
                    if (ar.state != AudioRecord.STATE_INITIALIZED) {
                        ar.release()
                        continue
                    }
                    ar.startRecording()
                    if (ar.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        ar.release()
                        continue
                    }
                    val baos = ByteArrayOutputStream()
                    audioRecord = ar
                    wavStream = baos
                    activeWavSampleRate = sr
                    usingWavFallback = true
                    wavCapturing = true
                    val readBuf = ByteArray(2048)
                    val thread = Thread {
                        while (wavCapturing) {
                            val read = try {
                                ar.read(readBuf, 0, readBuf.size)
                            } catch (_: Throwable) {
                                -1
                            }
                            if (read > 0) {
                                synchronized(baos) {
                                    if (baos.size() + read <= MAX_WAV_PCM_BYTES) {
                                        baos.write(readBuf, 0, read)
                                    } else {
                                        wavCapturing = false
                                    }
                                }
                            } else if (read < 0) {
                                break
                            }
                        }
                    }.apply {
                        name = "CallWavFallbackThread"
                        start()
                    }
                    wavThread = thread
                    return true
                } catch (t: Throwable) {
                    Log.w(TAG, "AudioRecord fallback ($sr, $src) failed: ${t.message}")
                    try {
                        ar?.release()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        return false
    }

    suspend fun stopAndUploadRecording(
        context: Context,
        overrideNumber: String? = null,
        overrideCallType: String? = null
    ): CallRecordingFinishResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext ?: context
        var capturedBytes: ByteArray? = null
        var mimeType = "audio/mp4"
        var durationSec = 0L
        var initialNumber = ""
        var initialCallType = "MANUAL"

        synchronized(lock) {
            initialNumber = overrideNumber?.trim()?.takeIf { it.isNotBlank() } ?: _activeNumber.value
            initialCallType = normalizeCallType(overrideCallType ?: _activeCallType.value)
            if (startTimestampMs > 0L) {
                durationSec = ((System.currentTimeMillis() - startTimestampMs) / 1000L).coerceIn(1L, 86400L)
            }

            if (usingWavFallback) {
                wavCapturing = false
                try {
                    audioRecord?.stop()
                } catch (_: Throwable) {
                }
                try {
                    audioRecord?.release()
                } catch (_: Throwable) {
                }
                audioRecord = null
                try {
                    wavThread?.join(500)
                } catch (_: Throwable) {
                }
                wavThread = null
                val pcm = wavStream?.let { stream ->
                    synchronized(stream) { stream.toByteArray() }
                } ?: ByteArray(0)
                wavStream = null
                if (pcm.isNotEmpty()) {
                    capturedBytes = wrapPcmAsWav(pcm, activeWavSampleRate)
                    mimeType = "audio/wav"
                }
            } else {
                try {
                    mediaRecorder?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "MediaRecorder stop warning: ${t.message}")
                }
                try {
                    mediaRecorder?.release()
                } catch (_: Throwable) {
                }
                mediaRecorder = null
                val file = outputFile
                outputFile = null
                if (file != null && file.exists() && file.length() > 64L) {
                    capturedBytes = file.readBytes()
                    mimeType = "audio/mp4"
                }
                try {
                    file?.delete()
                } catch (_: Throwable) {
                }
            }

            _isRecording.value = false
            _elapsedSec.value = 0L
            startTimestampMs = 0L
        }

        if (capturedBytes == null || capturedBytes!!.isEmpty()) {
            QrPairingManager.publishRemoteCallRecorderState(
                context = appContext,
                status = "ERROR",
                durationSec = durationSec,
                errorMessage = "কোনো অডিও ডেটা পাওয়া যায়নি (Recording empty)"
            )
            return@withContext CallRecordingFinishResult(
                success = false,
                phoneNumber = initialNumber,
                durationSec = durationSec,
                message = "রেকর্ডিং অডিও পাওয়া যায়নি।"
            )
        }

        // Resolve final phone number + contact name + callType from recent CallLog if missing or unknown
        var resolvedNumber = initialNumber.ifBlank { "Unknown" }
        var resolvedContact: String? = null
        var resolvedCallType = initialCallType

        val recentCalls = CallLogSyncHelper.readRecentCallLogs(appContext, limit = 5)
        if (resolvedNumber == "Unknown" || resolvedNumber == "Private / Unknown" || resolvedNumber.isBlank()) {
            val newestCall = recentCalls.firstOrNull { call ->
                kotlin.math.abs(System.currentTimeMillis() - call.timestampMs) <= 180_000L
            } ?: recentCalls.firstOrNull()
            if (newestCall != null) {
                resolvedNumber = newestCall.number.ifBlank { "Unknown" }.take(100)
                resolvedContact = newestCall.contactName
                if (resolvedCallType == "UNKNOWN" || resolvedCallType == "INCOMING" || resolvedCallType == "OUTGOING") {
                    resolvedCallType = normalizeCallType(newestCall.callType)
                }
            }
        } else {
            resolvedContact = recentCalls.firstOrNull {
                normalizeDigits(it.number) == normalizeDigits(resolvedNumber) && !it.contactName.isNullOrBlank()
            }?.contactName ?: lookupContactNameSafe(appContext, resolvedNumber)
        }

        QrPairingManager.publishRemoteCallRecorderState(
            context = appContext,
            status = "UPLOADING",
            targetNumber = resolvedNumber,
            durationSec = durationSec,
            errorMessage = ""
        )

        // Capture real device location coordinates (strictly real LocationManager when granted, never mocked)
        val location = captureDeviceLocation(appContext)

        // Ensure Base64 fits under Firestore's 750,000 char rule cap
        var rawAudio = capturedBytes!!
        var base64Audio = Base64.encodeToString(rawAudio, Base64.NO_WRAP)
        if (base64Audio.length > 740_000) {
            val maxRaw = 540_000.coerceAtMost(rawAudio.size)
            rawAudio = rawAudio.copyOf(maxRaw)
            base64Audio = Base64.encodeToString(rawAudio, Base64.NO_WRAP)
        }

        val recId = "rec_${System.currentTimeMillis()}_${(1000..9999).random()}"
        val uploadItem = CallRecordingUploadItem(
            id = recId,
            phoneNumber = resolvedNumber.take(100),
            contactName = resolvedContact?.take(120),
            callType = resolvedCallType,
            durationSec = durationSec,
            audioBase64 = base64Audio,
            mimeType = mimeType,
            latitude = location?.latitude,
            longitude = location?.longitude,
            locationLabel = location?.label?.take(200),
            timestampMs = System.currentTimeMillis()
        )

        val uploaded = QrPairingManager.syncCallRecordingToPairedSession(appContext, uploadItem)
        if (uploaded) {
            incrementUploadedCount(appContext)
            QrPairingManager.publishRemoteCallRecorderState(
                context = appContext,
                status = "SAVED",
                targetNumber = resolvedNumber,
                durationSec = durationSec,
                errorMessage = ""
            )
            CallRecordingFinishResult(
                success = true,
                recordingId = recId,
                phoneNumber = resolvedNumber,
                contactName = resolvedContact,
                durationSec = durationSec,
                hasLocation = location != null,
                locationLabel = location?.label,
                message = "✅ কল রেকর্ডিং ($resolvedNumber) ওয়েবসাইটে পাঠানো হয়েছে!"
            )
        } else {
            QrPairingManager.publishRemoteCallRecorderState(
                context = appContext,
                status = "ERROR",
                targetNumber = resolvedNumber,
                durationSec = durationSec,
                errorMessage = "ওয়েবসাইটে আপলোড ব্যর্থ — পেয়ারিং ও ইন্টারনেট চেক করুন"
            )
            CallRecordingFinishResult(
                success = false,
                recordingId = recId,
                phoneNumber = resolvedNumber,
                contactName = resolvedContact,
                durationSec = durationSec,
                hasLocation = location != null,
                locationLabel = location?.label,
                message = "ওয়েবসাইটে পাঠাতে ব্যর্থ (আগে ওয়েব QR/১-ক্লিকে পেয়ার করুন)।"
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun captureDeviceLocation(context: Context): CapturedDeviceLocation? {
        val appContext = context.applicationContext ?: context
        if (!hasLocationPermission(appContext)) return null
        return try {
            val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val providers = listOf(
                LocationManager.NETWORK_PROVIDER,
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            var bestLocation: Location? = null
            for (provider in providers) {
                try {
                    val loc = lm.getLastKnownLocation(provider) ?: continue
                    if (bestLocation == null || loc.time > bestLocation!!.time) {
                        bestLocation = loc
                    }
                } catch (_: SecurityException) {
                } catch (_: Throwable) {
                }
            }

            val loc = bestLocation ?: return null
            val lat = loc.latitude
            val lng = loc.longitude
            if (lat !in -90.0..90.0 || lng !in -180.0..180.0 || (lat == 0.0 && lng == 0.0)) {
                return null
            }

            val label = reverseGeocodeLabel(appContext, lat, lng)
                ?: String.format(Locale.US, "%.4f, %.4f", lat, lng)

            CapturedDeviceLocation(
                latitude = lat,
                longitude = lng,
                label = label
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Location lookup failed: ${t.message}")
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun reverseGeocodeLabel(context: Context, lat: Double, lng: Double): String? {
        return try {
            if (!Geocoder.isPresent()) return null
            val geocoder = Geocoder(context, Locale.getDefault())
            val addresses = geocoder.getFromLocation(lat, lng, 1)
            val addr = addresses?.firstOrNull() ?: return null
            listOfNotNull(
                addr.subLocality?.takeIf { it.isNotBlank() },
                addr.locality?.takeIf { it.isNotBlank() },
                addr.adminArea?.takeIf { it.isNotBlank() },
                addr.countryName?.takeIf { it.isNotBlank() }
            ).distinct().joinToString(", ").takeIf { it.isNotBlank() }?.take(200)
        } catch (_: Throwable) {
            null
        }
    }

    private fun lookupContactNameSafe(context: Context, phoneNumber: String): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val lookupUri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            context.contentResolver.query(
                lookupUri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.trim()?.takeIf { it.isNotBlank() }
                } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun normalizeDigits(num: String): String {
        val digits = num.filter { it.isDigit() }
        return if (digits.length > 8) digits.takeLast(8) else digits
    }

    private fun normalizeCallType(raw: String): String {
        return when (raw.uppercase(Locale.US)) {
            "INCOMING", "OUTGOING", "MANUAL", "UNKNOWN" -> raw.uppercase(Locale.US)
            else -> "UNKNOWN"
        }
    }

    private fun releaseActiveRecorderLocked() {
        wavCapturing = false
        try {
            audioRecord?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioRecord?.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
        try {
            wavThread?.join(250)
        } catch (_: Throwable) {
        }
        wavThread = null
        wavStream = null

        try {
            mediaRecorder?.stop()
        } catch (_: Throwable) {
        }
        try {
            mediaRecorder?.release()
        } catch (_: Throwable) {
        }
        mediaRecorder = null
        try {
            outputFile?.delete()
        } catch (_: Throwable) {
        }
        outputFile = null
        _isRecording.value = false
        _elapsedSec.value = 0L
        startTimestampMs = 0L
    }

    private fun wrapPcmAsWav(pcmData: ByteArray, sampleRate: Int): ByteArray {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * 2 // 16-bit mono
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(totalDataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM chunk size
            putShort(1.toShort()) // PCM format
            putShort(1.toShort()) // Mono
            putInt(sampleRate)
            putInt(byteRate)
            putShort(2.toShort()) // Block align
            putShort(16.toShort()) // Bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcmData.size)
        }.array()
        return header + pcmData
    }
}
