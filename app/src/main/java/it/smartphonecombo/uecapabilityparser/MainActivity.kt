package it.smartphonecombo.uecapabilityparser

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Context.DOWNLOAD_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewClientCompat
import it.smartphonecombo.uecapabilityparser.server.ServerMode
import it.smartphonecombo.uecapabilityparser.ui.theme.AppTheme
import java.io.File
import java.util.UUID


class MainActivity : ComponentActivity() {
    private var serverPort = 0
    val defaultHost = "localhost"
    private val defaultHostPort: String
        get() = "$defaultHost:$serverPort"
    private var downloadCallback: ValueCallback<Array<Uri>>? = null
    private var webView: WebView? = null

    private val downloadRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            var downloadResultUris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK) {
                val clipData = result.data?.clipData
                val array = clipData?.map { it.uri }?.toTypedArray()
                    ?: WebChromeClient.FileChooserParams.parseResult(
                        result.resultCode, result.data
                    )
                downloadResultUris = array
            }
            downloadCallback?.onReceiveValue(downloadResultUris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        serverPort = ServerMode.run(0)
        Log.d("server-port", serverPort.toString())
        setContent {
            AppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    MyWebView("http://$defaultHostPort/")
                }
            }
        }
    }

    @Suppress("deprecation")
    override fun onBackPressed() {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            super.onBackPressed()
        }
    }


    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun MyWebView(mUrl: String) {
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", AssetsPathHandler(this))
            .setDomain(defaultHostPort)
            .setHttpAllowed(true)
            .build()

        AndroidView(factory = { it ->
            val swController = ServiceWorkerController.getInstance()
            swController.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                    val customUrl = urlInterception(request.url, defaultHost) ?: return null
                    Log.i("url", "${request.url} - $customUrl")
                    return assetLoader.shouldInterceptRequest(customUrl)
                }
            })
            WebView(it).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                setDownloadListener { url: String, _: String, contentDisposition: String, mimetype: String, _: Long ->
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P || ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        download(this@MainActivity, url, contentDisposition, mimetype)
                    }
                }

                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        val customUrl = urlInterception(request.url, defaultHost) ?: return null
                        Log.i("url", "${request.url} - $customUrl")
                        return assetLoader.shouldInterceptRequest(customUrl)
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(
                        mWebView: WebView,
                        filePathCallback: ValueCallback<Array<Uri>>,
                        fileChooserParams: FileChooserParams
                    ): Boolean {
                        val fileIntent = fileChooserParams.createIntent()
                        val acceptTypes = fileChooserParams.acceptTypes

                        /* Check if there're multiple accept types */

                        if (acceptTypes.any { it.isNotBlank() }) {
                            fileIntent.putExtra(Intent.EXTRA_MIME_TYPES, acceptTypes)
                        }

                        /* Check if multiple file are supported */
                        val multiple =
                            fileChooserParams.mode == FileChooserParams.MODE_OPEN_MULTIPLE

                        if (multiple) {
                            fileIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                        }

                        try {
                            downloadCallback = filePathCallback
                            downloadRegister.launch(fileIntent)
                        } catch (e: ActivityNotFoundException) {
                            filePathCallback.onReceiveValue(null)
                        }
                        return true
                    }
                }
                settings.javaScriptEnabled = true
                loadUrl(mUrl)
                webView = this
            }
        }, update = {
            it.loadUrl(mUrl)
        })
    }
}

fun urlInterception(url: Uri, defaultHost: String): Uri? {
    val whitelistPaths = arrayOf("/assets/web/", "/version", "/store/", "/parse/", "/csv/", "/status")
    val path = url.path
    if (url.host == defaultHost && path != null && whitelistPaths.none { path.startsWith(it) }) {
        val index = if (path.endsWith("/")) "index.html" else ""
        return url.buildUpon().path("/assets/web$path$index").build()
    }
    return null
}

fun <R> ClipData.map(transform: (ClipData.Item) -> R): List<R> {
    val list = mutableListOf<R>()
    for (i in 0 until this.itemCount) {
        val item = this.getItemAt(i)
        list.add(transform(item))
    }
    return list
}

private fun download(
    context: Context,
    url: String,
    contentDisposition: String,
    mimetype: String
) {
    val dm = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
    var filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
    if (filename.length > 32) {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype)
        val extensionString = if (extension == null) "" else ".$extension"
        filename = "${UUID.randomUUID()}$extensionString"
    }
    val description = "Downloading $filename"
    val uri = Uri.parse(url)
    if (url.startsWith("data:")) {
        writeFileFromBase64Uri(url, filename)
    } else {
        val request = DownloadManager.Request(uri)
        request.setMimeType(mimetype)
        request.setDescription(description)
        request.setTitle(filename)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
        dm.enqueue(request)
    }
    Toast.makeText(
        context.applicationContext,
        description,
        Toast.LENGTH_LONG
    ).show()
}

fun writeFileFromBase64Uri(fileContent: String, filename: String) {
    val attachment = fileContent.replace("""data:(.*)base64,""".toRegex(), "")
    val byteArr = Base64.decode(attachment, Base64.DEFAULT)
    val file = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        filename
    )
    file.createNewFile()
    file.writeBytes(byteArr)
}



