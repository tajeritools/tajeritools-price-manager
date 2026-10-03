from __future__ import annotations

import io
import os
import re
import json
import math
import sqlite3
import hashlib
from pathlib import Path
from datetime import datetime
from typing import Optional, Iterable

import fitz
import pandas as pd
import streamlit as st
from PIL import Image
from rapidfuzz import fuzz
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.pagesizes import A4
from reportlab.pdfgen import canvas
import arabic_reshaper
from bidi.algorithm import get_display

try:
    import pytesseract
except Exception:
    pytesseract = None

APP_DIR = Path(__file__).resolve().parent
DATA_DIR = APP_DIR / "data"
UPLOAD_DIR = DATA_DIR / "uploads"
DB_PATH = DATA_DIR / "tajeritools.db"
UPLOAD_DIR.mkdir(parents=True, exist_ok=True)

st.set_page_config(
    page_title="TajeriTools Price Manager",
    page_icon="🧰",
    layout="wide",
)

CSS = """
<style>
html, body, [class*="css"] { direction: rtl; }
.block-container { padding-top: 1.3rem; }
h1,h2,h3,p,div,label,input,button,textarea { text-align: right; }
[data-testid="stSidebar"] * { direction: rtl; text-align: right; }
.price-box {
    border: 1px solid #d8d8d8; border-radius: 14px; padding: 16px; margin: 8px 0;
}
.small-muted { color:#777; font-size:.86rem; }
</style>
"""
st.markdown(CSS, unsafe_allow_html=True)


def db() -> sqlite3.Connection:
    con = sqlite3.connect(DB_PATH)
    con.row_factory = sqlite3.Row
    return con


def init_db() -> None:
    with db() as con:
        con.executescript(
            """
            CREATE TABLE IF NOT EXISTS files (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                brand TEXT NOT NULL,
                name TEXT NOT NULL,
                stored_path TEXT NOT NULL UNIQUE,
                file_type TEXT NOT NULL,
                sha256 TEXT NOT NULL,
                added_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id INTEGER NOT NULL,
                page_no INTEGER,
                source_ref TEXT,
                text TEXT,
                image_path TEXT,
                FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS products (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_id INTEGER,
                brand TEXT NOT NULL,
                code TEXT,
                name TEXT NOT NULL,
                raw_price REAL,
                source_text TEXT,
                page_no INTEGER,
                image_path TEXT,
                UNIQUE(file_id, brand, code, name, raw_price)
            );

            CREATE TABLE IF NOT EXISTS formulas (
                brand TEXT PRIMARY KEY,
                formula TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );
            """
        )


init_db()


def norm(s: str) -> str:
    if not s:
        return ""
    trans = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
    s = s.translate(trans)
    s = s.replace("ي", "ی").replace("ك", "ک")
    s = re.sub(r"[\u200c\u200f\u202a-\u202e]", " ", s)
    s = re.sub(r"\s+", " ", s).strip().lower()
    return s


def parse_number(s: str) -> Optional[float]:
    if not s:
        return None
    s = norm(s)
    s = s.replace(",", "").replace("٬", "").replace("/", "")
    m = re.search(r"(?<!\w)(\d{3,})(?:\.\d+)?", s)
    if not m:
        return None
    try:
        return float(m.group(1))
    except Exception:
        return None


def format_price(v: Optional[float]) -> str:
    if v is None or (isinstance(v, float) and math.isnan(v)):
        return "—"
    return f"{int(round(v)):,}".replace(",", "٬")


def safe_name(name: str) -> str:
    ext = Path(name).suffix.lower()
    stem = re.sub(r"[^A-Za-z0-9_.-]+", "_", Path(name).stem)[:100]
    return f"{stem}{ext}"


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def ocr_image(image: Image.Image) -> str:
    if pytesseract is None:
        return ""
    try:
        return pytesseract.image_to_string(image, lang="fas+eng")
    except Exception:
        try:
            return pytesseract.image_to_string(image)
        except Exception:
            return ""


