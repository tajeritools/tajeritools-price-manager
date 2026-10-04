import base64, json, os, re, shutil, tempfile, threading, uuid, webbrowser
from pathlib import Path
import tkinter as tk
from tkinter import ttk, filedialog, messagebox
import requests
import fitz
from PIL import Image, ImageTk
from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import A4

APP_NAME = "TajeriTools Price Manager"
VERSION = "2.2.0-desktop"
GATEWAY = "https://tajeritools.ir/wp-json/tajeritools/v1"

def app_dir():
    root = Path(os.getenv("APPDATA") or Path.home()) / "TajeriToolsPriceManager"
    root.mkdir(parents=True, exist_ok=True)
    (root / "files").mkdir(exist_ok=True)
    return root

STATE_FILE = app_dir() / "state.json"

def load_state():
    if STATE_FILE.exists():
        try:
            return json.loads(STATE_FILE.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {
        "docs": [], "formulas": {}, "license_token": "", "license_phone": "",
        "device_id": str(uuid.uuid4()), "paddle_url": "", "gemini_key": "",
        "woo_url": "https://tajeritools.ir", "woo_ck": "", "woo_cs": ""
    }

STATE = load_state()

def save_state():
    STATE_FILE.write_text(json.dumps(STATE, ensure_ascii=False, indent=2), encoding="utf-8")

def normalize(s):
    tr = str.maketrans("۰۱۲۳۴۵۶۷۸۹يك", "0123456789یک")
    return re.sub(r"\s+", " ", str(s).translate(tr).lower()).strip()

def detect_brand(text):
    s = normalize(text)
    brands = [
        ("Ronix", ["ronix","رونیکس"]), ("Tosan", ["tosan","توسن"]),
        ("Anchor", ["anchor","آنکور","انکر"]), ("Nova", ["nova","نووا"]),
        ("Arva", ["arva","آروا"]), ("Pukka", ["pukka","پوکا"]),
        ("Vivarex", ["vivarex","ویوارکس"]), ("Hans", ["hans","هنس"]),
        ("Winner", ["winner","وینر"])
    ]
    for name, keys in brands:
        if any(normalize(k) in s for k in keys):
            return name
    return "نامشخص"

def unit_from_text(text):
    s = normalize(text)
    if "ریال" in s or "rial" in s:
        return "rial"
    if "تومان" in s or "toman" in s:
        return "toman"
    return "unknown"

def to_toman(v, unit):
    return v / 10.0 if unit == "rial" else v

def fmt(v):
    try:
        return f"{int(round(v)):,}"
    except Exception:
        return str(v)

PRICE_RX = re.compile(r"(?<!\w)(\d{1,3}(?:[٬,/]\d{3}){2,3}|\d{6,12})(?!\w)")
CODE_RX = re.compile(r"\b(?:[A-Za-z]{1,8}[-_]?\d{2,8}[A-Za-z0-9-]*|\d{4,6}[A-Za-z]?)\b")

def parse_num(s):
    s = normalize(s).replace("٬","").replace(",","").replace("/","")
    m = re.search(r"\d+(?:\.\d+)?", s)
    return float(m.group()) if m else None

def local_products(text, brand, source):
    unit = unit_from_text(text)
    lines = [re.sub(r"\s+"," ", x).strip() for x in text.splitlines() if x.strip()]
    out = []
    for i, line in enumerate(lines):
        prices = []
        for m in PRICE_RX.finditer(normalize(line)):
            v = parse_num(m.group())
            if v and v >= 50000:
                prices.append((m.group(), v))
        if not prices:
            continue
        rawtok, source_price = max(prices, key=lambda x: x[1])
        chunk = " ".join(lines[max(0,i-2):min(len(lines),i+4)])
        codes = [m.group() for m in CODE_RX.finditer(chunk)]
        code = codes[0] if codes else ""
        name = line.replace(rawtok, " ")
        if code:
            name = re.sub(re.escape(code), " ", name, flags=re.I)
        name = re.sub(r"(قیمت|ریال|تومان|price|کد کالا|تصویر محصول|آخرین بروزرسانی)", " ", name, flags=re.I)
        name = re.sub(r"\s+"," ",name).strip(" -:،|")
        if len(re.sub(r"\W","",name)) < 3:
            continue
        out.append({
            "brand": brand, "name": name[:180], "code": code,
            "source_price": source_price, "price_unit": unit,
            "raw_price": to_toman(source_price, unit),
            "price_type": "list", "page": 0, "confidence": 0.0,
            "promotion": "", "source": source
        })
    seen=set(); ded=[]
    for p in out:
        k=(normalize(p["code"]), normalize(p["name"]), int(p["source_price"]))
        if k not in seen:
            seen.add(k); ded.append(p)
    return ded

def request_json(url, method="GET", token="", data=None, timeout=60, auth=None):
    headers={"Accept":"application/json"}
    if token: headers["Authorization"]=f"Bearer {token}"
    if data is not None: headers["Content-Type"]="application/json"
    r=requests.request(method,url,headers=headers,json=data,timeout=timeout,auth=auth)
    try: payload=r.json()
    except Exception: payload={"message":r.text[:500]}
    if not r.ok:
        raise RuntimeError(payload.get("message") or payload.get("error") or f"HTTP {r.status_code}")
    return payload

def activate_license(phone, code):
    p=request_json(GATEWAY+"/activate","POST",data={
        "phone":phone.strip(),"code":code.strip(),"device_id":STATE["device_id"],"app_version":VERSION
    })
    STATE["license_token"]=p["token"]; STATE["license_phone"]=p.get("phone",phone.strip()); save_state()
    return p

def license_status():
    if not STATE.get("license_token"): return {"ok":False,"message":"مجوز فعال نشده است."}
    return request_json(GATEWAY+"/status","GET",token=STATE["license_token"],timeout=20)

def gateway_analyze_bytes(name, data, mime, page_start, brand):
    p=request_json(GATEWAY+"/analyze","POST",token=STATE["license_token"],timeout=80,data={
        "file":base64.b64encode(data).decode(),"mime_type":mime,"page":page_start,
        "brand_hint":brand,"source_name":name
    })
    return p["result"]

def cloud_analyze_pdf(path, name, brand):
    src=fitz.open(path); merged={"brand":"","products":[]}
    for start in range(0, len(src), 4):
        chunk=fitz.open()
        chunk.insert_pdf(src, from_page=start, to_page=min(start+3,len(src)-1))
        data=chunk.tobytes(); chunk.close()
        result=gateway_analyze_bytes(f"{name} | original pages {start+1}-{min(start+4,len(src))}",data,"application/pdf",start+1,brand)
        if not merged["brand"]: merged["brand"]=result.get("brand","")
        for p in result.get("products",[]):
            pg=int(p.get("page") or 0)
            if 1 <= pg <= 4: p["page"]=pg+start
            merged["products"].append(p)
    src.close()
    return merged

def paddle_analyze(path, name, brand, mime):
    base=STATE.get("paddle_url","").rstrip("/")
    if not base: return None
    raw=Path(path).read_bytes()
    p=request_json(base+"/layout-parsing","POST",data={
        "file":base64.b64encode(raw).decode(), "fileType":0 if mime=="application/pdf" else 1,
        "useDocOrientationClassify":True,"useDocUnwarping":False,"useLayoutDetection":True,
        "formatBlockContent":True,"restructurePages":False,"returnMarkdownImages":False,"visualize":False
    },timeout=180)
    pages=p.get("result",{}).get("layoutParsingResults",[])
    text="\n".join((x.get("markdown") or {}).get("text","") for x in pages)
    return {"brand":brand or detect_brand(text),"products":local_products(text,brand or detect_brand(text),name),"text":text}

def gemini_text(text, name):
    key=STATE.get("gemini_key","").strip()
    if not key: return None
    prompt=("You analyze Iranian tool-store price lists. Return ONLY JSON with keys brand and products. "
            "Each product: name,code,price,price_unit,price_type,page,confidence,promotion,evidence. "
            "Keep exact model/name/price from same row. Never invent. Filename: "+name+"\n"+text[:50000])
    body={"contents":[{"parts":[{"text":prompt}]}],"generationConfig":{"responseMimeType":"application/json","temperature":0.0}}
    r=requests.post("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
                    headers={"Content-Type":"application/json","x-goog-api-key":key},json=body,timeout=70)
    if not r.ok: raise RuntimeError(f"Gemini HTTP {r.status_code}: {r.text[:250]}")
    return json.loads(r.json()["candidates"][0]["content"]["parts"][0]["text"])

def extract_pdf_text(path):
    doc=fitz.open(path); parts=[]
    for i,p in enumerate(doc):
        t=p.get_text("text")
        if t.strip(): parts.append(f"\n--- PAGE {i+1} ---\n{t}")
    doc.close()
    return "\n".join(parts)

def normalize_ai_products(result, brand, source):
    out=[]
    for p in result.get("products",[]):
        try: sp=float(p.get("price",0))
        except Exception: continue
        unit=normalize(p.get("price_unit",""))
        if unit not in ("rial","toman") or sp<50000: continue
        name=str(p.get("name","")).strip()
        if len(name)<3: continue
        out.append({
            "brand":result.get("brand") or brand or "نامشخص", "name":name, "code":str(p.get("code","")).strip(),
            "source_price":sp, "price_unit":unit, "raw_price":to_toman(sp,unit),
            "price_type":p.get("price_type","list"), "page":int(p.get("page") or 0),
            "confidence":float(p.get("confidence") or 0), "promotion":str(p.get("promotion","")),
            "source":source
        })
    return out

def apply_formula(price, formula):
    v=float(price); trace=[]
    for step in [x.strip() for x in formula.split("=") if x.strip()]:
        m=re.match(r"(\w+)\(([^)]*)\)",step)
        if not m: continue
        op,args=m.group(1).lower(),[float(x.strip()) for x in m.group(2).split(",") if x.strip()]
        before=v
        if op=="discount" and args: v*=1-args[0]/100
        elif op=="gift" and len(args)>=2 and args[0]+args[1]>0: v*=args[0]/(args[0]+args[1])
        elif op in ("cost","markup") and args: v*=1+args[0]/100
        elif op=="margin" and args and args[0]<100: v/=1-args[0]/100
        elif op=="round" and args and args[0]>0:
            import math; v=math.ceil(v/args[0])*args[0]
        trace.append((step,before,v))
    return v,trace

def formula_for(p):
    exact=f'{normalize(p["brand"])}||{normalize(p["code"] or p["name"])}'
    if exact in STATE["formulas"]: return STATE["formulas"][exact]
    default=f'{normalize(p["brand"])}||'
    return STATE["formulas"].get(default)

class App(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title(f"{APP_NAME} {VERSION}")
        self.geometry("1180x780")
        self.minsize(980,650)
        self.style=ttk.Style(self)
        try:self.style.theme_use("vista")
        except:pass
        self.status=tk.StringVar(value="آماده")
        self.nb=ttk.Notebook(self); self.nb.pack(fill="both",expand=True,padx=8,pady=8)
        self.tabs={}
        for name in ["جستجو","کاتالوگ","فایل‌ها","فرمول","PDF","سایت","مجوز","Paddle","AI"]:
            f=ttk.Frame(self.nb); self.nb.add(f,text=name); self.tabs[name]=f
        ttk.Label(self,textvariable=self.status,anchor="e").pack(fill="x",padx=12,pady=(0,6))
        self.build_search(); self.build_catalog(); self.build_files(); self.build_formula()
        self.build_pdf(); self.build_site(); self.build_license(); self.build_paddle(); self.build_ai()
        self.refresh_all()

    def run_bg(self, fn, done=None):
        self.status.set("در حال پردازش...")
        def work():
            try:
                r=fn()
                self.after(0,lambda:self._done(r,done,None))
            except Exception as e:
                self.after(0,lambda:self._done(None,done,e))
        threading.Thread(target=work,daemon=True).start()
    def _done(self,r,done,e):
        self.status.set("آماده" if not e else f"خطا: {e}")
        if e: messagebox.showerror("خطا",str(e))
        elif done: done(r)

    def products(self):
        out=[]
        for d in STATE["docs"]: out.extend(d.get("products",[]))
        return out

    def refresh_all(self):
        self.refresh_files(); self.refresh_search(); self.refresh_catalog(); self.refresh_formulas()

    def build_search(self):
        f=self.tabs["جستجو"]; top=ttk.Frame(f); top.pack(fill="x",padx=10,pady=10)
        self.search_q=tk.StringVar(); ttk.Entry(top,textvariable=self.search_q).pack(side="right",fill="x",expand=True)
        ttk.Button(top,text="جستجو",command=self.refresh_search).pack(side="right",padx=6)
        self.search_tree=ttk.Treeview(f,columns=("brand","name","code","source","final"),show="headings")
        for c,t,w in [("brand","برند",90),("name","محصول",420),("code","مدل/کد",100),("source","قیمت منبع",130),("final","قیمت نهایی",130)]:
            self.search_tree.heading(c,text=t); self.search_tree.column(c,width=w,anchor="e")
        self.search_tree.pack(fill="both",expand=True,padx=10,pady=10)
    def refresh_search(self):
        if not hasattr(self,"search_tree"): return
        for x in self.search_tree.get_children(): self.search_tree.delete(x)
        q=normalize(self.search_q.get()) if hasattr(self,"search_q") else ""
        for p in self.products()[:3000]:
            hay=normalize(" ".join([p.get("brand",""),p.get("name",""),p.get("code","")]))
            if q and q not in hay: continue
            f=formula_for(p); final=p["raw_price"]
            if f:
                try: final=apply_formula(final,f)[0]
                except: pass
            self.search_tree.insert("", "end", values=(p["brand"],p["name"],p.get("code",""),fmt(p["raw_price"]),fmt(final)))

    def build_catalog(self):
        f=self.tabs["کاتالوگ"]; top=ttk.Frame(f); top.pack(fill="x",padx=10,pady=10)
        self.cat_q=tk.StringVar(); ttk.Entry(top,textvariable=self.cat_q).pack(side="right",fill="x",expand=True)
        ttk.Button(top,text="جستجو",command=self.refresh_catalog).pack(side="right",padx=6)
        pan=ttk.Panedwindow(f,orient="horizontal"); pan.pack(fill="both",expand=True,padx=10,pady=10)
        left=ttk.Frame(pan); right=ttk.Frame(pan); pan.add(left,weight=2); pan.add(right,weight=1)
        self.cat_list=tk.Listbox(left); self.cat_list.pack(fill="both",expand=True); self.cat_list.bind("<<ListboxSelect>>",self.show_cat)
        self.cat_detail=tk.Text(right,wrap="word"); self.cat_detail.pack(fill="both",expand=True)
    def refresh_catalog(self):
        if not hasattr(self,"cat_list"): return
        self.cat_list.delete(0,"end"); self.cat_rows=[]
        q=normalize(self.cat_q.get())
        for p in self.products():
            if q and q not in normalize(f'{p.get("brand","")} {p.get("name","")} {p.get("code","")}'): continue
            self.cat_rows.append(p)
            self.cat_list.insert("end",f'{p.get("brand","")} | {p.get("code","")} | {p.get("name","")[:80]}')
    def show_cat(self,_=None):
        if not self.cat_list.curselection(): return
        p=self.cat_rows[self.cat_list.curselection()[0]]
        self.cat_detail.delete("1.0","end")
        text=f'برند: {p["brand"]}\nنام: {p["name"]}\nمدل/کد: {p.get("code","")}\nصفحه: {p.get("page",0)}\nقیمت منبع: {fmt(p["source_price"])} {p["price_unit"]}\nمبنای تومان: {fmt(p["raw_price"])}\nاطمینان: {int(p.get("confidence",0)*100)}٪\nاشانتیون: {p.get("promotion","")}\nمنبع: {p.get("source","")}'
        self.cat_detail.insert("end",text)

    def build_files(self):
        f=self.tabs["فایل‌ها"]; top=ttk.Frame(f); top.pack(fill="x",padx=10,pady=10)
        self.brand_override=tk.StringVar()
        ttk.Entry(top,textvariable=self.brand_override,width=24).pack(side="right")
        ttk.Label(top,text="برند اختیاری").pack(side="right",padx=6)
        ttk.Button(top,text="افزودن PDF / عکس / CSV / متن",command=self.add_files).pack(side="left")
        ttk.Button(top,text="حذف انتخاب",command=self.delete_file).pack(side="left",padx=6)
        self.files_tree=ttk.Treeview(f,columns=("brand","name","count","path"),show="headings")
        for c,t,w in [("brand","برند",100),("name","فایل",260),("count","محصول",80),("path","مسیر",500)]:
            self.files_tree.heading(c,text=t); self.files_tree.column(c,width=w,anchor="e")
        self.files_tree.pack(fill="both",expand=True,padx=10,pady=10)
    def refresh_files(self):
        if not hasattr(self,"files_tree"): return
        for x in self.files_tree.get_children(): self.files_tree.delete(x)
        for i,d in enumerate(STATE["docs"]):
            self.files_tree.insert("", "end",iid=str(i),values=(d.get("brand",""),d.get("name",""),len(d.get("products",[])),d.get("path","")))
    def add_files(self):
        paths=filedialog.askopenfilenames(filetypes=[("Supported","*.pdf *.png *.jpg *.jpeg *.webp *.csv *.txt"),("All","*.*")])
        if not paths:return
        def work():
            added=[]
            for src in paths:
                srcp=Path(src); dst=app_dir()/ "files" / f"{uuid.uuid4().hex}_{srcp.name}"
                shutil.copy2(srcp,dst)
                mime="application/pdf" if dst.suffix.lower()==".pdf" else ("image/png" if dst.suffix.lower()==".png" else "image/jpeg" if dst.suffix.lower() in (".jpg",".jpeg") else "image/webp" if dst.suffix.lower()==".webp" else "text/plain")
                text=extract_pdf_text(dst) if mime=="application/pdf" else (dst.read_text(encoding="utf-8",errors="ignore") if mime=="text/plain" else "")
                brand=self.brand_override.get().strip() or detect_brand(srcp.name+"\n"+text)
                result=None
                if STATE.get("license_token") and mime in ("application/pdf","image/png","image/jpeg","image/webp"):
                    try:
                        result=cloud_analyze_pdf(dst,srcp.name,brand) if mime=="application/pdf" else gateway_analyze_bytes(srcp.name,dst.read_bytes(),mime,1,brand)
                    except Exception:
                        result=None
                if result is None and STATE.get("paddle_url") and mime in ("application/pdf","image/png","image/jpeg","image/webp"):
                    try:
                        pr=paddle_analyze(dst,srcp.name,brand,mime); result={"brand":pr["brand"],"products":pr["products"]}; text=pr.get("text",text)
                    except Exception:
                        result=None
                if result is None and STATE.get("gemini_key") and text:
                    try: result=gemini_text(text,srcp.name)
                    except Exception: result=None
                products=normalize_ai_products(result,brand,srcp.name) if result else local_products(text,brand,srcp.name)
                final_brand=(result or {}).get("brand") or brand
                d={"id":uuid.uuid4().hex,"brand":final_brand or brand,"name":srcp.name,"path":str(dst),"mime":mime,"text":text,"products":products}
                STATE["docs"].append(d); added.append(d)
            save_state(); return added
        self.run_bg(work,lambda r:(self.refresh_all(),messagebox.showinfo("تمام",f"{len(r)} فایل اضافه شد.")))
    def delete_file(self):
        sel=self.files_tree.selection()
        if not sel:return
        idx=int(sel[0]); d=STATE["docs"].pop(idx)
        try: Path(d["path"]).unlink(missing_ok=True)
        except: pass
        save_state(); self.refresh_all()

    def build_formula(self):
        f=self.tabs["فرمول"]; form=ttk.Frame(f); form.pack(fill="x",padx=10,pady=10)
        self.form_brand=tk.StringVar(); self.form_product=tk.StringVar(); self.form_formula=tk.StringVar(value="discount(18)=markup(10)=round(10000)")
        for label,var in [("برند",self.form_brand),("محصول/مدل (خالی=پیش‌فرض برند)",self.form_product),("فرمول",self.form_formula)]:
            row=ttk.Frame(form); row.pack(fill="x",pady=3); ttk.Label(row,text=label,width=28).pack(side="right"); ttk.Entry(row,textvariable=var).pack(side="right",fill="x",expand=True)
        ttk.Button(form,text="ذخیره فرمول",command=self.save_formula).pack(side="right",pady=6)
        ttk.Button(form,text="حذف انتخاب",command=self.delete_formula).pack(side="right",padx=6)
        ttk.Button(form,text="حذف همه",command=self.delete_all_formulas).pack(side="right")
        self.form_tree=ttk.Treeview(f,columns=("key","formula"),show="headings"); self.form_tree.heading("key",text="برند / محصول"); self.form_tree.heading("formula",text="فرمول"); self.form_tree.pack(fill="both",expand=True,padx=10,pady=10)
    def refresh_formulas(self):
        if not hasattr(self,"form_tree"): return
        for x in self.form_tree.get_children(): self.form_tree.delete(x)
        for k,v in STATE["formulas"].items(): self.form_tree.insert("", "end",iid=k,values=(k.replace("||"," / "),v))
    def save_formula(self):
        b=normalize(self.form_brand.get()); p=normalize(self.form_product.get())
        if not b:return
        STATE["formulas"][f"{b}||{p}"]=self.form_formula.get().strip(); save_state(); self.refresh_formulas(); self.refresh_search()
    def delete_formula(self):
        for k in self.form_tree.selection(): STATE["formulas"].pop(k,None)
        save_state(); self.refresh_formulas(); self.refresh_search()
    def delete_all_formulas(self):
        if messagebox.askyesno("تأیید","همه فرمول‌ها حذف شوند؟"):
            STATE["formulas"]={}; save_state(); self.refresh_formulas(); self.refresh_search()

    def build_pdf(self):
        f=self.tabs["PDF"]; ttk.Label(f,text="خروجی PDF قیمت‌نامه",font=("Segoe UI",16)).pack(pady=12)
        self.pdf_brand=tk.StringVar(); ttk.Entry(f,textvariable=self.pdf_brand,width=30).pack()
        ttk.Button(f,text="ساخت PDF",command=self.make_pdf).pack(pady=10)
    def make_pdf(self):
        brand=self.pdf_brand.get().strip()
        rows=[p for p in self.products() if not brand or normalize(p["brand"])==normalize(brand)]
        out=filedialog.asksaveasfilename(defaultextension=".pdf",filetypes=[("PDF","*.pdf")])
        if not out:return
        c=canvas.Canvas(out,pagesize=A4); W,H=A4; y=H-50
        c.setFont("Helvetica",12); c.drawString(40,y,f"TajeriTools Price Manager - {brand or 'All'}"); y-=30
        for p in rows:
            f=formula_for(p)
            if not f: continue
            final,_=apply_formula(p["raw_price"],f)
            line=f'{p.get("brand","")} | {p.get("code","")} | {p.get("name","")[:60]} | {fmt(final)} Toman'
            c.drawString(40,y,line.encode("ascii","ignore").decode() or "Product")
            y-=18
            if y<50: c.showPage(); y=H-50
        c.save(); messagebox.showinfo("تمام",out)

    def build_site(self):
        f=self.tabs["سایت"]; self.woo_vars={}
        for key,label in [("woo_url","آدرس سایت"),("woo_ck","Consumer Key"),("woo_cs","Consumer Secret")]:
            row=ttk.Frame(f); row.pack(fill="x",padx=12,pady=6); ttk.Label(row,text=label,width=20).pack(side="right")
            v=tk.StringVar(value=STATE.get(key,"")); self.woo_vars[key]=v; ttk.Entry(row,textvariable=v,show="*" if key=="woo_cs" else "").pack(side="right",fill="x",expand=True)
        ttk.Button(f,text="ذخیره تنظیمات سایت",command=self.save_woo).pack(pady=8)
        ttk.Label(f,text="همگام‌سازی قیمت دقیق SKU در نسخه ویندوز از همین تنظیمات استفاده می‌کند.").pack(pady=4)
    def save_woo(self):
        for k,v in self.woo_vars.items(): STATE[k]=v.get().strip()
        save_state(); messagebox.showinfo("ذخیره شد","تنظیمات سایت ذخیره شد.")

    def build_license(self):
        f=self.tabs["مجوز"]; self.lic_phone=tk.StringVar(value=STATE.get("license_phone","")); self.lic_code=tk.StringVar(); self.lic_status=tk.StringVar(value="")
        for label,var in [("شماره موبایل",self.lic_phone),("کد فعال‌سازی",self.lic_code)]:
            row=ttk.Frame(f); row.pack(fill="x",padx=12,pady=6); ttk.Label(row,text=label,width=20).pack(side="right"); ttk.Entry(row,textvariable=var).pack(side="right",fill="x",expand=True)
        ttk.Button(f,text="فعال‌سازی",command=self.do_activate).pack(pady=6)
        ttk.Button(f,text="بررسی وضعیت",command=self.check_license_ui).pack(pady=6)
        ttk.Button(f,text="خروج از مجوز این سیستم",command=self.logout_license).pack(pady=6)
        ttk.Label(f,textvariable=self.lic_status,justify="right").pack(padx=12,pady=12,anchor="e")
    def do_activate(self):
        self.run_bg(lambda:activate_license(self.lic_phone.get(),self.lic_code.get()),lambda p:self.lic_status.set(f'فعال شد\nAI: {p.get("ai_model","")}\nسقف روزانه: {p.get("max_daily",0)}'))
    def check_license_ui(self):
        self.run_bg(license_status,lambda p:self.lic_status.set(f'وضعیت: {"فعال" if p.get("ok") else "غیرفعال"}\nAI: {p.get("ai_model","")}\nمصرف امروز: {p.get("used_today",0)} / {p.get("max_daily",0)}'))
    def logout_license(self):
        STATE["license_token"]=""; STATE["license_phone"]=""; save_state(); self.lic_status.set("مجوز از این سیستم خارج شد.")

    def build_paddle(self):
        f=self.tabs["Paddle"]; self.paddle_var=tk.StringVar(value=STATE.get("paddle_url",""))
        ttk.Label(f,text="آدرس سرور PaddleOCR-VL").pack(pady=(18,5)); ttk.Entry(f,textvariable=self.paddle_var,width=60).pack()
        ttk.Button(f,text="ذخیره",command=self.save_paddle).pack(pady=6); ttk.Button(f,text="تست اتصال",command=self.test_paddle).pack(pady=6)
    def save_paddle(self):
        STATE["paddle_url"]=self.paddle_var.get().strip().rstrip("/"); save_state()
    def test_paddle(self):
        self.save_paddle()
        self.run_bg(lambda:requests.get(STATE["paddle_url"]+"/health",timeout=10).text,lambda r:messagebox.showinfo("Paddle",r[:300]))

    def build_ai(self):
        f=self.tabs["AI"]; self.gemini_var=tk.StringVar(value=STATE.get("gemini_key",""))
        ttk.Label(f,text="Gemini API Key شخصی (اختیاری؛ مسیر قبلی حفظ شده)").pack(pady=(18,5)); ttk.Entry(f,textvariable=self.gemini_var,width=70,show="*").pack()
        ttk.Button(f,text="ذخیره",command=self.save_ai).pack(pady=8)
        ttk.Label(f,text="اولویت: AI ابری TajeriTools → PaddleOCR-VL → Gemini شخصی → parser محلی").pack(pady=8)
    def save_ai(self):
        STATE["gemini_key"]=self.gemini_var.get().strip(); save_state()

if __name__=="__main__":
    App().mainloop()
