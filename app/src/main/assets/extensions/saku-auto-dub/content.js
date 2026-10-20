(() => {
  if (window.__sakuAutoDubBundled) return;
  window.__sakuAutoDubBundled = true;

  const prefs = { enabled: true, duckVolume: 0.12 };
  const seen = new Set();
  const queue = [];
  let lastCaption = "";
  let pendingCaption = "";
  let debounceTimer = null;
  let processing = false;
  let badge = null;

  function status(message) {
    if (!badge || !badge.isConnected) {
      badge = document.createElement("div");
      badge.id = "saku-auto-dub-status";
      badge.setAttribute("role", "status");
      (document.documentElement || document.body).appendChild(badge);
    }
    badge.textContent = message;
    badge.style.display = "block";
  }

  function video() {
    return document.querySelector("video.html5-main-video") || document.querySelector("video");
  }

  function readCaptionText() {
    // YouTube uses .ytp-caption-segment on desktop and mobile web players.
    const nodes = Array.from(document.querySelectorAll(
      ".ytp-caption-segment, .caption-window .captions-text, .caption-window .caption-visual-line"
    ));
    const text = nodes.map(node => (node.textContent || "").trim())
      .filter(Boolean).join(" ").replace(/\s+/g, " ").trim();
    return text.length > 700 ? text.slice(0, 700) : text;
  }

  function enableCaptions() {
    const button = document.querySelector(".ytp-subtitles-button");
    if (button && button.getAttribute("aria-pressed") !== "true") {
      button.click();
      status("Auto Dub: mengaktifkan subtitle…");
    }
  }

  function enqueueCaption(text) {
    if (!text || text.length < 3 || text === lastCaption) return;
    lastCaption = text;
    if (seen.has(text)) return;
    seen.add(text);
    if (seen.size > 80) seen.delete(seen.values().next().value);
    queue.push(text);
    // Prevent a long backlog if subtitles update faster than the speech engine.
    while (queue.length > 8) queue.shift();
    processQueue();
  }

  async function translate(text) {
    const result = await Promise.race([
      browser.runtime.sendMessage({ type: "SAKU_TRANSLATE", text }),
      new Promise(resolve => setTimeout(() => resolve({ ok: false, error: "timeout" }), 15000))
    ]);
    if (!result || !result.ok || !result.text) {
      throw new Error(result && result.error ? result.error : "Terjemahan gagal");
    }
    return result.text.trim();
  }

  async function speakIndonesian(text) {
    const v = video();
    const previousVolume = v ? v.volume : null;
    if (v && prefs.enabled) v.volume = Math.min(v.volume, prefs.duckVolume);
    status("Auto Dub: membacakan bahasa Indonesia…");
    try {
      const result = await browser.runtime.sendMessage({ type: "SAKU_SPEAK", text });
      if (!result || !result.ok) throw new Error((result && result.error) || "TTS Android tidak tersedia");
    } finally {
      // Restore only if this is still the same player and the user has not changed
      // the volume while the Indonesian voice was speaking.
      if (v && previousVolume !== null && video() === v && v.volume <= prefs.duckVolume + 0.01) {
        v.volume = previousVolume;
      }
    }
  }

  async function processQueue() {
    if (processing || !prefs.enabled) return;
    processing = true;
    try {
      while (queue.length && prefs.enabled) {
        const source = queue.shift();
        try {
          status("Auto Dub: menerjemahkan subtitle…");
          const translated = await translate(source);
          if (!translated) continue;
          status("Auto Dub: " + translated);
          await speakIndonesian(translated);
          status("Auto Dub aktif · menunggu subtitle berikutnya");
        } catch (error) {
          status("Auto Dub: " + (String(error && error.message || "").includes("timeout")
            ? "terjemahan timeout · coba lagi"
            : "gagal · periksa koneksi dan mesin TTS"));
          // Do not spin forever on a failing network or unavailable speech engine.
          if (queue.length > 3) queue.splice(0, queue.length - 3);
        }
      }
    } finally {
      processing = false;
      if (queue.length && prefs.enabled) processQueue();
    }
  }

  function inspectCaptions() {
    if (!prefs.enabled) return;
    const text = readCaptionText();
    if (!text || text === lastCaption || text === pendingCaption) return;
    clearTimeout(debounceTimer);
    pendingCaption = text;
    debounceTimer = setTimeout(() => {
      pendingCaption = "";
      enqueueCaption(text);
    }, 350);
  }

  const observer = new MutationObserver(inspectCaptions);
  observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });

  status("SakuTube Auto Dub aktif · menunggu subtitle YouTube");
  setTimeout(enableCaptions, 1200);
  setTimeout(enableCaptions, 3500);
  setInterval(() => {
    if (!badge || !badge.isConnected) status("SakuTube Auto Dub aktif");
    inspectCaptions();
  }, 1200);
})();