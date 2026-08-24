# Projectmata Mobile Background Tasks

[![Latest Version](https://img.shields.io/packagist/v/projectmata/mobile-background-tasks.svg)](https://packagist.org/packages/projectmata/mobile-background-tasks)
[![Total Downloads](https://img.shields.io/packagist/dt/projectmata/mobile-background-tasks.svg)](https://packagist.org/packages/projectmata/mobile-background-tasks)
[![License](https://img.shields.io/packagist/l/projectmata/mobile-background-tasks.svg)](https://packagist.org/packages/projectmata/mobile-background-tasks)

Background task scheduling plugin for [NativePHP Mobile](https://nativephp.com). Lets you define recurring jobs with Laravel's standard scheduler and run them via Android **WorkManager** and iOS **BGTaskScheduler** — even when the app is backgrounded or killed.

> Android is fully implemented for NativePHP Mobile `^4.2`. The iOS handler remains a stub.

## Requirements

- PHP `^8.4`
- Laravel `^11.0` or `^12.0` / `^13.0`
- `nativephp/mobile ^4.2`
- Android: `min_version 33` (uses `androidx.work:work-runtime-ktx`)
- iOS: `min_version 16.0`

## Installation

```bash
composer require projectmata/mobile-background-tasks
```

Enable the package in your app's `NativeServiceProvider::plugins()` list, then rebuild the mobile app:

```bash
php artisan native:run android
# or
php artisan native:run ios
```

## Defining tasks

Use Laravel's normal scheduler in `routes/console.php`. The plugin adds a set of mobile-aware constraint methods you can chain on:

```php
use Illuminate\Support\Facades\Schedule;

Schedule::command('sync:data')
    ->everyFifteenMinutes()
    ->onAnyNetwork();

Schedule::command('cache:warm')
    ->hourly()
    ->onWifi()
    ->whileCharging();

Schedule::command('export:reports')
    ->daily()
    ->onWifi()
    ->whileCharging()
    ->whenIdle()
    ->longRunning();
```

Tasks are registered automatically when the NativePHP app runtime starts. You can also register them manually from code running in the mobile app:

```php
use Projectmata\MobileBackgroundTasks\Facades\BackgroundTasks;

BackgroundTasks::register();
```

This walks the schedule, serialises each periodic event into a task descriptor, and calls the `BackgroundTasks.Register` bridge function. The package automatically does this on each normal app start, adding, updating, and removing Android work as the schedule changes. It deliberately skips the ephemeral PHP runtime used to execute a background task.

## Constraint methods

| Method                  | Android                                | iOS                                          |
| ----------------------- | -------------------------------------- | -------------------------------------------- |
| `onAnyNetwork()`        | `NetworkType.CONNECTED`                | `requiresNetworkConnectivity = true`         |
| `onWifi()`              | `NetworkType.UNMETERED`                | `requiresNetworkConnectivity = true`         |
| `whileCharging()`       | `setRequiresCharging(true)`            | `requiresExternalPower = true`               |
| `whenBatteryNotLow()`   | `setRequiresBatteryNotLow(true)`       | *Ignored*                                    |
| `whenStorageNotLow()`   | `setRequiresStorageNotLow(true)`       | *Ignored*                                    |
| `whenIdle()`            | `setRequiresDeviceIdle(true)`          | Promotes to `BGProcessingTask`               |
| `longRunning()`         | *No-op*                                | Promotes to `BGProcessingTask`               |

## Supported intervals

`everyFifteenMinutes()`, `everyTwentyMinutes()`, `everyThirtyMinutes()`, `hourly()`, `everyTwoHours()`, `everyThreeHours()`, `everyFourHours()`, `everySixHours()`, `daily()`.

Other Laravel scheduler frequencies are ignored by the collector — both Android WorkManager and iOS BGTaskScheduler enforce minimum periodic intervals (15 minutes on Android, ~15 minutes practical floor on iOS).

## PHP API

```php
use Projectmata\MobileBackgroundTasks\Facades\BackgroundTasks;

// Push the current schedule to the OS scheduler
BackgroundTasks::register();

// Trigger registered tasks immediately (testing only — bypasses constraints)
BackgroundTasks::runNow();

// Cancel a single task
BackgroundTasks::cancel('com.projectmata.task.sync_data');

// Inspect what's scheduled
$registered = BackgroundTasks::getRegistered();

// Or just see the descriptors the collector would push
$tasks = BackgroundTasks::tasks();
```

## JavaScript API

```js
await window.NativePHP.BackgroundTasks.Register({ tasks: [...] });
await window.NativePHP.BackgroundTasks.RunNow();
await window.NativePHP.BackgroundTasks.Cancel({ taskId: 'com.projectmata.task.sync_data' });
const { tasks } = await window.NativePHP.BackgroundTasks.GetRegistered();
```

## Bridge methods

| Method                          | Params                                    | Returns                          |
| ------------------------------- | ----------------------------------------- | -------------------------------- |
| `BackgroundTasks.Register`      | `{ tasks: TaskDescriptor[] }`             | `{ success, registered }`        |
| `BackgroundTasks.RunNow`        | —                                         | `{ success, message }`           |
| `BackgroundTasks.Cancel`        | `{ taskId }`                              | `{ success, taskId }`            |
| `BackgroundTasks.GetRegistered` | —                                         | `{ success, tasks[] }`           |

`TaskDescriptor` shape:

```ts
{
    id: 'com.projectmata.task.sync_data',
    command: 'sync:data',
    intervalMinutes: 15,
    constraints: {
        network: 'any' | 'wifi' | null,
        charging: boolean,
        batteryNotLow: boolean,
        storageNotLow: boolean,
        idle: boolean,
        longRunning: boolean
    }
}
```

## Native integration

### Android

This package declares `androidx.work:work-runtime-ktx:2.9.1`. The `Register` bridge function enqueues a `PeriodicWorkRequest` per task with the right `Constraints`. When WorkManager runs a task, the package worker initializes NativePHP's background environment, boots an ephemeral PHP runtime, executes the bundled artisan command, and shuts the runtime down. No host-app Kotlin code is required.

`RunNow` enqueues one unconstrained one-time execution per registered task. Registered task descriptors are persisted in app-private storage, so `GetRegistered` and `RunNow` remain available after a cold start.

### iOS

`BGTaskScheduler` requires every task identifier to be listed in `Info.plist > BGTaskSchedulerPermittedIdentifiers`. The plugin declares the `com.projectmata.task.*` prefix; iOS will reject any identifier outside that prefix.

You must register a handler in your `AppDelegate` (or SwiftUI App) for each identifier you submit:

```swift
BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.projectmata.task.sync_data", using: nil) { task in
    // run the sync:data artisan command, then call task.setTaskCompleted(success: ...)
}
```

The plugin's `Register` bridge submits the request; your handler runs the actual work.

## Testing

**Android (ADB):**

```bash
adb shell cmd jobscheduler run -f <your.app.bundle> <job-id>
```

**iOS (Xcode LLDB):**

```
e -l objc -- (void)[[BGTaskScheduler sharedScheduler] _simulateLaunchForTaskWithIdentifier:@"com.projectmata.task.sync_data"]
```

Or just call `BackgroundTasks::runNow()` from PHP — it bypasses constraints and is intended for development only.

## License

MIT
