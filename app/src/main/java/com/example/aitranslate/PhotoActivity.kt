package com.example.aitranslate

import android.app.Activity
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File

/** 悬浮球「拍照」：调系统相机拍一张 → 图片翻译 */
class PhotoActivity : Activity() {
    private lateinit var file: File
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(cacheDir, "photo.jpg"); file.delete()
        val uri = Uri.parse("content://${packageName}.shots/photo.jpg")
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("photo", uri)
        try { startActivityForResult(i, 1) } catch (e: Exception) {
            Toast.makeText(this, "没有找到相机应用", Toast.LENGTH_SHORT).show(); finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && file.length() > 0) {
            startActivity(Intent(this, TranslateActivity::class.java)
                .putExtra(TranslateActivity.EXTRA_SHOT, file.absolutePath))
        }
        finish()
    }
}

/** 只给相机写入拍照文件用的极简 Provider */
class ShotProvider : ContentProvider() {
    override fun onCreate() = true
    private fun f(uri: Uri) = File(context!!.cacheDir, uri.lastPathSegment ?: "photo.jpg")
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(f(uri), ParcelFileDescriptor.parseMode(mode))
    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
        val file = f(uri)
        return MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf(file.name, file.length())) }
    }
    override fun insert(uri: Uri, v: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
