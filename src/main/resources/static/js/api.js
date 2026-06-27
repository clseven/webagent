// API 请求封装
const API_BASE = '';

function normalizeSandboxPath(path) {
    if (typeof path !== 'string') return path;

    const unquote = (value) => {
        if (value.length >= 2) {
            const first = value[0];
            const last = value[value.length - 1];
            if ((first === "'" || first === '"') && first === last) {
                return value.substring(1, value.length - 1);
            }
        }
        return value;
    };

    return unquote(path.trim())
        .split('/')
        .map(unquote)
        .join('/');
}

function createApiClient() {
    async function request(method, url, body, options = {}) {
        const token = localStorage.getItem('auth_token');
        const headers = { ...options.headers };

        if (token) {
            headers['Authorization'] = 'Bearer ' + token;
        }

        if (body && !(body instanceof FormData)) {
            headers['Content-Type'] = 'application/json';
        }

        const res = await fetch(API_BASE + url, {
            method,
            headers,
            body: body instanceof FormData ? body : (body ? JSON.stringify(body) : undefined),
        });

        const data = await res.json();

        if (data.code === 401) {
            localStorage.removeItem('auth_token');
            localStorage.removeItem('agent_session_id');
            window.location.reload();
            throw new Error('认证已过期');
        }

        if (data.code !== 200) {
            // 创建错误对象，保留后端返回的 code 和 data
            const err = new Error(data.message || '请求失败');
            err.code = data.code;
            err.data = data.data;  // 包含额外信息（如重复文件时的 existingDocId）
            throw err;
        }

        return data.data;
    }

    return {
        // 认证
        login: (username, password) => request('POST', '/api/auth/login', { username, password }),
        register: (username, password) => request('POST', '/api/auth/register', { username, password }),
        me: () => request('GET', '/api/auth/me'),

        // 会话
        listSessions: () => request('GET', '/api/sessions'),
        createSession: (appId) => request('POST', '/api/sessions', appId ? { appId } : undefined),
        getSession: (id) => request('GET', `/api/sessions/${id}`),
        deleteSession: (id) => request('DELETE', `/api/sessions/${id}`),
        deleteSessions: (sessionIds) => request('DELETE', '/api/sessions/batch', { sessionIds }),

        // 聊天
        sendMessage: (sessionId, message, searchEnabled = false, planningEnabled = true) => request('POST', `/api/sessions/${sessionId}/chat`, { message, searchEnabled, planningEnabled }),
        getHistory: (sessionId) => request('GET', `/api/sessions/${sessionId}/history`),

        // 流式聊天（SSE）
        // 返回 EventSource 和停止函数
        createChatStream: (sessionId, message, searchEnabled, planningEnabled, onEvent) => {
            const token = localStorage.getItem('auth_token');
            const url = new URL(API_BASE + `/api/sessions/${sessionId}/chat/stream`, window.location.origin);
            url.searchParams.set('message', message);
            url.searchParams.set('searchEnabled', searchEnabled ? 'true' : 'false');
            url.searchParams.set('planningEnabled', planningEnabled ? 'true' : 'false');

            // 使用 fetch + ReadableStream 实现 SSE（支持 Authorization header）
            let stopped = false;
            let terminalEventReceived = false;
            const decoder = new TextDecoder();
            const abortController = new AbortController();
            let buffer = '';
            const incrementalEvents = [];
            let incrementalFrame = null;

            const flushIncrementalEvents = () => {
                if (incrementalFrame !== null) {
                    window.cancelAnimationFrame(incrementalFrame);
                    incrementalFrame = null;
                }
                while (!stopped && incrementalEvents.length > 0) {
                    onEvent(incrementalEvents.shift());
                }
            };

            const enqueueIncrementalEvent = (event) => {
                const last = incrementalEvents[incrementalEvents.length - 1];
                if (last && last.type === event.type) {
                    last.data.content += event.data.content || '';
                } else {
                    incrementalEvents.push({
                        type: event.type,
                        data: { content: event.data.content || '' }
                    });
                }
                if (incrementalFrame === null) {
                    incrementalFrame = window.requestAnimationFrame(flushIncrementalEvents);
                }
            };

            // 兼容后端返回 {type,data} 或将数据直接放在顶层的 SSE 载荷。
            const normalizeEventData = (payload) => {
                if (payload && typeof payload === 'object' && Object.prototype.hasOwnProperty.call(payload, 'data')) {
                    return payload.data || {};
                }
                if (payload && typeof payload === 'object') {
                    const { type, ...rest } = payload;
                    return rest;
                }
                return {};
            };

            const dispatchFrame = (frame) => {
                if (!frame.trim()) return;

                let eventType = '';
                const dataLines = [];

                for (const rawLine of frame.split(/\r?\n/)) {
                    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
                    if (!line || line.startsWith(':')) continue;

                    if (line.startsWith('event:')) {
                        eventType = line.substring(6).trim();
                    } else if (line.startsWith('data:')) {
                        dataLines.push(line.substring(5).trimStart());
                    }
                }

                if (dataLines.length === 0) return;

                try {
                    const payload = JSON.parse(dataLines.join('\n'));
                    const type = eventType || payload.type;
                    if (!type) return;

                    if (['done', 'error', 'interrupted'].includes(type)) {
                        terminalEventReceived = true;
                    }

                    const event = {
                        type,
                        data: normalizeEventData(payload)
                    };
                    if (type === 'token' || type === 'reasoning_token') {
                        enqueueIncrementalEvent(event);
                    } else {
                        // 阶段事件必须紧跟后端进度，不能排在大量 token 后面。
                        flushIncrementalEvents();
                        onEvent(event);
                    }
                } catch (e) {
                    console.warn('SSE parse error:', e, frame);
                }
            };

            const drainFrames = (flush = false) => {
                buffer = buffer.replace(/\r\n/g, '\n');

                let boundary;
                while ((boundary = buffer.indexOf('\n\n')) >= 0) {
                    const frame = buffer.substring(0, boundary);
                    buffer = buffer.substring(boundary + 2);
                    dispatchFrame(frame);
                }

                if (flush && buffer.trim()) {
                    dispatchFrame(buffer);
                    buffer = '';
                }
            };

            const connect = async () => {
                try {
                    const response = await fetch(url, {
                        headers: {
                            'Authorization': 'Bearer ' + token,
                            'Accept': 'text/event-stream',
                            'Cache-Control': 'no-cache'
                        },
                        cache: 'no-store',
                        signal: abortController.signal
                    });

                    if (!response.ok) {
                        throw new Error(`HTTP ${response.status}`);
                    }
                    if (!response.body) {
                        throw new Error('浏览器不支持流式响应');
                    }

                    const reader = response.body.getReader();

                    while (!stopped) {
                        const { done, value } = await reader.read();
                        if (done) break;

                        buffer += decoder.decode(value, { stream: true });
                        drainFrames();
                    }

                    buffer += decoder.decode();
                    drainFrames(true);

                    if (!stopped && !terminalEventReceived) {
                        flushIncrementalEvents();
                        onEvent({ type: 'done', data: {} });
                    }
                } catch (e) {
                    if (!stopped && e.name !== 'AbortError') {
                        flushIncrementalEvents();
                        onEvent({ type: 'error', data: { message: e.message } });
                    }
                }
            };

            connect();

            // 返回停止函数
            return () => {
                stopped = true;
                abortController.abort();
                incrementalEvents.length = 0;
                if (incrementalFrame !== null) {
                    window.cancelAnimationFrame(incrementalFrame);
                    incrementalFrame = null;
                }
            };
        },

        // 技能（全局）
        listSkills: () => request('GET', '/api/skills'),
        getSkill: (id) => request('GET', `/api/skills/${id}`),
        setSkillRoot: (directory) => request('POST', '/api/skills/set-root', { directory }),

        // 技能（会话）
        getEnabledSkills: (sessionId) => request('GET', `/api/sessions/${sessionId}/skills`),
        // 融合视图：本地仓库 ∪ 当前会话沙箱发现，每项带 source（local/sandbox/both）与 enabled
        listSessionSkills: (sessionId) => request('GET', `/api/sessions/${sessionId}/skills/available`),
        enableSkill: (sessionId, skillId) => request('POST', `/api/sessions/${sessionId}/skills/${skillId}/enable`),
        disableSkill: (sessionId, skillId) => request('POST', `/api/sessions/${sessionId}/skills/${skillId}/disable`),

        // VNC
        getAioEndpoint: (sessionId) => request('GET', `/api/sessions/${sessionId}/aio/endpoint`),

        // 文件
        uploadFile: (sessionId, file) => {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('sessionId', sessionId);
            return request('POST', '/api/files/upload', formData);
        },
        // 替换沙箱已有文件
        replaceFile: (sessionId, file) => {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('sessionId', sessionId);
            return request('PUT', '/api/files/upload/replace', formData);
        },

        // 沙箱文件操作
        executeCommand: (sessionId, command) => request('POST', `/api/sessions/${sessionId}/execute`, { command }),
        refreshWorkspace: (sessionId) => request('POST', `/api/sessions/${sessionId}/workspace/refresh`),
        readFileInSandbox: (sessionId, path) => request('POST', `/api/sessions/${sessionId}/files/read`, { path }),

        // 沙箱文件预览（返回 ArrayBuffer，inline 渲染用）
        previewFileInSandbox: async (sessionId, path) => {
            const token = localStorage.getItem('auth_token');
            const cleanPath = normalizeSandboxPath(path);
            const encoded = encodeURIComponent(cleanPath);
            const res = await fetch(API_BASE + `/api/sessions/${sessionId}/files/preview?path=${encoded}`, {
                headers: { 'Authorization': 'Bearer ' + token }
            });
            if (!res.ok) {
                throw new Error('预览文件失败: HTTP ' + res.status);
            }
            return await res.arrayBuffer();
        },

        // 沙箱文件下载（attachment 触发浏览器原生下载）
        downloadFileFromSandbox: (sessionId, path) => {
            const token = localStorage.getItem('auth_token');
            const cleanPath = normalizeSandboxPath(path);
            const encoded = encodeURIComponent(cleanPath);
            const url = API_BASE + `/api/sessions/${sessionId}/files/download?path=${encoded}`;
            // 通过 fetch 拿到 blob 后用 <a download> 触发下载（带 Authorization）
            fetch(url, { headers: { 'Authorization': 'Bearer ' + token } })
                .then(r => r.blob())
                .then(blob => {
                    const blobUrl = URL.createObjectURL(blob);
                    const a = document.createElement('a');
                    a.href = blobUrl;
                    a.download = cleanPath.substring(cleanPath.lastIndexOf('/') + 1);
                    document.body.appendChild(a);
                    a.click();
                    document.body.removeChild(a);
                    URL.revokeObjectURL(blobUrl);
                });
        },

        // Token 统计
        getTokenSummary: (days) => request('GET', `/api/token-stats/summary?days=${days}`),
        getTokenDaily: (days) => request('GET', `/api/token-stats/daily?days=${days}`),
        getTokenByModel: (days) => request('GET', `/api/token-stats/by-model?days=${days}`),

        // Agent 应用
        listApps: () => request('GET', '/api/apps'),
        createApp: (name, description) => request('POST', '/api/apps', { name, description }),
        getApp: (appId) => request('GET', `/api/apps/${appId}`),
        updateApp: (appId, name, description) => request('PUT', `/api/apps/${appId}`, { name, description }),
        deleteApp: (appId) => request('DELETE', `/api/apps/${appId}`),
        updateAppKnowledgeBases: (appId, kbIds) => request('PUT', `/api/apps/${appId}/knowledge-bases`, { kbIds }),
        updateAppSkills: (appId, skillIds) => request('PUT', `/api/apps/${appId}/skills`, { skillIds }),

        // 知识库
        createKnowledgeBase: (name, description) => request('POST', '/api/rag/bases', { name, description }),
        listKnowledgeBases: () => request('GET', '/api/rag/bases'),
        getKnowledgeBase: (kbId) => request('GET', `/api/rag/bases/${kbId}`),
        updateKnowledgeBase: (kbId, name, description) => request('PUT', `/api/rag/bases/${kbId}`, { name, description }),
        deleteKnowledgeBase: (kbId) => request('DELETE', `/api/rag/bases/${kbId}`),

        // 知识库文档
        uploadKnowledge: (kbId, file, splitMode = 'smart', chunkSize = null, overlap = null) => {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('splitMode', splitMode);
            if (chunkSize !== null) formData.append('chunkSize', chunkSize);
            if (overlap !== null) formData.append('overlap', overlap);
            return request('POST', `/api/rag/bases/${kbId}/documents/upload`, formData);
        },
        // 替换已有文档（用于处理重复文件时选择"替换"）
        replaceKnowledgeDoc: (docId, file, splitMode = 'smart', chunkSize = null, overlap = null) => {
            const formData = new FormData();
            formData.append('file', file);
            formData.append('splitMode', splitMode);
            if (chunkSize !== null) formData.append('chunkSize', chunkSize);
            if (overlap !== null) formData.append('overlap', overlap);
            return request('PUT', `/api/rag/document/${docId}/replace`, formData);
        },
        listKnowledgeDocs: (kbId) => request('GET', `/api/rag/bases/${kbId}/documents`),
        getKnowledgeDoc: (docId) => request('GET', `/api/rag/document/${docId}`),
        deleteKnowledgeDoc: (docId) => request('DELETE', `/api/rag/document/${docId}`),
        searchKnowledge: (kbId, query, topK = 5) => request('POST', `/api/rag/bases/${kbId}/search`, { query, topK }),

        // 知识库预览（返回 ArrayBuffer / JSON，不走 ApiResponse 包装）
        getKnowledgeChunks: async (docId) => {
            const token = localStorage.getItem('auth_token');
            const res = await fetch(API_BASE + `/api/rag/document/${docId}/chunks`, {
                headers: { 'Authorization': 'Bearer ' + token }
            });
            const json = await res.json();
            if (json.code !== 200) throw new Error(json.message || '获取切片失败');
            return json.data;
        },
        getKnowledgeFile: async (docId) => {
            const token = localStorage.getItem('auth_token');
            const res = await fetch(API_BASE + `/api/rag/document/${docId}/file`, {
                headers: { 'Authorization': 'Bearer ' + token }
            });
            if (!res.ok) {
                const text = await res.text();
                throw new Error('文件读取失败: ' + text);
            }
            return await res.arrayBuffer();
        },
    };
}

const api = createApiClient();
