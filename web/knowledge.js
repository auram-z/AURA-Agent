/**
 * AURA Online Knowledge — جستجوی آزاد وب + بسته دانش برای همگام‌سازی با اندروید
 */
window.AuraKnowledge = (() => {
  const STORE_KEY = "aura_knowledge_chunks_v2";
  const FORMAT = "aura-knowledge-pack";
  const VERSION = 2;

  function uid() {
    return "web-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 9);
  }

  function loadChunks() {
    try {
      const raw = localStorage.getItem(STORE_KEY);
      if (!raw) return [];
      return JSON.parse(raw);
    } catch {
      return [];
    }
  }

  function saveChunks(chunks) {
    localStorage.setItem(STORE_KEY, JSON.stringify(chunks.slice(-500)));
  }

  function addChunk(chunk) {
    const all = loadChunks();
    const deduped = all.filter(
      (c) =>
        !(c.title.toLowerCase() === (chunk.title || "").toLowerCase() &&
          (c.content || "").slice(0, 80) === (chunk.content || "").slice(0, 80))
    );
    deduped.push(chunk);
    saveChunks(deduped);
    return chunk;
  }

  /** جستجوی ساده DuckDuckGo Instant Answer (رایگان، بدون کلید) */
  async function searchDuckDuckGo(query) {
    const enc = encodeURIComponent(query);
    const url = `https://api.duckduckgo.com/?q=${enc}&format=json&no_html=1&skip_disambig=1`;
    const res = await fetch(url);
    if (!res.ok) return [];
    const data = await res.json();
    const hits = [];
    if (data.AbstractText) {
      hits.push({
        title: data.Heading || "خلاصه",
        snippet: data.AbstractText,
        url: data.AbstractURL || "",
        language: detectLang(data.AbstractText),
        sourceLabel: "duckduckgo",
      });
    }
    (data.RelatedTopics || []).forEach((t) => {
      if (t.Text && t.Text.length > 20) {
        const title = t.Text.split(" - ")[0].slice(0, 80);
        const snip = t.Text.includes(" - ") ? t.Text.split(" - ").slice(1).join(" - ") : t.Text;
        hits.push({
          title,
          snippet: snip.slice(0, 400),
          url: t.FirstURL || "",
          language: detectLang(t.Text),
          sourceLabel: "duckduckgo",
        });
      }
    });
    return hits.slice(0, 6);
  }

  /** ویکی‌پدیا چندزبانه */
  async function searchWikipedia(query, lang = "fa") {
    const enc = encodeURIComponent(query);
    const base =
      lang === "fa"
        ? "https://fa.wikipedia.org"
        : lang === "ar"
        ? "https://ar.wikipedia.org"
        : "https://en.wikipedia.org";
    const url = `${base}/w/api.php?action=opensearch&search=${enc}&limit=3&namespace=0&format=json&origin=*`;
    const res = await fetch(url);
    if (!res.ok) return [];
    const data = await res.json();
    const titles = data[1] || [];
    const descs = data[2] || [];
    const urls = data[3] || [];
    return titles.map((title, i) => ({
      title,
      snippet: descs[i] || "",
      url: urls[i] || "",
      language: lang,
      sourceLabel: "wikipedia",
    }));
  }

  function detectLang(text) {
    const persian = (text.match(/[\u0600-\u06FF]/g) || []).length;
    const latin = (text.match(/[a-zA-Z]/g) || []).length;
    if (persian > 5) return "fa";
    if (latin > 5) return "en";
    return "und";
  }

  function scoreSource(url, label) {
    const host = (url || "").replace(/^https?:\/\//, "").split("/")[0].toLowerCase();
    if (host.includes("wikipedia")) return 0.82;
    if (label === "wikipedia") return 0.82;
    if (label === "duckduckgo") return 0.55;
    if (host.endsWith(".gov") || host.endsWith(".edu")) return 0.85;
    return 0.5;
  }

  /** جستجوی آزاد و ذخیره در حافظه آنلاین */
  async function searchAndLearn(query) {
    if (!query || !navigator.onLine) {
      return { hits: [], message: "آفلاین یا کوئری خالی" };
    }
    const [ddg, wikiFa, wikiEn] = await Promise.all([
      searchDuckDuckGo(query).catch(() => []),
      searchWikipedia(query, "fa").catch(() => []),
      searchWikipedia(query, "en").catch(() => []),
    ]);
    const all = [...ddg, ...wikiFa, ...wikiEn];
    const unique = [];
    const seen = new Set();
    for (const h of all) {
      const key = (h.title || "").toLowerCase().slice(0, 40);
      if (seen.has(key) || (h.snippet || "").length < 30) continue;
      seen.add(key);
      unique.push(h);
    }

    let added = 0;
    const now = Date.now();
    for (const h of unique.slice(0, 8)) {
      const conf = scoreSource(h.url, h.sourceLabel);
      if (conf < 0.45) continue;
      addChunk({
        id: uid(),
        title: (h.title || "نتیجه").slice(0, 100),
        content: (h.snippet || "").slice(0, 600),
        source: h.sourceLabel,
        sourceUrl: h.url || "",
        language: h.language || "und",
        tags: ["online", "web_search"],
        confidence: conf,
        claims: [{ text: (h.snippet || "").slice(0, 200), polarity: "assert" }],
        timestamp: now,
        lastVerifiedAt: now,
        receivedAt: now,
      });
      added++;
    }

    return {
      hits: unique,
      added,
      message: `جستجو: ${unique.length} نتیجه · ${added} مورد به حافظه آنلاین اضافه شد`,
    };
  }

  /** خروجی بسته دانش برای اندروید */
  function exportPack() {
    const pack = {
      format: FORMAT,
      version: VERSION,
      exportedAt: Date.now(),
      device: "web",
      chunks: loadChunks(),
    };
    return JSON.stringify(pack, null, 2);
  }

  /** دانلود فایل بسته دانش */
  function downloadPack() {
    const data = exportPack();
    const blob = new Blob([data], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `aura-knowledge-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
    return `بسته دانش (${loadChunks().length} مورد) دانلود شد.`;
  }

  /** وارد کردن بسته از اندروید یا جای دیگر */
  function importPack(raw) {
    try {
      const pack = JSON.parse(raw);
      if (pack.format !== FORMAT) return "فرمت ناشناخته";
      const incoming = pack.chunks || [];
      let added = 0;
      for (const c of incoming) {
        addChunk({
          ...c,
          id: c.id || uid(),
          tags: [...(c.tags || []), "synced_from_android"],
        });
        added++;
      }
      return `وارد شد: ${added} مورد · مجموع: ${loadChunks().length}`;
    } catch (e) {
      return "خطا در خواندن بسته: " + e.message;
    }
  }

  function status() {
    const n = loadChunks().length;
    return `حافظه آنلاین: ${n} تکه دانش · ${navigator.onLine ? "آنلاین" : "آفلاین"}`;
  }

  function clear() {
    localStorage.removeItem(STORE_KEY);
    return "حافظه آنلاین پاک شد.";
  }

  return {
    searchAndLearn,
    exportPack,
    downloadPack,
    importPack,
    status,
    clear,
    loadChunks,
  };
})();
