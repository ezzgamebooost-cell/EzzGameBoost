# Game VPN — سیستم اشتراکی کانفیگ WireGuard / V2Ray برای تانل بازی

```
server/    سرور API + پنل مدیریت (Node 22، بدون وابستگی خارجی)
android/   اپ اندروید (Kotlin + Jetpack Compose)
.github/   بیلد خودکار APK با GitHub Actions
```

## ۱) راه‌اندازی سرور

```bash
cd server
cp .env.example .env      # ADMIN_PASS و JWT_SECRET را حتما عوض کنید
node server.js            # نیاز به Node 22.13 یا بالاتر
```
- پنل مدیریت: `http://SERVER:3000/admin`
- **حتما** جلوی سرور یک HTTPS بگذارید (Caddy یا Nginx + Let's Encrypt). اپ اجازه HTTP ساده نمی‌دهد.
- نمونه Caddy: `example.com { reverse_proxy localhost:3000 }`
- با Docker: `docker build -t gamevpn server && docker run -d -p 3000:3000 -v gv:/data --env-file server/.env gamevpn`

### کار با پنل
- ساخت کاربر (نام کاربری، رمز، تعداد روز)
- دکمه «+۳۰ روز» یا «± روز» (عدد منفی = کم کردن روز)
- افزودن / حذف کانفیگ برای هر کاربر (WireGuard: متن فایل `.conf` ؛ V2Ray: لینک `vless://` و مشابه، یا JSON)
- غیرفعال‌سازی، تغییر رمز، حذف کاربر
- ویرایش تعرفه‌ها و لینک تلگرام (در تب «تعرفه» اپ نمایش داده می‌شود)

وقتی اشتراک تمام شود، سرور دیگر کانفیگ نمی‌دهد (کنترل سمت سرور است، نه اپ).
**توجه:** کانفیگی که قبلا روی گوشی لود شده تا پایان اتصال کار می‌کند؛ برای قطع کامل، کلید/UUID آن را روی سرور WireGuard/Xray هم حذف یا عوض کنید.

## ۲) اپ اندروید

آدرس سرور را در `android/gradle.properties` (`SERVER_URL`) بگذارید.

### بیلد با GitHub
1. پروژه را در یک ریپوی **خصوصی** آپلود کنید.
2. Settings → Secrets and variables → Actions → Variables → متغیر `SERVER_URL`
3. تب Actions → «Build Android APK» → Run workflow (یا یک تگ مثل `v1.0` بزنید تا APK در Releases بیاید).

### بیلد محلی
Android Studio → پوشه `android` را باز کنید → Run.

### نحوه کار
- لاگین با نام کاربری/رمز؛ کانفیگ‌ها فقط از سرور می‌آیند و **روی دیسک ذخیره یا نمایش داده نمی‌شوند** و کاربر امکان افزودن کانفیگ ندارد.
- کاربر بازی‌ها را از لیست برنامه‌های گوشی تیک می‌زند؛ فقط ترافیک همان‌ها از تانل می‌گذرد (`IncludedApplications` در WireGuard و `addAllowedApplication` در VpnService).
- تب «تعرفه و خرید»: لیست پلن‌ها + دکمه تلگرام که خارج از اپ باز می‌شود.

## ۳) اتصال هسته V2Ray (کار باقی‌مانده)
WireGuard کامل است ولی هسته V2Ray باید خودتان اضافه شود چون به کتابخانه بومی (AAR) نیاز دارد:
1. یک هسته Android را اضافه کنید (مثلا AndroidLibXrayLite یا sing-box libbox؛ مجوز آن‌ها را بررسی کنید).
2. در فایل `V2RayVpnService.kt` توابع `V2RayCore.start` و `stop` را کامل کنید: لینک `vless://...` را به JSON هسته تبدیل کنید و با `tun.fd` هسته را اجرا کنید.
3. بخش TUN و محدودسازی به بازی‌های انتخابی از قبل نوشته شده است.

## نکات امنیتی
- فایل `.env` و کلیدها را هرگز commit نکنید (در `.gitignore` هست).
- رمز ادمین قوی بگذارید و پنل را در صورت امکان پشت VPN/IP محدود قرار دهید.
- برای انتشار عمومی APK را با کلید release خودتان امضا کنید.
- این کد روی Android تست نشده (سرور تست شده)؛ قبل از استفاده واقعی روی گوشی امتحان کنید.
