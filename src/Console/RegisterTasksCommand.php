<?php

namespace Projectmata\MobileBackgroundTasks\Console;

use Illuminate\Console\Command;
use Projectmata\MobileBackgroundTasks\BackgroundTasksManager;

class RegisterTasksCommand extends Command
{
    protected $signature = 'projectmata:background-tasks:register';

    protected $description = 'Push the current Laravel schedule to the native background-task runtime.';

    public function handle(BackgroundTasksManager $manager): int
    {
        $tasks = $manager->tasks();

        if ($tasks === []) {
            $this->info('No mobile background tasks were found in the schedule.');
            return self::SUCCESS;
        }

        $this->info(sprintf('Registering %d background task(s) with the native runtime...', count($tasks)));

        foreach ($tasks as $task) {
            $this->line(sprintf(
                ' - %s every %d min',
                $task['command'],
                $task['intervalMinutes'],
            ));
        }

        $result = $manager->register();

        if (is_array($result) && ($result['success'] ?? false) === false) {
            $this->error($result['message'] ?? 'Native bridge returned an unsuccessful response.');
            return self::FAILURE;
        }

        $this->info('Done.');
        return self::SUCCESS;
    }
}
