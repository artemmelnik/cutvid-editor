package com.jamal.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Collections
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private const val PORT = 8787
private const val DEFAULT_PARALLEL_RENDERS = 5
private const val MAX_QUICK_RENDERS = 5
private const val SESSION_COOKIE = "jamal_session"
private const val SESSION_MAX_AGE_SECONDS = 60L * 60 * 24 * 30
internal val supportedExtensions = setOf("mov", "mp4", "m4v", "avi", "mkv", "webm")
internal val jobs = ConcurrentHashMap<String, RenderJob>()
private val oauthStates = ConcurrentHashMap<String, OAuthState>()
internal val httpClient = HttpClient.newHttpClient()
internal val secureRandom = SecureRandom()
private val renderWorkerNumber = AtomicInteger()
// Lazy because `.env` values are only readable once `dotEnvConfig` below has been initialised.
private val parallelRenders by lazy { configuredParallelRenders() ?: DEFAULT_PARALLEL_RENDERS }
private val renderQueue by lazy {
    Executors.newFixedThreadPool(parallelRenders) { task ->
        Thread(task, "jamal-render-${renderWorkerNumber.incrementAndGet()}").apply { isDaemon = true }
    }
}
private val deviceFingerprint by lazy(::localDeviceFingerprint)
private val serverStartedAt = System.currentTimeMillis()
private val dotEnvConfig by lazy(::loadDotEnv)
private fun configuration(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() } ?: dotEnvConfig.values[name]?.takeIf { it.isNotBlank() }
private fun publicBaseUrl(): String = configuration("JAMAL_PUBLIC_URL")?.trimEnd('/') ?: "http://127.0.0.1:$PORT"
/** How many videos render at the same time; the rest wait in the queue. */
private fun configuredParallelRenders() = configuration("JAMAL_PARALLEL_RENDERS")?.trim()?.toIntOrNull()?.takeIf { it >= 1 }

internal data class UploadedFile(val name: String, val path: Path)
internal data class SignedInUser(val id: String, val email: String)
internal data class RenderJob(
    val id: String,
    val owner: SignedInUser,
    val reference: UploadedFile,
    val background: UploadedFile,
    val settings: RenderSettings,
    val output: Path,
    val queuedAt: Long = System.currentTimeMillis(),
    @Volatile var startedAt: Long? = null,
    @Volatile var finishedAt: Long? = null,
    @Volatile var message: String = "Waiting for an available render worker",
    @Volatile var percent: Int? = 0,
    @Volatile var state: JobState = JobState.QUEUED,
    @Volatile var lastLoggedPercent: Int = -1,
    @Volatile var lastLoggedMessage: String = "",
    @Volatile var lastEngineError: String? = null,
    val projectId: String? = null,
    val projectName: String? = null,
    @Volatile var driveFolderId: String? = null,
    @Volatile var driveStatus: DriveStatus? = null,
    @Volatile var driveMessage: String = "",
    @Volatile var drivePercent: Int? = null,
    @Volatile var driveUrl: String? = null,
    /** Resumable-upload address of an unfinished Google Drive upload, kept so it can continue after a restart. */
    @Volatile var driveSession: String? = null,
) {
    /** Still worth polling: rendering, or waiting for its Google Drive upload to finish. */
    val running get() = state == JobState.QUEUED || state == JobState.RENDERING ||
        driveStatus == DriveStatus.PENDING || driveStatus == DriveStatus.UPLOADING
}

internal enum class JobState { QUEUED, RENDERING, COMPLETE, FAILED }

private data class OAuthState(val createdAt: Long, val driveUserId: String? = null)

internal data class RenderSettings(
    val outlinePixels: String,
    val scalePercent: String,
    val horizontalPercent: String,
    val bottomPercent: String,
    val outlineColor: String,
)

fun main() {
    loadPersistedJobs()
    loadPersistedProjects()
    val bindHost = configuration("JAMAL_BIND_HOST") ?: "127.0.0.1"
    val server = HttpServer.create(InetSocketAddress(bindHost, PORT), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        val noisyPoll = path.matches(Regex("/api/renders/[^/]+")) || path in setOf("/api/admin/overview", "/api/admin/events") ||
            (exchange.requestMethod == "GET" && path in setOf("/api/renders", "/api/projects"))
        if (!noisyPoll) ActivityLog.debug(
            "http.request", "${exchange.requestMethod} $path",
            requestContext(exchange, mapOf(
                "userAgent" to exchange.requestHeaders.getFirst("User-Agent"),
                "contentLength" to exchange.requestHeaders.getFirst("Content-Length"),
            )),
        )
        try {
            when {
                exchange.requestMethod == "GET" && path == "/" -> respondHtml(exchange)
                exchange.requestMethod == "GET" && path == "/admin" -> respondHtml(exchange, ADMIN_PAGE)
                exchange.requestMethod == "GET" && path == "/auth/google" -> beginGoogleLogin(exchange)
                exchange.requestMethod == "GET" && path == "/auth/google/callback" -> completeGoogleLogin(exchange)
                exchange.requestMethod == "GET" && path == "/auth/google/drive" -> beginDriveConnect(exchange)
                exchange.requestMethod == "POST" && path == "/auth/google/drive/disconnect" -> disconnectDrive(exchange)
                exchange.requestMethod == "POST" && path == "/auth/local" -> beginLocalSession(exchange)
                exchange.requestMethod == "POST" && path == "/auth/logout" -> logout(exchange)
                exchange.requestMethod == "GET" && path == "/api/me" -> currentUserStatus(exchange)
                exchange.requestMethod == "POST" && path == "/api/renders" -> createRenders(exchange)
                exchange.requestMethod == "GET" && path == "/api/renders" -> listRenders(exchange)
                exchange.requestMethod == "GET" && path == "/api/projects" -> listProjects(exchange)
                exchange.requestMethod == "POST" && path == "/api/projects" -> createProject(exchange)
                path.matches(Regex("/api/projects/[^/]+(/[^/]+)?")) -> routeProject(exchange)
                exchange.requestMethod == "POST" && path.matches(Regex("/api/renders/[^/]+/drive")) -> retryDriveUpload(exchange)
                exchange.requestMethod == "GET" && path == "/api/admin/overview" -> adminOverview(exchange)
                exchange.requestMethod == "GET" && path == "/api/admin/events" -> adminEvents(exchange)
                exchange.requestMethod == "GET" && path == "/api/admin/logs/download" -> downloadActivityLog(exchange)
                exchange.requestMethod == "GET" && path.matches(Regex("/api/renders/[^/]+")) -> renderStatus(exchange)
                exchange.requestMethod == "GET" && path.matches(Regex("/api/renders/[^/]+/download")) -> downloadRender(exchange)
                else -> {
                    ActivityLog.warn("http.not_found", "${exchange.requestMethod} $path", requestContext(exchange))
                    respondText(exchange, 404, "Not found")
                }
            }
        } catch (error: Exception) {
            ActivityLog.error("http.unhandled_error", "Unhandled request error for ${exchange.requestMethod} $path", error, requestContext(exchange))
            try { respondJson(exchange, 500, "{\"error\":\"Internal server error. Check the admin activity log.\"}") } catch (_: Exception) { }
        }
    }
    server.executor = Executors.newCachedThreadPool()
    server.start()
    println("Jamal web app is ready at ${publicBaseUrl()} (bound to $bindHost:$PORT)")
    println("Google sign-in configured: ${googleOauthConfigured()}")
    println("Admin dashboard: http://127.0.0.1:$PORT/admin")
    dotEnvConfig.path?.let { println("Configuration loaded from $it") }
    if (configuration("JAMAL_PARALLEL_RENDERS") != null && configuredParallelRenders() == null) {
        ActivityLog.warn("server.config_invalid", "JAMAL_PARALLEL_RENDERS must be a whole number of at least 1; using $DEFAULT_PARALLEL_RENDERS")
    }
    println("Renders at the same time: $parallelRenders (set JAMAL_PARALLEL_RENDERS to change)")
    ActivityLog.info(
        "server.started", "Jamal web app started",
        LogContext(device = deviceFingerprint.take(12), details = mapOf(
            "port" to PORT,
            "googleOAuthConfigured" to googleOauthConfigured(),
            "parallelRenders" to parallelRenders,
            "envFile" to dotEnvConfig.path?.toString(),
        )),
    )
    println("Press Ctrl+C to stop it.")
}

private fun createRenders(exchange: HttpExchange) {
    try {
        val user = requireSignedInUser(exchange) ?: return
        val fields = parseMultipart(exchange)
        val references = fields.files["reference"].orEmpty()
        val backgrounds = fields.files["background"].orEmpty()
        ActivityLog.info(
            "upload.received", "Video upload received",
            requestContext(exchange, mapOf(
                "referenceCount" to references.size,
                "backgroundCount" to backgrounds.size,
                "referenceFiles" to references.joinToString(" | ") { "${it.name} (${Files.size(it.path)} bytes)" },
                "backgroundFiles" to backgrounds.joinToString(" | ") { "${it.name} (${Files.size(it.path)} bytes)" },
            )),
        )
        require(references.isNotEmpty()) { "Choose at least one reference video." }
        require(backgrounds.isNotEmpty()) { "Choose at least one background video." }
        references.forEach { require(it.name.extension() in supportedExtensions) { "${it.name} is not a supported reference video." } }
        backgrounds.forEach { require(it.name.extension() in supportedExtensions) { "${it.name} is not a supported background video." } }

        val pairs = pairVideos(references, backgrounds)
        require(pairs.size <= MAX_QUICK_RENDERS) {
            "Quick render accepts up to $MAX_QUICK_RENDERS video compositions per submission."
        }

        val settings = RenderSettings(
            outlinePixels = fields.values["outlinePixels"].orDefault("12"),
            scalePercent = fields.values["scalePercent"].orDefault("46"),
            horizontalPercent = fields.values["horizontalPercent"].orDefault("2"),
            bottomPercent = fields.values["bottomPercent"].orDefault("0"),
            outlineColor = fields.values["outlineColor"].orDefault("#FFFFFF"),
        )
        val created = pairs.map { (reference, background) -> queueRender(exchange, user, reference, background, settings) }
        ActivityLog.info("batch.accepted", "Parallel render batch accepted", requestContext(exchange, mapOf("jobs" to created.size, "parallelLimit" to parallelRenders, "owner" to user.email)))
        respondJson(exchange, 202, "{\"jobs\":[${created.joinToString(",") { renderJson(it) }}]}")
    } catch (error: Exception) {
        ActivityLog.error("batch.rejected", error.message ?: "Invalid render request", error, requestContext(exchange))
        respondJson(exchange, 400, "{\"error\":\"${(error.message ?: "Invalid render request").jsonEscape()}\"}")
    }
}

