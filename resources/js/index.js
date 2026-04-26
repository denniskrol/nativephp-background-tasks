const baseUrl = '/_native/api/call';

async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
        },
        body: JSON.stringify({ method, params }),
    });

    const data = await response.json();

    if (!response.ok) {
        throw new Error(data?.message || `Native bridge error: ${response.status}`);
    }

    return data;
}

export async function register(tasks = []) {
    return bridgeCall('BackgroundTasks.Register', { tasks });
}

export async function runNow() {
    return bridgeCall('BackgroundTasks.RunNow', {});
}

export async function cancel(taskId) {
    return bridgeCall('BackgroundTasks.Cancel', { taskId });
}

export async function getRegistered() {
    return bridgeCall('BackgroundTasks.GetRegistered', {});
}

const BackgroundTasks = {
    Register({ tasks } = {}) {
        return register(tasks ?? []);
    },
    RunNow() {
        return runNow();
    },
    Cancel({ taskId } = {}) {
        return cancel(taskId);
    },
    GetRegistered() {
        return getRegistered();
    },
};

if (typeof window !== 'undefined') {
    window.NativePHP = window.NativePHP || {};
    window.NativePHP.BackgroundTasks = BackgroundTasks;
}

export default BackgroundTasks;
