package com.projectmata.mobilebackgroundtasks

import androidx.fragment.app.FragmentActivity
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nativephp.mobile.bridge.BridgeFunction
import com.nativephp.mobile.bridge.BridgeResponse
import com.nativephp.mobile.bridge.BridgeError
import java.util.concurrent.TimeUnit

/**
 * Background tasks bridge plugin.
 *
 * Uses WorkManager periodic work to run tasks at the requested cadence.
 * The actual execution of each `command` (e.g. `sync:data`) must be wired
 * up in your app's `BackgroundTasksWorker` — see the TODO blocks below.
 */
class BackgroundTasksPlugin {

    companion object {
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
    }

    class Register(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            return try {
                @Suppress("UNCHECKED_CAST")
                val tasks = parameters["tasks"] as? List<Map<String, Any>> ?: emptyList()

                val workManager = WorkManager.getInstance(activity)

                tasks.forEach { task ->
                    val taskId = task["id"]?.toString() ?: return@forEach
                    val command = task["command"]?.toString() ?: return@forEach
                    val intervalMinutes = (task["intervalMinutes"] as? Number)?.toLong() ?: 15L

                    @Suppress("UNCHECKED_CAST")
                    val constraintsMap = task["constraints"] as? Map<String, Any?> ?: emptyMap()

                    // TODO: replace BackgroundTasksWorker::class with your app's worker
                    // that knows how to invoke the Laravel `command` on the embedded runtime.
                    val request = PeriodicWorkRequestBuilder<BackgroundTasksWorker>(
                        intervalMinutes, TimeUnit.MINUTES
                    )
                        .setConstraints(buildConstraints(constraintsMap))
                        .addTag(taskId)
                        .setInputData(
                            androidx.work.workDataOf(
                                "command" to command,
                                "taskId" to taskId
                            )
                        )
                        .build()

                    workManager.enqueueUniquePeriodicWork(
                        taskId,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request
                    )
                }

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
            // TODO: dispatch each registered worker via OneTimeWorkRequest for testing.
            return BridgeResponse.success(
                mapOf<String, Any>(
                    "success" to true,
                    "message" to "RunNow dispatched (testing only)."
                )
            )
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
                val infos = workManager.getWorkInfosByTag("com.projectmata.task").get()

                val tasks = infos.map { info ->
                    mapOf<String, Any>(
                        "id" to info.tags.firstOrNull { it.startsWith("com.projectmata.task") }.orEmpty(),
                        "state" to info.state.name
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
 * Stub worker — replace with the real implementation in your host app.
 * It must know how to invoke a Laravel artisan command on the embedded
 * NativePHP runtime and respect the `command` / `taskId` input data.
 */
class BackgroundTasksWorker(
    appContext: android.content.Context,
    workerParams: androidx.work.WorkerParameters
) : androidx.work.Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val command = inputData.getString("command") ?: return Result.failure()
        // TODO: invoke `php artisan $command` against the bundled PHP runtime.
        return Result.success()
    }
}
