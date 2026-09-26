package com.jamal.web

import com.sun.net.httpserver.HttpExchange
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.Locale
import java.util.Properties

/*
 * MCP (Model Context Protocol) server, so ChatGPT and other MCP clients can list a user's finished videos and fetch
 * them. ChatGPT connects to remote MCP servers with OAuth or with "No authentication"; this server uses the latter
 * together with a personal secret link, `/mcp/<token>`, that the user creates on the page and can replace or turn
 * off. Clients that can send headers may call `/mcp` with `Authorization: Bearer <token>` instead.
 *
 * Transport: MCP Streamable HTTP without sessions or event streams — every JSON-RPC POST gets one JSON answer.
 * Every tool only reads. Video files are handed out as signed download links that expire after a day.
 */

private const val MCP_SERVER_VERSION = "1.0.0"
private val mcpProtocolVersions = listOf("2025-11-25", "2025-06-18", "2025-03-26")
private const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
private const val DOWNLOAD_LINK_SECONDS = 24L * 60 * 60
private const val MCP_INSTRUCTIONS = "Jamal renders cutout videos, grouped in projects. list_projects shows the projects; " +
    "list_videos lists finished videos (newest first) with download links; search and fetch find a single video. " +
    "Download links expire after 24 hours, so call a tool again for a fresh link."

private data class McpLink(val file: Path, val ownerId: String, val ownerEmail: String, val createdAt: Long)
private class McpInvalidParams(message: String) : Exception(message)
private class McpTool(
    val name: String,
    val title: String,
    val description: String,
    val properties: Map<String, Any?> = emptyMap(),
    val required: List<String> = emptyList(),
    val run: (SignedInUser, Map<*, *>) -> Map<String, Any?>,
)

private val mcpTools = listOf(
    McpTool("list_projects", "List projects", "Lists the user's Jamal projects: how many finished videos each has, how many are still rendering, its server folder and Google Drive folder.") { user, _ -> listProjectsTool(user) },
    McpTool(
        "list_videos", "List finished videos",
        "Lists finished videos, newest first, with a download link (valid 24 hours), Google Drive link, project, source videos and the random composition values used.",
        mapOf(
            "project" to mapOf("type" to "string", "description" to "Only videos of this project (name, folder name or id). Omit for all videos."),
            "limit" to mapOf("type" to "integer", "minimum" to 1, "maximum" to 100, "default" to 20, "description" to "How many videos to return."),
            "offset" to mapOf("type" to "integer", "minimum" to 0, "default" to 0, "description" to "How many of the newest videos to skip."),
        ),
    ) { user, arguments -> listVideosTool(user, arguments) },
    McpTool(
        "search", "Search videos",
        "Finds finished videos whose project, file name, source video names or date (yyyy-mm-dd) contain every word of the query. Returns ids for fetch and download links.",
        mapOf("query" to mapOf("type" to "string", "description" to "Words to look for; empty returns the newest videos.")), listOf("query"),
    ) { user, arguments -> searchTool(user, arguments) },
    McpTool(
        "fetch", "Get a video",
        "Returns one finished video by id (from search or list_videos): its details, a download link valid for 24 hours and its Google Drive link.",
        mapOf("id" to mapOf("type" to "string", "description" to "Video id.")), listOf("id"),
    ) { user, arguments -> fetchTool(user, arguments) },
).associateBy { it.name }

// ---- Personal links ----------------------------------------------------------------------------------------------

internal fun mcpStatusJson(user: SignedInUser): String {
    val link = mcpLinksOf(user).maxByOrNull { it.createdAt }
    return "{\"enabled\":${link != null},\"createdAt\":${link?.createdAt ?: "null"}}"
}

/** Creates the user's ChatGPT link; an earlier link stops working. The token is shown once and only its hash is kept. */
internal fun createMcpLink(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    val token = replaceMcpLinks(user, create = true)
    ActivityLog.info("mcp.link_created", "ChatGPT (MCP) link created", requestContext(exchange, mapOf("owner" to user.email)))
    respondJson(exchange, 200, "{\"url\":\"${"${publicBaseUrl()}/mcp/$token".jsonEscape()}\",\"mcp\":${mcpStatusJson(user)}}")
}

internal fun revokeMcpLink(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    replaceMcpLinks(user, create = false)
    ActivityLog.info("mcp.link_revoked", "ChatGPT (MCP) link turned off", requestContext(exchange, mapOf("owner" to user.email)))
    respondJson(exchange, 200, "{\"mcp\":${mcpStatusJson(user)}}")
}

