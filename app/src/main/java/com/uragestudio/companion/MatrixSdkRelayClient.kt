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
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ImageInfo
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
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
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

    fun chatStream(prompt: String, onDelta: Consumer<String>): String =
        execute("chat", JSONObject().put("prompt", prompt)) { delta -> onDelta.accept(delta) }.optString("text")

    fun generateImage(options: DashboardApi.ImageWorkflowOptions): DashboardApi.WorkflowItem {
        val payload = JSONObject()
            .put("prompt", options.prompt())
            .put("negativePrompt", options.negativePrompt())
            .put("width", options.width())
            .put("height", options.height())
            .put("autoPrompt", options.autoPrompt())
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
        workflowItem(execute("audio", JSONObject().put("prompt", prompt).put("seconds", seconds)))

    fun generateMusic(tags: String, lyrics: String, seconds: Int): DashboardApi.WorkflowItem =
        workflowItem(execute("music", JSONObject().put("tags", tags).put("lyrics", lyrics).put("seconds", seconds)))

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
        imageId?.takeIf { it.isNotBlank() }?.let { payload.put("imageId", it) }
        imageFileName?.takeIf { it.isNotBlank() }?.let { payload.put("imageFileName", it) }
        matrixSourceId?.takeIf { it.isNotBlank() }?.let { payload.put("matrixSourceId", it) }
        matrixSourceFileName?.takeIf { it.isNotBlank() }?.let { payload.put("matrixSourceFileName", it) }
        return workflowItem(execute("model3d", payload))
    }

    fun uploadSourceImage(uri: Uri, requestedFileName: String): SourceImageReference = withSession { session ->
        require(session.room.isEncrypted()) {
            "The configured Matrix room is not encrypted. Source images are never uploaded to a plaintext room."
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
            } else if (event.sender == config.botUserId() && body.startsWith("URAGE_RESULT ")) {
                val pieces = body.split(" ", limit = 4)
                if (pieces.size == 4 && pieces[2] == "ok") {
                    runCatching {
                        val decoded = String(Base64.decode(pieces[3], Base64.URL_SAFE or Base64.NO_WRAP), StandardCharsets.UTF_8)
                        replies[pieces[1]] = JSONObject(decoded).optString("text")
                    }
                }
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
                        parseProgress(body, requestId)?.let { progress ->
                            synchronized(result) {
                                if (progress.first == nextProgressSequence) {
                                    nextProgressSequence += 1
                                    acknowledgement.complete(Unit)
                                    onProgress(progress.second)
                                }
                            }
                        }
                        parseResult(body, requestId)?.let { parsed ->
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
            val identity = resolveIdentity()
            validateConfiguredRoomMembership()
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
            val sync = client.syncV2(
                SyncSettingsV2(30_000uL, true),
                object : SyncListenerV2 {
                    override fun onUpdate(response: org.matrix.rustcomponents.sdk.SyncResponseV2) = Unit
                }
            )
            try {
                val room = withTimeout(ROOM_DISCOVERY_TIMEOUT_MS) {
                    while (client.getRoom(config.roomId()) == null) delay(250)
                    client.getRoom(config.roomId())!!
                }
                // Timeline.send() writes through the Rust SDK's per-room send queue.
                // Without explicitly enabling it, a relay command can remain local and
                // never become an event visible to the bot or Element.
                room.enableSendQueue(true)
                val timeline = room.timeline()
                block(SdkSession(client, sync, timeline, room))
            } finally {
                sync.cancel()
                sync.close()
                client.close()
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

    private fun validateConfiguredRoomMembership() {
        val rooms = authenticatedGet("/_matrix/client/v3/joined_rooms", "Matrix room lookup")
            .optJSONArray("joined_rooms")
            ?.let { values -> buildSet { for (index in 0 until values.length()) add(values.getString(index)) } }
            .orEmpty()
        require(config.roomId() in rooms) {
            "The Matrix account for this access token has not joined the configured private room. " +
                "Join the exact room from your Matrix client, then save the Connection settings again."
        }
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
        private const val ROOM_DISCOVERY_TIMEOUT_MS = 20_000L
        private const val OUTBOUND_DELIVERY_TIMEOUT_MS = 30_000L
        private const val RELAY_ACKNOWLEDGEMENT_TIMEOUT_MS = 30_000L
        private const val WORKFLOW_TIMEOUT_MS = 15 * 60 * 1000L
        private const val MAX_SOURCE_IMAGE_BYTES = 20 * 1024 * 1024
        private val SDK_SESSION_LOCK = Any()

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

        private fun createRequestId(): String {
            val bytes = ByteArray(12)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        private fun safeImageFileName(value: String): String {
            val safe = value.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(120)
            return safe.ifBlank { "matrix-source.jpg" }
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun contentTypeFor(kind: String, fileName: String): String = when {
            kind == "image" || fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".glb", true) -> "model/gltf-binary"
            fileName.endsWith(".gltf", true) -> "model/gltf+json"
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
