package edu.hust.homeworklogistics

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.FrameLayout
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("session", MODE_PRIVATE) }
    private val ui by lazy { Ui(this) }
    private var token = ""
    private var role = ""
    private var userId = ""
    private var loginWeb: WebView? = null
    private var fileCallback: ValueCallback<Array<android.net.Uri>>? = null
    private val loginPoll = Handler(Looper.getMainLooper())
    private var userName = ""
    private var studentNo = ""
    private var roleLabel = ""
    private var avatarPath = ""
    /** 当前班级名，账号与安全页要用；懒加载，取不到时不用空字符串误导 */
    private var className = ""
    /** 班级下拉菜单的最后一项 */
    private val createClassLabel = "＋ 创建新班级"
    private var onPicked: ((ByteArray) -> Unit)? = null
    private var onBack: (() -> Unit)? = null
        set(value) {
            field = value
            syncBackCallback()
        }

    // Android 13+ (and always on Android 16+ with targetSdk 36+) routes the back gesture
    // through OnBackInvokedDispatcher instead of onBackPressed().
    private var backCallback: Any? = null
    private var backRegistered = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        MessageAlerts.schedule(this)
        CloudSession.load(prefs)
        token = CloudSession.accessToken
        if (token.isNotEmpty()) resumeSession() else showWebLogin()
    }

    override fun onDestroy() {
        loginPoll.removeCallbacksAndMessages(null)
        stopMessagePoll()
        executor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val back = onBack
        if (back != null) back() else super.onBackPressed()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 4102) {
            val result = if (resultCode == RESULT_OK && data != null) WebChromeClient.FileChooserParams.parseResult(resultCode, data) else null
            fileCallback?.onReceiveValue(result)
            fileCallback = null
            return
        }
        if (requestCode != 4101) return
        if (resultCode != RESULT_OK || data?.data == null) {
            onPicked = null
            return
        }
        val uri = data.data ?: return
        val callback = onPicked ?: return
        bg(onError = { toast(friendly(it)) }) {
            val raw = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@bg
            val jpeg = jpegBytes(raw)
            ui { callback(jpeg) }
        }
    }

    private fun pickImage(onReady: (ByteArray) -> Unit) {
        onPicked = onReady
        startActivityForResult(
            Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            4101,
        )
    }

    private fun jpegBytes(raw: ByteArray): ByteArray {
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: throw IllegalStateException("这张图片打不开")
        val maxEdge = 1600
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(decoded.width, decoded.height))
        val bitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
        } else decoded
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        if (bitmap !== decoded) bitmap.recycle()
        return out.toByteArray()
    }

    private fun face(name: String, path: String, sizeDp: Int): View {
        // optString 会把 JSON null 读成字符串 "null"，这里一并兜住
        // 没设头像就显示统一的默认头像（淡紫圆 + 人形），不用名字首字
        if (path.isBlank() || path == "null") {
            return glyphBadge("person", Palette.accentSoft, sizeDp, Palette.accentDark)
        }
        val size = ui.dp(sizeDp)
        val image = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.rounded(Palette.accentSoft, if (sizeDp >= 56) 8 else 18)
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
        }
        loadBitmap(path) { image.setImageBitmap(it) }
        return image
    }

    private fun photoStrip(images: org.json.JSONArray?): View? {
        if (images == null || images.length() == 0) return null
        val row = ui.horizontal()
        for (index in 0 until images.length()) {
            val url = images.getJSONObject(index).optString("url")
            val shot = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ui.rounded(Palette.field, 10)
                clipToOutline = true
                outlineProvider = ViewOutlineProvider.BACKGROUND
                setOnClickListener {
                    loadBitmap(url) { bitmap ->
                        val dialog = Dialog(this@MainActivity)
                        val preview = ImageView(this@MainActivity).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            clipToOutline = true
                            outlineProvider = ViewOutlineProvider.BACKGROUND
                            background = ui.rounded(Palette.card, 22)
                        }
                        dialog.setContentView(preview)
                        dialog.window?.setBackgroundDrawable(ui.rounded(Palette.card, 22))
                        dialog.show()
                    }
                }
            }
            loadBitmap(url) { shot.setImageBitmap(it) }
            row.addView(shot, ui.lp(ui.dp(160), ui.dp(120), end = 8))
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private val bitmapCache = LruCache<String, Bitmap>(32)

    private fun imageFile(path: String): File {
        val name = path.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "image" }
        val dir = File(filesDir, "images")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name)
    }

    private fun loadBitmap(path: String, onReady: (Bitmap) -> Unit) {
        if (path.isBlank()) return
        bitmapCache.get(path)?.let {
            onReady(it)
            return
        }
        val be = backend()
        val auth = token
        val file = imageFile(path)
        bg {
            val bytes = if (file.exists() && file.length() > 0) {
                runCatching { file.readBytes() }.getOrNull()
            } else {
                null
            } ?: run {
                val downloaded = runCatching { be.bytes(path, auth) }.getOrNull() ?: return@bg
                if (downloaded.size <= 2_000_000) runCatching { file.writeBytes(downloaded) }
                downloaded
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@bg
            bitmapCache.put(path, bitmap)
            ui { onReady(bitmap) }
        }
    }

    private fun syncBackCallback() {
        if (Build.VERSION.SDK_INT < 33) return
        val callback = (backCallback as? android.window.OnBackInvokedCallback)
            ?: android.window.OnBackInvokedCallback { onBack?.invoke() }.also { backCallback = it }
        if (onBack != null && !backRegistered) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backRegistered = true
        } else if (onBack == null && backRegistered) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
            backRegistered = false
        }
    }

    // ---------- mode & session ----------

    private fun isDemo() = false

    private fun tokenKey() = if (isDemo()) "token_demo" else "token_online"

    private fun backend(): Backend = if (isDemo()) DemoBackend.get(this) else CloudApi(prefs)

    private fun baseUrl() = "https://homework-assistant-14264.app.workbuddy.host"

    private fun applyUser(user: JSONObject) {
        userId = user.optString("id")
        role = user.optString("role")
        userName = user.optString("name")
        studentNo = user.optString("studentNo")
        roleLabel = user.optString("roleLabel")
        avatarPath = user.optString("avatar").removePrefix("null")
        myClassId = user.optLong("classId")
    }

    private fun logout() {
        stopMessagePoll()
        loginPoll.removeCallbacksAndMessages(null)
        MessageAlerts.clear(this)
        CloudSession.clear(prefs)
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        clearPageCache()
        token = ""
        userId = ""
        role = ""
        studentNo = ""
        avatarPath = ""
        showWebLogin()
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun showWebLogin() {
        if (loginWeb != null) return
        stopMessagePoll()
        val web = WebView(this)
        loginWeb = web
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https"
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pullSession(view)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<android.net.Uri>>?,
                params: FileChooserParams?,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(intent, 4102)
                return true
            }
        }
        setContentView(web)
        web.loadUrl("https://homework-assistant-14264.app.workbuddy.host/?sharecode=3NIEKUN3F6")
        loginPoll.removeCallbacksAndMessages(null)
        val tick = object : Runnable {
            override fun run() {
                pullSession(loginWeb)
                if (loginWeb != null) loginPoll.postDelayed(this, 1500)
            }
        }
        loginPoll.postDelayed(tick, 1500)
    }

    private var entering = false

    private fun jwtSub(token: String): String {
        val parts = token.split('.')
        if (parts.size < 2) return ""
        var payload = parts[1].replace('-', '+').replace('_', '/')
        val pad = (4 - payload.length % 4) % 4
        payload += "=".repeat(pad)
        val json = runCatching { org.json.JSONObject(String(android.util.Base64.decode(payload, android.util.Base64.DEFAULT))) }.getOrNull()
            ?: return ""
        return json.optString("sub").ifBlank { json.optString("uid") }
    }

    private fun pullSession(view: WebView?) {
        if (view == null || entering) return
        val script = "(function(){try{var want='workbuddy-cloud.session.wbpk_ofKMBbEQ72KsQszouCeHGw_yy7VeDCdh8d5aGN3RFhGIYPsJ8mtfESj';var best=null,score=-1;for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(!k||k.indexOf('workbuddy-cloud.session.')!==0)continue;var o=JSON.parse(localStorage.getItem(k)||'null');if(!o||!o.accessToken)continue;var s=(k===want?1e15:0)+(o.expiresAt||0);if(s>score){score=s;best=o}}if(!best)return null;var u=(best.user&&(best.user.id||best.user.sub||(best.user.raw&&(best.user.raw.sub||best.user.raw.uid))))||'';return JSON.stringify({a:best.accessToken||'',r:best.refreshToken||'',e:best.expiresAt||0,u:u});}catch(e){return null}})()"
        view.evaluateJavascript(script) { raw ->
            if (raw == null || raw == "null" || entering) return@evaluateJavascript
            val text = runCatching { org.json.JSONArray("[$raw]").getString(0) }.getOrNull() ?: return@evaluateJavascript
            val json = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return@evaluateJavascript
            val access = json.optString("a")
            if (access.isBlank()) return@evaluateJavascript
            val uid = json.optString("u").ifBlank { jwtSub(access) }
            android.util.Log.i("Homework", "session uid=${uid.take(8)} exp=${json.optLong("e")}")
            CloudSession.apply(access, json.optString("r"), json.optLong("e"), uid)
            CloudSession.save(prefs)
            token = access
            resumeSession()
        }
    }

    private fun resumeSession() {
        if (entering) return
        entering = true
        val be = backend()
        bg(onError = { error ->
            android.util.Log.e("Homework", "enter failed", error)
            entering = false
            ui { if (loginWeb == null) showWebLogin() }
        }) {
            // 没有档案就自动建一个「未加入班级」的账号，不进登录页
            val me = runCatching { be.get("/me", token).getJSONObject("user") }
                .getOrElse { be.post("/account/init", token, JSONObject()).getJSONObject("user") }
            ui {
                entering = false
                loginPoll.removeCallbacksAndMessages(null)
                loginWeb = null
                applyUser(me)
                MessageAlerts.ask(this)
                startMessagePoll()
                home()
            }
        }
    }

    // ---------- 加入班级 ----------

    /** 未加入班级时的落地页：下拉选班级 + 姓名学号，跟名单核对；菜单最下方可以建新班 */
    private fun joinClassPage() {
        onBack = null
        val card = ui.card(18)
        card.addView(ui.text("加入班级", 21f, Palette.ink, bold = true))
        card.addView(
            ui.text("选择班级，填写学委录入名单时的姓名和学号，核对通过就能进入班级。", 13f, Palette.muted),
            ui.lp(top = 6),
        )

        // 选项先只有「创建新班级」，班级列表拉回来再补进去
        val options = mutableListOf(createClassLabel)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val picker = Spinner(this).apply {
            this.adapter = adapter
            background = ui.rounded(Palette.field, 12, Palette.line)
            minimumHeight = ui.dp(48)
            // 右边留出箭头的位子，不然文字会压到箭头下面
            setPadding(ui.dp(12), 0, ui.dp(34), 0)
        }
        val fieldName = ui.field("姓名")
        val fieldNo = ui.field("学号")
        // 自己铺的圆角背景会把 Spinner 的系统箭头盖掉，这里补一个，不然看不出能点
        val pickerBox = FrameLayout(this).apply {
            addView(picker, FrameLayout.LayoutParams(MATCH_PARENT, ui.dp(48)))
            addView(
                ui.text("▾", 15f, Palette.muted).apply {
                    isClickable = false
                    isFocusable = false
                },
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL)
                    .apply { marginEnd = ui.dp(12) },
            )
        }
        card.addView(pickerBox, ui.lp(top = 14))
        card.addView(fieldName, ui.lp(top = 10))
        card.addView(fieldNo, ui.lp(top = 10))

        fun createFromPage() {
            val name = fieldName.text.toString().trim()
            val no = fieldNo.text.toString().trim()
            if (name.isEmpty() || no.isEmpty()) {
                toast("先填姓名和学号")
                return
            }
            askCreateClass(name, no) { joinClassPage() }
        }

        // 选中「创建新班级」时按钮跟着变成「创建班级」，点了就弹建班表单
        val joinButton = ui.primaryButton("加入") {
            val klass = picker.selectedItem?.toString().orEmpty()
            if (klass == createClassLabel) {
                createFromPage()
                return@primaryButton
            }
            val name = fieldName.text.toString().trim()
            val no = fieldNo.text.toString().trim()
            if (klass.isEmpty() || name.isEmpty() || no.isEmpty()) {
                toast("班级、姓名、学号都要填")
                return@primaryButton
            }
            bg(onError = { toast(friendly(it)) }) {
                val be = backend()
                be.post(
                    "/class/join",
                    token,
                    JSONObject().put("className", klass).put("name", name).put("studentNo", no),
                )
                val me = be.get("/me", token).getJSONObject("user")
                ui {
                    toast("欢迎加入 $klass")
                    applyUser(me)
                    className = klass
                    home()
                }
            }
        }
        // 按钮文案跟着选中项走：选中「创建新班级」就变成「创建班级」
        fun syncButton() {
            val creating = picker.selectedItem?.toString() == createClassLabel
            joinButton.text = if (creating) "创建班级" else "加入"
        }
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = syncButton()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        syncButton()
        card.addView(joinButton, ui.lp(top = 18))
        val content = ui.vertical(card)
        content.addView(
            ui.text("名单里没有你？让学委在「成员管理 → ＋」里添加你的姓名和学号。", 12f, Palette.faint),
            ui.lp(top = 14),
        )
        content.addView(
            ui.text("还没有班级？在下拉菜单里选「$createClassLabel」，建完你就是学委。", 12f, Palette.faint),
            ui.lp(top = 6),
        )
        // 底部导航留在这里：退过班的人还得能进「个人」页退出登录或注销账号
        screen(ui.topBar("加入班级"), content, "home")

        val be = backend()
        bg(onError = { toast(friendly(it)) }) {
            val data = be.get("/classes", token).getJSONArray("classes")
            val names = (0 until data.length()).map { data.getJSONObject(it).optString("name") }
            ui {
                if (names.isEmpty()) {
                    syncButton()
                    return@ui
                }
                val keep = picker.selectedItem?.toString()
                options.clear()
                options.addAll(names)
                options.add(createClassLabel)
                adapter.notifyDataSetChanged()
                val index = names.indexOf(keep)
                picker.setSelection(if (index >= 0) index else 0)
                // 选中位置没变时不会回调，这里手动同步一次，否则按钮文案会和显示不一致
                syncButton()
            }
        }
    }

    /** 建班表单：填了班级名 + 课程（选填），创建者当学委 */
    private fun askCreateClass(memberName: String, memberNo: String, then: () -> Unit) {
        val fieldClass = ui.field("班级名，如 能动2402")
        val fieldCourse = ui.field("课程，如 机械设计（选填）")
        val fieldName = ui.field("你的姓名").apply { setText(memberName) }
        val fieldNo = ui.field("你的学号").apply { setText(memberNo) }
        val box = ui.vertical(fieldClass, fieldCourse, fieldName, fieldNo).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4))
        }
        for (index in 1 until box.childCount) {
            (box.getChildAt(index).layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(10)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("创建新班级")
            .setMessage("建完你就是这个班的学委，可以添加成员、发布作业。之后同学选这个班名进来。")
            .setView(box)
            .setPositiveButton("创建") { _, _ ->
                val klass = fieldClass.text.toString().trim()
                val course = fieldCourse.text.toString().trim()
                val name = fieldName.text.toString().trim()
                val no = fieldNo.text.toString().trim()
                if (klass.isEmpty() || name.isEmpty() || no.isEmpty()) {
                    toast("班级名、姓名、学号都要填")
                    return@setPositiveButton
                }
                bg(onError = { toast(friendly(it)) }) {
                    val be = backend()
                    be.post(
                        "/class/create",
                        token,
                        JSONObject()
                            .put("className", klass)
                            .put("course", course)
                            .put("name", name)
                            .put("studentNo", no),
                    )
                    val me = be.get("/me", token).getJSONObject("user")
                    ui {
                        toast("「$klass」建好了")
                        applyUser(me)
                        className = klass
                        clearPageCache()
                        then()
                    }
                }
            }
            .setNegativeButton("取消") { _, _ -> then() }
            .show()
    }

    // ---------- home ----------

    private fun modeTag() = if (isDemo()) " · 离线体验" else ""

    private var collectedOpen = false
    private var latestMessageId = 0
    /** 当前停在哪一页（home/message/me），红点判定要用 */
    private var currentTab: String? = null
    private var updateChecked = false
    private var myClassId = 0L
    private var pollingMessages = false
    private val messagePoll = Handler(Looper.getMainLooper())

    private fun home() {
        onBack = null
        if (myClassId == 0L) {
            joinClassPage()
            return
        }
        val content = ui.vertical()
        val list = ui.vertical()
        list.addView(loadingView())
        content.addView(list)

        val bar = ui.topBar("作业")
        (bar.getChildAt(0) as LinearLayout).addView(face(userName, avatarPath, 36).apply {
            setOnClickListener { changeAvatar { home() } }
        })
        screen(bar, content, "home")
        if (!updateChecked) {
            updateChecked = true
            checkUpdate(silent = true)
        }

        val cached = readCache("/assignments")
        if (cached != null) renderHome(list, homeItems(cached))
        else list.addView(loadingView())
        val be = backend()
        bg(onError = { error ->
            if (cached == null) {
                list.removeAllViews()
                list.addView(errorCard(friendly(error)) { home() })
            }
        }) {
            val data = be.get("/assignments", token)
            writeCache("/assignments", data)
            ui { renderHome(list, homeItems(data)) }
        }
    }

    private fun homeItems(data: JSONObject): List<Pair<JSONObject, JSONObject?>> {
        val assignments = data.getJSONArray("assignments")
        return (0 until assignments.length()).map { index ->
            val item = assignments.getJSONObject(index)
            val status = str(item.optJSONObject("progress") ?: JSONObject(), "mineStatus")
            val mine = if (status.isBlank()) null else JSONObject().put("status", status)
            item to mine
        }
    }

    private fun renderHome(list: LinearLayout, items: List<Pair<JSONObject, JSONObject?>>) {
        list.removeAllViews()
        val active = items.filter { it.second?.optString("status") != "collected_by_monitor" }
        val collected = items.filter { it.second?.optString("status") == "collected_by_monitor" }
        if (active.isEmpty()) {
            list.addView(emptyCard(if (collected.isEmpty()) "没有作业" else "没有待揽收的作业"))
        }
        active.forEach { (item, mine) ->
            list.addView(assignmentCard(item, mine, null), ui.lp(bottom = 12))
        }
        if (collected.isEmpty()) return
        val head = ui.horizontal(
            ui.text("查看已揽收的作业", 14f, Palette.muted),
        ).apply {
            gravity = Gravity.CENTER
            setPadding(ui.dp(4), ui.dp(18), ui.dp(4), ui.dp(8))
        }
        ui.clickable(head, 0, android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)) {
            collectedOpen = !collectedOpen
            renderHome(list, items)
        }
        list.addView(head)
        if (collectedOpen) {
            collected.forEach { (item, mine) ->
                list.addView(assignmentCard(item, mine, null), ui.lp(bottom = 12))
            }
        }
    }

    private fun assignmentCard(item: JSONObject, mine: JSONObject?, subs: JSONArray?): View {
        val card = ui.card()
        val header = ui.horizontal(
            ui.text(assignmentTitle(item), 17f, Palette.ink, bold = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            },
        )
        when {
            mine != null -> header.addView(
                ui.statusChip(mine.optString("status"), item.optString("mode").ifBlank { "collect" }),
                ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 8),
            )
        }
        card.addView(header)
        val meta = ui.horizontal(
            ui.text("截止 ${formatDeadline(str(item, "deadline"))}", 13f, Palette.muted),
            ui.text("${str(item, "publisherName").ifEmpty { "学委" }} 发布", 13f, Palette.muted),
        )
        (meta.getChildAt(1).layoutParams as LinearLayout.LayoutParams).marginStart = ui.dp(16)
        card.addView(meta, ui.lp(top = 6))
        val preview = str(item, "description")
        if (preview.isNotBlank()) {
            card.addView(
                ui.text(preview, 14f, Palette.body).apply {
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                ui.lp(top = 8),
            )
        }
        card.addView(
            ui.text("查看详情 ›", 13f, Palette.accent, bold = true).apply { gravity = Gravity.END },
            ui.lp(top = 10),
        )
        card.isClickable = true
        card.background = ui.ripple(ui.rounded(Palette.card, 16), 16)
        card.setOnClickListener { detail(item.getInt("id")) }
        return card
    }

    private fun progressBar(value: Int, total: Int): View {
        val track = ui.horizontal().apply { background = ui.rounded(Palette.line, 999) }
        val fill = View(this).apply { background = ui.rounded(Palette.accent, 999) }
        track.addView(fill, LinearLayout.LayoutParams(0, ui.dp(6), value.toFloat()))
        track.addView(View(this), LinearLayout.LayoutParams(0, ui.dp(6), (total - value).toFloat()))
        return track
    }

    // ---------- detail ----------

    private fun detail(id: Int, back: () -> Unit = { home() }) {
        onBack = back
        val content = ui.vertical()
        content.addView(loadingView())
        screen(ui.topBar("作业详情", onBack = back), content)

        fun draw(assignment: JSONObject, mine: JSONObject?, comments: JSONArray, commentError: String) {
            content.removeAllViews()
            val mode = assignment.optString("mode").ifBlank { "full" }
            val info = ui.card(18)
            val headRow = ui.horizontal(
                ui.text(assignmentTitle(assignment), 21f, Palette.ink, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                },
            )
            if (mine != null) {
                headRow.addView(
                    ui.statusChip(mine.optString("status"), mode),
                    ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 8),
                )
            }
            info.addView(headRow)
            val description = str(assignment, "description")
            if (description.isNotBlank()) info.addView(ui.text(description, 15f, Palette.body), ui.lp(top = 8))
            val shots = photoStrip(assignment.optJSONArray("images"))
            if (shots != null) info.addView(shots, ui.lp(top = 12))
            val metaRow = ui.horizontal()
            metaRow.addView(ui.chip("截止 ${formatDeadline(str(assignment, "deadline"))}", Palette.accentDark, Palette.accentSoft))
            metaRow.addView(ui.chip("${str(assignment, "publisherName").ifEmpty { "学委" }} 发布", Palette.muted, Color.parseColor("#F3F4F6")).apply {
                layoutParams = ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 8)
            })
            info.addView(metaRow, ui.lp(top = 12))
            content.addView(info)
            if (mine != null) {
                content.addView(ui.section("我的进度"))
                content.addView(submissionCard(mine, id, expanded = true, showStudent = false, mode = mode))
            }
            content.addView(ui.section(if (comments.length() > 0) "评论 · ${comments.length()}" else "评论"))
            if (commentError.isNotBlank()) {
                content.addView(ui.card().apply { addView(ui.text(commentError, 14f, Palette.error)) })
            } else {
                content.addView(commentBoard(id, comments) { detail(id, back) })
            }
        }

        val cachedAssignment = readCache("/assignments/$id")
        val cachedMine = readCache("/assignments/$id/mine")
        val cachedComments = readCache("/assignments/$id/comments")
        if (cachedAssignment != null) {
            draw(
                cachedAssignment,
                cachedMine?.optJSONObject("submission"),
                cachedComments?.optJSONArray("comments") ?: JSONArray(),
                "",
            )
        } else content.addView(loadingView())

        val be = backend()
        bg(onError = { error ->
            if (cachedAssignment == null) {
                content.removeAllViews()
                content.addView(errorCard(friendly(error)) { detail(id) })
            }
        }) {
            val assignment = be.get("/assignments/$id", token)
            writeCache("/assignments/$id", assignment)
            val mineWrap = runCatching { be.get("/assignments/$id/mine", token) }.getOrNull()
            if (mineWrap != null) writeCache("/assignments/$id/mine", mineWrap)
            val commentsWrap = runCatching { be.get("/assignments/$id/comments", token) }.getOrElse {
                JSONObject().put("error", it.message ?: "评论加载失败")
            }
            if (!commentsWrap.has("error")) writeCache("/assignments/$id/comments", commentsWrap)
            ui {
                draw(
                    assignment,
                    mineWrap?.optJSONObject("submission"),
                    commentsWrap.optJSONArray("comments") ?: JSONArray(),
                    commentsWrap.optString("error"),
                )
            }
        }
    }

    private fun classSummary(submissions: JSONArray): View {
        val row = ui.horizontal()
        val counts = Domain.STATUSES.associateWith { status ->
            (0 until submissions.length()).count { submissions.getJSONObject(it).optString("status") == status }
        }
        val waiting = counts["not_submitted"] ?: 0
        val done = counts["distributed"] ?: 0
        val inProgress = submissions.length() - waiting - done
        listOf(
            Triple("未提交", waiting, "not_submitted"),
            Triple("流转中", inProgress, "received_by_ta"),
            Triple("已分发", done, "distributed"),
        ).forEachIndexed { index, (label, count, status) ->
            val (fg, bg) = Domain.statusColors(status)
            val box = ui.vertical(
                ui.text(count.toString(), 22f, fg, bold = true).apply { gravity = Gravity.CENTER },
                ui.text(label, 12f, fg).apply { gravity = Gravity.CENTER },
            ).apply {
                background = ui.rounded(bg, 12)
                setPadding(0, ui.dp(10), 0, ui.dp(10))
            }
            row.addView(box, ui.lp(0, WRAP_CONTENT, weight = 1f, start = if (index == 0) 0 else 8))
        }
        return row
    }

    private fun checkRow(submission: JSONObject): View {
        val student = submission.getJSONObject("student")
        val dot = View(this)
        val label = ui.text(submission.optString("statusLabel"), 13f, Palette.muted)
        val row = ui.horizontal(
            dot,
            face(student.optString("name"), student.optString("avatar"), 36),
            ui.vertical(
                nameWithRole(student.optString("name"), student.optString("role"), 16f, student.optBoolean("pending")),
                label,
            ).apply { setPadding(ui.dp(12), 0, 0, 0) },
        ).apply {
            setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12))
            tag = submission.optString("status") == "collected_by_monitor"
        }
        dot.layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22))
        fun paint(collected: Boolean, statusLabel: String) {
            row.tag = collected
            label.text = statusLabel
            dot.background = ui.circle(
                if (collected) Palette.ok else Color.TRANSPARENT,
                if (collected) Palette.ok else Palette.faint,
                2,
            )
        }
        paint(row.tag == true, submission.optString("statusLabel"))
        row.isClickable = true
        row.isFocusable = true
        row.background = ui.ripple(ui.rounded(Color.WHITE, 12), 12)
        row.setOnClickListener {
            if (!row.isEnabled) return@setOnClickListener
            val collected = row.tag == true
            row.isEnabled = false
            val be = backend()
            bg(onError = {
                row.isEnabled = true
                toast(friendly(it))
            }) {
                val next = be.post(
                    "/submissions/${submission.getInt("id")}/${if (collected) "revert" else "collect"}",
                    token,
                    JSONObject().put("note", ""),
                ).getJSONObject("submission")
                ui {
                    paint(next.optString("status") == "collected_by_monitor", next.optString("statusLabel"))
                    row.isEnabled = true
                }
            }
        }
        return row
    }

    private fun submissionCard(submission: JSONObject, assignmentId: Int, expanded: Boolean, showStudent: Boolean, mode: String = "full"): View {
        val card = ui.card()
        val status = submission.optString("status")
        val header = ui.horizontal()
        if (showStudent) {
            val student = submission.getJSONObject("student")
            header.addView(face(student.optString("name"), student.optString("avatar"), 36))
            header.addView(
                ui.vertical(
                    nameWithRole(student.optString("name"), student.optString("role"), 15f),
                    ui.text(student.optString("studentNo"), 12f, Palette.muted),
                ).apply { setPadding(ui.dp(10), 0, ui.dp(8), 0) },
                LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f),
            )
        } else {
            header.addView(ui.text("当前状态", 14f, Palette.muted), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        header.addView(ui.statusChip(status, mode))
        card.addView(header)

        val timeline = timeline(submission, mode)
        if (expanded) {
            card.addView(timeline, ui.lp(top = 16))
        } else {
            timeline.visibility = View.GONE
            val toggle = ui.linkButton("查看流程 ▾") {}
            toggle.setOnClickListener {
                val open = timeline.visibility != View.VISIBLE
                timeline.visibility = if (open) View.VISIBLE else View.GONE
                toggle.text = if (open) "收起流程 ▴" else "查看流程 ▾"
            }
            card.addView(toggle, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 6))
            card.addView(timeline, ui.lp(top = 8))
        }

        val actions = submission.optJSONObject("actions") ?: JSONObject()
        val canAdvance = actions.optBoolean("advance")
        val canRevert = actions.optBoolean("revert")
        if (canAdvance || canRevert) {
            card.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(1)).apply {
                topMargin = ui.dp(12); bottomMargin = ui.dp(12)
            })
            val buttons = ui.horizontal()
            val id = submission.getInt("id")
            if (canRevert) {
                buttons.addView(ui.secondaryButton("退回上一步", Palette.muted) {
                    move(id, "revert", "", assignmentId)
                }, ui.lp(0, WRAP_CONTENT, weight = 1f, end = if (canAdvance) 8 else 0))
            }
            if (canAdvance) {
                buttons.addView(ui.primaryButton(str(actions, "advanceLabel").ifEmpty { "下一步" }) {
                    move(id, "advance", "", assignmentId)
                }, ui.lp(0, WRAP_CONTENT, weight = if (canRevert) 1.4f else 1f))
            }
            card.addView(buttons, ui.lp(top = 10))
        }
        return card
    }

    private fun timeline(submission: JSONObject, mode: String = "full"): LinearLayout {
        val box = ui.vertical()
        val status = submission.optString("status")
        val collect = mode == "collect" || status in Domain.COLLECT_STATUSES
        val steps = if (collect) Domain.COLLECT_STATUSES else Domain.STATUSES
        val stepMode = if (collect) "collect" else mode
        val current = steps.indexOf(status).coerceAtLeast(0)
        val events = submission.optJSONArray("events") ?: JSONArray()
        val eventList = (0 until events.length()).map { events.getJSONObject(it) }
        val (currentColor, currentSoft) = Domain.statusColors(status)

        steps.forEachIndexed { index, step ->
            val done = index < current
            val isCurrent = index == current
            val last = index == steps.lastIndex

            val dot = ui.text(if (done) "✓" else if (isCurrent) "" else "${index + 1}", 11f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                when {
                    done -> background = ui.circle(Palette.accent)
                    isCurrent -> background = ui.circle(currentColor, currentSoft, 5)
                    else -> {
                        background = ui.circle(Color.WHITE, Palette.line, 2)
                        setTextColor(Palette.faint)
                    }
                }
            }
            val rail = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
            rail.addView(dot, LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)))
            if (!last) {
                rail.addView(View(this).apply { setBackgroundColor(if (done) Palette.accent else Palette.line) },
                    LinearLayout.LayoutParams(ui.dp(2), 0, 1f).apply { topMargin = ui.dp(2); bottomMargin = ui.dp(2) })
            }

            val info = ui.vertical().apply { setPadding(ui.dp(12), ui.dp(1), 0, if (last) 0 else ui.dp(16)) }
            val labelRow = ui.horizontal(
                ui.text(
                    Domain.label(step, stepMode),
                    15f,
                    when {
                        isCurrent -> currentColor
                        done -> Palette.ink
                        else -> Palette.faint
                    },
                    bold = isCurrent || done,
                ),
            )
            val stepEvents = eventList.filter { it.optString("status") == step }
            if (isCurrent) labelRow.addView(ui.chip("当前", currentColor, currentSoft), ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 8))
            if (index <= current && stepEvents.size > 1) {
                labelRow.addView(ui.chip("有退回", Palette.error, Palette.errorSoft), ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 6))
            }
            info.addView(labelRow)

            val event = if (index <= current) stepEvents.lastOrNull() else null
            if (event != null) {
                info.addView(
                    ui.text("${str(event, "actorName").ifEmpty { "—" }} · ${formatTime(str(event, "createdAt"))}", 13f, Palette.muted),
                    ui.lp(top = 2),
                )
                val note = str(event, "note")
                if (note.isNotBlank()) {
                    info.addView(ui.text("“$note”", 13f, Palette.body).apply {
                        background = ui.rounded(Color.parseColor("#F9FAFB"), 8, Palette.line)
                        setPadding(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(6))
                    }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 6))
                }
            }

            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(rail, LinearLayout.LayoutParams(ui.dp(24), MATCH_PARENT))
            row.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            box.addView(row)
        }
        return box
    }

    private fun commentBoard(assignmentId: Int, comments: JSONArray, reload: () -> Unit): View {
        val box = ui.vertical()
        if (comments.length() == 0) box.addView(ui.text("还没有评论", 14f, Palette.muted), ui.lp(bottom = 10))
        for (index in 0 until comments.length()) {
            box.addView(commentCard(comments.getJSONObject(index), reload), ui.lp(bottom = 10))
        }
        box.addView(commentComposer(assignmentId, reload))
        return box
    }

    private fun commentCard(comment: JSONObject, reload: () -> Unit): View {
        val card = ui.card()
        val author = comment.optJSONObject("author") ?: JSONObject()
        val header = ui.horizontal()
        header.addView(face(author.optString("name"), author.optString("avatar"), 36))
        header.addView(
            ui.vertical(
                nameWithRole(author.optString("name"), author.optString("role"), 15f),
                ui.text(formatTime(str(comment, "createdAt")), 12f, Palette.muted),
            ),
            ui.lp(0, WRAP_CONTENT, weight = 1f, start = 10),
        )
        val canDelete = author.optString("id") == userId || role == "monitor"
        if (canDelete) {
            header.addView(ui.text("删除", 13f, Palette.error).apply {
                setPadding(ui.dp(8), ui.dp(8), ui.dp(2), ui.dp(8))
                setOnClickListener {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("删除评论")
                        .setMessage("删除后不能恢复。")
                        .setPositiveButton("删除") { _, _ ->
                            val be = backend()
                            bg(onError = { toast(friendly(it)) }) {
                                be.delete("/comments/${comment.optInt("id")}", token)
                                ui {
                                    toast("已删除")
                                    reload()
                                }
                            }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
            })
        }
        card.addView(header)
        val body = str(comment, "body")
        if (body.isNotBlank()) card.addView(ui.text(body, 15f, Palette.body), ui.lp(top = 8))
        val shots = photoStrip(comment.optJSONArray("images"))
        if (shots != null) card.addView(shots, ui.lp(top = 8))
        return card
    }

    private fun commentComposer(assignmentId: Int, reload: () -> Unit): View {
        val photos = mutableListOf<ByteArray>()
        val preview = ui.horizontal().apply {
            setPadding(ui.dp(12), ui.dp(8), ui.dp(12), 0)
            visibility = View.GONE
        }
        val sending = booleanArrayOf(false)
        fun paintPhotos() {
            preview.removeAllViews()
            preview.visibility = if (photos.isEmpty()) View.GONE else View.VISIBLE
            photos.forEachIndexed { index, jpeg ->
                val thumb = ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ui.rounded(Palette.field, 8)
                    clipToOutline = true
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                    setOnClickListener {
                        photos.removeAt(index)
                        paintPhotos()
                    }
                }
                preview.addView(thumb, ui.lp(ui.dp(64), ui.dp(64), end = 8))
            }
        }

        fun send(text: String) {
            if (sending[0]) return
            if (text.isEmpty() && photos.isEmpty()) {
                toast("请填写评论或添加图片")
                return
            }
            sending[0] = true
            val shots = photos.toList()
            val be = backend()
            bg(onError = {
                sending[0] = false
                toast(friendly(it))
            }) {
                val created = be.post(
                    "/assignments/$assignmentId/comments",
                    token,
                    JSONObject().put("body", text),
                )
                val commentId = created.getJSONObject("comment").optInt("id")
                var failed = 0
                shots.forEach { jpeg ->
                    val uploaded = runCatching {
                        be.post(
                            "/comments/$commentId/images",
                            token,
                            JSONObject().put("mime", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                        )
                    }.isSuccess
                    if (!uploaded) failed += 1
                }
                if (text.isEmpty() && shots.isNotEmpty() && failed == shots.size) {
                    runCatching { be.delete("/comments/$commentId", token) }
                    throw IllegalStateException("图片没有传上去")
                }
                ui {
                    sending[0] = false
                    photos.clear()
                    paintPhotos()
                    toast(if (failed > 0) "评论已发出，有 $failed 张图片没传上" else "已发送")
                    reload()
                }
            }
        }

        val composer = ui.composer(
            "写下评论，也可以只发图片",
            onPickImage = {
                if (photos.size >= 4) toast("一条评论最多 4 张图片")
                else pickImage { jpeg ->
                    photos.add(jpeg)
                    paintPhotos()
                }
            },
            onSend = { text -> send(text) },
        )
        return ui.vertical(preview, composer).apply {
            background = ui.rounded(Palette.card, 16, Palette.line)
            clipToOutline = true
        }
    }

    private fun move(submissionId: Int, direction: String, note: String, assignmentId: Int) {
        val be = backend()
        bg(onError = { toast(friendly(it)) }) {
            be.post("/submissions/$submissionId/$direction", token, JSONObject().put("note", note))
            ui {
                toast(if (direction == "advance") "已更新" else "已退回上一步")
                detail(assignmentId)
            }
        }
    }

    // ---------- publish & members ----------

    private var rosterEditing = false
    private var rosterReady = false
    private val rosterMarks = mutableMapOf<Int, Boolean>()

    private fun manageAssignments() {
        onBack = { profile() }
        val content = ui.vertical()
        content.addView(ui.primaryButton("发布作业") { publish() })
        val list = ui.vertical()
        content.addView(list, ui.lp(top = 12))
        screen(ui.topBar("作业管理", onBack = { profile() }), content)

        fun paint(data: JSONObject) {
            val assignments = data.getJSONArray("assignments")
            list.removeAllViews()
            if (assignments.length() == 0) list.addView(emptyCard("没有作业"))
            for (index in 0 until assignments.length()) {
                val item = assignments.getJSONObject(index)
                val row = ui.horizontal(
                    ui.vertical(
                        ui.text(assignmentTitle(item), 16f, Palette.ink, bold = true),
                        ui.text("截止 ${formatDeadline(str(item, "deadline"))}", 12f, Palette.muted),
                    ).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) },
                    ui.text("删除", 14f, Palette.error).apply {
                        setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8))
                        setOnClickListener { askDeleteAssignment(item) }
                    },
                    ui.text("›", 18f, Palette.faint),
                ).apply { setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)) }
                ui.clickable(row, 12, ui.rounded(Color.WHITE, 12)) {
                    rosterEditing = false
                    rosterReady = false
                    rosterMarks.clear()
                    manageRoster(item.getInt("id"))
                }
                list.addView(row, ui.lp(bottom = 8))
            }
        }

        val cached = readCache("/assignments")
        if (cached != null) paint(cached) else list.addView(loadingView())
        val be = backend()
        bg(onError = {
            if (cached == null) {
                list.removeAllViews()
                list.addView(errorCard(friendly(it)) { manageAssignments() })
            }
        }) {
            val data = be.get("/assignments", token)
            writeCache("/assignments", data)
            ui { paint(data) }
        }
    }

    private fun askDeleteAssignment(item: JSONObject) {
        val title = item.optString("title")
        android.app.AlertDialog.Builder(this)
            .setTitle("删除作业")
            .setMessage("删除「$title」后，大家的进度也会一起消失。")
            .setPositiveButton("删除") { _, _ ->
                val be = backend()
                bg(onError = { toast(friendly(it)) }) {
                    be.delete("/assignments/${item.getInt("id")}", token)
                    ui {
                        toast("已删除")
                        manageAssignments()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun manageRoster(id: Int) {
        onBack = { manageAssignments() }
        val content = ui.vertical()
        screen(ui.topBar("作业管理", onBack = { manageAssignments() }), content)

        fun draw(assignment: JSONObject, submissions: JSONArray) {
            if (!rosterEditing) {
                rosterMarks.clear()
                for (index in 0 until submissions.length()) {
                    val row = submissions.getJSONObject(index)
                    rosterMarks[row.getInt("id")] = row.optString("status") == "collected_by_monitor"
                }
                rosterReady = true
            }
            content.removeAllViews()
            content.addView(ui.text(assignmentTitle(assignment), 20f, Palette.ink, bold = true))
            content.addView(ui.secondaryButton("修改作业") { editAssignment(assignment) }, ui.lp(top = 12))
            val list = ui.card(8)
            for (index in 0 until submissions.length()) {
                list.addView(rosterRow(submissions.getJSONObject(index)))
                if (index < submissions.length() - 1) {
                    list.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
                }
            }
            content.addView(list, ui.lp(top = 12))
            if (!rosterEditing && role == "monitor") {
                val collectedCount = (0 until submissions.length()).count { submissions.getJSONObject(it).optString("status") == "collected_by_monitor" }
                val retrievedCount = (0 until submissions.length()).count { submissions.getJSONObject(it).optString("status") == "retrieved_by_monitor" }
                if (collectedCount > 0) {
                    content.addView(ui.primaryButton("全班取回") { shiftClass(id, "retrieve") }, ui.lp(top = 16))
                }
                if (retrievedCount > 0) {
                    content.addView(ui.primaryButton("全班分发") { shiftClass(id, "distribute") }, ui.lp(top = 12))
                }
            }
            if (rosterEditing) {
                content.addView(ui.primaryButton("确定") { commitRoster(id, submissions) }, ui.lp(top = 16))
            } else {
                content.addView(ui.secondaryButton("修改状态") {
                    rosterEditing = true
                    manageRoster(id)
                }, ui.lp(top = 16))
            }
        }

        val cachedAssignment = readCache("/assignments/$id")
        val cachedBoard = readCache("/assignments/$id/submissions")
        if (cachedAssignment != null && cachedBoard != null) {
            // 缓存可能还是去重前的数据，pending 交给网络回来后再补，避免同一个人出现两次
            draw(cachedAssignment, cachedBoard.getJSONArray("submissions"))
        } else {
            content.addView(loadingView())
        }
        val be = backend()
        bg(onError = {
            if (cachedAssignment == null || cachedBoard == null) {
                content.removeAllViews()
                content.addView(errorCard(friendly(it)) { manageRoster(id) })
            }
        }) {
            val assignment = be.get("/assignments/$id", token)
            val board = be.get("/assignments/$id/submissions", token)
            writeCache("/assignments/$id", assignment)
            writeCache("/assignments/$id/submissions", board)
            ui { draw(assignment, board.getJSONArray("submissions")) }
        }
    }

    private fun rosterRow(submission: JSONObject): View {
        val student = submission.getJSONObject("student")
        val id = submission.getInt("id")
        val collected = rosterMarks[id] == true
        val wasCollected = submission.optString("status") == "collected_by_monitor"
        val original = submission.optString("statusLabel")
        fun labelFor(on: Boolean) = if (on) "学委已揽收" else if (wasCollected) "未写完" else original
        val label = ui.text(labelFor(collected), 13f, Palette.muted)
        val row = ui.horizontal(
            face(student.optString("name"), student.optString("avatar"), 36),
            ui.vertical(
                nameWithRole(student.optString("name"), student.optString("role"), 16f, student.optBoolean("pending")),
                label,
            ).apply { setPadding(ui.dp(12), 0, 0, 0) },
        ).apply { setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(12)) }
        if (rosterEditing) {
            val dot = View(this).apply {
                background = ui.circle(
                    if (collected) Palette.ok else Color.TRANSPARENT,
                    if (collected) Palette.ok else Palette.faint,
                    2,
                )
                layoutParams = LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { marginEnd = ui.dp(8) }
            }
            row.addView(dot, 0)
            row.tag = collected
            row.setOnClickListener {
                val next = row.tag != true
                row.tag = next
                rosterMarks[id] = next
                label.text = labelFor(next)
                dot.background = ui.circle(
                    if (next) Palette.ok else Color.TRANSPARENT,
                    if (next) Palette.ok else Palette.faint,
                    2,
                )
            }
        }
        return row
    }

    private fun shiftClass(assignmentId: Int, step: String) {
        val be = backend()
        bg(onError = { toast(friendly(it)) }) {
            val result = be.post("/assignments/$assignmentId/$step", token, JSONObject())
            ui {
                toast("已更新 ${result.optInt("count")} 人")
                manageRoster(assignmentId)
            }
        }
    }

    private fun commitRoster(assignmentId: Int, submissions: JSONArray) {
        val be = backend()
        bg(onError = { toast(friendly(it)) }) {
            for (index in 0 until submissions.length()) {
                val row = submissions.getJSONObject(index)
                val id = row.getInt("id")
                val status = row.optString("status")
                if (status == "retrieved_by_monitor" || status == "distributed") continue
                val was = status == "collected_by_monitor"
                val now = rosterMarks[id] == true
                if (was == now) continue
                be.post("/submissions/$id/${if (now) "collect" else "revert"}", token, JSONObject().put("note", ""))
            }
            ui {
                rosterEditing = false
                rosterReady = false
                toast("已更新")
                manageRoster(assignmentId)
            }
        }
    }

    private fun publish() {
        onBack = { manageAssignments() }
        val content = ui.vertical()
        content.addView(loadingView())
        screen(ui.topBar("发布作业", onBack = { manageAssignments() }), content)
        val be = backend()
        bg(onError = { error ->
            ui {
                content.removeAllViews()
                content.addView(errorCard(friendly(error)) { publish() })
            }
        }) {
            val data = be.get("/subjects", token)
            ui {
                content.removeAllViews()
                content.addView(publishForm(data.optJSONArray("subjects") ?: JSONArray()))
            }
        }
    }

    private fun publishForm(subjects: JSONArray): View {
        val names = subjectNames(subjects)
        val card = ui.card(18)
        if (names.isEmpty()) {
            card.addView(ui.text("还没有科目。请先到个人页的科目管理里添加。", 15f, Palette.body))
            card.addView(ui.primaryButton("去添加科目") { subjectsPage() }, ui.lp(top = 16))
            return card
        }
        val spinner = subjectSpinner(names, names.first())
        val weekInput = ui.field("例如 3").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val description = ui.field("要求、页码、交法…", multiline = true)
        val deadline = deadlinePick("")
        card.addView(ui.label("科目"))
        card.addView(spinner, ui.lp(bottom = 14))
        card.addView(ui.label("周次"))
        card.addView(weekInput, ui.lp(bottom = 14))
        card.addView(ui.label("说明"))
        card.addView(description, ui.lp(bottom = 14))
        card.addView(ui.label("截止时间"))
        card.addView(deadline.view, ui.lp(top = 4))
        val photos = mutableListOf<ByteArray>()
        val preview = ui.horizontal()
        card.addView(ui.secondaryButton("添加图片") {
            if (photos.size >= 6) {
                toast("一次作业最多 6 张图片")
            } else pickImage { jpeg ->
                photos.add(jpeg)
                val thumb = ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = ui.rounded(Palette.field, 8)
                    clipToOutline = true
                    outlineProvider = ViewOutlineProvider.BACKGROUND
                }
                preview.addView(thumb, ui.lp(ui.dp(72), ui.dp(72), end = 8))
            }
        }, ui.lp(top = 12))
        card.addView(preview, ui.lp(top = 10))
        val be = backend()
        card.addView(ui.primaryButton("发布给全班") {
            val body = JSONObject()
                .put("title", spinner.selectedItem?.toString().orEmpty())
                .put("week", weekInput.text.toString())
                .put("description", description.text.toString())
                .put("deadline", deadline.value())
                .put("mode", "collect")
            bg(onError = { toast(friendly(it)) }) {
                val created = be.post("/assignments", token, body)
                val id = created.getInt("id")
                photos.forEach { jpeg ->
                    be.post(
                        "/assignments/$id/images",
                        token,
                        JSONObject().put("mime", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                    )
                }
                ui {
                    if (created.has("messageId")) noteSeen(created.getInt("messageId"))
                    toast("已发布")
                    manageAssignments()
                }
            }
        }, ui.lp(top = 18))
        return card
    }

    private fun editAssignment(assignment: JSONObject) {
        val id = assignment.getInt("id")
        onBack = { manageRoster(id) }
        val content = ui.vertical()
        content.addView(loadingView())
        screen(ui.topBar("修改作业", assignment.optString("title"), onBack = { manageRoster(id) }), content)
        val be = backend()
        bg(onError = { error ->
            ui {
                content.removeAllViews()
                content.addView(errorCard(friendly(error)) { editAssignment(assignment) })
            }
        }) {
            val data = be.get("/subjects", token)
            ui {
                content.removeAllViews()
                content.addView(editForm(assignment, data.optJSONArray("subjects") ?: JSONArray()))
            }
        }
    }

    private fun editForm(assignment: JSONObject, subjects: JSONArray): View {
        val id = assignment.getInt("id")
        val names = subjectNames(subjects)
        val current = assignment.optString("title")
        if (current.isNotBlank() && current !in names) names.add(0, current)
        val card = ui.card(18)
        if (names.isEmpty()) {
            card.addView(ui.text("还没有科目。请先到个人页的科目管理里添加。", 15f, Palette.body))
            card.addView(ui.primaryButton("去添加科目") { subjectsPage() }, ui.lp(top = 16))
            return card
        }
        val spinner = subjectSpinner(names, current.ifBlank { names.first() })
        val weekInput = ui.field("例如 3").apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(str(assignment, "week"))
        }
        val description = ui.field("要求、页码、交法…", multiline = true).apply { setText(str(assignment, "description")) }
        val deadline = deadlinePick(str(assignment, "deadline"))
        card.addView(ui.label("科目"))
        card.addView(spinner, ui.lp(bottom = 14))
        card.addView(ui.label("周次"))
        card.addView(weekInput, ui.lp(bottom = 14))
        card.addView(ui.label("说明"))
        card.addView(description, ui.lp(bottom = 14))
        card.addView(ui.label("截止时间"))
        card.addView(deadline.view, ui.lp(top = 4))
        val be = backend()
        card.addView(ui.primaryButton("保存修改") {
            val body = JSONObject()
                .put("title", spinner.selectedItem?.toString().orEmpty())
                .put("week", weekInput.text.toString())
                .put("description", description.text.toString())
                .put("deadline", deadline.value())
            bg(onError = { toast(friendly(it)) }) {
                be.patch("/assignments/$id", token, body)
                ui {
                    toast("已保存")
                    manageRoster(id)
                }
            }
        }, ui.lp(top = 18))
        return card
    }

    private fun subjectNames(subjects: JSONArray): MutableList<String> {
        val names = mutableListOf<String>()
        for (index in 0 until subjects.length()) {
            val name = subjects.getJSONObject(index).optString("name").trim()
            if (name.isNotBlank() && name !in names) names.add(name)
        }
        return names
    }

    private fun deadlinePick(raw: String): DeadlinePick {
        val parsed = runCatching { LocalDateTime.parse(deadlineDraft(raw)) }.getOrNull()
            ?: LocalDateTime.now().withHour(23).withMinute(59).withSecond(0).withNano(0)
        val nowYear = LocalDateTime.now().year
        val years = (minOf(nowYear, parsed.year)..maxOf(nowYear + 1, parsed.year)).map { "${it}年" }
        val months = (1..12).map { "${it}月" }
        val hours = (0..23).map { "${it}时" }
        val minutes = (0..59).map { "${it}分" }
        val yearSpinner = choiceSpinner(years, "${parsed.year}年")
        val monthSpinner = choiceSpinner(months, "${parsed.monthValue}月")
        val daySpinner = choiceSpinner(listOf("${parsed.dayOfMonth}日"), "${parsed.dayOfMonth}日")
        val hourSpinner = choiceSpinner(hours, "${parsed.hour}时")
        val minuteSpinner = choiceSpinner(minutes, "${parsed.minute}分")
        fun selectedNumber(spinner: Spinner): Int =
            spinner.selectedItem?.toString().orEmpty().dropLast(1).toIntOrNull() ?: 1
        fun refreshDays() {
            val max = YearMonth.of(selectedNumber(yearSpinner), selectedNumber(monthSpinner)).lengthOfMonth()
            val current = selectedNumber(daySpinner).coerceIn(1, max)
            daySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, (1..max).map { "${it}日" }).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            daySpinner.setSelection(current - 1)
        }
        val watch = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refreshDays()
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        yearSpinner.onItemSelectedListener = watch
        monthSpinner.onItemSelectedListener = watch
        refreshDays()
        val dateRow = ui.horizontal()
        listOf(yearSpinner, monthSpinner, daySpinner).forEach { dateRow.addView(it, ui.lp(0, WRAP_CONTENT, end = 6, weight = 1f)) }
        val timeRow = ui.horizontal()
        listOf(hourSpinner, minuteSpinner).forEach { timeRow.addView(it, ui.lp(0, WRAP_CONTENT, end = 6, weight = 1f)) }
        val box = ui.vertical(dateRow)
        box.addView(timeRow, ui.lp(top = 8))
        return DeadlinePick(box) {
            val month = selectedNumber(monthSpinner).toString().padStart(2, '0')
            val day = selectedNumber(daySpinner).toString().padStart(2, '0')
            val hour = selectedNumber(hourSpinner).toString().padStart(2, '0')
            val minute = selectedNumber(minuteSpinner).toString().padStart(2, '0')
            "${selectedNumber(yearSpinner)}-$month-${day}T$hour:$minute"
        }
    }

    private fun choiceSpinner(options: List<String>, selected: String): Spinner {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.background = ui.rounded(Palette.field, 12, Palette.line)
        spinner.minimumHeight = ui.dp(48)
        val index = options.indexOf(selected)
        if (index >= 0) spinner.setSelection(index)
        return spinner
    }

    private fun subjectSpinner(names: List<String>, selected: String): Spinner {
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        spinner.background = ui.rounded(Palette.field, 12, Palette.line)
        spinner.minimumHeight = ui.dp(48)
        val index = names.indexOf(selected)
        if (index >= 0) spinner.setSelection(index)
        return spinner
    }

    private fun subjectsPage() {
        onBack = { profile() }
        val list = ui.vertical()
        val bar = ui.topBar("科目管理", onBack = { profile() })
        val plus = ui.text("＋", 26f, Palette.ink).apply {
            setPadding(ui.dp(12), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        ui.clickable(plus, 20, ui.rounded(Color.TRANSPARENT, 20)) { askAddSubject() }
        (bar.getChildAt(0) as LinearLayout).addView(plus)
        screen(bar, list)

        fun paint(data: JSONObject) {
            val subjects = data.optJSONArray("subjects") ?: JSONArray()
            list.removeAllViews()
            if (subjects.length() == 0) {
                list.addView(emptyCard("还没有科目"))
                return
            }
            val card = ui.card(4)
            for (index in 0 until subjects.length()) {
                val subject = subjects.getJSONObject(index)
                val row = ui.horizontal(
                    ui.text(subject.optString("name"), 16f, Palette.ink, bold = true).apply {
                        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    },
                    ui.text("删除", 14f, Palette.error).apply {
                        setPadding(ui.dp(12), ui.dp(8), ui.dp(4), ui.dp(8))
                        setOnClickListener { askDeleteSubject(subject) }
                    },
                ).apply { setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(12)) }
                card.addView(row)
                if (index < subjects.length() - 1) {
                    card.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1))
                }
            }
            list.addView(card)
        }

        val be = backend()
        list.addView(loadingView())
        bg(onError = { error ->
            ui {
                list.removeAllViews()
                list.addView(errorCard(friendly(error)) { subjectsPage() })
            }
        }) {
            val data = be.get("/subjects", token)
            ui { paint(data) }
        }
    }

    private fun askAddSubject() {
        val name = ui.field("例如：高等数学")
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        box.addView(name)
        android.app.AlertDialog.Builder(this)
            .setTitle("添加科目")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val be = backend()
                bg(onError = { toast(friendly(it)) }) {
                    be.post("/subjects", token, JSONObject().put("name", name.text.toString()))
                    ui {
                        toast("已添加")
                        subjectsPage()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askDeleteSubject(subject: JSONObject) {
        val name = subject.optString("name")
        android.app.AlertDialog.Builder(this)
            .setTitle("删除科目")
            .setMessage("删除「$name」后，发布作业时不能再选它。已经发出的作业还在。")
            .setPositiveButton("删除") { _, _ ->
                val be = backend()
                bg(onError = { toast(friendly(it)) }) {
                    be.delete("/subjects/${subject.optLong("id")}", token)
                    ui {
                        toast("已删除")
                        subjectsPage()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun members() {
        onBack = { profile() }
        val list = ui.vertical()
        val bar = ui.topBar("成员管理", onBack = { profile() })
        val plus = ui.text("＋", 26f, Palette.ink).apply {
            setPadding(ui.dp(12), ui.dp(2), ui.dp(8), ui.dp(2))
        }
        ui.clickable(plus, 20, ui.rounded(Color.TRANSPARENT, 20)) { askAddMember() }
        (bar.getChildAt(0) as LinearLayout).addView(plus)
        screen(bar, list)

        fun paint(data: JSONObject) {
            val members = data.getJSONArray("members")
            list.removeAllViews()
            list.addView(ui.section("成员"))
            val card = ui.card(4)
            for (index in 0 until members.length()) {
                val member = members.getJSONObject(index)
                val memberRole = member.optString("role")
                val row = ui.horizontal(
                    face(member.optString("name"), member.optString("avatar"), 36),
                    ui.vertical(
                        nameWithRole(member.optString("name"), memberRole, 15f),
                        ui.text(member.optString("studentNo"), 12f, Palette.muted),
                    ).apply { setPadding(ui.dp(12), 0, 0, 0) },
                ).apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) }
                card.addView(row)
                if (memberRole != "monitor") {
                    val actions = ui.horizontal(
                        ui.linkButton("编辑", Palette.accent) {
                            askEditSeat(
                                member.optLong("seatId"),
                                member.optString("name"),
                                member.optString("studentNo"),
                            )
                        },
                        ui.linkButton("删除", Palette.error) { askRemoveMember(member) },
                    ).apply { setPadding(ui.dp(60), 0, ui.dp(8), ui.dp(4)) }
                    card.addView(actions)
                }
                if (index < members.length() - 1) {
                    card.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply {
                        marginStart = ui.dp(60)
                    })
                }
            }
            list.addView(card)

            val pending = data.optJSONArray("pending")
            if (pending != null && pending.length() > 0) {
                list.addView(
                    ui.text("未认领的席位", 13f, Palette.muted).apply {
                        setPadding(ui.dp(4), ui.dp(18), ui.dp(4), ui.dp(6))
                    },
                )
                val pendingCard = ui.card(4)
                for (i in 0 until pending.length()) {
                    val p = pending.getJSONObject(i)
                    pendingCard.addView(
                        ui.horizontal(
                            ui.vertical(
                                ui.text(p.optString("name"), 15f, Palette.ink, bold = true),
                                ui.text(p.optString("studentNo"), 12f, Palette.muted).apply {
                                    setPadding(0, ui.dp(2), 0, 0)
                                },
                            ).apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) },
                            ui.text("编辑", 13f, Palette.accent).apply {
                                setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6))
                                setOnClickListener {
                                    askEditSeat(p.optLong("id"), p.optString("name"), p.optString("studentNo"))
                                }
                            },
                            ui.text("移除", 13f, Palette.error).apply {
                                setPadding(ui.dp(10), ui.dp(6), ui.dp(4), ui.dp(6))
                                setOnClickListener { askRemoveRoster(p.optLong("id"), p.optString("name")) }
                            },
                        ).apply { setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)) },
                    )
                    if (i < pending.length() - 1) {
                        pendingCard.addView(
                            View(this).apply { setBackgroundColor(Palette.line) },
                            LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = ui.dp(12) },
                        )
                    }
                }
                list.addView(pendingCard)
            }

            // 解散班级放在成员管理最下面：这是管整个班的事，不属于个人设置
            list.addView(ui.section("危险操作"))
            val danger = ui.card(16)
            danger.addView(
                actionRow("trash", "解散班级", "只有班里只剩你一个人时才能解散", Palette.error) { askDissolveClass() },
            )
            danger.addView(
                ui.text(
                    "会删除本班的作业、评论和消息，删掉就恢复不了。解散后你会回到加入班级页，可以重建或用别的班名重新加入。",
                    12f,
                    Palette.muted,
                ),
                ui.lp(top = 6),
            )
            list.addView(danger)
        }

        val cached = readCache("/members")
        if (cached != null) paint(cached) else list.addView(loadingView())
        val be = backend()
        bg(onError = {
            if (cached == null) {
                list.removeAllViews()
                list.addView(errorCard(friendly(it)) { members() })
            }
        }) {
            val data = be.get("/members", token)
            writeCache("/members", data)
            ui { paint(data) }
        }
    }

    private fun askRemoveRoster(id: Long, name: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("从名单移除")
            .setMessage("移除「$name」后，对方就不能用这个姓名和学号加入了。")
            .setPositiveButton("移除") { _, _ ->
                bg(onError = { toast(friendly(it)) }) {
                    backend().delete("/roster/$id", token)
                    ui {
                        toast("已移除")
                        members()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 学委把一名同学录进名单：对方还没账号，只用姓名 + 学号占位 */
    private fun askAddMember() {
        val name = ui.field("姓名")
        val studentNo = ui.field("学号")
        val box = ui.vertical()
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4))
        box.addView(name, ui.lp(bottom = 10))
        box.addView(studentNo)
        android.app.AlertDialog.Builder(this)
            .setTitle("添加到名单")
            .setMessage("对方用自己的手机号登录，填写这个姓名和学号就能加入班级。")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val n = name.text.toString().trim()
                val no = studentNo.text.toString().trim()
                if (n.isEmpty() || no.isEmpty()) {
                    toast("姓名和学号都要填")
                    return@setPositiveButton
                }
                bg(onError = { toast(friendly(it)) }) {
                    backend().post("/roster", token, JSONObject().put("name", n).put("studentNo", no))
                    ui {
                        toast("已加入名单")
                        members()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 学委编辑席位：改的是席位上的姓名和学号，已认领的账号跟着一起改 */
    private fun askEditSeat(seatId: Long, currentName: String, currentNo: String) {
        if (seatId <= 0) {
            toast("这个席位读不出来，刷新一下再试")
            return
        }
        val name = ui.field("姓名").apply { setText(currentName) }
        val studentNo = ui.field("学号").apply { setText(currentNo) }
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        box.addView(name, ui.lp(bottom = 10))
        box.addView(studentNo)
        android.app.AlertDialog.Builder(this)
            .setTitle("编辑席位")
            .setMessage("改的是这个席位的姓名和学号。已经认领它的账号会跟着改名，作业和评论的署名也一起变。")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val n = name.text.toString().trim()
                val no = studentNo.text.toString().trim()
                if (n.isEmpty() || no.isEmpty()) {
                    toast("姓名和学号都要填")
                    return@setPositiveButton
                }
                bg(onError = { toast(friendly(it)) }) {
                    backend().patch("/seats/$seatId", token, JSONObject().put("name", n).put("studentNo", no))
                    ui {
                        toast("已保存")
                        clearPageCache()
                        members()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askEditMember(id: String, currentName: String, currentNo: String, self: Boolean, then: () -> Unit) {
        val name = ui.field("姓名").apply { setText(currentName) }
        val studentNo = ui.field("学号").apply { setText(currentNo) }
        val box = ui.vertical().apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        box.addView(name, ui.lp(bottom = 10))
        box.addView(studentNo)
        android.app.AlertDialog.Builder(this)
            .setTitle(if (self) "修改资料" else "修改成员")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val be = backend()
                bg(onError = { toast(friendly(it)) }) {
                    val user = be.patch(
                        "/members/$id",
                        token,
                        JSONObject().put("name", name.text.toString()).put("studentNo", studentNo.text.toString()),
                    ).getJSONObject("user")
                    ui {
                        if (self) applyUser(user)
                        toast("已保存")
                        then()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 转让学委：列的是**席位**，包括还没绑手机的空席位 */
    private fun transferList() {
        onBack = { profile() }
        val list = ui.vertical()
        screen(ui.topBar("转让学委", onBack = { profile() }), list)

        fun seatRow(name: String, studentNo: String, avatar: String, pending: Boolean, onClick: () -> Unit): View {
            val row = ui.horizontal(
                if (pending) glyphBadge("person", Palette.line, 36, Palette.muted) else face(name, avatar, 36),
                ui.vertical(
                    nameWithRole(name, "student", 16f, pending = pending),
                    ui.text(studentNo, 12f, Palette.muted).apply { setPadding(0, ui.dp(2), 0, 0) },
                ).apply { setPadding(ui.dp(12), 0, 0, 0) },
                ui.spacer(),
            ).apply { setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)) }
            ui.clickable(row, 12, ui.rounded(Color.WHITE, 12), onClick)
            return row
        }

        fun paint(data: JSONObject) {
            list.removeAllViews()
            list.addView(
                ui.text("点一位同学，学委就转给他；也可以转给还没认领的席位，等他加入时自动成为学委。", 13f, Palette.muted),
                ui.lp(bottom = 12),
            )
            val members = data.getJSONArray("members")
            val card = ui.card(4)
            var count = 0
            for (index in 0 until members.length()) {
                val member = members.getJSONObject(index)
                if (member.optString("role") == "monitor") continue
                if (count > 0) {
                    card.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = ui.dp(62) })
                }
                card.addView(
                    seatRow(member.optString("name"), member.optString("studentNo"), member.optString("avatar"), false) {
                        askTransfer(member.optLong("seatId"), member.optString("name"), false)
                    },
                )
                count++
            }
            val pending = data.optJSONArray("pending")
            if (pending != null) {
                for (index in 0 until pending.length()) {
                    val seat = pending.getJSONObject(index)
                    if (count > 0) {
                        card.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = ui.dp(62) })
                    }
                    card.addView(
                        seatRow(seat.optString("name"), seat.optString("studentNo"), "", true) {
                            askTransfer(seat.optLong("id"), seat.optString("name"), true)
                        },
                    )
                    count++
                }
            }
            if (count == 0) list.addView(emptyCard("班里还没有别的席位")) else list.addView(card)
        }

        val cached = readCache("/members")
        if (cached != null) paint(cached) else list.addView(loadingView())
        val be = backend()
        bg(onError = {
            if (cached == null) {
                list.removeAllViews()
                list.addView(errorCard(friendly(it)) { transferList() })
            }
        }) {
            val data = be.get("/members", token)
            writeCache("/members", data)
            ui { paint(data) }
        }
    }

    private fun askTransfer(seatId: Long, name: String, pending: Boolean) {
        if (seatId <= 0) {
            toast("这个席位读不出来，刷新一下再试")
            return
        }
        val input = ui.field("我已确认转让")
        val box = ui.vertical(input).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        val note = if (pending) {
            "「$name」这个席位还没人认领。学委先转到席位上，等他加入时自动成为学委；在那之前班里没人能发布和揽收作业。"
        } else {
            "转给「$name」后，你变成普通同学，作业和成员管理都交给他。"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("转让学委")
            .setMessage("$note\n\n请在输入框中输入“我已确认转让”")
            .setView(box)
            .setPositiveButton("转让") { _, _ ->
                if (input.text.toString() != "我已确认转让") {
                    toast("请输入“我已确认转让”")
                    return@setPositiveButton
                }
                val be = backend()
                bg(onError = { toast(friendly(it)) }) {
                    val user = be.post(
                        "/seats/$seatId/transfer",
                        token,
                        JSONObject().put("phrase", input.text.toString()),
                    ).getJSONObject("user")
                    ui {
                        applyUser(user)
                        clearPageCache()
                        toast(if (pending) "已转给「$name」的席位" else "已转给 $name")
                        profile()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askRemoveMember(member: JSONObject) {
        val name = member.optString("name")
        android.app.AlertDialog.Builder(this)
            .setTitle("删除成员")
            .setMessage("删除「$name」后，这个人的作业进度也会一起消失。对方可以重新登录并填写学号加入。")
            .setPositiveButton("删除") { _, _ ->
                val be = backend()
                bg(onError = { toast(friendly(it)) }) {
                    be.delete("/members/${member.optString("id")}", token)
                    ui {
                        toast("已删除")
                        members()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addMember(nameInput: EditText, noInput: EditText, password: EditText, memberRole: String) {
        val be = backend()
        val body = JSONObject()
            .put("name", nameInput.text.toString())
            .put("studentNo", noInput.text.toString())
            .put("password", password.text.toString())
            .put("role", memberRole)
        bg(onError = { toast(friendly(it)) }) {
            be.post("/members", token, body)
            ui {
                toast("已添加")
                members()
            }
        }
    }

    /** Embedded knowledge site maintained by the previous class monitor. */
    private fun knowledge() {
        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = userAgentString.replace("; wv", "")
        }
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(web, true)
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                val scheme = request.url.scheme.orEmpty()
                return scheme != "http" && scheme != "https"
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        val leave = { if (web.canGoBack()) web.goBack() else profile() }
        onBack = leave
        val bar = ui.topBar("班级知识库", onBack = leave)
        val root = ui.vertical().apply { setBackgroundColor(Palette.card) }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(2)))
        root.addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        // 离开页面时释放 WebView，否则每次进入都会残留一个原生实例
        web.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                runCatching {
                    v.removeOnAttachStateChangeListener(this)
                    web.stopLoading()
                    web.destroy()
                }
            }
        })
        lightStatusBar(true)
        applyInsets(root, bar)
        setContentView(root)
        web.loadUrl("https://ehuster.com")
    }

    /** 学委在个人页发布一条系统通知，写进「系统通知」会话 */
    private fun askSystemMessage() {
        val input = ui.field("输入要通知全班的内容", multiline = true)
        val wrap = FrameLayout(this).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) }
        wrap.addView(input, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        android.app.AlertDialog.Builder(this)
            .setTitle("发布系统消息")
            .setView(wrap)
            .setPositiveButton("发布") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    toast("请填写内容")
                    return@setPositiveButton
                }
                bg(onError = { toast(friendly(it)) }) {
                    backend().post("/chat/system", token, JSONObject().put("body", text))
                    ui { toast("已发布到系统通知") }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 应用内更新 ----------

    /** 网关要求所有地址带 sharecode，否则返回 403 */
    private fun withShare(url: String): String {
        if (url.contains("sharecode=")) return url
        return url + (if (url.contains("?")) "&" else "?") + "sharecode=3NIEKUN3F6"
    }

    /** 拉 version.json 比对版本；silent=true 时没有新版就不打扰 */
    private fun checkUpdate(silent: Boolean = true) {
        bg(onError = { if (!silent) toast("检查更新失败：${friendly(it)}") }) {
            val raw = java.net.URL(withShare("${baseUrl()}/version.json")).openStream().use { it.readBytes() }
            val info = JSONObject(raw.toString(Charsets.UTF_8))
            val latest = info.optInt("versionCode")
            @Suppress("DEPRECATION")
            val current = packageManager.getPackageInfo(packageName, 0).versionCode
            ui {
                if (latest <= current) {
                    if (!silent) toast("已经是最新版本")
                    return@ui
                }
                android.app.AlertDialog.Builder(this)
                    .setTitle("发现新版本 ${info.optString("versionName")}")
                    .setMessage(info.optString("notes").ifBlank { "建议更新到最新版本。" })
                    .setPositiveButton("立即更新") { _, _ -> downloadUpdate(info) }
                    .setNegativeButton("以后再说", null)
                    .show()
            }
        }
    }

    /**
     * 下载更新包。必须校验拿到的是不是完整 APK：
     * 网关/网络中途断掉时会给你半截字节或一个 HTML 页面，直接丢给系统安装就会报「解析软件包时出现问题」。
     * 所以下完先让 PackageManager 解析一遍包名和版本号，不合格就重下；三次都失败就引导去浏览器。
     */
    private fun downloadUpdate(info: JSONObject, install: Boolean = true) {
        val apk = info.optString("apk").ifBlank { "app-release.apk" }
        val url = withShare(if (apk.startsWith("http")) apk else "${baseUrl()}/${apk.removePrefix("./")}")
        val expected = info.optInt("versionCode")
        toast("正在下载更新…")
        bg(onError = {
            ui { offerBrowserDownload(friendly(it)) }
        }) {
            val file = File(cacheDir, "update.apk")
            var problem = ""
            var good = false
            for (attempt in 1..3) {
                runCatching {
                    val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout = 15000
                    connection.readTimeout = 60000
                    connection.instanceFollowRedirects = true
                    val declared = connection.contentLengthLong
                    val body = connection.inputStream.use { it.readBytes() }
                    connection.disconnect()
                    android.util.Log.i("Homework", "update bytes=${body.size} declared=$declared attempt=$attempt")
                    if (body.size < 100_000) {
                        problem = "拿到的不是安装包（${body.size} 字节）"
                        return@runCatching
                    }
                    if (declared > 0 && body.size.toLong() != declared) {
                        problem = "下载不完整（${body.size}/$declared 字节）"
                        return@runCatching
                    }
                    file.writeBytes(body)
                    val parsed = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                    if (parsed == null || parsed.packageName != packageName) {
                        problem = "安装包无法解析"
                        return@runCatching
                    }
                    @Suppress("DEPRECATION")
                    val code = if (Build.VERSION.SDK_INT >= 28) parsed.longVersionCode.toInt() else parsed.versionCode
                    if (expected > 0 && code != expected) {
                        problem = "版本对不上（包里是 $code）"
                        return@runCatching
                    }
                    good = true
                }.onFailure { problem = "网络中断（${it.message ?: "未知"}）" }
                if (good) break
            }
            if (!good) {
                runCatching { file.delete() }
                throw IllegalStateException(problem.ifBlank { "下载失败" })
            }
            ui {
                if (install) {
                    installApk(file)
                } else {
                    toast("下载完成：${file.length()} 字节")
                }
            }
        }
    }

    /** 应用内下载反复失败时的退路：把下载页丢给浏览器 */
    private fun offerBrowserDownload(reason: String) {
        val page = withShare("${baseUrl()}/download.html")
        android.app.AlertDialog.Builder(this)
            .setTitle("更新包没下完整")
            .setMessage("$reason\n\n可以直接用浏览器下载安装包，装好之后再回来。")
            .setPositiveButton("用浏览器打开") { _, _ ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(page)))
                }.onFailure { toast("没有可用的浏览器") }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            toast("请先允许「安装未知应用」，再来一次")
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:$packageName"),
                    ),
                )
            }
            return
        }
        runCatching {
            val installer = packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("app.apk", 0, file.length()).use { out ->
                    file.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val callback = Intent(this, InstallResultReceiver::class.java)
                // Android 14 起，安装会话的状态接收器必须是 mutable 的 PendingIntent，
                // 用 FLAG_IMMUTABLE 会直接抛 "The commit() status receiver should come from a mutable PendingIntent"。
                // 这里是显式 Intent（指向本应用的接收器），用 mutable 不会违反 Android 12 的隐式 Intent 限制。
                val mutable = if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0
                val pending = android.app.PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    callback,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or mutable,
                )
                session.commit(pending.intentSender)
            }
            toast("已交给系统安装")
        }.onFailure { toast("安装失败：${it.message ?: ""}") }
    }

    /** 学委解散班级：只在班里只剩自己时允许，完成后回到加入班级页 */
    private fun askDissolveClass() {
        android.app.AlertDialog.Builder(this)
            .setTitle("解散班级")
            .setMessage("会删除本班的作业、评论和消息，且只有班里只剩你一个人时才允许。解散后可以重新填写班级。")
            .setPositiveButton("解散") { _, _ ->
                bg(onError = { toast(friendly(it)) }) {
                    val be = backend()
                    be.post("/class/dissolve", token, JSONObject())
                    // 解散会把整班档案一起删掉（包括自己），这里补一个新的空档案，
                    // 别把人甩回网页登录页——账号还在，只是没班了
                    val me = runCatching { be.get("/me", token).getJSONObject("user") }
                        .getOrElse { be.post("/account/init", token, JSONObject()).getJSONObject("user") }
                    ui {
                        toast("班级已解散")
                        applyUser(me)
                        className = ""
                        clearPageCache()
                        home()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun profile() {
        onBack = null
        lightStatusBar(true)
        val pageBg = Color.parseColor("#EDEDED")
        val header = ui.horizontal(
            face(userName, avatarPath, 64).apply { setOnClickListener { changeAvatar { profile() } } },
            ui.vertical(
                nameWithRole(userName, role, 22f).apply {
                    setOnClickListener { askEditMember(userId, userName, studentNo, true) { profile() } }
                },
                ui.text(studentNo, 14f, Palette.muted).apply { setPadding(0, ui.dp(6), 0, 0) },
            ).apply { setPadding(ui.dp(16), 0, 0, 0) },
        ).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(28))
            gravity = Gravity.CENTER_VERTICAL
        }
        val page = ui.vertical(header)
        page.addView(View(this).apply { setBackgroundColor(pageBg) }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(8)))
        val menu = ui.vertical()
        if (role == "monitor") {
            menu.addView(menuRow("work", "作业管理") { manageAssignments() })
            menu.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = ui.dp(56) })
            menu.addView(menuRow("subject", "科目管理") { subjectsPage() })
            menu.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = ui.dp(56) })
            menu.addView(menuRow("transfer", "转让学委") { transferList() })
            menu.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = ui.dp(56) })
            menu.addView(menuRow("people", "成员管理") { members() })
            menu.addView(View(this).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = ui.dp(56) })
            menu.addView(menuRow("bell", "发布系统消息") { askSystemMessage() })
        }
        if (menu.childCount > 0) {
            menu.addView(
                View(this).apply { setBackgroundColor(Palette.line) },
                LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = ui.dp(56) },
            )
        }
        menu.addView(menuRow("book", "班级知识库") { knowledge() })
        page.addView(menu)
        page.addView(View(this).apply { setBackgroundColor(pageBg) }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(8)))
        page.addView(menuRow("shield", "账号与安全") { accountSecurity() })
        page.addView(View(this).apply { setBackgroundColor(pageBg) }, LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(8)))
        page.addView(menuRow("work", "检查更新", Palette.body) { checkUpdate(silent = false) })
        page.addView(menuRow("logout", "退出登录", Palette.error) { logout() })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(pageBg)
            addView(page)
        }
        val root = ui.vertical().apply { setBackgroundColor(pageBg) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(dock("me"), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        applyInsets(root, header)
        setContentView(root)
    }

    // ---------- 账号与安全 ----------

    /**
     * 账号与安全：退出班级、注销账号。
     * 退出班级只断开「手机号账号 ↔ 班级席位」的连接；席位和作业进度归学委管，不跟着人走。
     */
    private fun accountSecurity() {
        onBack = { profile() }
        val content = ui.vertical()

        val classValue = ui.text(
            className.ifBlank { if (myClassId == 0L) "未加入" else "已加入" },
            14f,
            Palette.ink,
        )
        val info = ui.card(18)
        info.addView(infoRow("姓名", userName.ifBlank { "未填写" }))
        info.addView(infoRow("学号", studentNo.ifBlank { "未填写" }))
        info.addView(
            ui.horizontal(ui.text("班级", 14f, Palette.muted), ui.spacer(), classValue)
                .apply { setPadding(0, ui.dp(7), 0, ui.dp(7)) },
        )
        content.addView(ui.section("账号"))
        content.addView(info)

        content.addView(ui.section("班级"))
        val classCard = ui.card(16)
        classCard.addView(
            actionRow(
                "leave",
                "退出班级",
                if (role == "monitor") "学委席位会留着，回来还能认领" else "断开账号和班级席位的连接",
                Palette.warn,
            ) { askLeaveClass() },
        )
        classCard.addView(
            ui.text(
                if (role == "monitor") {
                    "退出后回到加入班级页。学委席位变成「没人绑定」，班里暂时没人能发布作业或揽收；用同样的姓名和学号加回来就恢复学委。作业和进度都留在班里。"
                } else {
                    "退出后回到加入班级页。学委那边你的席位会恢复成「未认领」，已经交上去的作业进度留在班级里，凭姓名和学号随时可以加回来。"
                },
                12f,
                Palette.muted,
            ),
            ui.lp(top = 6),
        )
        content.addView(classCard)

        content.addView(ui.section("危险操作"))
        val dangerCard = ui.card(16)
        dangerCard.addView(actionRow("trash", "注销账号", "删除你的姓名、学号和头像", Palette.error) { askDeleteAccount() })
        dangerCard.addView(
            ui.text(
                "注销后本机退出登录，可以用同一手机号重新登录，得到一个全新的空账号。已经交给学委的作业进度、发过的评论和私聊会留在班级里，只是不再显示你的名字。",
                12f,
                Palette.muted,
            ),
            ui.lp(top = 6),
        )
        content.addView(dangerCard)
        content.addView(ui.text("想退出登录，回「个人」页。", 12f, Palette.faint), ui.lp(top = 18))

        screen(ui.topBar("账号与安全", onBack = { profile() }), content)

        if (className.isBlank() && myClassId != 0L) {
            bg {
                val name = str(backend().get("/bootstrap", token), "className")
                ui {
                    if (name.isNotBlank()) {
                        className = name
                        classValue.text = name
                    }
                }
            }
        }
    }

    private fun infoRow(label: String, value: String): View =
        ui.horizontal(ui.text(label, 14f, Palette.muted), ui.spacer(), ui.text(value, 14f, Palette.ink))
            .apply { setPadding(0, ui.dp(7), 0, ui.dp(7)) }

    /** 带说明文字的菜单行，说明放在标题下面，账号与安全页用 */
    private fun actionRow(kind: String, title: String, subtitle: String, color: Int, onClick: () -> Unit): View {
        val row = ui.horizontal(
            MenuGlyph(this, kind).apply {
                this.color = color
                layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(26))
            },
            ui.vertical(
                ui.text(title, 16f, color),
                ui.text(subtitle, 12f, Palette.muted).apply { setPadding(0, ui.dp(3), 0, 0) },
            ).apply { setPadding(ui.dp(14), 0, ui.dp(8), 0) },
            ui.spacer(),
            ui.text("›", 18f, Palette.faint),
        ).apply { setPadding(ui.dp(2), ui.dp(10), ui.dp(2), ui.dp(10)) }
        ui.clickable(row, 12, android.graphics.drawable.ColorDrawable(Color.WHITE), onClick)
        return row
    }

    private fun askLeaveClass() {
        if (myClassId == 0L) {
            toast("你还没有加入班级")
            return
        }
        val where = if (className.isBlank()) "这个班" else "「$className」"
        val extra = if (role == "monitor") {
            "\n\n学委席位会留在这个班、只是暂时没人绑定，所以班里暂时没人能发布作业和揽收。用同样的姓名和学号加回来，学委身份就回到你身上。"
        } else {
            "\n\n学委那边你的席位会恢复成「未认领」，已经交上去的作业进度留在班级里。凭姓名和学号随时可以加回来。"
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("退出班级")
            .setMessage("退出${where}后，你会回到加入班级页。$extra")
            .setPositiveButton("退出") { _, _ ->
                bg(onError = { toast(friendly(it)) }) {
                    val be = backend()
                    be.post("/class/leave", token, JSONObject())
                    val user = be.get("/me", token).getJSONObject("user")
                    ui {
                        applyUser(user)
                        className = ""
                        clearPageCache()
                        toast("已退出班级")
                        home()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun askDeleteAccount() {
        val input = ui.field("在此输入「注销」")
        val box = ui.vertical(input).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(4)) }
        val monitorNote = if (role == "monitor" && myClassId != 0L) {
            "\n\n你是学委：注销后学委席位仍然留在这个班，只是没人绑定，班里暂时没人管作业。重新注册后用同样的姓名和学号加回来，就能拿回学委。"
        } else {
            ""
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("注销账号")
            .setMessage("会删除你的姓名、学号和头像，本机退出登录。\n\n已经交给学委的作业进度、发过的评论和私聊会留在班级里，不再显示你的名字。注销后可以用同一手机号重新登录，得到一个全新的空账号。$monitorNote\n\n确认请在下面输入「注销」。")
            .setView(box)
            .setPositiveButton("注销") { _, _ ->
                if (input.text.toString().trim() != "注销") {
                    toast("请输入「注销」")
                    return@setPositiveButton
                }
                bg(onError = { toast(friendly(it)) }) {
                    backend().post("/account/delete", token, JSONObject())
                    ui {
                        toast("账号已注销")
                        logout()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun changeAvatar(then: () -> Unit) {
        pickImage { jpeg -> uploadAvatar(jpeg, then) }
    }

    private fun uploadAvatar(jpeg: ByteArray, then: () -> Unit) {
        val body = JSONObject()
            .put("mime", "image/jpeg")
            .put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        bg(onError = { toast(friendly(it)) }) {
            val user = backend().post("/me/avatar", token, body).getJSONObject("user")
            ui {
                applyUser(user)
                then()
            }
        }
    }

    private fun nameWithRole(name: String, memberRole: String, size: Float, pending: Boolean = false): View {
        val row = ui.horizontal(ui.text(name, size, Palette.ink, bold = true))
        when {
            pending -> row.addView(ui.text("未加入", 11f, Palette.warn, bold = true).apply {
                background = ui.rounded(Palette.warnSoft, 6)
                setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1))
            }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 6))

            memberRole == "monitor" -> row.addView(ui.text("学委", 11f, Palette.accentDark, bold = true).apply {
                background = ui.rounded(Palette.accentSoft, 6)
                setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1))
            }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 6))
        }
        return row
    }

    /** 席位的名字 + 徽章：学委徽章跟席位走，空席位也要能看出学委挂在哪儿 */
    private fun nameWithSeat(name: String, memberRole: String, claimed: Boolean, size: Float): View {
        val row = ui.horizontal(ui.text(name, size, Palette.ink, bold = true))
        if (memberRole == "monitor") {
            row.addView(ui.text("学委", 11f, Palette.accentDark, bold = true).apply {
                background = ui.rounded(Palette.accentSoft, 6)
                setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1))
            }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 6))
        }
        if (!claimed) {
            row.addView(ui.text("未认领", 11f, Palette.warn, bold = true).apply {
                background = ui.rounded(Palette.warnSoft, 6)
                setPadding(ui.dp(6), ui.dp(1), ui.dp(6), ui.dp(1))
            }, ui.lp(WRAP_CONTENT, WRAP_CONTENT, start = 6))
        }
        return row
    }

    private fun menuRow(kind: String, title: String, titleColor: Int = Palette.ink, onClick: () -> Unit): View {
        val icon = MenuGlyph(this, kind).apply {
            color = titleColor
            layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(26))
        }
        val row = ui.horizontal(
            icon,
            ui.text(title, 16f, titleColor).apply { setPadding(ui.dp(14), 0, 0, 0) },
            ui.spacer(),
            ui.text("›", 18f, Palette.faint),
        ).apply {
            setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14))
            minimumHeight = ui.dp(56)
        }
        ui.clickable(row, 0, android.graphics.drawable.ColorDrawable(Color.WHITE), onClick)
        return row
    }

    // ---------- 消息 / 班级空间 / 会话 ----------

    private fun messages() {
        onBack = null
        // 进了消息页就算看过了：不然底部那个红点永远消不掉
        noteSeen(latestMessageId)
        val list = ui.vertical()
        screen(ui.topBar("消息"), list, "message")

        fun paint(data: JSONObject) {
            val threads = data.getJSONArray("threads")
            list.removeAllViews()
            list.addView(classSpaceEntry(), ui.lp(bottom = 14))
            list.addView(ui.section("会话"))
            if (threads.length() == 0) {
                list.addView(ui.text("还没有会话，去班级空间找同学聊聊", 13f, Palette.muted), ui.lp(top = 2, bottom = 12))
            }
            for (index in 0 until threads.length()) {
                list.addView(threadRow(threads.getJSONObject(index)), ui.lp(bottom = 8))
            }
        }

        val cached = readCache("/chat/threads")
        if (cached != null) paint(cached) else list.addView(loadingView())
        bg(onError = {
            if (cached == null) {
                list.removeAllViews()
                list.addView(errorCard(friendly(it)) { messages() })
            }
        }) {
            val data = backend().get("/chat/threads", token)
            writeCache("/chat/threads", data)
            ui { paint(data) }
        }
    }

    /** 实心圆 + 居中白色线条图标，用在班级空间和系统通知这类"功能会话"上 */
    private fun glyphBadge(kind: String, bg: Int, sizeDp: Int = 44, fg: Int = Color.WHITE): View {
        val badge = FrameLayout(this)
        badge.layoutParams = LinearLayout.LayoutParams(ui.dp(sizeDp), ui.dp(sizeDp))
        badge.background = ui.circle(bg)
        val inner = ui.dp((sizeDp * 0.54f).toInt())
        badge.addView(MenuGlyph(this, kind).apply { color = fg }, FrameLayout.LayoutParams(inner, inner, Gravity.CENTER))
        return badge
    }

    /** Fixed entry at the top of the message page. */
    private fun classSpaceEntry(): View {
        val row = ui.horizontal(
            glyphBadge("people", Palette.accent, 44),
            ui.vertical(
                ui.text("班级空间", 16f, Palette.ink, bold = true),
                ui.text("查看班级成员，点 ＋ 发起会话", 12f, Palette.muted).apply { setPadding(0, ui.dp(2), 0, 0) },
            ).apply { setPadding(ui.dp(12), 0, 0, 0) },
            ui.spacer(),
            ui.text("›", 18f, Palette.faint),
        ).apply {
            setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            elevation = ui.dp(2).toFloat()
        }
        ui.clickable(row, 16, ui.rounded(Color.WHITE, 16)) { classSpace() }
        return row
    }

    private fun threadRow(thread: JSONObject): View {
        val isSystem = thread.optString("kind") == "system"
        val title = thread.optString("title").ifBlank { if (isSystem) "系统通知" else "会话" }
        val avatarView = if (isSystem) {
            glyphBadge("bell", Palette.accentDark, 44)
        } else {
            face(title, thread.optString("peerAvatar"), 44)
        }
        val last = thread.optString("lastBody").ifBlank { "暂无消息" }
        val row = ui.horizontal(
            avatarView,
            ui.vertical(
                ui.text(title, 16f, Palette.ink, bold = true).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                ui.text(last, 13f, Palette.muted).apply {
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding(0, ui.dp(3), 0, 0)
                },
            ).apply { setPadding(ui.dp(12), 0, ui.dp(8), 0) },
            ui.spacer(),
            ui.text(formatTime(thread.optString("lastAt")), 11f, Palette.faint),
        ).apply {
            setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14))
            elevation = ui.dp(2).toFloat()
        }
        val id = thread.optLong("id")
        ui.clickable(row, 16, ui.rounded(Color.WHITE, 16)) { chatThread(id, title, isSystem) }
        if (!isSystem) {
            row.setOnLongClickListener {
                confirmDeleteThread(id, title)
                true
            }
        }
        return row
    }

    private fun confirmDeleteThread(threadId: Long, title: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("删除会话")
            .setMessage("和「$title」的会话及全部消息都会删除，不能恢复。")
            .setPositiveButton("删除") { _, _ ->
                bg(onError = { toast(friendly(it)) }) {
                    backend().delete("/chat/threads/$threadId", token)
                    ui {
                        toast("已删除")
                        messages()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun classSpace() {
        val list = ui.vertical()
        screen(ui.topBar("班级空间", onBack = { messages() }), list, "message")

        fun paint(data: JSONObject) {
            // 老缓存里没有 seats，用 members 兜一下
            val seats = data.optJSONArray("seats") ?: JSONArray().apply {
                val old = data.optJSONArray("members") ?: JSONArray()
                for (i in 0 until old.length()) put(JSONObject(old.getJSONObject(i).toString()).put("claimed", true))
            }
            val items = (0 until seats.length()).map { seats.getJSONObject(it) }
                .sortedWith(compareBy({ if (it.optString("role") == "monitor") 0 else 1 }, { it.optLong("seatId") }))
            list.removeAllViews()
            list.addView(
                ui.text("这里是本班的所有席位。空着的席位就是还没人认领的位置，学委挂在哪个席位上看徽章。", 13f, Palette.muted),
                ui.lp(bottom = 12),
            )
            for (seat in items) {
                val name = seat.optString("name")
                val claimed = seat.optBoolean("claimed", true)
                val mine = seat.optBoolean("self")
                val row = ui.horizontal(
                    if (claimed) {
                        face(name, seat.optString("avatar"), 42)
                    } else {
                        glyphBadge("person", Palette.line, 42, Palette.faint)
                    },
                    ui.vertical(
                        nameWithSeat(name, seat.optString("role"), claimed, 15f),
                        ui.text(seat.optString("studentNo"), 12f, Palette.muted).apply { setPadding(0, ui.dp(2), 0, 0) },
                    ).apply { setPadding(ui.dp(12), 0, 0, 0) },
                    ui.spacer(),
                    when {
                        mine -> ui.text("我", 12f, Palette.faint).apply { setPadding(ui.dp(6), 0, ui.dp(6), 0) }
                        !claimed -> ui.text("—", 14f, Palette.faint).apply { setPadding(ui.dp(6), 0, ui.dp(10), 0) }
                        else -> ui.roundAction("+", Palette.accent, Palette.accentSoft, 36) {
                            startThread(seat.optString("id"), name)
                        }
                    },
                ).apply {
                    setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
                    background = ui.rounded(Color.WHITE, 16)
                    elevation = ui.dp(2).toFloat()
                }
                list.addView(row, ui.lp(bottom = 8))
            }
        }

        val cached = readCache("/chat/members")
        if (cached != null) paint(cached) else list.addView(loadingView())
        bg(onError = {
            if (cached == null) {
                list.removeAllViews()
                list.addView(errorCard(friendly(it)) { classSpace() })
            }
        }) {
            val data = backend().get("/chat/members", token)
            writeCache("/chat/members", data)
            ui { paint(data) }
        }
    }

    private fun startThread(peerId: String, name: String) {
        bg(onError = { toast(friendly(it)) }) {
            val data = backend().post("/chat/threads", token, JSONObject().put("peerId", peerId))
            val thread = data.getJSONObject("thread")
            val title = thread.optString("title").ifBlank { name }
            ui { chatThread(thread.optLong("id"), title) }
        }
    }

    private fun chatThread(threadId: Long, title: String, readOnly: Boolean = false) {
        // 点开任何一个会话也算看过了
        noteSeen(latestMessageId)
        val list = ui.vertical()
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        scroll.addView(list)
        list.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(16))
        val bar = ui.topBar(
            title,
            onBack = { messages() },
            actions = listOf("刷新" to { loadChat(threadId, list, scroll) }),
        )

        val root = ui.vertical().apply { setBackgroundColor(Palette.bg) }
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (!readOnly) {
            val composer = ui.composer(
                "说点什么…",
                onPickImage = { pickImage { jpeg -> sendChatImage(threadId, jpeg, list, scroll) } },
                onSend = { text -> sendChatText(threadId, text, list, scroll) },
            )
            root.addView(
                View(this).apply { setBackgroundColor(Palette.line) },
                LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(1)),
            )
            root.addView(composer, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        lightStatusBar(true)
        applyInsets(root, bar)
        setContentView(root)
        loadChat(threadId, list, scroll)
    }

    private fun loadChat(threadId: Long, list: LinearLayout, scroll: ScrollView) {
        bg(onError = {
            ui {
                list.removeAllViews()
                list.addView(
                    ui.text("加载失败：${friendly(it)}", 13f, Palette.error),
                    ui.lp(top = 16),
                )
            }
        }) {
            val data = backend().get("/chat/threads/$threadId/messages", token)
            ui { paintChat(data, threadId, list, scroll) }
        }
    }

    private fun paintChat(data: JSONObject, threadId: Long, list: LinearLayout, scroll: ScrollView) {
        try {
            val rows = data.getJSONArray("messages")
            list.removeAllViews()
            if (rows.length() == 0) {
                list.addView(
                    ui.text("还没有消息，打个招呼吧", 13f, Palette.muted).apply { gravity = Gravity.CENTER_HORIZONTAL },
                    ui.lp(top = 24),
                )
            }
            for (index in 0 until rows.length()) {
                list.addView(chatBubble(rows.getJSONObject(index), threadId, list, scroll), ui.lp(bottom = 8))
            }
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        } catch (error: Exception) {
            list.removeAllViews()
            list.addView(
                ui.text("渲染失败：${error.message ?: error.javaClass.simpleName}", 13f, Palette.error),
                ui.lp(top = 16),
            )
        }
    }

    private fun chatBubble(message: JSONObject, threadId: Long, list: LinearLayout, scroll: ScrollView): View {
        val mine = message.optBoolean("mine")
        if (message.optBoolean("revoked")) {
            val tip = ui.text(if (mine) "你撤回了一条消息" else "对方撤回了一条消息", 12f, Palette.faint).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(8), 0, ui.dp(8))
            }
            return ui.vertical(tip)
        }
        val body = message.optString("body")
        val images = message.optJSONArray("images")
        val bubble = ui.vertical()
        if (body.isNotBlank()) bubble.addView(ui.text(body, 15f, if (mine) Color.WHITE else Palette.ink))
        if (images != null && images.length() > 0) {
            val strip = photoStrip(images)
            if (strip != null) bubble.addView(strip, ui.lp(top = if (body.isBlank()) 0 else 8))
        }
        bubble.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10))
        if (mine) {
            bubble.background = ui.ripple(ui.rounded(Palette.accent, 16), 16)
            bubble.isClickable = true
            bubble.setOnLongClickListener {
                messageActions(message, threadId, list, scroll)
                true
            }
        } else {
            bubble.background = ui.rounded(Palette.card, 16)
        }
        val meta = ui.text(formatTime(message.optString("createdAt")), 11f, Palette.faint).apply {
            setPadding(ui.dp(6), ui.dp(4), ui.dp(6), 0)
        }
        val column = ui.vertical().apply { gravity = if (mine) Gravity.END else Gravity.START }
        column.addView(bubble, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
        })
        column.addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = if (mine) Gravity.END else Gravity.START
        })
        val row = ui.horizontal()
        if (mine) {
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 0.2f))
            row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f))
        } else {
            row.addView(column, LinearLayout.LayoutParams(0, WRAP_CONTENT, 0.8f))
            row.addView(View(this), LinearLayout.LayoutParams(0, 1, 0.2f))
        }
        return row
    }

    /** Long-press menu: copy / revoke (keeps a placeholder) / delete (removes the row). */
    private fun messageActions(message: JSONObject, threadId: Long, list: LinearLayout, scroll: ScrollView) {
        android.app.AlertDialog.Builder(this)
            .setTitle("消息操作")
            .setItems(arrayOf("复制", "撤回", "删除")) { _, which ->
                when (which) {
                    0 -> copyText(message.optString("body"))
                    1 -> revokeChat(message, threadId, list, scroll)
                    else -> removeChat(message, threadId, list, scroll)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyText(text: String) {
        if (text.isBlank()) {
            toast("这条消息没有文字")
            return
        }
        val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("消息", text))
        toast("已复制")
    }

    private fun revokeChat(message: JSONObject, threadId: Long, list: LinearLayout, scroll: ScrollView) {
        val id = message.optLong("id")
        bg(onError = { toast(friendly(it)) }) {
            backend().post("/chat/messages/$id/revoke", token, JSONObject())
            ui {
                toast("已撤回")
                loadChat(threadId, list, scroll)
            }
        }
    }

    private fun removeChat(message: JSONObject, threadId: Long, list: LinearLayout, scroll: ScrollView) {
        val id = message.optLong("id")
        bg(onError = { toast(friendly(it)) }) {
            backend().delete("/chat/messages/$id", token)
            ui {
                toast("已删除")
                loadChat(threadId, list, scroll)
            }
        }
    }

    private fun sendChatText(threadId: Long, text: String, list: LinearLayout, scroll: ScrollView) {
        bg(onError = { toast(friendly(it)) }) {
            backend().post("/chat/threads/$threadId/messages", token, JSONObject().put("body", text))
            ui { loadChat(threadId, list, scroll) }
        }
    }

    private fun sendChatImage(threadId: Long, jpeg: ByteArray, list: LinearLayout, scroll: ScrollView) {
        bg(onError = { toast(friendly(it)) }) {
            val created = backend().post("/chat/threads/$threadId/messages", token, JSONObject().put("body", ""))
            val messageId = created.getJSONObject("message").optLong("id")
            val uploaded = runCatching {
                backend().post(
                    "/chat/messages/$messageId/images",
                    token,
                    JSONObject().put("mime", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)),
                )
            }
            if (uploaded.isFailure) {
                // 图片没传上去就把这条空消息撤掉，避免留下一个空气泡
                runCatching { backend().delete("/chat/messages/$messageId", token) }
                throw IllegalStateException("图片没有传上去")
            }
            ui {
                toast("图片已发送")
                loadChat(threadId, list, scroll)
            }
        }
    }

    private fun noteSeen(id: Int) {
        if (id > prefs.getInt("seen_message", 0)) prefs.edit().putInt("seen_message", id).apply()
    }

    private fun startMessagePoll() {
        if (pollingMessages) return
        pollingMessages = true
        messagePoll.post { checkMessages() }
    }

    private fun stopMessagePoll() {
        pollingMessages = false
        messagePoll.removeCallbacksAndMessages(null)
    }

    private fun checkMessages() {
        if (!pollingMessages || token.isEmpty()) return
        val be = backend()
        bg(onError = { messagePoll.postDelayed({ checkMessages() }, 8000) }) {
            val data = be.get("/messages", token)
            writeCache("/messages", data)
            val rows = data.getJSONArray("messages")
            val items = (0 until rows.length()).map { rows.getJSONObject(it) }
            val maxId = items.maxOfOrNull { it.getInt("id") } ?: 0
            ui {
                if (!pollingMessages) return@ui
                latestMessageId = maxId
                if (!prefs.contains("seen_message")) noteSeen(maxId)
                // 人就在消息页上，新消息等同已读，别让红点又冒出来
                if (currentTab == "message") noteSeen(maxId)
                MessageAlerts.publish(this, data)
                messagePoll.postDelayed({ checkMessages() }, 8000)
            }
        }
    }

    private fun dock(tab: String): View {
        val bar = ui.horizontal().apply {
            setBackgroundColor(Color.WHITE)
            setPadding(0, ui.dp(4), 0, ui.dp(2))
        }
        bar.addView(dockItem("home", "主页", tab == "home") { if (tab != "home") home() }, ui.lp(0, WRAP_CONTENT, weight = 1f))
        bar.addView(dockItem("message", "消息", tab == "message") { if (tab != "message") messages() }, ui.lp(0, WRAP_CONTENT, weight = 1f))
        bar.addView(dockItem("me", "个人", tab == "me") { if (tab != "me") profile() }, ui.lp(0, WRAP_CONTENT, weight = 1f))
        val wrap = ui.vertical(View(this).apply { setBackgroundColor(Palette.line) }, bar)
        wrap.getChildAt(0).layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, ui.dp(1))
        wrap.setBackgroundColor(Color.WHITE)
        return wrap
    }

    private fun dockItem(kind: String, label: String, selected: Boolean, onClick: () -> Unit): View {
        val color = if (selected) Color.parseColor("#07C160") else Color.parseColor("#181818")
        val glyph = TabIcon(this, kind).apply { this.color = color }
        val icon: View = if (kind == "message" && latestMessageId > prefs.getInt("seen_message", 0)) {
            FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply { gravity = Gravity.CENTER_HORIZONTAL }
                addView(glyph, FrameLayout.LayoutParams(ui.dp(26), ui.dp(26)))
                addView(
                    View(this@MainActivity).apply { background = ui.circle(Palette.error) },
                    FrameLayout.LayoutParams(ui.dp(8), ui.dp(8), Gravity.TOP or Gravity.END),
                )
            }
        } else {
            glyph.apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(26), ui.dp(26)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            }
        }
        val item = ui.vertical(
            icon,
            ui.text(label, 10f, color).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(2), 0, 0)
            },
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        ui.clickable(item, 0, android.graphics.drawable.ColorDrawable(Color.TRANSPARENT), onClick)
        return item
    }

    // ---------- shared pieces ----------

    private fun loadingView() = ui.text("加载中…", 14f, Palette.muted).apply {
        gravity = Gravity.CENTER
        setPadding(0, ui.dp(32), 0, ui.dp(32))
    }

    private fun emptyCard(message: String) = ui.card(20).apply {
        addView(ui.text(message, 14f, Palette.muted).apply { gravity = Gravity.CENTER })
    }

    private fun errorCard(message: String, retry: () -> Unit) = ui.card(18).apply {
        background = ui.rounded(Palette.errorSoft, 16, Color.parseColor("#FECACA"))
        addView(ui.text(message, 14f, Palette.error))
        addView(ui.secondaryButton("重试", Palette.error, retry), ui.lp(WRAP_CONTENT, WRAP_CONTENT, top = 12))
    }

    private fun screen(topBar: View, content: View, tab: String? = null) {
        lightStatusBar(true)
        currentTab = tab
        val root = ui.vertical().apply { setBackgroundColor(Palette.bg) }
        root.addView(topBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val inner = ui.vertical(content).apply { setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(28)) }
        scroll.addView(inner)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        if (tab != null) root.addView(dock(tab), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        applyInsets(root, topBar)
        setContentView(root)
    }

    private fun lightStatusBar(darkIcons: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val bits = (if (darkIcons) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0) or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                bits,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        // Touch decorView first: before setContentView the decor may not exist yet,
        // and getInsetsController() would throw on some Android versions.
        window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            )
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        window.statusBarColor = Color.TRANSPARENT
    }

    /** Pads the top bar below the status bar and the page above the nav bar / keyboard. */
    @Suppress("DEPRECATION")
    private fun applyInsets(root: View, top: View) {
        val baseTop = top.paddingTop
        val baseBottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val statusTop: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                statusTop = bars.top
                bottom = maxOf(bars.bottom, ime.bottom)
            } else {
                    statusTop = insets.systemWindowInsetTop
                    bottom = insets.systemWindowInsetBottom
            }
            top.setPadding(top.paddingLeft, baseTop + statusTop, top.paddingRight, top.paddingBottom)
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, baseBottom + bottom)
            insets
        }
        root.requestApplyInsets()
    }

    private fun cacheKey(path: String) = "page:${tokenKey()}:$path"

    private fun readCache(path: String): JSONObject? {
        val raw = prefs.getString(cacheKey(path), null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun writeCache(path: String, json: JSONObject) {
        val text = json.toString()
        if (text.length > 300_000) return
        prefs.edit().putString(cacheKey(path), text).apply()
    }

    private fun clearPageCache() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("page:") }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun bg(onError: ((Exception) -> Unit)? = null, block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (error: Exception) {
                ui { if (onError != null) onError(error) else toast(friendly(error)) }
            }
        }
    }

    private fun ui(block: () -> Unit) {
        if (isDestroyed) return
        runOnUiThread { if (!isDestroyed) block() }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun str(obj: JSONObject, key: String) = if (obj.isNull(key)) "" else obj.optString(key)

    private fun friendly(error: Exception) =
        if (!isDemo() && isConnectionFailure(error)) "连不上服务器\n${baseUrl()}" else error.message?.takeIf { it.isNotBlank() } ?: "出了点问题"

    private fun isConnectionFailure(error: Exception): Boolean {
        if (error is IOException) return true
        val message = error.message.orEmpty()
        return listOf("Failed to connect", "Unable to resolve", "UnknownHost", "ECONNREFUSED", "ETIMEDOUT", "timeout")
            .any { message.contains(it, ignoreCase = true) }
    }

    private val shortFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")

    private fun formatTime(iso: String): String {
        if (iso.isBlank()) return ""
        return runCatching { Instant.parse(iso).atZone(ZoneId.systemDefault()).format(shortFormat) }.getOrElse { iso }
    }

    private fun weekText(week: String): String {
        val text = week.trim()
        if (text.isBlank() || text == "null") return ""
        return if (text.all { it.isDigit() }) "第${text}周" else text
    }

    private fun assignmentTitle(item: JSONObject): String {
        val week = weekText(str(item, "week"))
        val title = item.optString("title")
        return if (week.isBlank()) title else "$title（$week）"
    }

    private fun deadlineDraft(raw: String): String {
        if (raw.isBlank() || raw == "null") return ""
        val pattern = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        return runCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).format(pattern) }
            .recoverCatching { LocalDateTime.parse(raw).format(pattern) }
            .getOrElse { raw.take(16) }
    }

    private fun formatDeadline(raw: String): String {
        if (raw.isBlank()) return "未设置"
        return runCatching { LocalDateTime.parse(raw).format(shortFormat) }
            .recoverCatching { Instant.parse(raw).atZone(ZoneId.systemDefault()).format(shortFormat) }
            .getOrElse { raw }
    }
}

