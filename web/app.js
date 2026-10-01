(() => {
  const $ = (id) => document.getElementById(id);
  const messagesEl = $("messages");
  const inputEl = $("input");
  const btnSend = $("btnSend");
  const btnMic = $("btnMic");
  const btnSettings = $("btnSettings");
  const btnClear = $("btnClear");
  const modal = $("settingsModal");

  const SYSTEM_PROMPT = `\u0634\u0645\u0627 AURA \u0647\u0633\u062a\u06cc\u062f \u2014 \u0627\u06cc\u062c\u0646\u062a \u0647\u0648\u0634\u0645\u0646\u062f \u0634\u062e\u0635\u06cc \u0627\u0644\u0647\u0627\u0645\u200c\u06af\u0631\u0641\u062a\u0647 \u0627\u0632 \u062c\u0627\u0631\u0648\u06cc\u0633 (J.A.R.V.I.S).
\u0645\u0624\u062f\u0628\u060c \u062f\u0642\u06cc\u0642\u060c \u06a9\u0645\u06cc \u0634\u0648\u062e\u200c\u0637\u0628\u0639. \u0647\u0645\u06cc\u0634\u0647 \u00abAt your service\u00bb.
\u0628\u0647 \u0632\u0628\u0627\u0646 \u06a9\u0627\u0631\u0628\u0631 \u067e\u0627\u0633\u062e \u0628\u062f\u0647. \u062e\u0648\u062f \u0631\u0627 \u0641\u0642\u0637 AURA \u0628\u062f\u0627\u0646.\n\u0627\u06af\u0631 \u0646\u062a\u06cc\u062c\u0647 \u0627\u0628\u0632\u0627\u0631 \u062f\u0631 \u067e\u06cc\u0627\u0645 \u0647\u0633\u062a\u060c \u0622\u0646 \u0631\u0627 \u062f\u0631 \u067e\u0627\u0633\u062e\u062a \u062f\u0631 \u0646\u0638\u0631 \u0628\u06af\u06cc\u0631.`;

  let history = [];
  let busy = false;
  let recognizing = false;
  let recognition = null;

  function loadCfg() {
    return {
      key: localStorage.getItem("aura_api_key") || "",
      base: localStorage.getItem("aura_base_url") || "https://api.x.ai/v1",
      model: localStorage.getItem("aura_model") || "grok-beta",
    };
  }

  function saveCfg() {
    localStorage.setItem("aura_api_key", $("cfgKey").value.trim());
    localStorage.setItem("aura_base_url", $("cfgBase").value.trim() || "https://api.x.ai/v1");
    localStorage.setItem("aura_model", $("cfgModel").value.trim() || "grok-beta");
    modal.classList.add("hidden");
    addSystem("\u062a\u0646\u0638\u06cc\u0645\u0627\u062a \u0630\u062e\u06cc\u0631\u0647 \u0634\u062f.");
  }

  function showWelcome() {
    messagesEl.innerHTML = `
      <div class="welcome">
        <div style="font-size:48px">\ud83c\udf0c</div>
        <h2>\u0633\u0644\u0627\u0645\u060c \u0645\u0646 AURA \u0647\u0633\u062a\u0645</div>
        <p>\u0646\u0633\u062e\u0647 \u0648\u0628 \u2014 \u062f\u0631 \u0647\u0631 \u0645\u0631\u0648\u0631\u06af\u0631\u06cc<br/>At your service.</p>
        <p style="font-size:0.85rem;margin-top:12px">\u0627\u0628\u062a\u062f\u0627 \u0627\u0632 \u0686\u0631\u062e\u200c\u062f\u0646\u062f\u0647 \u062a\u0646\u0638\u06cc\u0645\u0627\u062a\u060c API Key \u0631\u0627 \u0648\u0627\u0631\u062f \u06a9\u0646\u06cc\u062f.<br/>
        \u062f\u0633\u062a\u0648\u0631\u0627\u062a: \u0627\u0633\u06a9\u0646 \u06a9\u0631\u06cc\u067e\u062a\u0648 \u00b7 \u0628\u06a9\u200c\u062a\u0633\u062a \u06a9\u0631\u06cc\u067e\u062a\u0648 \u00b7 \u0648\u0636\u0639\u06cc\u062a \u067e\u0648\u0631\u062a\u0641\u0648</p>
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
    if (t.includes("\u0648\u0636\u0639\u06cc\u062a") && (t.includes("\u067e\u0648\u0631\u062a\u0641\u0648") || t.includes("\u0645\u0639\u0627\u0645\u0644")))
      return AuraTrading.status();
    if (t.includes("\u0627\u0633\u06a9\u0646") && t.includes("\u06a9\u0631\u06cc\u067e\u062a\u0648"))
      return AuraTrading.scanCrypto();
    if (t.includes("\u0628\u06a9") && t.includes("\u062a\u0633\u062a") && t.includes("\u06a9\u0631\u06cc\u067e\u062a\u0648"))
      return AuraTrading.backtestBasket();
    if (t.includes("backtest") && t.includes("crypto"))
      return AuraTrading.backtestBasket();
    if ((t.includes("\u0628\u06a9") && t.includes("\u062a\u0633\u062a")) || t === "backtest")
      return AuraTrading.backtestBasket();
    if (t.includes("\u0645\u0639\u0627\u0645\u0644\u0647 \u062e\u0648\u062f\u06a9\u0627\u0631") && (t.includes("\u0631\u0648\u0634\u0646") || t.includes("\u0641\u0639\u0627\u0644")))
      return AuraTrading.setAuto(true);
    if (t.includes("\u0645\u0639\u0627\u0645\u0644\u0647 \u062e\u0648\u062f\u06a9\u0627\u0631") && (t.includes("\u062e\u0627\u0645\u0648\u0634") || t.includes("\u0648\u0642\u0641")))
      return AuraTrading.setAuto(false);
    if (t.includes("\u06a9\u06cc\u0644") || t.includes("kill"))
      return AuraTrading.killSwitch();
    if (t.includes("\u0627\u062f\u0627\u0645\u0647 \u0645\u0639\u0627\u0645\u0644\u0627\u062a"))
      return AuraTrading.releaseKill();
    return null;
  }

  async function chat(userText, toolResult) {
    const cfg = loadCfg();
    if (!cfg.key) {
      throw new Error("API Key \u062a\u0646\u0638\u06cc\u0645 \u0646\u0634\u062f\u0647. \u0627\u0632 \u062a\u0646\u0638\u06cc\u0645\u0627\u062a \u0648\u0627\u0631\u062f \u06a9\u0646\u06cc\u062f.");
    }

    const msgs = [{ role: "system", content: SYSTEM_PROMPT }];
    if (toolResult) {
      msgs.push({
        role: "system",
        content: "\u0646\u062a\u06cc\u062c\u0647 \u0627\u0628\u0632\u0627\u0631 \u0628\u0631\u0627\u06cc \u06a9\u0627\u0631\u0628\u0631:\n" + toolResult,
      });
    }
    for (const m of history) msgs.push(m);
    msgs.push({ role: "user", content: userText });

    const url = cfg.base.replace(/\/$/, "") + "/chat/completions";
    const res = await fetch(url, {
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
      throw new Error(data.error?.message || data.message || "API error " + res.status);
    }
    const content = data.choices?.[0]?.message?.content?.trim();
    if (!content) throw new Error("\u067e\u0627\u0633\u062e \u062e\u0627\u0644\u06cc \u0627\u0632 \u0645\u062f\u0644");
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
    typing.textContent = "AURA \u062f\u0631 \u062d\u0627\u0644 \u0641\u06a9\u0631 \u06a9\u0631\u062f\u0646...";
    messagesEl.appendChild(typing);

    try {
      const toolResult = await runTool(text);
      let reply;
      try {
        reply = await chat(text, toolResult);
        if (toolResult && reply.length < 40) reply = toolResult;
        else if (toolResult) reply = toolResult + "\n\n" + reply;
      } catch (e) {
        reply = toolResult || ("\u062e\u0637\u0627: " + e.message);
      }

      history.push({ role: "user", content: text });
      history.push({ role: "assistant", content: reply });
      if (history.length > 40) history = history.slice(-40);

      $("typing")?.remove();
      addBubble("assistant", reply);
      speak(reply);
    } catch (e) {
      $("typing")?.remove();
      addBubble("assistant", "\u062e\u0637\u0627: " + e.message);
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
      const t = ev.results[0][0].transcript;
      inputEl.value = t;
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
      addSystem("\u062a\u0634\u062e\u06cc\u0635 \u06af\u0641\u062a\u0627\u0631 \u062f\u0631 \u0627\u06cc\u0646 \u0645\u0631\u0648\u0631\u06af\u0631 \u067e\u0634\u062a\u06cc\u0628\u0627\u0646\u06cc \u0646\u0645\u06cc\u200c\u0634\u0648\u062f.");
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

  btnSettings.addEventListener("click", () => {
    const c = loadCfg();
    $("cfgKey").value = c.key;
    $("cfgBase").value = c.base;
    $("cfgModel").value = c.model;
    modal.classList.remove("hidden");
  });
  $("btnCloseCfg").addEventListener("click", () => modal.classList.add("hidden"));
  $("btnSaveCfg").addEventListener("click", saveCfg);
  btnClear.addEventListener("click", () => {
    history = [];
    showWelcome();
  });

  initSpeech();
  showWelcome();
})();