internal fun queueRender(exchange: HttpExchange, owner: SignedInUser, reference: UploadedFile, background: UploadedFile, settings: RenderSettings, project: Project? = null): RenderJob {
    val jobId = UUID.randomUUID().toString()
    val output = exportsDirectory().resolve("jamal-${System.currentTimeMillis()}-${jobId.take(8)}.mp4")
    val job = RenderJob(jobId, owner, reference, background, settings, output, projectId = project?.id, projectName = project?.name, driveFolderId = project?.settings?.driveFolderId)
    if (job.driveFolderId != null) {
        job.driveStatus = DriveStatus.PENDING
        job.driveMessage = "Uploads to Google Drive after rendering"
    }
    jobs[jobId] = job
    persistJob(job)
    ActivityLog.info(
        "render.queued", "Render added to parallel pipeline",
        requestContext(exchange, mapOf(
            "reference" to reference.name,
            "background" to background.name,
            "output" to output.fileName.toString(),
            "owner" to owner.email,
            "project" to project?.name,
            "outlinePixels" to settings.outlinePixels,
            "scalePercent" to settings.scalePercent,
            "horizontalPercent" to settings.horizontalPercent,
            "bottomPercent" to settings.bottomPercent,
            "outlineColor" to settings.outlineColor,
        )).copy(jobId = jobId),
    )
    renderQueue.submit { performRender(job) }
    return job
}

private fun pairVideos(references: List<UploadedFile>, backgrounds: List<UploadedFile>): List<Pair<UploadedFile, UploadedFile>> = when {
    references.size == backgrounds.size -> references.zip(backgrounds)
    references.size == 1 -> backgrounds.map { references.first() to it }
    backgrounds.size == 1 -> references.map { it to backgrounds.first() }
    else -> error("Choose equal numbers of reference and background videos, or choose one video on either side to reuse it for every output.")
}

private fun renderStatus(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    val id = exchange.requestURI.path.substringAfterLast('/')
    val job = jobs[id] ?: return respondText(exchange, 404, "Unknown render")
    if (job.owner.id != user.id) return respondText(exchange, 404, "Unknown render")
    respondJson(exchange, 200, renderJson(job, queuePositions()[job.id] ?: 0))
}

private fun listRenders(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    val positions = queuePositions()
    val jobJson = jobs.values.filter { it.owner.id == user.id }
        .sortedByDescending { it.queuedAt }
        .joinToString(",") { renderJson(it, positions[it.id] ?: 0) }
    respondJson(exchange, 200, "{\"jobs\":[$jobJson]}")
}

private fun queuePositions(): Map<String, Int> = jobs.values.filter { it.state == JobState.QUEUED }
    .sortedWith(compareBy<RenderJob> { it.queuedAt }.thenBy { it.id })
    .mapIndexed { index, job -> job.id to index + 1 }.toMap()

internal fun renderJson(job: RenderJob, queuePosition: Int = 0): String {
    val now = job.finishedAt ?: System.currentTimeMillis()
    val elapsed = job.startedAt?.let { now - it } ?: 0
    val eta = job.percent?.takeIf { job.state == JobState.RENDERING && it in 1..99 }?.let { percent -> (elapsed * (100 - percent) / percent).coerceAtLeast(0) }
    val settings = with(job.settings) { """{"outlinePixels":"${outlinePixels.jsonEscape()}","scalePercent":"${scalePercent.jsonEscape()}","horizontalPercent":"${horizontalPercent.jsonEscape()}","bottomPercent":"${bottomPercent.jsonEscape()}","outlineColor":"${outlineColor.jsonEscape()}"}""" }
    val drive = job.driveStatus?.let { """{"status":"${it.name.lowercase()}","message":"${job.driveMessage.jsonEscape()}","percent":${job.drivePercent ?: "null"},"url":${job.driveUrl.jsonOrNull()}}""" } ?: "null"
    return """{"id":"${job.id}","reference":"${job.reference.name.jsonEscape()}","background":"${job.background.name.jsonEscape()}","message":"${job.message.jsonEscape()}","percent":${job.percent ?: "null"},"state":"${job.state.name.lowercase()}","queuePosition":$queuePosition,"queuedAt":${job.queuedAt},"startedAt":${job.startedAt ?: "null"},"finishedAt":${job.finishedAt ?: "null"},"elapsedMs":$elapsed,"etaMs":${eta ?: "null"},"running":${job.running},"failed":${job.state == JobState.FAILED},"ready":${Files.isRegularFile(job.output)},"projectId":${job.projectId.jsonOrNull()},"projectName":${job.projectName.jsonOrNull()},"settings":$settings,"drive":$drive}"""
}

private fun downloadRender(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    val id = exchange.requestURI.path.removeSuffix("/download").substringAfterLast('/')
    val job = jobs[id] ?: run {
        ActivityLog.warn("download.unknown", "Download requested for unknown render", requestContext(exchange).copy(jobId = id))
        return respondText(exchange, 404, "Unknown render")
    }
    if (job.owner.id != user.id) return respondText(exchange, 404, "Unknown render")
    if (!Files.isRegularFile(job.output)) {
        ActivityLog.warn("download.not_ready", "Download requested before render was ready", requestContext(exchange).copy(jobId = id))
        return respondText(exchange, 409, "Render is not ready")
    }
    ActivityLog.info("download.started", "Completed video download started", requestContext(exchange, mapOf("bytes" to Files.size(job.output), "file" to job.output.fileName.toString())).copy(jobId = id))
    exchange.responseHeaders.add("Content-Type", "video/mp4")
    exchange.responseHeaders.add("Content-Disposition", "attachment; filename=jamal-${job.id.take(8)}.mp4")
    exchange.sendResponseHeaders(200, Files.size(job.output))
    Files.newInputStream(job.output).use { it.copyTo(exchange.responseBody) }
    exchange.close()
}

private fun performRender(job: RenderJob) {
    job.state = JobState.RENDERING
    job.startedAt = System.currentTimeMillis()
    job.message = "Starting render engine"
    persistJob(job)
    ActivityLog.info("render.started", "Render engine starting", jobContext(job))
    try {
        val engine = resolveEngineExecutable() ?: error("Render engine was not found. Build render-engine first.")
        val jobFile = writeRenderJob(job)
        ProcessBuilder(engine.absolutePath, jobFile.toString()).apply {
            redirectErrorStream(true)
            environment()["KMP_DUPLICATE_LIB_OK"] = "TRUE"
        }.start().also { process ->
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { event ->
                ActivityLog.debug("engine.output", event.take(8_000), jobContext(job))
                updateProgress(job, event)
            } }
            val exit = process.waitFor()
            if (exit != 0) error(job.lastEngineError ?: "Render engine stopped with code $exit.")
        }
        job.message = "Render complete"
        job.percent = 100
        job.state = JobState.COMPLETE
        persistJob(job)
        ActivityLog.info("render.completed", "Render completed", jobContext(job, mapOf(
            "durationMs" to ((System.currentTimeMillis()) - (job.startedAt ?: System.currentTimeMillis())),
            "outputBytes" to Files.size(job.output),
            "output" to job.output.toString(),
        )))
    } catch (error: Exception) {
        job.message = error.message ?: "Render failed"
        job.state = JobState.FAILED
        job.driveStatus = null
        persistJob(job)
        ActivityLog.error("render.failed", job.message, error, jobContext(job, mapOf(
            "durationMs" to (System.currentTimeMillis() - (job.startedAt ?: System.currentTimeMillis())),
        )))
    } finally {
        job.finishedAt = System.currentTimeMillis()
        persistJob(job)
    }
    // Uploads run on their own workers so a slow upload never holds a render slot.
    if (job.state == JobState.COMPLETE && job.driveStatus == DriveStatus.PENDING) queueDriveUpload(job)
}

private fun updateProgress(job: RenderJob, event: String) {
    val type = Regex("\\\"type\\\"\\s*:\\s*\\\"([^\\\"]*)").find(event)?.groupValues?.get(1)
    val message = Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]*)").find(event)?.groupValues?.get(1)
    if (type == "error") {
        job.lastEngineError = message ?: "Render engine failed"
        job.message = job.lastEngineError!!
    } else {
        message?.let { job.message = it }
        Regex("\\\"percent\\\"\\s*:\\s*(\\d+)").find(event)?.groupValues?.get(1)?.toIntOrNull()?.let {
            job.percent = it.coerceIn(0, 100)
        }
    }
    val percent = job.percent ?: 0
    if (job.message != job.lastLoggedMessage || percent / 5 > job.lastLoggedPercent / 5) {
        job.lastLoggedMessage = job.message
        job.lastLoggedPercent = percent
        ActivityLog.info("render.progress", job.message, jobContext(job, mapOf("percent" to percent)))
        persistJob(job)
    }
}

private fun writeRenderJob(job: RenderJob): Path {
    val file = Files.createTempFile(jobsDirectory(), "render-", ".render-job.json")
    val model = File(System.getProperty("user.dir"), "models/modnet_photographic.onnx").takeIf(File::isFile)
    val json = """
        {"version":1,"referenceVideo":"${job.reference.path.toString().jsonEscape()}","backgroundVideo":"${job.background.path.toString().jsonEscape()}","outputVideo":"${job.output.toString().jsonEscape()}","modelPath":"${model?.absolutePath?.jsonEscape().orEmpty()}","outlinePixels":"${job.settings.outlinePixels.jsonEscape()}","scalePercent":"${job.settings.scalePercent.jsonEscape()}","horizontalPercent":"${job.settings.horizontalPercent.jsonEscape()}","bottomPercent":"${job.settings.bottomPercent.jsonEscape()}","outlineColor":"${job.settings.outlineColor.jsonEscape()}"}
    """.trimIndent()
    Files.writeString(file, json)
    return file
}

internal fun googleClientId(): String? = System.getenv("GOOGLE_OAUTH_CLIENT_ID")
    ?: System.getProperty("google.oauth.client.id") ?: dotEnvConfig.values["GOOGLE_OAUTH_CLIENT_ID"]
