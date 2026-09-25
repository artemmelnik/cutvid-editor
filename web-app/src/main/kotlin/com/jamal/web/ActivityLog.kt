package com.jamal.web

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Comparator

/**
 * Persistent newline-delimited JSON activity log. Sensitive fields are
 * redacted defensively, and a new file is created each UTC day.
 */
object ActivityLog {
    private const val RETENTION_DAYS = 30L
    private val lock = Any()
    private var lastPrunedDate: LocalDate? = null

    fun debug(action: String, message: String, context: LogContext = LogContext()) =
        write("DEBUG", action, message, context)

    fun info(action: String, message: String, context: LogContext = LogContext()) =
        write("INFO", action, message, context)

    fun warn(action: String, message: String, context: LogContext = LogContext()) =
        write("WARN", action, message, context)

    fun error(action: String, message: String, error: Throwable? = null, context: LogContext = LogContext()) {
        val details = context.details.toMutableMap()
        error?.let {
            details["exception"] = it::class.qualifiedName ?: it::class.simpleName
            details["stackTrace"] = it.stackTraceToString().take(16_000)
        }
        write("ERROR", action, message, context.copy(details = details))
    }

    fun recentJson(limit: Int): String = synchronized(lock) {
        val safeLimit = limit.coerceIn(20, 1000)
        val lines = logFiles().asReversed().asSequence()
            .flatMap { path ->
                try { Files.readAllLines(path, StandardCharsets.UTF_8).asReversed().asSequence() }
                catch (_: Exception) { emptySequence() }
            }
            .filter { it.isNotBlank() }
            .take(safeLimit)
            .toList()
            .asReversed()
        lines.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    fun eventCount(): Long = synchronized(lock) {
        logFiles().sumOf { path ->
            try { Files.lines(path).use { it.count() } }
            catch (_: Exception) { 0L }
        }
    }

    fun currentFile(): Path = logDirectory().resolve("activity-${LocalDate.now(ZoneOffset.UTC)}.ndjson")

    private fun write(level: String, action: String, message: String, context: LogContext) = synchronized(lock) {
        try {
            pruneIfNeeded()
            val now = Instant.now()
            val detailsJson = context.details.entries.joinToString(",") { (key, value) ->
                val safeValue = if (key.contains(Regex("token|password|authorization", RegexOption.IGNORE_CASE))) "[REDACTED]" else value
                "\"${key.logJsonEscape()}\":${safeValue.toJsonValue()}"
            }
            val line = buildString {
                append("{\"timestamp\":\"").append(DateTimeFormatter.ISO_INSTANT.format(now)).append("\"")
                append(",\"epochMs\":").append(now.toEpochMilli())
                append(",\"level\":\"").append(level).append("\"")
                append(",\"action\":\"").append(action.logJsonEscape()).append("\"")
                append(",\"message\":\"").append(message.logJsonEscape()).append("\"")
                context.device?.let { append(",\"device\":\"").append(it.logJsonEscape()).append("\"") }
                context.remote?.let { append(",\"remote\":\"").append(it.logJsonEscape()).append("\"") }
                context.jobId?.let { append(",\"jobId\":\"").append(it.logJsonEscape()).append("\"") }
                append(",\"details\":{").append(detailsJson).append("}}\n")
            }
            Files.writeString(
                currentFile(), line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
            val console = "[$level] $action: $message"
            if (level == "ERROR" || level == "WARN") System.err.println(console) else println(console)
        } catch (loggingError: Exception) {
            System.err.println("[ERROR] activity.log_write_failed: ${loggingError.message}")
        }
    }

    private fun pruneIfNeeded() {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (lastPrunedDate == today) return
        lastPrunedDate = today
        val cutoff = today.minusDays(RETENTION_DAYS)
        logFiles().forEach { path ->
            val date = Regex("activity-(\\d{4}-\\d{2}-\\d{2})\\.ndjson")
                .matchEntire(path.fileName.toString())?.groupValues?.get(1)?.let(LocalDate::parse)
            if (date != null && date.isBefore(cutoff)) Files.deleteIfExists(path)
        }
    }

    private fun logFiles(): List<Path> {
        val directory = logDirectory()
        return Files.list(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().matches(Regex("activity-\\d{4}-\\d{2}-\\d{2}\\.ndjson")) }
                .sorted(Comparator.comparing { it.fileName.toString() })
                .toList()
        }
    }

    // Next to jobs and uploads, so a Docker deployment keeps the log in its data volume.
    private fun logDirectory(): Path = jamalDirectory().resolve("logs").also(Files::createDirectories)
}

data class LogContext(
    val device: String? = null,
    val remote: String? = null,
    val jobId: String? = null,
    val details: Map<String, Any?> = emptyMap(),
)

private fun Any?.toJsonValue(): String = when (this) {
    null -> "null"
    is Number, is Boolean -> toString()
    else -> "\"${toString().logJsonEscape()}\""
}

private fun String.logJsonEscape(): String = replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r")
    .replace("\t", "\\t")

