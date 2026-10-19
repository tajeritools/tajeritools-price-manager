"""Offline AI bridge for the existing TajeriTools Price Manager desktop app."""
import json
import threading
import webbrowser
import tkinter as tk
from tkinter import ttk, messagebox
import requests
from tajeritools_price_manager import App as BaseApp, APP_NAME

LOCAL = "http://127.0.0.1:8002"
TIMEOUT = (3, 100)

class AIEnabledApp(BaseApp):
    def __init__(self):
        super().__init__()
        self.title(APP_NAME + " — Offline AI")
        self.make_ai_tab()

    def make_ai_tab(self):
        tab = ttk.Frame(self.nb)
        self.nb.add(tab, text="هوش مصنوعی آفلاین")
        ttk.Label(tab, text="اتصال به Tajeri AI روی همین لپ‌تاپ | تغییرات سایت فقط پس از تایید",
                  font=("Segoe UI", 12)).pack(pady=12)
        top = ttk.Frame(tab)
        top.pack(fill="x", padx=12)
        self.ai_status = tk.StringVar(value="برای بررسی اتصال کلیک کنید")
        ttk.Button(top, text="بررسی اتصال", command=self.check_ai).pack(side="right", padx=4)
        ttk.Button(top, text="بازکردن Store Manager", command=lambda: webbrowser.open(LOCAL+"/studio")).pack(side="right", padx=4)
        ttk.Label(tab, textvariable=self.ai_status).pack(pady=8)
        self.ai_prompt = tk.Text(tab, height=7, wrap="word")
        self.ai_prompt.pack(fill="x", padx=14, pady=8)
        commands = ttk.Frame(tab)
        commands.pack(fill="x", padx=10)
        ttk.Button(commands, text="پرسش از Qwen آفلاین", command=self.ask_ai).pack(side="right", padx=4)
        ttk.Button(commands, text="طرح عملیات (بدون اعمال)", command=self.plan_ai).pack(side="right", padx=4)
        ttk.Button(commands, text="پاک کردن متن و پاسخ", command=self.clear_ai).pack(side="right", padx=4)
        self.ai_output = tk.Text(tab, wrap="word", height=15)
        self.ai_output.pack(fill="both", expand=True, padx=14, pady=8)
        ttk.Label(tab, text="اگر سرویس آفلاین اجرا نباشد، میان‌بر Tajeri AI - Store and Photos را روشن کنید.").pack(pady=6)

    def ai_background(self, call):
        self.ai_status.set("در حال پردازش؛ برنامه پاسخ‌گو می‌ماند...")
        def work():
            try:
                result = call()
                self.after(0, lambda: self.ai_done(result, None))
            except Exception as err:
                msg = str(err)
                self.after(0, lambda: self.ai_done(None, msg))
        threading.Thread(target=work, daemon=True).start()

    def ai_done(self, result, error):
        if error:
            self.ai_status.set("خطا در ارتباط")
            self.ai_output.delete("1.0", "end")
            self.ai_output.insert("end", error)
            return
        self.ai_status.set("اتصال برقرار است")
        self.ai_output.delete("1.0", "end")
        self.ai_output.insert("end", result if isinstance(result, str) else json.dumps(result, ensure_ascii=False, indent=2))

    def check_ai(self):
        self.ai_background(lambda: requests.get(LOCAL + "/api/status", timeout=5).json())

    def ask_ai(self):
        prompt = self.ai_prompt.get("1.0", "end").strip()
        if not prompt:
            messagebox.showwarning("متن لازم است", "درخواست خود را بنویسید.")
            return
        def call():
            r = requests.post(LOCAL + "/api/agent/ask", json={"message": prompt}, timeout=TIMEOUT)
            r.raise_for_status()
            return r.json().get("answer", "پاسخی دریافت نشد.")
        self.ai_background(call)

    def plan_ai(self):
        prompt = self.ai_prompt.get("1.0", "end").strip()
        if not prompt:
            messagebox.showwarning("متن لازم است", "درخواست خود را بنویسید.")
            return
        def call():
            r = requests.post(LOCAL + "/api/agent/workflow-preview", json={"instruction": prompt}, timeout=TIMEOUT)
            r.raise_for_status()
            return r.json()
        self.ai_background(call)

    def clear_ai(self):
        self.ai_prompt.delete("1.0", "end")
        self.ai_output.delete("1.0", "end")
        self.ai_status.set("پاک شد")

if __name__ == "__main__":
    AIEnabledApp().mainloop()
