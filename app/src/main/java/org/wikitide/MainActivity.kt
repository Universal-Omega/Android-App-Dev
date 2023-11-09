package org.wikitide

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commit
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.wikitide.databinding.ActivityMainBinding
import org.wikitide.search.SearchResultsFragment
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.Locale

private const val WIKI_URL = "https://meta.wikitide.org"
private const val WIKI_SCRIPT = "/w/"
private const val WIKI_SKIN = "vector-2022"

private val internalUrls = listOf(
    "wikiforge.net",
    "wikitide.com",
    "wikitide.org"
)

private val staticUrls = listOf(
    "static.wikiforge.net"
)

data class WikiPageResponse(
    val parse: Parse,
)

data class Parse(
    val text: String
)

interface WikiService {
    @GET("api.php")
    suspend fun getAvailablePages(
        @Query("action") action: String = "query",
        @Query("format") format: String = "json",
        @Query("list") list: String = "allpages",
        @Query("aplimit") aplimit: Int = 500,
        @Query("apfilterredir") apfilterredir: String = "nonredirects",
        @Query("apfrom") apfrom: String = "",
        @Query("apcontinue") apcontinue: String = ""
    ): AvailablePagesResponse


    @GET("api.php")
    suspend fun getPageContent(
        @Query("action") action: String,
        @Query("format") format: String,
        @Query("prop") prop: String,
        @Query("page") page: String,
        @Query("formatversion") formatversion: Int
    ): WikiPageResponse
}

data class AvailablePagesResponse(
    val query: AvailablePagesQuery,
    val `continue`: ContinueData?
)

data class AvailablePagesQuery(
    val allpages: List<AvailablePage>
)

data class AvailablePage(
    val title: String,
)

data class TitleWithUrl(
    val title: String,
    val url: String
)

data class ContinueData(
    @SerializedName("apcontinue")
    val apcontinue: String
)

suspend fun fetchAvailablePages(): List<String> {
    val wikiService = Retrofit.Builder()
        .baseUrl(WIKI_URL + WIKI_SCRIPT)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WikiService::class.java)

    val allPages = mutableListOf<String>()
    var apcontinue: String? = null

    try {
        while (true) {
            val response = if (apcontinue != null) {
                wikiService.getAvailablePages(apcontinue = apcontinue)
            } else wikiService.getAvailablePages()

            val pages = response.query.allpages.map { it.title }
            allPages.addAll(pages)

            apcontinue = response.`continue`?.apcontinue

            if (apcontinue == null) {
                break
            }
        }
    } catch (e: Exception) {
        // TODO handle exception
    }

    return allPages
}

suspend fun fetchPageContent(pageTitle: String): WikiPageResponse? {
    val wikiService = Retrofit.Builder()
        .baseUrl(WIKI_URL + WIKI_SCRIPT)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WikiService::class.java)

    return try {
        withContext(Dispatchers.IO) {
            wikiService.getPageContent(
                action = "parse",
                format = "json",
                prop = "text",
                page = pageTitle,
                formatversion = 2
            )
        }
    } catch (e: Exception) {
        null
    }
}

fun stripHtmlTags(html: String?): String? {
    if (html.isNullOrBlank()) {
        return null
    }

    val text = html
        .replace(Regex("<.*?>"), "") // Remove all HTML tags
        .replace(Regex("&[a-zA-Z]+;"), " ")

    return text.lines().firstOrNull()?.trim()
}

suspend fun fetchMainPageUrl(): TitleWithUrl? {
    val mainPage = stripHtmlTags(
        fetchPageContent("MediaWiki:Mainpage")?.parse?.text
    ) ?: return null

    val baseUrl = "${WIKI_URL + WIKI_SCRIPT}index.php"
    val url = Uri.parse(baseUrl)
        .buildUpon()
        .appendQueryParameter("title", mainPage)
        .build()
        .toString()

    return TitleWithUrl(
        title = mainPage,
        url = url
    )
}

class MainActivity : AppCompatActivity() {
    private var currentUrl: String? = null

    private var cachedPages: List<String>? = null
    private lateinit var filteredPages: List<String>

    private lateinit var webView: WebView
    private lateinit var materialToolbar: MaterialToolbar
    private lateinit var fragmentContainerView: FragmentContainerView
    private lateinit var searchResultsFragment: SearchResultsFragment
    private lateinit var loadingProgressBar: LinearProgressIndicator

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadingProgressBar = binding.loadingProgressBar
        loadingProgressBar.max = 100

        materialToolbar = binding.materialToolbar
        val swipeRefreshLayout = binding.swipeRefreshLayout

        val contentTextView = binding.contentTextView

        fragmentContainerView = binding.fragmentContainer
        searchResultsFragment = SearchResultsFragment()
        supportFragmentManager.commit {
            add(fragmentContainerView.id, searchResultsFragment)
        }

        webView = binding.webView

        webView.settings.javaScriptEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

        webView.webViewClient = WikiWebViewClient()
        webView.webChromeClient = WikiWebChromeClient()

        val searchItem = materialToolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView
        fun finishSearch() {
            searchView.setQuery(null, false)
            materialToolbar.clearFocus()
            fragmentContainerView.isVisible = false
        }

