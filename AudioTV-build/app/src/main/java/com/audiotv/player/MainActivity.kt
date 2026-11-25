package com.audiotv.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var adapter: ArrayAdapter<String>
    private val files = mutableListOf<Uri>()
    private val names = mutableListOf<String>()
    private val prefs by lazy { getSharedPreferences("library", MODE_PRIVATE) }
    private var current = -1
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        player = ExoPlayer.Builder(this).build()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(30,20,30,20); layoutDirection = View.LAYOUT_DIRECTION_RTL; setBackgroundColor(0xff101b28.toInt()) }
        root.addView(TextView(this).apply { text = "مكتبتي الصوتية | نسخة اختبار"; textSize = 26f; setTextColor(-1) })
        status = TextView(this).apply { text = "أضف ملفات صوتية أو اختبر زر المايك"; textSize = 18f; setTextColor(-1) }; root.addView(status)
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; root.addView(controls)
        fun button(label: String, action: () -> Unit) { controls.addView(Button(this).apply { text = label; isFocusable = true; setOnClickListener { action() } }, LinearLayout.LayoutParams(0,-2,1f)) }
        button("إضافة ملفات") { pick() }; button("أمر صوتي") { voice() }; button("فحص المايك") { startActivityForResult(Intent(this, MicActivity::class.java), 30) }; button("تشغيل / توقف") { toggle() }; button("التالي") { next() }
        list = ListView(this); adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, names); list.adapter = adapter; list.setOnItemClickListener { _,_,position,_ -> play(position) }; root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        try { val data = JSONArray(prefs.getString("files","[]")); for (i in 0 until data.length()) add(Uri.parse(data.getString(i))) } catch (_: Exception) {}
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { if(current in files.indices) prefs.edit().remove("pos:${files[current]}").apply(); if(current+1 < files.size) play(current+1) } }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) { status.text = "تعذر التشغيل: ${error.errorCodeName}" }
        })
        controls.getChildAt(0).requestFocus()
    }
    private fun pick() { try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "audio/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) },10) } catch (_: Exception) { status.text = "منتقي الملفات غير متوفر في النظام" } }
    private fun add(uri: Uri) { if(uri in files) return; files.add(uri); val name = try { contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } } catch (_: Exception) { null }; names.add(name ?: uri.lastPathSegment ?: "ملف صوتي"); adapter.notifyDataSetChanged() }
    private fun savePosition() { if(current in files.indices && player.playbackState != Player.STATE_ENDED) prefs.edit().putLong("pos:${files[current]}",player.currentPosition).apply() }
    private fun play(i: Int) { if(i !in files.indices) return; savePosition(); current=i; player.setMediaItem(MediaItem.fromUri(files[i])); player.seekTo(prefs.getLong("pos:${files[i]}",0)); player.prepare(); player.play(); status.text = "تشغيل: ${names[i]}" }
    private fun toggle() { if(current < 0) { play(0); return }; if(player.isPlaying) { player.pause(); savePosition(); status.text="متوقف مؤقتاً" } else { player.play(); status.text="تشغيل: ${names[current]}" } }
    private fun next() { if(current+1 in files.indices) play(current+1) else status.text="لا يوجد ملف تالٍ" }
    private fun voice() { if (getSharedPreferences("mic", MODE_PRIVATE).getString("mode", "system") != "system") { startActivityForResult(Intent(this, MicActivity::class.java).putExtra("auto", true), 30); return }; try { startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE,"ar-JO"); putExtra(RecognizerIntent.EXTRA_PROMPT,"قل الأمر الصوتي") },20) } catch (_: Exception) { status.text="خدمة التعرف الصوتي غير متوفرة على الشاشة" } }
    private fun normalize(s: String) = s.lowercase().replace(Regex("[\\u064B-\\u065F\\u0640]"),"").replace(Regex("[أإآ]"),"ا").replace('ى','ي').trim()
    private fun command(raw: String) { val s=normalize(raw); status.text="سمعت: $raw"; when { s.contains("التالي") -> next(); s.contains("توقف") || s.contains("ايقاف") -> { player.pause(); savePosition() }; s.contains("كمل") || s.contains("استكمل") -> { if(current>=0) player.play() else play(0) }; s.contains("قدم") && s.contains("دق") -> if(current>=0) player.seekTo(player.currentPosition+300000); else -> { val q=s.removePrefix("شغل").removePrefix("تشغيل").trim(); val i=names.indexOfFirst { q.isNotBlank() && normalize(it).contains(q) }; if(i>=0) play(i) else status.text="لم أجد ملفاً مطابقاً: $raw" } } }
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: Intent?) { super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK || data==null) return; if(requestCode==30) { data.getStringExtra("speech")?.let { command(it) } } else if(requestCode==10) { val selected=mutableListOf<Uri>(); data.clipData?.let { for(i in 0 until it.itemCount) selected.add(it.getItemAt(i).uri) }; data.data?.let { selected.add(it) }; for(uri in selected) { try { contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); add(uri) } catch (_: Exception) { status.text="تعذر حفظ صلاحية الملف" } }; prefs.edit().putString("files",JSONArray(files.map{it.toString()}).toString()).apply() } else if(requestCode==20) { data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { command(it) } } }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean { if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) { when(event.keyCode) { KeyEvent.KEYCODE_VOICE_ASSIST,KeyEvent.KEYCODE_SEARCH -> { voice(); return true }; KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { toggle(); return true }; KeyEvent.KEYCODE_MEDIA_NEXT -> { next(); return true } } }; return super.dispatchKeyEvent(event) }
    override fun onPause() { savePosition(); super.onPause() }
    override fun onDestroy() { savePosition(); player.release(); super.onDestroy() }
}