internal fun googleClientSecret(): String? = System.getenv("GOOGLE_OAUTH_CLIENT_SECRET")
    ?: System.getProperty("google.oauth.client.secret") ?: dotEnvConfig.values["GOOGLE_OAUTH_CLIENT_SECRET"]
internal fun googleOauthConfigured() = !googleClientId().isNullOrBlank() && !googleClientSecret().isNullOrBlank()
internal fun googleRedirectUri() = "${publicBaseUrl()}/auth/google/callback"

private fun beginGoogleLogin(exchange: HttpExchange) {
    val clientId = googleClientId() ?: return respondText(exchange, 503, "Google sign-in is not configured. Set GOOGLE_OAUTH_CLIENT_ID and GOOGLE_OAUTH_CLIENT_SECRET in .env.")
    val query = mapOf(
        "client_id" to clientId, "redirect_uri" to googleRedirectUri(), "response_type" to "code",
        "scope" to "openid email", "state" to newOAuthState(), "prompt" to "select_account",
    ).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}" }
    redirect(exchange, "https://accounts.google.com/o/oauth2/v2/auth?$query")
}

/** Google sign-in and the later Google Drive consent share one registered callback; the state tells them apart. */
internal fun newOAuthState(driveUserId: String? = null): String {
    val stateBytes = ByteArray(32).also(secureRandom::nextBytes)
    val state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes)
    oauthStates.entries.removeIf { it.value.createdAt < System.currentTimeMillis() - 10 * 60_000 }
    oauthStates[state] = OAuthState(System.currentTimeMillis(), driveUserId)
    return state
}

private fun completeGoogleLogin(exchange: HttpExchange) {
    try {
        val query = queryValues(exchange.requestURI.rawQuery.orEmpty())
        val state = query["state"]
        val pending = state?.let { oauthStates.remove(it) }
        pending?.driveUserId?.let { return completeDriveConnect(exchange, query, it) }
        val code = query["code"] ?: error("Google did not return an authorization code.")
        require(state != null) { "Google did not return a sign-in state." }
        require(pending != null) { "Sign-in request expired. Please try again." }
        val form = mapOf("code" to code, "client_id" to googleClientId().orEmpty(), "client_secret" to googleClientSecret().orEmpty(), "redirect_uri" to googleRedirectUri(), "grant_type" to "authorization_code")
            .entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, StandardCharsets.UTF_8)}" }
        val tokenResponse = httpClient.send(HttpRequest.newBuilder(URI("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString())
        require(tokenResponse.statusCode() == 200) { "Google sign-in could not be completed." }
        val accessToken = jsonString(tokenResponse.body(), "access_token") ?: error("Google did not return an access token.")
        val profileResponse = httpClient.send(HttpRequest.newBuilder(URI("https://openidconnect.googleapis.com/v1/userinfo"))
            .header("Authorization", "Bearer $accessToken").GET().build(), HttpResponse.BodyHandlers.ofString())
        require(profileResponse.statusCode() == 200) { "Google account details could not be read." }
        val user = SignedInUser(jsonString(profileResponse.body(), "sub") ?: error("Google account id is missing."), jsonString(profileResponse.body(), "email") ?: error("Google email is missing."))
        require(jsonBoolean(profileResponse.body(), "email_verified")) { "Your Google email address must be verified." }
        setSessionCookie(exchange, user)
        ActivityLog.info("auth.signed_in", "Google sign-in completed", requestContext(exchange, mapOf("email" to user.email)))
        redirect(exchange, "/")
    } catch (error: Exception) {
        ActivityLog.warn("auth.sign_in_failed", error.message ?: "Google sign-in failed", requestContext(exchange))
        respondText(exchange, 400, error.message ?: "Google sign-in failed")
    }
}

private fun currentUserStatus(exchange: HttpExchange) {
    val user = currentUser(exchange)
    respondJson(exchange, 200, if (user == null) "{\"signedIn\":false,\"googleConfigured\":${googleOauthConfigured()},\"localModeAvailable\":${!googleOauthConfigured()}}" else "{\"signedIn\":true,\"email\":\"${user.email.jsonEscape()}\",\"parallelRenders\":$parallelRenders,\"drive\":${driveStatusJson(user)}}")
}

private fun beginLocalSession(exchange: HttpExchange) {
    if (googleOauthConfigured()) return respondJson(exchange, 409, "{\"error\":\"Google sign-in is enabled for this server.\"}")
    val localId = "local-" + UUID.randomUUID().toString()
    val user = SignedInUser(localId, "Local user")
    setSessionCookie(exchange, user)
    ActivityLog.info("auth.local_session", "Local upload session started", requestContext(exchange))
    respondJson(exchange, 200, "{\"signedIn\":true,\"email\":\"${user.email}\"}")
}

private fun logout(exchange: HttpExchange) {
    exchange.responseHeaders.add("Set-Cookie", "$SESSION_COOKIE=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0")
    respondJson(exchange, 200, "{\"signedOut\":true}")
}

internal fun requireSignedInUser(exchange: HttpExchange): SignedInUser? = currentUser(exchange) ?: run {
    respondJson(exchange, 401, "{\"error\":\"Sign in with Google to continue.\"}")
    null
}

internal fun currentUser(exchange: HttpExchange): SignedInUser? {
    val value = exchange.requestHeaders.getFirst("Cookie")?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$SESSION_COOKIE=") }?.substringAfter('=') ?: return null
    val parts = value.split('.')
    if (parts.size != 2) return null
    val expected = hmac(parts[0])
    if (!MessageDigest.isEqual(expected.toByteArray(StandardCharsets.UTF_8), parts[1].toByteArray(StandardCharsets.UTF_8))) return null
    val payload = try { String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8).split('|') } catch (_: Exception) { return null }
    if (payload.size != 3 || payload[2].toLongOrNull()?.let { it > System.currentTimeMillis() } != true) return null
    return SignedInUser(payload[0], payload[1])
}

private fun setSessionCookie(exchange: HttpExchange, user: SignedInUser) {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("${user.id}|${user.email}|${System.currentTimeMillis() + SESSION_MAX_AGE_SECONDS * 1000}".toByteArray(StandardCharsets.UTF_8))
    exchange.responseHeaders.add("Set-Cookie", "$SESSION_COOKIE=$payload.${hmac(payload)}; Path=/; HttpOnly; SameSite=Lax; Max-Age=$SESSION_MAX_AGE_SECONDS")
}

private fun hmac(value: String): String = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(sessionSecret().toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
    doFinal(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
internal fun sessionSecret(): String = System.getenv("JAMAL_SESSION_SECRET") ?: dotEnvConfig.values["JAMAL_SESSION_SECRET"] ?: run {
    val path = jamalDirectory().resolve("session-secret")
    if (!Files.exists(path)) Files.writeString(path, UUID.randomUUID().toString())
    Files.readString(path).trim()
}
internal fun jsonString(json: String, key: String) = Regex("\\\"$key\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").find(json)?.groupValues?.get(1)?.let(::unescapeJsonString)
internal fun jsonBoolean(json: String, key: String) = Regex("\\\"$key\\\"\\s*:\\s*true").containsMatchIn(json)
internal fun jsonNumber(json: String, key: String) = Regex("\\\"$key\\\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()
private fun unescapeJsonString(value: String) = Regex("\\\\(u[0-9a-fA-F]{4}|.)").replace(value) { match ->
    val escape = match.groupValues[1]
    when (escape[0]) {
        'u' -> escape.substring(1).toInt(16).toChar().toString()
        'n' -> "\n"
        'r' -> "\r"
        't' -> "\t"
        'b' -> "\b"
        'f' -> "\u000C"
        else -> escape
    }
}
internal fun queryValues(query: String) = query.split('&').mapNotNull { part -> part.substringBefore('=').takeIf { it.isNotEmpty() }?.let { key -> key to java.net.URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8) } }.toMap()

private data class DotEnvConfig(val path: Path?, val values: Map<String, String>)

private fun loadDotEnv(): DotEnvConfig {
    val explicit = System.getenv("JAMAL_ENV_FILE")?.takeIf { it.isNotBlank() }?.let(Path::of)
    val discovered = explicit ?: generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .take(5)
        .map { it.toPath().resolve(".env") }
        .firstOrNull(Files::isRegularFile)
    if (discovered == null || !Files.isRegularFile(discovered)) return DotEnvConfig(null, emptyMap())

    val values = linkedMapOf<String, String>()
    Files.readAllLines(discovered, StandardCharsets.UTF_8).forEach { rawLine ->
        val line = rawLine.trim().removePrefix("export ").trim()
        if (line.isEmpty() || line.startsWith('#')) return@forEach
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator).trim()
        if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return@forEach
        val rawValue = line.substring(separator + 1).trim()
        val value = when {
            rawValue.length >= 2 && rawValue.startsWith('"') && rawValue.endsWith('"') -> rawValue.substring(1, rawValue.length - 1)
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
            rawValue.length >= 2 && rawValue.startsWith('\'') && rawValue.endsWith('\'') -> rawValue.substring(1, rawValue.length - 1)
            else -> rawValue
        }
        values[key] = value
    }
    return DotEnvConfig(discovered.toAbsolutePath().normalize(), values)
}

internal fun requestContext(exchange: HttpExchange, details: Map<String, Any?> = emptyMap()) = LogContext(
    device = deviceFingerprint.take(12),
    remote = exchange.remoteAddress.address.hostAddress,
    details = details,
)

internal fun jobContext(job: RenderJob, details: Map<String, Any?> = emptyMap()) = LogContext(
    device = deviceFingerprint.take(12),
    jobId = job.id,
    details = mapOf("reference" to job.reference.name, "background" to job.background.name) + details,
)

private fun adminOverview(exchange: HttpExchange) {
    exchange.responseHeaders.add("Cache-Control", "no-store")
    val allJobs = jobs.values.sortedByDescending { it.queuedAt }
    val jobJson = allJobs.joinToString(",") { job ->
        """{"id":"${job.id}","owner":"${job.owner.email.jsonEscape()}","project":${job.projectName.jsonOrNull()},"reference":"${job.reference.name.jsonEscape()}","background":"${job.background.name.jsonEscape()}","state":"${job.state.name.lowercase()}","percent":${job.percent ?: "null"},"message":"${job.message.jsonEscape()}","drive":${job.driveStatus?.let { "\"${it.name.lowercase()}: ${job.driveMessage.jsonEscape()}\"" } ?: "null"},"queuedAt":${job.queuedAt},"startedAt":${job.startedAt ?: "null"},"finishedAt":${job.finishedAt ?: "null"}}"""
    }
    fun count(state: JobState) = allJobs.count { it.state == state }
    respondJson(exchange, 200, """{"startedAt":$serverStartedAt,"uptimeMs":${System.currentTimeMillis() - serverStartedAt},"device":"${deviceFingerprint.take(12)}","counts":{"total":${allJobs.size},"queued":${count(JobState.QUEUED)},"rendering":${count(JobState.RENDERING)},"complete":${count(JobState.COMPLETE)},"failed":${count(JobState.FAILED)}},"eventCount":${ActivityLog.eventCount()},"logFile":"${ActivityLog.currentFile().toString().jsonEscape()}","jobs":[$jobJson]}""")
}

private fun adminEvents(exchange: HttpExchange) {
    exchange.responseHeaders.add("Cache-Control", "no-store")
    val limit = Regex("(?:^|&)limit=(\\d+)").find(exchange.requestURI.rawQuery.orEmpty())
        ?.groupValues?.get(1)?.toIntOrNull() ?: 250
    respondJson(exchange, 200, "{\"events\":${ActivityLog.recentJson(limit)}}")
}

private fun downloadActivityLog(exchange: HttpExchange) {
    val file = ActivityLog.currentFile()
    if (!Files.isRegularFile(file)) return respondText(exchange, 404, "No activity log exists yet")
    ActivityLog.info("admin.log_downloaded", "Administrator downloaded the current activity log", requestContext(exchange, mapOf("bytes" to Files.size(file))))
    exchange.responseHeaders.add("Content-Type", "application/x-ndjson; charset=utf-8")
    exchange.responseHeaders.add("Content-Disposition", "attachment; filename=${file.fileName}")
    exchange.responseHeaders.add("Cache-Control", "no-store")
    exchange.sendResponseHeaders(200, Files.size(file))
    Files.newInputStream(file).use { it.copyTo(exchange.responseBody) }
    exchange.close()
}

private fun localDeviceFingerprint(): String {
    val macs = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).mapNotNull { network ->
            network.hardwareAddress?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02x".format(it) }
        }.distinct().sorted()
    } catch (_: Exception) { emptyList() }
    val source = if (macs.isNotEmpty()) {
        macs.joinToString("|")
    } else {
        val fallback = jamalDirectory().resolve("device-id")
        if (!Files.exists(fallback)) Files.writeString(fallback, UUID.randomUUID().toString())
        Files.readString(fallback).trim()
    }
    return sha256("jamal-device-v1|$source")
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun resolveEngineExecutable(): File? = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
    .take(5).map { File(it, "render-engine/build/jamal-render-engine") }
    .firstOrNull { it.setExecutable(true) && it.canExecute() }

