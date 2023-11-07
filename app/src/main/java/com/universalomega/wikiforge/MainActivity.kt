package com.universalomega.wikiforge

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import com.google.gson.annotations.SerializedName
import com.universalomega.wikiforge.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
            } else {
                wikiService.getAvailablePages()
            }

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

    val text =  html
        .replace(Regex("<.*?>"), "") // Remove all HTML tags
        .replace(Regex("&[a-zA-Z]+;"), " ")

    return text.lines().firstOrNull()?.trim()
}

suspend fun fetchMainPageUrl(): String? {
    val mainPage = stripHtmlTags(
        fetchPageContent("MediaWiki:Mainpage")?.parse?.text
    ) ?: return null

    val baseUrl = "${WIKI_URL + WIKI_SCRIPT}index.php"
    return Uri.parse(baseUrl)
        .buildUpon()
        .appendQueryParameter("title", mainPage)
        .build()
        .toString()
}

class MainActivity : AppCompatActivity() {
    private var cachedPages: List<String>? = null
    private lateinit var filteredPages: List<String>

    private lateinit var webView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView

        val contentTextView = binding.contentTextView
        val drawerLayout = binding.drawerLayout
        val navigationView = binding.navigationView

        webView.settings.javaScriptEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK

        webView.webViewClient = WikiWebViewClient()

        // Handle back press
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isOpen) {
                    // If the drawer is open when we press back, close it
                    drawerLayout.closeDrawer(GravityCompat.START)
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

        CoroutineScope(Dispatchers.IO).launch {
            createOptionsMenu(navigationView.menu, drawerLayout)
            val mainPageUrl = fetchMainPageUrl()
            if (mainPageUrl != null) {
                runOnUiThread {
                    webView.loadUrl(mainPageUrl)
                }
            }

            cachedPages = fetchAvailablePages()
            filteredPages = cachedPages ?: emptyList()

            if (filteredPages.isNotEmpty()) {
                // Handle DrawerLayout menu item clicks
                navigationView.setNavigationItemSelectedListener { menuItem ->
                    if (menuItem.itemId == R.id.action_search) return@setNavigationItemSelectedListener false
                    val selectedPageTitle = menuItem.title.toString()

                    runOnUiThread {
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

                    // Close the DrawerLayout after item selection
                    drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
            } else {
                contentTextView.text = getString(R.string.no_available_pages_found)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        webView.restoreState(savedInstanceState)
    }

    private fun createOptionsMenu(menu: Menu, drawerLayout: DrawerLayout): Boolean {
        val searchItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String): Boolean {
                filterPages(newText, menu)

                // So we don't loose focus
                if (!searchView.isFocused && drawerLayout.isOpen) {
                    searchView.post {
                        searchView.requestFocus()
                    }
                }

                return true
            }
        })

        return true
    }

    private fun filterPages(query: String, menu: Menu) {
        val lowerCaseQuery = query.lowercase(Locale.getDefault())
        filteredPages = if (query.isNotBlank()) {
            cachedPages?.filter { it.lowercase(Locale.getDefault()).contains(lowerCaseQuery) }
                ?: emptyList()
        } else emptyList()
        updateDrawerMenu(menu)
    }

    private fun updateDrawerMenu(menu: Menu) {
        menu.removeGroup(R.id.group_id)
        for (page in filteredPages) {
            menu.add(R.id.group_id, Menu.NONE, Menu.NONE, page)
                // TODO set a real icon, maybe pull from PageImages and
                //  convert to a Bitmap to draw with this or something
                .setIcon(R.drawable.avatar_10)
        }
    }

    inner class WikiWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (isExternalLink(url)) {
                showExternalLinkDialog(view, url)
                return true
            }

            if (!url.contains("useskin=$WIKI_SKIN")) {
                var modifiedUrl: String = appendParameter(url, "useskin", WIKI_SKIN)
                modifiedUrl = appendParameter(modifiedUrl, "safemode", "1")
                view.loadUrl(modifiedUrl)

                setScaleIfNeeded(url)

                return true
            }

            setScaleIfNeeded(url)

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
}
//266