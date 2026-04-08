package com.gpsbrowser

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
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

    private val LOCATION_PERMISSION_CODE = 1001
    private val HOME_URL = "https://App.alpha-ecc.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        loadFixedLocation()
        setupWebView()
        setupNavigation()
        setupFixedLocationDialog()
        requestLocationPermission()

        webView.loadUrl(HOME_URL)
    }

    // ========== Fixed location (custom coordinates) ==========

    private var useFixedLocation = false

    private fun loadFixedLocation() {
        val prefs = getSharedPreferences("gpsbrowser", Context.MODE_PRIVATE)
        useFixedLocation = prefs.getBoolean("use_fixed", false)
        if (useFixedLocation) {
            currentLat = prefs.getFloat("fixed_lat", 0f).toDouble()
            currentLng = prefs.getFloat("fixed_lng", 0f).toDouble()
            hasLocation = true
        }
    }

    private fun setupFixedLocationDialog() {
        if (useFixedLocation) {
            txtLocation.text = "📌 ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLng)} (cố định)"
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
                        txtLocation.text = "📌 ${String.format("%.6f", lat)}, ${String.format("%.6f", lng)} (cố định)"
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
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                updateNavButtons()
                // Inject geolocation override sau khi trang load
                injectGeolocationOverride()
                // Chặn WebRTC leak
                blockWebRTCLeak()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                // Mở trong WebView, không mở app ngoài
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return false
                }
                return true
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
        }
    }

    private fun setupNavigation() {
        btnBack.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        btnForward.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        btnRefresh.setOnClickListener { webView.reload() }
        btnHome.setOnClickListener { webView.loadUrl(HOME_URL) }

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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_CODE
            )
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == LOCATION_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
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
                txtLocation.text = "📍 ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLng)}"
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
        } catch (e: Exception) { }

        // Cũng đọc từ NETWORK_PROVIDER
        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER, 3000, 0f, locationListener
            )
        } catch (e: Exception) { }

        // Lấy last known location ngay lập tức
        try {
            val lastGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            val lastNet = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            val last = lastGPS ?: lastNet
            if (last != null && !useFixedLocation) {
                currentLat = last.latitude
                currentLng = last.longitude
                hasLocation = true
                txtLocation.text = "📍 ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLng)}"
            }
        } catch (e: Exception) { }
    }

    // ========== Inject JS override Geolocation ==========

    private fun injectGeolocationOverride() {
        if (!hasLocation) return

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
                    if (success) success(fakePos);
                };

                navigator.geolocation.watchPosition = function(success, error, options) {
                    if (success) success(fakePos);
                    return 0;
                };

                navigator.geolocation.clearWatch = function(id) {};
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)
    }

    // ========== Chặn WebRTC leak ==========

    private fun blockWebRTCLeak() {
        val js = """
            (function() {
                // Disable WebRTC to prevent IP leak
                if (window.RTCPeerConnection) {
                    window.RTCPeerConnection = undefined;
                }
                if (window.webkitRTCPeerConnection) {
                    window.webkitRTCPeerConnection = undefined;
                }
                if (window.mozRTCPeerConnection) {
                    window.mozRTCPeerConnection = undefined;
                }
                if (navigator.mediaDevices) {
                    navigator.mediaDevices.getUserMedia = function() {
                        return Promise.reject(new Error('Not allowed'));
                    };
                }
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
                            const r = await fetch('https://ipapi.co/json/');
                            const d = await r.json();
                            
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
}