private fun mcpDirectory(): Path = jamalDirectory().resolve("mcp").also(Files::createDirectories)
private fun mcpLinkFile(token: String): Path = mcpDirectory().resolve("${sha256("mcp-link|$token")}.properties")

private fun mcpLinksOf(user: SignedInUser): List<McpLink> = Files.list(mcpDirectory()).use { files ->
    files.filter { it.fileName.toString().endsWith(".properties") }.toList()
}.mapNotNull { file ->
    val values = try { Properties().also { Files.newInputStream(file).use(it::load) } } catch (_: Exception) { return@mapNotNull null }
    McpLink(file, values.getProperty("ownerId") ?: return@mapNotNull null, values.getProperty("ownerEmail").orEmpty(), values.getProperty("createdAt")?.toLongOrNull() ?: 0L)
}.filter { it.ownerId == user.id }

@Synchronized private fun replaceMcpLinks(user: SignedInUser, create: Boolean): String? {
    mcpLinksOf(user).forEach { Files.deleteIfExists(it.file) }
    if (!create) return null
    val token = "jm" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(secureRandom::nextBytes))
    val values = Properties()
    values["ownerId"] = user.id; values["ownerEmail"] = user.email; values["createdAt"] = System.currentTimeMillis().toString()
    val temporary = Files.createTempFile(mcpDirectory(), "link-", ".tmp")
    Files.newOutputStream(temporary).use { values.store(it, "Jamal ChatGPT (MCP) link; the file name is a hash of the secret") }
    Files.move(temporary, mcpLinkFile(token), StandardCopyOption.REPLACE_EXISTING)
    return token
}

private fun mcpLinkOwner(token: String): SignedInUser? {
    if (!token.matches(Regex("[A-Za-z0-9_-]{20,100}"))) return null
    val file = mcpLinkFile(token)
    if (!Files.isRegularFile(file)) return null
    val values = Properties().also { Files.newInputStream(file).use(it::load) }
    return SignedInUser(values.getProperty("ownerId") ?: return null, values.getProperty("ownerEmail").orEmpty())
}

// ---- MCP endpoint -------------------------------------------------------------------------------------------------

