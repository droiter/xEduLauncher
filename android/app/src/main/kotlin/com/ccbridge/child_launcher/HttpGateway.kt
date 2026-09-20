package com.ccbridge.child_launcher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文件传输服务：家长用电脑/手机的浏览器连上这台设备，下载日志、上传视频等文件。
 *
 * 零依赖手写 HTTP/1.1（项目一贯不用第三方库）：
 *   GET  /            目录列表（?p=当前目录）
 *   GET  /get?p=路径   下载（支持 Range 分片，可断点续传）
 *   GET  /ls?p=目录    这个目录里已有的名字（JSON），浏览器选完文件先拿它预检重名
 *   POST /up?p=目录    上传，multipart/form-data
 *   POST /mkdir?p=目录 在这个目录下新建子目录，字段 name=
 *
 * 路径空间（?p= 的值，浏览和上传落点是同一个：浏览到哪一层就传到哪一层）：
 *   ""              根：列出下面两个位置
 *   dl              /sdcard/Download/（默认落点，其他应用也看得到）
 *   dl/子目录/…     Download 下本应用建过的子目录
 *   files           /sdcard/Android/data/<包名>/files（logs/ 是运行日志和自检报告）
 *   files/子目录/…  私有目录下随便进出、随便新建
 *
 * 两个位置都能进目录、回上一级、新建子目录（owner 2026-09-19 要求：原来只有上传目标一个下拉框，
 * 想进哪一层得先在列表里点、想回上一级得靠「返回上一层」，建不了目录）。
 * 注意 Download/ 那半边的目录是 MediaStore 里的记录，只看得见本应用自己放进去的东西（见
 * [PublicDownloads]）；私有目录那半边是普通文件系统，没这个限制。
 *
 * 重名一律**拒绝上传**，不覆盖也不自动改名（owner 2026-09-17 要求）：选的目录里已经有同名文件时
 * 一个字节都不写，传完在结果页单独列出「因重名未上传」的清单。浏览器那头选完文件会先预检一遍、
 * 把重名的剔出上传队列，服务端落盘前再查一次兜底（预检到落盘之间文件可能被别处加进来）。
 *
 * 安全上有三道：
 *   ① 服务默认关着，家长要在「家长设置 → 文件传输」里手动打开；
 *   ② 每台新设备第一次连上来，必须在**这台设备上**点一次「同意」（见 [HttpConsentActivity]），
 *      没同意之前浏览器只看得到一页「等待确认」，什么文件都列不出来；
 *   ③ 路径一律先 canonical 化再确认落在根目录里，杜绝 ../ 穿越。
 */
object HttpGateway {

    /** 端口被占用时往后顺延的个数 */
    private const val PORT_TRIES = 10

    /** 一个请求挂太久就丢弃，免得线程被占死 */
    private const val READ_TIMEOUT_MS = 30_000

    /** 上传大文件要慢得多，这一档单独放宽 */
    private const val UPLOAD_TIMEOUT_MS = 300_000

    /** 一个 IP 等太久没被处理就当它过期，重新问一次 */
    private const val PENDING_TTL_MS = 300_000L

    /** 家长点了「拒绝」之后，这段时间内同一台设备不再弹框 */
    private const val DENY_COOLDOWN_MS = 60_000L

    /** 路径空间里两个位置的前缀：公共下载目录 / 应用私有目录 */
    private const val LOC_DL = "dl"
    private const val LOC_FILES = "files"

    /** 「快捷切换」下拉框里最多列这么多目录，免得目录一多页面就爆 */
    private const val MAX_TARGET_DIRS = 300
    private const val MAX_TARGET_DEPTH = 6

    /** 落盘结果 */
    private const val UP_OK = 0
    private const val UP_DUP = 1
    private const val UP_FAIL = 2

    /** PublicDownloads 这次走不通（Android 9 或 MediaStore 插入失败），要退回私有目录写 */
    private const val UP_FALLBACK = 3

