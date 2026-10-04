import SwiftUI
import PDFKit
import UniformTypeIdentifiers

struct Product: Codable, Identifiable, Hashable {
    var id = UUID()
    var brand: String
    var name: String
    var code: String
    var sourcePrice: Double
    var priceUnit: String
    var rawPrice: Double
    var priceType: String
    var page: Int
    var confidence: Double
    var promotion: String
    var source: String
}

struct DocumentItem: Codable, Identifiable {
    var id = UUID()
    var brand: String
    var name: String
    var path: String
    var mime: String
    var text: String
    var products: [Product]
}

struct PersistedState: Codable {
    var docs: [DocumentItem] = []
    var formulas: [String:String] = [:]
    var licenseToken = ""
    var licensePhone = ""
    var deviceId = UUID().uuidString
    var paddleURL = ""
    var geminiKey = ""
}

@MainActor
final class Store: ObservableObject {
    @Published var state: PersistedState
    @Published var message = ""
    @Published var busy = false

    let gateway = URL(string: "https://tajeritools.ir/wp-json/tajeritools/v1")!

    init() {
        if let data = UserDefaults.standard.data(forKey: "state"),
           let decoded = try? JSONDecoder().decode(PersistedState.self, from: data) {
            state = decoded
        } else {
            state = PersistedState()
        }
    }

    func save() {
        if let data = try? JSONEncoder().encode(state) {
            UserDefaults.standard.set(data, forKey: "state")
        }
    }

