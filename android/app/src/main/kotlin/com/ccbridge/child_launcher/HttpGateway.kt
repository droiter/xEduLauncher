package com.ccbridge.child_launcher

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 文件传输服务：家长用电脑/手机的浏览器连上这台设备，下载日志、上传视频等文件。
 *
 * 零依赖手写 HTTP/1.1（项目一贯不用第三方库）：
 *   GET  /            文件列表（?d=子目录）
 *   GET  /f?p=路径     下载（支持 Range 分片，可断点续传）
 *   GET  /pub?n=名字   下载「公共下载目录」里本应用上传上去的文件
 *   POST /up          上传，multipart/form-data
 *
 * 根目录是应用私有外部目录 /sdcard/Android/data/<包名>/files，里面 logs/ 是运行日志和自检报告。
 * 上传的文件不落这里，落到设备的公共下载目录 /sdcard/Download/（见 [PublicDownloads]）：
 * 家长传的视频是给孩子那些应用用的，私有目录别的应用根本看不到。
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
            ctx.startActivity(i)
        } catch (e: Exception) {
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
            path == "/" || path == "/index.html" -> listPage(ctx, out, query["d"].orEmpty())
            path == "/f" -> download(ctx, req, out, query["p"].orEmpty())
            path == "/pub" -> downloadPublic(ctx, req, out, query["n"].orEmpty())
            path == "/up" && req.method == "POST" -> upload(ctx, req, br, out)
            else -> page(out, 404, "没这个地址")
        }
    }

    // ---------- 列表 / 下载 ----------

    private fun listPage(ctx: Context, out: OutputStream, sub: String) {
        val root = Store.filesRoot(ctx)
        val dir = resolve(root, sub)
        if (dir == null || !dir.isDirectory) {
            page(out, 404, "目录不存在")
            return
        }
        val rel = relOf(root, dir)
        val sb = StringBuilder()
        sb.append(docHead("儿童桌面 · 文件传输"))
        sb.append("<h2>儿童桌面文件传输</h2>")
        sb.append("<p class=mut>根目录：${esc(root.absolutePath)}</p>")

        sb.append("<div class=bar><b>${esc(if (rel.isEmpty()) "/" else "/$rel")}</b>")
        if (rel.isNotEmpty()) {
            val up = rel.substringBeforeLast('/', "")
            sb.append(" <a class=btn href=\"/?d=${urlEnc(up)}\">返回上一层</a>")
        }
        sb.append("</div>")

        sb.append("<h3>上传</h3>")
        sb.append("<form method=post action=\"/up?back=${urlEnc(rel)}\" enctype=\"multipart/form-data\" class=up>")
        sb.append("<input type=file name=f multiple> ")
        sb.append("<button type=submit>上传</button></form>")
        sb.append(
            "<p class=mut>上传的文件一律保存到设备的公共下载目录 <b>Download/</b>：" +
                "放那里其他应用也看得到（播放器、孩子的教育应用都能直接选到）；" +
                "应用私有目录别的应用看不到，所以不用它。</p>"
        )

        sb.append("<h3>文件</h3><table>")
        val items = dir.listFiles()
            ?.filter { !it.name.startsWith(".") } // 上传中的临时文件不给看
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?: emptyList()
        if (items.isEmpty()) {
            sb.append("<tr><td class=mut>（这一层还没有文件）</td></tr>")
        }
        for (f in items) {
            val childRel = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            sb.append("<tr><td>")
            if (f.isDirectory) {
                sb.append("<a href=\"/?d=${urlEnc(childRel)}\">📁 ${esc(f.name)}/</a>")
            } else {
                sb.append("<a href=\"/f?p=${urlEnc(childRel)}\">${esc(f.name)}</a>")
            }
            sb.append("</td><td class=mut>${if (f.isDirectory) "目录" else size(f.length())}</td>")
            sb.append("<td class=mut>${stamp(f.lastModified())}</td></tr>")
        }
        sb.append("</table>")

        // 上传的东西不在这里，在设备的公共下载目录，单独列一节让家长能核对
        sb.append("<h3>公共下载目录 Download/（上传的文件在这里）</h3><table>")
        val pub = PublicDownloads.list(ctx)
        if (!PublicDownloads.available()) {
            sb.append("<tr><td class=mut>这台设备（Android 9 及以下）没有公共下载目录可写，上传落在下面的 uploads/</td></tr>")
        } else if (pub.isEmpty()) {
            sb.append("<tr><td class=mut>（还没有上传过文件）</td></tr>")
        }
        for (r in pub) {
            sb.append("<tr><td><a href=\"/pub?n=${urlEnc(r.name)}\">${esc(r.name)}</a></td>")
            sb.append("<td class=mut>${size(r.size)}</td><td class=mut>${stamp(r.addedAt)}</td></tr>")
        }
        sb.append("</table>")

        sb.append(foot())
        page(out, html(sb.toString()))
    }

    private fun download(ctx: Context, req: Req, out: OutputStream, rel: String) {
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

    private fun upload(ctx: Context, req: Req, ins: BackReader, out: OutputStream) {
        // 上传不认「家长当前浏览到哪一层」，一律落到设备的公共下载目录（见 [PublicDownloads]）。
        // back 只决定传完之后把家长送回哪一页。
        val back = parseQuery(req.target.substringAfter('?', ""))["back"].orEmpty()
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

        val publicSaved = ArrayList<String>()
        val privateSaved = ArrayList<String>()
        var seq = 0
        while (true) {
            val headers = HashMap<String, String>()
            while (true) {
                val h = readLine(ins) ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
            }
            val name = filenameOf(headers["content-disposition"].orEmpty())?.let { safeName(it) }
            val pub = if (name != null) PublicDownloads.begin(ctx, name, mimeOf(name)) else null
            if (pub != null) {
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
                    publicSaved.add(pub.name)
                    Diag.log("http", "收到文件 ${pub.name} → 公共下载目录 Download/")
                } else {
                    PublicDownloads.abort(ctx, pub)
                }
            } else {
                // 兜底：Android 9 及以下没有 Downloads 集合、MediaStore 插入失败，或这一段没带文件名。
                // 不管走哪条，正文都必须读干净，否则后面几段的头会被当成文件内容
                val dir = File(Store.filesRoot(ctx), "uploads")
                if (!dir.isDirectory && !dir.mkdirs()) {
                    page(out, 500, "目标目录建不出来")
                    return
                }
                val tmp = File(dir, ".upload-${SystemClock.elapsedRealtime()}-${seq++}.part")
                val ok = try {
                    FileOutputStream(tmp).use { fos ->
                        BufferedOutputStream(fos, 64 * 1024).use { copyUntil(ins, it, delim) }
                    }
                } catch (e: Exception) {
                    Diag.log("http", "上传写盘失败：${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                if (name != null && ok && tmp.length() > 0) {
                    val target = uniqueName(dir, name)
                    if (tmp.renameTo(target)) {
                        privateSaved.add(target.name)
                        Diag.log("http", "收到文件 ${target.name}（${size(target.length())}）→ 私有 uploads/")
                    } else {
                        tmp.delete()
                    }
                } else {
                    tmp.delete()
                }
            }
            val after = readLine(ins) ?: break
            if (after.trim().startsWith("--")) break
        }

        if (publicSaved.isEmpty() && privateSaved.isEmpty()) {
            page(out, 400, "没有收到文件")
            return
        }
        val sb = StringBuilder()
        sb.append(docHead("上传完成"))
        sb.append("<h2>上传完成</h2><div class=card>")
        if (publicSaved.isNotEmpty()) {
            sb.append("<p class=ok>已保存到设备的公共下载目录 <b>Download/</b>：</p><ul>")
            for (n in publicSaved) sb.append("<li>${esc(n)}</li>")
            sb.append("</ul>")
            sb.append("<p class=mut>这个目录其他应用也看得到：播放器、孩子的教育应用都能直接选到。</p>")
        }
        if (privateSaved.isNotEmpty()) {
            sb.append("<p class=ok>已保存到应用私有目录 uploads/：</p><ul>")
            for (n in privateSaved) sb.append("<li>${esc(n)}</li>")
            sb.append("</ul>")
            sb.append("<p class=mut>这台设备写不了公共下载目录，只能先放这里。</p>")
        }
        sb.append("<p><a class=btn href=\"/?d=${urlEnc(back)}\">返回文件列表</a></p>")
        sb.append("</div>")
        sb.append(foot())
        page(out, html(sb.toString()))
    }

    /** 下载公共下载目录里本应用上传上去的文件，Range 分片照旧支持 */
    private fun downloadPublic(ctx: Context, req: Req, out: OutputStream, name: String) {
        val row = PublicDownloads.list(ctx).firstOrNull { it.name == name }
        val stream = PublicDownloads.open(ctx, name)
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

    /** Content-Disposition 里的 filename（含 filename* 的 UTF-8 形式） */
    private fun filenameOf(cd: String): String? {
        val star = cd.indexOf("filename*=")
        if (star >= 0) {
            var v = cd.substring(star + "filename*=".length).trim()
            if (v.startsWith("\"")) v = v.trim('"')
            val idx = v.indexOf("''")
            if (idx >= 0) v = v.substring(idx + 2)
            return try {
                URLDecoder.decode(v, "UTF-8")
            } catch (_: Exception) {
                null
            }
        }
        val plain = cd.indexOf("filename=")
        if (plain < 0) return null
        var v = cd.substring(plain + "filename=".length).trim().substringBefore(';').trim()
        if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v = v.substring(1, v.length - 1)
        return v.ifBlank { null }
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

    private fun uniqueName(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (f.exists() && n < 1000) {
            f = File(dir, "$base($n)$ext")
            n++
        }
        return f
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

    private fun relOf(root: File, f: File): String = try {
        f.canonicalFile.toRelativeString(root.canonicalFile).replace(File.separatorChar, '/').trim('/')
    } catch (_: Exception) {
        ""
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
        button{background:#2b6cff;color:#fff;border:0;border-radius:8px;padding:9px 18px;font-size:14px;cursor:pointer}
        input[type=file]{font-size:14px;margin-right:8px}
        </style>
    """

    private fun docHead(title: String): String =
        "<html><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">" +
            "<title>$title</title>${css()}</head><body>"

    private fun foot(): String = "</body></html>"

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
        sb.appendLine(
            "上传落点：" + if (PublicDownloads.available()) {
                "设备的公共下载目录 Download/（其他应用也能看到），已上传 ${PublicDownloads.list(ctx).size} 个"
            } else {
                "Android 9 及以下写不了公共目录，落在私有 uploads/"
            }
        )
        sb.appendLine("已同意的设备：${Store.approvedIps(ctx).joinToString(" / ").ifEmpty { "（还没有）" }}")
        sb.appendLine("正在等同意：${pendingIp() ?: "（没有）"}")
        return sb.toString()
    }
}
