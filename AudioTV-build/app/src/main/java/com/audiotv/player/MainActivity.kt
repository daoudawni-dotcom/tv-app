package com.audiotv.player

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private data class LibraryItem(
        val uri: Uri,
        val title: String,
        val isFolder: Boolean = false,
        val folderPath: String? = null,
        var coverPath: String? = null
    )

    private lateinit var player: ExoPlayer
    private lateinit var surfaceView: SurfaceView
    private lateinit var status: TextView
    private lateinit var timeInfo: TextView
    private lateinit var artwork: ImageView
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>

    private val items = mutableListOf<LibraryItem>()
    private val displayLabels = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var current = -1
    private var pendingCoverTargetIndex = -1
    private var surfaceReady = false
    private var hasVideoTrack = false

    private val mediaExts = setOf(
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "wma", "opus", "amr", "m4b", "aiff", "alac",
        "mp4", "mkv", "webm", "m4v", "3gp", "avi", "mov", "ts", "m2ts", "flv", "vob", "mpg", "mpeg"
    )
    private val videoExts = setOf(
        "mp4", "mkv", "webm", "m4v", "3gp", "avi", "mov", "ts", "m2ts", "flv", "vob", "mpg", "mpeg"
    )
    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                updateTimeDisplay()
                if (player.isPlaying) {
                    savePositionSilent()
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureStoragePermission()

        player = ExoPlayer.Builder(this).build()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 14)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(0xff101b28.toInt())
        }

        root.addView(TextView(this).apply {
            text = "مكتبتي الصوتية والمرئية"
            textSize = 22f
            setTextColor(Color.WHITE)
        })

        surfaceView = SurfaceView(this).apply {
            visibility = View.GONE
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surfaceReady = true
                    if (hasVideoTrack && ::player.isInitialized) {
                        player.setVideoSurfaceView(this@surfaceView)
                    }
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    surfaceReady = true
                    if (hasVideoTrack && ::player.isInitialized) {
                        player.setVideoSurfaceView(this@surfaceView)
                    }
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surfaceReady = false
                    if (::player.isInitialized) {
                        player.clearVideoSurfaceView(this@surfaceView)
                    }
                }
            })
        }
        root.addView(surfaceView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 420))

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 6)
        }
        artwork = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(84, 84)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(defaultDrawable(isFolder = false))
        }

        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 0, 0, 0)
        }
        status = TextView(this).apply {
            text = "جاهز — أضف ملفات أو مجلدات أو استأنف التشغيل"
            textSize = 15f
            setTextColor(Color.WHITE)
        }
        timeInfo = TextView(this).apply {
            text = "مضى: 00:00  |  باقي: 00:00  |  الكلي: 00:00"
            textSize = 14f
            setTextColor(0xff9ad1ff.toInt())
            setPadding(0, 4, 0, 0)
        }
        infoCol.addView(status)
        infoCol.addView(timeInfo)

        headerRow.addView(artwork)
        headerRow.addView(infoCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(headerRow)

        val controlsRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val controlsRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 6, 0, 6)
        }
        root.addView(controlsRow1)
        root.addView(controlsRow2)

        fun addBtn(parent: LinearLayout, label: String, action: () -> Unit) {
            parent.addView(
                Button(this).apply {
                    text = label
                    textSize = 13f
                    isFocusable = true
                    setOnClickListener { action() }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }

        addBtn(controlsRow1, "إضافة ملفات") { pickMedia() }
        addBtn(controlsRow1, "تصفح مجلدات") { openFolderBrowser(selectCoverMode = false) }
        addBtn(controlsRow1, "تشغيل / إيقاف") { toggle() }
        addBtn(controlsRow1, "استئناف") { resumeLast() }

        addBtn(controlsRow2, "التالي") { next() }
        addBtn(controlsRow2, "أمر صوتي") { voice() }
        addBtn(controlsRow2, "فحص المايك") { openMicScreen(autoStart = false) }

        list = ListView(this).apply {
            dividerHeight = 2
        }
        adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, displayLabels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val tv = view.findViewById<TextView>(android.R.id.text1)
                val item = items.getOrNull(position)
                val isPlayed = if (item != null && !item.isFolder) {
                    prefs.getBoolean("played:${item.uri}", false)
                } else {
                    false
                }
                tv.setTextColor(
                    when {
                        position == current -> 0xff66d9ef.toInt()
                        isPlayed -> 0xffa6e22e.toInt()
                        else -> Color.WHITE
                    }
                )
                return view
            }
        }
        list.adapter = adapter

        list.setOnItemClickListener { _, _, position, _ ->
            val item = items.getOrNull(position) ?: return@setOnItemClickListener
            if (item.isFolder && item.folderPath != null) {
                openFolderBrowser(selectCoverMode = false, startDir = File(item.folderPath))
            } else {
                playIndex(position)
            }
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            showItemOptions(position)
            true
        }

        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        loadSavedLibrary()
        refreshListLabels()

        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val hasVid = videoSize.width > 0 && videoSize.height > 0
                hasVideoTrack = hasVid
                if (hasVid) {
                    surfaceView.visibility = View.VISIBLE
                    if (surfaceReady) {
                        player.setVideoSurfaceView(surfaceView)
                    }
                } else {
                    surfaceView.visibility = View.GONE
                    player.clearVideoSurfaceView(surfaceView)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                updateTimeDisplay()
                if (state == Player.STATE_ENDED) {
                    if (current in items.indices) {
                        val uriStr = items[current].uri.toString()
                        prefs.edit()
                            .remove("pos:$uriStr")
                            .putBoolean("played:$uriStr", true)
                            .apply()
                        refreshListLabels()
                    }
                    playNextPlayable(fromIndex = current + 1)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                status.text = "تعذر التشغيل: ${error.errorCodeName}"
            }
        })

        controlsRow1.getChildAt(0)?.requestFocus()
        autoResumeIfAvailable()
        updateTimeDisplay()
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L || ms == C.TIME_UNSET) return "00:00"
        val totalSec = ms / 1000L
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        return if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    private fun updateTimeDisplay() {
        val pos = if (::player.isInitialized) player.currentPosition.coerceAtLeast(0L) else 0L
        val dur = if (::player.isInitialized) player.duration else C.TIME_UNSET
        val hasDur = dur > 0L && dur != C.TIME_UNSET
        val rem = if (hasDur) (dur - pos).coerceAtLeast(0L) else 0L
        timeInfo.text = "مضى: ${formatDuration(pos)}  |  باقي: ${if (hasDur) formatDuration(rem) else "--:--"}  |  الكلي: ${if (hasDur) formatDuration(dur) else "--:--"}"
    }

    private fun refreshListLabels() {
        displayLabels.clear()
        for (item in items) {
            val uriStr = item.uri.toString()
            val savedMs = if (!item.isFolder) prefs.getLong("pos:$uriStr", 0L) else 0L
            val isPlayed = if (!item.isFolder) prefs.getBoolean("played:$uriStr", false) else false

            val posTag = if (savedMs > 1000L) {
                " [${formatDuration(savedMs)}]"
            } else {
                ""
            }
            val playedTag = if (!item.isFolder && isPlayed) " • تم تشغيله" else ""
            val coverTag = if (item.coverPath != null) " [صورة]" else ""
            val prefix = if (item.isFolder) "مجلد: " else ""
            displayLabels.add("$prefix${item.title}$coverTag$posTag$playedTag")
        }
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                )
            } catch (_: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) {
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val needed = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= 33) {
                for (perm in listOf(
                    Manifest.permission.READ_MEDIA_AUDIO,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_IMAGES
                )) {
                    if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                        needed.add(perm)
                    }
                }
            } else {
                if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    needed.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            if (needed.isNotEmpty()) {
                requestPermissions(needed.toTypedArray(), 50)
            }
        }
    }

    private fun pickMedia() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, 10)
        } catch (_: Exception) {
            status.text = "منتقي الملفات غير متوفر — فتح متصفح المجلدات الداخلي"
            openFolderBrowser(selectCoverMode = false)
        }
    }

    private fun openFolderBrowser(selectCoverMode: Boolean, startDir: File? = null) {
        val roots = getStorageRoots()
        val initialDir = startDir?.takeIf { it.exists() && it.isDirectory }
            ?: roots.firstOrNull()
            ?: Environment.getExternalStorageDirectory()

        fun showDirDialog(dir: File) {
            val entries = dir.listFiles()?.sortedWith(
                compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }
            )?.filter { file ->
                if (selectCoverMode) {
                    file.isDirectory || isImageFile(file)
                } else {
                    file.isDirectory || isMediaFile(file)
                }
            } ?: emptyList()

            val labels = mutableListOf<String>()
            val canGoUp = dir.parentFile != null && dir.parentFile?.canRead() == true
            if (canGoUp) labels.add(".. (مجلد أعلى: ${dir.parentFile?.name ?: ""})")
            if (!selectCoverMode && dir.canRead()) {
                labels.add("[إضافة المجلد كاملاً] ${dir.name}")
            }
            for (f in entries) {
                if (f.isDirectory) {
                    val hasImg = findFolderCover(f) != null
                    labels.add(if (hasImg) "[مجلد+صورة] ${f.name}" else "[مجلد] ${f.name}")
                } else {
                    val tag = if (selectCoverMode) "[صورة]" else if (isVideoFile(f)) "[فيديو]" else "[صوت]"
                    labels.add("$tag ${f.name}")
                }
            }

            AlertDialog.Builder(this)
                .setTitle(if (selectCoverMode) "اختر صورة: ${dir.name}" else "المجلدات: ${dir.name}")
                .setItems(labels.toTypedArray()) { dialog, which ->
                    dialog.dismiss()
                    var idx = which
                    if (canGoUp) {
                        if (idx == 0) {
                            dir.parentFile?.let { showDirDialog(it) }
                            return@setItems
                        }
                        idx--
                    }
                    if (!selectCoverMode && dir.canRead()) {
                        if (idx == 0) {
                            addFolderWithMedia(dir)
                            return@setItems
                        }
                        idx--
                    }
                    val chosen = entries.getOrNull(idx) ?: return@setItems
                    if (chosen.isDirectory) {
                        showDirDialog(chosen)
                    } else if (selectCoverMode && isImageFile(chosen)) {
                        applyCoverToTarget(chosen.absolutePath)
                    } else if (isMediaFile(chosen)) {
                        val addedIdx = addFileItem(Uri.fromFile(chosen), chosen.name, findMediaCover(chosen))
                        saveLibrary()
                        refreshListLabels()
                        if (addedIdx >= 0) playIndex(addedIdx)
                    }
                }
                .setNeutralButton("تبديل الجذر") { d, _ ->
                    d.dismiss()
                    val rootLabels = roots.map { it.absolutePath }.toTypedArray()
                    AlertDialog.Builder(this)
                        .setTitle("اختر وحدة التخزين")
                        .setItems(rootLabels) { _, rIdx ->
                            roots.getOrNull(rIdx)?.let { showDirDialog(it) }
                        }
                        .show()
                }
                .setNegativeButton("إغلاق", null)
                .show()
        }

        showDirDialog(initialDir)
    }

    private fun getStorageRoots(): List<File> {
        val listRoots = mutableListOf<File>()

        fun addCandidate(dir: File?) {
            if (dir == null) return
            try {
                if (dir.exists() && dir.isDirectory && dir.canRead() &&
                    listRoots.none { it.absolutePath == dir.absolutePath }
                ) {
                    listRoots.add(dir)
                }
            } catch (_: Exception) {
            }
        }

        addCandidate(Environment.getExternalStorageDirectory())

        try {
            getExternalFilesDirs(null)?.forEach { extDir ->
                if (extDir != null) {
                    val path = extDir.absolutePath
                    val androidIdx = path.indexOf("/Android/")
                    val base = if (androidIdx > 0) File(path.substring(0, androidIdx)) else extDir
                    addCandidate(base)
                }
            }
        } catch (_: Exception) {
        }

        try {
            File("/storage").listFiles()?.forEach { entry ->
                if (entry.isDirectory && entry.name != "emulated" && entry.name != "self") {
                    addCandidate(entry)
                }
            }
        } catch (_: Exception) {
        }

        val extraMountPaths = listOf(
            "/mnt/media_rw",
            "/mnt/usb",
            "/mnt/udisk",
            "/mnt/usb_storage",
            "/mnt/sdcard/usb_storage"
        )
        for (p in extraMountPaths) {
            val base = File(p)
            if (base.exists() && base.isDirectory) {
                addCandidate(base)
                try {
                    base.listFiles()?.forEach { sub ->
                        if (sub.isDirectory) addCandidate(sub)
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (listRoots.isEmpty()) addCandidate(File("/"))
        return listRoots
    }

    private fun addFolderWithMedia(folder: File) {
        val folderCover = findFolderCover(folder)?.absolutePath
        val folderUri = Uri.parse("folder://${folder.absolutePath}")
        if (items.none { it.uri == folderUri }) {
            items.add(
                LibraryItem(
                    uri = folderUri,
                    title = folder.name,
                    isFolder = true,
                    folderPath = folder.absolutePath,
                    coverPath = folderCover
                )
            )
        }

        val mediaFiles = folder.walkTopDown()
            .maxDepth(2)
            .filter { it.isFile && isMediaFile(it) }
            .sortedBy { it.name.lowercase() }
            .toList()

        var addedCount = 0
        for (media in mediaFiles) {
            val cover = findMediaCover(media) ?: folderCover
            val beforeSize = items.size
            addFileItem(Uri.fromFile(media), media.name, cover)
            if (items.size > beforeSize) addedCount++
        }

        refreshListLabels()
        saveLibrary()
        status.text = "تمت إضافة مجلد (${folder.name}) و $addedCount ملف وسائط"
    }

    private fun addFileItem(uri: Uri, explicitName: String? = null, explicitCover: String? = null): Int {
        val existingIdx = items.indexOfFirst { it.uri == uri }
        if (existingIdx >= 0) {
            if (explicitCover != null && items[existingIdx].coverPath == null) {
                items[existingIdx].coverPath = explicitCover
            }
            return existingIdx
        }

        val resolvedName = explicitName ?: try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: uri.lastPathSegment ?: "ملف وسائط"

        val localFile = uriToLocalFile(uri)
        val cover = explicitCover ?: localFile?.let { findMediaCover(it) }

        items.add(
            LibraryItem(
                uri = uri,
                title = resolvedName,
                isFolder = false,
                folderPath = localFile?.parentFile?.absolutePath,
                coverPath = cover
            )
        )
        return items.lastIndex
    }

    private fun uriToLocalFile(uri: Uri): File? {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path ?: return null
            val f = File(path)
            return if (f.exists()) f else null
        }
        return null
    }

    private fun findFolderCover(folder: File): File? {
        val children = folder.listFiles()?.filter { it.isFile && isImageFile(it) } ?: return null
        val priority = listOf("cover", "folder", "thumb", "thumbnail", "art", "poster")
        for (p in priority) {
            children.firstOrNull { it.nameWithoutExtension.lowercase().contains(p) }?.let { return it }
        }
        return children.sortedBy { it.name.lowercase() }.firstOrNull()
    }

    private fun findMediaCover(mediaFile: File): String? {
        val parent = mediaFile.parentFile ?: return null
        val baseName = mediaFile.nameWithoutExtension.lowercase()
        val images = parent.listFiles()?.filter { it.isFile && isImageFile(it) } ?: emptyList()

        images.firstOrNull { it.nameWithoutExtension.lowercase() == baseName }?.let { return it.absolutePath }
        images.firstOrNull { it.nameWithoutExtension.lowercase().contains(baseName) }?.let { return it.absolutePath }
        findFolderCover(parent)?.let { return it.absolutePath }
        return null
    }

    private fun isMediaFile(file: File): Boolean = file.extension.lowercase() in mediaExts
    private fun isVideoFile(file: File): Boolean = file.extension.lowercase() in videoExts
    private fun isImageFile(file: File): Boolean = file.extension.lowercase() in imageExts

    private fun showItemOptions(index: Int) {
        val item = items.getOrNull(index) ?: return
        val isPlayed = prefs.getBoolean("played:${item.uri}", false)
        val options = mutableListOf<String>()
        if (!item.isFolder) {
            options.add("تشغيل")
            options.add(if (isPlayed) "إلغاء علامة تم تشغيله" else "تحديد كـ تم تشغيله")
        }
        options.add("تعيين صورة للملف أو المجلد")
        if (item.coverPath != null) options.add("إزالة الصورة المرتبطة")
        options.add("حذف من القائمة")

        AlertDialog.Builder(this)
            .setTitle(item.title)
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "تشغيل" -> playIndex(index)
                    "تحديد كـ تم تشغيله" -> {
                        prefs.edit().putBoolean("played:${item.uri}", true).apply()
                        refreshListLabels()
                    }
                    "إلغاء علامة تم تشغيله" -> {
                        prefs.edit().putBoolean("played:${item.uri}", false).apply()
                        refreshListLabels()
                    }
                    "تعيين صورة للملف أو المجلد" -> {
                        pendingCoverTargetIndex = index
                        val start = item.folderPath?.let { File(it) } ?: Environment.getExternalStorageDirectory()
                        openFolderBrowser(selectCoverMode = true, startDir = start)
                    }
                    "إزالة الصورة المرتبطة" -> {
                        item.coverPath = null
                        refreshListLabels()
                        saveLibrary()
                        updateHeaderArtwork(item)
                    }
                    "حذف من القائمة" -> {
                        if (current == index) {
                            player.stop()
                            surfaceView.visibility = View.GONE
                            current = -1
                        } else if (current > index) {
                            current--
                        }
                        items.removeAt(index)
                        refreshListLabels()
                        saveLibrary()
                    }
                }
            }
            .show()
    }

    private fun applyCoverToTarget(imagePath: String) {
        val idx = pendingCoverTargetIndex
        pendingCoverTargetIndex = -1
        if (idx !in items.indices) return
        items[idx].coverPath = imagePath
        refreshListLabels()
        saveLibrary()
        if (idx == current) {
            updateHeaderArtwork(items[idx])
        }
        status.text = "تم ربط الصورة بنجاح"
    }

    private fun updateHeaderArtwork(item: LibraryItem?) {
        if (item == null) {
            artwork.setImageDrawable(defaultDrawable(isFolder = false))
            return
        }
        artwork.setImageDrawable(loadItemDrawable(item))
    }

    private fun loadItemDrawable(item: LibraryItem): Drawable {
        item.coverPath?.let { path ->
            val f = File(path)
            if (f.exists()) {
                val bmp = decodeSampledBitmap(f.absolutePath, 120, 120)
                if (bmp != null) return BitmapDrawable(resources, bmp)
            }
        }
        return defaultDrawable(item.isFolder)
    }

    private fun decodeSampledBitmap(path: String, reqW: Int, reqH: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            var sample = 1
            while (opts.outHeight / sample > reqH * 2 || opts.outWidth / sample > reqW * 2) {
                sample *= 2
            }
            BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (_: Exception) {
            null
        }
    }

    private fun defaultDrawable(isFolder: Boolean): Drawable {
        val size = 84
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(if (isFolder) 0xff284363.toInt() else 0xff1f344d.toInt())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            textAlign = Paint.Align.CENTER
        }
        val tag = if (isFolder) "DIR" else "AV"
        canvas.drawText(tag, size / 2f, size * 0.62f, paint)
        return BitmapDrawable(resources, bmp)
    }

    private fun saveLibrary() {
        val arr = JSONArray()
        for (it in items) {
            val obj = JSONObject().apply {
                put("uri", it.uri.toString())
                put("title", it.title)
                put("isFolder", it.isFolder)
                put("folderPath", it.folderPath ?: JSONObject.NULL)
                put("coverPath", it.coverPath ?: JSONObject.NULL)
            }
            arr.put(obj)
        }
        prefs.edit().putString("items_v2", arr.toString()).apply()
    }

    private fun loadSavedLibrary() {
        val rawV2 = prefs.getString("items_v2", null)
        if (!rawV2.isNullOrBlank()) {
            try {
                val arr = JSONArray(rawV2)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val uri = Uri.parse(obj.getString("uri"))
                    val title = obj.optString("title", uri.lastPathSegment ?: "عنصر")
                    val isFolder = obj.optBoolean("isFolder", false)
                    val folderPath = obj.optString("folderPath").takeIf { it.isNotBlank() && it != "null" }
                    val coverPath = obj.optString("coverPath").takeIf { it.isNotBlank() && it != "null" }
                    items.add(LibraryItem(uri, title, isFolder, folderPath, coverPath))
                }
                return
            } catch (_: Exception) {
            }
        }

        try {
            val legacy = JSONArray(prefs.getString("files", "[]"))
            for (i in 0 until legacy.length()) {
                addFileItem(Uri.parse(legacy.getString(i)))
            }
        } catch (_: Exception) {
        }
    }

    private fun savePositionSilent() {
        if (current !in items.indices) return
        val item = items[current]
        if (item.isFolder) return
        val pos = player.currentPosition.coerceAtLeast(0L)
        val dur = player.duration
        val uriStr = item.uri.toString()

        if (dur > 0 && dur != C.TIME_UNSET && pos >= dur - 1500L) {
            prefs.edit()
                .remove("pos:$uriStr")
                .putBoolean("played:$uriStr", true)
                .apply()
        } else {
            val editor = prefs.edit()
                .putLong("pos:$uriStr", pos)
                .putString("last_uri", uriStr)
            if (pos > 1000L) {
                editor.putBoolean("played:$uriStr", true)
            }
            editor.apply()
        }
    }

    private fun savePosition() {
        savePositionSilent()
        refreshListLabels()
    }

    private fun playIndex(i: Int, forceStartMs: Long? = null) {
        if (i !in items.indices) return
        val item = items[i]
        if (item.isFolder) {
            playNextPlayable(fromIndex = i + 1)
            return
        }

        savePositionSilent()
        current = i
        val uriStr = item.uri.toString()
        prefs.edit()
            .putString("last_uri", uriStr)
            .putBoolean("played:$uriStr", true)
            .apply()

        val resumeMs = forceStartMs ?: prefs.getLong("pos:$uriStr", 0L).coerceAtLeast(0L)
        hasVideoTrack = false
        surfaceView.visibility = View.GONE
        player.clearVideoSurfaceView(surfaceView)

        player.setMediaItem(MediaItem.fromUri(item.uri))
        player.prepare()
        if (resumeMs > 0L) {
            player.seekTo(resumeMs)
        }
        player.play()
        updateHeaderArtwork(item)
        updateTimeDisplay()
        refreshListLabels()

        status.text = if (resumeMs > 1000L) {
            "تشغيل: ${item.title} (استئناف من ${formatDuration(resumeMs)})"
        } else {
            "تشغيل: ${item.title}"
        }
    }

    private fun playNextPlayable(fromIndex: Int) {
        for (idx in fromIndex until items.size) {
            if (!items[idx].isFolder) {
                playIndex(idx)
                return
            }
        }
        status.text = "لا يوجد ملف تالٍ"
    }

    private fun autoResumeIfAvailable() {
        val lastUriStr = prefs.getString("last_uri", null) ?: return
        val idx = items.indexOfFirst { !it.isFolder && it.uri.toString() == lastUriStr }
        if (idx >= 0) {
            current = idx
            val savedPos = prefs.getLong("pos:${items[idx].uri}", 0L)
            updateHeaderArtwork(items[idx])
            if (savedPos > 1000L) {
                status.text = "محفوظ عند ${formatDuration(savedPos)} — ${items[idx].title}"
            }
        }
    }

    private fun resumeLast() {
        val lastUriStr = prefs.getString("last_uri", null)
        val idx = if (current in items.indices && !items[current].isFolder) {
            current
        } else if (lastUriStr != null) {
            items.indexOfFirst { !it.isFolder && it.uri.toString() == lastUriStr }
        } else {
            items.indexOfFirst { !it.isFolder }
        }

        if (idx >= 0) {
            playIndex(idx)
        } else {
            status.text = "لا يوجد تشغيل سابق لاستئنافه"
        }
    }

    private fun toggle() {
        if (current !in items.indices || items[current].isFolder) {
            resumeLast()
            return
        }
        if (player.isPlaying) {
            player.pause()
            savePosition()
            status.text = "متوقف مؤقتاً: ${items[current].title}"
        } else {
            player.play()
            status.text = "تشغيل: ${items[current].title}"
        }
        updateTimeDisplay()
    }

    private fun next() {
        if (current < 0) {
            playNextPlayable(0)
        } else {
            playNextPlayable(current + 1)
        }
    }

    private fun openMicScreen(autoStart: Boolean) {
        try {
            val intent = Intent(this, MicActivity::class.java)
            if (autoStart) intent.putExtra("auto", true)
            startActivityForResult(intent, 30)
        } catch (_: Exception) {
            status.text = "تعذر فتح شاشة المايك"
        }
    }

    private fun voice() {
        openMicScreen(autoStart = false)
    }

    private fun normalize(s: String): String {
        var out = s.lowercase()
        val diacritics = listOf("\u064B", "\u064C", "\u064D", "\u064E", "\u064F", "\u0650", "\u0651", "\u0652", "\u0640")
        for (d in diacritics) {
            out = out.replace(d, "")
        }
        return out
            .replace("أ", "ا")
            .replace("إ", "ا")
            .replace("آ", "ا")
            .replace("ى", "ي")
            .trim()
    }

    private fun command(raw: String) {
        val s = normalize(raw)
        status.text = "بحث داخلي عن: $raw"
        when {
            s.contains("التالي") -> next()
            s.contains("توقف") || s.contains("ايقاف") -> {
                player.pause()
                savePosition()
            }
            s.contains("كمل") || s.contains("استكمل") -> resumeLast()
            s.contains("قدم") && s.contains("دق") -> {
                if (current in items.indices && !items[current].isFolder) {
                    player.seekTo(player.currentPosition + 300000L)
                    savePosition()
                    updateTimeDisplay()
                }
            }
            else -> {
                val q = s.removePrefix("شغل").removePrefix("تشغيل").removePrefix("ابحث").removePrefix("بحث").trim()
                val idx = items.indexOfFirst { !it.isFolder && q.isNotBlank() && normalize(it.title).contains(q) }
                if (idx >= 0) {
                    playIndex(idx)
                } else {
                    status.text = "لا يوجد ملف بهذا الاسم داخل المكتبة: $raw"
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 30) {
            val speech = data?.getStringExtra("speech")?.trim()
            if (resultCode == RESULT_OK && !speech.isNullOrEmpty()) {
                command(speech)
            } else {
                status.text = "لم يتم التقاط صوت من المايك — جرّب «فحص المايك»"
            }
            return
        }
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == 10) {
            val selected = mutableListOf<Uri>()
            data.clipData?.let { for (i in 0 until it.itemCount) selected.add(it.getItemAt(i).uri) }
            data.data?.let { selected.add(it) }
            for (uri in selected) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Exception) {
                }
                addFileItem(uri)
            }
            refreshListLabels()
            saveLibrary()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_SEARCH -> {
                    voice()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    toggle()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    next()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        handler.post(tickRunnable)
    }

    override fun onStop() {
        handler.removeCallbacks(tickRunnable)
        savePositionSilent()
        super.onStop()
    }

    override fun onPause() {
        savePositionSilent()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        savePositionSilent()
        player.release()
        super.onDestroy()
    }
}
