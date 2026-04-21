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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var coordinatesList = mutableListOf<Pair<Double, Double>>()
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
        webView.addJavascriptInterface(WebAppInterface(this, coordinatesList, fullUrl), "AndroidInterface")

        setupWebViewClient()
        webView.loadUrl(urlToLoad)

        Toast.makeText(this, "Loading: $urlToLoad", Toast.LENGTH_LONG).show()
    }

    private fun setupWebViewClient() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
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
                                    if (text.includes('N') && text.includes('E') && (text.match(/\d+\.\d+/g) || []).length >= 2) {
                                        // This looks like a coordinate point marker (like the ones in the screenshot)
                                        if (currentSegment.length > 0) {
                                            instructions.push(currentSegment.join('\n'));
                                            currentSegment = [];
                                        } else if (instructions.length === 0 && segmentIndex === 0) {
                                            // First point, push empty if no instructions before it
                                            instructions.push('');
                                        }
                                        segmentIndex++;
                                    } else if (text.toLowerCase().includes('turn') || text.toLowerCase().includes('continue') || text.toLowerCase().includes('keep')) {
                                        // Looks like an instruction
                                        // Clean up text
                                        let cleanText = text.replace(/\n+/g, ' ').trim();
                                        if (cleanText) {
                                            currentSegment.push(cleanText);
                                        }
                                    }
                                }
                            }
                            if (currentSegment.length > 0) {
                                instructions.push(currentSegment.join('\n'));
                            }
                        }
                    } catch (e) {
                        console.error('Error scraping itinerary', e);
                    }
                    return instructions;
                }

                function modifyGpx(gpxText) {
                    try {
                        const coordsStr = AndroidInterface.getCoordinatesJson();
                        const coords = JSON.parse(coordsStr);
                        const sourceUrl = AndroidInterface.getSourceUrl();
                        const scrapedInstructions = extractItineraryInstructions();

                        let wptXml = '';
                        if (sourceUrl) {
                            wptXml += '\n  <!-- source_url: ' + sourceUrl.replace(/--/g, '- -') + ' -->\n';
                        }

                        for (let i = 0; i < coords.length; i++) {
                            wptXml += '  <wpt lat="' + coords[i].lat + '" lon="' + coords[i].lon + '">\n';
                            wptXml += '    <name>' + (i + 1) + '</name>\n';

                            // Attach instructions to the following wpt.
                            // If i=1 (Point 2), attach the instructions collected after Point 1.
                            if (i > 0 && scrapedInstructions.length >= i) {
                                const descText = scrapedInstructions[i - 1];
                                if (descText) {
                                    // Escape XML
                                    const escapedDesc = descText.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');
                                    wptXml += '    <desc>' + escapedDesc + '</desc>\n';
                                }
                            }

                            wptXml += '  </wpt>\n';
                        }

                        // Insert after <gpx ...>
                        const gpxTagEnd = gpxText.indexOf('>', gpxText.indexOf('<gpx')) + 1;
                        if (gpxTagEnd > 0) {
                            gpxText = gpxText.substring(0, gpxTagEnd) + '\n' + wptXml + gpxText.substring(gpxTagEnd);
                        }
                        return gpxText;
                    } catch (e) {
                        return gpxText;
                    }
                }

                // Intercept clicks on links that might trigger direct download instead of blob
                document.addEventListener('click', function(e) {
                    const target = e.target.closest('a, button');

                    if (target) {
                        let text = target.innerText ? target.innerText.toLowerCase() : '';
                        if (text.includes('export') || (target.href && target.href.includes('export'))) {
                             AndroidInterface.showToast("Clicked Export! Tag: " + target.tagName + ", href: " + (target.href || "none") + ", class: " + target.className);
                        }
                    }

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

    private fun parseCoordinatesFromUrl(uri: Uri) {
        coordinatesList.clear()

        // The Mapy URL has multiple 'ri=' query parameters.
        // Some are IDs (like 1018994021), others are lat,lon pairs separated by comma
        // Mapy uses lon,lat or lat,lon.
        // Based on the example: ri=35.553835183382034%2C32.666597440838814
        // Typically longitude,latitude in mapping APIs or vice-versa.

        val riParams = uri.getQueryParameters("ri")
        for (ri in riParams) {
            if (ri.contains(",")) {
                val parts = ri.split(",")
                if (parts.size == 2) {
                    try {
                        val lon = parts[0].toDouble()
                        val lat = parts[1].toDouble()
                        coordinatesList.add(Pair(lon, lat))
                    } catch (e: NumberFormatException) {
                        // ignore invalid numbers
                    }
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
