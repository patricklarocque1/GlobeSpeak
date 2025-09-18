package com.globespeak.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.TranslationBackend
import com.globespeak.engine.asr.AsrFactory
import com.globespeak.engine.asr.AsrFinal
import com.globespeak.engine.asr.AsrPartial
import com.globespeak.engine.asr.StreamingAsr
import com.globespeak.engine.backend.BackendFactory
import com.globespeak.mobile.logging.LogBus
import com.globespeak.shared.Bridge
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationServiceTest {

    @After
    fun tearDown() {
        TranslationService.dependenciesFactory = null
    }

    @Test
    fun pcmStreamEmitsPartialAndFinalWithFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val payload = ByteArray(320) { 1 }
        val bytes = ByteBuffer.allocate(16 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(42)
            .putLong(123L)
            .putInt(payload.size)
            .put(payload)
            .array()

        val fakeBackend = FakeTranslationBackend()
        val fakeAsr = FakeStreamingAsr(
            partialText = "partial hello",
            finalText = "final hello",
            partialSeq = 10,
            finalSeq = 20
        )
        val messageClient = FakeMessageClient(context)
        val channelClient = FakeChannelClient(context, bytes)

        val asrResult = AsrFactory.Result(
            initialStatus = AsrFactory.Status.OK,
            initialMessage = null,
            expectedBackend = AsrFactory.Backend.WHISPER_CPP,
            create = {
                AsrFactory.AsrInstance(
                    asr = fakeAsr,
                    backend = AsrFactory.Backend.WHISPER_CPP,
                    status = AsrFactory.Status.OK,
                    statusMessage = null
                )
            },
            fallback = {
                AsrFactory.AsrInstance(
                    asr = fakeAsr,
                    backend = AsrFactory.Backend.WHISPER_CPP,
                    status = AsrFactory.Status.OK,
                    statusMessage = null
                )
            }
        )

        TranslationService.dependenciesFactory = { _ ->
            TranslationService.Dependencies(
                translator = TranslatorEngine(fakeBackend),
                asrSelection = asrResult,
                initialAsrStatus = TranslationService.AsrStatusSnapshot(
                    status = asrResult.initialStatus,
                    message = asrResult.initialMessage,
                    backend = asrResult.expectedBackend
                ),
                messageClient = messageClient,
                channelClient = channelClient,
                backendInfo = BackendFactory.EngineSelectionInfo(
                    selected = "advanced",
                    active = "standard",
                    reason = "model missing"
                )
            )
        }

        val service = launchTranslationService(context)
        try {
            service.onChannelOpened(channelClient.channel)

            val messages = messageClient.awaitMessages(count = 2, timeoutMs = 5_000)
            assertThat(messages.map { it.type }).containsExactly("partial", "final").inOrder()
            assertThat(messages[0].text).isEqualTo("MLKIT:fr:partial hello")
            assertThat(messages[0].seq).isEqualTo(10)
            assertThat(messages[1].text).isEqualTo("MLKIT:fr:final hello")
            assertThat(messages[1].seq).isEqualTo(20)

            assertThat(fakeBackend.downloadedModels).contains("fr")
            assertThat(fakeAsr.closed).isTrue()

            val logs = LogBus.events.replayCache.lastOrNull().orEmpty()
            assertThat(
                logs.any { it.msg.contains("active=standard") && it.msg.contains("reason=model missing") }
            ).isTrue()
        } finally {
            destroyTranslationService(service)
        }
    }
}

private fun launchTranslationService(context: Context): TranslationService {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    lateinit var service: TranslationService
    instrumentation.runOnMainSync {
        service = TranslationService()
        attachService(service, context)
        service.onCreate()
    }
    return service
}

private fun destroyTranslationService(service: TranslationService) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        service.onDestroy()
    }
}

private fun attachService(service: TranslationService, context: Context) {
    val activityThreadClass = Class.forName("android.app.ActivityThread")
    val currentThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        ?: activityThreadClass.getMethod("systemMain").invoke(null)
    val activityManagerClass = Class.forName("android.app.ActivityManager")
    val activityManager = activityManagerClass.getDeclaredMethod("getService").apply { isAccessible = true }
        .invoke(null)
    val attachMethod = android.app.Service::class.java.getDeclaredMethod(
        "attach",
        Context::class.java,
        activityThreadClass,
        String::class.java,
        android.os.IBinder::class.java,
        Application::class.java,
        Object::class.java
    )
    attachMethod.isAccessible = true
    val application = context.applicationContext as Application
    attachMethod.invoke(
        service,
        context,
        currentThread,
        TranslationService::class.java.name,
        android.os.Binder(),
        application,
        activityManager
    )
}

private class FakeStreamingAsr(
    private val partialText: String,
    private val finalText: String,
    private val partialSeq: Int,
    private val finalSeq: Int
) : StreamingAsr {
    var closed: Boolean = false
    private var emittedPartial = false

    override fun onPcmChunk(pcm16LeMono16k: ByteArray): AsrPartial? {
        if (emittedPartial) return null
        emittedPartial = true
        return AsrPartial(text = partialText, sequenceId = partialSeq)
    }

    override fun finalizeSegment(): AsrFinal = AsrFinal(text = finalText, sequenceId = finalSeq)

    override fun close() {
        closed = true
    }
}

private class FakeTranslationBackend : TranslationBackend {
    val downloadedModels = mutableListOf<String>()

    override suspend fun supportedLanguageTags(): Set<String> = setOf("en", "fr")

