package com.jamal.web

import com.sun.net.httpserver.HttpExchange
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
import kotlin.random.Random

/*
 * A project keeps a folder of reference videos and a folder of background videos on this
 * server. Every video it generates draws its own random reference/background pair and its own
 * composition values from the project's from–to ranges.
 */

private const val MAX_PROJECT_OUTPUTS = 100
private const val MAX_FORM_BYTES = 64 * 1024
private val projectPools = listOf("reference", "background")
internal val projects = ConcurrentHashMap<String, Project>()

/** An inclusive from–to range; each generated video picks its own value from it. */
internal data class SettingRange(val min: Int, val max: Int) {
    fun pick(random: Random) = random.nextInt(min, max + 1)
}

internal data class ProjectSettings(
    val outputCount: Int,
    val outlinePixels: SettingRange,
    val scalePercent: SettingRange,
    val horizontalPercent: SettingRange,
    val bottomPercent: SettingRange,
    val outlineColorFrom: String,
    val outlineColorTo: String,
    val driveFolderId: String? = null,
    val driveFolderName: String? = null,
)

internal class Project(
    val id: String,
    val owner: SignedInUser,
    val createdAt: Long,
    @Volatile var name: String,
    @Volatile var settings: ProjectSettings,
) {
    val references = CopyOnWriteArrayList<UploadedFile>()
    val backgrounds = CopyOnWriteArrayList<UploadedFile>()
    /** Subfolder of the exports folder that receives this project's renders; see [projectExportFolder]. */
    @Volatile var exportFolder: String? = null
    fun pool(name: String): MutableList<UploadedFile> = if (name == "reference") references else backgrounds
}

internal fun listProjects(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    val projectJson = projects.values.filter { it.owner.id == user.id }
        .sortedByDescending { it.createdAt }
        .joinToString(",") { projectJson(it) }
    respondJson(exchange, 200, "{\"projects\":[$projectJson]}")
}

internal fun createProject(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    projectRequest(exchange) {
        val fields = formFields(exchange)
        val project = Project(UUID.randomUUID().toString(), user, System.currentTimeMillis(), projectName(fields), projectSettings(fields, user, null))
        projects[project.id] = project
        projectExportFolder(project)
        ActivityLog.info("project.created", "Project created", requestContext(exchange, mapOf("project" to project.name, "owner" to user.email)))
        respondJson(exchange, 201, "{\"project\":${projectJson(project)}}")
    }
}

internal fun routeProject(exchange: HttpExchange) {
    val user = requireSignedInUser(exchange) ?: return
    val parts = exchange.requestURI.path.removePrefix("/api/projects/").split('/')
    val project = projects[parts[0]]?.takeIf { it.owner.id == user.id }
        ?: return respondJson(exchange, 404, "{\"error\":\"Unknown project\"}")
    projectRequest(exchange) {
        when (exchange.requestMethod to parts.getOrNull(1)) {
            "PUT" to null -> updateProject(exchange, project)
            "DELETE" to null -> deleteProject(exchange, project)
            "POST" to "videos" -> addProjectVideo(exchange, project)
            "DELETE" to "videos" -> clearProjectVideos(exchange, project)
            "POST" to "generate" -> generateProject(exchange, project)
            else -> respondText(exchange, 404, "Not found")
        }
    }
}

private inline fun projectRequest(exchange: HttpExchange, handle: () -> Unit) {
    try {
        handle()
    } catch (error: Exception) {
        val context = requestContext(exchange, mapOf("method" to exchange.requestMethod, "path" to exchange.requestURI.path))
        // Validation messages and unreachable Drive folders are expected; anything else is logged with its stack trace.
        if (error is IllegalArgumentException || error is IllegalStateException) ActivityLog.warn("project.request_rejected", error.message ?: "Invalid project request", context)
        else ActivityLog.error("project.request_failed", error.message ?: "Project request failed", error, context)
        respondJson(exchange, 400, "{\"error\":\"${(error.message ?: "Invalid project request").jsonEscape()}\"}")
    }
}

