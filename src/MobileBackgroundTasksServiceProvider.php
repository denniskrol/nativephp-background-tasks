<?php

namespace Projectmata\MobileBackgroundTasks;

use Illuminate\Console\Scheduling\Schedule;
use Illuminate\Contracts\Console\Kernel;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\ServiceProvider;
use Native\Mobile\Runtime;

class MobileBackgroundTasksServiceProvider extends ServiceProvider
{
    private static bool $registered = false;

    public function register(): void
    {
        ScheduleConstraints::register();

        $this->app->singleton('mobile-background-tasks', function ($app) {
            return new BackgroundTasksManager(
                $app->make(Schedule::class),
                $app->make(Kernel::class),
            );
        });

        $this->app->bind(BackgroundTasksManager::class, fn ($app) => $app->make('mobile-background-tasks'));
    }

    public function boot(): void
    {
        // The persistent runtime calls reset immediately before each real app
        // dispatch, after Laravel and the native bridge are ready. Ephemeral
        // Artisan workers call Runtime::artisan directly, so never reach here.
        Runtime::onReset(function (): void {
            if (self::$registered) {
                return;
            }

            try {
                $manager = $this->app->make(BackgroundTasksManager::class);
                $result = $manager->register();

                if (is_array($result) && ($result['success'] ?? false) === false) {
                    throw new \RuntimeException($result['message'] ?? 'Native bridge returned an unsuccessful response.');
                }

                self::$registered = true;

                Log::info('Registered NativePHP background tasks.', [
                    'result' => $result,
                ]);
            } catch (\Throwable $exception) {
                Log::warning('Failed to register NativePHP background tasks.', [
                    'exception' => $exception,
                ]);
            }
        });
    }
}