/** Bottom-tab glyph: a house for 主页, a person for 个人. */
private class TabIcon(context: Context, private val kind: String) : View(context) {
    var color: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val size = width.coerceAtMost(height).toFloat()
        if (size <= 0f) return
        paint.color = color
        paint.strokeWidth = size * 0.075f
        when (kind) {
            "home" -> drawHome(canvas, size)
            "message" -> drawMessage(canvas, size)
            else -> drawPerson(canvas, size)
        }
    }

    private fun drawHome(canvas: Canvas, size: Float) {
        val roof = Path().apply {
            moveTo(size * 0.16f, size * 0.46f)
            lineTo(size * 0.50f, size * 0.16f)
            lineTo(size * 0.84f, size * 0.46f)
        }
        canvas.drawPath(roof, paint)
        canvas.drawRoundRect(size * 0.27f, size * 0.44f, size * 0.73f, size * 0.86f, size * 0.04f, size * 0.04f, paint)
    }

    private fun drawMessage(canvas: Canvas, size: Float) {
        canvas.drawRoundRect(size * 0.14f, size * 0.18f, size * 0.86f, size * 0.68f, size * 0.12f, size * 0.12f, paint)
        val tail = Path().apply {
            moveTo(size * 0.28f, size * 0.64f)
            lineTo(size * 0.24f, size * 0.86f)
            lineTo(size * 0.48f, size * 0.66f)
        }
        canvas.drawPath(tail, paint)
    }

    private fun drawPerson(canvas: Canvas, size: Float) {
        canvas.drawCircle(size * 0.50f, size * 0.32f, size * 0.15f, paint)
        val body = Path().apply {
            moveTo(size * 0.20f, size * 0.88f)
            quadTo(size * 0.50f, size * 0.50f, size * 0.80f, size * 0.88f)
        }
        canvas.drawPath(body, paint)
    }
}

