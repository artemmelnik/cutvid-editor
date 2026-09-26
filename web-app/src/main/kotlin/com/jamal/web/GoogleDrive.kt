package com.jamal.web

import com.sun.net.httpserver.HttpExchange
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Google Drive delivery for project renders. Connecting Drive is a separate, optional Google
 * consent for offline access to the `drive` scope, which is what allows uploading into any
 * folder the user pastes a link to. The refresh token is the only Google token kept on disk:
 * it is AES-GCM encrypted with a key derived from the session secret and deleted (and revoked)
 * on disconnect.
 */

internal const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
private const val DRIVE_FOLDER_MIME = "application/vnd.google-apps.folder"
private const val MAX_UPLOAD_ATTEMPTS = 3
private const val PARALLEL_UPLOADS = 2

internal enum class DriveStatus { PENDING, UPLOADING, UPLOADED, FAILED }

private class DriveException(message: String, val retryable: Boolean = false) : IllegalStateException(message)
private data class DriveConnection(val email: String, val refreshToken: String)
private data class DriveAccess(val token: String, val expiresAt: Long)

private val driveAccess = ConcurrentHashMap<String, DriveAccess>()
private val uploadWorkerNumber = AtomicInteger()
private val uploadQueue = Executors.newFixedThreadPool(PARALLEL_UPLOADS) { task ->
    Thread(task, "jamal-drive-${uploadWorkerNumber.incrementAndGet()}").apply { isDaemon = true }
}

internal fun driveAvailable(user: SignedInUser) = googleOauthConfigured() && !user.id.startsWith("local-")
internal fun driveConnected(user: SignedInUser) = driveConnection(user) != null

internal fun driveStatusJson(user: SignedInUser): String {
    val connection = if (driveAvailable(user)) driveConnection(user) else null
    return "{\"available\":${driveAvailable(user)},\"connected\":${connection != null},\"email\":${connection?.email.jsonOrNull()}}"
}

/** Accepts a Drive folder link (or a bare folder id) and returns the folder id; null when the link is empty. */
internal fun driveFolderId(link: String): String? {
    val value = link.trim()
    if (value.isEmpty()) return null
    if (Regex("drive\\.google\\.com/drive/(u/\\d+/)?my-drive").containsMatchIn(value)) return "root"
    return Regex("/folders/([A-Za-z0-9_-]{10,})").find(value)?.groupValues?.get(1)
        ?: Regex("[?&]id=([A-Za-z0-9_-]{10,})").find(value)?.groupValues?.get(1)
        ?: value.takeIf { it.matches(Regex("[A-Za-z0-9_-]{10,}")) }
        ?: throw IllegalArgumentException("Paste a Google Drive folder link, such as https://drive.google.com/drive/folders/…")
}

internal fun driveFolderLink(folderId: String) =
    if (folderId == "root") "https://drive.google.com/drive/my-drive" else "https://drive.google.com/drive/folders/$folderId"

internal fun beginDriveConnect(exchange: HttpExchange) {
    val user = currentUser(exchange)
    val clientId = googleClientId()
    if (user == null || clientId.isNullOrBlank() || !driveAvailable(user)) {
        return redirect(exchange, "/?driveError=" + urlEncode("Sign in with Google before connecting Google Drive."))
    }
    val query = mapOf(
        "client_id" to clientId, "redirect_uri" to googleRedirectUri(), "response_type" to "code",
        "scope" to "openid email $DRIVE_SCOPE", "state" to newOAuthState(driveUserId = user.id),
        "access_type" to "offline", "prompt" to "consent", "include_granted_scopes" to "true", "login_hint" to user.email,
    ).entries.joinToString("&") { "${it.key}=${urlEncode(it.value)}" }
    redirect(exchange, "https://accounts.google.com/o/oauth2/v2/auth?$query")
}

