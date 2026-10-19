(() => {
  if (window.__sakuAutoDubBundled) return;
  window.__sakuAutoDubBundled = true;
  const prefs = { enabled: true, rate: 1, volume: 1, duckOriginal: false };
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
    if (!("speechSynthesis" in window) || !("SpeechSynthesisUtterance" in window)) {
      status("Auto Dub: TTS tidak tersedia pada mesin browser ini");
      return;
    }
    try {
      speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = "id-ID";
      utterance.rate = Number(prefs.rate) || 1;
      utterance.volume = Math.max(0, Math.min(1, Number(prefs.volume)));
      const voices = speechSynthesis.getVoices();
      const voice = voices.find(item => /^id(-|_)?ID$/i.test(item.lang)) ||
                    voices.find(item => /^id/i.test(item.lang));
      if (voice) utterance.voice = voice;
      utterance.onstart = () => { lowerOriginal(); status("SakuTube Auto Dub: suara Indonesia"); };
      utterance.onend = () => { restoreOriginal(); status("Auto Dub aktif · menunggu subtitle"); };
      utterance.onerror = () => { restoreOriginal(); status("Auto Dub: suara gagal, periksa TTS Android"); };
      speechSynthesis.speak(utterance);
    } catch (_) {
      restoreOriginal();
      status("Auto Dub: tidak dapat memulai suara");
    }
  }
  async function translate(text) {
    if (translating) { queued = text; return; }
    translating = true;
    status("Auto Dub: menerjemahkan…");
    try {
      const result = await browser.runtime.sendMessage({ type: "SAKU_TRANSLATE", text });
      if (prefs.enabled && result && result.ok && result.text && result.text !== lastSpoken) {
        lastSpoken = result.text;
        speak(result.text);
      } else if (!result || !result.ok) {
        status("Auto Dub: terjemahan gagal · cek koneksi");
      }
    } catch (_) {
      status("Auto Dub: layanan terjemahan tidak tersedia");
    } finally {
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
