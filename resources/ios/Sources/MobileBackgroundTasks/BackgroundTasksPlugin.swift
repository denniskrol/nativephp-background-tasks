import Foundation
import BackgroundTasks

/**
 Background tasks bridge plugin.

 Uses iOS `BGTaskScheduler` to schedule periodic refresh and processing tasks.
 The actual Laravel command execution must be performed by your app's
 task handler — see the TODO blocks below.

 NOTE: iOS only allows task identifiers that are listed in
 `Info.plist > BGTaskSchedulerPermittedIdentifiers`. This plugin's
 `nativephp.json` declares `com.projectmata.task.*` as the allowed prefix.
 */
@objc(BackgroundTasksPlugin)
class BackgroundTasksPlugin: NSObject {

    fileprivate static var registered: [String: [String: Any]] = [:]

    @objc(BackgroundTasksPluginRegister)
    class Register: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            guard let tasks = parameters["tasks"] as? [[String: Any]] else {
                return [
                    "success": false,
                    "message": "tasks parameter is required."
                ]
            }

            for task in tasks {
                guard
                    let id = task["id"] as? String,
                    let intervalMinutes = task["intervalMinutes"] as? Int
                else { continue }

                let constraints = task["constraints"] as? [String: Any] ?? [:]
                BackgroundTasksPlugin.registered[id] = task

                let useProcessing = (constraints["longRunning"] as? Bool == true)
                    || (constraints["charging"] as? Bool == true)
                    || (constraints["idle"] as? Bool == true)

                let request: BGTaskRequest = useProcessing
                    ? BGProcessingTaskRequest(identifier: id)
                    : BGAppRefreshTaskRequest(identifier: id)

                if let processing = request as? BGProcessingTaskRequest {
                    processing.requiresExternalPower = (constraints["charging"] as? Bool) ?? false
                    processing.requiresNetworkConnectivity = (constraints["network"] as? String) != nil
                }

                if let refresh = request as? BGAppRefreshTaskRequest {
                    refresh.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(intervalMinutes * 60))
                }

                do {
                    try BGTaskScheduler.shared.submit(request)
                } catch {
                    return [
                        "success": false,
                        "message": "Failed to submit task \(id): \(error.localizedDescription)"
                    ]
                }
            }

            return [
                "success": true,
                "registered": tasks.count
            ]
        }
    }

    @objc(BackgroundTasksPluginRunNow)
    class RunNow: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            // TODO: invoke each registered task's handler synchronously for testing.
            return [
                "success": true,
                "message": "RunNow dispatched (testing only)."
            ]
        }
    }

    @objc(BackgroundTasksPluginCancel)
    class Cancel: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            guard let taskId = parameters["taskId"] as? String else {
                return [
                    "success": false,
                    "message": "taskId is required."
                ]
            }

            BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: taskId)
            BackgroundTasksPlugin.registered.removeValue(forKey: taskId)

            return [
                "success": true,
                "taskId": taskId
            ]
        }
    }

    @objc(BackgroundTasksPluginGetRegistered)
    class GetRegistered: NSObject {
        @objc
        func execute(_ parameters: [String: Any]) -> [String: Any] {
            let semaphore = DispatchSemaphore(value: 0)
            var pending: [[String: Any]] = []

            BGTaskScheduler.shared.getPendingTaskRequests { requests in
                pending = requests.map { request in
                    [
                        "id": request.identifier,
                        "earliestBeginDate": request.earliestBeginDate?.timeIntervalSince1970 ?? 0
                    ]
                }
                semaphore.signal()
            }

            semaphore.wait()

            return [
                "success": true,
                "tasks": pending
            ]
        }
    }
}
