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
 */
object PublicDownloads {

    /** 落点：Download/ 根下（owner 要的就是「存储/Downloads 下」，不另开子目录） */
    private val RELATIVE = Environment.DIRECTORY_DOWNLOADS

    fun available(): Boolean = Build.VERSION.SDK_INT >= 29

    /** 一次上传里正在写的那个条目：写完 [finish]，中途失败 [abort] */
    class Pending(val uri: Uri, val name: String)

    /** 列表 / 下载用的一行 */
    class Row(val name: String, val size: Long, val addedAt: Long)

    /**
     * 在公共下载目录里开一个待写条目。同名文件系统会自己改名（a.mp4 → a(1).mp4），不用算重名。
     * 返回 null 表示这条道走不通，调用方退回私有目录。
     */
    fun begin(ctx: Context, name: String, mime: String): Pending? {
        if (!available()) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE)
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

    /**
     * 本应用放进公共下载目录的文件（也就是家长上传的那些），新的在前。
     * 只按「属主是本应用」筛：别的应用放进 Download/ 的文件，本应用既列不出也读不了。
     */
    fun list(ctx: Context): List<Row> {
        if (!available()) return emptyList()
        val rows = ArrayList<Row>()
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
            )
            val sel = "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, arrayOf(ctx.packageName), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    // DATE_ADDED 是秒，这里统一成毫秒
                    rows.add(Row(n, c.getLong(1), c.getLong(2) * 1000L))
                }
            }
        } catch (e: Exception) {
            Diag.log("http", "列公共下载目录失败：${e.javaClass.simpleName}: ${e.message}")
            return emptyList()
        }
        return rows.sortedByDescending { it.addedAt }
    }

    /** 打开公共下载目录里的一个文件（只认本应用自己放进去的）读，给浏览器下载用 */
    fun open(ctx: Context, name: String): InputStream? {
        if (!available() || name.isBlank()) return null
        return try {
            val proj = arrayOf(MediaStore.MediaColumns._ID)
            val sel = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND " +
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?"
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, arrayOf(name, ctx.packageName), null,
            )?.use { c ->
                if (!c.moveToFirst()) return null
                val uri = Uri.withAppendedPath(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString(),
                )
                ctx.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            Diag.log("http", "读公共下载目录文件失败：${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
