(function () {
  function getLoginState() {
    return window.AIAuth ? window.AIAuth.getLoginState() : null;
  }

  function getConfigPath(key) {
    return window.APP_CONFIG && window.APP_CONFIG.API_PATHS
      ? window.APP_CONFIG.API_PATHS[key]
      : "";
  }

  function joinUrl(base, path) {
    return `${String(base || "").replace(/\/$/, "")}${path}`;
  }

  function escapeHtml(value) {
    return String(value || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  function renderMarkdownLike(text) {
    return escapeHtml(text).replace(/\n/g, "<br>");
  }

  function formatTime() {
    return new Date().toLocaleTimeString("zh-CN", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
  }

  async function requestJson(url, options) {
    const identity = getLoginState();
    const response = await fetch(url, {
      headers: {
        "Content-Type": "application/json",
        ...(identity && identity.accessToken
          ? { Authorization: `Bearer ${identity.accessToken}` }
          : {}),
      },
      ...options,
    });

    const payload = await response.json().catch(() => null);
    if (!response.ok) {
      throw new Error((payload && payload.info) || `请求失败：${response.status}`);
    }
    return payload;
  }

  async function loadAgentList(apiBase) {
    const result = await requestJson(joinUrl(apiBase, getConfigPath("queryAiAgentConfigList")), {
      method: "GET",
    });
    return Array.isArray(result && result.data) ? result.data : [];
  }

  async function createSession(apiBase, agentId, userId) {
    const result = await requestJson(joinUrl(apiBase, getConfigPath("createSession")), {
      method: "POST",
      body: JSON.stringify({ agentId, userId }),
    });

    const sessionId = result && result.data && result.data.sessionId;
    if (!sessionId) {
      throw new Error("未获取到会话ID");
    }
    return sessionId;
  }

  async function sendChat(apiBase, agentId, userId, sessionId, message) {
    return requestJson(joinUrl(apiBase, getConfigPath("chat")), {
      method: "POST",
      body: JSON.stringify({ agentId, userId, sessionId, message }),
    });
  }

  function createMessageBubble(type, title, text, meta) {
    const item = document.createElement("article");
    item.className = `message message-${type}`;
    item.innerHTML = `
      <div class="message-head">
        <span class="message-role">${escapeHtml(title)}</span>
        <span class="message-time">${escapeHtml(meta || formatTime())}</span>
      </div>
      <div class="message-body">${renderMarkdownLike(text)}</div>
    `;
    return item;
  }

  function ensureLoggedInOrRedirect() {
    const state = getLoginState();
    if (!state || !state.principalId || !state.accessToken) {
      window.location.replace("./login.html");
      return null;
    }
    return state;
  }

  function init() {
    if (window.__AI_CHAT_INITIALIZED__) {
      return;
    }
    window.__AI_CHAT_INITIALIZED__ = true;

    const loginState = ensureLoggedInOrRedirect();
    if (!loginState) {
      return;
    }

    const apiBase = window.APP_CONFIG && window.APP_CONFIG.API_BASE ? window.APP_CONFIG.API_BASE : "";
    const elements = {
      welcome: document.getElementById("welcomeText"),
      loginBadge: document.getElementById("loginBadge"),
      userText: document.getElementById("userText"),
      statusPill: document.getElementById("statusPill"),
      agentSelect: document.getElementById("agentSelect"),
      agentHint: document.getElementById("agentHint"),
      messageList: document.getElementById("messageList"),
      input: document.getElementById("messageInput"),
      sendBtn: document.getElementById("sendBtn"),
      newSessionBtn: document.getElementById("newSessionBtn"),
      refreshBtn: document.getElementById("refreshBtn"),
      logoutBtn: document.getElementById("logoutBtn"),
      errorBanner: document.getElementById("errorBanner"),
      sessionId: document.getElementById("sessionId"),
      sessionMeta: document.getElementById("sessionMeta"),
      agentDesc: document.getElementById("agentDesc"),
      quickBtns: Array.from(document.querySelectorAll("[data-quick]")),
    };

    const state = {
      userId: loginState.principalId,
      agentId: "",
      sessionId: "",
      loading: false,
      agents: [],
    };

    function setError(message) {
      elements.errorBanner.textContent = message || "";
      elements.errorBanner.hidden = !message;
    }

    function setStatus(text, tone) {
      elements.statusPill.textContent = text;
      elements.statusPill.dataset.tone = tone || "idle";
    }

    function setSessionInfo(sessionId) {
      elements.sessionId.textContent = sessionId ? sessionId : "未创建";
      elements.sessionMeta.textContent = sessionId ? "当前会话已激活" : "发送消息时将自动创建";
    }

    function updateAgentDescription(agent) {
      if (!agent) {
        elements.agentDesc.textContent = "请选择一个智能体，左侧会展示它的描述和状态。";
        return;
      }
      elements.agentDesc.textContent = agent.agentDesc || "该智能体暂无描述。";
    }

    function renderAgentOptions(list) {
      elements.agentSelect.innerHTML = "";
      const placeholder = document.createElement("option");
      placeholder.value = "";
      placeholder.textContent = "请选择智能体";
      elements.agentSelect.appendChild(placeholder);

      list.forEach((agent) => {
        const option = document.createElement("option");
        option.value = agent.agentId;
        option.textContent = agent.agentName || agent.agentId;
        option.dataset.desc = agent.agentDesc || "";
        elements.agentSelect.appendChild(option);
      });
    }

    function appendMessage(type, title, text, meta) {
      elements.messageList.appendChild(createMessageBubble(type, title, text, meta));
      elements.messageList.scrollTop = elements.messageList.scrollHeight;
    }

    function setLoading(loading) {
      state.loading = loading;
      elements.sendBtn.disabled = loading;
      elements.newSessionBtn.disabled = loading;
      elements.refreshBtn.disabled = loading;
      elements.agentSelect.disabled = loading;
      elements.input.disabled = loading;
      elements.sendBtn.textContent = loading ? "发送中..." : "发送";
    }

    function readSelectedAgent() {
      return state.agents.find((item) => item.agentId === elements.agentSelect.value) || null;
    }

    async function ensureAgentList() {
      setError("");
      setStatus("加载智能体", "busy");
      try {
        const agents = await loadAgentList(apiBase);
        state.agents = agents;
        renderAgentOptions(agents);
        const preferred = agents[0] || null;
        if (preferred) {
          elements.agentSelect.value = preferred.agentId;
          state.agentId = preferred.agentId;
          updateAgentDescription(preferred);
          elements.agentHint.textContent = `当前智能体：${preferred.agentName || preferred.agentId}`;
        } else {
          updateAgentDescription(null);
          elements.agentHint.textContent = "暂无可用智能体";
        }
        setStatus("在线", "success");
      } catch (error) {
        setStatus("接口异常", "error");
        setError("智能体列表加载失败，请确认后端已启动并检查 API_BASE 是否正确。");
        throw error;
      }
    }

    async function createCurrentSession() {
      const agentId = elements.agentSelect.value;
      if (!agentId) {
        throw new Error("请先选择一个智能体");
      }
      setStatus("创建会话", "busy");
      const sessionId = await createSession(apiBase, agentId, state.userId);
      state.sessionId = sessionId;
      setSessionInfo(sessionId);
      setStatus("会话已创建", "success");
      return sessionId;
    }

    async function handleSend(message) {
      const trimmed = String(message || "").trim();
      if (!trimmed) {
        setError("请输入要发送的消息。");
        return;
      }

      const agentId = elements.agentSelect.value;
      if (!agentId) {
        setError("请先选择一个智能体。");
        return;
      }

      if (!state.loading) {
        setError("");
      }

      try {
        setLoading(true);
        setStatus("发送中", "busy");
        appendMessage("user", `用户 · ${state.userId}`, trimmed);
        elements.input.value = "";

        const sessionId = await createCurrentSession();
        const result = await sendChat(apiBase, agentId, state.userId, sessionId, trimmed);
        const content = result && result.data && result.data.content ? result.data.content : "接口返回为空。";
        appendMessage("agent", `智能体 · ${readSelectedAgent()?.agentName || agentId}`, content);
        setStatus("已完成", "success");
      } catch (error) {
        setStatus("发送失败", "error");
        appendMessage("agent", "系统提示", error.message || "发送失败");
        setError(error.message || "发送失败");
      } finally {
        setLoading(false);
      }
    }

    function bindEvents() {
      elements.agentSelect.addEventListener("change", async function () {
        const agent = readSelectedAgent();
        state.agentId = elements.agentSelect.value;
        state.sessionId = "";
        setSessionInfo("");
        updateAgentDescription(agent);
        elements.agentHint.textContent = agent
          ? `当前智能体：${agent.agentName || agent.agentId}`
          : "请选择智能体";
        setError("");
      });

      elements.sendBtn.addEventListener("click", function () {
        handleSend(elements.input.value);
      });

      elements.input.addEventListener("keydown", function (event) {
        if (event.key === "Enter" && !event.shiftKey) {
          event.preventDefault();
          handleSend(elements.input.value);
        }
      });

      elements.newSessionBtn.addEventListener("click", async function () {
        if (!elements.agentSelect.value) {
          setError("请先选择一个智能体。");
          return;
        }
        try {
          setLoading(true);
          await createCurrentSession();
        } catch (error) {
          setError(error.message || "创建会话失败");
          appendMessage("agent", "系统提示", error.message || "创建会话失败");
        } finally {
          setLoading(false);
        }
      });

      elements.refreshBtn.addEventListener("click", async function () {
        try {
          setLoading(true);
          await ensureAgentList();
        } catch (error) {
          setError(error.message || "刷新失败");
        } finally {
          setLoading(false);
        }
      });

      const bottomNewSessionBtn = document.getElementById("newSessionBtnBottom");
      if (bottomNewSessionBtn) {
        bottomNewSessionBtn.addEventListener("click", function () {
          elements.newSessionBtn.click();
        });
      }

      elements.logoutBtn.addEventListener("click", function () {
        if (window.AIAuth) {
          window.AIAuth.clearLoginState();
        }
        window.location.replace("./login.html");
      });

      elements.quickBtns.forEach((button) => {
        button.addEventListener("click", function () {
          const text = button.getAttribute("data-quick") || "";
          elements.input.value = text;
          elements.input.focus();
        });
      });
    }

    function initHeader() {
      const loginTime = loginState.ts ? new Date(loginState.ts).toLocaleString("zh-CN") : "未知时间";
      elements.welcome.textContent = `设备身份 ${loginState.principalId} 已就绪。`;
      elements.loginBadge.textContent = `设备 ${loginState.principalId}`;
      elements.userText.textContent = `设备身份：${loginState.principalId} · 注册时间：${loginTime}`;
    }

    async function boot() {
      initHeader();
      bindEvents();
      setSessionInfo("");
      setError("");
      await ensureAgentList();
      appendMessage("agent", "系统欢迎", "请选择智能体，然后输入消息开始对话。");
    }

    boot().catch((error) => {
      setStatus("初始化失败", "error");
      setError(error.message || "页面初始化失败");
    });
  }

  window.AIChatPage = {
    init,
  };

  document.addEventListener("DOMContentLoaded", init);
})();