/** Profile-menu glyphs, same line style as the dock icons. */
private class DeadlinePick(val view: View, private val read: () -> String) {
    fun value(): String = read()
}

private class MenuGlyph(context: Context, private val kind: String) : View(context) {
    var color: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val size = width.coerceAtMost(height).toFloat()
        if (size <= 0f) return
        paint.color = color
        paint.strokeWidth = size * 0.07f
        when (kind) {
            "work" -> drawWork(canvas, size)
            "subject" -> drawSubject(canvas, size)
            "book" -> drawBook(canvas, size)
            "bell" -> drawBell(canvas, size)
            "person" -> drawPerson(canvas, size)
            "people" -> drawPeople(canvas, size)
            "transfer" -> drawTransfer(canvas, size)
            "shield" -> drawShield(canvas, size)
            "leave" -> drawLeave(canvas, size)
            "trash" -> drawTrash(canvas, size)
            else -> drawLogout(canvas, size)
        }
    }

    /** 默认头像：单人剪影 */
    private fun drawPerson(canvas: Canvas, size: Float) {
        canvas.drawCircle(size * 0.50f, size * 0.32f, size * 0.155f, paint)
        val body = Path().apply {
            moveTo(size * 0.20f, size * 0.88f)
            quadTo(size * 0.50f, size * 0.50f, size * 0.80f, size * 0.88f)
        }
        canvas.drawPath(body, paint)
    }

    /** 系统通知：铃铛 */
    private fun drawBell(canvas: Canvas, size: Float) {
        val body = Path().apply {
            moveTo(size * 0.22f, size * 0.68f)
            lineTo(size * 0.30f, size * 0.60f)
            lineTo(size * 0.30f, size * 0.42f)
            quadTo(size * 0.30f, size * 0.20f, size * 0.50f, size * 0.20f)
            quadTo(size * 0.70f, size * 0.20f, size * 0.70f, size * 0.42f)
            lineTo(size * 0.70f, size * 0.60f)
            lineTo(size * 0.78f, size * 0.68f)
            close()
        }
        canvas.drawPath(body, paint)
        val clapper = Path().apply {
            moveTo(size * 0.42f, size * 0.76f)
            quadTo(size * 0.50f, size * 0.88f, size * 0.58f, size * 0.76f)
        }
        canvas.drawPath(clapper, paint)
    }

    /** 科目管理：书签 */
    private fun drawSubject(canvas: Canvas, size: Float) {
        val tag = Path().apply {
            moveTo(size * 0.26f, size * 0.16f)
            lineTo(size * 0.74f, size * 0.16f)
            lineTo(size * 0.74f, size * 0.86f)
            lineTo(size * 0.50f, size * 0.64f)
            lineTo(size * 0.26f, size * 0.86f)
            close()
        }
        canvas.drawPath(tag, paint)
    }

    /** 班级知识库：打开的书 */
    private fun drawBook(canvas: Canvas, size: Float) {
        val left = Path().apply {
            moveTo(size * 0.50f, size * 0.28f)
            quadTo(size * 0.33f, size * 0.18f, size * 0.16f, size * 0.24f)
            lineTo(size * 0.16f, size * 0.78f)
            quadTo(size * 0.33f, size * 0.72f, size * 0.50f, size * 0.82f)
        }
        canvas.drawPath(left, paint)
        val right = Path().apply {
            moveTo(size * 0.50f, size * 0.28f)
            quadTo(size * 0.67f, size * 0.18f, size * 0.84f, size * 0.24f)
            lineTo(size * 0.84f, size * 0.78f)
            quadTo(size * 0.67f, size * 0.72f, size * 0.50f, size * 0.82f)
        }
        canvas.drawPath(right, paint)
        canvas.drawLine(size * 0.50f, size * 0.28f, size * 0.50f, size * 0.82f, paint)
    }

    private fun drawWork(canvas: Canvas, size: Float) {
        canvas.drawRoundRect(size * 0.24f, size * 0.22f, size * 0.76f, size * 0.84f, size * 0.06f, size * 0.06f, paint)
        canvas.drawRoundRect(size * 0.38f, size * 0.12f, size * 0.62f, size * 0.28f, size * 0.04f, size * 0.04f, paint)
        canvas.drawLine(size * 0.36f, size * 0.48f, size * 0.64f, size * 0.48f, paint)
        canvas.drawLine(size * 0.36f, size * 0.64f, size * 0.56f, size * 0.64f, paint)
    }

    private fun drawPeople(canvas: Canvas, size: Float) {
        canvas.drawCircle(size * 0.38f, size * 0.32f, size * 0.11f, paint)
        val front = Path().apply {
            moveTo(size * 0.16f, size * 0.78f)
            quadTo(size * 0.38f, size * 0.48f, size * 0.60f, size * 0.78f)
        }
        canvas.drawPath(front, paint)
        canvas.drawCircle(size * 0.66f, size * 0.36f, size * 0.09f, paint)
        val back = Path().apply {
            moveTo(size * 0.52f, size * 0.78f)
            quadTo(size * 0.68f, size * 0.54f, size * 0.86f, size * 0.78f)
        }
        canvas.drawPath(back, paint)
    }

    private fun drawTransfer(canvas: Canvas, size: Float) {
        canvas.drawLine(size * 0.18f, size * 0.34f, size * 0.72f, size * 0.34f, paint)
        val right = Path().apply {
            moveTo(size * 0.58f, size * 0.20f)
            lineTo(size * 0.80f, size * 0.34f)
            lineTo(size * 0.58f, size * 0.48f)
        }
        canvas.drawPath(right, paint)
        canvas.drawLine(size * 0.82f, size * 0.66f, size * 0.28f, size * 0.66f, paint)
        val left = Path().apply {
            moveTo(size * 0.42f, size * 0.52f)
            lineTo(size * 0.20f, size * 0.66f)
            lineTo(size * 0.42f, size * 0.80f)
        }
        canvas.drawPath(left, paint)
    }

    /** 账号与安全：盾牌，里面一道勾 */
    private fun drawShield(canvas: Canvas, size: Float) {
        val shield = Path().apply {
            moveTo(size * 0.50f, size * 0.14f)
            lineTo(size * 0.80f, size * 0.26f)
            lineTo(size * 0.80f, size * 0.50f)
            quadTo(size * 0.80f, size * 0.74f, size * 0.50f, size * 0.88f)
            quadTo(size * 0.20f, size * 0.74f, size * 0.20f, size * 0.50f)
            lineTo(size * 0.20f, size * 0.26f)
            close()
        }
        canvas.drawPath(shield, paint)
        val tick = Path().apply {
            moveTo(size * 0.36f, size * 0.50f)
            lineTo(size * 0.47f, size * 0.61f)
            lineTo(size * 0.65f, size * 0.41f)
        }
        canvas.drawPath(tick, paint)
    }

    /** 退出班级：一个方框（班级）加一支向外的箭头 */
    private fun drawLeave(canvas: Canvas, size: Float) {
        canvas.drawRoundRect(size * 0.14f, size * 0.22f, size * 0.56f, size * 0.78f, size * 0.08f, size * 0.08f, paint)
        canvas.drawLine(size * 0.44f, size * 0.50f, size * 0.84f, size * 0.50f, paint)
        val head = Path().apply {
            moveTo(size * 0.70f, size * 0.36f)
            lineTo(size * 0.86f, size * 0.50f)
            lineTo(size * 0.70f, size * 0.64f)
        }
        canvas.drawPath(head, paint)
    }

    /** 注销账号：垃圾桶 */
    private fun drawTrash(canvas: Canvas, size: Float) {
        canvas.drawLine(size * 0.40f, size * 0.16f, size * 0.60f, size * 0.16f, paint)
        canvas.drawLine(size * 0.18f, size * 0.28f, size * 0.82f, size * 0.28f, paint)
        canvas.drawRoundRect(size * 0.29f, size * 0.36f, size * 0.71f, size * 0.86f, size * 0.07f, size * 0.07f, paint)
        canvas.drawLine(size * 0.43f, size * 0.48f, size * 0.43f, size * 0.74f, paint)
        canvas.drawLine(size * 0.57f, size * 0.48f, size * 0.57f, size * 0.74f, paint)
    }

    private fun drawLogout(canvas: Canvas, size: Float) {
        val door = Path().apply {
            moveTo(size * 0.42f, size * 0.18f)
            lineTo(size * 0.22f, size * 0.18f)
            lineTo(size * 0.22f, size * 0.82f)
            lineTo(size * 0.42f, size * 0.82f)
        }
        canvas.drawPath(door, paint)
        canvas.drawLine(size * 0.42f, size * 0.50f, size * 0.78f, size * 0.50f, paint)
        val head = Path().apply {
            moveTo(size * 0.64f, size * 0.36f)
            lineTo(size * 0.82f, size * 0.50f)
            lineTo(size * 0.64f, size * 0.64f)
        }
        canvas.drawPath(head, paint)
    }
}
