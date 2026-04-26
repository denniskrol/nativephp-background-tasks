<?php

namespace Projectmata\MobileBackgroundTasks;

use Illuminate\Console\Scheduling\Event;

class ScheduleConstraints
{
    public static function register(): void
    {
        Event::macro('onAnyNetwork', function () {
            $this->mobileConstraints['network'] = 'any';
            return $this;
        });

        Event::macro('onWifi', function () {
            $this->mobileConstraints['network'] = 'wifi';
            return $this;
        });

        Event::macro('whileCharging', function () {
            $this->mobileConstraints['charging'] = true;
            return $this;
        });

        Event::macro('whenBatteryNotLow', function () {
            $this->mobileConstraints['batteryNotLow'] = true;
            return $this;
        });

        Event::macro('whenStorageNotLow', function () {
            $this->mobileConstraints['storageNotLow'] = true;
            return $this;
        });

        Event::macro('whenIdle', function () {
            $this->mobileConstraints['idle'] = true;
            return $this;
        });

        Event::macro('longRunning', function () {
            $this->mobileConstraints['longRunning'] = true;
            return $this;
        });

        Event::macro('mobileConstraints', function () {
            return $this->mobileConstraints ?? [];
        });
    }
}
