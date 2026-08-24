<?php

namespace Projectmata\MobileBackgroundTasks;

use Illuminate\Contracts\Console\Kernel;
use Illuminate\Console\Scheduling\Schedule;

class BackgroundTasksManager
{
    public function __construct(
        private readonly Schedule $schedule,
        private readonly Kernel $consoleKernel,
    )
    {
    }

    public function register(): mixed
    {
        $tasks = $this->tasks();

        return $this->callNative('BackgroundTasks.Register', [
            'tasks' => $tasks,
        ]);
    }

    public function runNow(): mixed
    {
        return $this->callNative('BackgroundTasks.RunNow');
    }

    public function cancel(string $taskId): mixed
    {
        return $this->callNative('BackgroundTasks.Cancel', [
            'taskId' => $taskId,
        ]);
    }

    public function getRegistered(): mixed
    {
        return $this->callNative('BackgroundTasks.GetRegistered');
    }

    public function tasks(): array
    {
        // NativePHP normally boots Laravel through the HTTP kernel, which does
        // not load routes/console.php. Bootstrap the console kernel before
        // inspecting the schedule so device-side registration sees its tasks.
        $this->consoleKernel->bootstrap();

        // The app may already have been bootstrapped by NativePHP's HTTP
        // runtime, in which case Laravel skips the `withRouting(commands: …)`
        // callback. Load the conventional schedule file explicitly; require_once
        // keeps repeated component renders from adding duplicate events.
        $consoleRoutes = base_path('routes/console.php');

        if (is_file($consoleRoutes)) {
            require_once $consoleRoutes;
        }

        return (new TaskCollector($this->schedule))->collect();
    }

    protected function callNative(string $method, array $params = []): mixed
    {
        if (! function_exists('nativephp_call')) {
            return [
                'success' => false,
                'message' => 'NativePHP bridge helper not available.',
            ];
        }

        $json = json_encode($params);

        if ($json === false) {
            return [
                'success' => false,
                'message' => 'Failed to encode NativePHP bridge parameters.',
            ];
        }

        return nativephp_call($method, $json);
    }
}
