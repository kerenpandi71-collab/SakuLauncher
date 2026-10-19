// Terjemahan gratis tanpa API key; endpoint tidak resmi dan dapat dibatasi.
browser.runtime.onMessage.addListener(async msg => {
  if (!msg || msg.type !== "TRANSLATE") return;
  const text = String(msg.text || "").trim().slice(0, 900);
  if (!text) return { ok: false };
  try {
    const u = new URL("https://translate.googleapis.com/translate_a/single");
    u.searchParams.set("client", "gtx"); u.searchParams.set("sl", "auto");
    u.searchParams.set("tl", "id"); u.searchParams.set("dt", "t"); u.searchParams.set("q", text);
    const r = await fetch(u.toString());
    if (!r.ok) throw new Error("HTTP " + r.status);
    const data = await r.json();
    const translated = Array.isArray(data[0]) ? data[0].map(x => Array.isArray(x) ? x[0] || "" : "").join("").trim() : "";
    return translated ? { ok: true, text: translated } : { ok: false };
  } catch (e) { return { ok: false, error: e.message || "Terjemahan gagal" }; }
});
browser.runtime.onInstalled.addListener(() => browser.storage.local.get({enabled:false,rate:1,volume:1}).then(p => browser.storage.local.set(p)));