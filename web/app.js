(() => {
  const $ = (id) => document.getElementById(id);
  const messagesEl = $("messages");
  const inputEl = $("input");
  const btnSend = $("btnSend");
  const btnMic = $("btnMic");
  const modal = $("settingsModal");
  const wipeModal = $("wipeModal");

  const SYSTEM_PROMPT = `شما AURA هستید — ایجنت هوشمند شخصی الهام‌گرفته از جارویس.
مؤدب، دقیق، کمی شوخ‌طبع. همیشه At your service. به زبان کاربر پاسخ بده.`;

  let history = [];
  let busy = false;
  let recognizing = false;
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
      addSystem("تنظیمات امن ذخیره شد (فقط همین مرورگر).");
    } catch (e) {
      addSystem("خطا: " + e.message);
    }
  }

  function wipeAll() {
    STORE_KEYS.forEach((k) => localStorage.removeItem(k));
    history = [];
    wipeModal.classList.add("hidden");
    showWelcome();
    addSystem("همه داده‌های محلی AURA پاک شد.");
  }

  function showWelcome() {
    messagesEl.innerHTML = `
      <div class="welcome">
        <div style="font-size:48px">🌌</div>
        <h2>سلام، من AURA هستم</h2>
        <p>نسخه وب امن‌تر<br/>At your service.</p>
        <p style="font-size:0.85rem;margin-top:12px">
          از ⚙️ کلید API را وارد کنید (فقط HTTPS).<br/>
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
    const base = requireHttps(cfg.base);

    const msgs = [{ role: "system", content: SYSTEM_PROMPT }];
    if (toolResult) {
      msgs.push({ role: "system", content: "نتیجه ابزار:\n" + toolResult });
    }
    for (const m of history) msgs.push(m);
    msgs.push({ role: "user", content: userText });

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
        max_tokens: 2048,
      }),
    });

    const data = await res.json().catch(() => ({}));
    if (!res.ok) {
      throw new Error(data.error?.message || "API error " + res.status);
    }
    const content = data.choices?.[0]?.message?.content?.trim();
    if (!content) throw new Error("پاسخ خالی از مدل");
    return content;
  }

  function speak(text) {
    if (!window.speechSynthesis) return;
    window.speechSynthesis.cancel();
    const u = new SpeechSynthesisUtterance(text.slice(0, 400));
    u.lang = "fa-IR";
    window.speechSynthesis.speak(u);
  }

  async function send() {
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
      if (history.length > 40) history = history.slice(-40);
      $("typing")?.remove();
      addBubble("assistant", reply);
      speak(reply);
    } catch (e) {
      $("typing")?.remove();
      addBubble("assistant", "خطا: " + e.message);
    } finally {
      busy = false;
    }
  }

  function initSpeech() {
    const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
    if (!SR) return;
    recognition = new SR();
    recognition.lang = "fa-IR";
    recognition.interimResults = false;
    recognition.onresult = (ev) => {
      inputEl.value = ev.results[0][0].transcript;
      send();
    };
    recognition.onerror = () => {
      recognizing = false;
      btnMic.classList.remove("listening");
    };
    recognition.onend = () => {
      recognizing = false;
      btnMic.classList.remove("listening");
    };
  }

  btnSend.addEventListener("click", send);
  inputEl.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      send();
    }
  });

  btnMic.addEventListener("click", () => {
    if (!recognition) {
      addSystem("تشخیص گفتار در این مرورگر پشتیبانی نمی‌شود.");
      return;
    }
    if (recognizing) {
      recognition.stop();
      return;
    }
    recognizing = true;
    btnMic.classList.add("listening");
    recognition.start();
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

  initSpeech();
  showWelcome();
})();
