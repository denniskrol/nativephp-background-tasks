<?php

namespace Projectmata\MobileBackgroundTasks;

use Illuminate\Console\Scheduling\Schedule;
use Illuminate\Support\ServiceProvider;
use Projectmata\MobileBackgroundTasks\Console\RegisterTasksCommand;

class MobileBackgroundTasksServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        ScheduleConstraints::register();

        $this->app->singleton('mobile-background-tasks', function ($app) {
            return new BackgroundTasksManager($app->make(Schedule::class));
        });

        $this->app->bind(BackgroundTasksManager::class, fn ($app) => $app->make('mobile-background-tasks'));
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->commands([
                RegisterTasksCommand::class,
            ]);
        }
    }
}
