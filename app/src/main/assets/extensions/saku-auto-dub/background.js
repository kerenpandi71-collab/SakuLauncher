// Free translation endpoint; availability and rate limits are controlled by the provider.
browser.runtime.onMessage.addListener(async (msg) => {
  if (!msg) return;
  if (msg.type === "SAKU_SPEAK") {
    try {
      return await browser.runtime.sendNativeMessage("saku.autodub", { type: "SPEAK_TEXT", text: String(msg.text || "").slice(0, 700) });
    } catch (error) { return { ok: false, error: String(error && error.message || "TTS Android tidak tersedia") }; }
  }
  if (msg.type !== "SAKU_TRANSLATE") return;
  const text = String(msg.text || "").trim().slice(0, 700);
  if (!text) return { ok: false };
  try {
    const url = new URL("https://translate.googleapis.com/translate_a/single");
    url.searchParams.set("client", "gtx");
    url.searchParams.set("sl", "auto");
    url.searchParams.set("tl", "id");
    url.searchParams.set("dt", "t");
    url.searchParams.set("q", text);
    const response = await fetch(url.toString());
    if (!response.ok) throw new Error("HTTP " + response.status);
    const data = await response.json();
    const translated = Array.isArray(data[0])
      ? data[0].map(part => Array.isArray(part) ? (part[0] || "") : "").join("").trim()
      : "";
    return translated ? { ok: true, text: translated } : { ok: false };
  } catch (error) {
    return { ok: false, error: String(error && error.message || "Terjemahan gagal") };
  }
});
