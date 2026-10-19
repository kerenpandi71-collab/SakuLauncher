(() => {
  if (window.__sakuAutoDubBundled) return;
  window.__sakuAutoDubBundled = true;
  const prefs = { enabled: true, rate: 1, volume: 1, duckOriginal: true };
  let lastCaption = "", lastSpoken = "", timer = null, translating = false, queued = "";
  let badge = null, originalVolume = null;

  function status(message) {
    if (!badge || !badge.isConnected) {
      badge = document.createElement("div");
      badge.id = "saku-auto-dub-status";
      badge.textContent = "SakuTube Auto Dub";
      (document.documentElement || document.body).appendChild(badge);
    }
    badge.textContent = message;
    badge.style.display = "block";
  }
  function captions() {
    return Array.from(document.querySelectorAll(".ytp-caption-segment"))
      .map(node => (node.textContent || "").trim()).filter(Boolean)
      .join(" ").replace(/\s+/g, " ").trim();
  }
  function enableCaptions() {
    const button = document.querySelector(".ytp-subtitles-button");
    if (button && button.getAttribute("aria-pressed") !== "true") button.click();
  }
  function video() {
    return document.querySelector("video.html5-main-video") || document.querySelector("video");
  }
  function lowerOriginal() {
    if (!prefs.duckOriginal) return;
    const v = video();
    if (v && originalVolume === null) {
      originalVolume = v.volume;
      v.volume = Math.min(v.volume, 0.2);
    }
  }
  function restoreOriginal() {
    const v = video();
    if (v && originalVolume !== null) v.volume = originalVolume;
    originalVolume = null;
  }
  function speak(text) {
    const v = video();
    const previousVolume = v ? v.volume : null;
    if (v && prefs.duckOriginal) v.volume = Math.min(v.volume, 0.08);
    status("Auto Dub: meminta suara Indonesia dari Android…");
    browser.runtime.sendMessage({ type: "SAKU_SPEAK", text: text }).then(result => {
      if (!result || !result.ok) {
        if (v && previousVolume !== null) v.volume = previousVolume;
        status("Auto Dub: TTS Android gagal · periksa mesin suara");
        return;
      }
      status("SakuTube Auto Dub: suara Indonesia");
      const estimatedMs = Math.max(2200, Math.min(18000, text.length * 75));
      setTimeout(() => {
        if (v && previousVolume !== null) v.volume = previousVolume;
        status("Auto Dub aktif · menunggu subtitle");
      }, estimatedMs);
    }).catch(() => {
      if (v && previousVolume !== null) v.volume = previousVolume;
      status("Auto Dub: jembatan suara Android gagal");
    });
  }
  async function translate(text) {
    if (translating) { queued = text; return; }
    translating = true;
    status("Auto Dub: menerjemahkan…");
    try {
      // Prevent a stalled bridge from leaving the status stuck forever.
      const result = await Promise.race([
        browser.runtime.sendMessage({ type: "SAKU_TRANSLATE", text }),
        new Promise(resolve => setTimeout(() => resolve({ ok: false, error: "timeout" }), 12000))
      ]);
      if (prefs.enabled && result && result.ok && result.text && result.text !== lastSpoken) {
        lastSpoken = result.text;
        speak(result.text);
      } else if (result && result.error === "timeout") {
        status("Auto Dub: terjemahan timeout · muat ulang video");
      } else if (!result || !result.ok) {
        status("Auto Dub: terjemahan gagal · cek koneksi");
      }
    } catch (_) {
      status("Auto Dub: layanan terjemahan tidak tersedia");    } finally {
      translating = false;
      if (queued && prefs.enabled) {
        const next = queued; queued = "";
        if (next !== text) translate(next);
      }
    }
  }
  function inspectCaptions() {
    if (!prefs.enabled) return;
    const text = captions();
    if (!text || text === lastCaption) return;
    lastCaption = text;
    clearTimeout(timer);
    timer = setTimeout(() => {
      if (!prefs.enabled || text.length < 12 || text === lastSpoken) return;
      if (!/[.!?…]$/.test(text) && text.split(" ").length < 4) return;
      translate(text);
    }, 800);
  }
  new MutationObserver(inspectCaptions).observe(document.documentElement, {
    childList: true, subtree: true, characterData: true
  });
  status("SakuTube Auto Dub aktif · menunggu subtitle YouTube");
  setTimeout(enableCaptions, 1400);
  setInterval(() => { if (!badge || !badge.isConnected) status("SakuTube Auto Dub aktif"); }, 5000);
})();