def extract_pdf(path: Path) -> list[dict]:
    doc = fitz.open(path)
    chunks = []
    previews = UPLOAD_DIR / "_previews"
    previews.mkdir(exist_ok=True)
    for i, page in enumerate(doc):
        text = page.get_text("text") or ""
        pix = page.get_pixmap(matrix=fitz.Matrix(1.4, 1.4), alpha=False)
        img_path = previews / f"{path.stem}_p{i+1}.jpg"
        pix.save(str(img_path))
        if len(norm(text)) < 20:
            try:
                image = Image.open(img_path)
                text = ocr_image(image)
            except Exception:
                pass
        chunks.append({
            "page_no": i + 1,
            "source_ref": f"صفحه {i+1}",
            "text": text,
            "image_path": str(img_path),
        })
    return chunks


def extract_image(path: Path) -> list[dict]:
    img = Image.open(path).convert("RGB")
    text = ocr_image(img)
    return [{"page_no": 1, "source_ref": "تصویر", "text": text, "image_path": str(path)}]


def extract_table(path: Path) -> list[dict]:
    ext = path.suffix.lower()
    if ext == ".csv":
        df = pd.read_csv(path)
        sheets = {"CSV": df}
    else:
        sheets = pd.read_excel(path, sheet_name=None)
    chunks = []
    for idx, (sheet, df) in enumerate(sheets.items(), 1):
        text = df.fillna("").astype(str).to_csv(index=False)
        chunks.append({"page_no": idx, "source_ref": str(sheet), "text": text, "image_path": None})
    return chunks


def guess_product_rows(text: str, brand: str, file_id: int, page_no: int, image_path: Optional[str]) -> list[dict]:
    rows = []
    for raw in text.splitlines():
        line = re.sub(r"\s+", " ", raw).strip()
        if len(line) < 4:
            continue

        price = parse_number(line)
        if price is None:
            continue

        # Product-code heuristic: mixed alpha/numeric codes or compact numeric model identifiers.
        tokens = re.findall(r"\b[A-Za-z]{1,6}[-_ ]?\d{1,6}[A-Za-z0-9-]*\b|\b\d{3,6}[A-Za-z]?\b", line)
        code = tokens[0].replace(" ", "") if tokens else None

        # Keep readable name by removing the largest price-looking token.
        name = line
        matches = list(re.finditer(r"[۰-۹0-9][۰-۹0-9٬,/.]{2,}", line))
        if matches:
            largest = max(matches, key=lambda m: len(m.group(0)))
            name = (line[:largest.start()] + " " + line[largest.end():]).strip(" -:،")
        if len(name) < 3:
            name = line

        rows.append({
            "file_id": file_id,
            "brand": brand,
            "code": code,
            "name": name[:220],
            "raw_price": price,
            "source_text": line[:1000],
            "page_no": page_no,
            "image_path": image_path,
        })
    return rows


