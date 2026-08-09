(function () {
  const STORAGE_KEY = "crowssh_demo_device_identity";

  function readIdentity() {
    try {
      return JSON.parse(sessionStorage.getItem(STORAGE_KEY) || "null");
    } catch (error) {
      return null;
    }
  }

  window.AIAuth = {
    getLoginState() {
      return readIdentity();
    },
    saveLoginState(identity) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ ...identity, ts: Date.now() }));
    },
    clearLoginState() {
      sessionStorage.removeItem(STORAGE_KEY);
    },
  };
})();
