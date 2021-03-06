package org.ligi.survivalmanual

import android.annotation.TargetApi
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.support.design.widget.NavigationView
import android.support.v4.view.MenuItemCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AlertDialog
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.SearchView
import android.support.v7.widget.Toolbar
import android.text.method.LinkMovementMethod
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import com.github.rjeschke.txtmark.Processor
import kotlinx.android.synthetic.main.activity_main.*
import org.ligi.compat.HtmlCompat
import org.ligi.compat.WebViewCompat
import org.ligi.kaxt.setVisibility
import org.ligi.kaxt.startActivityFromClass
import org.ligi.snackengage.SnackEngage
import org.ligi.snackengage.snacks.DefaultRateSnack
import org.ligi.survivalmanual.ImageLogic.isImage


class MainActivity : AppCompatActivity() {

    private val drawerToggle by lazy { ActionBarDrawerToggle(this, drawer_layout, R.string.drawer_open, R.string.drawer_close) }

    lateinit var currentUrl: String
    lateinit var textInput: MutableList<String>

    fun imageWidth(): Int {
        val totalWidthPadding = (resources.getDimension(R.dimen.content_padding) * 2).toInt()
        return Math.min(contentRecycler.width - totalWidthPadding, contentRecycler.height)
    }

    val onURLClick: (String) -> Unit = {
        supportActionBar?.subtitle?.let { subtitle ->
            EventTracker.trackContent(it, subtitle.toString(), "clicked_in_text")
        }

        if (isImage(it)) {
            val intent = Intent(this, ImageViewActivity::class.java)
            intent.putExtra("URL", it)
            startActivity(intent)
        } else {
            processURL(it)
        }
    }

    private val linearLayoutManager by lazy { LinearLayoutManager(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        drawer_layout.addDrawerListener(drawerToggle)
        setSupportActionBar(findViewById(R.id.toolbar) as Toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val navigationView = findViewById(R.id.navigationView) as NavigationView

        navigationView.setNavigationItemSelectedListener { item ->
            drawer_layout.closeDrawers()
            processURL(NavigationDefinitions.menu2htmlMap[item.itemId]!!)
            true
        }

        contentRecycler.layoutManager = linearLayoutManager

        class RememberPositionOnScroll : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView?, dx: Int, dy: Int) {
                State.lastScrollPos = (contentRecycler.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                super.onScrolled(recyclerView, dx, dy)
            }
        }

        contentRecycler.addOnScrollListener(RememberPositionOnScroll())

        SnackEngage.from(this).withSnack(DefaultRateSnack()).build().engageWhenAppropriate()

        contentRecycler.post {
            processURL(State.lastVisitedURL)
            switchMode(false)
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_search).isVisible = State.allowSearch()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        if (Build.VERSION.SDK_INT >= 19) {
            menuInflater.inflate(R.menu.print, menu)
        }

        val searchView = MenuItemCompat.getActionView(menu.findItem(R.id.action_search)) as SearchView

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                val adapter = contentRecycler.adapter
                if (adapter is MarkdownRecyclerAdapter) {
                    adapter.wordHighLight = newText

                    val first = linearLayoutManager.findFirstVisibleItemPosition()
                    val searchRegex = Regex("(?i)$newText")
                    if (!adapter.list[first].contains(searchRegex)) {
                        val next = (first..adapter.list.lastIndex).firstOrNull {
                            adapter.list[it].contains(searchRegex)
                        }
                        if (next != null) {
                            contentRecycler.smoothScrollToPosition(next)
                        }
                    }
                    adapter.notifyDataSetChanged()
                }

                return true
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                return true
            }

        })
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.menu_help -> {
            EventTracker.trackGeneric("help")
            val textView = TextView(this)
            val helpText = getString(R.string.help_text).replace("\$VERSION", BuildConfig.VERSION_NAME)
            textView.text = HtmlCompat.fromHtml(helpText)
            textView.movementMethod = LinkMovementMethod.getInstance()
            val padding = resources.getDimensionPixelSize(R.dimen.help_padding)
            textView.setPadding(padding, padding, padding, padding)
            AlertDialog.Builder(this)
                    .setTitle(R.string.help_title)
                    .setView(textView)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            true
        }

        R.id.menu_settings -> {
            startActivityFromClass(PreferenceActivity::class.java)
            true
        }

        R.id.menu_share -> {
            EventTracker.trackGeneric("share")
            val intent = Intent(Intent.ACTION_SEND)
            intent.putExtra(Intent.EXTRA_TEXT, "https://play.google.com/store/apps/details?id=" + BuildConfig.APPLICATION_ID)
            intent.type = "text/plain"
            startActivity(Intent.createChooser(intent, null))
            true
        }

        R.id.menu_rate -> {
            EventTracker.trackGeneric("rate")
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse("market://details?id=" + BuildConfig.APPLICATION_ID)
            startActivity(intent)

            true
        }

        R.id.menu_print -> {
            EventTracker.trackGeneric("print", currentUrl)
            val newWebView = WebView(this@MainActivity)
            newWebView.setWebViewClient(object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false
                override fun onPageFinished(view: WebView, url: String) = createWebPrintJob(view)
            })

            val htmlDocument = Processor.process(assets.open(currentUrl).reader().readText())
            newWebView.loadDataWithBaseURL("file:///android_asset/md/", htmlDocument, "text/HTML", "UTF-8", null)

            true
        }

        else -> drawerToggle.onOptionsItemSelected(item)
    }


    @TargetApi(19)
    private fun createWebPrintJob(webView: WebView) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = getString(R.string.app_name) + " Document"
        val printAdapter = WebViewCompat.createPrintDocumentAdapter(webView, jobName)
        printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
    }

    private fun processURL(url: String) {
        currentUrl = url
        val newTitle = getString(NavigationDefinitions.getTitleResByURL(url))
        EventTracker.trackContent(url, newTitle, "processMenuId")

        supportActionBar?.subtitle = newTitle

        State.lastVisitedURL = url

        textInput = TextSplitter.split(assets.open(getFullMarkDownURL(currentUrl)))

        contentRecycler.adapter = MarkdownRecyclerAdapter(textInput, imageWidth(), onURLClick)
    }


    private fun getFullMarkDownURL(url: String) = "md/$url.md"

    override fun onPostCreate(savedInstanceState: Bundle?) {
        super.onPostCreate(savedInstanceState)
        drawerToggle.syncState()
    }

    @TargetApi(11)
    fun recreateActivity() {
        if (Build.VERSION.SDK_INT >= 11) {
            recreate()
        }
    }

    var lastFontSize = State.getFontSize()
    var lastNightMode = State.getNightMode()

    override fun onResume() {
        super.onResume()
        fab.setVisibility(State.allowEdit())
        if (lastFontSize != State.getFontSize()) {
            contentRecycler.adapter?.notifyDataSetChanged()
            lastFontSize = State.getFontSize()
        }
        if (lastNightMode != State.getNightMode()) {
            recreateActivity()
            lastNightMode = State.getNightMode()
        }

        supportInvalidateOptionsMenu()
    }

    fun switchMode(editing: Boolean) {
        fab.setOnClickListener {
            switchMode(!editing)
        }

        if (editing) {
            fab.setImageResource(R.drawable.ic_image_remove_red_eye)
            contentRecycler.adapter = EditingRecyclerAdapter(textInput)
        } else {
            fab.setImageResource(R.drawable.ic_editor_mode_edit)
            contentRecycler.adapter = MarkdownRecyclerAdapter(textInput, imageWidth(), onURLClick)
        }

        contentRecycler.scrollToPosition(State.lastScrollPos)

    }
}
