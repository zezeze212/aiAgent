"use strict";

const el = id => document.getElementById(id);
const terminals = new Set(["SUCCEEDED", "FAILED", "REJECTED", "TIMED_OUT"]);

let source = null;
let version = 0;
let currentTaskId = "";
let pendingSubmission = null;
const logs = [];

function log(message) {
    logs.push(`${new Date().toLocaleTimeString()} ${message}`);
    if (logs.length > 60) logs.shift();
    el("events").textContent = logs.join("\n");
}

function disconnect(message) {
    if (source) source.close();
    source = null;
    el("connection").textContent = message;
}

function beginView(taskId) {
    disconnect("尚未订阅");
    version++;
    currentTaskId = taskId;

    el("taskId").value = taskId;
    el("currentTask").textContent = taskId || "—";
    el("status").textContent = "等待查询或快照";
    el("traceId").textContent = "—";
    el("taskError").textContent = "—";
    el("answer").textContent = "尚未获取执行记录";

    return version;
}

async function api(url, options = {}, acceptTask = false) {
    const response = await fetch(url, { cache: "no-store", ...options });
    const body = await response.json();

    // 提交被执行器拒绝时，HTTP 503 也可能携带已创建的任务。
    if (acceptTask && body.data?.taskId) return body.data;

    if (!response.ok || ![200, 202].includes(body.code)) {
        const error = new Error(body.message || `HTTP ${response.status}`);
        error.definitive = response.status >= 400 && response.status < 500;
        throw error;
    }

    return body.data;
}

async function loadRun(traceId, token) {
    try {
        const detail = await api(`/agent/runs/${encodeURIComponent(traceId)}`);
        if (token !== version) return;

        if (!detail?.run) {
            throw new Error("尚未找到关联执行记录，可稍后查询任务");
        }

        // 输出作为纯文本展示，不将模型回答解释成 HTML。
        el("answer").textContent =
            detail.run.answer || detail.run.errorMessage || "执行记录没有回答正文";

        log(`执行记录已读取：${traceId}，run.success=${detail.run.success}`);
    } catch (error) {
        if (token !== version) return;

        el("answer").textContent = `读取执行记录失败：${error.message}`;
        log(`读取记录失败，不修改任务状态：${error.message}`);
    }
}

function renderTask(task, token) {
    if (token !== version) return;

    if (!task || task.taskId !== currentTaskId) {
        throw new Error("返回的任务 ID 不匹配");
    }

    el("status").textContent = task.status;
    el("traceId").textContent = task.traceId || "—";
    el("taskError").textContent =
        [task.errorCode, task.errorMessage].filter(Boolean).join("：") || "—";

    log(`任务快照：${task.status}`);

    if (!terminals.has(task.status)) return;

    // 必须主动 close，否则浏览器可能再次连接已完成的任务。
    disconnect("任务已结束，订阅已关闭");

    if (task.traceId) {
        void loadRun(task.traceId, token);
    } else {
        el("answer").textContent =
            "当前没有关联 Trace；后续可查询任务检查是否补充了记录。";
    }
}

function subscribeTask(taskId) {
    const token = beginView(taskId);
    const connection = new EventSource(
        `/agent/tasks/${encodeURIComponent(taskId)}/events`
    );

    source = connection;
    let openCount = 0;

    const isCurrent = () => token === version && source === connection;
    el("connection").textContent = "正在连接";

    connection.onopen = () => {
        if (!isCurrent()) return;

        el("connection").textContent = "已连接";
        log(++openCount === 1
            ? "SSE 已连接"
            : "SSE 已自动重连，等待当前快照");
    };

    // 后端发送的是 event:task，因此不能只使用 onmessage。
    connection.addEventListener("task", event => {
        if (!isCurrent()) return;

        try {
            renderTask(JSON.parse(event.data), token);
        } catch (error) {
            disconnect("快照解析失败，请查询任务");
            log(error.message);
        }
    });

    connection.onerror = () => {
        if (!isCurrent()) return;

        if (connection.readyState === EventSource.CLOSED) {
            disconnect("连接已停止，请查询任务或重新订阅");
            log("浏览器已停止重连；具体 HTTP 错误可查看 Network");
        } else {
            el("connection").textContent = "连接中断，等待浏览器自动重连";
            log("连接中断；保留任务状态，不重新提交任务");
        }
    };
}

async function queryTask(taskId) {
    const token = beginView(taskId);
    el("connection").textContent = "仅查询一次，未订阅";

    try {
        renderTask(
            await api(`/agent/tasks/${encodeURIComponent(taskId)}`),
            token
        );
    } catch (error) {
        if (token !== version) return;

        el("connection").textContent = `查询失败：${error.message}`;
        log(error.message);
    }
}

async function submitTask() {
    if (!pendingSubmission) {
        const message = el("message").value;
        if (!message.trim()) return log("请先输入问题");

        pendingSubmission = {
            key: crypto.randomUUID(),
            body: JSON.stringify({
                message,
                modelId: el("modelId").value
            })
        };
    }

    const token = beginView("");
    el("submit").disabled = true;
    el("message").disabled = true;
    el("modelId").disabled = true;
    el("connection").textContent = "正在提交任务";

    try {
        const task = await api("/agent/tasks", {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Idempotency-Key": pendingSubmission.key
            },
            body: pendingSubmission.body
        }, true);

        if (!task?.taskId) {
            throw new Error("响应没有 taskId，提交结果未知");
        }

        pendingSubmission = null;
        log(`提交得到任务：${task.taskId}`);

        // 用户若已切换查看对象，不让较晚返回的提交结果覆盖页面。
        if (token === version) subscribeTask(task.taskId);
    } catch (error) {
        // 网络故障或 5xx 时保留原请求和键，手动重发不会另建任务。
        if (error.definitive) pendingSubmission = null;

        if (token === version) {
            el("connection").textContent = `提交未确认：${error.message}`;
        }

        log(pendingSubmission
            ? "结果未知：可点击“重发原提交”，本页保留了原请求和幂等键"
            : error.message);
    } finally {
        el("submit").disabled = false;
        el("submit").textContent =
            pendingSubmission ? "重发原提交" : "提交新任务";

        el("message").disabled = Boolean(pendingSubmission);
        el("modelId").disabled = Boolean(pendingSubmission);
    }
}

el("submit").onclick = submitTask;

el("subscribe").onclick = () => {
    const id = el("taskId").value.trim();
    if (id) subscribeTask(id);
    else log("请输入 taskId");
};

el("query").onclick = () => {
    const id = el("taskId").value.trim();
    if (id) void queryTask(id);
    else log("请输入 taskId");
};

el("disconnect").onclick = () => {
    // 同时让尚未返回的旧查询失效，避免随后重新打开订阅。
    version++;
    disconnect("已主动断开，任务继续执行");
    log("只关闭订阅，没有取消任务");
};

window.addEventListener("pagehide", () => {
    version++;
    if (source) source.close();
    source = null;
});