# AURA Web (Secure)

## امنیت
- CSP در `index.html`
- فقط **HTTPS** برای Base URL
- کلید در `localStorage` همان مرورگر
- دکمه 🧹 پاک‌سازی کامل کلید + چت
- `Referrer-Policy: no-referrer`

## اجرا
```bash
cd web
python -m http.server 8080
```

http://localhost:8080
