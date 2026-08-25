package com.denniskrol.nativephpbackgroundtasks

import androidx.fragment.app.FragmentActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.bridge.BridgeError
import com.nativephp.mobile.bridge.LaravelEnvironment
import com.nativephp.mobile.bridge.PHPBridge
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Background tasks bridge plugin.
 *
 * Uses WorkManager periodic work to run tasks at the requested cadence.
 * Uses NativePHP's ephemeral runtime so WorkManager can execute commands when
 * Android starts the app in a background-only process.
 */
class BackgroundTasksPlugin {

    companion object {
        private const val TASK_TAG = "com.denniskrol.nativephpbackgroundtasks"
        private const val PREFERENCES = "com.denniskrol.nativephpbackgroundtasks.tasks"
        private const val TASKS_KEY = "tasks"
        private const val RUN_NOW_SUFFIX = ".run-now"
        private val taskIdPattern = Regex("[A-Za-z0-9._-]+")

        private data class Task(val id: String, val command: String, val intervalMinutes: Long, val constraints: Map<String, Any?>)

        private fun makeError(code: String, message: String): BridgeError {
            val ctor = BridgeError::class.java.getDeclaredConstructor(
                String::class.java,
                String::class.java
            )
            ctor.isAccessible = true
            return ctor.newInstance(code, message)
        }

        private fun buildConstraints(map: Map<String, Any?>): Constraints {
            val builder = Constraints.Builder()

            when (map["network"]) {
                "any" -> builder.setRequiredNetworkType(NetworkType.CONNECTED)
                "wifi" -> builder.setRequiredNetworkType(NetworkType.UNMETERED)
                else -> builder.setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            }

            if (map["charging"] == true) builder.setRequiresCharging(true)
            if (map["batteryNotLow"] == true) builder.setRequiresBatteryNotLow(true)
            if (map["storageNotLow"] == true) builder.setRequiresStorageNotLow(true)
            if (map["idle"] == true) builder.setRequiresDeviceIdle(true)

            return builder.build()
        }

        private fun parseTask(raw: Map<String, Any?>): Task? {
            val id = raw["id"]?.toString()?.takeIf { taskIdPattern.matches(it) } ?: return null
            val command = raw["command"]?.toString()?.trim()
                ?.takeIf {
                    it.isNotEmpty() && !it.contains('\n') && !it.contains('\r') &&
                        !it.contains('\'') && !it.contains('\\')
                }
                ?: return null
            val interval = (raw["intervalMinutes"] as? Number)?.toLong()?.takeIf { it >= 15 } ?: return null

            @Suppress("UNCHECKED_CAST")
            val constraints = raw["constraints"] as? Map<String, Any?> ?: emptyMap()

            return Task(id, command, interval, constraints)
        }

        private fun parseTasks(value: Any?): List<Task> {
            val array = value as? JSONArray ?: return emptyList()

            return buildList {
                for (index in 0 until array.length()) {
                    val raw = array.optJSONObject(index)
                        ?: throw IllegalArgumentException("Each task must be an object.")
                    val constraints = raw.optJSONObject("constraints")
                    add(parseTask(mapOf(
                        "id" to raw.opt("id"),
                        "command" to raw.opt("command"),
                        "intervalMinutes" to raw.opt("intervalMinutes"),
                        "constraints" to buildMap {
                            constraints?.let { json ->
                                val keys = json.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    put(key, json.opt(key))
                                }
                            }
                        },
                    )) ?: throw IllegalArgumentException("Each task needs a valid id, command, and interval of at least 15 minutes."))
                }
            }
        }

