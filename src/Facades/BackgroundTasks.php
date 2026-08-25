<?php

namespace Denniskrol\NativePHPBackgroundTasks\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static array register()
 * @method static array runNow()
 * @method static array cancel(string $taskId)
 * @method static array getRegistered()
 * @method static array tasks()
 */
class BackgroundTasks extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return 'nativephp-background-tasks';
    }
}
