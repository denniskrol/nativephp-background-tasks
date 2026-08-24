<?php

namespace Projectmata\MobileBackgroundTasks;

use Illuminate\Console\Scheduling\Event;
use Illuminate\Console\Scheduling\Schedule;

class TaskCollector
{
    private const INTERVAL_MAP = [
        '*/15 * * * *' => 15,
        '*/20 * * * *' => 20,
        '*/30 * * * *' => 30,
        '0 * * * *' => 60,
        '0 */2 * * *' => 120,
        '0 */3 * * *' => 180,
        '0 */4 * * *' => 240,
        '0 */6 * * *' => 360,
        '0 0 * * *' => 1440,
    ];

    public function __construct(private readonly Schedule $schedule)
    {
    }

    public function collect(): array
    {
        $tasks = [];

        foreach ($this->schedule->events() as $event) {
            $intervalMinutes = $this->resolveInterval($event);

            if ($intervalMinutes === null) {
                continue;
            }

            $command = $this->extractCommand($event);

            if ($command === null) {
                continue;
            }

            $constraints = method_exists($event, 'mobileConstraints')
                ? $event->mobileConstraints()
                : ($event->mobileConstraints ?? []);

            $tasks[] = [
                'id' => $this->makeId($command),
                'command' => $command,
                'intervalMinutes' => $intervalMinutes,
                'constraints' => $this->normaliseConstraints($constraints),
            ];
        }

        return $tasks;
    }

    private function resolveInterval(Event $event): ?int
    {
        return self::INTERVAL_MAP[$event->expression] ?? null;
    }

    private function extractCommand(Event $event): ?string
    {
        $command = $event->command ?? null;

        if ($command === null) {
            return null;
        }

        // Laravel stores scheduled Artisan commands as a shell command, e.g.
        // `'/path/to/php' '/path/to/artisan' app:update-time`. Extract the
        // portion Artisan receives rather than returning that shell wrapper.
        if (preg_match('/(?:^|\s)(?:\'[^\']*artisan\'|"[^"]*artisan"|\S*artisan)\s+(.+)$/', $command, $matches)) {
            return trim($matches[1], "'\" ");
        }

        $parts = preg_split('/\s+/', trim($command));
        $artisanIndex = array_search('artisan', $parts ?: [], true);

        if ($artisanIndex !== false && isset($parts[$artisanIndex + 1])) {
            return $parts[$artisanIndex + 1];
        }

        return $command;
    }

    private function makeId(string $command): string
    {
        $slug = preg_replace('/[^a-z0-9]+/i', '_', $command);
        return 'com.projectmata.task.' . strtolower(trim($slug, '_'));
    }

    private function normaliseConstraints(array $raw): array
    {
        return [
            'network' => $raw['network'] ?? null,
            'charging' => (bool) ($raw['charging'] ?? false),
            'batteryNotLow' => (bool) ($raw['batteryNotLow'] ?? false),
            'storageNotLow' => (bool) ($raw['storageNotLow'] ?? false),
            'idle' => (bool) ($raw['idle'] ?? false),
            'longRunning' => (bool) ($raw['longRunning'] ?? false),
        ];
    }
}
