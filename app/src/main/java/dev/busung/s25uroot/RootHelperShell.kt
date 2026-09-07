package dev.busung.s25uroot

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Synchronous client for the app-authenticated root-helper daemon created by
 * the v0266 UMH path. Post-root callers run on Dispatchers.IO, so this keeps
 * the existing helper protocol available without introducing Wireless ADB or
 * another privileged transport.
 */
internal object RootHelperShell {
    fun shell(context: Context, command: String): LocalAdbClient.ShellResult =
        execute(context, "-c", command)

    fun execute(context: Context, vararg arguments: String): LocalAdbClient.ShellResult {
        val helper = helperFile(context)
        if (!helper.isFile || !helper.canExecute()) {
            return LocalAdbClient.ShellResult(
                LocalAdbClient.UNKNOWN_SHELL_EXIT_CODE,
                "Root helper is unavailable: ${helper.absolutePath}",
            )
        }

        val process = runCatching {
            ProcessBuilder(listOf(helper.absolutePath) + arguments)
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            return LocalAdbClient.ShellResult(
                LocalAdbClient.UNKNOWN_SHELL_EXIT_CODE,
                error.message ?: error.javaClass.simpleName,
            )
        }

        val output = StringBuilder()
        val reader = Thread({
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count <= 0) break
                        output.append(buffer, 0, count)
                    }
                }
            }
        }, "rmg-root-helper-reader").apply {
            isDaemon = true
            start()
        }

        val finished = runCatching {
            process.waitFor(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)

        if (!finished) {
            process.destroy()
            runCatching { process.waitFor(250, TimeUnit.MILLISECONDS) }
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.waitFor(1, TimeUnit.SECONDS) }
        }
        runCatching { reader.join(1_000) }

        val code = if (finished) {
            runCatching { process.exitValue() }
                .getOrDefault(LocalAdbClient.UNKNOWN_SHELL_EXIT_CODE)
        } else {
            LocalAdbClient.UNKNOWN_SHELL_EXIT_CODE
        }
        val body = output.toString().trim()
        return LocalAdbClient.ShellResult(
            code,
            if (!finished && body.isBlank()) "Root helper command timed out" else body,
        )
    }

    private fun helperFile(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libcve43499root.so")

    private const val COMMAND_TIMEOUT_MILLIS = 15_000L
}