/** Finishes the Drive consent that [beginDriveConnect] started; Google returns to the shared OAuth callback. */
internal fun completeDriveConnect(exchange: HttpExchange, query: Map<String, String>, driveUserId: String) {
    try {
        // The consent is bound to the session that asked for it, so nobody can attach their Drive to another account.
        val user = currentUser(exchange)?.takeIf { it.id == driveUserId }
            ?: error("Your sign-in changed while Google Drive was connecting. Please try again.")
        query["error"]?.let { error(if (it == "access_denied") "Google Drive access was not granted." else "Google could not connect Drive ($it).") }
        val code = query["code"] ?: error("Google did not return an authorization code.")
        val response = googleTokenRequest(mapOf("code" to code, "redirect_uri" to googleRedirectUri(), "grant_type" to "authorization_code"))
        require(response.statusCode() == 200) { "Google Drive could not be connected (HTTP ${response.statusCode()})." }
        val body = response.body()
        require(DRIVE_SCOPE in jsonString(body, "scope").orEmpty().split(' ')) { "Allow access to Google Drive on Google's consent screen, then connect again." }
        val refreshToken = jsonString(body, "refresh_token")
            ?: error("Google did not grant offline access. Remove this app under your Google Account's third-party connections, then connect again.")
        val accessToken = jsonString(body, "access_token") ?: error("Google did not return an access token.")
        val profile = httpClient.send(HttpRequest.newBuilder(URI("https://openidconnect.googleapis.com/v1/userinfo"))
            .header("Authorization", "Bearer $accessToken").GET().build(), HttpResponse.BodyHandlers.ofString())
        val email = jsonString(profile.body(), "email")?.takeIf { profile.statusCode() == 200 } ?: error("Google account details could not be read.")
        saveDriveConnection(user, DriveConnection(email, refreshToken))
        driveAccess[user.id] = DriveAccess(accessToken, expiresAt(body))
        ActivityLog.info("drive.connected", "Google Drive connected", requestContext(exchange, mapOf("owner" to user.email, "driveAccount" to email)))
        redirect(exchange, "/?drive=connected")
    } catch (error: Exception) {
        ActivityLog.warn("drive.connect_failed", error.message ?: "Google Drive connection failed", requestContext(exchange))
        redirect(exchange, "/?driveError=" + urlEncode(error.message ?: "Google Drive connection failed."))
    }
}