internal fun handleMcp(exchange: HttpExchange) {
    val path = exchange.requestURI.path
    val token = if (path == "/mcp") exchange.requestHeaders.getFirst("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.trim()
    else path.removePrefix("/mcp/")
    val user = token?.let(::mcpLinkOwner) ?: return respondJson(exchange, if (path == "/mcp") 401 else 404, "{\"error\":\"Unknown or turned-off ChatGPT link. Create a new one on the Jamal page.\"}")
    if (exchange.requestMethod != "POST") {
        exchange.responseHeaders.add("Allow", "POST")
        return respondJson(exchange, 405, "{\"error\":\"This MCP server accepts JSON-RPC over POST only.\"}")
    }
    val message = try {
        JsonReader(String(exchange.requestBody.readAllBytes(), StandardCharsets.UTF_8)).read()
    } catch (_: Exception) {
        return respondJson(exchange, 400, rpcError(null, -32700, "Parse error"))
    }
    // A batch (allowed by protocol 2025-03-26) gets an array answer; notifications and client responses get none.
    val replies = if (message is List<*>) message.mapNotNull { mcpReply(user, it) } else listOfNotNull(mcpReply(user, message))
    when {
        replies.isEmpty() -> { exchange.sendResponseHeaders(202, -1); exchange.close() }
        message is List<*> -> respondJson(exchange, 200, replies.joinToString(",", "[", "]"))
        else -> respondJson(exchange, 200, replies.single())
    }
}

private fun mcpReply(user: SignedInUser, message: Any?): String? {
    val request = message as? Map<*, *> ?: return rpcError(null, -32600, "Invalid request")
    if ("id" !in request) return null
    val id = request["id"]
    val method = request["method"] as? String ?: return null
    val params = request["params"] as? Map<*, *> ?: emptyMap<String, Any?>()
    return try {
        rpcResult(id, when (method) {
            "initialize" -> mapOf(
                "protocolVersion" to ((params["protocolVersion"] as? String)?.takeIf { it in mcpProtocolVersions } ?: DEFAULT_PROTOCOL_VERSION),
                "capabilities" to mapOf("tools" to mapOf("listChanged" to false)),
                "serverInfo" to mapOf("name" to "jamal-videos", "title" to "Jamal videos", "version" to MCP_SERVER_VERSION),
                "instructions" to MCP_INSTRUCTIONS,
            )
            "ping" -> emptyMap<String, Any?>()
            "tools/list" -> mapOf("tools" to mcpTools.values.map(::toolDefinition))
            "tools/call" -> callTool(user, params)
            else -> return rpcError(id, -32601, "Method not found: $method")
        })
    } catch (error: McpInvalidParams) {
        rpcError(id, -32602, error.message ?: "Invalid params")
    }
}

private fun toolDefinition(tool: McpTool) = mapOf(
    "name" to tool.name,
    "title" to tool.title,
    "description" to tool.description,
    "inputSchema" to mapOf("type" to "object", "properties" to tool.properties, "required" to tool.required, "additionalProperties" to false),
    // Read-only tools run in ChatGPT without a confirmation prompt.
    "annotations" to mapOf("title" to tool.title, "readOnlyHint" to true, "destructiveHint" to false, "idempotentHint" to true, "openWorldHint" to false),
)

private fun callTool(user: SignedInUser, params: Map<*, *>): Map<String, Any?> {
    val name = params["name"] as? String ?: throw McpInvalidParams("The tool name is missing.")
    val tool = mcpTools[name] ?: throw McpInvalidParams("Unknown tool: $name")
    val arguments = params["arguments"] as? Map<*, *> ?: emptyMap<String, Any?>()
    ActivityLog.info("mcp.tool_called", "MCP tool $name called", LogContext(details = mapOf("owner" to user.email, "tool" to name)))
    return try {
        val result = tool.run(user, arguments)
        // ChatGPT's search/fetch convention: a single text item holding the JSON result.
        mapOf("content" to listOf(mapOf("type" to "text", "text" to toJson(result))), "structuredContent" to result, "isError" to false)
    } catch (error: IllegalArgumentException) {
        mapOf("content" to listOf(mapOf("type" to "text", "text" to (error.message ?: "The request could not be answered."))), "isError" to true)
    }
}

private fun rpcResult(id: Any?, result: Any?) = toJson(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
private fun rpcError(id: Any?, code: Int, message: String) = toJson(mapOf("jsonrpc" to "2.0", "id" to id, "error" to mapOf("code" to code, "message" to message)))

// ---- Tools ----------------------------------------------------------------------------------------------------------

private fun finishedVideos(user: SignedInUser) = jobs.values
    .filter { it.owner.id == user.id && it.state == JobState.COMPLETE && Files.isRegularFile(it.output) }
    .sortedByDescending { it.finishedAt ?: it.queuedAt }

private fun listProjectsTool(user: SignedInUser): Map<String, Any?> {
    val userJobs = jobs.values.filter { it.owner.id == user.id }
    val finished = finishedVideos(user)
    return mapOf(
        "projects" to projects.values.filter { it.owner.id == user.id }.sortedByDescending { it.createdAt }.map { project ->
            mapOf(
                "id" to project.id,
                "name" to project.name,
                "finishedVideos" to finished.count { it.projectId == project.id },
                "inProgress" to userJobs.count { it.projectId == project.id && (it.state == JobState.QUEUED || it.state == JobState.RENDERING) },
                "referenceVideos" to project.references.size,
                "backgroundVideos" to project.backgrounds.size,
                "videosPerRun" to project.settings.outputCount,
                "serverFolder" to project.exportFolder,
                "googleDriveFolder" to project.settings.driveFolderId?.let(::driveFolderLink),
            )
        },
        "quickRenderVideos" to finished.count { it.projectId == null },
    )
}

private fun listVideosTool(user: SignedInUser, arguments: Map<*, *>): Map<String, Any?> {
    val projectName = (arguments["project"] as? String)?.trim().orEmpty()
    val limit = ((arguments["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
    val offset = ((arguments["offset"] as? Number)?.toInt() ?: 0).coerceAtLeast(0)
    var videos = finishedVideos(user)
    if (projectName.isNotEmpty()) {
        val owned = projects.values.filter { it.owner.id == user.id }
        val project = owned.firstOrNull { it.id == projectName || it.name.equals(projectName, true) || it.exportFolder.equals(projectName, true) }
        requireNotNull(project) { "There is no project called \"$projectName\". Projects: ${owned.joinToString { it.name }.ifEmpty { "none" }}." }
        videos = videos.filter { it.projectId == project.id }
    }
    return mapOf("total" to videos.size, "offset" to offset, "videos" to videos.drop(offset).take(limit).map(::videoDetails))
}

private fun searchTool(user: SignedInUser, arguments: Map<*, *>): Map<String, Any?> {
    val words = (arguments["query"] as? String).orEmpty().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    val matches = finishedVideos(user).filter { job ->
        val text = listOf(job.output.fileName.toString(), job.projectName.orEmpty(), job.reference.name, job.background.name, Instant.ofEpochMilli(job.finishedAt ?: job.queuedAt).toString().take(10))
            .joinToString(" ").lowercase(Locale.ROOT)
        words.all { it in text }
    }
    return mapOf("results" to matches.take(20).map { job -> mapOf("id" to job.id, "title" to job.output.fileName.toString(), "url" to downloadLink(job).first) })
}

private fun fetchTool(user: SignedInUser, arguments: Map<*, *>): Map<String, Any?> {
    val id = (arguments["id"] as? String)?.trim().orEmpty()
    val videos = finishedVideos(user)
    val job = videos.firstOrNull { it.id == id } ?: videos.filter { id.length >= 8 && it.id.startsWith(id) }.singleOrNull()
    requireNotNull(job) { "No finished video has the id \"$id\". Use search or list_videos to find ids." }
    val details = videoDetails(job)
    val settings = job.settings
    val text = listOfNotNull(
        "File: ${details["title"]}",
        "Project: ${job.projectName ?: "quick render (no project)"}",
        "Finished: ${details["finishedAt"]}",
        "Size: ${"%.1f".format(Locale.ROOT, (details["sizeBytes"] as Long) / 1_048_576.0)} MB",
        "Reference video: ${job.reference.name}",
        "Background video: ${job.background.name}",
        "Composition: outline ${settings.outlinePixels} px, person scale ${settings.scalePercent}%, left margin ${settings.horizontalPercent}%, bottom margin ${settings.bottomPercent}%, outline colour ${settings.outlineColor}",
        "Download (valid until ${details["downloadUrlExpiresAt"]}): ${details["downloadUrl"]}",
        (details["googleDriveUrl"] as String?)?.let { "Google Drive: $it" },
    ).joinToString("\n")
    return mapOf("id" to job.id, "title" to details["title"], "text" to text, "url" to details["downloadUrl"], "metadata" to details)
}

private fun videoDetails(job: RenderJob): Map<String, Any?> {
    val (url, expires) = downloadLink(job)
    fun number(value: String): Any = value.toIntOrNull() ?: value
    return mapOf(
        "id" to job.id,
        "title" to job.output.fileName.toString(),
        "project" to job.projectName,
        "finishedAt" to Instant.ofEpochMilli(job.finishedAt ?: job.queuedAt).toString(),
        "sizeBytes" to Files.size(job.output),
        "referenceVideo" to job.reference.name,
        "backgroundVideo" to job.background.name,
        "composition" to mapOf(
            "outlinePixels" to number(job.settings.outlinePixels), "scalePercent" to number(job.settings.scalePercent),
            "leftMarginPercent" to number(job.settings.horizontalPercent), "bottomMarginPercent" to number(job.settings.bottomPercent),
            "outlineColor" to job.settings.outlineColor,
        ),
        "downloadUrl" to url,
        "downloadUrlExpiresAt" to Instant.ofEpochSecond(expires).toString(),
        "googleDriveUrl" to job.driveUrl.takeIf { job.driveStatus == DriveStatus.UPLOADED },
    )
}

// ---- Signed download links --------------------------------------------------------------------------------------

private fun downloadLink(job: RenderJob): Pair<String, Long> {
    val expires = Instant.now().epochSecond + DOWNLOAD_LINK_SECONDS
    val name = URLEncoder.encode(job.output.fileName.toString(), StandardCharsets.UTF_8).replace("+", "%20")
    return "${publicBaseUrl()}/files/${job.id}/$name?expires=$expires&signature=${downloadSignature(job.id, expires)}" to expires
}

private fun downloadSignature(jobId: String, expires: Long) = hmac("jamal-download-v1|$jobId|$expires")

/** `/files/<render id>/<file name>?expires=…&signature=…`: works without a browser session until the link expires. */
internal fun downloadSignedFile(exchange: HttpExchange) {
    val jobId = exchange.requestURI.path.split('/').getOrNull(2).orEmpty()
    val query = queryValues(exchange.requestURI.rawQuery.orEmpty())
    val expires = query["expires"]?.toLongOrNull()
    val signature = query["signature"].orEmpty()
    val valid = expires != null && expires >= Instant.now().epochSecond &&
        MessageDigest.isEqual(downloadSignature(jobId, expires).toByteArray(StandardCharsets.UTF_8), signature.toByteArray(StandardCharsets.UTF_8))
    if (!valid) return respondText(exchange, 403, "This download link is invalid or has expired. Ask for a new link.")
    val job = jobs[jobId]?.takeIf { it.state == JobState.COMPLETE && Files.isRegularFile(it.output) }
        ?: return respondText(exchange, 404, "This video is no longer on the server.")
    val size = Files.size(job.output)
    exchange.responseHeaders.add("Content-Type", "video/mp4")
    exchange.responseHeaders.add("Content-Disposition", attachmentHeader(job.output.fileName.toString()))
    if (exchange.requestMethod == "HEAD") {
        exchange.responseHeaders.add("Content-Length", size.toString())
        exchange.sendResponseHeaders(200, -1)
        exchange.close()
        return
    }
    ActivityLog.info("download.link_used", "Video downloaded through a signed link", requestContext(exchange, mapOf("bytes" to size, "file" to job.output.fileName.toString(), "owner" to job.owner.email)).copy(jobId = job.id))
    exchange.sendResponseHeaders(200, size)
    Files.newInputStream(job.output).use { it.copyTo(exchange.responseBody) }
    exchange.close()
}

// ---- Minimal JSON (the project has no JSON library) ---------------------------------------------------------------

private fun toJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        for (character in value) when {
            character == '"' -> append("\\\"")
            character == '\\' -> append("\\\\")
            character == '\n' -> append("\\n")
            character == '\r' -> append("\\r")
            character == '\t' -> append("\\t")
            character < ' ' -> append("\\u%04x".format(character.code))
            else -> append(character)
        }
        append('"')
    }
    is Boolean, is Int, is Long -> value.toString()
    is Number -> value.toDouble().takeIf { it.isFinite() }?.toString() ?: "null"
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (key, item) -> toJson(key.toString()) + ":" + toJson(item) }
    is Iterable<*> -> value.joinToString(",", "[", "]") { toJson(it) }
    else -> toJson(value.toString())
}

/** Parses JSON into maps, lists, strings, longs, doubles, booleans and nulls. */
private class JsonReader(private val text: String) {
    private var index = 0

    fun read(): Any? = value().also { skipSpace(); require(index == text.length) { "Unexpected trailing characters" } }

    private fun value(): Any? {
        skipSpace()
        return when (val character = text.getOrNull(index)) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (character == '-' || character?.isDigit() == true) readNumber() else throw IllegalArgumentException("Unexpected character at $index")
        }
    }

    private fun readObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        index++
        skipSpace()
        if (text.getOrNull(index) == '}') return result.also { index++ }
        while (true) {
            skipSpace()
            val key = readString()
            skipSpace()
            expect(':')
            result[key] = value()
            skipSpace()
            if (text.getOrNull(index) == ',') index++ else return result.also { expect('}') }
        }
    }

    private fun readArray(): List<Any?> {
        val result = ArrayList<Any?>()
        index++
        skipSpace()
        if (text.getOrNull(index) == ']') return result.also { index++ }
        while (true) {
            result += value()
            skipSpace()
            if (text.getOrNull(index) == ',') index++ else return result.also { expect(']') }
        }
    }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (true) {
            val character = text.getOrNull(index++) ?: throw IllegalArgumentException("Unterminated string")
            when (character) {
                '"' -> return result.toString()
                '\\' -> when (val escape = text.getOrNull(index++)) {
                    'u' -> result.append(text.substring(index, index + 4).toInt(16).toChar()).also { index += 4 }
                    'n' -> result.append('\n')
                    'r' -> result.append('\r')
                    't' -> result.append('\t')
                    'b' -> result.append('\b')
                    'f' -> result.append('\u000C')
                    '"', '\\', '/' -> result.append(escape)
                    else -> throw IllegalArgumentException("Invalid escape at $index")
                }
                else -> result.append(character)
            }
        }
    }

    private fun readNumber(): Any {
        val start = index
        while (index < text.length && text[index] in "+-0123456789.eE") index++
        val number = text.substring(start, index)
        return number.toLongOrNull() ?: number.toDouble()
    }

    private fun literal(word: String, value: Any?): Any? {
        require(text.startsWith(word, index)) { "Unexpected character at $index" }
        index += word.length
        return value
    }

    private fun expect(character: Char) {
        require(text.getOrNull(index) == character) { "Expected '$character' at $index" }
        index++
    }

    private fun skipSpace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }
}