def save_uploaded_file(uploaded, brand: str) -> tuple[bool, str]:
    data = uploaded.getvalue()
    h = sha256_bytes(data)
    suffix = Path(uploaded.name).suffix.lower()
    if suffix not in {".pdf", ".png", ".jpg", ".jpeg", ".webp", ".xlsx", ".xls", ".csv"}:
        return False, "فرمت فایل پشتیبانی نمی‌شود."

    with db() as con:
        exists = con.execute("SELECT id FROM files WHERE sha256=?", (h,)).fetchone()
        if exists:
            return False, "این فایل قبلاً اضافه شده است."

    brand_dir = UPLOAD_DIR / re.sub(r"[^A-Za-z0-9آ-ی_-]+", "_", brand.strip())
    brand_dir.mkdir(parents=True, exist_ok=True)
    path = brand_dir / f"{datetime.now():%Y%m%d%H%M%S}_{safe_name(uploaded.name)}"
    path.write_bytes(data)

    if suffix == ".pdf":
        chunks = extract_pdf(path)
        ftype = "PDF"
    elif suffix in {".png", ".jpg", ".jpeg", ".webp"}:
        chunks = extract_image(path)
        ftype = "IMAGE"
    else:
        chunks = extract_table(path)
        ftype = "TABLE"

    with db() as con:
        cur = con.execute(
            "INSERT INTO files(brand,name,stored_path,file_type,sha256,added_at) VALUES(?,?,?,?,?,?)",
            (brand.strip(), uploaded.name, str(path), ftype, h, datetime.now().isoformat(timespec="seconds")),
        )
        file_id = cur.lastrowid
        products = []
        for ch in chunks:
            con.execute(
                "INSERT INTO chunks(file_id,page_no,source_ref,text,image_path) VALUES(?,?,?,?,?)",
                (file_id, ch["page_no"], ch["source_ref"], ch["text"], ch["image_path"]),
            )
            products.extend(
                guess_product_rows(
                    ch["text"], brand.strip(), file_id, ch["page_no"], ch["image_path"]
                )
            )
        for p in products:
            try:
                con.execute(
                    """INSERT OR IGNORE INTO products
                    (file_id,brand,code,name,raw_price,source_text,page_no,image_path)
                    VALUES(?,?,?,?,?,?,?,?)""",
                    (
                        p["file_id"], p["brand"], p["code"], p["name"], p["raw_price"],
                        p["source_text"], p["page_no"], p["image_path"],
                    ),
                )
            except Exception:
                pass
    return True, f"فایل اضافه شد؛ {len(chunks)} بخش و {len(products)} ردیف قیمت احتمالی استخراج شد."


def delete_file(file_id: int) -> None:
    with db() as con:
        row = con.execute("SELECT stored_path FROM files WHERE id=?", (file_id,)).fetchone()
        con.execute("DELETE FROM products WHERE file_id=?", (file_id,))
        con.execute("DELETE FROM chunks WHERE file_id=?", (file_id,))
        con.execute("DELETE FROM files WHERE id=?", (file_id,))
    if row:
        try:
            Path(row["stored_path"]).unlink(missing_ok=True)
        except Exception:
            pass


def search_all(query: str, limit: int = 30) -> list[dict]:
    q = norm(query)
    if not q:
        return []
    with db() as con:
        rows = con.execute(
            """SELECT p.*, f.name AS file_name
            FROM products p LEFT JOIN files f ON f.id=p.file_id"""
        ).fetchall()
        chunk_rows = con.execute(
            """SELECT c.*, f.brand, f.name AS file_name
            FROM chunks c JOIN files f ON f.id=c.file_id"""
        ).fetchall()

    scored = []
    for r in rows:
        hay = norm(f"{r['brand']} {r['code'] or ''} {r['name']} {r['source_text'] or ''}")
        if q in hay:
            score = 100
        else:
            score = max(
                fuzz.partial_ratio(q, hay),
                fuzz.token_set_ratio(q, hay),
            )
        if score >= 48:
            scored.append({
                "kind": "product",
                "score": score,
                **dict(r),
            })

    # If product extraction did not catch the row, surface matching raw chunks.
    for r in chunk_rows:
        hay = norm(r["text"] or "")
        if not hay:
            continue
        if q in hay:
            score = 92
        else:
            score = fuzz.partial_ratio(q, hay)
        if score >= 72:
            excerpt_pos = hay.find(q) if q in hay else 0
            original = r["text"] or ""
            excerpt = original[max(0, excerpt_pos - 180): excerpt_pos + 650]
            scored.append({
                "kind": "chunk",
                "score": score - 5,
                "brand": r["brand"],
                "code": None,
                "name": f"نتیجه از {r['file_name']} - {r['source_ref']}",
                "raw_price": None,
                "source_text": excerpt,
                "page_no": r["page_no"],
                "image_path": r["image_path"],
                "file_name": r["file_name"],
                "id": f"chunk-{r['id']}",
            })

    scored.sort(key=lambda x: x["score"], reverse=True)
    return scored[:limit]