    override suspend fun isModelDownloaded(langTag: String): Boolean = downloadedModels.contains(langTag)

    override suspend fun downloadModel(langTag: String, requireWifi: Boolean) {
        if (!downloadedModels.contains(langTag)) downloadedModels.add(langTag)
    }

    override suspend fun deleteModel(langTag: String) {
        downloadedModels.remove(langTag)
    }

    override suspend fun translate(
        text: String,
        targetLangTag: String,
        sourceLangTagOrNull: String?
    ): String = "MLKIT:$targetLangTag:$text"
}

private class FakeMessageClient(
    context: Context
) : MessageClient(context, com.google.android.gms.common.api.GoogleApi.Settings.DEFAULT_SETTINGS) {
    data class SentMessage(val nodeId: String, val path: String, val bytes: ByteArray) {
        private val json by lazy {
            if (path != Bridge.PATH_TEXT_OUT) return@lazy null
            runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
        }

        val type: String get() = json?.optString("type").orEmpty()
        val text: String get() = json?.optString("text").orEmpty()
        val seq: Int get() = json?.optInt("seq") ?: 0
    }

    private val flow = MutableSharedFlow<SentMessage>(extraBufferCapacity = 8)

    suspend fun awaitMessages(count: Int, timeoutMs: Long): List<SentMessage> {
        val collected = mutableListOf<SentMessage>()
        withTimeout(timeoutMs) {
            flow
                .filter { it.path == Bridge.PATH_TEXT_OUT }
                .take(count)
                .collect { collected.add(it) }
        }
        return collected
    }

    override fun sendMessage(nodeId: String, path: String, data: ByteArray?): Task<Int> {
        val payload = data ?: ByteArray(0)
        val sent = SentMessage(nodeId, path, payload)
        flow.tryEmit(sent)
        return Tasks.forResult(payload.size)
    }

    override fun sendMessage(
        nodeId: String,
        path: String,
        data: ByteArray?,
        options: com.google.android.gms.wearable.MessageOptions
    ): Task<Int> = sendMessage(nodeId, path, data)

    override fun sendRequest(
        nodeId: String,
        path: String,
        data: ByteArray?
    ): Task<ByteArray> = Tasks.forResult(ByteArray(0))

    override fun addListener(listener: MessageClient.OnMessageReceivedListener): Task<Void> =
        Tasks.forResult(null)

    override fun addListener(
        listener: MessageClient.OnMessageReceivedListener,
        uri: android.net.Uri,
        filterType: Int
    ): Task<Void> = Tasks.forResult(null)

    override fun addRpcService(
        service: MessageClient.RpcService,
        path: String
    ): Task<Void> = Tasks.forResult(null)

    override fun addRpcService(
        service: MessageClient.RpcService,
        path: String,
        nodeId: String
    ): Task<Void> = Tasks.forResult(null)

    override fun removeListener(listener: MessageClient.OnMessageReceivedListener): Task<Boolean> =
        Tasks.forResult(true)

    override fun removeRpcService(service: MessageClient.RpcService): Task<Boolean> =
        Tasks.forResult(true)
}

private class FakeChannelClient(
    context: Context,
    private val bytes: ByteArray
) : ChannelClient(context, com.google.android.gms.common.api.GoogleApi.Settings.DEFAULT_SETTINGS) {
    private val nodeId = "node-1"
    val channel: ChannelClient.Channel = object : ChannelClient.Channel {
        override fun getNodeId(): String = this@FakeChannelClient.nodeId
        override fun getPath(): String = Bridge.PATH_AUDIO_PCM16
        override fun describeContents(): Int = 0
        override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
    }

    override fun getInputStream(channel: ChannelClient.Channel): Task<InputStream> {
        return Tasks.forResult(ByteArrayInputStream(bytes))
    }

    // Unused members
    override fun openChannel(nodeId: String, path: String): Task<ChannelClient.Channel> =
        Tasks.forException(UnsupportedOperationException())

    override fun close(channel: ChannelClient.Channel): Task<Void> = Tasks.forResult(null)

    override fun close(channel: ChannelClient.Channel, closeReason: Int): Task<Void> = Tasks.forResult(null)

    override fun receiveFile(
        channel: ChannelClient.Channel,
        uri: android.net.Uri,
        append: Boolean
    ): Task<Void> = Tasks.forException(UnsupportedOperationException())

    override fun registerChannelCallback(callback: ChannelClient.ChannelCallback): Task<Void> =
        Tasks.forResult(null)

    override fun registerChannelCallback(
        channel: ChannelClient.Channel,
        callback: ChannelClient.ChannelCallback
    ): Task<Void> = Tasks.forResult(null)

    override fun unregisterChannelCallback(callback: ChannelClient.ChannelCallback): Task<Boolean> =
        Tasks.forResult(true)

    override fun unregisterChannelCallback(
        channel: ChannelClient.Channel,
        callback: ChannelClient.ChannelCallback
    ): Task<Boolean> = Tasks.forResult(true)

    override fun sendFile(channel: ChannelClient.Channel, uri: android.net.Uri): Task<Void> =
        Tasks.forException(UnsupportedOperationException())

    override fun sendFile(
        channel: ChannelClient.Channel,
        uri: android.net.Uri,
        startOffset: Long,
        length: Long
    ): Task<Void> = Tasks.forException(UnsupportedOperationException())

    override fun getOutputStream(channel: ChannelClient.Channel): Task<java.io.OutputStream> =
        Tasks.forException(UnsupportedOperationException())
}