internal fun jamalDirectory(): Path = (configuration("JAMAL_DATA_DIR")?.let(Path::of)
    ?: Path.of(System.getProperty("user.home"), ".jamal")).also(Files::createDirectories)
private fun uploadsDirectory(): Path = jamalDirectory().resolve("uploads").also(Files::createDirectories)
private fun jobsDirectory(): Path = jamalDirectory().resolve("jobs").also(Files::createDirectories)

@Synchronized internal fun persistJob(job: RenderJob) {
    val values = Properties()
    values["id"] = job.id; values["ownerId"] = job.owner.id; values["ownerEmail"] = job.owner.email
    values["referenceName"] = job.reference.name; values["referencePath"] = job.reference.path.toString()
    values["backgroundName"] = job.background.name; values["backgroundPath"] = job.background.path.toString()
    values["output"] = job.output.toString(); values["queuedAt"] = job.queuedAt.toString()
    values["startedAt"] = job.startedAt?.toString().orEmpty(); values["finishedAt"] = job.finishedAt?.toString().orEmpty()
    values["message"] = job.message; values["percent"] = job.percent?.toString().orEmpty(); values["state"] = job.state.name
    values["outlinePixels"] = job.settings.outlinePixels; values["scalePercent"] = job.settings.scalePercent
    values["horizontalPercent"] = job.settings.horizontalPercent; values["bottomPercent"] = job.settings.bottomPercent; values["outlineColor"] = job.settings.outlineColor
    values["projectId"] = job.projectId.orEmpty(); values["projectName"] = job.projectName.orEmpty(); values["driveFolderId"] = job.driveFolderId.orEmpty()
    values["driveStatus"] = job.driveStatus?.name.orEmpty(); values["driveMessage"] = job.driveMessage; values["driveUrl"] = job.driveUrl.orEmpty(); values["driveSession"] = job.driveSession.orEmpty()
    val target = jobsDirectory().resolve("${job.id}.properties")
    val temporary = Files.createTempFile(jobsDirectory(), "${job.id}-", ".tmp")
    Files.newOutputStream(temporary).use { values.store(it, "Jamal render job") }
    try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: Exception) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
}

private fun loadPersistedJobs() {
    if (!Files.isDirectory(jobsDirectory())) return
    Files.list(jobsDirectory()).use { files -> files.filter { it.fileName.toString().endsWith(".properties") }.forEach { path ->
        try {
            val values = Properties().also { Files.newInputStream(path).use(it::load) }
            val id = values.getProperty("id") ?: return@forEach
            val settings = RenderSettings(values.getProperty("outlinePixels", "12"), values.getProperty("scalePercent", "46"), values.getProperty("horizontalPercent", "2"), values.getProperty("bottomPercent", "0"), values.getProperty("outlineColor", "#FFFFFF"))
            val job = RenderJob(id, SignedInUser(values.getProperty("ownerId") ?: return@forEach, values.getProperty("ownerEmail") ?: return@forEach), UploadedFile(values.getProperty("referenceName", "Reference"), Path.of(values.getProperty("referencePath") ?: return@forEach)), UploadedFile(values.getProperty("backgroundName", "Background"), Path.of(values.getProperty("backgroundPath") ?: return@forEach)), settings, Path.of(values.getProperty("output") ?: return@forEach), values.getProperty("queuedAt")?.toLongOrNull() ?: System.currentTimeMillis(), values.getProperty("startedAt")?.toLongOrNull(), values.getProperty("finishedAt")?.toLongOrNull(), values.getProperty("message", "Recovered render"), values.getProperty("percent")?.toIntOrNull(), runCatching { JobState.valueOf(values.getProperty("state", "FAILED")) }.getOrDefault(JobState.FAILED),
                projectId = values.getProperty("projectId")?.ifBlank { null }, projectName = values.getProperty("projectName")?.ifBlank { null }, driveFolderId = values.getProperty("driveFolderId")?.ifBlank { null },
                driveStatus = values.getProperty("driveStatus")?.let { status -> DriveStatus.entries.firstOrNull { it.name == status } }, driveMessage = values.getProperty("driveMessage", ""), driveUrl = values.getProperty("driveUrl")?.ifBlank { null }, driveSession = values.getProperty("driveSession")?.ifBlank { null })
            if (job.state == JobState.QUEUED || job.state == JobState.RENDERING) { job.state = JobState.FAILED; job.message = "Render stopped because the server restarted"; job.driveStatus = null; job.finishedAt = System.currentTimeMillis(); persistJob(job) }
            jobs[id] = job
            // A finished render whose upload was cut off by the restart continues uploading.
            if (job.state == JobState.COMPLETE && (job.driveStatus == DriveStatus.PENDING || job.driveStatus == DriveStatus.UPLOADING)) queueDriveUpload(job)
        } catch (error: Exception) { ActivityLog.warn("jobs.restore_failed", "Could not restore ${path.fileName}: ${error.message}") }
    } }
}

private fun exportsDirectory(): Path {
    val configured = System.getProperty("jamal.exports.dir")?.takeIf { it.isNotBlank() }?.let(Path::of)
    val home = Path.of(System.getProperty("user.home"))
    val oneDrive = home.resolve("Library/CloudStorage/OneDrive-Personal")
    val destination = configured
        ?: oneDrive.takeIf(Files::isDirectory)?.resolve("Jamal Video Compositor/Exports")
        ?: home.resolve(".jamal/exports")
    return destination.toAbsolutePath().also(Files::createDirectories)
}

private data class Multipart(
    val values: MutableMap<String, String> = mutableMapOf(),
    val files: MutableMap<String, MutableList<UploadedFile>> = mutableMapOf(),
)

private fun parseMultipart(exchange: HttpExchange): Multipart {
    val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: error("Missing form data")
    val boundary = Regex("boundary=([^;]+)").find(contentType)?.groupValues?.get(1)?.trim('"') ?: error("Invalid form data")
    val bytes = exchange.requestBody.readBytes()
    val marker = "--$boundary".toByteArray()
    val headerMarker = "\r\n\r\n".toByteArray()
    val result = Multipart()
    var boundaryStart = indexOf(bytes, marker)
    require(boundaryStart >= 0) { "Invalid multipart boundary" }
    while (boundaryStart >= 0) {
        var partStart = boundaryStart + marker.size
        if (partStart + 1 < bytes.size && bytes[partStart] == '-'.code.toByte() && bytes[partStart + 1] == '-'.code.toByte()) break
        if (partStart + 1 < bytes.size && bytes[partStart] == 13.toByte() && bytes[partStart + 1] == 10.toByte()) partStart += 2
        val nextBoundary = indexOf(bytes, marker, partStart)
        if (nextBoundary < 0) break
        var partEnd = nextBoundary
        if (partEnd >= 2 && bytes[partEnd - 2] == 13.toByte() && bytes[partEnd - 1] == 10.toByte()) partEnd -= 2
        val headerEnd = indexOf(bytes, headerMarker, partStart)
        if (headerEnd < 0 || headerEnd >= partEnd) { boundaryStart = nextBoundary; continue }
        val headers = String(bytes, partStart, headerEnd - partStart, StandardCharsets.UTF_8)
        val dataStart = headerEnd + headerMarker.size
        if (dataStart > partEnd) { boundaryStart = nextBoundary; continue }
        val name = Regex("name=\"([^\"]+)\"").find(headers)?.groupValues?.get(1)
        if (name == null) { boundaryStart = nextBoundary; continue }
        val filename = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1)
        if (filename.isNullOrEmpty()) result.values[name] = String(bytes, dataStart, partEnd - dataStart, StandardCharsets.UTF_8)
        else {
            val safeName = File(filename).name
            val destination = Files.createTempFile(uploadsDirectory(), "${Instant.now().epochSecond}-", "-${safeName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            Files.newOutputStream(destination).use { it.write(bytes, dataStart, partEnd - dataStart) }
            result.files.getOrPut(name, ::mutableListOf) += UploadedFile(safeName, destination)
        }
        boundaryStart = nextBoundary
    }
    return result
}