    /** 丢弃字节用的水槽：重名的文件不写盘，但正文照样得从连接里读干净 */
    private val nullSink = object : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    private val lock = Any()
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "http-gateway-conn").apply { isDaemon = true }
    }

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var port = 0

    @Volatile
    private var acceptThread: Thread? = null

    private val running = AtomicBoolean(false)

    /**
     * 正在写、还没写完的名字占位（私有目录用绝对路径，公共目录用 "pub:名字"）。
     * 查重名时除了看磁盘上有没有，还要看这里——两个浏览器同时传同名文件时，
     * 光看磁盘两边都是「还不存在」，会一起写进去把对方覆盖掉。
     */
    private val inflight: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())


    /** 正在等家长点「同意」的那台设备，null = 没有 */
    @Volatile
    private var pendingIp: String? = null
    private var pendingAt = 0L

    /** 已经为哪个 IP 拉起过同意页（同一个 IP 只拉一次，别每秒弹一个） */
    private var consentShownFor: String? = null

    /** 刚刚被家长拒绝的 IP，浏览器那一页据此给个明确说法 */
    @Volatile
    private var deniedIp: String? = null
    private var deniedAt = 0L

    // ---------- 生命周期 ----------

    fun isRunning(): Boolean = running.get()

    fun port(): Int = port

    /** 一个能直接敲进浏览器的地址；服务没开时返回空串 */
    fun urlOrEmpty(ctx: Context): String {
        if (!running.get()) return ""
        val ip = lanIps().firstOrNull() ?: return ""
        return "http://$ip:$port"
    }

    fun urls(ctx: Context): List<String> =
        if (running.get()) lanIps().map { "http://$it:$port" } else emptyList()

    fun start(ctx: Context): Boolean {
        synchronized(lock) {
            if (running.get()) return true
            var sock: ServerSocket? = null
            var bound = 0
            for (i in 0 until PORT_TRIES) {
                try {
                    sock = ServerSocket(Store.DEFAULT_FILE_PORT + i)
                    bound = Store.DEFAULT_FILE_PORT + i
                    break
                } catch (_: Exception) {
                    sock = null
                }
            }
            if (sock == null) {
                Diag.log("http", "端口 ${Store.DEFAULT_FILE_PORT}~${Store.DEFAULT_FILE_PORT + PORT_TRIES - 1} 全被占用，服务没起来")
                return false
            }
            server = sock
            port = bound
            running.set(true)
            Store.revokeIps(ctx) // 每次开启都重新问一遍
            pendingIp = null
            consentShownFor = null
            // 先把目录建出来：家长连上来第一眼就能看到上传放哪、日志在哪
            try {
                File(Store.filesRoot(ctx), "uploads").mkdirs()
                Diag.logDir(ctx).mkdirs()
            } catch (_: Exception) {
            }
            val t = Thread({ acceptLoop(ctx.applicationContext, sock) }, "http-gateway")
            t.isDaemon = true
            acceptThread = t
            t.start()
            Diag.log("http", "文件传输服务已启动：${urls(ctx).joinToString(" / ")}，根目录 ${Store.filesRoot(ctx)}")
            Audit.record(Audit.SERVICE, "文件传输", "服务已启动，监听 ${urls(ctx).joinToString(" / ")}（每台新设备都要在设备上点同意）")
            return true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!running.getAndSet(false)) return
            try {
                server?.close()
            } catch (_: Exception) {
            }
            server = null
            port = 0
            pendingIp = null
            consentShownFor = null
            Diag.log("http", "文件传输服务已停止")
            Audit.record(Audit.SERVICE, "文件传输", "服务已停止（已同意的设备名单一并清空）")
        }
    }

    private fun acceptLoop(ctx: Context, sock: ServerSocket) {
        while (running.get()) {
            val s = try {
                sock.accept()
            } catch (_: Exception) {
                break
            }
            try {
                pool.execute { serveSafely(ctx, s) }
            } catch (_: Exception) {
                try {
                    s.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------- 同意 ----------

    fun pendingIp(): String? = pendingIp

    /** 桌面回到前台：同意页可能是被系统拦住没弹出来，这里补一次 */
    fun onLauncherResume(ctx: Context) {
        val ip = pendingIp ?: return
        askConsent(ctx, ip, force = true)
    }

    fun approve(ctx: Context) {
        val ip = pendingIp ?: return
        Store.approveIp(ctx, ip)
        deniedIp = null
        pendingIp = null
    }

    fun deny(ctx: Context) {
        val ip = pendingIp ?: return
        deniedIp = ip
        deniedAt = SystemClock.elapsedRealtime()
        pendingIp = null
        Diag.log("http", "家长拒绝了 $ip 的连接")
        Audit.record(Audit.HTTP, ip, "家长点了「拒绝」，这台设备看不了也用不了（60 秒内不再弹框）")
    }

    /** 这台设备已被同意过吗；没同意就顺手把同意页拉起来，并返回 false */
    private fun ensureApproved(ctx: Context, ip: String): Boolean {
        if (ip in Store.approvedIps(ctx)) return true
        // 刚被拒过就先冷一会儿：浏览器那一页每 2 秒刷一次，不冷一下会变成连环弹框
        if (deniedIp == ip && SystemClock.elapsedRealtime() - deniedAt < DENY_COOLDOWN_MS) return false
        val stale = pendingIp != null && SystemClock.elapsedRealtime() - pendingAt > PENDING_TTL_MS
        if (pendingIp != ip || stale) {
            pendingIp = ip
            pendingAt = SystemClock.elapsedRealtime()
            consentShownFor = null
            Diag.log("http", "$ip 请求连接，等家长在设备上确认")
            Audit.record(Audit.HTTP, ip, "有设备连过来（浏览器打开了地址），等家长在设备上点同意")
        }
        askConsent(ctx, ip, force = false)
        return false
    }

    private fun askConsent(ctx: Context, ip: String, force: Boolean) {
        if (pendingIp != ip) return
        if (!force && consentShownFor == ip) return
        if (HttpConsentActivity.showing) return
        consentShownFor = ip
        val i = Intent(ctx, HttpConsentActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(HttpConsentActivity.EXTRA_IP, ip)
        try {
            // 桌面 onPause 早于同意页 onCreate，这里先把标记立起来，见 Store.showLock 里那段说明
            HttpConsentActivity.showing = true
            ctx.startActivity(i)
        } catch (e: Exception) {
            HttpConsentActivity.showing = false
            // 后台启动 Activity 被系统拦了：桌面下次回前台时 onLauncherResume 会再补一次
            Diag.log("http", "拉起同意页失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    // ---------- 单个连接 ----------

    private fun serveSafely(ctx: Context, s: Socket) {
        try {
            serve(ctx, s)
        } catch (e: Exception) {
            Diag.log("http", "处理请求出错：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun serve(ctx: Context, s: Socket) {
        s.soTimeout = READ_TIMEOUT_MS
        val ip = s.inetAddress?.hostAddress ?: "?"
        val ins = BufferedInputStream(s.getInputStream(), 64 * 1024)
        val br = BackReader(ins)
        val out = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
        val req = readRequest(br) ?: return

        if (req.method == "POST") s.soTimeout = UPLOAD_TIMEOUT_MS

        if (!ensureApproved(ctx, ip)) {
            page(out, html(waitPage(ip)))
            return
        }
        deniedIp = null

        val q = req.target.indexOf('?')
        val path = if (q < 0) req.target else req.target.substring(0, q)
        val query = parseQuery(if (q < 0) "" else req.target.substring(q + 1))

        when {
            // 没带 ?p= 的（家长刚敲地址进来）直接落到默认位置，省得先点一层；
            // 带了 ?p=（哪怕是空的，面包屑里的「根目录」）就照给的路径渲染
            path == "/" || path == "/index.html" ->
                if (query.containsKey("p")) {
                    listPage(ctx, out, query["p"].orEmpty(), query["done"].orEmpty(), query["err"].orEmpty())
                } else {
                    redirect(out, "/?p=${urlEnc(if (PublicDownloads.available()) LOC_DL else LOC_FILES)}")
                }
            path == "/get" -> download(ctx, req, out, query["p"].orEmpty(), ip)
            path == "/ls" -> listNames(ctx, out, query["p"].orEmpty())
            path == "/up" && req.method == "POST" -> upload(ctx, req, br, out, ip)
            path == "/mkdir" && req.method == "POST" -> mkdir(ctx, req, br, out, ip)
            else -> page(out, 404, "没这个地址")
        }
    }

    // ---------- 列表 / 下载 ----------

    /**
     * 路径空间里的一条路径收敛成标准形式：
     * "" 根、dl / dl/子目录、files / files/子目录。不认识的（含 ../ 之类）一律落到根。
     */
    private fun normalizePath(p: String): String {
        val v = p.replace('\\', '/').trim().trim('/')
        return when {
            v.isEmpty() -> ""
            v == LOC_DL || v.startsWith("$LOC_DL/") -> v
            v == LOC_FILES || v.startsWith("$LOC_FILES/") -> v
            else -> ""
        }
    }

    /** 目录列表里的一行 */
    private class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val mtime: Long)

    private fun dlRel(p: String): String = if (p == LOC_DL) "" else p.removePrefix("$LOC_DL/")

    private fun filesRel(p: String): String = if (p == LOC_FILES) "" else p.removePrefix("$LOC_FILES/")

    private fun childPath(p: String, name: String): String = if (p.isEmpty()) name else "$p/$name"

    private fun parentPath(p: String): String? = when {
        p.isEmpty() -> null
        p == LOC_DL || p == LOC_FILES -> ""
        else -> p.substringBeforeLast('/', "")
    }

    /**
     * 列一个目录。返回 null 表示这个目录不存在。
     * 根那一层是虚拟的：只列出两个位置，没有别的。
     */
    private fun listingAt(ctx: Context, p: String): List<Entry>? {
        if (p.isEmpty()) {
            val out = ArrayList<Entry>()
            if (PublicDownloads.available()) {
                out.add(Entry("Download/", LOC_DL, true, 0, 0))
            }
            out.add(Entry("files/", LOC_FILES, true, 0, 0))
            return out
        }
        if (p == LOC_DL || p.startsWith("$LOC_DL/")) {
            val rel = dlRel(p)
            return PublicDownloads.listAt(ctx, rel).map {
                Entry(
                    it.name + if (it.dir) "/" else "",
                    childPath(p, it.name), it.dir, it.size, it.addedAt,
                )
            }
        }
        val root = Store.filesRoot(ctx)
        val dir = resolve(root, filesRel(p)) ?: return null
        if (!dir.isDirectory) return null
        val items = dir.listFiles()
            ?.filter { !it.name.startsWith(".") } // 上传中的临时文件不给看
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
        return items.map {
            Entry(
                it.name + if (it.isDirectory) "/" else "",
                childPath(p, it.name), it.isDirectory,
                if (it.isDirectory) 0L else it.length(), it.lastModified(),
            )
        }
    }

    /** 位置那一排：两个（或这台设备上可用的那几个）顶层位置 */
    private fun locationLinks(p: String): String {
        val sb = StringBuilder("<b>位置</b> ")
        fun link(path: String, label: String) {
            val here = p == path || (path.isNotEmpty() && p.startsWith("$path/"))
            if (here) sb.append("<b>$label</b> ")
            else sb.append("<a class=btn href=\"/?p=${urlEnc(path)}\">$label</a> ")
        }
        if (PublicDownloads.available()) link(LOC_DL, "Download/（公共下载目录）")
        link(LOC_FILES, "files/（应用私有目录）")
        return sb.toString()
    }

    /** 当前位置的面包屑，每一层都能点 */
    private fun crumbsOf(p: String): String {
        val sb = StringBuilder("<a href=\"/?p=\">根目录</a>")
        if (p.isEmpty()) return sb.toString()
        val parts = p.split('/')
        var acc = ""
        for ((i, seg) in parts.withIndex()) {
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            // 头一段是位置前缀，写成人话，别直接把 dl/files 甩给家长看
            val label = if (i == 0) (if (seg == LOC_DL) "Download/" else "files/") else seg
            sb.append(" / ")
            if (i == parts.size - 1) sb.append("<b>${esc(label)}</b>")
            else sb.append("<a href=\"/?p=${urlEnc(acc)}\">${esc(label)}</a>")
        }
        return sb.toString()
    }

    private fun listPage(ctx: Context, out: OutputStream, pArg: String, done: String, err: String) {
        val p = normalizePath(pArg)
        val entries = listingAt(ctx, p)
        if (entries == null) {
            page(out, 404, "目录不存在")
            return
        }
        val target = if (p.isEmpty()) null else resolveTarget(ctx, p)
        val sb = StringBuilder()
        sb.append(docHead("儿童桌面 · 文件传输"))
        sb.append("<h2>儿童桌面文件传输</h2>")
        sb.append("<p class=mut>应用私有目录：${esc(Store.filesRoot(ctx).absolutePath)}</p>")

        sb.append("<div class=bar>${locationLinks(p)}")
        sb.append("<div style=\"margin-top:10px\">当前位置：${crumbsOf(p)}")
        val up = parentPath(p)
        if (up != null) sb.append(" <a class=btn href=\"/?p=${urlEnc(up)}\">▲ 上一级</a>")
        sb.append("</div>")
        if (p.isNotEmpty()) {
            sb.append("<form method=post action=\"/mkdir?p=${urlEnc(p)}\" class=urow style=\"margin-top:10px\">")
            sb.append("<input type=text name=name placeholder=\"新子目录的名字\" maxlength=60 required>")
            sb.append(" <button type=submit>新建子目录</button></form>")
        }
        sb.append("</div>")

        if (err.isNotEmpty()) sb.append("<div class=\"bar bad\">${esc(err)}</div>")
        if (done.isNotEmpty()) sb.append("<div class=\"bar ok\">${esc(done)}</div>")

        if (target != null) {
            sb.append("<h3>上传文件</h3>")
            sb.append(
                "<form id=upform method=post action=\"/up?p=${urlEnc(p)}\" " +
                    "enctype=\"multipart/form-data\" class=up>"
            )
            sb.append("<div class=urow><label>上传到</label><b>${esc(target.label)}</b></div>")
            sb.append(dirSelect(ctx, p))
            sb.append("<div class=urow><input id=upfile type=file name=f multiple> ")
            sb.append("<button id=upbtn type=submit>上传</button></div>")
            sb.append("<div id=upq class=q hidden></div>")
            sb.append("<div id=upprog class=prog hidden><div class=track><i id=upfill></i></div>")
            sb.append("<div class=\"mut\" id=uptext></div></div>")
            sb.append(uploadScript(p, target.label))
            sb.append("</form>")
            sb.append(
                "<p class=mut>传进来的东西落在<b>当前这一层</b>（浏览到哪一层就传到哪一层）。" +
                    "目标目录里已经有同名文件的会<b>拒绝上传</b>——不覆盖、也不自动改名，" +
                    "选完文件会先标出来哪些要跳过，传完还会再列一遍。</p>"
            )
            if (target.public) {
                sb.append("<p class=mut>Download/ 里其他应用也看得到（播放器、孩子的教育应用都能直接选到）。</p>")
            }
        } else {
            sb.append("<div class=\"bar mut\">先在上面选一个位置（Download/ 或 files/），再往里传文件。</div>")
        }

        sb.append("<h3>这一层</h3><table>")
        if (entries.isEmpty()) {
            val why = if (p == LOC_DL) "（这一层还没有本应用放过东西）" else "（这一层是空的）"
            sb.append("<tr><td class=mut>$why</td></tr>")
        }
        for (e in entries) {
            sb.append("<tr><td>")
            if (e.dir) sb.append("<a href=\"/?p=${urlEnc(e.path)}\">📁 ${esc(e.name)}</a>")
            else sb.append("<a href=\"/get?p=${urlEnc(e.path)}\">${esc(e.name)}</a>")
            sb.append("</td><td class=mut>${if (e.dir) "目录" else size(e.size)}</td>")
            sb.append("<td class=mut>${if (e.dir) "" else stamp(e.mtime)}</td></tr>")
        }
        sb.append("</table>")

        sb.append(foot())
        page(out, html(sb.toString()))
    }

    /** 「快捷切换」下拉框：整个路径空间里能进的目录，选一下就跳过去 */
    private fun dirSelect(ctx: Context, p: String): String {
        val sb = StringBuilder("<div class=urow><label for=updir>快捷切换</label>")
        sb.append("<select id=updir name=dir><option value=\"\">（下拉跳到别的目录）</option>")
        for ((path, label) in dirOptions(ctx)) {
            if (path == p) continue
            sb.append("<option value=\"${esc(path)}\">${esc(label)}</option>")
        }
        sb.append("</select></div>")
        return sb.toString()
    }

    private fun dirOptions(ctx: Context): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val locs = ArrayList<Pair<String, String>>()
        if (PublicDownloads.available()) locs.add(LOC_DL to "Download/")
        locs.add(LOC_FILES to "files/")
        for ((loc, label) in locs) {
            out.add(loc to "$label（本层）")
            val subs = ArrayList<String>()
            val dl = loc == LOC_DL
            fillDirs(ctx, dl, if (dl) "" else loc, "", 1, subs)
            for (s in subs) out.add("$loc/$s" to "$label$s/")
        }
        return out.take(MAX_TARGET_DIRS)
    }

    /** 某个位置下所有子目录（相对路径），最多挖 [MAX_TARGET_DEPTH] 层、总数封顶 */
    private fun fillDirs(
        ctx: Context,
        dl: Boolean,
        loc: String,
        rel: String,
        depth: Int,
        out: MutableList<String>,
    ) {
        if (depth > MAX_TARGET_DEPTH || out.size >= MAX_TARGET_DIRS) return
        if (dl) {
            for (e in PublicDownloads.listAt(ctx, rel)) {
                if (!e.dir) continue
                val r = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                out.add(r)
                fillDirs(ctx, true, loc, r, depth + 1, out)
            }
            return
        }
        val dir = resolve(Store.filesRoot(ctx), rel) ?: return
        for (k in dir.listFiles()?.sortedBy { it.name.lowercase() } ?: return) {
            if (!k.isDirectory || k.name.startsWith(".")) continue
            val r = if (rel.isEmpty()) k.name else "$rel/${k.name}"
            out.add(r)
            fillDirs(ctx, false, loc, r, depth + 1, out)
        }
    }

    private fun download(ctx: Context, req: Req, out: OutputStream, p: String, ip: String) {
        val path = normalizePath(p)
        if (path == LOC_DL || path.startsWith("$LOC_DL/")) {
            val rel = dlRel(path)
            downloadPublic(ctx, req, out, rel.substringBeforeLast('/', ""), rel.substringAfterLast('/'), ip)
            return
        }
        if (path.startsWith("$LOC_FILES/")) {
            downloadPrivate(ctx, req, out, filesRel(path), ip)
            return
        }
        page(out, 404, "文件不存在")
    }

    private fun downloadPrivate(ctx: Context, req: Req, out: OutputStream, rel: String, ip: String) {
        val root = Store.filesRoot(ctx)
        val f = resolve(root, rel)
        if (f == null || !f.isFile) {
            page(out, 404, "文件不存在")
            return
        }
        val total = f.length()
        val name = f.name
        val disp = "attachment; filename*=UTF-8''${urlEnc(name)}"

        var start = 0L
        var end = total - 1
        var partial = false
        val range = req.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash).trim()
                val b = spec.substring(dash + 1).trim()
                try {
                    if (a.isEmpty()) {
                        // bytes=-N：最后 N 个字节
                        val n = b.toLong()
                        start = (total - n).coerceAtLeast(0L)
                    } else {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = b.toLong().coerceAtMost(total - 1)
                    }
                    partial = true
                } catch (_: Exception) {
                    partial = false
                }
            }
        }
        if (partial && (start > end || start >= total)) {
            head(out, 416, "text/plain; charset=utf-8")
            body(out, "bytes=$start-$end/$total".toByteArray())
            return
        }
        val len = end - start + 1
        val status = if (partial) "206 Partial Content" else "200 OK"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: ${mimeOf(name)}\r\n")
        sb.append("Content-Length: $len\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Disposition: $disp\r\n")
        if (partial) sb.append("Content-Range: bytes $start-$end/$total\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (req.method == "HEAD") {
            out.flush()
            return
        }
        // 记在这一步而不是传完之后：客户端中途断线会抛异常，那样就只在日志里留个错、
        // 审计里干干净净，看着像「没人取过这个文件」
        Audit.record(Audit.FILE, rel, "把私有目录里的文件交给 $ip 下载（${size(len)}）")
        FileInputStream(f).use { fis ->
            fis.skip(start)
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    // ---------- 上传 ----------

    private fun upload(ctx: Context, req: Req, ins: BackReader, out: OutputStream, ip: String) {
        val q = parseQuery(req.target.substringAfter('?', ""))
        var targetVal = normalizePath(q["p"].orEmpty())
        val back = targetVal
        val ct = req.headers["content-type"].orEmpty()
        val bi = ct.indexOf("boundary=")
        if (bi < 0) {
            page(out, 400, "上传请求里没有 boundary")
            return
        }
        var boundary = ct.substring(bi + "boundary=".length).trim()
        if (boundary.length >= 2 && boundary.startsWith("\"") && boundary.endsWith("\"")) {
            boundary = boundary.substring(1, boundary.length - 1)
        }
        val delim = "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1)
        val firstLine = readLine(ins)
        if (firstLine == null || firstLine.trim() != "--$boundary") {
            page(out, 400, "上传格式不对")
            return
        }
        var target = resolveTarget(ctx, targetVal) ?: run {
            page(out, 400, "上传目标目录不存在")
            return
        }

        /** 落了盘的：名字 → 落点说明 */
        val saved = ArrayList<Pair<String, String>>()
        /** 因重名没传的：名字 → 原因（浏览器预检剔掉的 + 服务端拒掉的，都在这里） */
        val skipped = ArrayList<Pair<String, String>>()
        while (true) {
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(ins) ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val disp = headers["content-disposition"].orEmpty()
            val rawName = filenameOf(disp)
            if (rawName == null) {
                // 普通表单字段，没有正文文件。dir= 是没开 JS 时下拉框跟着表单提交上来的目标目录；
                // skipped= 是浏览器预检剔掉的重名清单（它比服务端先知道，别让家长白等一遍重传）
                val field = fieldNameOf(disp)
                val v = readField(ins, delim)
                if (field == "dir" && v.isNotEmpty()) {
                    val t2 = resolveTarget(ctx, normalizePath(v))
                    // 文件段后面才到的 dir 只能作废：前面的已经按老目标写下去了
                    if (t2 != null && saved.isEmpty() && skipped.isEmpty()) {
                        targetVal = normalizePath(v)
                        target = t2
                    }
                } else if (field == "skipped") {
                    parseSkipped(v, skipped)
                }
            } else {
                val name = safeName(rawName)
                var r: Int
                var place: String
                if (target.public) {
                    place = target.label
                    r = saveToPublic(ctx, ins, delim, target.rel, name)
                    if (r == UP_FALLBACK) {
                        // Android 9 及以下没有 Downloads 集合，或 MediaStore 插入失败：退回私有 uploads/
                        place = "应用私有目录 uploads/（别的应用看不到）"
                        r = saveToPrivate(ins, delim, File(Store.filesRoot(ctx), "uploads"), name)
                    }
                } else {
                    place = target.label
                    r = saveToPrivate(ins, delim, target.dir!!, name)
                }
                when (r) {
                    UP_OK -> {
                        saved.add(name to place)
                        Diag.log("http", "收到文件 $name → $place")
                        Audit.record(Audit.FILE, name, "$ip 上传的文件落进 $place")
                    }

                    UP_DUP -> {
                        skipped.add(name to "$place 里已有同名文件")
                        Diag.log("http", "$name 与 $place 里的文件重名，拒绝上传（不覆盖）")
                        Audit.record(
                            Audit.FILE, name,
                            "$ip 上传的 $name 在 $place 里已有同名文件，按重名拒绝，没有覆盖",
                        )
                    }

                    else -> Audit.record(Audit.FILE, name, "$ip 上传的 $name 写盘失败，没传成")
                }
            }
            val after = readLine(ins) ?: break
            if (after.trim().startsWith("--")) break
        }

        if (saved.isEmpty() && skipped.isEmpty()) {
            page(out, 400, "没有收到文件")
            return
        }
        val sb = StringBuilder()
        sb.append(docHead("上传完成"))
        sb.append("<h2>上传完成</h2><div class=card>")
        if (saved.isNotEmpty()) {
            sb.append("<p class=ok>已上传 ${saved.size} 个：</p><ul>")
            for ((n, where) in saved) sb.append("<li>${esc(n)} <span class=mut>→ ${esc(where)}</span></li>")
            sb.append("</ul>")
            if (target.public) {
                sb.append("<p class=mut>这个目录其他应用也看得到：播放器、孩子的教育应用都能直接选到。</p>")
            }
        }
        if (skipped.isNotEmpty()) {
            sb.append("<p class=bad>因重名未上传 ${skipped.size} 个：</p><ul>")
            for ((n, why) in skipped) sb.append("<li>${esc(n)} <span class=mut>（${esc(why)}）</span></li>")
            sb.append("</ul>")
            sb.append(
                "<p class=mut>服务器上没有覆盖、也没有自动改名。" +
                    "要传这一份，先在自己电脑上把文件名改掉；要换成新的，先在文件列表里把旧的删掉再传。</p>"
            )
        }
        sb.append("<p><a class=btn href=\"/?p=${urlEnc(back)}\">返回文件列表</a></p>")
        sb.append("</div>")
        sb.append(foot())
        page(out, html(sb.toString()))
    }

    /**
     * 在当前目录下新建子目录：一个普通表单 POST（字段 name=，建在哪层在 ?p= 里）。
     * 建完 303 回列表页，结果挂在 done= / err= 上，刷新一下也还在。
     */
    private fun mkdir(ctx: Context, req: Req, ins: BackReader, out: OutputStream, ip: String) {
        val p = normalizePath(parseQuery(req.target.substringAfter('?', ""))["p"].orEmpty())
        val raw = readBodyField(ins, req, "name").trim()
        val back = "/?p=${urlEnc(p)}"
        fun fail(msg: String) {
            redirect(out, "$back&err=${urlEnc(msg)}")
        }
        if (raw.isEmpty()) return fail("目录名不能空着")
        if (raw == "." || raw == "..") return fail("这个目录名不能用")
        val name = safeName(raw)
        val target = resolveTarget(ctx, p) ?: return fail("这个位置建不了目录")
        if (target.public) {
            if (!PublicDownloads.mkdir(ctx, target.rel, name)) {
                return fail("在 Download/ 里建目录没成功——这台设备的系统不认这么建，改传到 files/ 那边试试")
            }
        } else {
            val d = File(target.dir, name)
            if (d.exists()) return fail("「$name」已经在了")
            if (!d.mkdir()) return fail("建不了「$name」：设备不允许写这里")
        }
        Diag.log("http", "新建目录 $name → ${target.label}")
        Audit.record(Audit.FILE, name, "$ip 在 ${target.label} 下新建了子目录")
        redirect(out, "$back&done=${urlEnc("已新建子目录「$name」")}")
    }

    /** 读一个 x-www-form-urlencoded 请求体里的字段（限长，超了当没给） */
    private fun readBodyField(ins: BackReader, req: Req, key: String): String {
        val n = req.headers["content-length"]?.trim()?.toIntOrNull() ?: return ""
        if (n <= 0 || n > 64 * 1024) return ""
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val tmp = ByteArray(n - got)
            val r = try {
                ins.read(tmp)
            } catch (_: Exception) {
                -1
            }
            if (r <= 0) break
            System.arraycopy(tmp, 0, buf, got, r)
            got += r
        }
        return parseQuery(String(buf, 0, got, Charsets.UTF_8))[key].orEmpty()
    }

    /** 公共下载目录落盘：重名或 MediaStore 走不通分别返回 [UP_DUP] / [UP_FALLBACK] */
    private fun saveToPublic(ctx: Context, ins: BackReader, delim: ByteArray, rel: String, name: String): Int {
        if (PublicDownloads.has(ctx, rel, name)) {
            drainPart(ins, delim)
            return UP_DUP
        }
        val key = "pub:${if (rel.isEmpty()) "" else "$rel/"}$name"
        if (!inflight.add(key)) {
            drainPart(ins, delim)
            return UP_DUP
        }
        try {
            val pub = PublicDownloads.begin(ctx, rel, name, mimeOf(name)) ?: return UP_FALLBACK
            val stream = PublicDownloads.out(ctx, pub)
            val ok = try {
                // 必须套一层缓冲：copyUntil 找分隔符是按字节滑窗的，写出去也是按字节——
                // 直接怼输出流的话，传一个几十上百 MB 的视频就是几千万次 write 系统调用
                if (stream == null) false
                else stream.use { BufferedOutputStream(it, 64 * 1024).use { o -> copyUntil(ins, o, delim) } }
            } catch (e: Exception) {
                Diag.log("http", "上传写盘失败：${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (ok) {
                PublicDownloads.finish(ctx, pub)
                return UP_OK
            }
            PublicDownloads.abort(ctx, pub)
            return UP_FAIL
        } finally {
            inflight.remove(key)
        }
    }

    /**
     * 私有目录落盘：重名拒绝，先写 .part 再改名（列表页不给看 .part，家长不会下到半截文件）。
     * 无论走哪条分支，正文都会被读干净，否则后面几段的头会被当成文件内容。
     */
    private fun saveToPrivate(ins: BackReader, delim: ByteArray, dir: File, name: String): Int {
        val dest = File(dir, name)
        val key = dest.absolutePath
        // 先看磁盘上有没有，再看有没有别的连接正在写同一个名字——两边都查才不会互相覆盖
        if (dest.exists() || !inflight.add(key)) {
            drainPart(ins, delim)
            return UP_DUP
        }
        try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                drainPart(ins, delim)
                return UP_FAIL
            }
            val tmp = File(dir, ".upload-${System.nanoTime()}.part")
            val ok = try {
                FileOutputStream(tmp).use { fos ->
                    BufferedOutputStream(fos, 64 * 1024).use { copyUntil(ins, it, delim) }
                }
            } catch (e: Exception) {
                Diag.log("http", "上传写盘失败：${e.javaClass.simpleName}: ${e.message}")
                false
            }
            if (!ok || tmp.length() == 0L) {
                tmp.delete()
                return UP_FAIL
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return UP_FAIL
            }
            return UP_OK
        } finally {
            inflight.remove(key)
        }
    }

    /** 预检接口：目标目录里已有的名字，浏览器选完文件先拿它标出重名的 */
    private fun listNames(ctx: Context, out: OutputStream, pArg: String) {
        val target = resolveTarget(ctx, normalizePath(pArg))
        if (target == null) {
            json(out, 404, JSONObject().put("ok", false).put("error", "目标目录不存在"))
            return
        }
        val names = if (target.public) {
            PublicDownloads.names(ctx, target.rel)
        } else {
            target.dir?.listFiles()?.filter { !it.name.startsWith(".") }?.map { it.name } ?: emptyList()
        }
        json(
            out, 200,
            JSONObject().put("ok", true).put("label", target.label).put("names", JSONArray(names)),
        )
    }

    /** 浏览器预检剔掉的那批：JSON 数组 [{"n":名字,"r":原因}] */
    private fun parseSkipped(v: String, into: MutableList<Pair<String, String>>) {
        try {
            val arr = JSONArray(v)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("n")
                if (n.isNotEmpty()) into.add(n to o.optString("r").ifEmpty { "目标目录里已有同名文件" })
            }
        } catch (_: Exception) {
        }
    }

    /** 上传落点：`dl/…` 是公共下载目录下的目录，`files/…` 是私有目录下的目录 */
    private class Target(val public: Boolean, val dir: File?, val rel: String, val label: String)

    private fun resolveTarget(ctx: Context, p: String): Target? {
        if (p == LOC_DL || p.startsWith("$LOC_DL/")) {
            // Android 9 及以下没有 Downloads 集合，这个位置整个用不了
            if (!PublicDownloads.available()) return null
            val rel = dlRel(p)
            val label = if (rel.isEmpty()) "公共下载目录 Download/" else "公共下载目录 Download/$rel/"
            return Target(true, null, rel, label)
        }
        if (p != LOC_FILES && !p.startsWith("$LOC_FILES/")) return null
        val root = Store.filesRoot(ctx)
        val rel = filesRel(p)
        val d = resolve(root, rel) ?: return null
        if (!d.isDirectory) return null
        val label = if (rel.isEmpty()) "应用私有目录 files/" else "应用私有目录 files/$rel/"
        return Target(false, d, rel, label)
    }

    /**
     * Content-Disposition 里的表单字段名（没有名字的段返回空串）。
     * 得跳过 filename=（它自带一个 name=），否则文件段会被当成 dir/skipped 字段。
     */
    private fun fieldNameOf(cd: String): String {
        var from = 0
        while (true) {
            val i = cd.indexOf("name=", from)
            if (i < 0) return ""
            val isFilename = i >= 4 && cd.startsWith("filename=", i - 4)
            if (!isFilename) return cd.substring(i + "name=".length).trim().substringBefore(';').trim().trim('"')
            from = i + "name=".length
        }
    }

    /** 读一个普通表单字段的值（限长，超长部分照样读到分隔符为止、只是不留） */
    private fun readField(ins: BackReader, delim: ByteArray, cap: Int = 256 * 1024): String {
        val buf = ByteArrayOutputStream()
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                if (buf.size() < cap) buf.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (buf.size() < cap) buf.write(b, off, minOf(len, cap - buf.size()))
            }
        }
        copyUntil(ins, sink, delim)
        return buf.toString("UTF-8")
    }

    /** 重名的段落不写盘，但正文必须从连接里读干净，不然下一段的头会被当成文件内容 */
    private fun drainPart(ins: BackReader, delim: ByteArray) {
        copyUntil(ins, nullSink, delim)
    }

    /** 下载公共下载目录里本应用上传上去的文件（rel 是 Download 下的目录），Range 分片照旧支持 */
    private fun downloadPublic(
        ctx: Context,
        req: Req,
        out: OutputStream,
        rel: String,
        name: String,
        ip: String,
    ) {
        val row = PublicDownloads.listAt(ctx, rel).firstOrNull { it.name == name && !it.dir }
        val stream = PublicDownloads.open(ctx, rel, name)
        if (row == null || stream == null) {
            page(out, 404, "文件不存在")
            return
        }
        val total = row.size
        var start = 0L
        var end = total - 1
        var partial = false
        val range = req.headers["range"]
        if (range != null && range.startsWith("bytes=")) {
            val spec = range.removePrefix("bytes=").substringBefore(',')
            val dash = spec.indexOf('-')
            if (dash >= 0) {
                val a = spec.substring(0, dash).trim()
                val b = spec.substring(dash + 1).trim()
                try {
                    if (a.isEmpty()) {
                        start = (total - b.toLong()).coerceAtLeast(0L)
                    } else {
                        start = a.toLong()
                        if (b.isNotEmpty()) end = b.toLong().coerceAtMost(total - 1)
                    }
                    partial = true
                } catch (_: Exception) {
                    partial = false
                }
            }
        }
        if (partial && (start > end || start >= total)) {
            stream.close()
            head(out, 416, "text/plain; charset=utf-8")
            body(out, "bytes=$start-$end/$total".toByteArray())
            return
        }
        val len = end - start + 1
        val status = if (partial) "206 Partial Content" else "200 OK"
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: ${mimeOf(name)}\r\n")
        sb.append("Content-Length: $len\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Content-Disposition: attachment; filename*=UTF-8''${urlEnc(name)}\r\n")
        if (partial) sb.append("Content-Range: bytes $start-$end/$total\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (req.method == "HEAD") {
            stream.close()
            out.flush()
            return
        }
        Audit.record(Audit.FILE, name, "把 Download/ 里的文件交给 $ip 下载（${size(len)}）")
        stream.use { s ->
            var left2skip = start
            while (left2skip > 0) {
                val n = s.skip(left2skip)
                if (n <= 0) break
                left2skip -= n
            }
            val buf = ByteArray(64 * 1024)
            var left = len
            while (left > 0) {
                val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    // ---------- HTTP 小工具 ----------

    private class Req(val method: String, val target: String, val headers: Map<String, String>)

    private fun readRequest(ins: BackReader): Req? {
        val line = readLine(ins) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(ins) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        return Req(parts[0].uppercase(), parts[1], headers)
    }

    /** HTTP 头一律按逐字节读，绝不用 Reader——后面还跟着二进制正文 */
    private fun readLine(br: BackReader): String? {
        val sb = StringBuilder()
        while (true) {
            val b = br.readByte()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    /**
     * 把 [br] 里的字节写进 [out]，直到碰上分隔符 [delim]（分隔符本身不写入）。
     * 返回 false 表示流先结束了（多半是客户端断开）。
     *
     * 一次读一大块、窗口滑动着找分隔符，别一个字节一次 I/O；找到之后**把多读的尾巴推回 [br]**，
     * 否则后面那几段的头就被吞掉了——浏览器一次选多个文件上传时，只能落盘第一个。
     */
    private fun copyUntil(br: BackReader, out: OutputStream, delim: ByteArray): Boolean {
        val win = ByteArray(delim.size)
        var filled = 0
        val chunk = ByteArray(32 * 1024)
        while (true) {
            val n = br.read(chunk)
            if (n < 0) return false
            var i = 0
            while (i < n) {
                if (filled < win.size) {
                    win[filled++] = chunk[i++]
                    if (filled == win.size && matches(win, delim)) {
                        br.unread(chunk, i, n)
                        return true
                    }
                } else {
                    out.write(win[0].toInt())
                    System.arraycopy(win, 1, win, 0, win.size - 1)
                    win[win.size - 1] = chunk[i++]
                    if (matches(win, delim)) {
                        br.unread(chunk, i, n)
                        return true
                    }
                }
            }
        }
    }

    /**
     * 带一小段「回推」的输入流包装。[copyUntil] 为了快会一次读 32KB，
     * 匹配到分隔符时这一块里往往还留着后面几段的字节，得原样还回去。
     */
    private class BackReader(private val ins: InputStream) {
        private var back: ByteArray = EMPTY
        private var pos = 0
        private val one = ByteArray(1)

        /** 先把还回去的字节吐完，再读底层流（可能短读，调用方自己接着读） */
        fun read(buf: ByteArray): Int {
            if (pos < back.size) {
                val n = minOf(buf.size, back.size - pos)
                System.arraycopy(back, pos, buf, 0, n)
                pos += n
                if (pos >= back.size) {
                    back = EMPTY
                    pos = 0
                }
                return n
            }
            return ins.read(buf)
        }

        fun readByte(): Int {
            val n = read(one)
            return if (n < 0) -1 else one[0].toInt() and 0xFF
        }

        /** 把 [src] 里 [from, to) 这一段留到下一次读；调用前 back 一定是空的（要么已吐完，要么刚被整块读走） */
        fun unread(src: ByteArray, from: Int, to: Int) {
            back = if (to > from) src.copyOfRange(from, to) else EMPTY
            pos = 0
        }

        private companion object {
            val EMPTY = ByteArray(0)
        }
    }

    private fun matches(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] != b[i]) return false
        return true
    }

    /**
     * Content-Disposition 里的 filename。先看 RFC 2231 的 filename*=（值带百分号编码，能直接 UTF-8 解），
     * 没有就取普通的 filename=。两条路最后都要过一遍 [bytesToUtf8]。
     */
    private fun filenameOf(cd: String): String? {
        val star = cd.indexOf("filename*=")
        if (star >= 0) {
            var v = cd.substring(star + "filename*=".length).trim().substringBefore(';').trim()
            if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
            val idx = v.indexOf("''")
            if (idx >= 0) v = v.substring(idx + 2)
            val decoded = try {
                URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
                null
            }
            if (decoded != null) return bytesToUtf8(decoded).ifBlank { null }
        }
        val plain = cd.indexOf("filename=")
        if (plain < 0) return null
        var v = cd.substring(plain + "filename=".length).trim().substringBefore(';').trim()
        if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
        return bytesToUtf8(v).ifBlank { null }
    }

    /**
     * 把「按字节读成字符」的字符串还原回真正的文字。
     *
     * HTTP 头是逐字节读进来的（后面紧跟二进制正文，绝不能用 Reader），于是每个字节都落成
     * 一个 U+0000~U+00FF 的字符——UTF-8 的「中文.mp4」变成「ä¸æ–‡.mp4」。
     * 浏览器在 multipart 头里写非 ASCII 文件名用的正是原始 UTF-8 字节，所以这里按 ISO-8859-1
     * 还原成字节、再按 UTF-8 解一次。解不出来（名字本来就是 Latin-1，或者已经被
     * filename*= 正确解过一遍）就原样返回，绝不把好名字改成「???」。
     *
     * owner 2026-09-15 反馈：上传中文名文件，落到 Download/ 里名字是乱码。
     */
    private fun bytesToUtf8(s: String): String {
        if (s.all { it.code < 0x80 }) return s
        val bytes = ByteArray(s.length)
        for (i in s.indices) {
            val c = s[i].code
            if (c > 0xFF) return s // 已经是正常文字（码点超过单字节范围），不用再解
            bytes[i] = c.toByte()
        }
        return try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            dec.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            s
        }
    }

    /** 只取文件名本身，去掉路径和换行——上传上来的名字是不可信输入 */
    private fun safeName(name: String): String {
        var v = name.replace('\\', '/').substringAfterLast('/')
        // 控制字符（\r、\n、\0 之类）一律去掉——上传上来的名字是不可信输入
        v = v.replace("\r", "").replace("\n", "").trim()
        if (v.isEmpty() || v == "." || v == "..") v = "file"
        if (v.length > 120) {
            val dot = v.lastIndexOf('.')
            v = if (dot > 0) v.substring(0, 100) + v.substring(dot) else v.substring(0, 120)
        }
        return v
    }

    /** 把相对路径解析到根目录下；越界（../）或不存在都返回 null */
    private fun resolve(root: File, rel: String): File? {
        val clean = rel.replace('\\', '/').trimStart('/')
        val f = if (clean.isEmpty()) root else File(root, clean)
        return try {
            val rc = root.canonicalFile
            val fc = f.canonicalFile
            if (fc == rc || fc.path.startsWith(rc.path + File.separator)) fc else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseQuery(s: String): Map<String, String> {
        if (s.isEmpty()) return emptyMap()
        val m = HashMap<String, String>()
        for (kv in s.split('&')) {
            if (kv.isEmpty()) continue
            val i = kv.indexOf('=')
            val k = if (i < 0) kv else kv.substring(0, i)
            val v = if (i < 0) "" else kv.substring(i + 1)
            m[urlDec(k)] = urlDec(v)
        }
        return m
    }

    private fun urlDec(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun urlEnc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        s
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun size(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> "%.1f KB".format(n / 1024.0)
        n < 1024L * 1024 * 1024 -> "%.1f MB".format(n / 1024.0 / 1024)
        else -> "%.2f GB".format(n / 1024.0 / 1024 / 1024)
    }

    private fun stamp(t: Long): String =
        SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(t))

    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log" -> "text/plain; charset=utf-8"
        "html", "htm" -> "text/html; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "mkv" -> "video/x-matroska"
        "mp3" -> "audio/mpeg"
        "pdf" -> "application/pdf"
        "apk" -> "application/vnd.android.package-archive"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private fun lanIps(): List<String> {
        val pairs = ArrayList<Pair<String, String>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces() ?: return emptyList()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { pairs.add(nif.name to it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        // wlan 排前面：家长多半是连着 Wi-Fi 从电脑上连过来
        return pairs.sortedBy { if (it.first.startsWith("wlan")) 0 else 1 }.map { it.second }
    }

    // ---------- 页面 ----------

    private fun html(bodyText: String): ByteArray = bodyText.toByteArray(Charsets.UTF_8)

    /** 200 + 整页 HTML */
    private fun page(out: OutputStream, bodyBytes: ByteArray) {
        head(out, 200, "text/html; charset=utf-8")
        body(out, bodyBytes)
    }

    /** 出错的简单一页 */
    private fun page(out: OutputStream, code: Int, msg: String) {
        val b = html(
            "<html><head><meta charset=utf-8></head>" +
                "<body style=\"font-family:sans-serif;padding:24px\">$msg</body></html>"
        )
        head(out, code, "text/html; charset=utf-8")
        body(out, b)
    }

    /** 303 回另一页：表单提交完用，刷新不会重复提交 */
    private fun redirect(out: OutputStream, to: String) {
        head(out, 303, "text/plain; charset=utf-8", listOf("Location" to to))
        body(out, ByteArray(0))
    }

    private fun head(out: OutputStream, code: Int, type: String, extra: List<Pair<String, String>> = emptyList()) {
        val reason = when (code) {
            200 -> "OK"; 303 -> "See Other"; 400 -> "Bad Request"; 404 -> "Not Found"
            416 -> "Range Not Satisfiable"; 500 -> "Internal Server Error"
            else -> "OK"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Content-Type: $type\r\n")
        for ((k, v) in extra) sb.append("$k: $v\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
    }

    private fun body(out: OutputStream, b: ByteArray) {
        out.write(b)
        out.flush()
    }

    /** 给页面里的 fetch 用的 JSON 响应 */
    private fun json(out: OutputStream, code: Int, obj: JSONObject) {
        val b = obj.toString().toByteArray(Charsets.UTF_8)
        head(out, code, "application/json; charset=utf-8", listOf("Content-Length" to b.size.toString()))
        body(out, b)
    }

    private fun css(): String = """
        <style>
        body{font-family:-apple-system,'Segoe UI',Roboto,'Noto Sans SC',sans-serif;margin:0;padding:24px;background:#f5f7fb;color:#1b2431}
        h2{margin:0 0 4px}h3{margin:22px 0 8px;font-size:15px;color:#3b5bdb}
        .mut{color:#7b8794;font-size:13px}.ok{color:#2f9e44;font-weight:bold}
        .bar{background:#fff;border-radius:12px;padding:12px 16px;margin:12px 0;box-shadow:0 1px 3px rgba(0,0,0,.08)}
        .card{background:#fff;border-radius:12px;padding:20px;box-shadow:0 1px 3px rgba(0,0,0,.08);max-width:720px}
        table{width:100%;border-collapse:collapse;background:#fff;border-radius:12px;overflow:hidden;max-width:900px}
        td{padding:10px 14px;border-bottom:1px solid #eef1f6;font-size:14px}
        a{color:#2b6cff;text-decoration:none}a:hover{text-decoration:underline}
        .btn{background:#eef3ff;border-radius:8px;padding:6px 12px;font-size:13px;display:inline-block}
        .up{background:#fff;border-radius:12px;padding:16px;max-width:900px}
        .bad{color:#e03131;font-weight:bold}
        .urow{display:flex;align-items:center;gap:8px;flex-wrap:wrap;margin-bottom:12px}
        select{font-size:14px;padding:8px 10px;border:1px solid #cbd5e1;border-radius:8px;background:#fff;max-width:100%}
        label{color:#4b5563;font-size:14px}
        button{background:#2b6cff;color:#fff;border:0;border-radius:8px;padding:9px 18px;font-size:14px;cursor:pointer}
        button:disabled{background:#9db4e8;cursor:default}
        input[type=file]{font-size:14px;margin-right:8px}
        .q[hidden]{display:none}
        .q{margin:4px 0 12px;border:1px solid #e6ecf7;border-radius:10px;overflow:hidden;max-width:640px}
        .q .qrow{display:flex;gap:10px;align-items:center;padding:7px 12px;border-bottom:1px solid #f2f5fa;font-size:13px}
        .q .qrow:last-child{border-bottom:0}
        .q .qname{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
        .q .qstat{font-size:12px;color:#2f9e44}
        .q .qstat.bad{color:#e03131}
        .q .qfoot{padding:7px 12px;background:#fff5f5;color:#e03131;font-size:12px}
        .prog[hidden]{display:none}
        .prog{margin-top:14px;max-width:520px}
        .track{height:8px;background:#e6ecf7;border-radius:99px;overflow:hidden}
        .track>i{display:block;height:100%;width:0;background:#2b6cff;border-radius:99px;transition:width .2s}
        .prog.bad .track>i{background:#e03131}
        .prog.bad .mut{color:#e03131}
        </style>
    """

    private fun docHead(title: String): String =
        "<html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title>${css()}</head><body>"

    private fun foot(): String = "</body></html>"

    /**
     * 上传进度条。
     *
     * owner 2026-09-15 反馈：上传文件看不见进展，一个大视频传上去不知道是在走还是卡住了。
     * 进度只有浏览器知道（服务端收完才回页面），所以把表单提交接管成 XHR，用 upload.onprogress
     * 拿已发字节数画进度条。传完（100%）到服务端回页之间还有一段写盘时间，单独标出来，
     * 免得家长以为卡在 100% 不动了。
     *
     * 没有 XHR/FormData 的老浏览器直接 return，退回原生表单提交——只是没有进度可看，功能照旧。
     */
    private fun uploadScript(p: String, where: String): String = """
<script>
(function(){
  var form=document.getElementById('upform');
  if(!form) return;
  var file=document.getElementById('upfile'), btn=document.getElementById('upbtn'), sel=document.getElementById('updir');
  var box=document.getElementById('upprog'), fill=document.getElementById('upfill'), txt=document.getElementById('uptext');
  var q=document.getElementById('upq');
  var P=${JSONObject.quote(p)}, WHERE=${JSONObject.quote(where)};
  var canXhr=!!(window.XMLHttpRequest && window.FormData);
  var dups={};
  function size(n){
    if(n<1024) return n+' B';
    if(n<1048576) return (n/1024).toFixed(1)+' KB';
    if(n<1073741824) return (n/1048576).toFixed(1)+' MB';
    return (n/1073741824).toFixed(2)+' GB';
  }
  function esc(s){
    return String(s).replace(/[&<>"']/g, function(c){
      return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
    });
  }
  function fail(msg){
    box.className='prog bad';
    txt.textContent=msg;
    btn.disabled=false; file.disabled=false;
  }
  // 快捷切换：选一个目录就整页跳过去，浏览到哪一层就传到哪一层。
  // 没开 JS 的浏览器下拉框会跟着表单提交（字段名 dir），服务端也认。
  if(sel){
    sel.onchange=function(){
      if(!sel.value) return;
      location.href='/?p='+encodeURIComponent(sel.value);
    };
  }
  // 选完文件先问一遍目标目录里已有哪些名字，重名的当场标出来、不往上传队列里放，
  // 免得家长传一个几百 MB 的视频等半天才被告知重名。
  function render(names, existing){
    var seen={}, i, dupN=0, rows='';
    for(i=0;i<names.length;i++){
      var nm=names[i], why='';
      if(seen[nm]) why='本次选择里有同名文件';
      else if(existing.indexOf(nm)>=0) why='目标目录里已有同名文件';
      seen[nm]=1;
      if(why){
        dupN++; dups[nm]=why;
        rows+='<div class=qrow><span class=qname title="'+esc(nm)+'">'+esc(nm)+'</span><span class="qstat bad">重名，跳过</span></div>';
      }else{
        delete dups[nm];
        rows+='<div class=qrow><span class=qname title="'+esc(nm)+'">'+esc(nm)+'</span><span class=qstat>可上传</span></div>';
      }
    }
    q.innerHTML = rows + (dupN ? '<div class=qfoot>'+dupN+' 个文件在 '+esc(WHERE)+' 里已有同名，不会被上传；要传请先改名</div>' : '');
    q.hidden=false;
  }
  function precheck(){
    if(!file.files || !file.files.length){ q.hidden=true; dups={}; return; }
    if(!window.fetch) return;
    var names=[], i;
    for(i=0;i<file.files.length;i++) names.push(file.files[i].name);
    fetch('/ls?p='+encodeURIComponent(P)).then(function(r){ return r.json(); }).then(function(j){
      render(names, (j && j.names) || []);
    }, function(){ render(names, []); });
  }
  file.addEventListener('change', precheck);
  form.addEventListener('submit', function(ev){
    if(!file.files || !file.files.length) return; // 没选文件就交给服务端回「没有收到文件」
    if(!canXhr) return;                           // 老浏览器退回原生表单提交：没有进度条，服务端照样查重名
    ev.preventDefault();
    var i, total=0, fd=new FormData(), skipped=[], names=[];
    for(i=0;i<file.files.length;i++){
      var f=file.files[i];
      if(dups[f.name]){ skipped.push({n:f.name, r:dups[f.name]}); names.push(f.name); }
      else { fd.append('f', f); total+=f.size; }
    }
    fd.append('skipped', JSON.stringify(skipped));
    if(total===0){
      fail('选中的 '+names.length+' 个文件在 '+WHERE+' 里都已有同名，一个都没传：'+names.join('、'));
      return;
    }
    var xhr=new XMLHttpRequest();
    xhr.open('POST', form.getAttribute('action'), true);
    var last=Date.now(), lastBytes=0;
    box.hidden=false; box.className='prog'; fill.style.width='0%';
    txt.textContent='正在上传 0%  ·  0 B / '+size(total);
    btn.disabled=true; btn.textContent='上传中…'; file.disabled=true;
    xhr.upload.onprogress=function(e){
      var tot=e.lengthComputable?e.total:total;
      var pct=tot>0?Math.round(e.loaded*100/tot):0;
      var speed='';
      var now=Date.now();
      if(now-last>400){
        var bps=(e.loaded-lastBytes)*1000/(now-last);
        if(bps>0) speed='  ·  '+size(bps)+'/s';
        last=now; lastBytes=e.loaded;
      }
      fill.style.width=pct+'%';
      txt.textContent=(pct>=100 ? '已传完，设备正在写入 '+WHERE+' …' : '正在上传 '+pct+'%')
        +'  ·  '+size(e.loaded)+' / '+size(tot)+speed;
    };
    xhr.onload=function(){
      if(xhr.status>=200 && xhr.status<400){
        // 服务端回的是「上传完成」整页，直接顶掉当前页，省得再刷一次
        document.open(); document.write(xhr.responseText); document.close();
        return;
      }
      fail('上传失败（HTTP '+xhr.status+'），请重试。');
    };
    xhr.onerror=function(){ fail('上传失败：和设备断开了。看看设备上的「文件传输」是否还开着、两边是不是同一个 Wi-Fi。'); };
    xhr.ontimeout=function(){ fail('上传超时，请重试。'); };
    xhr.send(fd);
  });
})();
</script>
"""

    private fun waitPage(ip: String): String {
        val denied = deniedIp == ip
        val sb = StringBuilder()
        sb.append(docHead("等待确认"))
        sb.append("<div class=card>")
        sb.append("<h2>等待在这台设备上确认</h2>")
        if (denied) {
            sb.append("<p>设备上刚刚<b>拒绝了</b>这次连接。</p>")
            sb.append("<p class=mut>如果是误点，请在这一页按 F5 重新连接，然后在设备上点「同意」。</p>")
        } else {
            sb.append("<p>儿童桌面上已经弹出确认框，请拿起设备点「<b>同意</b>」。</p>")
            sb.append("<p class=mut>同意之后这一页每 2 秒自动刷新一次，刷新出来就是文件列表。</p>")
        }
        sb.append("<p class=mut>连上来的设备：${esc(ip)}</p>")
        sb.append("</div>")
        sb.append("<script>setTimeout(function(){location.reload()},2000)</script>")
        sb.append("</body></html>")
        return sb.toString()
    }

    /** 自检报告里的一小节 */
    fun report(ctx: Context): String {
        val sb = StringBuilder()
        sb.appendLine("服务状态：${if (running.get()) "运行中" else "未开启"}")
        sb.appendLine("端口：${if (running.get()) port.toString() else "（未启动）"}")
        sb.appendLine("访问地址：${urls(ctx).joinToString(" / ").ifEmpty { "（未启动）" }}")
        sb.appendLine("根目录：${Store.filesRoot(ctx)}")
        sb.appendLine("日志文件：${Diag.logFile(ctx)}")
        sb.appendLine("行为审计文件：${Audit.file(ctx)}（连上浏览器后在 logs/ 里点它就能下载）")
        sb.appendLine(
            "网页目录：根那层是 Download/（公共下载目录）和 files/（应用私有目录）两个位置，" +
                "都能进子目录、回上一级、新建子目录；浏览到哪一层就把文件传到哪一层，" +
                "重名文件一律拒绝上传、不覆盖，传完单独列出未上传的重名清单"
        )
        sb.appendLine(
            "公共下载目录：" + if (PublicDownloads.available()) {
                "本应用放过 ${PublicDownloads.total(ctx)} 个文件（其他应用也能看到）"
            } else {
                "Android 9 及以下写不了公共下载目录，只能用 files/"
            }
        )
        sb.appendLine("已同意的设备：${Store.approvedIps(ctx).joinToString(" / ").ifEmpty { "（还没有）" }}")
        sb.appendLine("正在等同意：${pendingIp() ?: "（没有）"}")
        return sb.toString()
    }
}
