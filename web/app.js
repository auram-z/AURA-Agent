(() => {
  const $ = (id) => document.getElementById(id);
  const messagesEl = $("messages");
  const inputEl = $("input");
  const btnSend = $("btnSend");
  const btnMic = $("btnMic");
  const modal = $("settingsModal");
  const wipeModal = $("wipeModal");

  const SYSTEM_PROMPT = `شما AURA هستید — هوش مصنوعی پیشرفته با دسترسی به اینترنت آزاد.
مؤدب، دقیق، کمی شوخ‌طبع. At your service. به زبان کاربر پاسخ بده.
اگر کاربر درخواست جستجو یا دانش کرد، از ابزار دانش آنلاین استفاده شده است.`;

  const DEFAULT_MODEL = "grok-4.7";

  let history = [];
  let busy = false;
  let recognizing = false;
  let continuousMode = false;
  let recognition = null;

  const STORE_KEYS = ["aura_api_key", "aura_base_url", "aura_model", "aura_chat_backup"];

  function requireHttps(url) {
    const u = (url || "").trim();
    if (!u.startsWith("https://")) {
      throw new Error("فقط آدرس HTTPS مجاز است.");
    }
    return u.replace(/\/$/, "");
  }

  function loadCfg() {
    let model = localStorage.getItem("aura_model") || DEFAULT_MODEL;
    if (model === "grok-beta") model = DEFAULT_MODEL;
    return {
      key: localStorage.getItem("aura_api_key") || "",
      base: localStorage.getItem("aura_base_url") || "https://api.x.ai/v1",
      model,
    };
  }

  async function tryAutoUpgradeModel(cfg) {
    if (!cfg.key || !navigator.onLine) return cfg;
    try {
      const base = requireHttps(cfg.base);
      const res = await fetch(base + "/models", {
        headers: { Authorization: "Bearer " + cfg.key },
      });
      if (!res.ok) return cfg;
      const data = await res.json();
      const ids = (data.data || []).map((m) => m.id);
      const preferred = ["grok-4.7", "grok-4-latest", "grok-4.6", "grok-4.5", "grok-4"];
      for (const p of preferred) {
        const found = ids.find((id) => id.toLowerCase() === p);
        if (found && found !== cfg.model) {
          localStorage.setItem("aura_model", found);
          addSystem("✅ مدل ارتقا یافت: " + cfg.model + " → " + found);
          return { ...cfg, model: found };
        }
      }
      if (ids.includes(cfg.model)) return cfg;
      const best = preferred.find((p) => ids.includes(p));
      if (best) {
        localStorage.setItem("aura_model", best);
        return { ...cfg, model: best };
      }
    } catch (_) {}
    return cfg;
  }

  function saveCfg() {
    try {
      const base = requireHttps($("cfgBase").value.trim() || "https://api.x.ai/v1");
      const key = $("cfgKey").value.trim();
      if (!key) {
        addSystem("کلید API نمی‌تواند خالی باشد.");
        return;
      }
      localStorage.setItem("aura_api_key", key);
      localStorage.setItem("aura_base_url", base);
      localStorage.setItem("aura_model", $("cfgModel").value.trim() || DEFAULT_MODEL);
      modal.classList.add("hidden");
      addSystem("تنظیمات ذخیره شد · مدل: " + (localStorage.getItem("aura_model") || DEFAULT_MODEL));
    } catch (e) {
      addSystem("خطا: " + e.message);
    }
  }

  function wipeAll() {
    STORE_KEYS.forEach((k) => localStorage.removeItem(k));
    history = [];
    continuousMode = false;
    if (window.AuraKnowledge) AuraKnowledge.clear();
    wipeModal.classList.add("hidden");
    showWelcome();
    addSystem("همه داده‌های محلی پاک شد.");
  }

  function showWelcome() {
    messagesEl.innerHTML = `
      <div class="welcome">
        <div style="font-size:48px">🌌</div>
        <h2>سلام، من AURA آنلاین هستم</h2>
        <p>جستجوی آزاد اینترنت · همگام‌سازی با اندروید<br/>At your service.</p>
        <p style="font-size:13px;opacity:.7;margin-top:12px">
          دستورات: جستجو کن … · وضعیت دانش · دانلود بسته دانش · وارد کردن بسته
        </p>
      </div>`;
  }

  function addBubble(role, text) {
    const welcome = messagesEl.querySelector(".welcome");
    if (welcome) welcome.remove();
    const div = document.createElement("div");
    div.className = "bubble " + role;
    div.textContent = text;
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function addSystem(text) {
    const div = document.createElement("div");
    div.className = "bubble system";
    div.textContent = text;
    messagesEl.appendChild(div);
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  async function runTool(text) {
    const t = text.trim().toLowerCase();
    const original = text.trim();

    // —— دانش آنلاین و همگام‌سازی ——
    if (window.AuraKnowledge) {
      if (t.startsWith("جستجو کن") || t.startsWith("جستجو:") || t.startsWith("search ") ||
          t.startsWith("سرچ کن") || t.includes("در اینترنت جستجو")) {
        const q = original
          .replace(/^(جستجو کن|جستجو:|سرچ کن|search)\s*/i, "")
          .replace(/در اینترنت جستجو( کن)?\s*/i, "")
          .trim();
        if (q.length < 2) return "موضوع جستجو را بنویس. مثال: جستجو کن هوش مصنوعی";
        const r = await AuraKnowledge.searchAndLearn(q);
        const lines = (r.hits || []).slice(0, 5).map((h, i) =>
          `${i + 1}. ${h.title}\n   ${(h.snippet || "").slice(0, 180)}`
        );
        return (r.message || "") + (lines.length ? "\n\n" + lines.join("\n\n") : "");
      }
      if (t.includes("وضعیت دانش") || t.includes("حافظه آنلاین") || t.includes("knowledge status")) {
        return AuraKnowledge.status();
      }
      if (t.includes("دانلود بسته") || t.includes("خروجی دانش") || t.includes("export knowledge") ||
          t.includes("بسته دانش")) {
        return AuraKnowledge.downloadPack();
      }
      if (t.includes("پاک کردن دانش") || t.includes("پاک دانش آنلاین")) {
        return AuraKnowledge.clear();
      }
    }

    if (t.includes("ارتقای مدل") || t.includes("ارتقا مدل")) {
      const cfg = loadCfg();
      const next = await tryAutoUpgradeModel(cfg);
      return "مدل فعال: " + next.model;
    }
    if (t.includes("مدل فعلی") || t === "مدل؟") {
      return "مدل فعال: " + loadCfg().model;
    }
    if (t.includes("شنیدن مداوم") || t.includes("گفتار مداوم")) {
      continuousMode = true;
      if (recognition && !recognizing) {
        recognizing = true;
        btnMic.classList.add("listening");
        recognition.start();
      }
      return "✅ شنیدن مداوم فعال شد.";
    }
    if (t.includes("توقف شنیدن")) {
      continuousMode = false;
      if (recognition && recognizing) recognition.stop();
      return "🛑 شنیدن مداوم خاموش شد.";
    }
    if (t.includes("وضعیت شبکه") || t.includes("اینترنت")) {
      return navigator.onLine ? "🌐 آنلاین" : "🌐 آفلاین";
    }

    if (!window.AuraTrading) return null;
    if (t.includes("وضعیت") && (t.includes("پورتفو") || t.includes("معامل")))
      return AuraTrading.status();
    if (t.includes("اسکن") && t.includes("کریپتو")) return AuraTrading.scanCrypto();
    if ((t.includes("بک") && t.includes("تست")) || t.includes("backtest"))
      return AuraTrading.backtestBasket();
    if (t.includes("معامله خودکار") && (t.includes("روشن") || t.includes("فعال")))
      return AuraTrading.setAuto(true);
    if (t.includes("معامله خودکار") && (t.includes("خاموش") || t.includes("وقف")))
      return AuraTrading.setAuto(false);
    if (t.includes("کیل") || t.includes("kill")) return AuraTrading.killSwitch();
    return null;
  }

  async function chat(userText, toolResult) {
    let cfg = loadCfg();
    cfg = await tryAutoUpgradeModel(cfg);
    if (!cfg.key) throw new Error("API Key تنظیم نشده. (جستجو و دانش آنلاین بدون کلید هم کار می‌کند)");
    if (!navigator.onLine) throw new Error("آفلاین هستید.");
    const base = requireHttps(cfg.base);

    const msgs = [{ role: "system", content: SYSTEM_PROMPT + "\nمدل: " + cfg.model }];
    if (toolResult) msgs.push({ role: "system", content: "نتیجه ابزار:\n" + toolResult });
    const hist = history.length > 50 ? history.slice(-50) : history;
    for (const m of hist) msgs.push(m);
    msgs.push({ role: "user", content: userText });

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 120000);

    try {
      const res = await fetch(base + "/chat/completions", {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          Authorization: "Bearer " + cfg.key,
        },
        body: JSON.stringify({
          model: cfg.model,
          messages: msgs,
          temperature: 0.7,
          max_tokens: 8192,
        }),
        signal: controller.signal,
      });

      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.error?.message || "API error " + res.status);
      }
      const content = data.choices?.[0]?.message?.content?.trim();
      if (!content) throw new Error("پاسخ خالی");
      return content;
    } finally {
      clearTimeout(timeout);
    }
  }

  function speak(text) {
    if (!window.speechSynthesis || !text) return;
    window.speechSynthesis.cancel();
    const chunks = chunkText(text, 400);
    let i = 0;
    function next() {
      if (i >= chunks.length) return;
      const u = new SpeechSynthesisUtterance(chunks[i++]);
      u.lang = "fa-IR";
      u.onend = next;
      u.onerror = next;
      window.speechSynthesis.speak(u);
    }
    next();
  }

  function chunkText(text, maxLen) {
    if (text.length <= maxLen) return [text];
    const chunks = [];
    let remaining = text.trim();
    while (remaining.length > 0) {
      if (remaining.length <= maxLen) {
        chunks.push(remaining);
        break;
      }
      let cut = remaining.lastIndexOf(".", maxLen);
      if (cut < maxLen / 3) cut = remaining.lastIndexOf("؟", maxLen);
      if (cut < maxLen / 3) cut = remaining.lastIndexOf("\n", maxLen);
      if (cut < maxLen / 3) cut = remaining.lastIndexOf(" ", maxLen);
      if (cut < maxLen / 3) cut = maxLen;
      chunks.push(remaining.slice(0, cut + 1).trim());
      remaining = remaining.slice(cut + 1).trim();
    }
    return chunks.filter(Boolean);
  }

  async function send(fromVoice) {
    const text = inputEl.value.trim();
    if (!text || busy) return;
    busy = true;
    inputEl.value = "";
    addBubble("user", text);

    const typing = document.createElement("div");
    typing.className = "typing";
    typing.id = "typing";
    typing.textContent = "AURA در حال فکر کردن...";
    messagesEl.appendChild(typing);

    try {
      const toolResult = await runTool(text);
      let reply;
      // جستجو و دانش بدون نیاز به API key
      const isKnowledgeOnly = toolResult && (
        text.toLowerCase().includes("جستجو") ||
        text.toLowerCase().includes("سرچ") ||
        text.toLowerCase().includes("دانش") ||
        text.toLowerCase().includes("بسته")
      );
      try {
        if (isKnowledgeOnly && toolResult) {
          reply = toolResult;
        } else {
          reply = await chat(text, toolResult);
          if (toolResult && reply.length < 40) reply = toolResult;
          else if (toolResult) reply = toolResult + "\n\n" + reply;
        }
      } catch (e) {
        reply = toolResult || ("خطا: " + e.message);
      }
      history.push({ role: "user", content: text });
      history.push({ role: "assistant", content: reply });
      if (history.length > 80) history = history.slice(-80);
      $("typing")?.remove();
      addBubble("assistant", reply);
      speak(reply);
    } catch (e) {
      $("typing")?.remove();
      addBubble("assistant", "خطا: " + e.message);
    } finally {
      busy = false;
      if (continuousMode && fromVoice && recognition && !recognizing) {
        setTimeout(() => {
          recognizing = true;
          btnMic.classList.add("listening");
          try { recognition.start(); } catch (_) {}
        }, 800);
      }
    }
  }

  function initSpeech() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;
    recognition = new SR();
    recognition.lang = "fa-IR";
    recognition.interimResults = false;
    recognition.continuous = false;
    recognition.maxAlternatives = 3;

    recognition.onresult = (ev) => {
      inputEl.value = ev.results[0][0].transcript;
      recognizing = false;
      btnMic.classList.remove("listening");
      send(true);
    };
    recognition.onerror = (ev) => {
      recognizing = false;
      btnMic.classList.remove("listening");
      if (continuousMode && (ev.error === "no-speech" || ev.error === "aborted")) {
        setTimeout(() => {
          if (continuousMode) {
            recognizing = true;
            btnMic.classList.add("listening");
            try { recognition.start(); } catch (_) {}
          }
        }, 400);
      }
    };
    recognition.onend = () => {
      recognizing = false;
      btnMic.classList.remove("listening");
      if (continuousMode) {
        setTimeout(() => {
          if (continuousMode && !busy) {
            recognizing = true;
            btnMic.classList.add("listening");
            try { recognition.start(); } catch (_) {}
          }
        }, 500);
      }
    };
  }

  btnSend.addEventListener("click", () => send(false));
  inputEl.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send(false);
    }
  });

  let lastMicClick = 0;
  btnMic.addEventListener("click", () => {
    if (!recognition) {
      addSystem("تشخیص گفتار پشتیبانی نمی‌شود.");
      return;
    }
    const now = Date.now();
    if (now - lastMicClick < 400) {
      continuousMode = true;
      addSystem("✅ شنیدن مداوم فعال شد.");
    }
    lastMicClick = now;
    if (recognizing) {
      continuousMode = false;
      recognition.stop();
      return;
    }
    recognizing = true;
    btnMic.classList.add("listening");
    try { recognition.start(); } catch (e) {
      recognizing = false;
      btnMic.classList.remove("listening");
    }
  });

  $("btnSettings").addEventListener("click", () => {
    const c = loadCfg();
    $("cfgKey").value = c.key;
    $("cfgBase").value = c.base;
    $("cfgModel").value = c.model;
    modal.classList.remove("hidden");
  });
  $("btnCloseCfg").addEventListener("click", () => modal.classList.add("hidden"));
  $("btnSaveCfg").addEventListener("click", saveCfg);
  $("btnClear").addEventListener("click", () => { history = []; showWelcome(); });
  $("btnWipe").addEventListener("click", () => wipeModal.classList.remove("hidden"));
  $("btnCancelWipe").addEventListener("click", () => wipeModal.classList.add("hidden"));
  $("btnConfirmWipe").addEventListener("click", wipeAll);

  window.addEventListener("online", () => addSystem("🌐 آنلاین — می‌توانی با اندروید همگام شوی"));
  window.addEventListener("offline", () => addSystem("⚠️ آفلاین"));

  initSpeech();
  showWelcome();
  tryAutoUpgradeModel(loadCfg());
})();