        private fun jsonMap(objectValue: JSONObject?): Map<String, Any?> = buildMap {
            objectValue?.let { json ->
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, json.opt(key))
                }
            }
        }

        private fun inputData(task: Task) = workDataOf("command" to task.command, "taskId" to task.id)

        private fun loadTasks(context: android.content.Context): List<Task> {
            val serialized = context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
                .getString(TASKS_KEY, "[]")
                ?: "[]"

            return try {
                val array = JSONArray(serialized)
                buildList {
                    for (index in 0 until array.length()) {
                        val objectTask = array.getJSONObject(index)
                        val id = objectTask.optString("id")
                        val command = objectTask.optString("command")
                        val interval = objectTask.optLong("intervalMinutes")
                        if (taskIdPattern.matches(id) && command.isNotBlank() && interval >= 15) {
                            add(Task(id, command, interval, jsonMap(objectTask.optJSONObject("constraints"))))
                        }
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun saveTasks(context: android.content.Context, tasks: List<Task>) {
            val array = JSONArray()
            tasks.forEach { task ->
                array.put(JSONObject().apply {
                    put("id", task.id)
                    put("command", task.command)
                    put("intervalMinutes", task.intervalMinutes)
                    put("constraints", JSONObject(task.constraints))
                })
            }

            context.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(TASKS_KEY, array.toString())
                .apply()
        }

        private fun removeTask(context: android.content.Context, taskId: String) {
            saveTasks(context, loadTasks(context).filterNot { it.id == taskId })
        }
    }

    class Register(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val tasks = parseTasks(parameters["tasks"])
                val duplicateIds = tasks.groupingBy { it.id }.eachCount().filterValues { it > 1 }
                require(duplicateIds.isEmpty()) { "Task IDs must be unique." }

                val workManager = WorkManager.getInstance(activity)
                val existingTasks = loadTasks(activity)
                val existingTaskIds = existingTasks.map { it.id }.toSet()
                val existingTasksById = existingTasks.associateBy { it.id }
                val registeredTaskIds = tasks.map { it.id }.toSet()

                tasks.forEach { task ->
                    if (existingTasksById[task.id] == task) {
                        return@forEach
                    }

                    val request = PeriodicWorkRequestBuilder<BackgroundTasksWorker>(
                        task.intervalMinutes, TimeUnit.MINUTES
                    )
                        .setConstraints(buildConstraints(task.constraints))
                        .addTag(TASK_TAG)
                        .addTag(task.id)
                        .setInputData(inputData(task))
                        .build()

                    workManager.enqueueUniquePeriodicWork(
                        task.id,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request
                    )
                    android.util.Log.i("BackgroundTasks", "Queued periodic task ${task.id} as ${request.id}")
                }

                (existingTaskIds - registeredTaskIds).forEach { taskId ->
                    workManager.cancelUniqueWork(taskId)
                    workManager.cancelUniqueWork(taskId + RUN_NOW_SUFFIX)
                }
                saveTasks(activity, tasks)
                android.util.Log.i("BackgroundTasks", "Registered ${tasks.size} background task(s): ${tasks.joinToString { it.id }}")

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "registered" to tasks.size
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    makeError("BG_TASKS_REGISTER_ERROR", e.message ?: "Failed to register background tasks.")
                )
            }
        }
    }

    class RunNow(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val tasks = loadTasks(activity)
                val workManager = WorkManager.getInstance(activity)

                tasks.forEach { task ->
                    val request = OneTimeWorkRequestBuilder<BackgroundTasksWorker>()
                        .addTag(TASK_TAG)
                        .addTag(task.id)
                        .setInputData(inputData(task))
                        .build()

                    workManager.enqueueUniqueWork(
                        task.id + RUN_NOW_SUFFIX,
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
                }
                android.util.Log.i("BackgroundTasks", "Dispatched ${tasks.size} background task(s) for immediate execution")

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "triggered" to tasks.size,
                        "message" to "RunNow dispatched (testing only)."
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    makeError("BG_TASKS_RUN_NOW_ERROR", e.message ?: "Failed to dispatch background tasks.")
                )
            }
        }
    }

    class Cancel(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val taskId = parameters["taskId"]?.toString()
                    ?: return BridgeResponse.error(
                        makeError("BG_TASKS_BAD_ID", "taskId is required.")
                    )

                WorkManager.getInstance(activity).cancelUniqueWork(taskId)
                WorkManager.getInstance(activity).cancelUniqueWork(taskId + RUN_NOW_SUFFIX)
                removeTask(activity, taskId)

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "taskId" to taskId
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    makeError("BG_TASKS_CANCEL_ERROR", e.message ?: "Failed to cancel task.")
                )
            }
        }
    }

    class GetRegistered(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                val workManager = WorkManager.getInstance(activity)
                val states = workManager.getWorkInfosByTag(TASK_TAG).get()
                    .groupBy { info -> info.tags.firstOrNull { tag -> tag.startsWith("com.denniskrol.nativephp.task.") } }

                val tasks = loadTasks(activity).map { task ->
                    val state = states[task.id]?.firstOrNull()?.state?.name ?: "NOT_SCHEDULED"
                    mapOf<String, Any>(
                        "id" to task.id,
                        "command" to task.command,
                        "state" to state
                    )
                }

                BridgeResponse.success(
                    mapOf<String, Any>(
                        "success" to true,
                        "tasks" to tasks
                    )
                )
            } catch (e: Exception) {
                BridgeResponse.error(
                    makeError("BG_TASKS_LIST_ERROR", e.message ?: "Failed to list registered tasks.")
                )
            }
        }
    }
}

/**
 * Executes a bundled artisan command from WorkManager, including cold starts.
 */
class BackgroundTasksWorker(
    appContext: android.content.Context,
    workerParams: androidx.work.WorkerParameters
) : androidx.work.Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val command = inputData.getString("command")
        val taskId = inputData.getString("taskId")

        android.util.Log.i("BackgroundTasks", "Worker invoked with taskId=$taskId command=$command")

        if (command == null || taskId == null) {
            android.util.Log.e("BackgroundTasks", "Background task has missing input data")
            return Result.failure()
        }

        return try {
            android.util.Log.i("BackgroundTasks", "Starting background task $taskId: $command")
            // WorkManager has no activity, so register only context-safe plugin bridges.
            com.nativephp.mobile.bridge.plugins.registerContextOnlyBridgeFunctions(applicationContext)
            LaravelEnvironment(applicationContext).initializeForBackground()
            val bridge = PHPBridge(applicationContext)
            val bootstrapPath = "${bridge.getLaravelPath()}/vendor/nativephp/mobile/bootstrap/android/persistent.php"

            if (bridge.nativeEphemeralBoot(bootstrapPath) != 0) {
                android.util.Log.e("BackgroundTasks", "Failed to boot PHP for background task $taskId")
                return Result.retry()
            }

            val output = bridge.nativeEphemeralArtisan(command)
            if (output.contains("Ephemeral artisan error:")) {
                android.util.Log.e("BackgroundTasks", "Background task $taskId failed: $output")
                Result.retry()
            } else {
                android.util.Log.i("BackgroundTasks", "Completed background task $taskId: $output")
                Result.success()
            }
        } catch (e: Exception) {
            android.util.Log.e("BackgroundTasks", "Background task $taskId failed", e)
            Result.retry()
        } finally {
            try {
                PHPBridge(applicationContext).nativeEphemeralShutdown()
            } catch (e: Exception) {
                android.util.Log.e("BackgroundTasks", "Failed to shut down background task $taskId", e)
            }
        }
    }
}
