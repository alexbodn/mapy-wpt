package com.mapywptsaver

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    data class WptCoord(val lon: Double?, val lat: Double?, val isOsm: Boolean = false, val rawId: String? = null)
    private var coordinatesList = mutableListOf<WptCoord>()
    private var lastRcParam: String? = null
    private var fullUrl: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)

        // Setup WebView
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        // Handle Intent
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val data: Uri? = intent?.data
        val type = intent?.type

        var urlToLoad = "https://en.mapy.cz"
        var parsedUri: Uri? = null

        if (Intent.ACTION_VIEW == action && data != null) {
            urlToLoad = data.toString()
            parsedUri = data
        } else if (Intent.ACTION_SEND == action && "text/plain" == type) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (sharedText != null) {
                // Extract URL from shared text (sometimes sharing includes extra text before/after the URL)
                val urlRegex = "(?i)\\b((?:https?://|www\\d{0,3}[.]|[a-z0-9.\\-]+[.][a-z]{2,4}/)(?:[^\\s()<>]+|\\((?:[^\\s()<>]+|\\([^\\s()<>]+\\))*\\))+(?:\\((?:[^\\s()<>]+|\\([^\\s()<>]+\\))*\\)|[^\\s`!()\\[\\]{};:'\".,<>?«»“”‘’]))".toRegex()
                val matchResult = urlRegex.find(sharedText)
                if (matchResult != null) {
                    var extractedUrl = matchResult.value
                    // If the URL is missing the scheme (e.g. "mapy.cz/s/abcde"), Uri.parse might not treat it as absolute
                    if (!extractedUrl.startsWith("http://") && !extractedUrl.startsWith("https://")) {
                        extractedUrl = "https://$extractedUrl"
                    }
                    urlToLoad = extractedUrl
                    parsedUri = Uri.parse(urlToLoad)
                }
            }
        }

        fullUrl = urlToLoad

        if (parsedUri != null) {
            parseCoordinatesFromUrl(parsedUri)
        }

        // Add JS Interface for next steps
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidInterface")

        setupWebViewClient()
        webView.loadUrl(urlToLoad)

        Toast.makeText(this, "Loading: $urlToLoad", Toast.LENGTH_LONG).show()
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                if (url != null) {
                    val currentUri = Uri.parse(url)
                    if (currentUri.getQueryParameters("ri").isNotEmpty() || url.contains("ri=")) {
                        fullUrl = url
                        parseCoordinatesFromUrl(currentUri)
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                // Sometimes the page redirects (e.g., from mapy.com/s/xyz to a full URL with 'ri' params)
                // We should re-parse the URL here to capture any coordinates if we missed them initially.
                if (url != null) {
                    val currentUri = Uri.parse(url)
                    if (currentUri.getQueryParameters("ri").isNotEmpty() || url.contains("ri=")) {
                        fullUrl = url
                        parseCoordinatesFromUrl(currentUri)
                    }
                }

                injectInterceptScript(view)
            }
        }
    }

    private fun injectInterceptScript(view: WebView?) {
        val js = """
            (function() {
                if (window.__wptIntercepted) return;
                window.__wptIntercepted = true;

                // We want to intercept Blob creation to catch GPX downloads
                const originalCreateObjectURL = URL.createObjectURL;
                URL.createObjectURL = function(obj) {
                    if (obj instanceof Blob && (obj.type === 'application/gpx+xml' || obj.type.includes('gpx') || obj.size > 0)) {
                        const reader = new FileReader();
                        reader.onload = function(e) {
                            let text = e.target.result;
                            if (text.includes('<gpx')) {
                                text = modifyGpx(text);
                                AndroidInterface.onGpxDownloaded('mapy_export.gpx', text);
                            }
                        };
                        reader.readAsText(obj);
                    }
                    return originalCreateObjectURL.apply(this, arguments);
                };

                function extractItineraryInstructions() {
                    // Try to find the itinerary container.
                    // It typically looks like a list with elements having class like "itinerary", "route-itinerary", or "step"
                    // As seen in the screenshot, there are points (with coordinates) and instructions between them.
                    const instructions = [];
                    try {
                        // This assumes the layout is somewhat linear in the DOM, e.g., inside `.itinerary` or similar lists.
                        // We will collect all text items that look like directions (e.g. "turn left", "continue", etc.)
                        // Since we cannot be 100% sure of the exact DOM structure mapy uses, we'll try a generic approach
                        // looking for elements that represent a step.

                        const items = document.querySelectorAll('.itinerary-item, .route-item, li'); // heuristic classes
                        let currentSegment = [];
                        let segmentIndex = 0;

                        // Fallback: If we can't find specific classes, we'll just try to scrape the text from the itinerary panel.
                        // We'll collect the texts of elements that appear to be instructions.
                        const itineraryPanel = document.querySelector('.itinerary, .route-detail') || document.body;

                        if (itineraryPanel) {
                            // Mapy's web app tends to use specific classes for points vs instructions.
                            // We will attempt to group them by looking at elements.
                            const nodes = itineraryPanel.querySelectorAll('.point, .instruction, li, .step');
                            if (nodes.length > 0) {
                                for (let i = 0; i < nodes.length; i++) {
                                    const text = nodes[i].innerText || '';

                                    // Mapy sometimes separates point names (like "Alumot dam") from the coordinates.
                                    // Usually they appear as titles right before or inside the point element.
                                    // Turn-by-turn instructions often contain specific keywords.

                                    const lowerText = text.toLowerCase();

                                    if (lowerText.includes('turn ') || lowerText.includes('continue ') || lowerText.includes('keep ') || lowerText.includes('head ') || lowerText.includes('take the')) {
                                        // Looks like a navigation instruction
                                        let cleanText = text.replace(/\n+/g, ' ').trim();
                                        if (cleanText && !currentSegment.includes(cleanText)) {
                                            currentSegment.push(cleanText);
                                        }
                                    } else if (nodes[i].querySelector && nodes[i].querySelector('.point-title, .title, strong, b')) {
                                        // If this node represents a route point (usually indicated by strong styling)
                                        if (currentSegment.length > 0) {
                                            instructions.push(currentSegment.join('\n'));
                                            currentSegment = [];
                                        }
                                        segmentIndex++;
                                    } else if (text.includes('N') && text.includes('E') && (text.match(/\d+\.\d+/g) || []).length >= 2) {
                                        // Fallback coordinate point marker
                                        if (currentSegment.length > 0) {
                                            instructions.push(currentSegment.join('\n'));
                                            currentSegment = [];
                                        } else {
                                            // Ensure we always add an empty slot if we hit a waypoint so indexing aligns
                                            instructions.push("");
                                        }
                                        segmentIndex++;
                                    }
                                }
                            }

                            // Flush any remaining trailing instructions
                            if (currentSegment.length > 0) {
                                instructions.push(currentSegment.join('\n'));
                            }
                        }
                    } catch (e) {
                        console.error('Error scraping itinerary', e);
                    }

                    // Filter down to valid strings, even if empty, so the JSON is an array of strings
                    const cleanInstructions = instructions.map(inst => (inst || "").trim());

                    // Send to Android immediately after scraping just in case
                    AndroidInterface.saveInstructions(JSON.stringify(cleanInstructions));
                    return cleanInstructions;
                }

                // Do a periodic scrape to keep instructions updated
                setInterval(() => {
                   extractItineraryInstructions();
                }, 2000);

                function modifyGpx(gpxText) {
                    try {
                        const coordsStr = AndroidInterface.getCoordinatesJson();
                        const coords = JSON.parse(coordsStr);

                        if (!coords || coords.length === 0) {
                            AndroidInterface.showToast("No coords found to inject!");
                            return gpxText;
                        }

                        const sourceUrl = AndroidInterface.getSourceUrl();

                        let wptXml = '';
                        if (sourceUrl) {
                            wptXml += '\n  <!-- source_url: ' + sourceUrl.replace(/--/g, '- -') + ' -->\n';
                        }

                        let wptIndex = 1;
                        for (let i = 0; i < coords.length; i++) {
                            if (coords[i].lat === null || coords[i].lon === null) {
                                wptXml += '  <!-- Non-coordinate point ID: ' + (coords[i].rawId || 'unknown') + ' -->\n';
                                continue;
                            }
                            wptXml += '  <wpt lat="' + coords[i].lat + '" lon="' + coords[i].lon + '">\n';
                            wptXml += '    <name>' + wptIndex + '</name>\n';

                            let descParts = [];

                            if (coords[i].isOsm) {
                                descParts.push("Source: OpenStreetMap");
                            }

                            // Instruction comes from Android backend directly via JSON now
                            if (coords[i].instruction) {
                                descParts.push(coords[i].instruction);
                            }

                            if (descParts.length > 0) {
                                const combinedDesc = descParts.join("\n\n");
                                const escapedDesc = combinedDesc.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');
                                wptXml += '    <desc>' + escapedDesc + '</desc>\n';
                            }

                            // OsmAnd styling extensions
                            wptXml += '    <extensions>\n';
                            wptXml += '      <osmand:color>#ff0000</osmand:color>\n';
                            wptXml += '      <osmand:icon>number_' + wptIndex + '</osmand:icon>\n';
                            wptXml += '      <osmand:background>circle</osmand:background>\n';
                            wptXml += '    </extensions>\n';

                            wptXml += '  </wpt>\n';
                            wptIndex++;
                        }

                        // Insert after <gpx ...> (case-insensitive search just in case)
                        const gpxTagIndex = gpxText.toLowerCase().indexOf('<gpx');
                        const gpxTagEnd = gpxText.indexOf('>', gpxTagIndex) + 1;
                        if (gpxTagIndex >= 0 && gpxTagEnd > 0) {
                            let gpxOpenTag = gpxText.substring(gpxTagIndex, gpxTagEnd);

                            // Some apps (like OsmAnd) strictly require the osmand namespace for osmand:* elements
                            // We also need to strip any trailing slash before the > so it's properly closed if it was self-closing
                            if (!gpxOpenTag.includes('xmlns:osmand')) {
                                gpxOpenTag = gpxOpenTag.replace(/\/?\s*>/, ' xmlns:osmand="https://osmand.net">');
                            }

                            // Insert waypoints right after the opening <gpx> tag
                            gpxText = gpxText.substring(0, gpxTagIndex) + gpxOpenTag + '\n' + wptXml + gpxText.substring(gpxTagEnd);
                            AndroidInterface.showToast("Injected " + coords.length + " wpts!");
                        } else {
                            AndroidInterface.showToast("Could not find <gpx tag!");
                        }
                        return gpxText;
                    } catch (e) {
                        AndroidInterface.showToast("Error modifying GPX: " + e.message);
                        return gpxText;
                    }
                }

                // Intercept clicks on links that might trigger direct download instead of blob
                document.addEventListener('click', function(e) {
                    const target = e.target.closest('a, button');

                    if (target && target.hasAttribute('download') && target.href && target.href.includes('blob:')) {
                        // The blob should have been intercepted above
                    } else if (target && target.href && target.href.includes('export') && target.href.includes('gpx')) {
                        // If it's a direct link to a GPX export, intercept it
                        e.preventDefault();
                        fetch(target.href)
                            .then(response => response.text())
                            .then(text => {
                                text = modifyGpx(text);
                                AndroidInterface.onGpxDownloaded(target.getAttribute('download') || 'mapy_export.gpx', text);
                            });
                    }
                }, true);

                // Override window.fetch to catch API calls returning GPX data
                const originalFetch = window.fetch;
                window.fetch = async function() {
                    const response = await originalFetch.apply(this, arguments);
                    const url = arguments[0];
                    if (url && typeof url === 'string' && (url.includes('gpx') || url.includes('export'))) {
                        const clone = response.clone();
                        clone.text().then(text => {
                            if (text && text.includes('<gpx')) {
                                text = modifyGpx(text);
                                AndroidInterface.onGpxDownloaded('mapy_export.gpx', text);
                            }
                        }).catch(err => console.error(err));
                    }
                    return response;
                };

                // Override XMLHttpRequest to catch old-school AJAX downloads
                const originalXHRSend = XMLHttpRequest.prototype.send;
                XMLHttpRequest.prototype.send = function() {
                    this.addEventListener('load', function() {
                        if (this.responseURL && (this.responseURL.includes('gpx') || this.responseURL.includes('export'))) {
                            if (this.responseText && this.responseText.includes('<gpx')) {
                                let modified = modifyGpx(this.responseText);
                                AndroidInterface.onGpxDownloaded('mapy_export.gpx', modified);
                            }
                        }
                    });
                    originalXHRSend.apply(this, arguments);
                };
            })();
        """.trimIndent()
        view?.evaluateJavascript(js, null)
    }

    private var pendingGpxContent: String? = null

    fun saveGpxFile(filename: String, content: String) {
        pendingGpxContent = content
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/gpx+xml"
            putExtra(Intent.EXTRA_TITLE, filename)
        }
        try {
            startActivityForResult(intent, CREATE_FILE_REQUEST_CODE)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to open save dialog", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CREATE_FILE_REQUEST_CODE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                try {
                    val outputStream: OutputStream? = contentResolver.openOutputStream(uri)
                    outputStream?.use {
                        it.write((pendingGpxContent ?: "").toByteArray())
                    }
                    Toast.makeText(this, "GPX file saved successfully!", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
        // Clear pending content after saving or cancelling
        pendingGpxContent = null
    }

    companion object {
        private const val CREATE_FILE_REQUEST_CODE = 1001
    }

    fun getCoordinatesList(): List<WptCoord> {
        return coordinatesList
    }

    private var scrapedInstructionsList: List<String> = emptyList()

    fun saveScrapedInstructions(jsonArrayString: String) {
        try {
            val instructions = mutableListOf<String>()
            val trimmed = jsonArrayString.trim().removePrefix("[").removeSuffix("]")
            if (trimmed.isNotEmpty()) {
                // simple split by stringified json commas if needed, or proper parsing
                // To keep it simple without extra libraries:
                val jsonArray = org.json.JSONArray(jsonArrayString)
                for (i in 0 until jsonArray.length()) {
                    instructions.add(jsonArray.getString(i))
                }
            }
            scrapedInstructionsList = instructions
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getScrapedInstructions(): List<String> {
        return scrapedInstructionsList
    }

    fun getFullUrl(): String? {
        return fullUrl
    }

    private var lastParsedUrlQuery: String? = null

    private fun parseCoordinatesFromUrl(uri: Uri) {
        val riParams = uri.getQueryParameters("ri")
        // If Android Uri parsing misses them entirely due to fragment placement, do not abort
        val hasRiInString = uri.toString().contains("ri=")
        if (riParams.isEmpty() && !hasRiInString) return

        // Prevent repeated fetches for the exact same query params
        // Check ONLY the string contents of "ri=" parameters to prevent multiple parses for the exact same coordinate setup,
        // while allowing re-parsing if the user navigates to a new itinerary setup on the same base domain
        val currentQuery = uri.toString()
        // Mapy uses `#` for fragments which android Uri parser misses. we should split by `#`, `?`, `&`
        val queryParts = currentQuery.split("&", "?", "#")
        // Filter out empty ri parameters (e.g. `&ri=&ri=`)
        val justRiParamsString = queryParts.filter { it.startsWith("ri=") && it.length > 3 }.joinToString("&")

        val currentRcParam = queryParts.firstOrNull { it.startsWith("rc=") }

        if (justRiParamsString.isNotEmpty() && justRiParamsString == lastParsedUrlQuery && currentRcParam == lastRcParam) {
            return
        }

        if (currentRcParam != null && currentRcParam != lastRcParam) {
            coordinatesList.clear()
            lastRcParam = currentRcParam
        }

        if (justRiParamsString.isNotEmpty()) {
            lastParsedUrlQuery = justRiParamsString
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Android's Uri.getQueryParameters fails when fragment (#) is present before the query parameters
            // which mapy.com routinely does during heavy redirects.
            // We use a robust manual extraction string split to guarantee all coordinates are fetched.

            val query = uri.toString()
            val pairs = query.split("&", "?", "#").filter { it.startsWith("ri=") && it.length > 3 }

            val parsedCoords = arrayOfNulls<WptCoord>(pairs.size)

            val manualJobs = pairs.mapIndexed { index, pair ->
                launch {
                    val riRaw = pair.substring(3)
                    val ri = riRaw.replace("%2C", ",")
                    val parts = ri.split(",")

                    if (parts.size >= 2) {
                        try {
                            val lon = parts[0].toDouble()
                            val lat = parts[1].toDouble()
                            parsedCoords[index] = WptCoord(lon, lat, false)
                        } catch(e: Exception) {
                            parsedCoords[index] = WptCoord(null, null, false, riRaw)
                        }
                    } else {
                        // Mapy IDs are NOT OSM Node IDs. They are internal Mapy POI IDs.
                        // We cannot resolve them via api.openstreetmap.org.
                        // So we store them as unresolved IDs to be commented out in the GPX.
                        parsedCoords[index] = WptCoord(null, null, false, riRaw)
                    }
                }
            }
            manualJobs.forEach { it.join() }

            val validCoords = parsedCoords.filterNotNull()

            withContext(Dispatchers.Main) {
                if (validCoords.isNotEmpty()) {
                    // Fully replace coordinates list to guarantee exact ordered preservation from URL
                    coordinatesList.clear()
                    coordinatesList.addAll(validCoords)
                }
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
