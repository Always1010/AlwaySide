package com.alwayside.android

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.util.Locale

/** Displays Google's supplied HTML/CSS offline; this WebView never navigates or executes scripts. */
@Suppress("DEPRECATION")
@SuppressLint("ClickableViewAccessibility") // The listener only coordinates scrolling; WebView handles every click.
internal fun Context.searchSuggestionsView(html: String): View {
    require(html.length <= 32768) { "搜索建议内容过大" }
    val owner = this
    return WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(128))
        contentDescription = "Google 搜索建议"
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        isLongClickable = false
        setOnLongClickListener { true }
        setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        settings.apply {
            javaScriptEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            blockNetworkLoads = true
            blockNetworkImage = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame && request.hasGesture() && !request.isRedirect && request.method == "GET") {
                    val uri = runCatching { URI(request.url.toString()) }.getOrNull()
                    if (uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                        !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port != 0 && uri.port in -1..65535) {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri.toASCIIString()))
                            .addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { owner.startActivity(intent) }
                    }
                }
                return true // Block every in-WebView navigation, including automatic redirects and custom schemes.
            }

            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                WebResourceResponse("text/plain", "UTF-8", ByteArray(0).inputStream())
        }
        // No base URL or history URL: relative links/resources have no remote origin to resolve against.
        // Inline HTML/CSS is preserved; no JavaScript bridge, string evaluation or remote loading is enabled.
        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}