internal fun disconnectDrive(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    val connection = driveConnection(user)
    forgetDriveConnection(user)
    connection?.let {
        runCatching {
            httpClient.send(HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/revoke"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("token=" + urlEncode(it.refreshToken))).build(), HttpResponse.BodyHandlers.discarding())
        }
    }
    ActivityLog.info("drive.disconnected", "Google Drive disconnected", requestContext(exchange, mapOf("owner" to user.email)))
    respondJson(exchange, 200, "{\"drive\":${driveStatusJson(user)}}")
}

/** Confirms that the connected account can add files to [folderId] and returns the folder's name. */
internal fun driveFolderName(user: SignedInUser, folderId: String): String {
    val response = driveSend(user) { token ->
        HttpRequest.newBuilder(URI("https://www.googleapis.com/drive/v3/files/$folderId?fields=name,mimeType,trashed,capabilities/canAddChildren&supportsAllDrives=true"))
            .header("Authorization", "Bearer $token").GET().build()
    }
    val body = response.body()
    val account = driveConnection(user)?.email ?: "The connected Google account"
    if (response.statusCode() == 404) throw DriveException("That Google Drive folder was not found, or $account cannot open it.")
    if (response.statusCode() != 200) throw driveFailure(response, "Checking the Google Drive folder")
    if (jsonString(body, "mimeType") != DRIVE_FOLDER_MIME) throw DriveException("The Google Drive link points to a file, not a folder.")
    if (jsonBoolean(body, "trashed")) throw DriveException("That Google Drive folder is in the trash.")
    if (!jsonBoolean(body, "canAddChildren")) throw DriveException("$account can open that Google Drive folder but cannot add files to it. Ask its owner for Editor access.")
    return jsonString(body, "name")?.replace(Regex("\\p{Cntrl}"), " ") ?: "Google Drive folder"
}

internal fun queueDriveUpload(job: RenderJob) {
    job.driveStatus = DriveStatus.PENDING
    job.drivePercent = null
    job.driveMessage = "Waiting for a Google Drive upload slot"
    persistJob(job)
    uploadQueue.submit { uploadToDrive(job) }
}

internal fun retryDriveUpload(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    val id = exchange.requestURI.path.removeSuffix("/drive").substringAfterLast('/')
    val job = jobs[id]?.takeIf { it.owner.id == user.id } ?: return respondJson(exchange, 404, "{\"error\":\"Unknown render\"}")
    try {
        // A corrected link in the project applies to retries of its earlier renders too.
        val folderId = job.projectId?.let(projects::get)?.settings?.driveFolderId ?: job.driveFolderId
            ?: error("This render has no Google Drive folder.")
        require(job.state == JobState.COMPLETE && Files.isRegularFile(job.output)) { "Only finished renders can be uploaded." }
        driveAccessToken(user)
        synchronized(job) {
            require(job.driveStatus != DriveStatus.PENDING && job.driveStatus != DriveStatus.UPLOADING) { "This video is already being uploaded." }
            // An unfinished session belongs to the folder it was started for.
            if (folderId != job.driveFolderId) job.driveSession = null
            job.driveFolderId = folderId
            queueDriveUpload(job)
        }
        ActivityLog.info("drive.upload_requeued", "Google Drive upload queued again", requestContext(exchange, mapOf("folderId" to folderId)).copy(jobId = job.id))
        respondJson(exchange, 202, renderJson(job))
    } catch (error: Exception) {
        respondJson(exchange, 400, "{\"error\":\"${(error.message ?: "The upload could not be retried.").jsonEscape()}\"}")
    }
}

private fun uploadToDrive(job: RenderJob) {
    val folderId = job.driveFolderId ?: run {
        job.driveStatus = null
        persistJob(job)
        return
    }
    try {
        require(Files.isRegularFile(job.output)) { "The rendered video is no longer on this server." }
        job.driveStatus = DriveStatus.UPLOADING
        job.drivePercent = 0
        job.driveMessage = "Uploading to Google Drive"
        persistJob(job)
        ActivityLog.info("drive.upload_started", "Google Drive upload started", jobContext(job, mapOf("folderId" to folderId, "bytes" to Files.size(job.output))))
        var attempt = 1
        while (true) {
            try {
                job.driveUrl = uploadOnce(job, folderId)
                break
            } catch (error: Exception) {
                val retryable = error is IOException || (error as? DriveException)?.retryable == true
                if (!retryable || attempt >= MAX_UPLOAD_ATTEMPTS) throw error
                ActivityLog.warn("drive.upload_retry", "Google Drive upload attempt $attempt failed; retrying", jobContext(job, mapOf("error" to error.message)))
                Thread.sleep(3_000L * attempt)
                attempt++
            }
        }
        job.driveStatus = DriveStatus.UPLOADED
        job.drivePercent = 100
        job.driveMessage = "Saved to Google Drive"
        persistJob(job)
        ActivityLog.info("drive.upload_completed", "Video saved to Google Drive", jobContext(job, mapOf("folderId" to folderId)))
    } catch (error: Exception) {
        job.driveStatus = DriveStatus.FAILED
        job.driveMessage = if (error is IOException) "The upload was interrupted (${error.message ?: error::class.simpleName})." else error.message ?: "Google Drive upload failed."
        persistJob(job)
        ActivityLog.error("drive.upload_failed", job.driveMessage, error, jobContext(job, mapOf("folderId" to folderId)))
    }
}

/**
 * Uploads through a resumable session: the metadata request returns an upload address, then one PUT streams the file.
 * A session left behind by an interrupted attempt or a server restart is asked how much it already holds first, so
 * the upload continues where it stopped instead of sending everything again or creating a second copy.
 */
private fun uploadOnce(job: RenderJob, folderId: String): String? {
    val size = Files.size(job.output)
    require(size > 0) { "The rendered video is empty." }
    job.driveSession?.let { session ->
        val status = try {
            httpClient.send(HttpRequest.newBuilder(URI(session)).header("Content-Range", "bytes */$size")
                .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
        } catch (error: IOException) {
            throw DriveException("Google Drive could not be reached (${error.message ?: error::class.simpleName}).", retryable = true)
        }
        when (status.statusCode()) {
            200, 201 -> return finishUpload(job, status)
            308 -> {
                val received = status.headers().firstValue("Range").map { it.substringAfterLast('-').toLong() + 1 }.orElse(0L)
                return sendUpload(job, session, size, received.coerceIn(0, size - 1))
            }
            else -> job.driveSession = null
        }
    }
    val metadata = "{\"name\":\"${driveFileName(job).jsonEscape()}\",\"parents\":[\"$folderId\"],\"mimeType\":\"video/mp4\"}"
    val start = driveSend(job.owner) { token ->
        HttpRequest.newBuilder(URI("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&supportsAllDrives=true&fields=id,webViewLink"))
            .header("Authorization", "Bearer $token").header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Type", "video/mp4").header("X-Upload-Content-Length", size.toString())
            .POST(HttpRequest.BodyPublishers.ofString(metadata)).build()
    }
    if (start.statusCode() == 404) throw DriveException("The Google Drive folder was not found, or the connected account cannot open it.")
    if (start.statusCode() != 200) throw driveFailure(start, "Starting the Google Drive upload")
    val session = start.headers().firstValue("Location").orElse(null)
        ?: throw DriveException("Google Drive did not return an upload address.", retryable = true)
    job.driveSession = session
    persistJob(job)
    return sendUpload(job, session, size, 0)
}

private fun sendUpload(job: RenderJob, session: String, size: Long, offset: Long): String? {
    var reported = -1
    val body = HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream {
        ProgressInputStream(Files.newInputStream(job.output).apply { skipNBytes(offset) }, offset) { sent ->
            val percent = (sent * 100 / size).toInt().coerceAtMost(99)
            if (percent != reported) {
                reported = percent
                job.drivePercent = percent
                job.driveMessage = "Uploading to Google Drive · $percent%"
            }
        }
    }, size - offset)
    val request = HttpRequest.newBuilder(URI(session)).header("Content-Type", "video/mp4")
    if (offset > 0) request.header("Content-Range", "bytes $offset-${size - 1}/$size")
    val upload = httpClient.send(request.PUT(body).build(), HttpResponse.BodyHandlers.ofString())
    if (upload.statusCode() == 404 || upload.statusCode() == 410) {
        job.driveSession = null
        throw DriveException("The Google Drive upload session expired.", retryable = true)
    }
    if (upload.statusCode() == 308) throw DriveException("Google Drive received only part of the video.", retryable = true)
    if (upload.statusCode() !in 200..201) throw driveFailure(upload, "Uploading to Google Drive")
    return finishUpload(job, upload)
}

private fun finishUpload(job: RenderJob, response: HttpResponse<String>): String? {
    job.driveSession = null
    return jsonString(response.body(), "webViewLink")?.takeIf { it.startsWith("https://") }
}

private fun driveFileName(job: RenderJob) = job.output.fileName.toString()

/** Sends a Drive API request, refreshing the access token once if Google no longer accepts it. */
private fun driveSend(user: SignedInUser, request: (String) -> HttpRequest): HttpResponse<String> {
    for (attempt in 1..2) {
        val response = try {
            httpClient.send(request(driveAccessToken(user)), HttpResponse.BodyHandlers.ofString())
        } catch (error: IOException) {
            throw DriveException("Google Drive could not be reached (${error.message ?: error::class.simpleName}).", retryable = true)
        }
        if (response.statusCode() != 401 || attempt == 2) return response
        driveAccess.remove(user.id)
    }
    error("unreachable")
}

private fun driveFailure(response: HttpResponse<String>, action: String): DriveException {
    val status = response.statusCode()
    val detail = jsonString(response.body(), "message")?.takeIf { it.isNotBlank() }
    val rateLimited = jsonString(response.body(), "reason").orEmpty().contains("rateLimitExceeded", ignoreCase = true)
    return DriveException("$action failed (HTTP $status)${detail?.let { ": $it" }.orEmpty()}", retryable = status == 429 || status >= 500 || rateLimited)
}

private fun driveAccessToken(user: SignedInUser): String {
    driveAccess[user.id]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }?.let { return it.token }
    val connection = driveConnection(user) ?: throw DriveException("Google Drive is not connected. Connect it at the top of the page, then retry.")
    val response = try {
        googleTokenRequest(mapOf("refresh_token" to connection.refreshToken, "grant_type" to "refresh_token"))
    } catch (error: IOException) {
        throw DriveException("Google could not be reached to refresh Drive access (${error.message ?: error::class.simpleName}).", retryable = true)
    }
    if (jsonString(response.body(), "error") == "invalid_grant") {
        forgetDriveConnection(user)
        throw DriveException("Google Drive access expired or was revoked. Connect Google Drive again, then retry.")
    }
    if (response.statusCode() != 200) throw DriveException("Google Drive access could not be refreshed (HTTP ${response.statusCode()}).", retryable = response.statusCode() >= 500)
    val token = jsonString(response.body(), "access_token") ?: throw DriveException("Google did not return a Drive access token.")
    driveAccess[user.id] = DriveAccess(token, expiresAt(response.body()))
    return token
}

