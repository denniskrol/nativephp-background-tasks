<?php

namespace Projectmata\MobileBackgroundTasks;

use Illuminate\Console\Scheduling\Schedule;

class BackgroundTasksManager
{
    public function __construct(private readonly Schedule $schedule)
    {
    }

    public function register(): mixed
    {
        $tasks = (new TaskCollector($this->schedule))->collect();

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