    func normalize(_ value: String) -> String {
        var s = value.lowercased()
            .replacingOccurrences(of: "ي", with: "ی")
            .replacingOccurrences(of: "ك", with: "ک")
        let fa = Array("۰۱۲۳۴۵۶۷۸۹")
        for i in 0..<fa.count { s = s.replacingOccurrences(of: String(fa[i]), with: String(i)) }
        return s.replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    func detectBrand(_ value: String) -> String {
        let s = normalize(value)
        let brands:[(String,[String])] = [
            ("Ronix",["ronix","رونیکس"]),("Tosan",["tosan","توسن"]),
            ("Anchor",["anchor","آنکور","انکر"]),("Nova",["nova","نووا"]),
            ("Arva",["arva","آروا"]),("Pukka",["pukka","پوکا"]),
            ("Vivarex",["vivarex","ویوارکس"]),("Hans",["hans","هنس"]),
            ("Winner",["winner","وینر"])
        ]
        for (name,keys) in brands where keys.contains(where:{ s.contains(normalize($0)) }) { return name }
        return "نامشخص"
    }

    func formulaKey(brand:String, product:String) -> String {
        "\(normalize(brand))||\(normalize(product))"
    }

    func formula(for p:Product) -> String? {
        let exactCode = formulaKey(brand: p.brand, product: p.code.isEmpty ? p.name : p.code)
        if let v = state.formulas[exactCode] { return v }
        return state.formulas[formulaKey(brand: p.brand, product: "")]
    }

    func applyFormula(_ price:Double, _ formula:String) -> Double {
        var value = price
        for raw in formula.split(separator:"=") {
            let step = raw.trimmingCharacters(in:.whitespaces)
            guard let l = step.firstIndex(of:"("), let r = step.firstIndex(of:")") else { continue }
            let op = String(step[..<l]).lowercased()
            let args = step[step.index(after:l)..<r].split(separator:",").compactMap{ Double($0.trimmingCharacters(in:.whitespaces)) }
            if op == "discount", let x=args.first { value *= 1 - x/100 }
            else if op == "gift", args.count >= 2, args[0]+args[1] > 0 { value *= args[0]/(args[0]+args[1]) }
            else if (op == "cost" || op == "markup"), let x=args.first { value *= 1 + x/100 }
            else if op == "margin", let x=args.first, x < 100 { value /= 1 - x/100 }
            else if op == "round", let x=args.first, x > 0 { value = ceil(value/x)*x }
        }
        return value
    }

    func products() -> [Product] { state.docs.flatMap(\.products) }

    func activate(phone:String, code:String) async throws -> [String:Any] {
        let body:[String:Any] = [
            "phone":phone.trimmingCharacters(in:.whitespacesAndNewlines),
            "code":code.trimmingCharacters(in:.whitespacesAndNewlines),
            "device_id":state.deviceId,
            "app_version":"2.2.0-ios"
        ]
        let json = try await request(path:"activate", method:"POST", token:"", body:body)
        guard let token=json["token"] as? String, !token.isEmpty else { throw NSError(domain:"License",code:1,userInfo:[NSLocalizedDescriptionKey:"توکن فعال‌سازی دریافت نشد."]) }
        state.licenseToken=token
        state.licensePhone=(json["phone"] as? String) ?? phone
        save()
        return json
    }

    func licenseStatus() async throws -> [String:Any] {
        guard !state.licenseToken.isEmpty else { throw NSError(domain:"License",code:2,userInfo:[NSLocalizedDescriptionKey:"مجوز فعال نشده است."]) }
        return try await request(path:"status", method:"GET", token:state.licenseToken, body:nil)
    }

    func request(path:String, method:String, token:String, body:[String:Any]?) async throws -> [String:Any] {
        var req=URLRequest(url:gateway.appendingPathComponent(path))
        req.httpMethod=method
        req.timeoutInterval=80
        req.setValue("application/json", forHTTPHeaderField:"Accept")
        if !token.isEmpty { req.setValue("Bearer \(token)", forHTTPHeaderField:"Authorization") }
        if let body {
            req.setValue("application/json", forHTTPHeaderField:"Content-Type")
            req.httpBody=try JSONSerialization.data(withJSONObject:body)
        }
        let (data,res)=try await URLSession.shared.data(for:req)
        let http=res as! HTTPURLResponse
        let obj=(try? JSONSerialization.jsonObject(with:data)) as? [String:Any] ?? [:]
        guard (200..<300).contains(http.statusCode) else {
            let msg=(obj["message"] as? String) ?? (obj["error"] as? String) ?? "HTTP \(http.statusCode)"
            throw NSError(domain:"HTTP",code:http.statusCode,userInfo:[NSLocalizedDescriptionKey:msg])
        }
        return obj
    }

    func cloudAnalyze(data:Data, mime:String, pageStart:Int, brand:String, source:String) async throws -> [String:Any] {
        let body:[String:Any] = [
            "file":data.base64EncodedString(),
            "mime_type":mime,
            "page":pageStart,
            "brand_hint":brand,
            "source_name":source
        ]
        let json=try await request(path:"analyze", method:"POST", token:state.licenseToken, body:body)
        guard let result=json["result"] as? [String:Any] else { throw NSError(domain:"AI",code:1,userInfo:[NSLocalizedDescriptionKey:"پاسخ AI ساختاری نبود."]) }
        return result
    }

    func cloudAnalyzePDF(url:URL, brand:String) async throws -> [String:Any] {
        guard let pdf=PDFDocument(url:url) else { throw NSError(domain:"PDF",code:1,userInfo:[NSLocalizedDescriptionKey:"PDF باز نشد."]) }
        var merged:[[String:Any]]=[]
        var finalBrand=""
        var start=0
        while start < pdf.pageCount {
            let chunk=PDFDocument()
            let end=min(start+4,pdf.pageCount)
            for i in start..<end {
                if let page=pdf.page(at:i)?.copy() as? PDFPage { chunk.insert(page, at:chunk.pageCount) }
            }
            guard let data=chunk.dataRepresentation() else { break }
            let result=try await cloudAnalyze(data:data,mime:"application/pdf",pageStart:start+1,brand:brand,source:"\(url.lastPathComponent) | original pages \(start+1)-\(end)")
            if finalBrand.isEmpty { finalBrand=result["brand"] as? String ?? "" }
            for var p in (result["products"] as? [[String:Any]] ?? []) {
                if let local=p["page"] as? Int, (1...4).contains(local) { p["page"]=local+start }
                merged.append(p)
            }
            start=end
        }
        return ["brand":finalBrand,"products":merged]
    }

    func paddleAnalyze(data:Data, isPDF:Bool) async throws -> [String:Any] {
        guard let base=URL(string:state.paddleURL.trimmingCharacters(in:.whitespacesAndNewlines)), !state.paddleURL.isEmpty else {
            throw NSError(domain:"Paddle",code:1,userInfo:[NSLocalizedDescriptionKey:"آدرس Paddle تنظیم نشده است."])
        }
        var req=URLRequest(url:base.appendingPathComponent("layout-parsing"))
        req.httpMethod="POST"; req.timeoutInterval=180
        req.setValue("application/json",forHTTPHeaderField:"Content-Type")
        let body:[String:Any]=[
            "file":data.base64EncodedString(),"fileType":isPDF ? 0:1,
            "useDocOrientationClassify":true,"useDocUnwarping":false,"useLayoutDetection":true,
            "formatBlockContent":true,"restructurePages":false,"returnMarkdownImages":false,"visualize":false
        ]
        req.httpBody=try JSONSerialization.data(withJSONObject:body)
        let (d,r)=try await URLSession.shared.data(for:req)
        let http=r as! HTTPURLResponse
        let obj=(try JSONSerialization.jsonObject(with:d)) as! [String:Any]
        guard (200..<300).contains(http.statusCode) else { throw NSError(domain:"Paddle",code:http.statusCode,userInfo:[NSLocalizedDescriptionKey:"Paddle HTTP \(http.statusCode)"]) }
        return obj
    }

    func pdfText(_ url:URL) -> String {
        guard let pdf=PDFDocument(url:url) else { return "" }
        return (0..<pdf.pageCount).compactMap { i in pdf.page(at:i)?.string.map{"\n--- PAGE \(i+1) ---\n\($0)"} }.joined()
    }

    func parseLocal(text:String, brand:String, source:String) -> [Product] {
        let unit = normalize(text).contains("ریال") ? "rial" : (normalize(text).contains("تومان") ? "toman" : "unknown")
        let rx=try! NSRegularExpression(pattern:"(?<!\\\\w)([0-9۰-۹]{1,3}(?:[٬,/][0-9۰-۹]{3}){2,3}|[0-9۰-۹]{6,12})(?!\\\\w)")
        let codeRx=try! NSRegularExpression(pattern:"\\\\b(?:[A-Za-z]{1,8}[-_]?[0-9]{2,8}[A-Za-z0-9-]*|[0-9]{4,6}[A-Za-z]?)\\\\b")
        var out:[Product]=[]
        for line in text.components(separatedBy:.newlines).filter({!$0.trimmingCharacters(in:.whitespaces).isEmpty}) {
            let ns=line as NSString
            let matches=rx.matches(in:line,range:NSRange(location:0,length:ns.length))
            guard let m=matches.first else { continue }
            let token=ns.substring(with:m.range)
            let digits=normalize(token).replacingOccurrences(of:"[٬,/]",with:"",options:.regularExpression)
            guard let sourcePrice=Double(digits), sourcePrice>=50000 else { continue }
            let cm=codeRx.firstMatch(in:line,range:NSRange(location:0,length:ns.length))
            let code=cm.map{ns.substring(with:$0.range)} ?? ""
            var name=line.replacingOccurrences(of:token,with:" ")
            if !code.isEmpty { name=name.replacingOccurrences(of:code,with:" ") }
            name=name.replacingOccurrences(of:"\\s+",with:" ",options:.regularExpression).trimmingCharacters(in:.whitespacesAndNewlines)
            if name.count<3 { continue }
            out.append(Product(brand:brand,name:name,code:code,sourcePrice:sourcePrice,priceUnit:unit,rawPrice:unit=="rial" ? sourcePrice/10:sourcePrice,priceType:"list",page:0,confidence:0,promotion:"",source:source))
        }
        return out
    }

    func decodeProducts(_ result:[String:Any], fallbackBrand:String, source:String) -> [Product] {
        let brand=(result["brand"] as? String).flatMap{$0.isEmpty ? nil:$0} ?? fallbackBrand
        return (result["products"] as? [[String:Any]] ?? []).compactMap { p in
            guard let name=p["name"] as? String, !name.isEmpty else { return nil }
            let price=(p["price"] as? NSNumber)?.doubleValue ?? 0
            let unit=p["price_unit"] as? String ?? "unknown"
            guard price>=50000, ["rial","toman"].contains(unit) else { return nil }
            return Product(
                brand:brand,name:name,code:p["code"] as? String ?? "",
                sourcePrice:price,priceUnit:unit,rawPrice:unit=="rial" ? price/10:price,
                priceType:p["price_type"] as? String ?? "list",
                page:(p["page"] as? NSNumber)?.intValue ?? 0,
                confidence:(p["confidence"] as? NSNumber)?.doubleValue ?? 0,
                promotion:p["promotion"] as? String ?? "",source:source
            )
        }
    }

    func importFile(_ sourceURL:URL, brandOverride:String) async throws {
        let fm=FileManager.default
        let dir=fm.urls(for:.documentDirectory,in:.userDomainMask)[0].appendingPathComponent("uploads")
        try? fm.createDirectory(at:dir,withIntermediateDirectories:true)
        let dest=dir.appendingPathComponent("\(UUID().uuidString)_\(sourceURL.lastPathComponent)")
        let access=sourceURL.startAccessingSecurityScopedResource()
        defer { if access { sourceURL.stopAccessingSecurityScopedResource() } }
        if fm.fileExists(atPath:dest.path) { try fm.removeItem(at:dest) }
        try fm.copyItem(at:sourceURL,to:dest)

        let ext=dest.pathExtension.lowercased()
        let isPDF=ext=="pdf"
        let mime=isPDF ? "application/pdf" : (["png"].contains(ext) ? "image/png" : (["jpg","jpeg"].contains(ext) ? "image/jpeg" : (ext=="webp" ? "image/webp":"text/plain")))
        let text=isPDF ? pdfText(dest) : ((try? String(contentsOf:dest,encoding:.utf8)) ?? "")
        let brand=brandOverride.isEmpty ? detectBrand(dest.lastPathComponent+"\n"+text) : brandOverride

        var result:[String:Any]?=nil
        if !state.licenseToken.isEmpty && (isPDF || mime.hasPrefix("image/")) {
            result = try? (isPDF ? await cloudAnalyzePDF(url:dest,brand:brand) : await cloudAnalyze(data:Data(contentsOf:dest),mime:mime,pageStart:1,brand:brand,source:dest.lastPathComponent))
        }
        // Paddle is preserved as fallback.
        if result == nil && !state.paddleURL.isEmpty && (isPDF || mime.hasPrefix("image/")) {
            _ = try? await paddleAnalyze(data:Data(contentsOf:dest),isPDF:isPDF)
        }
        let products = result.map{ decodeProducts($0,fallbackBrand:brand,source:dest.lastPathComponent) } ?? parseLocal(text:text,brand:brand,source:dest.lastPathComponent)
        let finalBrand=(result?["brand"] as? String).flatMap{$0.isEmpty ? nil:$0} ?? brand
        state.docs.append(DocumentItem(brand:finalBrand,name:dest.lastPathComponent,path:dest.path,mime:mime,text:text,products:products))
        save()
    }
}

@main
struct TajeriToolsPriceManagerApp: App {
    @StateObject var store=Store()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var store:Store
    var body: some View {
        TabView {
            SearchView().tabItem{Label("جستجو",systemImage:"magnifyingglass")}
            CatalogView().tabItem{Label("کاتالوگ",systemImage:"square.grid.2x2")}
            FilesView().tabItem{Label("فایل‌ها",systemImage:"doc")}
            FormulaView().tabItem{Label("فرمول",systemImage:"function")}
            LicenseView().tabItem{Label("مجوز",systemImage:"key")}
            SettingsView().tabItem{Label("تنظیمات",systemImage:"gear")}
        }
        .overlay(alignment:.top) {
            if store.busy { ProgressView().progressViewStyle(.linear).padding(.top,2) }
        }
        .environment(\.layoutDirection,.rightToLeft)
    }
}

struct SearchView: View {
    @EnvironmentObject var store:Store
    @State var q=""
    var results:[Product] {
        let n=store.normalize(q)
        return store.products().filter { n.isEmpty || store.normalize("\($0.brand) \($0.name) \($0.code)").contains(n) }
    }
    var body: some View {
        NavigationStack {
            List {
                TextField("نام، مدل یا برند",text:$q)
                ForEach(results.prefix(300)) { p in
                    VStack(alignment:.trailing,spacing:4) {
                        Text(p.name).font(.headline)
                        Text("\(p.brand) • \(p.code)").font(.caption)
                        Text("قیمت مبنا: \(Int(p.rawPrice).formatted()) تومان")
                        if let f=store.formula(for:p) {
                            Text("قیمت نهایی: \(Int(store.applyFormula(p.rawPrice,f)).formatted()) تومان").bold()
                        }
                    }.frame(maxWidth:.infinity,alignment:.trailing)
                }
            }.navigationTitle("جستجو")
        }
    }
}

struct CatalogView: View {
    @EnvironmentObject var store:Store
    @State var q=""
    var rows:[Product] {
        let n=store.normalize(q)
        return store.products().filter{ n.isEmpty || store.normalize("\($0.brand) \($0.name) \($0.code)").contains(n) }
    }
    var body: some View {
        NavigationStack {
            List {
                TextField("مثال: دریل، 5304، C2106",text:$q)
                ForEach(rows.prefix(300)) { p in
                    VStack(alignment:.trailing,spacing:5) {
                        Text(p.name).font(.headline)
                        Text("برند: \(p.brand)")
                        if !p.code.isEmpty { Text("مدل/کد: \(p.code)") }
                        if p.page>0 { Text("صفحه PDF: \(p.page)") }
                        Text("قیمت منبع: \(Int(p.sourcePrice).formatted()) \(p.priceUnit)")
                        if !p.promotion.isEmpty { Text("اشانتیون: \(p.promotion)") }
                        if p.confidence>0 { Text("اطمینان AI: \(Int(p.confidence*100))٪") }
                    }.frame(maxWidth:.infinity,alignment:.trailing)
                }
            }.navigationTitle("کاتالوگ")
        }
    }
}

struct DocumentPicker: UIViewControllerRepresentable {
    var onPick:([URL])->Void
    func makeUIViewController(context:Context)->UIDocumentPickerViewController {
        let vc=UIDocumentPickerViewController(forOpeningContentTypes:[.pdf,.image,.plainText,.commaSeparatedText],asCopy:false)
        vc.allowsMultipleSelection=true; vc.delegate=context.coordinator; return vc
    }
    func updateUIViewController(_ uiViewController:UIDocumentPickerViewController,context:Context){}
    func makeCoordinator()->Coordinator{Coordinator(onPick:onPick)}
    final class Coordinator:NSObject,UIDocumentPickerDelegate{
        let onPick:([URL])->Void; init(onPick:@escaping([URL])->Void){self.onPick=onPick}
        func documentPicker(_ controller:UIDocumentPickerViewController,didPickDocumentsAt urls:[URL]){onPick(urls)}
    }
}

struct FilesView: View {
    @EnvironmentObject var store:Store
    @State var showPicker=false
    @State var brand=""
    var body: some View {
        NavigationStack {
            List {
                TextField("برند اختیاری",text:$brand)
                Button("افزودن PDF / عکس / CSV / متن"){showPicker=true}
                ForEach(store.state.docs) { d in
                    VStack(alignment:.trailing) {
                        Text(d.name).bold()
                        Text("\(d.brand) • \(d.products.count) محصول").font(.caption)
                    }
                }.onDelete { idx in
                    for i in idx.sorted(by:>) {
                        let d=store.state.docs.remove(at:i)
                        try? FileManager.default.removeItem(atPath:d.path)
                    }
                    store.save()
                }
            }.navigationTitle("فایل‌ها")
            .sheet(isPresented:$showPicker) {
                DocumentPicker { urls in
                    showPicker=false
                    Task {
                        store.busy=true
                        for u in urls {
                            do { try await store.importFile(u,brandOverride:brand.trimmingCharacters(in:.whitespacesAndNewlines)) }
                            catch { store.message=error.localizedDescription }
                        }
                        store.busy=false
                    }
                }
            }
        }
    }
}

struct FormulaView: View {
    @EnvironmentObject var store:Store
    @State var brand=""; @State var product=""; @State var formula="discount(18)=markup(10)=round(10000)"
    var body: some View {
        NavigationStack {
            Form {
                Section("فرمول جدید") {
                    TextField("برند",text:$brand)
                    TextField("محصول/مدل؛ خالی = پیش‌فرض برند",text:$product)
                    TextField("فرمول",text:$formula)
                    Button("ذخیره") {
                        guard !brand.trimmingCharacters(in:.whitespaces).isEmpty else{return}
                        store.state.formulas[store.formulaKey(brand:brand,product:product)]=formula
                        store.save()
                    }
                }
                Section("فرمول‌های ذخیره‌شده") {
                    ForEach(Array(store.state.formulas.keys.sorted()),id:\.self) { k in
                        HStack {
                            Button(role: .destructive, action: { store.state.formulas.removeValue(forKey: k); store.save() }) { Image(systemName: "trash") }
                            Spacer()
                            VStack(alignment:.trailing){Text(k.replacingOccurrences(of:"||",with:" / "));Text(store.state.formulas[k] ?? "").font(.caption)}
                        }
                    }
                    if !store.state.formulas.isEmpty {
                        Button("حذف همه",role:.destructive){store.state.formulas.removeAll();store.save()}
                    }
                }
            }.navigationTitle("فرمول")
        }
    }
}

struct LicenseView: View {
    @EnvironmentObject var store:Store
    @State var phone=""; @State var code=""; @State var status=""
    var body: some View {
        NavigationStack {
            Form {
                Section("مجوز و AI ابری TajeriTools") {
                    TextField("شماره موبایل",text:$phone).keyboardType(.phonePad)
                    TextField("کد فعال‌سازی",text:$code).textInputAutocapitalization(.characters)
                    Button("فعال‌سازی") {
                        Task {
                            store.busy=true
                            do {
                                let p=try await store.activate(phone:phone,code:code)
                                status="فعال شد\nAI: \(p["ai_model"] ?? "")\nسقف روزانه: \(p["max_daily"] ?? "")"
                            } catch { status=error.localizedDescription }
                            store.busy=false
                        }
                    }
                    Button("بررسی وضعیت") {
                        Task {
                            do {
                                let p=try await store.licenseStatus()
                                status="وضعیت: فعال\nAI: \(p["ai_model"] ?? "")\nمصرف امروز: \(p["used_today"] ?? 0) / \(p["max_daily"] ?? 0)"
                            } catch { status=error.localizedDescription }
                        }
                    }
                    if !store.state.licenseToken.isEmpty {
                        Button("خروج از مجوز این دستگاه",role:.destructive){
                            store.state.licenseToken="";store.state.licensePhone="";store.save();status="خارج شد."
                        }
                    }
                    if !status.isEmpty { Text(status) }
                }
                Section("ترتیب پردازش") {
                    Text("1) AI ابری TajeriTools: Mistral Document AI → Gemini fallback")
                    Text("2) PaddleOCR-VL قبلی در صورت تنظیم")
                    Text("3) Gemini شخصی قبلی در صورت تنظیم")
                    Text("4) parser محلی")
                }
            }.navigationTitle("مجوز")
            .onAppear{phone=store.state.licensePhone}
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject var store:Store
    @State var paddle=""; @State var gemini=""
    var body: some View {
        NavigationStack {
            Form {
                Section("PaddleOCR-VL") {
                    TextField("http://192.168.x.x:8080",text:$paddle)
                    Button("ذخیره Paddle"){store.state.paddleURL=paddle.trimmingCharacters(in:.whitespacesAndNewlines);store.save()}
                }
                Section("Gemini شخصی قبلی") {
                    SecureField("Gemini API Key",text:$gemini)
                    Button("ذخیره Gemini"){store.state.geminiKey=gemini.trimmingCharacters(in:.whitespacesAndNewlines);store.save()}
                }
                Section {
                    Text("AI ابری با مجوز بدون کامپیوتر کار می‌کند. کلیدهای اصلی Mistral/Gemini روی tajeritools.ir می‌مانند.")
                }
            }.navigationTitle("تنظیمات")
            .onAppear{paddle=store.state.paddleURL;gemini=store.state.geminiKey}
        }
    }
}
