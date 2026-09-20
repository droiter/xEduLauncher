package com.ccbridge.child_launcher

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.InputStream
import java.io.OutputStream

/**
 * 设备的公共下载目录 /sdcard/Download。
 *
 * 家长通过文件传输服务上传的视频放这里，而不是应用私有目录 /sdcard/Android/data/<包名>/files——
 * 私有目录从 Android 11 起别的应用根本看不到，孩子要用的播放器、教育应用就选不到那些视频
 * （owner 2026-09-15 反馈）。
 *
 * Android 10 起往公共目录写必须走 MediaStore（Scoped Storage），直接按路径写会被拒；
 * 插入自己新建的文件不需要任何存储权限，也不会弹框。Android 9 及以下没有 Downloads 集合，
 * [available] 返回 false，调用方退回私有目录。
 *
 * 这里能看见的**只有本应用自己放进去的东西**（按 [MediaStore.MediaColumns.OWNER_PACKAGE_NAME] 筛）：
 * 别的应用放进 Download/ 的文件本应用既列不出也读不了，子目录同理。
 *
 * 子目录：owner 2026-09-19 要求网页上能进出子目录、还能新建子目录。MediaStore 里没有「目录」这种
 * 东西，一个子目录要么靠「插入一条 MIME_TYPE 为目录的记录」建出来（[mkdir]），要么靠落在它里面的
 * 文件带出来（文件记录的 RELATIVE_PATH 就写着它在 Download 下的位置）。所以 [listAt] 是两路合起来
 * 算的：直接放在这一层的文件 + 往下一层还有东西的目录。
 */
object PublicDownloads {

    /** 落点：Download/ 根下（owner 要的就是「存储/Downloads 下」，不另开子目录） */
    private val RELATIVE = Environment.DIRECTORY_DOWNLOADS

    /** 目录记录的 MIME_TYPE，MediaStore 用它区分「这一条是目录」 */
    private const val MIME_DIR = "vnd.android.document/directory"

    fun available(): Boolean = Build.VERSION.SDK_INT >= 29

    /** 一次上传里正在写的那个条目：写完 [finish]，中途失败 [abort] */
    class Pending(val uri: Uri, val name: String)

    /** 目录列表里的一行 */
    class Entry(val name: String, val dir: Boolean, val size: Long, val addedAt: Long)

    /** MediaStore 里的一条记录，已经归一化好路径 */
    private class Row(
        val id: Long,
        val name: String,
        val size: Long,
        val addedAt: Long,
        /** 所在目录的 RELATIVE_PATH（去掉了结尾斜杠），Download 根是 "Download" */
        val parent: String,
        val dir: Boolean,
    )

    /** rel 是 Download 下的相对路径（"" 表示 Download 根），转成 MediaStore 要的 RELATIVE_PATH */
    private fun relPathOf(rel: String): String {
        val sub = rel.trim('/')
        return if (sub.isEmpty()) RELATIVE else "$RELATIVE/$sub"
    }

    /**
     * rel 这一层里的条目：直接放在这儿的文件/目录，加上「里面有东西」的下一层目录。
     * 目录排前面，各自按名字排。
     */
    fun listAt(ctx: Context, rel: String): List<Entry> {
        val prefix = relPathOf(rel)
        val out = LinkedHashMap<String, Entry>()
        for (r in rows(ctx)) {
            when {
                r.parent == prefix -> out[r.name] = Entry(r.name, r.dir, r.size, r.addedAt)
                r.parent.startsWith("$prefix/") -> {
                    val seg = r.parent.removePrefix("$prefix/").substringBefore('/')
                    if (seg.isNotEmpty()) out.putIfAbsent(seg, Entry(seg, true, 0L, 0L))
                }
            }
        }
        return out.values.sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
    }

    /** rel 这一层里已经有的名字（文件 + 目录），重名预检用 */
    fun names(ctx: Context, rel: String): List<String> {
        val prefix = relPathOf(rel)
        return rows(ctx).filter { it.parent == prefix }.map { it.name }
    }

    /** rel 这一层里已经有这个名字了吗（文件或目录都算） */
    fun has(ctx: Context, rel: String, name: String): Boolean {
        val prefix = relPathOf(rel)
        return rows(ctx).any { it.parent == prefix && it.name == name }
    }

    /** 本应用放进公共下载目录的文件总数（各个子目录都算） */
    fun total(ctx: Context): Int = rows(ctx).count { !it.dir }

    /**
     * 在 rel 下新建子目录。插完再查一遍，查得到才算成功——有的系统版本不认目录记录，
     * 那就如实返回 false，让页面告诉家长这台设备上建不了。
     */
    fun mkdir(ctx: Context, rel: String, name: String): Boolean {
        if (!available()) return false
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, MIME_DIR)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPathOf(rel))
            }
            if (ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) == null) {
                return false
            }
        } catch (e: Exception) {
            Diag.log("http", "在下载目录里建子目录失败：${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        return has(ctx, rel, name)
    }

    /**
     * 在 rel 下开一个待写条目。同名文件系统会自己改名（a.mp4 → a(1).mp4），
     * 重名在调用方那边已经拦掉了，这里不负责。
     * 返回 null 表示这条道走不通，调用方退回私有目录。
     */
    fun begin(ctx: Context, rel: String, name: String, mime: String): Pending? {
        if (!available()) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPathOf(rel))
            // 写完之前先挂着 IS_PENDING：别的应用这时看不到半截文件
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) null else Pending(uri, name)
        } catch (e: Exception) {
            Diag.log("http", "建公共下载目录条目失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    fun out(ctx: Context, p: Pending): OutputStream? = try {
        ctx.contentResolver.openOutputStream(p.uri)
    } catch (e: Exception) {
        Diag.log("http", "打开公共下载目录写入流失败：${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /** 写完了：清掉 IS_PENDING，系统这时才把文件对别的应用开放 */
    fun finish(ctx: Context, p: Pending) {
        try {
            val v = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            ctx.contentResolver.update(p.uri, v, null, null)
        } catch (e: Exception) {
            Diag.log("http", "公共下载目录收尾失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 写失败/客户端断开：把那个半截条目删掉，别在下载目录里留垃圾 */
    fun abort(ctx: Context, p: Pending) {
        try {
            ctx.contentResolver.delete(p.uri, null, null)
        } catch (_: Exception) {
        }
    }

    /** 打开 rel 下本应用放进去的一个文件读，给浏览器下载用 */
    fun open(ctx: Context, rel: String, name: String): InputStream? {
        if (!available()) return null
        val prefix = relPathOf(rel)
        val row = rows(ctx).firstOrNull { it.parent == prefix && it.name == name && !it.dir } ?: return null
        return try {
            ctx.contentResolver.openInputStream(
                Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row.id.toString())
            )
        } catch (e: Exception) {
            Diag.log("http", "读公共下载目录文件失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** 把本应用在 Download/ 下的记录一次查全，列表、查重、下载都拿它挑 */
    private fun rows(ctx: Context): List<Row> {
        if (!available()) return emptyList()
        val out = ArrayList<Row>()
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            val sel = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, arrayOf(ctx.packageName), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1) ?: continue
                    val rp = (c.getString(4) ?: RELATIVE).trimEnd('/')
                    // DATE_ADDED 是秒，这里统一成毫秒
                    out.add(Row(c.getLong(0), n, c.getLong(2), c.getLong(3) * 1000L, rp, c.getString(5) == MIME_DIR))
                }
            }
        } catch (e: Exception) {
            Diag.log("http", "列公共下载目录失败：${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
        return out
    }
}