        // Handle back press
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fragmentContainerView.isVisible) {
                    // Stop searching if we are right now
                    finishSearch()
                } else if (webView.canGoBack()) {
                    // If the WebView can go back, navigate back in the WebView's history
                    webView.goBack()
                } else {
                    // If the WebView cannot go back further, perform the default back action
                    isEnabled = false // Disable this callback
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // Handle refreshing
        swipeRefreshLayout.setOnRefreshListener {
            webView.reload()
            if (swipeRefreshLayout.isRefreshing) {
                swipeRefreshLayout.isRefreshing = false
            }
        }

        if (savedInstanceState != null) {
            // Restore the current URL
            currentUrl = savedInstanceState.getString("currentUrl")
        }

        CoroutineScope(Dispatchers.IO).launch {
            if (currentUrl == null) {
                val mainPage = fetchMainPageUrl()
                if (mainPage != null) {
                    runOnUiThread {
                        materialToolbar.title = mainPage.title
                        materialToolbar.navigationIcon = null
                        webView.loadUrl(mainPage.url)
                    }
                }
            }

            handleToolbarOptions()

            cachedPages = fetchAvailablePages()
            filteredPages = cachedPages ?: emptyList()

            if (filteredPages.isNotEmpty()) {
                createToolbar(searchView)

                searchResultsFragment.setOnPageSelectedListener { selectedPageTitle ->
                    runOnUiThread {
                        finishSearch()
                        try {
                            // Check if there's a valid URL to load
                            val baseUrl = "${WIKI_URL + WIKI_SCRIPT}index.php"
                            val pageUrl = Uri.parse(baseUrl)
                                .buildUpon()
                                .appendQueryParameter("title", selectedPageTitle)
                                .build()
                                .toString()

                            webView.loadUrl(pageUrl)
                            contentTextView.text = ""
                        } catch (e: Exception) {
                            contentTextView.text = getString(R.string.failed_to_fetch_content)
                        }
                    }
                }
            } else {
                contentTextView.text = getString(R.string.no_available_pages_found)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        currentUrl?.let { url ->
            outState.putString("currentUrl", url)
        }

        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    private fun handleToolbarOptions() {
        materialToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_clear_cache -> {
                    webView.clearCache(true)
                    webView.reload()
                    Toast.makeText(this, R.string.cache_cleared, Toast.LENGTH_SHORT).show()
                    true
                }

                else -> true
            }
        }
    }

    private fun createToolbar(searchView: SearchView) {
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                filterPages(newText)
                return true
            }
        })
    }

    private fun filterPages(query: String) {
        val lowerCaseQuery = query.lowercase(Locale.getDefault())
        filteredPages = if (query.isNotBlank()) {
            cachedPages?.filter { it.lowercase(Locale.getDefault()).contains(lowerCaseQuery) }
                ?: emptyList()
        } else emptyList()

        searchResultsFragment.updateSearchResults(filteredPages)
        fragmentContainerView.isVisible = filteredPages.isNotEmpty()
    }

    inner class WikiWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageFinished(view, url)
            if (!loadingProgressBar.isShown) {
                loadingProgressBar.show()
                loadingProgressBar.progress = 0
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            loadingProgressBar.hide()
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            loadingProgressBar.show()
            loadingProgressBar.progress = 0
            if (isExternalLink(url)) {
                showExternalLinkDialog(view, url)
                return true
            }

            setScaleIfNeeded(url)

            if (!url.contains("useskin=$WIKI_SKIN")) {
                var modifiedUrl: String = appendParameter(url, "useskin", WIKI_SKIN)
                modifiedUrl = appendParameter(modifiedUrl, "safemode", "1")
                view.loadUrl(modifiedUrl)
                currentUrl = modifiedUrl

                return true
            }

            currentUrl = url
            return super.shouldOverrideUrlLoading(view, request)
        }

        private fun setScaleIfNeeded(url: String) {
            if (staticUrls.any { url.contains(it) }) {
                webView.setInitialScale(30)
                webView.settings.useWideViewPort = true
                webView.settings.loadWithOverviewMode = true
            }
        }

        private fun isExternalLink(url: String): Boolean {
            return !url.contains(WIKI_URL) && !internalUrls.any { url.contains(it) }
        }

        private fun appendParameter(url: String, parameter: String, value: String): String {
            val uri = Uri.parse(url)
            val modifiedUri = uri.buildUpon()
                .appendQueryParameter(parameter, value)
                .build()
            return modifiedUri.toString()
        }

        private fun showExternalLinkDialog(view: WebView, url: String) {
            val alertDialog = AlertDialog.Builder(view.context)
                .setTitle(R.string.external_link)
                .setMessage(R.string.external_link_message)
                .setPositiveButton(R.string.open_in_browser) { _, _ ->
                    openLinkInBrowser(view, url)
                }
                .setNegativeButton(R.string.cancel) { _, _ ->
                    // User canceled the action, do nothing
                }
                .create()
            alertDialog.show()
        }

        private fun openLinkInBrowser(view: WebView, url: String) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            view.context.startActivity(intent)
        }
    }

    inner class WikiWebChromeClient(): WebChromeClient() {
        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)
            // Update your loading bar or handle progress changes here
            loadingProgressBar.progress = newProgress
        }

        override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
            super.onReceivedIcon(view, icon)
            materialToolbar.navigationIcon = createFaviconDrawable(icon)
        }

        override fun onReceivedTitle(view: WebView?, title: String?) {
            super.onReceivedTitle(view, title)
            materialToolbar.title = title
        }

        private fun createFaviconDrawable(faviconBitmap: Bitmap?): Drawable {
            return object : Drawable() {
                override fun draw(canvas: Canvas) {
                    faviconBitmap?.let { bitmap ->
                        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, 80, 80, false)
                        canvas.drawBitmap(scaledBitmap, 40f, 40f, null)
                    }
                }

                override fun setAlpha(alpha: Int) {}

                override fun setColorFilter(colorFilter: ColorFilter?) {}

                @Deprecated(
                    "Deprecated in Java",
                    ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat")
                )
                override fun getOpacity(): Int {
                    return PixelFormat.TRANSLUCENT
                }
            }
        }
    }
}