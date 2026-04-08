package com.gpsbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import java.util.Locale
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var txtLocation: TextView
    private lateinit var btnCheckIP: Button

    private lateinit var locationManager: LocationManager
    private var currentLat: Double = 0.0
    private var currentLng: Double = 0.0
    private var hasLocation = false

    private val PERMISSION_REQUEST_CODE = 1001
    private val HOME_URL = "https://App.alpha-ecc.com"

    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadFixedLocation()
        setupFileChooser()
        setupWebView()
        setupNavigation()
        setupFixedLocationDialog()
        requestLocationPermission()

        webView.loadUrl(HOME_URL)

        // Check update silent (delay 2s de khong block UI)
        updateChecker = UpdateChecker(this)
        webView.postDelayed({
            updateChecker?.checkForUpdate(silent = true)
        }, 2000)
    }

    private var updateChecker: UpdateChecker? = null

    // ========== Fixed location (custom coordinates) ==========

    private var useFixedLocation = false

    private fun loadFixedLocation() {
        val prefs = getSharedPreferences("gpsbrowser", Context.MODE_PRIVATE)
        // Mac dinh BAT toa do co dinh lan dau
        if (!prefs.contains("use_fixed")) {
            prefs.edit()
                .putBoolean("use_fixed", true)
                .putFloat("fixed_lat", 10.404246840349387f)
                .putFloat("fixed_lng", 107.11750153452158f)
                .apply()
        }
        useFixedLocation = prefs.getBoolean("use_fixed", false)
        if (useFixedLocation) {
            currentLat = prefs.getFloat("fixed_lat", 10.404246840349387f).toDouble()
            currentLng = prefs.getFloat("fixed_lng", 107.11750153452158f).toDouble()
            hasLocation = true
        }
    }

    private fun setupFixedLocationDialog() {
        if (useFixedLocation) {
            txtLocation.text = "📌 ${String.format(Locale.US, "%.6f", currentLat)}, ${String.format(Locale.US, "%.6f", currentLng)} (cố định)"
        }
        txtLocation.setOnLongClickListener {
            showFixedLocationDialog()
            true
        }
    }

    private fun showFixedLocationDialog() {
        val input = EditText(this)
        input.hint = "VD: 21.028511, 105.804817 (để trống = dùng GPS thật)"
        val prefs = getSharedPreferences("gpsbrowser", Context.MODE_PRIVATE)
        if (useFixedLocation) {
            input.setText("$currentLat, $currentLng")
        }
        AlertDialog.Builder(this)
            .setTitle("Đặt toạ độ cố định")
            .setMessage("Dán toạ độ Google Maps (lat, lng).\nĐể trống → dùng GPS thật.")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isEmpty()) {
                    useFixedLocation = false
                    prefs.edit().putBoolean("use_fixed", false).apply()
                    Toast.makeText(this, "Đã tắt toạ độ cố định, dùng GPS thật", Toast.LENGTH_SHORT).show()
                } else {
                    try {
                        val parts = text.replace(" ", "").split(",")
                        val lat = parts[0].toDouble()
                        val lng = parts[1].toDouble()
                        currentLat = lat
                        currentLng = lng
                        hasLocation = true
                        useFixedLocation = true
                        prefs.edit()
                            .putBoolean("use_fixed", true)
                            .putFloat("fixed_lat", lat.toFloat())
                            .putFloat("fixed_lng", lng.toFloat())
                            .apply()
                        txtLocation.text = "📌 ${String.format(Locale.US, "%.6f", lat)}, ${String.format(Locale.US, "%.6f", lng)} (cố định)"
                        injectGeolocationOverride()
                        webView.reload()
                        Toast.makeText(this, "Đã đặt toạ độ cố định", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Sai định dạng. VD: 21.028511, 105.804817", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Huỷ", null)
            .show()
    }

    private fun initViews() {
        webView = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progressBar)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnHome = findViewById(R.id.btnHome)
        txtLocation = findViewById(R.id.txtLocation)
        btnCheckIP = findViewById(R.id.btnCheckIP)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setGeolocationEnabled(true)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowFileAccess = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.mediaPlaybackRequiresUserGesture = false

        // User-Agent giả Chrome Mobile
        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-G991B) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.6099.144 " +
                "Mobile Safari/537.36"

        // Cookie manager - giữ session đăng nhập
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // WebViewClient
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
                url?.let { urlBar.setText(it) }
                // Inject SOM (truoc khi web JS chay) - quan trong cho fetch/XHR override
                injectGeolocationOverride()
                injectFetchInterceptor()
                blockWebRTCLeak()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                updateNavButtons()
                // Inject lai sau khi load xong (phong khi web reset)
                injectGeolocationOverride()
                injectFetchInterceptor()
                blockWebRTCLeak()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                // Cac scheme khac (tel, mailto, intent...) -> mo app ngoai
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.w("GPSBrowser", "Cannot handle scheme: $url", e)
                }
                return true
            }

            // Intercept IP geolocation APIs -> tra ve toa do gia khop GPS
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!hasLocation) return null

                // Bypass: check IP page cua chinh app dung header dac biet de XEM IP THAT
                val bypassHeader = request.requestHeaders?.get("X-GPSBrowser-Bypass")
                if (bypassHeader == "1") return null

                val lat = currentLat
                val lng = currentLng

                // Universal IP geolocation API intercept
                // Bat ca URL chua tu khoa "ip", "geo", "location" + ".com/.io/.net..."
                val lowUrl = url.lowercase()
                val isIpApi = (
                    lowUrl.contains("api.ipbase.com") ||
                    lowUrl.contains("ip-api.com") ||
                    lowUrl.contains("ipapi.co") ||
                    lowUrl.contains("ipwho.is") ||
                    lowUrl.contains("ipwhois.app") ||
                    lowUrl.contains("freeipapi.com") ||
                    lowUrl.contains("ipinfo.io") ||
                    lowUrl.contains("ipgeolocation.io") ||
                    lowUrl.contains("ipify.org") ||
                    lowUrl.contains("ipdata.co") ||
                    lowUrl.contains("ipstack.com") ||
                    lowUrl.contains("geoip-db.com") ||
                    lowUrl.contains("geojs.io") ||
                    lowUrl.contains("db-ip.com") ||
                    lowUrl.contains("ipregistry.co") ||
                    lowUrl.contains("iplocation.net") ||
                    lowUrl.contains("ip2location.io") ||
                    lowUrl.contains("ipgeo.io") ||
                    lowUrl.contains("api.country.is") ||
                    lowUrl.contains("findip.net") ||
                    // Pattern regex CHAT - chi match /ip /geoip /ipgeo (KHONG match /location, /geocode...)
                    // Phai co json + path khop chat + KHONG phai alpha-ecc
                    (!lowUrl.contains("alpha-ecc") && lowUrl.contains("json") &&
                        Regex("""[/.](ip|geoip|ipgeo)[/.?]""").containsMatchIn(lowUrl))
                )

                if (isIpApi) {
                    // Universal JSON containing TAT CA field name pho bien
                    // Web nao parse field nao thi cung khop
                    val json = """{
                        "ip":"113.161.0.1",
                        "ipAddress":"113.161.0.1",
                        "ipVersion":4,
                        "version":"IPv4",
                        "query":"113.161.0.1",
                        "country":"Vietnam",
                        "country_name":"Vietnam",
                        "countryName":"Vietnam",
                        "country_code":"VN",
                        "countryCode":"VN",
                        "country_code_iso3":"VNM",
                        "region":"Ba Ria - Vung Tau",
                        "region_name":"Ba Ria - Vung Tau",
                        "regionName":"Ba Ria - Vung Tau",
                        "region_code":"57",
                        "regionCode":"57",
                        "state":"Ba Ria - Vung Tau",
                        "city":"Vung Tau",
                        "cityName":"Vung Tau",
                        "zip":"790000",
                        "zip_code":"790000",
                        "zipCode":"790000",
                        "postal":"790000",
                        "latitude":$lat,
                        "lat":$lat,
                        "longitude":$lng,
                        "lon":$lng,
                        "lng":$lng,
                        "loc":"$lat,$lng",
                        "location":{"latitude":$lat,"longitude":$lng,"lat":$lat,"lng":$lng,"lon":$lng},
                        "coordinates":{"latitude":$lat,"longitude":$lng},
                        "geo":{"latitude":$lat,"longitude":$lng,"lat":$lat,"lng":$lng,"lon":$lng,"city":"Vung Tau","country":"Vietnam"},
                        "timezone":"Asia/Ho_Chi_Minh",
                        "time_zone":"Asia/Ho_Chi_Minh",
                        "timeZone":"+07:00",
                        "utc_offset":"+0700",
                        "isp":"Viettel Corporation",
                        "org":"Viettel Corporation",
                        "asn":"AS7552",
                        "as":"AS7552 Viettel Corporation",
                        "connection":{"isp":"Viettel Corporation","org":"Viettel","asn":7552},
                        "currency":"VND",
                        "languages":"vi",
                        "calling_code":"84",
                        "status":"success",
                        "success":true,
                        "continent_code":"AS",
                        "continent":"Asia"
                    }""".trimIndent().replace("\n", "").replace("  ", "")
                    return makeJsonResponse(json)
                }

                return null
            }

            private fun makeJsonResponse(json: String): WebResourceResponse {
                val headers = mutableMapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                    "Access-Control-Allow-Headers" to "*"
                )
                return WebResourceResponse(
                    "application/json",
                    "UTF-8",
                    200,
                    "OK",
                    headers,
                    json.byteInputStream(Charsets.UTF_8)
                )
            }
        }

        // WebChromeClient - xử lý geolocation permission
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // Tự động cho phép geolocation
                callback?.invoke(origin, true, false)
            }

            // Cho phép web truy cap camera/mic (de quet QR live)
            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    request?.grant(request.resources)
                }
            }

            // Ho tro <input type="file"> de upload anh QR
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                }
                // Cho phep chon nhieu source: gallery + camera
                val chooser = Intent.createChooser(intent, "Chon anh QR")
                try {
                    fileChooserLauncher.launch(chooser)
                } catch (e: Exception) {
                    fileChooserCallback = null
                    return false
                }
                return true
            }
        }
    }

    private fun setupFileChooser() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris: Array<Uri>? = if (result.resultCode == RESULT_OK) {
                val data = result.data
                when {
                    data?.clipData != null -> {
                        val count = data.clipData!!.itemCount
                        Array(count) { data.clipData!!.getItemAt(it).uri }
                    }
                    data?.data != null -> arrayOf(data.data!!)
                    else -> null
                }
            } else null
            fileChooserCallback?.onReceiveValue(uris)
            fileChooserCallback = null
        }
    }

    private fun setupNavigation() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnRefresh.setOnClickListener { webView.reload() }
        btnHome.setOnClickListener { webView.loadUrl(HOME_URL) }
        btnHome.setOnLongClickListener {
            updateChecker?.checkForUpdate(silent = false)
            true
        }

        // Check IP - load trang hiển thị IP, location, ISP
        btnCheckIP.setOnClickListener { checkMyIP() }

        urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER)) {
                loadUrl(urlBar.text.toString())
                true
            } else false
        }
    }

    private fun loadUrl(input: String) {
        var url = input.trim()
        if (url.isEmpty()) return

        url = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.contains(".") && !url.contains(" ") -> "https://$url"
            else -> "https://www.google.com/search?q=${Uri.encode(url)}"
        }
        webView.loadUrl(url)
    }

    private fun updateNavButtons() {
        btnBack.alpha = if (webView.canGoBack()) 1.0f else 0.3f
        btnForward.alpha = if (webView.canGoForward()) 1.0f else 0.3f
    }

    // ========== GPS - Đọc vị trí từ hệ thống (đã bị fake bởi app) ==========

    private fun requestLocationPermission() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST_CODE) return
        // Check tung permission theo ten, KHONG dua vao grantResults[0]
        var locationGranted = false
        for (i in permissions.indices) {
            if (i >= grantResults.size) break
            if (permissions[i] == Manifest.permission.ACCESS_FINE_LOCATION &&
                grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                locationGranted = true
            }
        }
        if (locationGranted) {
            startLocationUpdates()
        } else if (!useFixedLocation) {
            // Chi hien canh bao khi KHONG dung toa do co dinh
            txtLocation.text = "⚠ Cần quyền GPS"
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        val locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (useFixedLocation) return
                currentLat = location.latitude
                currentLng = location.longitude
                hasLocation = true
                txtLocation.text = "📍 ${String.format(Locale.US, "%.6f", currentLat)}, ${String.format(Locale.US, "%.6f", currentLng)}"
                // Override lại geolocation mỗi khi vị trí thay đổi
                injectGeolocationOverride()
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        // Đọc từ GPS_PROVIDER (sẽ nhận vị trí fake từ app fake GPS)
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 3000, 0f, locationListener
            )
        } catch (e: Exception) { Log.w("GPSBrowser", "GPS provider unavailable", e) }

        // Cũng đọc từ NETWORK_PROVIDER
        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 3000, 0f, locationListener
            )
        } catch (e: Exception) { Log.w("GPSBrowser", "Network provider unavailable", e) }

        // Lấy last known location ngay lập tức
        try {
            val lastGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val last = lastGPS ?: lastNet
            if (last != null && !useFixedLocation) {
                currentLat = last.latitude
                currentLng = last.longitude
                hasLocation = true
                txtLocation.text = "📍 ${String.format(Locale.US, "%.6f", currentLat)}, ${String.format(Locale.US, "%.6f", currentLng)}"
            }
        } catch (e: Exception) { Log.w("GPSBrowser", "getLastKnownLocation failed", e) }
    }

    // ========== Inject JS override Geolocation ==========

    private fun injectGeolocationOverride() {
        // Cung khong gate - dung fixed default neu chua co GPS
        if (!hasLocation && !useFixedLocation) {
            // Set default tu fixed coords da save trong prefs
            currentLat = 10.404246840349387
            currentLng = 107.11750153452158
            hasLocation = true
        }

        val js = """
            (function() {
                var fakePos = {
                    coords: {
                        latitude: $currentLat,
                        longitude: $currentLng,
                        accuracy: 10,
                        altitude: null,
                        altitudeAccuracy: null,
                        heading: null,
                        speed: null
                    },
                    timestamp: Date.now()
                };

                navigator.geolocation.getCurrentPosition = function(success, error, options) {
                    // Async (microtask) - chuan W3C Geolocation API
                    setTimeout(function() { if (success) success(fakePos); }, 0);
                };

                navigator.geolocation.watchPosition = function(success, error, options) {
                    setTimeout(function() { if (success) success(fakePos); }, 0);
                    return Math.floor(Math.random() * 1000) + 1;
                };

                navigator.geolocation.clearWatch = function(id) {};
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    // ========== Chặn WebRTC leak ==========

    private fun blockWebRTCLeak() {
        // Chi chan RTCPeerConnection (lo IP), KHONG chan getUserMedia (can cho QR camera)
        val js = """
            (function() {
                if (window.RTCPeerConnection) window.RTCPeerConnection = undefined;
                if (window.webkitRTCPeerConnection) window.webkitRTCPeerConnection = undefined;
                if (window.mozRTCPeerConnection) window.mozRTCPeerConnection = undefined;
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    // ========== Lop 2: JS-level fetch/XHR override de bat IP API la ==========

    private fun injectFetchInterceptor() {
        // KHONG gate boi hasLocation - phai install hook NGAY cho moi page
        // de tranh race: page load truoc khi GPS callback fire
        // Default coords (Vung Tau) neu chua co location
        val lat = if (hasLocation) currentLat else 10.404246840349387
        val lng = if (hasLocation) currentLng else 107.11750153452158

        val js = """
            (function() {
                if (window.__gpsBrowserFetchHooked) return;
                window.__gpsBrowserFetchHooked = true;

                var fakeLat = $lat;
                var fakeLng = $lng;

                // JSON gia chua TAT CA field name pho bien
                var fakeJson = {
                    ip:"113.161.0.1", ipAddress:"113.161.0.1", query:"113.161.0.1",
                    country:"Vietnam", country_name:"Vietnam", countryName:"Vietnam",
                    country_code:"VN", countryCode:"VN",
                    region:"Ba Ria - Vung Tau", region_name:"Ba Ria - Vung Tau", regionName:"Ba Ria - Vung Tau",
                    city:"Vung Tau", cityName:"Vung Tau",
                    zip:"790000", zip_code:"790000", postal:"790000",
                    latitude:fakeLat, lat:fakeLat,
                    longitude:fakeLng, lon:fakeLng, lng:fakeLng,
                    loc: fakeLat + "," + fakeLng,
                    location:{latitude:fakeLat, longitude:fakeLng, lat:fakeLat, lng:fakeLng, lon:fakeLng},
                    geo:{latitude:fakeLat, longitude:fakeLng, city:"Vung Tau", country:"Vietnam"},
                    timezone:"Asia/Ho_Chi_Minh", time_zone:"Asia/Ho_Chi_Minh",
                    isp:"Viettel Corporation", org:"Viettel Corporation",
                    asn:"AS7552", as:"AS7552 Viettel Corporation",
                    success:true, status:"success"
                };

                // Pattern de phat hien IP geolocation API
                function isIpGeoUrl(url) {
                    if (!url) return false;
                    var u = String(url).toLowerCase();
                    // Same-origin (alpha-ecc) thi BO QUA - de cong ty xu ly checkin binh thuong
                    if (u.indexOf(location.hostname) >= 0) return false;
                    // Pattern domain pho bien
                    var patterns = [
                        'ipbase.com','ip-api.com','ipapi.co','ipwho.is','ipwhois.app',
                        'freeipapi.com','ipinfo.io','ipgeolocation.io','ipify.org',
                        'ipdata.co','ipstack.com','geoip-db.com','geojs.io','db-ip.com',
                        'ipregistry.co','iplocation.net','ip2location.io','ipgeo.io',
                        'country.is','findip.net','iplogger','iplookup','ipfind',
                        'whoer.net','ipchicken','what-is-my-ip'
                    ];
                    for (var i=0;i<patterns.length;i++) {
                        if (u.indexOf(patterns[i]) >= 0) return true;
                    }
                    // Heuristic CHAT: phai co json + path that su la /ip /geoip /ipgeo
                    // KHONG match /location, /geocode, /geo (qua rong, false positive Google Maps)
                    if (u.indexOf('json') < 0) return false;
                    if (/[/.](ip|geoip|ipgeo)[/.?]/i.test(u)) return true;
                    return false;
                }

                // Helper: dispatch event tuong thich addEventListener
                function fireEvent(target, type) {
                    try {
                        var ev = new Event(type);
                        target.dispatchEvent(ev);
                    } catch(e) {}
                }

                // ===== Override fetch =====
                var origFetch = window.fetch;
                // Helper: check bypass header in any format (Headers/object/array/lowercase)
                function hasBypassHeader(h) {
                    if (!h) return false;
                    var key = 'x-gpsbrowser-bypass';
                    // Headers object
                    if (typeof h.get === 'function') {
                        var v = h.get('X-GPSBrowser-Bypass') || h.get(key);
                        if (v === '1') return true;
                    }
                    // Array of [k,v]
                    if (Array.isArray(h)) {
                        for (var i=0;i<h.length;i++) {
                            if (h[i] && h[i].length >= 2 &&
                                String(h[i][0]).toLowerCase() === key &&
                                String(h[i][1]) === '1') return true;
                        }
                        return false;
                    }
                    // Plain object
                    for (var k in h) {
                        if (String(k).toLowerCase() === key && String(h[k]) === '1') return true;
                    }
                    return false;
                }

                window.fetch = function(input, init) {
                    var url = (typeof input === 'string') ? input : (input && input.url);
                    var bypass = false;
                    // Check init.headers
                    if (init && hasBypassHeader(init.headers)) bypass = true;
                    // Check Request object headers
                    if (input && typeof input === 'object' && input.headers && hasBypassHeader(input.headers)) bypass = true;
                    if (!bypass && isIpGeoUrl(url)) {
                        console.log('[GPSBrowser] Faking fetch:', url);
                        var body = JSON.stringify(fakeJson);
                        return Promise.resolve(new Response(body, {
                            status: 200,
                            statusText: 'OK',
                            headers: {'Content-Type':'application/json'}
                        }));
                    }
                    return origFetch.call(window, input, init);
                };

                // ===== Override XMLHttpRequest =====
                var OrigXHR = window.XMLHttpRequest;
                function FakeXHR() {
                    var xhr = new OrigXHR();
                    var origOpen = xhr.open;
                    var origSend = xhr.send;
                    var origSetHeader = xhr.setRequestHeader;
                    var fakedUrl = null;
                    var bypass = false;

                    xhr.setRequestHeader = function(name, value) {
                        if (name === 'X-GPSBrowser-Bypass' && value === '1') {
                            bypass = true;
                            fakedUrl = null;
                            return;
                        }
                        return origSetHeader.apply(xhr, arguments);
                    };

                    xhr.open = function(method, url) {
                        // Reset state moi cycle (XHR co the reuse: open->send->open->send)
                        bypass = false;
                        fakedUrl = null;
                        if (isIpGeoUrl(url)) {
                            fakedUrl = url;
                        }
                        return origOpen.apply(xhr, arguments);
                    };

                    xhr.send = function() {
                        if (fakedUrl) {
                            console.log('[GPSBrowser] Faking XHR:', fakedUrl);
                            var jsonStr = JSON.stringify(fakeJson);
                            setTimeout(function() {
                                try {
                                    Object.defineProperty(xhr, 'readyState', {value: 4, configurable: true});
                                    Object.defineProperty(xhr, 'status', {value: 200, configurable: true});
                                    Object.defineProperty(xhr, 'statusText', {value: 'OK', configurable: true});
                                    Object.defineProperty(xhr, 'responseText', {value: jsonStr, configurable: true});
                                    Object.defineProperty(xhr, 'response', {value: jsonStr, configurable: true});
                                    Object.defineProperty(xhr, 'responseType', {value: '', configurable: true});
                                } catch(e) {}
                                // Goi ca property handler VA dispatch event (cho addEventListener)
                                if (xhr.onreadystatechange) try { xhr.onreadystatechange(); } catch(e) {}
                                fireEvent(xhr, 'readystatechange');
                                if (xhr.onload) try { xhr.onload(); } catch(e) {}
                                fireEvent(xhr, 'load');
                                if (xhr.onloadend) try { xhr.onloadend(); } catch(e) {}
                                fireEvent(xhr, 'loadend');
                            }, 0);
                            return;
                        }
                        return origSend.apply(xhr, arguments);
                    };

                    return xhr;
                }
                FakeXHR.prototype = OrigXHR.prototype;
                window.XMLHttpRequest = FakeXHR;

                console.log('[GPSBrowser] Fetch + XHR interceptor installed');
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    // ========== Check IP ==========

    private fun checkMyIP() {
        // Load HTML nội bộ gọi API check IP
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <style>
                    * { margin:0; padding:0; box-sizing:border-box; }
                    body { background:#0f0f23; color:#e0e0e0; font-family:monospace;
                           padding:20px; display:flex; flex-direction:column; 
                           align-items:center; min-height:100vh; }
                    h1 { color:#00d2ff; font-size:22px; margin:20px 0; }
                    .card { background:#16213e; border-radius:12px; padding:20px;
                            width:100%; max-width:400px; margin:10px 0;
                            border:1px solid #1a1a4e; }
                    .row { display:flex; justify-content:space-between;
                           padding:10px 0; border-bottom:1px solid #1a1a4e; }
                    .row:last-child { border:none; }
                    .label { color:#888; font-size:13px; }
                    .value { color:#00d2ff; font-size:14px; font-weight:bold; text-align:right; max-width:60%; }
                    .status { text-align:center; padding:15px; border-radius:8px; margin:15px 0; width:100%; max-width:400px; }
                    .ok { background:#0a3622; color:#4ade80; border:1px solid #166534; }
                    .warn { background:#3b2007; color:#fbbf24; border:1px solid #92400e; }
                    .loading { color:#888; font-size:16px; margin:40px 0; }
                    .geo { margin-top:5px; }
                    #map { width:100%; height:200px; border-radius:8px; margin-top:10px;
                           border:1px solid #1a1a4e; overflow:hidden; }
                    #map img { width:100%; height:100%; object-fit:cover; }
                </style>
            </head>
            <body>
                <h1>🔍 IP CHECK</h1>
                <div id="result" class="loading">Đang kiểm tra...</div>
                <script>
                    async function checkIP() {
                        try {
                            // Bypass header de xem IP THAT (khong bi interceptor fake)
                            const r = await fetch('https://free.freeipapi.com/api/json', {
                                headers: {'X-GPSBrowser-Bypass': '1'}
                            });
                            const raw = await r.json();
                            // Map freeipapi.com to common schema
                            const d = {
                                ip: raw.ipAddress,
                                country_name: raw.countryName,
                                country_code: raw.countryCode,
                                region: raw.regionName,
                                city: raw.cityName,
                                org: 'N/A',
                                timezone: raw.timeZone,
                                latitude: raw.latitude,
                                longitude: raw.longitude
                            };

                            let gpsLat = ${if (hasLocation) currentLat.toString() else "null"};
                            let gpsLng = ${if (hasLocation) currentLng.toString() else "null"};

                            let ipLat = d.latitude;
                            let ipLng = d.longitude;
                            
                            // Tính khoảng cách giữa IP location và GPS location
                            let distKm = null;
                            let matchStatus = '';
                            if (gpsLat !== null && ipLat) {
                                let R = 6371;
                                let dLat = (ipLat - gpsLat) * Math.PI / 180;
                                let dLng = (ipLng - gpsLng) * Math.PI / 180;
                                let a = Math.sin(dLat/2)*Math.sin(dLat/2) +
                                        Math.cos(gpsLat*Math.PI/180)*Math.cos(ipLat*Math.PI/180)*
                                        Math.sin(dLng/2)*Math.sin(dLng/2);
                                distKm = R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
                                
                                if (distKm < 100) {
                                    matchStatus = '<div class="status ok">✅ IP & GPS khớp vùng (' + distKm.toFixed(0) + ' km)</div>';
                                } else {
                                    matchStatus = '<div class="status warn">⚠️ IP & GPS LỆCH ' + distKm.toFixed(0) + ' km — cần VPN!</div>';
                                }
                            }
                            
                            let html = '<div class="card">' +
                                '<div class="row"><span class="label">IP</span><span class="value">' + d.ip + '</span></div>' +
                                '<div class="row"><span class="label">Quốc gia</span><span class="value">' + d.country_name + ' ' + d.country_code + '</span></div>' +
                                '<div class="row"><span class="label">Vùng</span><span class="value">' + d.region + '</span></div>' +
                                '<div class="row"><span class="label">Thành phố</span><span class="value">' + d.city + '</span></div>' +
                                '<div class="row"><span class="label">ISP</span><span class="value">' + d.org + '</span></div>' +
                                '<div class="row"><span class="label">Timezone</span><span class="value">' + d.timezone + '</span></div>' +
                                '<div class="row"><span class="label">IP Location</span><span class="value">' + ipLat + ', ' + ipLng + '</span></div>' +
                                '</div>';
                            
                            if (gpsLat !== null) {
                                html += '<div class="card geo">' +
                                    '<div class="row"><span class="label">GPS (Fake)</span><span class="value">' + gpsLat.toFixed(6) + ', ' + gpsLng.toFixed(6) + '</span></div>' +
                                    '<div class="row"><span class="label">Khoảng cách</span><span class="value">' + (distKm ? distKm.toFixed(1) + ' km' : 'N/A') + '</span></div>' +
                                    '</div>';
                            }
                            
                            html += matchStatus;
                            
                            // Static map
                            html += '<div id="map"><img src="https://static-maps.yandex.ru/v1?lang=en_US&ll=' + ipLng + ',' + ipLat + '&z=8&size=450,200&l=map&pt=' + ipLng + ',' + ipLat + ',pm2rdl" onerror="this.parentElement.style.display=\'none\'"></div>';
                            
                            document.getElementById('result').innerHTML = html;
                        } catch(e) {
                            document.getElementById('result').innerHTML = '<div class="status warn">❌ Lỗi: ' + e.message + '</div>';
                        }
                    }
                    checkIP();
                </script>
            </body>
            </html>
        """.trimIndent()

        webView.loadDataWithBaseURL("https://check.local", html, "text/html", "UTF-8", null)
    }

    // ========== Handle Back button ==========

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        // Tranh leak Activity context
        updateChecker?.cleanup()
        try {
            webView.stopLoading()
            webView.removeAllViews()
            webView.destroy()
        } catch (e: Exception) { Log.w("GPSBrowser", "WebView destroy failed", e) }
        super.onDestroy()
    }
}
