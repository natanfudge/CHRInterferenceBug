package foo.chrshutdownbug

import androidx.compose.material.Button
import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.gradle.tooling.BuildLauncher
import org.gradle.tooling.GradleConnectionException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.ResultHandler
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.UncheckedIOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.coroutines.resume

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "chrshutdownbug",
    ) {
        Button(onClick = {
            GlobalScope.launch {
                gradlew(Paths.get("../demoProject"), "hotRunJvm")
            }
        }) {
            Text("Run Run")
        }
    }
}

suspend fun gradlew(dir: Path, task: String, arguments: List<String> = listOf(), onLine:  (String) -> Unit = {}) = coroutineScope {

    val pin = PipedInputStream()
    val pout = withContext(Dispatchers.IO) {
        PipedOutputStream(pin)
    }

    val readOutputJob = launch(Dispatchers.IO) {
        pin.reader().buffered().use {
            try {
                it.lines().forEach { line ->
                    println(line)
                    onLine(line)
                }
            } catch (e: UncheckedIOException) {
                // Pipe was closed, we are done.
            }

        }

    }

    val cts = GradleConnector.newCancellationTokenSource()
    val conn: ProjectConnection = GradleConnector.newConnector()
        .forProjectDirectory(dir.toFile())
        .connect()
    try {
        println("Starting Gradle task $task")
        val launcher: BuildLauncher = conn.newBuild()
            .forTasks(task)
            .withArguments(*arguments.toTypedArray())
            .withCancellationToken(cts.token())
            .setStandardOutput(pout)
            .setStandardError(pout)
            .setColorOutput(false)

        launcher.runSuspend()
        readOutputJob.cancel()
    } finally {
        cts.cancel()
        conn.close()
        pin.close()
        pout.close()
    }
}
private suspend fun BuildLauncher.runSuspend(): Void? = suspendCancellableCoroutine {
    run(object : ResultHandler<Void?> {
        override fun onComplete(result: Void?) {
            it.resume(result)
        }

        override fun onFailure(e: GradleConnectionException) {
            it.cancel(e)
        }
    })
}
