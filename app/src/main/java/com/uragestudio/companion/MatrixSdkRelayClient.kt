package com.uragestudio.companion

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.FileMessageContent
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersion
import org.matrix.rustcomponents.sdk.SyncListenerV2
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.initPlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.function.Consumer

/**
 * Matrix relay transport backed by Matrix Rust SDK.
 *
 * The one direct request is /account/whoami, needed to turn a previously-issued
 * access token into the user/device identifiers required by Session. Sync,
 * encryption, event sending, timeline decryption, and media decryption are all
 * owned by the SDK and its persistent crypto store.
 */
class MatrixSdkRelayClient(
    private val context: Context,
    private val config: SecureMatrixRelayStore.Config
) {
    data class MediaDownload(val bytes: ByteArray, val contentType: String, val fileName: String)
    data class SourceImageReference(val id: String, val fileName: String)

    fun chat(prompt: String): String = chatStream(prompt) {}

    /**
     * Matrix delivers chat replies as one completed timeline event (or an
     * attached text file), not a token stream. Do not replay a complete long
     * reply through the streaming callback: the UI would render the same large
     * Markdown payload once as a pending message and again as the final bubble.
     */
    fun chatStream(prompt: String, onDelta: Consumer<String>): String = executeAsk(prompt)

    fun generateImage(options: DashboardApi.ImageWorkflowOptions): DashboardApi.WorkflowItem {
        val payload = JSONObject()
            .put("prompt", options.prompt())
            .put("negativePrompt", options.negativePrompt())
            .put("width", options.width())
            .put("height", options.height())
            .put("autoPrompt", options.autoPrompt())
            .put("allowUnencryptedMedia", config.allowUnencryptedMedia())
        options.seed()?.let { payload.put("seed", it) }
        options.steps()?.let { payload.put("steps", it) }
        options.cfg()?.let { payload.put("cfg", it) }
        options.imageId()?.takeIf { it.isNotBlank() }?.let { payload.put("imageId", it) }
        options.imageFileName()?.takeIf { it.isNotBlank() }?.let { payload.put("imageFileName", it) }
        options.matrixSourceId()?.takeIf { it.isNotBlank() }?.let { payload.put("matrixSourceId", it) }
        options.matrixSourceFileName()?.takeIf { it.isNotBlank() }?.let { payload.put("matrixSourceFileName", it) }
        return workflowItem(execute("image", payload))
    }

    fun generateAudio(prompt: String, seconds: Int): DashboardApi.WorkflowItem =
        workflowItem(execute("audio", JSONObject().put("prompt", prompt).put("seconds", seconds)
            .put("allowUnencryptedMedia", config.allowUnencryptedMedia())))

    fun generateMusic(tags: String, lyrics: String, seconds: Int): DashboardApi.WorkflowItem =
        workflowItem(execute("music", JSONObject().put("tags", tags).put("lyrics", lyrics).put("seconds", seconds)
            .put("allowUnencryptedMedia", config.allowUnencryptedMedia())))

    fun interpretImages(images: List<MediaItem>, mode: String, currentPrompt: String): String {
        require(images.isNotEmpty()) { "Add at least one source image first." }
        var interpreted = currentPrompt
        for (image in images) {
            val source = uploadSourceImage(Uri.parse(image.downloadUrl()), image.fileName())
            val payload = JSONObject()
                .put("prompt", interpreted)
                .put("mode", mode)
                .put("matrixSourceId", source.id)
                .put("matrixSourceFileName", source.fileName)
                .put("allowUnencryptedMedia", config.allowUnencryptedMedia())
            interpreted = execute("image-interpret", payload).optString("text").trim()
            require(interpreted.isNotBlank()) { "Matrix image interpretation returned an empty prompt." }
        }
        return interpreted
    }

    fun improveImagePrompt(prompt: String, negativePrompt: String, instructions: String): String {
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("negativePrompt", negativePrompt)
            .put("instructions", instructions)
            .put("allowUnencryptedMedia", config.allowUnencryptedMedia())
        return execute("image-improve", payload).optString("text").trim().also {
            require(it.isNotBlank()) { "Matrix prompt improvement returned an empty prompt." }
        }
    }

    /** Uploads a recorded clip and runs the dashboard STT workflow through the encrypted relay. */
    fun transcribeAudio(audioFile: File): String {
        val source = uploadAudioSource(audioFile)
        val payload = JSONObject()
            .put("matrixAudioSourceId", source.id)
            .put("matrixAudioSourceFileName", source.fileName)
            .put("allowUnencryptedMedia", config.allowUnencryptedMedia())
        return execute("stt", payload).optString("text").trim().also {
            require(it.isNotBlank()) { "Matrix STT returned an empty transcript." }
        }
    }

    fun generateVideo(
        prompt: String,
        negativePrompt: String,
        seconds: Int,
        fps: Int,
        width: Int,
        height: Int,
        steps: Int?,
        seed: Long?,
        imageId: String?,
        imageFileName: String?,
        matrixSourceId: String?,
        matrixSourceFileName: String?
    ): DashboardApi.WorkflowItem {
        val payload = JSONObject()
            .put("prompt", prompt)
            .put("negativePrompt", negativePrompt)
            .put("seconds", seconds)
            .put("fps", fps)
            .put("width", width)
            .put("height", height)
            .put("allowUnencryptedMedia", config.allowUnencryptedMedia())
        steps?.let { payload.put("steps", it) }
        seed?.let { payload.put("seed", it) }
        imageId?.takeIf { it.isNotBlank() }?.let { payload.put("imageId", it) }
        imageFileName?.takeIf { it.isNotBlank() }?.let { payload.put("imageFileName", it) }
        matrixSourceId?.takeIf { it.isNotBlank() }?.let { payload.put("matrixSourceId", it) }
        matrixSourceFileName?.takeIf { it.isNotBlank() }?.let { payload.put("matrixSourceFileName", it) }
        return workflowItem(execute("video", payload))
    }

    fun generateModel3d(
        sourceMode: String, prompt: String, imageId: String?, imageFileName: String?,
        matrixSourceId: String?, matrixSourceFileName: String?, generateLowPoly: Boolean
    ): DashboardApi.WorkflowItem {
        val payload = JSONObject()
            .put("sourceMode", sourceMode)
            .put("prompt", prompt)
            .put("generateLowPoly", generateLowPoly)
            .put("allowUnencryptedMedia", config.allowUnencryptedMedia())
        imageId?.takeIf { it.isNotBlank() }?.let { payload.put("imageId", it) }
        imageFileName?.takeIf { it.isNotBlank() }?.let { payload.put("imageFileName", it) }
        matrixSourceId?.takeIf { it.isNotBlank() }?.let { payload.put("matrixSourceId", it) }
        matrixSourceFileName?.takeIf { it.isNotBlank() }?.let { payload.put("matrixSourceFileName", it) }
        return workflowItem(execute("model3d", payload))
    }

    fun uploadSourceImage(uri: Uri, requestedFileName: String): SourceImageReference = withSession { session ->
        require(session.room.isEncrypted() || config.allowUnencryptedMedia()) {
            "The configured Matrix room is not encrypted. Enable unencrypted-room media only after explicitly accepting the privacy risks."
        }
        val bytes = readSourceBytes(uri)
        val bounds = BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The selected source is not a readable image." }
        val fileName = safeImageFileName(requestedFileName)
        val contentType = context.contentResolver.getType(uri)
            ?.takeIf { it.startsWith("image/") }
            ?: contentTypeFor("image", fileName)
        val sourceId = createRequestId()
        val source = UploadSource.Data(bytes, fileName)
        val parameters = UploadParameters(source, "URAGE_SOURCE $sourceId", null, null, null)
        val imageInfo = ImageInfo(
            bounds.outHeight.toULong(), bounds.outWidth.toULong(), contentType,
            bytes.size.toULong(), null, null, null, false
        )
        val upload = session.timeline.sendImage(parameters, null, imageInfo)
        try {
            upload.join()
        } finally {
            upload.close()
        }
        SourceImageReference(sourceId, fileName)
    }

    private fun uploadAudioSource(audioFile: File): SourceImageReference = withSession { session ->
        require(session.room.isEncrypted() || config.allowUnencryptedMedia()) {
            "The configured Matrix room is not encrypted. Enable unencrypted-room media only after explicitly accepting the privacy risks."
        }
        require(audioFile.isFile) { "The recorded audio file is missing." }
        val bytes = audioFile.readBytes()
        require(bytes.isNotEmpty() && bytes.size <= MAX_SOURCE_AUDIO_BYTES) {
            "Matrix source audio is limited to 20 MiB."
        }
        val fileName = safeAudioFileName(audioFile.name)
        val contentType = contentTypeFor("audio", fileName)
        val sourceId = createRequestId()
        val parameters = UploadParameters(
            UploadSource.Data(bytes, fileName), "URAGE_AUDIO_SOURCE $sourceId", null, null, null
        )
        val upload = session.timeline.sendFile(parameters, FileInfo(contentType, bytes.size.toULong(), null, null))
        try {
            upload.join()
        } finally {
            upload.close()
        }
        SourceImageReference(sourceId, fileName)
    }

    fun download(item: DashboardApi.WorkflowItem): MediaDownload = withSession { session ->
        val descriptor = item.thumbnailUrl()
        val source = if (descriptor.isNotBlank()) MediaSource.fromJson(descriptor) else MediaSource.fromUrl(item.downloadUrl())
        MediaDownload(
            session.client.getMediaContent(source),
            contentTypeFor(item.kind(), item.fileName()),
            item.fileName()
        )
    }

    fun synchronizedChatHistory(limit: Int): List<DashboardApi.ChatMessage> = withSession { session ->
        val items = session.collectTimelineItems(3_000)
        val requests = linkedMapOf<String, String>()
        val replies = linkedMapOf<String, String>()
        val visibleAskRequests = ArrayDeque<String>()
        var visibleAskSequence = 0
        for (item in items) {
            val event = item.asEvent() ?: continue
            val body = textBody(event) ?: continue
            if (event.sender == session.client.userId() && body.startsWith("!urage ")) {
                val pieces = body.split(" ", limit = 4)
                if (pieces.size == 4 && pieces[2] == "chat") {
                    runCatching {
                        val decoded = String(Base64.decode(pieces[3], Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
                        requests[pieces[1]] = JSONObject(decoded).optString("prompt")
                    }
                }
            } else if (event.sender == session.client.userId() && body.startsWith("!ask ")) {
                val requestId = "ask-${visibleAskSequence++}"
                requests[requestId] = body.removePrefix("!ask ").trim()
                visibleAskRequests.addLast(requestId)
            } else if (event.sender == config.botUserId() && body.startsWith("URAGE_RESULT ")) {
                val pieces = body.split(" ", limit = 4)
                if (pieces.size == 4 && pieces[2] == "ok") {
                    runCatching {
                        val decoded = String(Base64.decode(pieces[3], Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
                        replies[pieces[1]] = JSONObject(decoded).optString("text")
                    }
                }
            } else if (event.sender == config.botUserId()
                && !body.startsWith("URAGE_")
                && visibleAskRequests.isNotEmpty()) {
                replies[visibleAskRequests.removeFirst()] = body
            }
        }
        requests.entries.flatMap { (requestId, prompt) ->
            val reply = replies[requestId].orEmpty()
            if (prompt.isBlank() || reply.isBlank()) emptyList()
            else listOf(DashboardApi.ChatMessage("user", prompt), DashboardApi.ChatMessage("assistant", reply))
        }.takeLast(limit.coerceAtLeast(2))
    }

    private fun execute(
        action: String,
        payload: JSONObject,
        onProgress: (String) -> Unit = {}
    ): JSONObject = withSession { session ->
        val requestId = createRequestId()
        val result = CompletableDeferred<JSONObject>()
        val acknowledgement = CompletableDeferred<Unit>()
        val outboundDelivery = CompletableDeferred<Unit>()
        var nextProgressSequence = 0
        val timeline = session.timeline
        val encoded = Base64.encodeToString(
            payload.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val commandBody = "!urage $requestId $action $encoded"
        val listener = timeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                // This callback originates from the Rust SDK. Never let an exception cross
                // that boundary: Android treats it as an uncaught native callback failure and
                // can terminate the process instead of reporting a workflow error in the UI.
                try {
                    for (item in timelineItems(diff)) {
                        val event = item.asEvent() ?: continue
                        val body = textBody(event).orEmpty()
                        if (event.sender == session.client.userId() && body == commandBody) {
                            when (val sendState = event.localSendState) {
                                is EventSendState.Sent -> outboundDelivery.complete(Unit)
                                is EventSendState.SendingFailed -> if (!outboundDelivery.isCompleted) {
                                    outboundDelivery.completeExceptionally(
                                        IllegalStateException("Matrix rejected the outbound command: ${sendState.error}")
                                    )
                                }
                                else -> if (event.isRemote) outboundDelivery.complete(Unit)
                            }
                        }
                        if (event.sender != config.botUserId()) continue
                        parsePrompt(body, requestId)?.let { prompt ->
                            // The bot can only emit a correlated reply after the homeserver
                            // accepted our command. Some SDK timelines do not replay our own
                            // final send-state transition, so the reply is the stronger proof.
                            outboundDelivery.complete(Unit)
                            acknowledgement.complete(Unit)
                            if (!result.isCompleted) result.complete(JSONObject().put("text", prompt))
                        }
                        parseProgress(body, requestId)?.let { progress ->
                            synchronized(result) {
                                if (progress.first == nextProgressSequence) {
                                    nextProgressSequence += 1
                                    outboundDelivery.complete(Unit)
                                    acknowledgement.complete(Unit)
                                    onProgress(progress.second)
                                }
                            }
                        }
                        parseResult(body, requestId)?.let { parsed ->
                            outboundDelivery.complete(Unit)
                            acknowledgement.complete(Unit)
                            if (parsed.error != null) {
                                if (!result.isCompleted) result.completeExceptionally(IllegalStateException(parsed.error))
                            } else if (!result.isCompleted) {
                                result.complete(requireNotNull(parsed.payload))
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (!result.isCompleted) {
                        result.completeExceptionally(IllegalStateException("Matrix relay response could not be processed.", error))
                    }
                }
            }
        })
        try {
            val message = requireNotNull(timeline.createMessageContent(MessageType.Text(TextMessageContent(commandBody, null))))
            val send = timeline.send(message)
            try {
                try {
                    withTimeout(OUTBOUND_DELIVERY_TIMEOUT_MS) { outboundDelivery.await() }
                } catch (_: TimeoutCancellationException) {
                    throw IllegalStateException(
                        "The Matrix command remained in the local send queue and never reached the homeserver. " +
                            "Re-save the Matrix connection or create a fresh Matrix device session."
                    )
                }
                try {
                    withTimeout(RELAY_ACKNOWLEDGEMENT_TIMEOUT_MS) { acknowledgement.await() }
                } catch (_: TimeoutCancellationException) {
                    throw IllegalStateException(
                        "No Matrix bot acknowledgement was received. Start the Matrix Runtime in Dashboard " +
                            "with a separate bot access token; joining the bot account to the room is not enough."
                    )
                }
                withTimeout(WORKFLOW_TIMEOUT_MS) { result.await() }
            } finally {
                send.close()
            }
        } finally {
            listener.cancel()
        }
    }

    /**
     * Chat deliberately uses a human-readable Matrix command. Media workflows retain the
     * correlated relay protocol because their results can include encrypted attachments.
     * Sessions are serialized, so one `!ask` can safely await the next normal bot reply.
     */
    private fun executeAsk(prompt: String): String = withSession { session ->
        val commandBody = "!ask ${prompt.trim()}"
        require(commandBody.length > "!ask ".length) { "Message is required." }
        // Adding a timeline listener can replay already-loaded events. Without a
        // baseline, an earlier bot failure was accepted as the response for the
        // newly-sent command before the real answer arrived. Keep only bodies
        // that were already visible before this request as stale candidates.
        val preexistingBotReplies = session.collectTimelineItems(PREEXISTING_REPLY_SNAPSHOT_MS)
            .mapNotNull { it.asEvent() }
            .filter { it.sender == config.botUserId() }
            .mapNotNull { textBody(it) }
            .filterNot { it.startsWith("URAGE_") }
            .toHashSet()
        val outboundDelivery = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<ChatReply>()
        var expectedTextAttachment: String? = null
        var receivedTextAttachment: TextAttachment? = null
        val timeline = session.timeline
        val listener = timeline.addListener(object : TimelineListener {
            override fun onUpdate(diff: List<TimelineDiff>) {
                try {
                    for (item in timelineItems(diff)) {
                        val event = item.asEvent() ?: continue
                        val body = textBody(event).orEmpty()
                        if (event.sender == session.client.userId() && body == commandBody) {
                            when (val sendState = event.localSendState) {
                                is EventSendState.Sent -> outboundDelivery.complete(Unit)
                                is EventSendState.SendingFailed -> if (!outboundDelivery.isCompleted) {
                                    outboundDelivery.completeExceptionally(
                                        IllegalStateException("Matrix rejected the outbound message: ${sendState.error}")
                                    )
                                }
                                else -> if (event.isRemote) outboundDelivery.complete(Unit)
                            }
                        } else if (event.sender == config.botUserId()) {
                            val attachment = textAttachment(event)
                            if (attachment != null) {
                                receivedTextAttachment = attachment
                                if (attachment.fileName == expectedTextAttachment && !reply.isCompleted) {
                                    reply.complete(ChatReply.Attachment(attachment))
                                }
                            } else if (!body.startsWith("URAGE_")
                                && body !in preexistingBotReplies
                                && !reply.isCompleted) {
                                // A fresh bot reply is stronger evidence that the command was
                                // delivered than the local echo event. Some SDK timelines do
                                // not surface that echo, which otherwise leaves Chat waiting
                                // until the workflow timeout even though Matrix already replied.
                                outboundDelivery.complete(Unit)
                                val attachmentName = attachedTextFileName(body)
                                if (attachmentName == null) {
                                    reply.complete(ChatReply.Inline(body))
                                } else {
                                    expectedTextAttachment = attachmentName
                                    if (receivedTextAttachment?.fileName == attachmentName) {
                                        reply.complete(ChatReply.Attachment(requireNotNull(receivedTextAttachment)))
                                    }
                                }
                            }
                        }
                    }
                } catch (error: Exception) {
                    if (!reply.isCompleted) {
                        reply.completeExceptionally(IllegalStateException("Matrix chat response could not be processed.", error))
                    }
                }
            }
        })
        try {
            val message = requireNotNull(timeline.createMessageContent(MessageType.Text(TextMessageContent(commandBody, null))))
            val send = timeline.send(message)
            try {
                try {
                    withTimeout(OUTBOUND_DELIVERY_TIMEOUT_MS) { outboundDelivery.await() }
                } catch (_: TimeoutCancellationException) {
                    throw IllegalStateException("The Matrix message remained in the local send queue and never reached the homeserver.")
                }
                when (val response = withTimeout(WORKFLOW_TIMEOUT_MS) { reply.await() }) {
                    is ChatReply.Inline -> response.text
                    is ChatReply.Attachment -> String(
                        session.client.getMediaContent(response.attachment.source),
                        StandardCharsets.UTF_8
                    )
                }
            } finally {
                send.close()
            }
        } finally {
            listener.cancel()
        }
    }

    private fun workflowItem(payload: JSONObject): DashboardApi.WorkflowItem {
        val encryptedFile = payload.optJSONObject("encryptedFile")
        return DashboardApi.WorkflowItem(
            payload.optString("id"),
            payload.optString("kind"),
            payload.optString("fileName"),
            payload.optString("fileName"),
            payload.optString("mxcUrl"),
            encryptedFile?.toString().orEmpty()
        )
    }

    private fun parseResult(body: String, requestId: String): RelayResult? {
        val prefix = "URAGE_RESULT $requestId "
        if (!body.startsWith(prefix)) return null
        val pieces = body.split(" ", limit = 4)
        if (pieces.size != 4) return null
        val payload = runCatching {
            val decoded = String(Base64.decode(pieces[3], Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
            JSONObject(decoded)
        }.getOrNull() ?: return null
        return if (pieces[2] == "ok") RelayResult(payload, null)
        else RelayResult(null, payload.optString("error", "Matrix relay workflow failed."))
    }

    private fun parsePrompt(body: String, requestId: String): String? {
        val prefix = "URAGE_PROMPT $requestId "
        if (!body.startsWith(prefix)) return null
        return body.removePrefix(prefix).trim().takeIf { it.isNotBlank() }
    }

    private fun parseProgress(body: String, requestId: String): Pair<Int, String>? {
        val prefix = "URAGE_PROGRESS $requestId "
        if (!body.startsWith(prefix)) return null
        val pieces = body.split(" ", limit = 4)
        if (pieces.size != 4) return null
        val sequence = pieces[2].toIntOrNull() ?: return null
        val payload = runCatching {
            val decoded = String(Base64.decode(pieces[3], Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
            JSONObject(decoded)
        }.getOrNull() ?: return null
        return sequence to payload.optString("delta")
    }

    private fun <T> withSession(block: suspend (SdkSession) -> T): T = synchronized(SDK_SESSION_LOCK) {
        runBlocking {
            validateConfiguration()
            initializeSdkPlatform()
            val identity = resolveCachedIdentity()
            val storeId = sha256("${identity.userId}:${identity.deviceId}").take(24)
            val dataDirectory = File(context.filesDir, "matrix-sdk/$storeId").apply { mkdirs() }
            val cacheDirectory = File(context.cacheDir, "matrix-sdk/$storeId").apply { mkdirs() }
            val client = ClientBuilder()
                .homeserverUrl(config.homeserverUrl())
                .sessionPaths(dataDirectory.absolutePath, cacheDirectory.absolutePath)
                .build()
            val session = Session(
                config.accessToken(),
                null,
                identity.userId,
                identity.deviceId,
                config.homeserverUrl(),
                null,
                // Native Sliding Sync needs a server-side room-list subscription. The
                // companion owns one private room, so the compatibility sync mode is
                // both sufficient and works with more homeservers.
                SlidingSyncVersion.NONE
            )
            client.restoreSession(session)
            client.encryption().waitForE2eeInitializationTasks()
            // The encrypted store persists the room after the first successful session.
            // Reusing it avoids a needless one-shot sync on every send; first launch and
            // a changed store still synchronize before the lookup.
            val room = client.getRoom(config.roomId()) ?: run {
                client.syncOnceV2(SyncSettingsV2(INITIAL_SYNC_TIMEOUT_MS.toULong(), true))
                client.getRoom(config.roomId())
            } ?: throw IllegalStateException(
                "Matrix synchronized successfully but did not expose the configured room. " +
                    "Leave and rejoin the room with this Matrix account, then save Connection again."
            )
            // Keep the normal long-poll loop running after the deterministic initial
            // sync so that outgoing delivery state, bot progress, and replies arrive.
            val sync = client.syncV2(
                SyncSettingsV2(SYNC_REQUEST_TIMEOUT_MS.toULong(), false),
                object : SyncListenerV2 {
                    override fun onUpdate(response: org.matrix.rustcomponents.sdk.SyncResponseV2) = Unit
                }
            )
            val timeline = room.timeline()
            try {
                // Timeline.send() writes through the Rust SDK's per-room send queue.
                // Without explicitly enabling it, a relay command can remain local and
                // never become an event visible to the bot or Element.
                room.enableSendQueue(true)
                block(SdkSession(client, sync, timeline, room))
            } finally {
                // UniFFI uses Android's Cleaner only as a last-resort fallback. Leaving
                // Room and Timeline to that finalizer runs their Rust destructors on the
                // FinalizerDaemon thread, which has no Tokio runtime. Dispose each native
                // object while this session is still active instead.
                runCatching { sync.cancel() }
                runCatching { sync.close() }
                runCatching { timeline.close() }
                runCatching { room.close() }
                runCatching { client.close() }
            }
        }
    }

    private fun validateConfiguration() {
        require(config.homeserverUrl().startsWith("https://")) { "Matrix homeserver must use HTTPS." }
        require(config.accessToken().isNotBlank() && config.botUserId().startsWith("@") && config.roomId().startsWith("!")) {
            "Configure a Matrix token, full bot user ID, and private room ID under Connection."
        }
    }

    private fun resolveIdentity(): Identity {
        val value = authenticatedGet("/_matrix/client/v3/account/whoami", "Matrix session lookup")
        return Identity(value.getString("user_id"), value.getString("device_id"))
    }

    private fun resolveCachedIdentity(): Identity {
        val key = sha256("${config.homeserverUrl()}|${config.accessToken()}")
        synchronized(IDENTITY_CACHE_LOCK) {
            if (cachedIdentityKey == key && cachedIdentity != null) return cachedIdentity!!
        }
        val identity = resolveIdentity()
        synchronized(IDENTITY_CACHE_LOCK) {
            cachedIdentityKey = key
            cachedIdentity = identity
        }
        return identity
    }

    private fun authenticatedGet(path: String, operation: String): JSONObject {
        val connection = URL(config.homeserverUrl().trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Authorization", "Bearer ${config.accessToken()}")
        val status = connection.responseCode
        val input = if (status in 200..299) connection.inputStream else connection.errorStream
        val body = input?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw IllegalStateException(JSONObject(body).optString("error", "$operation failed ($status)."))
        return JSONObject(body)
    }

    private data class Identity(val userId: String, val deviceId: String)
    private sealed interface ChatReply {
        data class Inline(val text: String) : ChatReply
        data class Attachment(val attachment: TextAttachment) : ChatReply
    }
    private data class TextAttachment(val fileName: String, val source: MediaSource)
    private data class RelayResult(val payload: JSONObject?, val error: String?)
    private data class SdkSession(
        val client: Client,
        val sync: TaskHandle,
        val timeline: Timeline,
        val room: org.matrix.rustcomponents.sdk.Room
    ) {
        suspend fun collectTimelineItems(waitMs: Long): List<TimelineItem> {
            val items = mutableListOf<TimelineItem>()
            val lock = Any()
            var callbackFailure: Exception? = null
            val listener: TaskHandle = timeline.addListener(object : TimelineListener {
                override fun onUpdate(diff: List<TimelineDiff>) {
                    try {
                        val received = timelineItems(diff)
                        synchronized(lock) { items.addAll(received) }
                    } catch (error: Exception) {
                        synchronized(lock) { callbackFailure = error }
                    }
                }
            })
            try {
                delay(waitMs)
            } finally {
                listener.cancel()
            }
            return synchronized(lock) {
                callbackFailure?.let { throw IllegalStateException("Matrix timeline could not be synchronized.", it) }
                items.toList()
            }
        }
    }

    companion object {
        private const val INITIAL_SYNC_TIMEOUT_MS = 0L
        private const val SYNC_REQUEST_TIMEOUT_MS = 30_000L
        private const val OUTBOUND_DELIVERY_TIMEOUT_MS = 30_000L
        private const val RELAY_ACKNOWLEDGEMENT_TIMEOUT_MS = 30_000L
        private const val WORKFLOW_TIMEOUT_MS = 15 * 60 * 1000L
        // This only gives the SDK enough time to replay its already-loaded timeline.
        // Keeping it short removes a fixed delay from every normal Matrix chat send.
        private const val PREEXISTING_REPLY_SNAPSHOT_MS = 40L
        private const val MAX_SOURCE_IMAGE_BYTES = 20 * 1024 * 1024
        private const val MAX_SOURCE_AUDIO_BYTES = 20 * 1024 * 1024
        private val ATTACHED_TEXT_FILE_PATTERN = Regex("attached as ([A-Za-z0-9._-]+\\.txt)", RegexOption.IGNORE_CASE)
        private val SDK_SESSION_LOCK = Any()
        private val SDK_PLATFORM_LOCK = Any()
        private val IDENTITY_CACHE_LOCK = Any()
        private var sdkPlatformInitialized = false
        private var cachedIdentityKey: String? = null
        private var cachedIdentity: Identity? = null

        /**
         * Initializes Matrix Rust SDK's Android TLS verifier exactly once before
         * any SDK client can open a network connection.
         */
        private fun initializeSdkPlatform() = synchronized(SDK_PLATFORM_LOCK) {
            if (sdkPlatformInitialized) return@synchronized
            initPlatform(
                TracingConfiguration(LogLevel.ERROR, emptyList(), emptyList(), false, null, null),
                false
            )
            sdkPlatformInitialized = true
        }

        private fun timelineItems(diffs: List<TimelineDiff>): List<TimelineItem> = diffs.flatMap { diff ->
            when (diff) {
                is TimelineDiff.Reset -> diff.values
                is TimelineDiff.Append -> diff.values
                is TimelineDiff.PushBack -> listOf(diff.value)
                is TimelineDiff.PushFront -> listOf(diff.value)
                is TimelineDiff.Insert -> listOf(diff.value)
                is TimelineDiff.Set -> listOf(diff.value)
                else -> emptyList()
            }
        }

        private fun textBody(event: EventTimelineItem): String? {
            val msgLike = event.content as? TimelineItemContent.MsgLike ?: return null
            val message = msgLike.content.kind as? MsgLikeKind.Message ?: return null
            return message.content.body
        }

        private fun attachedTextFileName(body: String): String? =
            ATTACHED_TEXT_FILE_PATTERN.find(body)?.groupValues?.getOrNull(1)

        private fun textAttachment(event: EventTimelineItem): TextAttachment? {
            val msgLike = event.content as? TimelineItemContent.MsgLike ?: return null
            val message = msgLike.content.kind as? MsgLikeKind.Message ?: return null
            val file = message.content.msgType as? MessageType.File ?: return null
            val content: FileMessageContent = file.content
            val fileName = content.filename.ifBlank { message.content.body }
            if (!fileName.endsWith(".txt", ignoreCase = true)) return null
            return TextAttachment(fileName, content.source)
        }

        private fun createRequestId(): String {
            val bytes = ByteArray(12)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        private fun safeImageFileName(value: String): String {
            val safe = value.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
            return safe.ifBlank { "matrix-source.jpg" }
        }

        private fun safeAudioFileName(value: String): String {
            val safe = value.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
            return safe.ifBlank { "matrix-audio.m4a" }
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun contentTypeFor(kind: String, fileName: String): String = when {
            kind == "image" || fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".glb", true) -> "model/gltf-binary"
            fileName.endsWith(".gltf", true) -> "model/gltf+json"
            fileName.endsWith(".m4a", true) || fileName.endsWith(".mp4", true) -> "audio/mp4"
            fileName.endsWith(".webm", true) -> "audio/webm"
            fileName.endsWith(".wav", true) -> "audio/wav"
            fileName.endsWith(".ogg", true) -> "audio/ogg"
            else -> "application/octet-stream"
        }
    }

    private fun readSourceBytes(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Android could not open the selected source image.")
        return input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                require(total <= MAX_SOURCE_IMAGE_BYTES) {
                    "Matrix source images are limited to 20 MiB."
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}
