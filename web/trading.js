/** Paper trading + simple backtest for AURA Web */
(function (global) {
  const INITIAL = 100_000_000;
  let cash = INITIAL;
  let positions = {};
  let autoOn = false;
  let kill = false;
  const history = [];

  const CRYPTO = ["BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT", "XRPUSDT"];

  function fmt(n) {
    return n.toLocaleString("en-US", { maximumFractionDigits: 2 });
  }

  async function binancePrice(symbol) {
    const r = await fetch("https://api.binance.com/api/v3/ticker/price?symbol=" + symbol);
    if (!r.ok) throw new Error("Binance error");
    const j = await r.json();
    return parseFloat(j.price);
  }

  async function binanceKlines(symbol, limit = 100) {
    const r = await fetch(
      `https://api.binance.com/api/v3/klines?symbol=${symbol}&interval=1h&limit=${limit}`
    );
    if (!r.ok) return [];
    const raw = await r.json();
    return raw.map((row) => ({
      open: +row[1],
      high: +row[2],
      low: +row[3],
      close: +row[4],
      volume: +row[5],
    }));
  }

  function sma(arr, p) {
    if (arr.length < p) return null;
    const s = arr.slice(-p);
    return s.reduce((a, b) => a + b, 0) / p;
  }

  function rsi(closes, period = 14) {
    if (closes.length <= period) return null;
    let gains = 0, losses = 0;
    for (let i = closes.length - period; i < closes.length; i++) {
      const d = closes[i] - closes[i - 1];
      if (d >= 0) gains += d;
      else losses -= d;
    }
    const ag = gains / period;
    const al = losses / period;
    if (al === 0) return 100;
    const rs = ag / al;
    return 100 - 100 / (1 + rs);
  }

  function signalFromCandles(closes) {
    if (closes.length < 22) return null;
    const fast = sma(closes, 7);
    const slow = sma(closes, 21);
    const prevFast = sma(closes.slice(0, -1), 7);
    const prevSlow = sma(closes.slice(0, -1), 21);
    const r = rsi(closes, 14);
    if (fast == null || slow == null || r == null) return null;

    let side = null;
    let conf = 0.5;
    const reasons = [];

    if (prevFast != null && prevSlow != null) {
      if (prevFast <= prevSlow && fast > slow) {
        side = "BUY"; conf += 0.2; reasons.push("\u062a\u0642\u0627\u0637\u0639 \u0635\u0639\u0648\u062f\u06cc SMA");
      } else if (prevFast >= prevSlow && fast < slow) {
        side = "SELL"; conf += 0.2; reasons.push("\u062a\u0642\u0627\u0637\u0639 \u0646\u0632\u0648\u0644\u06cc SMA");
      }
    }
    if (r < 30) {
      if (!side) side = "BUY";
      if (side === "BUY") conf += 0.15;
      reasons.push("RSI \u062a\u0634\u0646\u0647\u200c\u0641\u0631\u0648\u0634 (" + r.toFixed(0) + ")");
    } else if (r > 70) {
      if (!side) side = "SELL";
      if (side === "SELL") conf += 0.15;
      reasons.push("RSI \u062a\u0634\u0646\u0647\u200c\u062e\u0631\u06cc\u062f (" + r.toFixed(0) + ")");
    }

    conf = Math.min(0.95, conf);
    if (!side || conf < 0.62) return null;
    return { side, confidence: conf, reason: reasons.join(" | ") };
  }

  function portfolioValue(prices) {
    let v = cash;
    for (const [sym, pos] of Object.entries(positions)) {
      const px = prices[sym] ?? pos.entry;
      v += pos.qty * px;
    }
    return v;
  }

  async function status() {
    const prices = {};
    for (const s of Object.keys(positions)) {
      try { prices[s] = await binancePrice(s); } catch (_) {}
    }
    const total = portfolioValue(prices);
    let lines = [
      "\ud83c\udf0c AURA Trading (Web / Paper)",
      "\u2501".repeat(14),
      "\u062d\u0627\u0644\u062a: \ud83d\udcdd Paper",
      "\u062e\u0648\u062f\u06a9\u0627\u0631: " + (autoOn ? "\u2705" : "\u23f8\ufe0f"),
      "Kill Switch: " + (kill ? "\ud83d\uded1" : "\u2705"),
      "\u0646\u0642\u062f: " + fmt(cash),
      "\u0627\u0631\u0632\u0634 \u06a9\u0644: " + fmt(total),
      "\u067e\u0648\u0632\u06cc\u0634\u0646\u200c\u0647\u0627: " + Object.keys(positions).length,
    ];
    for (const [sym, pos] of Object.entries(positions)) {
      const px = prices[sym] ?? pos.entry;
      const pnl = ((px - pos.entry) / pos.entry) * 100;
      lines.push(`\u2022 ${sym}: ${pos.qty.toFixed(4)} @ ${fmt(pos.entry)} \u2192 ${fmt(px)} (${pnl >= 0 ? "+" : ""}${pnl.toFixed(1)}%)`);
    }
    return lines.join("\n");
  }

  async function scanCrypto() {
    if (kill) return "\ud83d\uded1 Kill Switch \u0641\u0639\u0627\u0644 \u0627\u0633\u062a.";
    const out = ["\ud83d\udd0d \u0627\u0633\u06a9\u0646 \u06a9\u0631\u06cc\u067e\u062a\u0648 (Binance)", ""];
    let executed = 0;
    for (const sym of CRYPTO) {
      try {
        const price = await binancePrice(sym);
        const kl = await binanceKlines(sym, 50);
        const closes = kl.map((c) => c.close);
        const sig = signalFromCandles(closes);
        const chg = closes.length >= 2 ? ((price - closes[0]) / closes[0]) * 100 : 0;
        const line = `${sym}: ${fmt(price)} (${chg >= 0 ? "+" : ""}${chg.toFixed(1)}%)`;
        if (!sig) {
          out.push("\u23f8\ufe0f " + line + " \u2014 \u0628\u062f\u0648\u0646 \u0633\u06cc\u06af\u0646\u0627\u0644");
          continue;
        }
        out.push(
          (autoOn ? "\u2705 " : "\ud83d\udcca ") +
            line +
            ` \u2192 ${sig.side} (${(sig.confidence * 100).toFixed(0)}%) | ${sig.reason}`
        );
        if (autoOn && sig.side === "BUY" && !positions[sym]) {
          const risk = portfolioValue({}) * 0.015;
          const stopDist = price * 0.02;
          const qty = risk / stopDist;
          if (qty * price <= cash) {
            cash -= qty * price;
            positions[sym] = { qty, entry: price };
            history.push({ sym, side: "BUY", price, qty });
            executed++;
          }
        }
      } catch (e) {
        out.push("\u274c " + sym + ": " + e.message);
      }
    }
    if (executed) out.push("\n\ud83d\udcc8 " + executed + " \u0645\u0639\u0627\u0645\u0644\u0647 Paper \u0627\u062c\u0631\u0627 \u0634\u062f.");
    return out.join("\n");
  }

  async function backtestBasket() {
    const lines = ["\ud83d\udcc8 \u0628\u06a9\u200c\u062a\u0633\u062a \u0633\u0628\u062f \u06a9\u0631\u06cc\u067e\u062a\u0648 (SMA+RSI)", ""];
    const pnls = [];
    for (const sym of CRYPTO) {
      try {
        const kl = await binanceKlines(sym, 100);
        const res = backtestSymbol(sym, kl);
        pnls.push(res.pnlPct);
        lines.push(
          `${sym}: ${res.pnlPct >= 0 ? "+" : ""}${res.pnlPct.toFixed(2)}% | WR ${res.winRate.toFixed(0)}% | ${res.trades} trades | DD ${res.maxDd.toFixed(1)}%`
        );
      } catch (e) {
        lines.push(sym + ": \u062e\u0637\u0627 " + e.message);
      }
    }
    if (pnls.length) {
      const avg = pnls.reduce((a, b) => a + b, 0) / pnls.length;
      lines.push("\n\u0645\u06cc\u0627\u0646\u06af\u06cc\u0646 \u0628\u0627\u0632\u062f\u0647: " + (avg >= 0 ? "+" : "") + avg.toFixed(2) + "%");
    }
    return lines.join("\n");
  }

  function backtestSymbol(sym, candles) {
    if (candles.length < 30) return { pnlPct: 0, winRate: 0, trades: 0, maxDd: 0 };
    let capital = INITIAL;
    let peak = capital;
    let maxDd = 0;
    let wins = 0, losses = 0, tradeCount = 0;
    let open = null;

    for (let i = 21; i < candles.length; i++) {
      const closes = candles.slice(0, i + 1).map((c) => c.close);
      const price = candles[i].close;

      if (open) {
        let exit = false;
        let exitPx = price;
        if (open.side === "BUY") {
          if (price <= open.sl) { exit = true; exitPx = open.sl; }
          else if (price >= open.tp) { exit = true; exitPx = open.tp; }
        }
        if (!exit && i === candles.length - 1) exit = true;
        if (exit) {
          const pnl = (exitPx - open.entry) * open.qty;
          capital += pnl;
          tradeCount++;
          if (pnl > 0) wins++; else losses++;
          peak = Math.max(peak, capital);
          const dd = peak > 0 ? ((peak - capital) / peak) * 100 : 0;
          if (dd > maxDd) maxDd = dd;
          open = null;
        }
      }

      if (!open && i < candles.length - 1) {
        const sig = signalFromCandles(closes);
        if (sig && sig.side === "BUY") {
          const sl = price * 0.98;
          const tp = price * 1.035;
          const stopDist = Math.abs(price - sl);
          const qty = (capital * 0.015) / stopDist;
          if (qty > 0 && qty * price <= capital * 0.2) {
            open = { side: "BUY", entry: price, qty, sl, tp };
          }
        }
      }
    }

    const pnlPct = ((capital - INITIAL) / INITIAL) * 100;
    const winRate = tradeCount ? (wins / tradeCount) * 100 : 0;
    return { pnlPct, winRate, trades: tradeCount, maxDd };
  }

  function setAuto(on) {
    if (kill && on) return "\ud83d\uded1 \u0627\u0648\u0644 Kill Switch \u0631\u0627 \u062e\u0627\u0645\u0648\u0634 \u06a9\u0646\u06cc\u062f.";
    autoOn = on;
    return on
      ? "\u2705 \u0645\u0639\u0627\u0645\u0644\u0647 \u062e\u0648\u062f\u06a9\u0627\u0631 Paper \u0641\u0639\u0627\u0644 \u0634\u062f."
      : "\ud83d\uded1 \u0645\u0639\u0627\u0645\u0644\u0647 \u062e\u0648\u062f\u06a9\u0627\u0631 \u062e\u0627\u0645\u0648\u0634 \u0634\u062f.";
  }

  function killSwitch() {
    kill = true;
    autoOn = false;
    return "\ud83d\uded1 Kill Switch \u0641\u0639\u0627\u0644 \u0634\u062f. \u0647\u06cc\u0686 \u0645\u0639\u0627\u0645\u0644\u0647 \u062c\u062f\u06cc\u062f\u06cc \u0627\u062c\u0631\u0627 \u0646\u0645\u06cc\u200c\u0634\u0648\u062f.";
  }

  function releaseKill() {
    kill = false;
    return "\u2705 Kill Switch \u062e\u0627\u0645\u0648\u0634 \u0634\u062f.";
  }

  global.AuraTrading = {
    status,
    scanCrypto,
    backtestBasket,
    setAuto,
    killSwitch,
    releaseKill,
  };
})(window);