private fun updateProject(exchange: HttpExchange, project: Project) {
    val fields = formFields(exchange)
    val name = projectName(fields)
    project.settings = projectSettings(fields, project.owner, project.settings)
    project.name = name
    persistProject(project)
    ActivityLog.info("project.updated", "Project settings saved", requestContext(exchange, mapOf("project" to project.name, "owner" to project.owner.email)))
    respondJson(exchange, 200, "{\"project\":${projectJson(project)}}")
}

private fun deleteProject(exchange: HttpExchange, project: Project) {
    requireIdle(project, "deleting it")
    removeProject(project)
    ActivityLog.info("project.deleted", "Project and its uploaded videos deleted", requestContext(exchange, mapOf("project" to project.name, "owner" to project.owner.email)))
    respondJson(exchange, 200, "{\"deleted\":true}")
}

/** Streams one video of a chosen folder straight to disk; the browser sends a folder one file per request. */
private fun addProjectVideo(exchange: HttpExchange, project: Project) {
    val query = queryValues(exchange.requestURI.rawQuery.orEmpty())
    val pool = poolName(query["pool"])
    val name = cleanName(File(query["name"].orEmpty()).name)
    require(name.extension() in supportedExtensions) { "${name.ifEmpty { "This file" }} is not a supported video." }
    val directory = projectDirectory(project.id).resolve(pool).also(Files::createDirectories)
    val stored = Files.createTempFile(directory, "", "-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(120))
    try {
        Files.copy(exchange.requestBody, stored, StandardCopyOption.REPLACE_EXISTING)
        require(Files.size(stored) > 0) { "$name is empty." }
        require(projects[project.id] === project) { "This project was deleted." }
    } catch (error: Exception) {
        Files.deleteIfExists(stored)
        throw error
    }
    project.pool(pool) += UploadedFile(name, stored)
    persistProject(project)
    ActivityLog.info("project.video_added", "Video added to project", requestContext(exchange, mapOf("project" to project.name, "pool" to pool, "file" to name, "bytes" to Files.size(stored))))
    respondJson(exchange, 201, "{\"project\":${projectJson(project)}}")
}

private fun clearProjectVideos(exchange: HttpExchange, project: Project) {
    val pool = poolName(queryValues(exchange.requestURI.rawQuery.orEmpty())["pool"])
    requireIdle(project, "replacing its videos")
    val removed = project.pool(pool).toList()
    project.pool(pool).removeAll(removed)
    persistProject(project)
    removed.forEach { Files.deleteIfExists(it.path) }
    ActivityLog.info("project.videos_cleared", "Project folder emptied before a replacement upload", requestContext(exchange, mapOf("project" to project.name, "pool" to pool, "removed" to removed.size)))
    respondJson(exchange, 200, "{\"project\":${projectJson(project)}}")
}

private fun generateProject(exchange: HttpExchange, project: Project) {
    val count = formFields(exchange)["count"]?.trim()?.let { it.toIntOrNull() ?: 0 } ?: project.settings.outputCount
    require(count in 1..MAX_PROJECT_OUTPUTS) { "Videos to generate must be between 1 and $MAX_PROJECT_OUTPUTS." }
    val references = project.references.toList()
    val backgrounds = project.backgrounds.toList()
    require(references.isNotEmpty()) { "Add a folder of reference videos to this project first." }
    require(backgrounds.isNotEmpty()) { "Add a folder of background videos to this project first." }
    var settings = project.settings.copy(outputCount = count)
    settings.driveFolderId?.let { folderId ->
        require(driveAvailable(project.owner) && driveConnected(project.owner)) {
            "Connect Google Drive at the top of the page, or clear this project's Drive link to keep its videos on this server."
        }
        settings = settings.copy(driveFolderName = driveFolderName(project.owner, folderId))
    }
    project.settings = settings
    persistProject(project)

    val random = Random.Default
    val created = randomPairs(references, backgrounds, count, random).map { (reference, background) ->
        val renderSettings = RenderSettings(
            outlinePixels = settings.outlinePixels.pick(random).toString(),
            scalePercent = settings.scalePercent.pick(random).toString(),
            horizontalPercent = settings.horizontalPercent.pick(random).toString(),
            bottomPercent = settings.bottomPercent.pick(random).toString(),
            outlineColor = randomColor(settings.outlineColorFrom, settings.outlineColorTo, random),
        )
        queueRender(exchange, project.owner, reference, background, renderSettings, project)
    }
    ActivityLog.info("project.generated", "Project renders queued", requestContext(exchange, mapOf("project" to project.name, "jobs" to created.size, "owner" to project.owner.email, "driveFolderId" to settings.driveFolderId)))
    respondJson(exchange, 202, "{\"jobs\":[${created.joinToString(",") { renderJson(it) }}]}")
}

private fun requireIdle(project: Project, action: String) = require(
    jobs.values.none { it.projectId == project.id && (it.state == JobState.QUEUED || it.state == JobState.RENDERING) },
) { "Wait for this project's renders to finish before $action." }

/** Project forms are small URL-encoded bodies; a larger one is refused instead of being read into memory. */
private fun formFields(exchange: HttpExchange): Map<String, String> {
    val body = exchange.requestBody.readNBytes(MAX_FORM_BYTES + 1)
    require(body.size <= MAX_FORM_BYTES) { "The form is too large." }
    return queryValues(String(body, StandardCharsets.UTF_8))
}
private fun poolName(value: String?) = value?.takeIf { it in projectPools } ?: throw IllegalArgumentException("Unknown video folder.")
private fun cleanName(value: String) = value.replace(Regex("\\p{Cntrl}"), " ").replace(Regex("\\s+"), " ").trim()
private fun projectName(fields: Map<String, String>) = cleanName(fields["name"].orEmpty()).take(80).also { require(it.isNotEmpty()) { "Give the project a name." } }

private fun projectSettings(fields: Map<String, String>, user: SignedInUser, previous: ProjectSettings?): ProjectSettings {
    val count = fields["count"]?.trim()?.toIntOrNull()
    require(count != null && count in 1..MAX_PROJECT_OUTPUTS) { "Videos to generate must be between 1 and $MAX_PROJECT_OUTPUTS." }
    val folderId = driveFolderId(fields["driveLink"].orEmpty())
    val folderName = when {
        folderId == null -> null
        !driveAvailable(user) -> throw IllegalArgumentException("Saving to Google Drive needs Google sign-in, which is not configured on this server.")
        previous != null && previous.driveFolderId == folderId && previous.driveFolderName != null -> previous.driveFolderName
        // Without a Drive connection the link cannot be checked yet; generating checks it again.
        !driveConnected(user) -> null
        else -> driveFolderName(user, folderId)
    }
    return ProjectSettings(
        outputCount = count,
        outlinePixels = settingRange(fields, "outline", "Outline", 1..200),
        scalePercent = settingRange(fields, "scale", "Person scale", 5..200),
        horizontalPercent = settingRange(fields, "left", "Left margin", 0..99),
        bottomPercent = settingRange(fields, "bottom", "Bottom margin", 0..99),
        outlineColorFrom = outlineColor(fields["colorFrom"]),
        outlineColorTo = outlineColor(fields["colorTo"]),
        driveFolderId = folderId,
        driveFolderName = folderName,
    )
}

private fun settingRange(fields: Map<String, String>, key: String, label: String, bounds: IntRange): SettingRange {
    val from = fields["${key}Min"]?.trim()?.toIntOrNull()
    val to = fields["${key}Max"]?.trim()?.toIntOrNull()
    require(from != null && to != null && from in bounds && to in bounds) { "$label must be whole numbers from ${bounds.first} to ${bounds.last}." }
    return SettingRange(minOf(from, to), maxOf(from, to))
}

private fun outlineColor(value: String?): String {
    val color = value?.trim().orEmpty()
    require(color.matches(Regex("#[0-9A-Fa-f]{6}"))) { "Outline colours must be hex colours such as #FFFFFF." }
    return color.uppercase()
}

/** A random colour on the straight line between the two ends of the outline-colour range. */
private fun randomColor(from: String, to: String, random: Random): String {
    val start = from.removePrefix("#").toInt(16)
    val end = to.removePrefix("#").toInt(16)
    val position = random.nextDouble()
    return "#" + listOf(16, 8, 0).joinToString("") { shift ->
        val first = start shr shift and 0xFF
        val last = end shr shift and 0xFF
        "%02X".format((first + (last - first) * position).roundToInt())
    }
}

/**
 * Every video of a folder is used once, in random order, before any video of that folder
 * repeats. A background that would recreate an already generated pair is skipped while the
 * current round still has another one.
 */
private fun randomPairs(references: List<UploadedFile>, backgrounds: List<UploadedFile>, count: Int, random: Random): List<Pair<UploadedFile, UploadedFile>> {
    val referenceBag = ShuffleBag(references, random)
    val backgroundBag = ShuffleBag(backgrounds, random)
    val used = HashSet<Pair<Path, Path>>()
    return List(count) {
        if (used.size.toLong() >= references.size.toLong() * backgrounds.size) used.clear()
        val reference = referenceBag.next()
        val background = backgroundBag.next { (reference.path to it.path) !in used }
        used += reference.path to background.path
        reference to background
    }
}

private class ShuffleBag<T>(private val items: List<T>, private val random: Random) {
    private val round = mutableListOf<T>()
    fun next(prefer: (T) -> Boolean = { true }): T {
        if (round.isEmpty()) round += items.shuffled(random)
        return round.removeAt(round.indexOfFirst(prefer).coerceAtLeast(0))
    }
}

private fun projectJson(project: Project): String {
    val settings = project.settings
    fun names(pool: List<UploadedFile>) = pool.joinToString(",", "[", "]") { "\"${it.name.jsonEscape()}\"" }
    return """{"id":"${project.id}","name":"${project.name.jsonEscape()}","createdAt":${project.createdAt},"count":${settings.outputCount},"outlineMin":${settings.outlinePixels.min},"outlineMax":${settings.outlinePixels.max},"scaleMin":${settings.scalePercent.min},"scaleMax":${settings.scalePercent.max},"leftMin":${settings.horizontalPercent.min},"leftMax":${settings.horizontalPercent.max},"bottomMin":${settings.bottomPercent.min},"bottomMax":${settings.bottomPercent.max},"colorFrom":"${settings.outlineColorFrom}","colorTo":"${settings.outlineColorTo}","driveFolderId":${settings.driveFolderId.jsonOrNull()},"driveFolderName":${settings.driveFolderName.jsonOrNull()},"driveLink":${settings.driveFolderId?.let(::driveFolderLink).jsonOrNull()},"exportFolder":${project.exportFolder.jsonOrNull()},"references":${names(project.references)},"backgrounds":${names(project.backgrounds)}}"""
}

/**
 * The project's own folder inside the exports folder. It is chosen once, from the project name, and kept when the
 * project is renamed so a project's videos never end up split across folders. Names are made unique across
 * projects (ignoring case, for case-insensitive disks) and limited to characters every disk and the renderer accept.
 */
@Synchronized internal fun projectExportFolder(project: Project): String {
    project.exportFolder?.let { return it }
    val taken = projects.values.filter { it !== project }.mapNotNull { it.exportFolder?.lowercase() }.toSet()
    val base = safeFileName(project.name)
    val folder = (sequenceOf(base) + generateSequence(2) { it + 1 }.map { "$base ($it)" }).first { it.lowercase() !in taken }
    project.exportFolder = folder
    persistProject(project)
    return folder
}

/**
 * Letters (any alphabet), digits, spaces and `._()-` only: no path separators, quotes or `%` patterns. Characters
 * the JVM cannot write in file names (a server started without a UTF-8 locale) become `_` as well.
 */
internal fun safeFileName(value: String): String {
    val fileNames = Charset.forName(System.getProperty("sun.jnu.encoding") ?: "UTF-8").newEncoder()
    return value.replace(Regex("[^\\p{L}\\p{M}\\p{N} ._()-]"), "_").map { if (fileNames.canEncode(it)) it else '_' }.joinToString("")
        .replace(Regex("\\s+"), " ").trim(' ', '.').take(80).trimEnd(' ', '.').ifEmpty { "Project" }
}

private fun projectsDirectory(): Path = jamalDirectory().resolve("projects").also(Files::createDirectories)
private fun projectDirectory(id: String): Path = projectsDirectory().resolve(id)

@Synchronized private fun persistProject(project: Project) {
    // A request that finishes after the project was deleted must not bring it back.
    if (projects[project.id] !== project) return
    val values = Properties()
    values["id"] = project.id; values["ownerId"] = project.owner.id; values["ownerEmail"] = project.owner.email
    values["name"] = project.name; values["createdAt"] = project.createdAt.toString()
    with(project.settings) {
        values["count"] = outputCount.toString()
        values["outlineMin"] = outlinePixels.min.toString(); values["outlineMax"] = outlinePixels.max.toString()
        values["scaleMin"] = scalePercent.min.toString(); values["scaleMax"] = scalePercent.max.toString()
        values["leftMin"] = horizontalPercent.min.toString(); values["leftMax"] = horizontalPercent.max.toString()
        values["bottomMin"] = bottomPercent.min.toString(); values["bottomMax"] = bottomPercent.max.toString()
        values["colorFrom"] = outlineColorFrom; values["colorTo"] = outlineColorTo
        values["driveFolderId"] = driveFolderId.orEmpty(); values["driveFolderName"] = driveFolderName.orEmpty()
    }
    values["exportFolder"] = project.exportFolder.orEmpty()
    for (pool in projectPools) project.pool(pool).forEach { values["video.$pool.${it.path.fileName}"] = it.name }
    val directory = projectDirectory(project.id).also(Files::createDirectories)
    val target = directory.resolve("project.properties")
    val temporary = Files.createTempFile(directory, "project-", ".tmp")
    Files.newOutputStream(temporary).use { values.store(it, "Jamal project") }
    try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
    catch (_: Exception) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING) }
}

