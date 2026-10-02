(() => {
  const $ = (id) => document.getElementById(id);
  const messagesEl = $("messages");
  const inputEl = $("input");
  const btnSend = $("btnSend");
  const btnMic = $("btnMic");
  const modal = $("settingsModal");
  const wipeModal = $("wipeModal");

  const SYSTEM_PROMPT = `شما AURA هستید — ایجنت هوشمند شخصی الهام‌گرفته از جارویس.
مؤدب، دقیق، کمی شوخ‌طبع. همیشه At your service. به زبان کاربر پاسخ بده.
پاسخ‌ها می‌توانند کامل و بدون محدودیت طول باشند.`;

  let history = [];
  let busy = false;
  let recognizing = false;
  let continuousMode = false;
  let recognition = null;

  const STORE_KEYS = ["aura_api_key", "aura_base_url", "aura_model", "aura_chat_backup"];

  function requireHttps(url) {
    const u = (url || "").trim();
    if (!u.startsWith("https://")) {
      throw new Error("فقط آدرس HTTPS مجاز است (اتصال امن به شبکه جهانی).");
    }
    return u.replace(/\/$/, "");
  }

  function loadCfg() {
    return {
      key: localStorage.getItem("aura_api_key") || "",
      base: localStorage.getItem("aura_base_url") || "https://api.x.ai/v1",
      model: localStorage.getItem("aura_model") || "grok-beta",
    };
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
      localStorage.setItem("aura_model", $("cfgModel").value.trim() || "grok-beta");
      modal.classList.add("hidden");
      addSystem("تنظیمات امن ذخیره شد (فقط همین مرورگر · HTTPS جهانی).");
    } catch (e) {
      addSystem("خطا: " + e.message);
    }
  }

  function wipeAll() {
    STORE_KEYS.forEach((k) => localStorage.removeItem(k));
    history = [];
    continuousMode = false;
    wipeModal.classList.add("hidden");
    showWelcome();
    addSystem("همه داده‌های محلی AURA پاک شد.");
  }

  function showWelcome() {
    messagesEl.innerHTML = `
      <div class="welcome">
        <div style="font-size:48px">🌌</div>
        <h2>سلام، من AURA هستم</h2>
        <p>نسخه وب · گفتار بدون محدودیت · شبکه جهانی<br/>At your service.</p>
        <p style="font-size:0.85rem;margin-top:12px">
          از ⚙️ کلید API را وارد کنید (فقط HTTPS).<br/>
          دوبار کلیک روی 🎙️ = شنیدن مداوم<br/>
          🧹 پاک‌سازی کامل کلید و چت
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
    if (t.includes("شنیدن مداوم") || t.includes("گفتار مداوم") || t.includes("صدا بدون محدودیت")) {
      continuousMode = true;
      if (recognition && !recognizing) {
        recognizing = true;
        btnMic.classList.add("listening");
        recognition.start();
      }
      return "✅ شنیدن مداوم فعال شد. بدون محدودیت صحبت کنید. برای توقف بگویید «توقف شنیدن».";
    }
    if (t.includes("توقف شنیدن") || t.includes("خاموش کردن صدا")) {
      continuousMode = false;
      if (recognition && recognizing) recognition.stop();
      return "🛑 شنیدن مداوم خاموش شد.";
    }
    if (t.includes("وضعیت شبکه") || t.includes("اینترنت")) {
      const online = navigator.onLine;
      return online
        ? "🌐 اتصال به اینترنت جهانی: ✅ آنلاین (navigator.onLine)"
        : "🌐 اتصال به اینترنت جهانی: ❌ آفلاین";
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
    if (t.includes("ادامه معاملات")) return AuraTrading.releaseKill();
    return null;
  }

  async function chat(userText, toolResult) {
    const cfg = loadCfg();
    if (!cfg.key) throw new Error("API Key تنظیم نشده. از تنظیمات وارد کنید.");
    if (!navigator.onLine) throw new Error("اتصال به اینترنت جهانی برقرار نیست.");
    const base = requireHttps(cfg.base);

    const msgs = [{ role: "system", content: SYSTEM_PROMPT }];
    if (toolResult) {
      msgs.push({ role: "system", content: "نتیجه ابزار:\n" + toolResult });
    }
    // تاریخچه گسترده‌تر — بدون محدودیت سخت
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
          max_tokens: 4096,
        }),
        signal: controller.signal,
      });

      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.error?.message || "API error " + res.status + " — شبکه جهانی را بررسی کنید");
      }
      const content = data.choices?.[0]?.message?.content?.trim();
      if (!content) throw new Error("پاسخ خالی از مدل");
      return content;
    } finally {
      clearTimeout(timeout);
    }
  }

  /** TTS بدون محدودیت طول — chunk خودکار */
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
      try {
        reply = await chat(text, toolResult);
        if (toolResult && reply.length < 40) reply = toolResult;
        else if (toolResult) reply = toolResult + "\n\n" + reply;
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
      // در حالت مداوم بعد از پاسخ دوباره گوش بده
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
    recognition.continuous = false; // هر دور جدا — continuousMode را خودمان مدیریت می‌کنیم
    recognition.maxAlternatives = 3;

    recognition.onresult = (ev) => {
      const transcript = ev.results[0][0].transcript;
      inputEl.value = transcript;
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

  // تک‌کلیک = یک‌بار شنیدن | دابل‌کلیک = شنیدن مداوم
  let lastMicClick = 0;
  btnMic.addEventListener("click", () => {
    if (!recognition) {
      addSystem("تشخیص گفتار در این مرورگر پشتیبانی نمی‌شود.");
      return;
    }
    const now = Date.now();
    if (now - lastMicClick < 400) {
      // دابل‌کلیک → continuous
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
    try {
      recognition.start();
    } catch (e) {
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

  $("btnClear").addEventListener("click", () => {
    history = [];
    showWelcome();
  });

  $("btnWipe").addEventListener("click", () => wipeModal.classList.remove("hidden"));
  $("btnCancelWipe").addEventListener("click", () => wipeModal.classList.add("hidden"));
  $("btnConfirmWipe").addEventListener("click", wipeAll);

  window.addEventListener("online", () => addSystem("🌐 اتصال به اینترنت جهانی برقرار شد."));
  window.addEventListener("offline", () => addSystem("⚠️ اتصال اینترنت قطع شد."));

  initSpeech();
  showWelcome();
})();
