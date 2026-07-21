(function () {
  const COOKIE_NAME = "ai_agent_login";

  function parseCookie(name) {
    const match = document.cookie.match(
      new RegExp("(^|;\\s*)" + name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + "=([^;]*)")
    );
    if (!match) {
      return null;
    }
    try {
      return JSON.parse(decodeURIComponent(match[2]));
    } catch (error) {
      return null;
    }
  }

  function writeCookie(name, value, days) {
    const expires = new Date(Date.now() + days * 24 * 60 * 60 * 1000).toUTCString();
    document.cookie = [
      `${name}=${encodeURIComponent(JSON.stringify(value))}`,
      `expires=${expires}`,
      "path=/",
      "SameSite=Lax",
    ].join("; ");
  }

  function clearCookie(name) {
    document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/; SameSite=Lax`;
  }

  window.AIAuth = {
    cookieName: COOKIE_NAME,
    getLoginState() {
      return parseCookie(COOKIE_NAME);
    },
    saveLoginState(user) {
      writeCookie(COOKIE_NAME, { user, ts: Date.now() }, 7);
    },
    clearLoginState() {
      clearCookie(COOKIE_NAME);
    },
  };
})();