class FormulaError(ValueError):
    pass


def apply_formula(price: float, formula: str) -> float:
    value = float(price)
    steps = [s.strip() for s in formula.split("=") if s.strip()]
    if not steps:
        return value

    for step in steps:
        expr = norm(step).replace(" ", "")
        expr = expr.replace("قیمت", "price")
        if expr.startswith("price"):
            expr = expr[5:]
        if not expr:
            continue

        m = re.fullmatch(r"([+\-*/])([0-9.]+)(%)?", expr)
        if not m:
            raise FormulaError(f"مرحله نامعتبر: {step}")
        op, num_s, percent = m.groups()
        num = float(num_s)

        if percent:
            amount = value * num / 100.0
            if op == "+":
                value += amount
            elif op == "-":
                value -= amount
            elif op == "*":
                value *= num / 100.0
            elif op == "/":
                if num == 0:
                    raise FormulaError("تقسیم بر صفر")
                value /= num / 100.0
        else:
            if op == "+":
                value += num
            elif op == "-":
                value -= num
            elif op == "*":
                value *= num
            elif op == "/":
                if num == 0:
                    raise FormulaError("تقسیم بر صفر")
                value /= num

    return value


def rtl(text: str) -> str:
    return get_display(arabic_reshaper.reshape(str(text)))


