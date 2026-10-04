# راه‌اندازی PaddleOCR‑VL برای TajeriTools Price Manager

نسخه 2.1.0 برنامه می‌تواند قبل از Gemini از PaddleOCR‑VL استفاده کند. این موتور روی کامپیوتر/سرور شما اجرا می‌شود و APK از طریق شبکه به آن وصل می‌شود.

## روش پیشنهادی برای شروع روی ویندوز

پیش‌نیاز: Python 3.9 تا 3.13.

در PowerShell:

```powershell
python -m venv .venv_paddleocr
.\.venv_paddleocr\Scripts\Activate.ps1
python -m pip install --upgrade pip
python -m pip install paddlepaddle==3.2.1 -i https://www.paddlepaddle.org.cn/packages/stable/cpu/
python -m pip install -U "paddleocr[doc-parser]"
paddlex --install serving
paddlex --serve --pipeline PaddleOCR-VL --host 0.0.0.0 --port 8080
```

روی سیستم دارای NVIDIA GPU، به جای بسته CPU باید نسخه مناسب `paddlepaddle-gpu` برای CUDA نصب شود. مستندات رسمی PaddleOCR برای CUDA 12.6 نمونه `paddlepaddle-gpu==3.2.1` را ارائه می‌کند.

## اتصال گوشی

1. گوشی و کامپیوتر را به یک Wi‑Fi وصل کنید.
2. در ویندوز `ipconfig` اجرا کنید و IPv4 کامپیوتر را پیدا کنید؛ مثال: `192.168.1.20`.
3. در APK وارد تب **Paddle** شوید.
4. آدرس زیر را وارد کنید:
   `http://192.168.1.20:8080`
5. **ذخیره** و سپس **تست اتصال** را بزنید.
6. اگر اتصال موفق بود، PDF یا عکس را از تب فایل‌ها دوباره وارد کنید.

## ترتیب پردازش در APK

1. PaddleOCR‑VL
2. parser محلی TajeriTools برای اتصال نام + مدل + قیمت
3. Gemini فقط در صورت کمبود نتیجه و فقط اگر API Key تنظیم شده باشد
4. parser محلی قدیمی در نبود AI

برای PDFهای بزرگ، برنامه فایل را به قطعه‌های 4 صفحه‌ای تقسیم می‌کند و هر قطعه را جدا به `POST /layout-parsing` می‌فرستد. این کار برای فایل‌های حجیم مناسب‌تر از ارسال یک‌جای PDF است.

## نکته امنیتی

اتصال HTTP فقط برای شبکه محلی مورد اعتماد در نظر گرفته شده است. برای سرور اینترنتی، HTTPS/VPN یا reverse proxy دارای احراز هویت استفاده کنید. سرویس خام PaddleOCR را بدون محافظ روی اینترنت عمومی باز نکنید.

## API مورد استفاده

APK از API رسمی PaddleOCR‑VL استفاده می‌کند:

- `GET /health` برای تست اتصال
- `POST /layout-parsing`
- PDF با `fileType=0`
- تصویر با `fileType=1`
- فایل به صورت Base64 ارسال می‌شود
- `visualize=false` و `returnMarkdownImages=false` برای کاهش حجم پاسخ
