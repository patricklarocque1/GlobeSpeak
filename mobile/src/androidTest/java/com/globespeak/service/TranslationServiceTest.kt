package com.globespeak.service

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ServiceScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.globespeak.engine.TranslatorEngine
import com.globespeak.engine.TranslationBackend
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
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
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
        val messageClient = FakeMessageClient()
        val channelClient = FakeChannelClient(bytes)

        TranslationService.dependenciesFactory = { service ->
            TranslationService.Dependencies(
                translator = TranslatorEngine(fakeBackend),
                streamingAsrFactory = { fakeAsr },
                messageClient = messageClient,
                channelClient = channelClient,
                backendInfo = BackendFactory.EngineSelectionInfo(
                    selected = "advanced",
                    active = "standard",
                    reason = "model missing"
                )
            )
        }

        val intent = Intent(context, TranslationService::class.java)
        val scenario = ServiceScenario.launch<TranslationService>(intent)
        try {
            scenario.onService { service ->
                service.onChannelOpened(channelClient.channel)
            }

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
            scenario.close()
        }
    }
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

private class FakeMessageClient : MessageClient {
    data class SentMessage(val nodeId: String, val path: String, val bytes: ByteArray) {
        val json: JSONObject = JSONObject(String(bytes, Charsets.UTF_8))
        val type: String = json.optString("type")
        val text: String = json.optString("text")
        val seq: Int = json.optInt("seq")
    }

    private val flow = MutableSharedFlow<SentMessage>(extraBufferCapacity = 8)

    suspend fun awaitMessages(count: Int, timeoutMs: Long): List<SentMessage> {
        val collected = mutableListOf<SentMessage>()
        withTimeout(timeoutMs) {
            flow.take(count).collect { collected.add(it) }
        }
        return collected
    }

    override fun sendMessage(nodeId: String, path: String, data: ByteArray): Task<Int> {
        val sent = SentMessage(nodeId, path, data)
        flow.tryEmit(sent)
        return Tasks.forResult(data.size)
    }

    // Unused in tests
    override fun addListener(listener: MessageClient.OnMessageReceivedListener): Task<Void> =
        Tasks.forResult(null)

    override fun addListener(listener: MessageClient.OnMessageReceivedListener, executor: Executor): Task<Void> =
        Tasks.forResult(null)

    override fun removeListener(listener: MessageClient.OnMessageReceivedListener): Task<Boolean> =
        Tasks.forResult(true)

    override fun registerMessageReceiver(service: Context, intent: Intent): Task<Void> =
        Tasks.forResult(null)

    override fun unregisterMessageReceiver(service: Context, intent: Intent): Task<Boolean> =
        Tasks.forResult(true)
}

private class FakeChannelClient(private val bytes: ByteArray) : ChannelClient {
    private val nodeId = "node-1"
    val channel: ChannelClient.Channel = object : ChannelClient.Channel {
        override fun getNodeId(): String = nodeId
        override fun getPath(): String = Bridge.PATH_AUDIO_PCM16
        override fun getToken(): String = "token"
        override fun close() {}
    }

    override fun getInputStream(channel: ChannelClient.Channel): Task<InputStream> {
        return Tasks.forResult(ByteArrayInputStream(bytes))
    }

    // Unused members
    override fun openChannel(nodeId: String, path: String): Task<ChannelClient.Channel> =
        Tasks.forException(UnsupportedOperationException())

    override fun close(channel: ChannelClient.Channel): Task<Void> = Tasks.forResult(null)

    override fun close(channel: ChannelClient.Channel, closeReason: Int): Task<Void> = Tasks.forResult(null)

    override fun sendFile(channel: ChannelClient.Channel, uri: android.net.Uri): Task<Void> =
        Tasks.forException(UnsupportedOperationException())

    override fun sendFile(channel: ChannelClient.Channel, uri: android.net.Uri, startOffset: Long, length: Long): Task<Void> =
        Tasks.forException(UnsupportedOperationException())

    override fun addListener(listener: ChannelClient.ChannelListener): Task<Void> = Tasks.forResult(null)

    override fun addListener(listener: ChannelClient.ChannelListener, executor: Executor): Task<Void> = Tasks.forResult(null)

    override fun removeListener(listener: ChannelClient.ChannelListener): Task<Boolean> = Tasks.forResult(true)

    override fun getOutputStream(channel: ChannelClient.Channel): Task<java.io.OutputStream> =
        Tasks.forException(UnsupportedOperationException())
}