def find_font() -> Optional[str]:
    candidates = [
        APP_DIR / "fonts" / "Vazirmatn-Regular.ttf",
        Path("C:/Windows/Fonts/tahoma.ttf"),
        Path("C:/Windows/Fonts/arial.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
        Path("/usr/share/fonts/truetype/freefont/FreeSans.ttf"),
    ]
    for p in candidates:
        if p.exists():
            return str(p)
    return None


def build_pdf(rows: Iterable[dict], title: str = "لیست قیمت تاجری تولز") -> bytes:
    out = io.BytesIO()
    c = canvas.Canvas(out, pagesize=A4)
    width, height = A4
    font_path = find_font()
    font_name = "Helvetica"
    if font_path:
        font_name = "TajeriFont"
        try:
            pdfmetrics.registerFont(TTFont(font_name, font_path))
        except Exception:
            font_name = "Helvetica"

    def page_header():
        c.setFont(font_name, 16)
        c.drawRightString(width - 36, height - 42, rtl(title))
        c.setFont(font_name, 9)
        c.drawRightString(width - 36, height - 60, rtl(datetime.now().strftime("%Y/%m/%d")))
        c.line(36, height - 70, width - 36, height - 70)

    page_header()
    y = height - 100
    c.setFont(font_name, 11)

    for row in rows:
        name = str(row.get("display_name") or row.get("name") or "").strip()
        code = str(row.get("code") or "").strip()
        price = row.get("final_price")
        label = f"{name}"
        if code and norm(code) not in norm(name):
            label += f" {code}"
        label += f"   قیمت: {format_price(price)} تومان"
        if y < 55:
            c.showPage()
            page_header()
            y = height - 100
            c.setFont(font_name, 11)
        c.drawRightString(width - 36, y, rtl(label))
        y -= 24

    c.save()
    out.seek(0)
    return out.read()


def list_brands() -> list[str]:
    with db() as con:
        rows = con.execute(
            "SELECT brand FROM files UNION SELECT brand FROM formulas ORDER BY brand"
        ).fetchall()
    return [r[0] for r in rows if r[0]]


def get_formula(brand: str) -> str:
    with db() as con:
        row = con.execute("SELECT formula FROM formulas WHERE brand=?", (brand,)).fetchone()
    return row["formula"] if row else ""


def set_formula(brand: str, formula: str) -> None:
    with db() as con:
        con.execute(
            """INSERT INTO formulas(brand,formula,updated_at) VALUES(?,?,?)
            ON CONFLICT(brand) DO UPDATE SET formula=excluded.formula,updated_at=excluded.updated_at""",
            (brand, formula, datetime.now().isoformat(timespec="seconds")),
        )


st.title("🧰 مدیریت هوشمند قیمت تاجری تولز")
st.caption("فایل‌ها، جستجوی محصول، فرمول قیمت و خروجی PDF در یک برنامه")

tabs = st.tabs(["🔎 جستجوی محصول", "📁 فایل‌ها", "🧮 فرمول قیمت", "📄 ساخت قیمت‌نامه PDF"])

with tabs[0]:
    st.subheader("جستجوی محصول")
    query = st.text_input(
        "نام، کد، برند یا مشخصه را وارد کنید",
        placeholder="مثلاً DCE12 یا دریل شارژی آنکور",
    )
    if query:
        results = search_all(query)
        st.caption(f"{len(results)} نتیجه")
        if not results:
            st.warning("نتیجه‌ای پیدا نشد.")
        for idx, r in enumerate(results):
            with st.container(border=True):
                left, right = st.columns([1, 2])
                with left:
                    if r.get("image_path") and Path(r["image_path"]).exists():
                        st.image(r["image_path"], use_container_width=True)
                    else:
                        st.info("تصویر/پیش‌نمایش موجود نیست")
                with right:
                    st.markdown(f"### {r.get('name','')}")
                    st.write(f"**برند:** {r.get('brand') or '—'}")
                    if r.get("code"):
                        st.write(f"**کد:** {r['code']}")
                    if r.get("raw_price"):
                        st.write(f"**قیمت استخراج‌شده:** {format_price(r['raw_price'])}")
                    st.write(f"**منبع:** {r.get('file_name','—')} / صفحه {r.get('page_no') or '—'}")
                    if r.get("source_text"):
                        with st.expander("متن استخراج‌شده"):
                            st.text(r["source_text"])

with tabs[1]:
    st.subheader("مدیریت فایل‌ها")
    c1, c2 = st.columns([1, 2])
    with c1:
        brand = st.text_input("نام برند", placeholder="Anchor / آنکور")
        uploaded_files = st.file_uploader(
            "فایل‌ها را انتخاب کنید",
            type=["pdf", "png", "jpg", "jpeg", "webp", "xlsx", "xls", "csv"],
            accept_multiple_files=True,
        )
        if st.button("➕ افزودن و پردازش فایل‌ها", type="primary", use_container_width=True):
            if not brand.strip():
                st.error("ابتدا نام برند را وارد کنید.")
            elif not uploaded_files:
                st.error("حداقل یک فایل انتخاب کنید.")
            else:
                for up in uploaded_files:
                    with st.spinner(f"در حال پردازش {up.name} ..."):
                        ok, msg = save_uploaded_file(up, brand)
                    (st.success if ok else st.warning)(f"{up.name}: {msg}")
                st.rerun()

    with c2:
        with db() as con:
            files = con.execute(
                "SELECT * FROM files ORDER BY id DESC"
            ).fetchall()
        if not files:
            st.info("هنوز فایلی اضافه نشده است.")
        for f in files:
            with st.container(border=True):
                x, y = st.columns([4, 1])
                with x:
                    st.write(f"**{f['name']}**")
                    st.caption(f"{f['brand']} · {f['file_type']} · {f['added_at']}")
                with y:
                    if st.button("حذف", key=f"del-{f['id']}", use_container_width=True):
                        delete_file(f["id"])
                        st.rerun()

with tabs[2]:
    st.subheader("فرمول قیمت‌گذاری برندها")
    st.info(
        "هر مرحله را با = جدا کنید. مثال: price-7%=price*7=price/8=price+10%"
    )
    brands = list_brands()
    chosen_brand = st.selectbox("برند", options=["➕ برند جدید"] + brands)
    if chosen_brand == "➕ برند جدید":
        formula_brand = st.text_input("نام برند جدید")
    else:
        formula_brand = chosen_brand

    current = get_formula(formula_brand) if formula_brand else ""
    formula = st.text_input(
        "فرمول",
        value=current,
        placeholder="price-7%=price*7=price/8=price+10%",
    )

    test_price = st.number_input("قیمت آزمایشی", min_value=0.0, value=10000000.0, step=100000.0)
    if formula.strip():
        try:
            test_result = apply_formula(test_price, formula)
            st.success(f"نتیجه آزمایشی: {format_price(test_result)}")
        except FormulaError as e:
            st.error(str(e))

    if st.button("💾 ذخیره فرمول", type="primary"):
        if not formula_brand or not formula_brand.strip():
            st.error("نام برند لازم است.")
        elif not formula.strip():
            st.error("فرمول لازم است.")
        else:
            try:
                apply_formula(100000, formula)
                set_formula(formula_brand.strip(), formula.strip())
                st.success("فرمول ذخیره شد.")
                st.rerun()
            except FormulaError as e:
                st.error(str(e))

    if brands:
        st.divider()
        st.markdown("#### فرمول‌های ذخیره‌شده")
        for b in brands:
            f = get_formula(b)
            if f:
                st.code(f"{b}: {f}", language=None)

with tabs[3]:
    st.subheader("ساخت قیمت‌نامه PDF")
    brands = list_brands()
    brand_pdf = st.selectbox("برند برای خروجی", options=brands, key="pdf_brand")
    if brand_pdf:
        formula = get_formula(brand_pdf)
        if not formula:
            st.warning("برای این برند هنوز فرمولی ذخیره نشده است.")

        with db() as con:
            prows = con.execute(
                """SELECT id,brand,code,name,raw_price,source_text,page_no
                   FROM products
                   WHERE brand=? AND raw_price IS NOT NULL
                   ORDER BY id""",
                (brand_pdf,),
            ).fetchall()

        if not prows:
            st.warning("محصول قیمت‌دار قابل استخراج برای این برند پیدا نشد.")
        else:
            output_rows = []
            for r in prows:
                try:
                    final_price = apply_formula(r["raw_price"], formula) if formula else r["raw_price"]
                except FormulaError:
                    final_price = r["raw_price"]
                output_rows.append({
                    "id": r["id"],
                    "use": True,
                    "name": r["name"],
                    "display_name": r["name"],
                    "code": r["code"] or "",
                    "raw_price": int(r["raw_price"]),
                    "final_price": int(round(final_price)),
                })

            df = pd.DataFrame(output_rows)
            edited = st.data_editor(
                df,
                hide_index=True,
                use_container_width=True,
                column_config={
                    "id": None,
                    "use": st.column_config.CheckboxColumn("انتخاب"),
                    "name": st.column_config.TextColumn("نام استخراج‌شده", disabled=True),
                    "display_name": st.column_config.TextColumn("نام در PDF"),
                    "code": st.column_config.TextColumn("کد"),
                    "raw_price": st.column_config.NumberColumn("قیمت اولیه", format="%d"),
                    "final_price": st.column_config.NumberColumn("قیمت نهایی", format="%d"),
                },
            )

            selected = edited[edited["use"] == True].to_dict("records")
            st.caption(f"{len(selected)} محصول برای خروجی انتخاب شده است.")

            if selected:
                pdf_bytes = build_pdf(selected, f"لیست قیمت {brand_pdf} - تاجری تولز")
                st.download_button(
                    "⬇️ دانلود PDF قیمت‌نامه",
                    data=pdf_bytes,
                    file_name=f"tajeritools-{safe_name(brand_pdf)}-price-list.pdf",
                    mime="application/pdf",
                    type="primary",
                    use_container_width=True,
                )

st.divider()
st.caption("TajeriTools Price Manager · MVP v0.1")