private fun googleTokenRequest(fields: Map<String, String>): HttpResponse<String> {
    val form = (fields + mapOf("client_id" to googleClientId().orEmpty(), "client_secret" to googleClientSecret().orEmpty()))
        .entries.joinToString("&") { "${it.key}=${urlEncode(it.value)}" }
    return httpClient.send(HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
        .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString())
}

private fun expiresAt(tokenResponse: String) = System.currentTimeMillis() + (jsonNumber(tokenResponse, "expires_in") ?: 3600L) * 1000

private fun driveConnectionFile(user: SignedInUser): Path =
    jamalDirectory().resolve("drive").also(Files::createDirectories).resolve("${sha256("drive|${user.id}").take(32)}.properties")

private fun driveConnection(user: SignedInUser): DriveConnection? {
    val file = driveConnectionFile(user)
    if (!Files.isRegularFile(file)) return null
    return try {
        val values = Properties().also { Files.newInputStream(file).use(it::load) }
        DriveConnection(values.getProperty("email").orEmpty(), decryptSecret(values.getProperty("refreshToken").orEmpty()))
    } catch (error: Exception) {
        ActivityLog.warn("drive.connection_unreadable", "Stored Google Drive connection could not be decrypted; the user must connect Drive again", LogContext(details = mapOf("owner" to user.email, "exception" to error::class.simpleName)))
        null
    }
}