private fun indexOf(source: ByteArray, target: ByteArray, from: Int = 0): Int {
    if (target.isEmpty() || from < 0 || from > source.size - target.size) return -1
    for (i in from..source.size - target.size) if (target.indices.all { source[i + it] == target[it] }) return i
    return -1
}
internal fun String.extension() = substringAfterLast('.', "").lowercase()
private fun String?.orDefault(default: String) = this?.takeIf { it.isNotBlank() } ?: default
internal fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
internal fun String?.jsonOrNull() = this?.let { "\"${it.jsonEscape()}\"" } ?: "null"
internal fun redirect(exchange: HttpExchange, location: String) { exchange.responseHeaders.add("Location", location); exchange.sendResponseHeaders(302, -1); exchange.close() }
private fun respondHtml(exchange: HttpExchange, page: String = WEB_PAGE) { val body = page.toByteArray(); exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8"); exchange.responseHeaders.add("Cache-Control", "no-store"); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
internal fun respondJson(exchange: HttpExchange, status: Int, body: String) { exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8"); respond(exchange, status, body) }
internal fun respondText(exchange: HttpExchange, status: Int, body: String) { exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8"); respond(exchange, status, body) }
private fun respond(exchange: HttpExchange, status: Int, body: String) { val bytes = body.toByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }

private val ADMIN_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Jamal — Admin</title><style>
*{box-sizing:border-box}body{margin:0;background:#0b0d11;color:#eff2f8;font:14px ui-sans-serif,system-ui,-apple-system,sans-serif}.page{max-width:1320px;margin:auto;padding:30px 24px 70px}header{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:22px}h1{font-size:32px;margin:0}h2{font-size:17px;margin:0 0 14px}.muted{color:#8e97a7}.card{background:#15181e;border:1px solid #2b303a;border-radius:14px;padding:18px}.login{max-width:480px;margin:90px auto}.login input{margin:14px 0}.toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.cards{display:grid;grid-template-columns:repeat(6,1fr);gap:12px;margin-bottom:18px}.metric strong{display:block;font-size:27px;margin-top:7px}.metric span{color:#919aaa;font-size:12px;text-transform:uppercase;letter-spacing:.5px}.grid{display:grid;grid-template-columns:minmax(0,1fr);gap:18px}.table-wrap{overflow:auto;max-height:390px}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{text-align:left;padding:10px 11px;border-bottom:1px solid #292e37}th{position:sticky;top:0;background:#15181e;color:#8f98a8;font-size:11px;text-transform:uppercase}td.message{max-width:400px;overflow:hidden;text-overflow:ellipsis}.badge{display:inline-block;border-radius:6px;background:#29303a;padding:4px 7px;font-size:11px;text-transform:uppercase}.info{color:#83b7ff}.warn{color:#ffc36b}.error{color:#ff7f88}.debug{color:#8891a0}input,select{background:#0e1116;color:#eef2f8;border:1px solid #3a424f;border-radius:9px;padding:10px;font:inherit}button,a.button{border:0;border-radius:9px;background:#3478f6;color:#fff;padding:10px 14px;font-weight:700;text-decoration:none;cursor:pointer}button.secondary,a.secondary{background:#252b34}.hidden{display:none!important}.error-box{color:#ff8f96;margin-top:10px}.meta{display:flex;gap:20px;flex-wrap:wrap;color:#8992a2;font-size:12px;margin-top:9px}.events-head{display:flex;justify-content:space-between;align-items:center;gap:15px;margin-bottom:12px}.events-head input{min-width:270px}.empty{text-align:center;color:#788191;padding:30px}@media(max-width:900px){.cards{grid-template-columns:repeat(3,1fr)}}@media(max-width:560px){.cards{grid-template-columns:repeat(2,1fr)}header,.events-head{align-items:flex-start;flex-direction:column}.events-head input{min-width:0;width:100%}}
 </style></head><body><main class="page"><section id="dashboard"><header><div><h1>Jamal activity</h1><div class="meta"><span id="device"></span><span id="uptime"></span><span id="logFile"></span></div></div><div class="toolbar"><a class="button secondary" href="/">Open app</a><button class="secondary" id="download">Download log</button></div></header>
<div class="cards"><div class="card metric"><span>Total jobs</span><strong id="total">0</strong></div><div class="card metric"><span>Queued</span><strong id="queued">0</strong></div><div class="card metric"><span>Rendering</span><strong id="rendering">0</strong></div><div class="card metric"><span>Complete</span><strong id="complete">0</strong></div><div class="card metric"><span>Failed</span><strong id="failed">0</strong></div><div class="card metric"><span>Log events</span><strong id="eventCount">0</strong></div></div>
<div class="grid"><section class="card"><h2>All render jobs</h2><div class="table-wrap"><table><thead><tr><th>Created</th><th>Owner</th><th>Job</th><th>Reference → background</th><th>State</th><th>Progress</th><th>Message</th></tr></thead><tbody id="jobs"></tbody></table></div></section>
<section class="card"><div class="events-head"><div><h2>Activity log</h2><span class="muted">Automatically refreshes every 2 seconds</span></div><div class="toolbar"><select id="level"><option value="">All levels</option><option>ERROR</option><option>WARN</option><option>INFO</option><option>DEBUG</option></select><input id="search" type="search" placeholder="Filter action, job, message…"></div></div><div class="table-wrap"><table><thead><tr><th>Time</th><th>Level</th><th>Action</th><th>Job</th><th>Message / details</th></tr></thead><tbody id="events"></tbody></table></div></section></div></section></main><script>
const byId=id=>document.getElementById(id);let timer=null,allEvents=[];
async function api(path){const r=await fetch(path,{cache:'no-store'}),text=await r.text();let data;try{data=JSON.parse(text)}catch(_){data={error:text||'Invalid server response'}}if(!r.ok)throw Error(data.error||`Request failed (__DOLLAR__{r.status})`);return data}
async function refresh(){const [overview,eventData]=await Promise.all([api('/api/admin/overview'),api('/api/admin/events?limit=500')]);renderOverview(overview);allEvents=eventData.events||[];renderEvents()}
function schedule(){clearTimeout(timer);timer=setTimeout(()=>refresh().catch(()=>{}).finally(schedule),2000)}
function renderOverview(d){for(const k of ['total','queued','rendering','complete','failed'])byId(k).textContent=d.counts[k]||0;byId('eventCount').textContent=d.eventCount;byId('device').textContent='Server '+d.device;byId('uptime').textContent='Uptime '+duration(d.uptimeMs);byId('logFile').textContent=d.logFile;byId('jobs').innerHTML=d.jobs.length?d.jobs.map(j=>`<tr><td>__DOLLAR__{date(j.queuedAt)}</td><td>__DOLLAR__{esc(j.owner)}</td><td title="__DOLLAR__{esc(j.id)}">__DOLLAR__{esc(j.id.slice(0,8))}</td><td>__DOLLAR__{j.project?`<span class="muted">__DOLLAR__{esc(j.project)} · </span>`:''}__DOLLAR__{esc(j.reference)} → __DOLLAR__{esc(j.background)}</td><td><span class="badge">__DOLLAR__{esc(j.state)}</span></td><td>__DOLLAR__{j.percent??'—'}%</td><td class="message" title="__DOLLAR__{esc(j.message)}">__DOLLAR__{esc(j.message)}__DOLLAR__{j.drive?` <span class="muted">· Drive __DOLLAR__{esc(j.drive)}</span>`:''}</td></tr>`).join(''):'<tr><td colspan="7" class="empty">No render jobs yet.</td></tr>'}
function renderEvents(){const level=byId('level').value,q=byId('search').value.toLowerCase(),filtered=allEvents.filter(e=>(!level||e.level===level)&&(!q||JSON.stringify(e).toLowerCase().includes(q))).reverse();byId('events').innerHTML=filtered.length?filtered.map(e=>`<tr><td>__DOLLAR__{date(e.epochMs)}</td><td class="__DOLLAR__{e.level.toLowerCase()}">__DOLLAR__{esc(e.level)}</td><td>__DOLLAR__{esc(e.action)}</td><td title="__DOLLAR__{esc(e.jobId||'')}">__DOLLAR__{esc(e.jobId?e.jobId.slice(0,8):'—')}</td><td class="message" title="__DOLLAR__{esc(JSON.stringify(e.details||{}))}">__DOLLAR__{esc(e.message)} <span class="muted">__DOLLAR__{esc(details(e.details))}</span></td></tr>`).join(''):'<tr><td colspan="5" class="empty">No matching events.</td></tr>'}
byId('level').onchange=renderEvents;byId('search').oninput=renderEvents;byId('download').onclick=async()=>{try{const r=await fetch('/api/admin/logs/download');if(!r.ok)throw Error(await r.text());const blob=await r.blob(),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='jamal-activity.ndjson';a.click();URL.revokeObjectURL(url)}catch(e){alert(e.message)}};
function details(d){return Object.entries(d||{}).map(([k,v])=>`__DOLLAR__{k}=__DOLLAR__{v}`).join(' · ')}function date(ms){return new Date(ms).toLocaleString()}function duration(ms){const s=Math.floor(ms/1000),h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?`__DOLLAR__{h}h __DOLLAR__{m}m`:`__DOLLAR__{m}m __DOLLAR__{s%60}s`}function esc(v){const e=document.createElement('span');e.textContent=String(v??'');return e.innerHTML}
refresh().finally(schedule)
</script></body></html>""".replace("__DOLLAR__", "$")

private val WEB_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Jamal — Video Cutout</title><style>
*{box-sizing:border-box}body{margin:0;background:#0d0f13;color:#f4f6fb;font:15px ui-sans-serif,system-ui,-apple-system,sans-serif}.page{max-width:960px;margin:auto;padding:54px 26px 80px}h1{font-size:44px;margin:0 0 7px;letter-spacing:-1.4px}h2{font-size:19px;margin:0}.lead{color:#9da4b2;line-height:1.5}.card{background:#171a20;border:1px solid #303540;border-radius:17px;padding:24px;margin:18px 0;box-shadow:0 12px 35px #0003}.inputs{display:grid;grid-template-columns:1fr 1fr;gap:16px}.picker{display:block;background:#11141a;border:1px dashed #485263;border-radius:12px;padding:17px;font-weight:700}.picker input{margin-top:10px}.file-help{display:block;color:#858e9f;font-size:12px;font-weight:400;margin-top:7px}input{width:100%;background:#0d1015;color:#eef1f7;border:1px solid #424957;border-radius:9px;padding:11px;font:inherit}input[type=file]{padding:8px}button{border:0;border-radius:10px;background:#3478f6;color:white;padding:13px 18px;font-weight:750;font-size:15px;cursor:pointer}button:disabled{background:#343944;color:#8b93a2;cursor:wait}.primary{width:100%;margin-top:20px}.settings-toggle{width:100%;display:flex;justify-content:space-between;align-items:center;background:#232832;margin-top:18px}.settings{padding:16px 2px 2px}.settings[hidden]{display:none}.settings-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.settings label{display:block;color:#cdd2dc;font-weight:650}.settings input{margin-top:7px}.notice{min-height:20px;margin-top:14px;color:#8fc0ff}.upload{margin-top:10px}.bar{height:9px;background:#292e37;border-radius:99px;overflow:hidden}.bar i{display:block;height:100%;width:0;background:linear-gradient(90deg,#3478f6,#70a2ff);transition:width .35s}.jobs{display:grid;gap:12px;margin-top:20px}.job{background:#171a20;border:1px solid #303540;border-radius:14px;padding:18px}.job-head,.job-meta{display:flex;justify-content:space-between;align-items:center;gap:14px}.job-title{font-weight:750;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.badge{font-size:12px;text-transform:uppercase;letter-spacing:.6px;padding:5px 8px;background:#292f3a;border-radius:7px;color:#aeb8c8}.job .bar{margin:13px 0 10px}.job-message{color:#9fc7ff}.job-details{color:#8992a2;font-size:12px;margin-top:7px}.download{color:#8fc0ff;font-weight:750;text-decoration:none}.empty{color:#7f8795;text-align:center;padding:22px}@media(max-width:680px){.inputs,.settings-grid{grid-template-columns:1fr}.page{padding:34px 16px}h1{font-size:36px}}
button.secondary{background:#262c36}button.danger{background:#3a2229;color:#ffb3b9}button.link{background:none;padding:0;color:#8fc0ff;font-size:12px}.muted{color:#8992a2;font-weight:400}.warn{color:#ffc36b}.error-text{color:#ff8f96}.account-row,.drive-row,.section-head{display:flex;justify-content:space-between;align-items:center;gap:12px 16px;flex-wrap:wrap}.drive-row{margin-top:16px;padding-top:16px;border-top:1px solid #2b303a;color:#c3c9d4}.drive-row span{flex:1 1 260px}.section-head{margin-top:34px}.section-head .lead{margin:6px 0 0;max-width:640px}.form-title{margin-bottom:16px}.project-form h3{font-size:15px;margin:22px 0 0}.project-form .field{display:block;color:#cdd2dc;font-weight:650;margin:16px 0}.project-form .field input{margin-top:7px}.range{display:flex;align-items:center;gap:8px;margin-top:7px;color:#8992a2;font-weight:400}.settings .range input{margin-top:0;min-width:0;flex:1}input[type=color]{height:44px;padding:4px 6px;cursor:pointer}input:disabled{opacity:.55}.projects{display:grid;gap:12px;margin-top:14px}.project{background:#171a20;border:1px solid #303540;border-radius:14px;padding:18px}.project-meta{color:#9aa3b3;font-size:13px;margin-top:8px;line-height:1.5}.project-meta a,.job-details a{color:#8fc0ff}.project-actions{display:flex;gap:10px;align-items:center;flex-wrap:wrap;margin-top:16px}.count{display:flex;align-items:center;gap:8px;color:#cdd2dc;font-weight:650}.count input{width:84px}.swatch{display:inline-block;width:11px;height:11px;border-radius:3px;border:1px solid #fff6;vertical-align:-1px;margin-right:4px}.jobs,.projects{grid-template-columns:minmax(0,1fr)}.job-title{min-width:0}
</style></head><body><main class="page"><a href="/admin" style="float:right;color:#8fc0ff;text-decoration:none">Admin</a><h1>Jamal</h1><p class="lead">Generate randomised cutout compositions from folders of videos with projects, or submit up to five compositions at once. Renders beyond the server's parallel limit wait in a queue.</p><section class="card" id="account">Checking Google sign-in…</section>
<section id="projects" hidden><div class="section-head"><div><h2>Projects</h2><p class="lead">Each generated video takes a random reference and background video from the project's folders, and random composition values within its from–to ranges.</p></div><button type="button" id="addProject">+ Add project</button></div>
<form id="projectForm" class="card project-form" hidden><h2 id="projectFormTitle">New project</h2><label class="field">Project name<input name="name" maxlength="80" placeholder="Autumn campaign" autocomplete="off" required></label>
<div class="inputs"><label class="picker">Reference videos folder<input id="projectRefs" type="file" webkitdirectory multiple><span class="file-help" id="projectRefHelp"></span></label><label class="picker">Background videos folder<input id="projectBgs" type="file" webkitdirectory multiple><span class="file-help" id="projectBgHelp"></span></label></div>
<div class="settings"><div class="settings-grid"><label>Videos to generate<input name="count" type="number" min="1" max="100" inputmode="numeric" required></label><label>Google Drive folder link<input name="driveLink" placeholder="https://drive.google.com/drive/folders/…" autocomplete="off"><span class="file-help" id="driveHelp"></span></label></div>
<h3>Composition settings <span class="muted">· every video gets a random value from … to</span></h3><div class="settings-grid" style="margin-top:12px"><label>Outline (pixels)<span class="range"><input name="outlineMin" type="number" min="1" max="200" inputmode="numeric" required aria-label="Outline from">to<input name="outlineMax" type="number" min="1" max="200" inputmode="numeric" required aria-label="Outline to"></span></label><label>Person scale (% frame height)<span class="range"><input name="scaleMin" type="number" min="5" max="200" inputmode="numeric" required aria-label="Person scale from">to<input name="scaleMax" type="number" min="5" max="200" inputmode="numeric" required aria-label="Person scale to"></span></label><label>Left margin (% frame width)<span class="range"><input name="leftMin" type="number" min="0" max="99" inputmode="numeric" required aria-label="Left margin from">to<input name="leftMax" type="number" min="0" max="99" inputmode="numeric" required aria-label="Left margin to"></span></label><label>Bottom margin (% frame height)<span class="range"><input name="bottomMin" type="number" min="0" max="99" inputmode="numeric" required aria-label="Bottom margin from">to<input name="bottomMax" type="number" min="0" max="99" inputmode="numeric" required aria-label="Bottom margin to"></span></label><label>Outline colour<span class="range"><input name="colorFrom" type="color" aria-label="Outline colour from">to<input name="colorTo" type="color" aria-label="Outline colour to"></span></label></div></div>
<div class="project-actions"><button id="saveProject">Create project</button><button type="button" class="secondary" id="cancelProject">Cancel</button></div><div class="notice" id="projectNotice"></div><div class="upload" id="projectUpload" hidden><div class="bar"><i id="projectUploadBar"></i></div></div></form>
<div class="projects" id="projectList"></div></section>
<form id="render" class="card" hidden><h2 class="form-title">Quick render <span class="muted">· choose individual files</span></h2><div class="inputs"><label class="picker">Reference videos<input required multiple name="reference" type="file" accept="video/*,.mov,.m4v,.avi,.mkv,.webm"><span class="file-help" id="refHelp">Choose up to five videos</span></label><label class="picker">Background videos<input required multiple name="background" type="file" accept="video/*,.mov,.m4v,.avi,.mkv,.webm"><span class="file-help" id="bgHelp">Choose up to five videos</span></label></div>
<button class="settings-toggle" id="settingsToggle" type="button"><span>Composition settings</span><span id="settingsIcon">Show ▾</span></button><section class="settings" id="settings" hidden><div class="settings-grid"><label>Outline (pixels)<input name="outlinePixels" value="12" inputmode="numeric"></label><label>Person scale (% frame height)<input name="scalePercent" value="46" inputmode="numeric"></label><label>Left margin (% frame width)<input name="horizontalPercent" value="2" inputmode="numeric"></label><label>Bottom margin (% frame height)<input name="bottomPercent" value="0" inputmode="numeric"></label><label>Outline colour<input name="outlineColor" value="#FFFFFF" pattern="#[0-9A-Fa-f]{6}"></label></div></section>
<div class="notice" id="pairText">Select videos to see output count</div>
<button class="primary" id="submitButton">Start render</button><div class="notice" id="notice"></div><div class="upload" id="upload" hidden><div class="bar"><i id="uploadBar"></i></div></div></form>
<section id="history" hidden><h2>My renders <span class="badge" id="parallelBadge">up to 5 at a time</span></h2><div class="jobs" id="jobs"><div class="empty">No videos submitted yet.</div></div></section></main><script>
const form=document.querySelector('#render'),notice=document.querySelector('#notice'),submit=document.querySelector('#submitButton'),jobsEl=document.querySelector('#jobs'),upload=document.querySelector('#upload'),uploadBar=document.querySelector('#uploadBar');
const refs=form.elements.reference,bgs=form.elements.background,account=document.querySelector('#account'),history=document.querySelector('#history'),rendered=new Map();
const projectsEl=document.querySelector('#projects'),projectForm=document.querySelector('#projectForm'),projectList=document.querySelector('#projectList'),projectNotice=document.querySelector('#projectNotice'),projectUpload=document.querySelector('#projectUpload'),projectUploadBar=document.querySelector('#projectUploadBar'),saveProject=document.querySelector('#saveProject'),projectRefs=document.querySelector('#projectRefs'),projectBgs=document.querySelector('#projectBgs');
const projectFields=['name','count','driveLink','outlineMin','outlineMax','scaleMin','scaleMax','leftMin','leftMax','bottomMin','bottomMax','colorFrom','colorTo'],projectDefaults={name:'',count:5,driveLink:'',outlineMin:10,outlineMax:14,scaleMin:42,scaleMax:50,leftMin:0,leftMax:4,bottomMin:0,bottomMax:2,colorFrom:'#FFFFFF',colorTo:'#FFFFFF'};
let projects=[],editingProject=null,drive={available:false,connected:false,email:null},pollTimer=null,polling=false,pollAgain=false;
const params=new URLSearchParams(location.search);let driveNotice=params.get('driveError')?{error:true,text:params.get('driveError')}:params.get('drive')==='connected'?{error:false,text:'Google Drive connected.'}:null;if(params.has('drive')||params.has('driveError'))window.history.replaceState(null,'',location.pathname);
document.querySelector('#settingsToggle').onclick=()=>{const panel=document.querySelector('#settings'),opening=panel.hidden;panel.hidden=!opening;document.querySelector('#settingsIcon').textContent=opening?'Hide ▴':'Show ▾'};
function pairCount(){const a=refs.files.length,b=bgs.files.length;if(!a||!b)return 0;if(a===b)return a;if(a===1)return b;if(b===1)return a;return -1}
function selection(){document.querySelector('#refHelp').textContent=refs.files.length?refs.files.length+' selected':'Choose up to five videos';document.querySelector('#bgHelp').textContent=bgs.files.length?bgs.files.length+' selected':'Choose up to five videos';const n=pairCount();document.querySelector('#pairText').textContent=n<0?'Selections cannot be paired':n>5?'Maximum is 5 outputs per submission':n?`__DOLLAR__{n} output__DOLLAR__{n===1?'':'s'} will be rendered`:'Select videos to see output count'}refs.onchange=selection;bgs.onchange=selection;
async function api(path,options){const r=await fetch(path,options),text=await r.text();let data={};try{data=text?JSON.parse(text):{}}catch(_){data={error:text.replace(/<[^>]*>/g,' ').replace(/\s+/g,' ').trim().slice(0,240)}}if(!r.ok)throw Error(data.error||`Request failed (HTTP __DOLLAR__{r.status})`);return data}
async function loadAccount(){const me=await fetch('/api/me').then(r=>r.json());if(!me.signedIn){account.innerHTML=me.googleConfigured?'<div class="account-row"><strong>Sign in to manage your renders</strong><a href="/auth/google"><button>Continue with Google</button></a></div>':'<div class="account-row"><strong>Local upload mode</strong><button id="continueLocal">Continue without Google</button></div>';const local=document.querySelector('#continueLocal');if(local)local.onclick=async()=>{await fetch('/auth/local',{method:'POST'});location.reload()};return}drive=me.drive||drive;if(me.parallelRenders)document.querySelector('#parallelBadge').textContent=me.parallelRenders===1?'one at a time':`up to __DOLLAR__{me.parallelRenders} at a time`;account.innerHTML=`<div class="account-row"><strong>Signed in as __DOLLAR__{escapeHtml(me.email)}</strong><button class="secondary" id="signOut">Sign out</button></div><div class="drive-row" id="driveRow"></div>`;document.querySelector('#signOut').onclick=async()=>{await fetch('/auth/logout',{method:'POST'});location.reload()};renderDrive();form.hidden=false;history.hidden=false;projectsEl.hidden=false;loadProjects().catch(e=>projectList.innerHTML=`<div class="empty error-text">__DOLLAR__{escapeHtml(e.message)}</div>`);pollAll()}
function renderDrive(){const row=document.querySelector('#driveRow');if(row){const note=driveNotice?`<span class="__DOLLAR__{driveNotice.error?'error-text':'job-message'}">__DOLLAR__{escapeHtml(driveNotice.text)}</span>`:'';if(!drive.available)row.innerHTML=`<span class="muted">Saving to Google Drive needs Google sign-in, which is not configured on this server.</span>__DOLLAR__{note}`;else if(drive.connected){row.innerHTML=`<span>Google Drive connected as <strong>__DOLLAR__{escapeHtml(drive.email||'')}</strong></span>__DOLLAR__{note}<button type="button" class="secondary" id="driveButton">Disconnect Drive</button>`;row.querySelector('#driveButton').onclick=disconnectDrive}else{row.innerHTML=`<span>Google Drive is not connected. Connect it so projects with a Drive link can upload their videos.</span>__DOLLAR__{note}<button type="button" id="driveButton">Connect Google Drive</button>`;row.querySelector('#driveButton').onclick=()=>location.href='/auth/google/drive'}}renderDriveHelp()}
async function disconnectDrive(){if(!confirm('Disconnect Google Drive? Projects stop uploading until you connect it again.'))return;try{drive=(await api('/auth/google/drive/disconnect',{method:'POST'})).drive;driveNotice=null}catch(e){driveNotice={error:true,text:e.message}}renderDrive();renderProjects()}
function renderDriveHelp(){const input=projectForm.elements.namedItem('driveLink');input.disabled=!drive.available;document.querySelector('#driveHelp').textContent=!drive.available?'Needs Google sign-in to be configured on this server.':drive.connected?`Uploaded as __DOLLAR__{drive.email}. Leave empty to keep videos on this server only.`:'Connect Google Drive above so finished videos can be uploaded here.'}
function folderVideos(input){return[...input.files].filter(f=>f.size>0&&/\.(mov|mp4|m4v|avi|mkv|webm)__DOLLAR__/i.test(f.name)&&!(f.webkitRelativePath||f.name).split('/').some(part=>part.startsWith('.')))}
function folderHelp(input,help,current){const n=folderVideos(input).length,other=input.files.length-n;help.textContent=input.files.length?`__DOLLAR__{n} video__DOLLAR__{n===1?'':'s'} found__DOLLAR__{other?` · __DOLLAR__{other} other file__DOLLAR__{other===1?'':'s'} skipped`:''}__DOLLAR__{current?' · replaces the current videos':''}`:current?`__DOLLAR__{current} video__DOLLAR__{current===1?'':'s'} in this project · choose a folder to replace them`:'Choose a folder · every generated video picks one of its videos at random'}
function projectHelp(){folderHelp(projectRefs,document.querySelector('#projectRefHelp'),editingProject?.references.length);folderHelp(projectBgs,document.querySelector('#projectBgHelp'),editingProject?.backgrounds.length)}projectRefs.onchange=projectBgs.onchange=projectHelp;
function openProjectForm(project){editingProject=project;projectForm.reset();projectFields.forEach(k=>projectForm.elements.namedItem(k).value=(project||projectDefaults)[k]??'');document.querySelector('#projectFormTitle').textContent=project?'Edit project':'New project';saveProject.textContent=project?'Save changes':'Create project';projectNotice.textContent='';projectUpload.hidden=true;projectHelp();renderDriveHelp();projectForm.hidden=false;projectForm.scrollIntoView({behavior:'smooth',block:'nearest'})}
function closeProjectForm(){projectForm.hidden=true;projectForm.reset();editingProject=null}document.querySelector('#addProject').onclick=()=>openProjectForm(null);document.querySelector('#cancelProject').onclick=closeProjectForm;
projectForm.onsubmit=async e=>{e.preventDefault();const refVideos=folderVideos(projectRefs),bgVideos=folderVideos(projectBgs);if(projectRefs.files.length&&!refVideos.length||projectBgs.files.length&&!bgVideos.length){projectNotice.textContent='A chosen folder has no supported videos (MOV, MP4, M4V, AVI, MKV or WEBM).';return}if(!(refVideos.length||editingProject?.references.length)||!(bgVideos.length||editingProject?.backgrounds.length)){projectNotice.textContent='Choose a folder of reference videos and a folder of background videos.';return}saveProject.disabled=true;projectNotice.textContent='Saving project…';try{const body=new URLSearchParams();projectFields.forEach(k=>body.set(k,projectForm.elements.namedItem(k).value));let{project}=await api(editingProject?'/api/projects/'+editingProject.id:'/api/projects',{method:editingProject?'PUT':'POST',body});editingProject=project;const uploads=[];for(const[pool,videos]of[['reference',refVideos],['background',bgVideos]]){if(!videos.length)continue;if(project[pool+'s'].length)project=(await api(`/api/projects/__DOLLAR__{project.id}/videos?pool=__DOLLAR__{pool}`,{method:'DELETE'})).project;videos.forEach(file=>uploads.push([pool,file]))}if(uploads.length)project=await uploadVideos(project.id,uploads);closeProjectForm();await loadProjects();projectMessage(project.id,'Project saved.')}catch(err){projectNotice.textContent=err.message;loadProjects().catch(()=>{})}finally{saveProject.disabled=false;projectUpload.hidden=true}};
function sendVideo(projectId,pool,file,progress){return new Promise((resolve,reject)=>{const xhr=new XMLHttpRequest();xhr.open('POST',`/api/projects/__DOLLAR__{projectId}/videos?pool=__DOLLAR__{pool}&name=__DOLLAR__{encodeURIComponent(file.name)}`);xhr.upload.onprogress=x=>{if(x.lengthComputable)progress(x.loaded)};xhr.onload=()=>{let d={};try{d=JSON.parse(xhr.responseText)}catch(_){}if(xhr.status>=200&&xhr.status<300)resolve(d.project);else reject(Error(`__DOLLAR__{file.name}: __DOLLAR__{d.error||uploadError(xhr)}`))};xhr.onerror=()=>reject(Error(`Uploading __DOLLAR__{file.name} was interrupted. Save the project again to retry.`));xhr.send(file)})}
async function uploadVideos(projectId,uploads){const total=uploads.reduce((sum,[,file])=>sum+file.size,0)||1;let sent=0,project;projectUpload.hidden=false;projectUploadBar.style.width='0%';for(const[index,[pool,file]]of uploads.entries()){project=await sendVideo(projectId,pool,file,loaded=>{const p=Math.min(100,Math.round((sent+loaded)/total*100));projectUploadBar.style.width=p+'%';projectNotice.textContent=`Uploading video __DOLLAR__{index+1} of __DOLLAR__{uploads.length} · __DOLLAR__{p}%`});sent+=file.size}return project}
async function loadProjects(){projects=(await api('/api/projects')).projects;renderProjects()}
function span(min,max,unit){return min===max?`__DOLLAR__{min}__DOLLAR__{unit}`:`__DOLLAR__{min}–__DOLLAR__{max}__DOLLAR__{unit}`}function plural(n,word){return`__DOLLAR__{n} __DOLLAR__{word}__DOLLAR__{n===1?'':'s'}`}function swatch(color){return/^#[0-9A-Fa-f]{6}__DOLLAR__/.test(color||'')?`<i class="swatch" style="background:__DOLLAR__{color}"></i>`:''}
function renderProjects(){projectList.innerHTML=projects.length?'':'<div class="empty">No projects yet. Use “Add project” to generate videos from folders.</div>';for(const p of projects){const el=document.createElement('article'),colours=p.colorFrom===p.colorTo?swatch(p.colorFrom)+escapeHtml(p.colorFrom):`__DOLLAR__{swatch(p.colorFrom)}__DOLLAR__{escapeHtml(p.colorFrom)} – __DOLLAR__{swatch(p.colorTo)}__DOLLAR__{escapeHtml(p.colorTo)}`,target=p.driveFolderId?`Saves to Google Drive: <a href="__DOLLAR__{escapeHtml(p.driveLink)}" target="_blank" rel="noopener">__DOLLAR__{escapeHtml(p.driveFolderName||'linked folder')}</a>__DOLLAR__{drive.connected?'':' <span class="warn">· connect Google Drive to upload</span>'}`:'Saved on this server · download from My renders';el.className='project';el.id='project-'+p.id;el.innerHTML=`<div class="job-head"><div class="job-title">__DOLLAR__{escapeHtml(p.name)}</div><span class="badge">__DOLLAR__{p.driveFolderId?'Google Drive':'Server'}</span></div><div class="project-meta">__DOLLAR__{plural(p.references.length,'reference video')} · __DOLLAR__{plural(p.backgrounds.length,'background video')}</div><div class="project-meta">Outline __DOLLAR__{span(p.outlineMin,p.outlineMax,' px')} · Scale __DOLLAR__{span(p.scaleMin,p.scaleMax,'%')} · Left __DOLLAR__{span(p.leftMin,p.leftMax,'%')} · Bottom __DOLLAR__{span(p.bottomMin,p.bottomMax,'%')} · __DOLLAR__{colours}</div><div class="project-meta">__DOLLAR__{target}</div><div class="project-actions"><label class="count">Videos<input type="number" min="1" max="100" value="__DOLLAR__{p.count}" inputmode="numeric"></label><button type="button" data-generate>Generate</button><button type="button" class="secondary" data-edit>Edit</button><button type="button" class="danger" data-delete>Delete</button></div><div class="notice" data-notice></div>`;const note=el.querySelector('[data-notice]'),generate=el.querySelector('[data-generate]');generate.onclick=async()=>{generate.disabled=true;note.textContent='Queuing videos…';try{const d=await api(`/api/projects/__DOLLAR__{p.id}/generate`,{method:'POST',body:new URLSearchParams({count:el.querySelector('.count input').value})});d.jobs.forEach(renderJob);pollAll();await loadProjects().catch(()=>{});projectMessage(p.id,`__DOLLAR__{plural(d.jobs.length,'video')} queued. Follow them under My renders.`)}catch(err){note.textContent=err.message}finally{generate.disabled=false}};el.querySelector('[data-edit]').onclick=()=>openProjectForm(p);el.querySelector('[data-delete]').onclick=async()=>{if(!confirm(`Delete the project “__DOLLAR__{p.name}” and its uploaded videos? Finished renders stay in My renders.`))return;try{await api('/api/projects/'+p.id,{method:'DELETE'});if(editingProject?.id===p.id)closeProjectForm();await loadProjects()}catch(err){note.textContent=err.message}};projectList.append(el)}}
function projectMessage(id,text){const note=document.querySelector(`#project-__DOLLAR__{id} [data-notice]`);if(note)note.textContent=text}
function uploadError(xhr){const raw=(xhr.responseText||'').trim();try{const data=JSON.parse(raw);if(data.error)return data.error}catch(_){}const text=raw.replace(/<[^>]*>/g,' ').replace(/\s+/g,' ').trim().slice(0,240);if(xhr.status===0)return'Upload connection was interrupted. Keep this page open and try again on a faster connection.';if(xhr.status===413)return'The selected videos are larger than the server or proxy upload limit.';if(xhr.status===502)return'The video server is temporarily unavailable (HTTP 502).';if(xhr.status===504)return'The upload gateway timed out (HTTP 504). Try a smaller file or a faster connection.';return`Upload failed: HTTP __DOLLAR__{xhr.status||'network error'}__DOLLAR__{text?' — '+text:''}`}
function uploadFinished(){submit.disabled=false;upload.hidden=true}
form.onsubmit=e=>{e.preventDefault();const n=pairCount();if(n<0){notice.textContent='Choose equal counts, or choose one file on either side to reuse it.';return}if(n>5){notice.textContent='Quick render accepts a maximum of 5 outputs per submission.';return}submit.disabled=true;notice.textContent='Uploading selected videos…';upload.hidden=false;uploadBar.style.width='0%';const xhr=new XMLHttpRequest();xhr.open('POST','/api/renders');xhr.timeout=30*60*1000;xhr.upload.onprogress=x=>{if(x.lengthComputable){const p=Math.round(x.loaded/x.total*100);uploadBar.style.width=p+'%';notice.textContent=`Uploading videos… __DOLLAR__{p}%`}};xhr.onload=()=>{uploadFinished();let d;try{d=JSON.parse(xhr.responseText)}catch(_){notice.textContent=uploadError(xhr);return}if(xhr.status<200||xhr.status>=300){notice.textContent=d.error||uploadError(xhr);return}notice.textContent=`__DOLLAR__{d.jobs.length} render__DOLLAR__{d.jobs.length===1?'':'s'} queued.`;d.jobs.forEach(renderJob);pollAll()};xhr.onerror=()=>{uploadFinished();notice.textContent='Upload connection was interrupted before the server received all video data. Please retry and keep this page open.'};xhr.ontimeout=()=>{uploadFinished();notice.textContent='Upload timed out after 30 minutes. Try smaller files or a faster connection.'};xhr.onabort=()=>{uploadFinished();notice.textContent='Upload was cancelled.'};xhr.send(new FormData(form))};
function renderJob(j){const key=JSON.stringify(j);if(rendered.get(j.id)===key)return;rendered.set(j.id,key);let el=document.getElementById('job-'+j.id);if(!el){el=document.createElement('article');el.className='job';el.id='job-'+j.id;jobsEl.querySelector('.empty')?.remove();jobsEl.prepend(el)}const d=j.drive,state=j.state==='complete'&&d&&(d.status==='pending'||d.status==='uploading')?'uploading':j.state||'queued',title=`__DOLLAR__{j.reference||'Reference'} → __DOLLAR__{j.background||'Background'}`,queue=j.state==='queued'&&j.queuePosition?`Queue position __DOLLAR__{j.queuePosition}`:'',time=j.elapsedMs?`Elapsed __DOLLAR__{duration(j.elapsedMs)}`:'',eta=j.etaMs?`About __DOLLAR__{duration(j.etaMs)} remaining`:'',s=j.settings,settings=j.projectName&&s?`<div class="job-details">__DOLLAR__{escapeHtml(j.projectName)} · outline __DOLLAR__{escapeHtml(s.outlinePixels)} px · scale __DOLLAR__{escapeHtml(s.scalePercent)}% · left __DOLLAR__{escapeHtml(s.horizontalPercent)}% · bottom __DOLLAR__{escapeHtml(s.bottomPercent)}% · __DOLLAR__{swatch(s.outlineColor)}__DOLLAR__{escapeHtml(s.outlineColor)}</div>`:'';el.innerHTML=`<div class="job-head"><div class="job-title" title="__DOLLAR__{escapeHtml(title)}">__DOLLAR__{escapeHtml(title)}</div><span class="badge">__DOLLAR__{escapeHtml(state)}</span></div><div class="bar"><i style="width:__DOLLAR__{j.percent||0}%"></i></div><div class="job-meta"><span class="job-message">__DOLLAR__{escapeHtml(j.message||'Queued')}__DOLLAR__{j.percent!=null?' · '+j.percent+'%':''}</span>__DOLLAR__{j.ready?`<a class="download" href="/api/renders/__DOLLAR__{j.id}/download">Download MP4</a>`:''}</div>__DOLLAR__{settings}__DOLLAR__{driveLine(j)}<div class="job-details">__DOLLAR__{[queue,time,eta].filter(Boolean).join(' · ')}</div>`}
function driveLine(j){const d=j.drive;if(!d)return'';const link=/^https:\/\//.test(d.url||'')?` · <a href="__DOLLAR__{escapeHtml(d.url)}" target="_blank" rel="noopener">Open in Google Drive</a>`:'',text=d.status==='uploaded'?`Saved to Google Drive__DOLLAR__{link}`:d.status==='failed'?`<span class="error-text">Google Drive: __DOLLAR__{escapeHtml(d.message)}</span> <button type="button" class="link" data-retry="__DOLLAR__{j.id}">Retry upload</button>`:d.status==='uploading'?escapeHtml(d.message||'Uploading to Google Drive'):j.state==='complete'?'Waiting for a Google Drive upload slot':'Uploads to Google Drive when the render finishes';return`<div class="job-details">__DOLLAR__{text}</div>`}
jobsEl.onclick=async e=>{const button=e.target.closest('[data-retry]');if(!button)return;button.disabled=true;try{renderJob(await api(`/api/renders/__DOLLAR__{button.dataset.retry}/drive`,{method:'POST'}));pollAll()}catch(err){alert(err.message);button.disabled=false}};
async function pollAll(){if(polling){pollAgain=true;return}polling=true;clearTimeout(pollTimer);let running=false;try{const d=await api('/api/renders');d.jobs.slice().reverse().forEach(renderJob);running=d.jobs.some(j=>j.running)}catch(_){running=true}polling=false;if(pollAgain){pollAgain=false;return pollAll()}if(running)pollTimer=setTimeout(pollAll,1000)}
function duration(ms){const s=Math.max(0,Math.round(ms/1000)),m=Math.floor(s/60);return m?`__DOLLAR__{m}m __DOLLAR__{s%60}s`:`__DOLLAR__{s}s`}
function escapeHtml(v){const e=document.createElement('span');e.textContent=v;return e.innerHTML.replace(/"/g,'&quot;').replace(/'/g,'&#39;')}loadAccount().catch(()=>account.textContent='Could not check Google sign-in.');selection();
</script></body></html>""".replace("__DOLLAR__", "$")