@Synchronized private fun removeProject(project: Project) {
    projects.remove(project.id, project)
    projectDirectory(project.id).toFile().deleteRecursively()
}

internal fun loadPersistedProjects() {
    Files.list(projectsDirectory()).use { directories -> directories.filter(Files::isDirectory).forEach { directory ->
        val file = directory.resolve("project.properties")
        if (!Files.isRegularFile(file)) return@forEach
        try {
            val values = Properties().also { Files.newInputStream(file).use(it::load) }
            val id = directory.fileName.toString()
            require(values.getProperty("id") == id) { "project id does not match its folder" }
            fun number(key: String, fallback: Int) = values.getProperty(key)?.toIntOrNull() ?: fallback
            fun range(key: String, fallback: Int) = SettingRange(number("${key}Min", fallback), number("${key}Max", fallback))
            val settings = ProjectSettings(
                number("count", 5), range("outline", 12), range("scale", 46), range("left", 2), range("bottom", 0),
                values.getProperty("colorFrom", "#FFFFFF"), values.getProperty("colorTo", "#FFFFFF"),
                values.getProperty("driveFolderId")?.ifBlank { null }, values.getProperty("driveFolderName")?.ifBlank { null },
            )
            val owner = SignedInUser(values.getProperty("ownerId") ?: return@forEach, values.getProperty("ownerEmail").orEmpty())
            val project = Project(id, owner, values.getProperty("createdAt")?.toLongOrNull() ?: 0L, values.getProperty("name", "Project"), settings)
            project.exportFolder = values.getProperty("exportFolder")?.ifBlank { null }
            for (pool in projectPools) {
                project.pool(pool) += values.stringPropertyNames()
                    .filter { it.startsWith("video.$pool.") }
                    .mapNotNull { key ->
                        val storedName = key.removePrefix("video.$pool.")
                        val path = directory.resolve(pool).resolve(storedName)
                        if (storedName.contains('/') || storedName.startsWith('.') || !Files.isRegularFile(path)) null
                        else UploadedFile(values.getProperty(key), path)
                    }
                    .sortedBy { it.name.lowercase() }
            }
            projects[id] = project
        } catch (error: Exception) {
            ActivityLog.warn("projects.restore_failed", "Could not restore project ${directory.fileName}: ${error.message}")
        }
    } }
    // Projects created before export folders existed get theirs now, oldest first.
    projects.values.sortedBy { it.createdAt }.forEach { projectExportFolder(it) }
}