private fun saveDriveConnection(user: SignedInUser, connection: DriveConnection) {
    val values = Properties()
    values["email"] = connection.email
    values["refreshToken"] = encryptSecret(connection.refreshToken)
    values["connectedAt"] = System.currentTimeMillis().toString()
    val target = driveConnectionFile(user)
    val temporary = Files.createTempFile(target.parent, "drive-", ".tmp")
    Files.newOutputStream(temporary).use { values.store(it, "Jamal Google Drive connection; the refresh token is AES-GCM encrypted") }
    try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: Exception) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
}

private fun forgetDriveConnection(user: SignedInUser) {
    driveAccess.remove(user.id)
    Files.deleteIfExists(driveConnectionFile(user))
}

private fun driveTokenKey() = SecretKeySpec(
    MessageDigest.getInstance("SHA-256").digest("jamal-drive-token-v1|${sessionSecret()}".toByteArray(StandardCharsets.UTF_8)), "AES",
)

private fun encryptSecret(value: String): String {
    val iv = ByteArray(12).also(secureRandom::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, driveTokenKey(), GCMParameterSpec(128, iv)) }
    return Base64.getEncoder().encodeToString(iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
}

private fun decryptSecret(value: String): String {
    val bytes = Base64.getDecoder().decode(value)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, driveTokenKey(), GCMParameterSpec(128, bytes, 0, 12)) }
    return String(cipher.doFinal(bytes, 12, bytes.size - 12), StandardCharsets.UTF_8)
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private class ProgressInputStream(source: InputStream, private var sent: Long, private val progress: (Long) -> Unit) : FilterInputStream(source) {
    override fun read(): Int = super.read().also { if (it >= 0) progress(++sent) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) { sent += it; progress(sent) } }
}
