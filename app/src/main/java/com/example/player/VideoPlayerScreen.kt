package com.example.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.model.MediaItem as AppMediaItem
import com.example.model.StreamServer
import kotlinx.coroutines.delay

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    mediaItem: AppMediaItem,
    playlist: List<AppMediaItem> = emptyList(),
    isTvMode: Boolean = false,
    onSelectMedia: (AppMediaItem) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val configuration = LocalConfiguration.current
    val isScreenLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    var currentMedia by remember(mediaItem) { mutableStateOf(mediaItem) }
    val servers = remember(currentMedia) { currentMedia.getAllServers() }
    var selectedServerIndex by remember(currentMedia) { mutableIntStateOf(0) }
    var currentUrl by remember(currentMedia, selectedServerIndex) {
        mutableStateOf(servers.getOrNull(selectedServerIndex)?.url ?: currentMedia.streamUrl)
    }

    var isFullscreen by rememberSaveable { mutableStateOf(isScreenLandscape || isTvMode) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Immediately trigger buffering state whenever channel or server URL changes
    LaunchedEffect(currentMedia.id, currentUrl) {
        isBuffering = true
        errorMessage = null
    }
    var showControls by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var showQuickChannelDrawer by remember { mutableStateOf(false) }

    // Intercept system/mobile back press to safely exit player and return to previous list
    BackHandler {
        if (showQuickChannelDrawer) {
            showQuickChannelDrawer = false
        } else {
            onBack()
        }
    }

    // Synchronize fullscreen state with device orientation if physically rotated or in TV Mode
    LaunchedEffect(isScreenLandscape, isTvMode) {
        if ((isScreenLandscape || isTvMode) && !isFullscreen) {
            isFullscreen = true
        }
        if (isTvMode) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // Reset orientation only when exiting the player screen entirely
    DisposableEffect(isTvMode) {
        onDispose {
            if (isTvMode) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
    }

    // Hide System Bars (Status Bar & Navigation Bar) completely in fullscreen mode
    DisposableEffect(isFullscreen, activity) {
        val window = activity?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, !isFullscreen)
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isFullscreen) {
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
                insetsController.hide(WindowInsetsCompat.Type.captionBar())
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    window.attributes.layoutInDisplayCutoutMode =
                        android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                )
            } else {
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
        onDispose {
            val w = activity?.window
            if (w != null) {
                WindowCompat.setDecorFitsSystemWindows(w, true)
                val insetsController = WindowCompat.getInsetsController(w, w.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // Re-enforce system bar hiding when fullscreen or controls change
    LaunchedEffect(isFullscreen, showControls) {
        if (isFullscreen) {
            val window = activity?.window
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.systemBars())
                insetsController.hide(WindowInsetsCompat.Type.statusBars())
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
                insetsController.hide(WindowInsetsCompat.Type.captionBar())
            }
        }
    }

    // Auto hide controls after 4 seconds of inactivity
    LaunchedEffect(showControls, isPlaying) {
        if (showControls && isPlaying) {
            delay(4500)
            showControls = false
        }
    }

    // Setup ExoPlayer instance with custom http data source, headers and dynamic pipe parsing
    val exoPlayer = remember(currentUrl, currentMedia) {
        var cleanUrl = currentUrl.trim()
        var extractedUa: String? = currentMedia.userAgent
        var extractedReferer: String? = currentMedia.referrer
        var extractedOrigin: String? = currentMedia.origin
        var extractedCookie: String? = currentMedia.cookie
        val dynamicHeaders = mutableMapOf<String, String>()

        // Parse pipe syntax: http://stream.m3u8|User-Agent=...&Referer=...
        if (cleanUrl.contains("|")) {
            val parts = cleanUrl.split("|", limit = 2)
            cleanUrl = parts[0].trim()
            val pairs = parts[1].split("&")
            for (pair in pairs) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim()
                    val rawV = kv[1].trim()
                    val v = try {
                        java.net.URLDecoder.decode(rawV, "UTF-8")
                    } catch (_: Exception) {
                        rawV
                    }
                    when {
                        k.equals("User-Agent", ignoreCase = true) || k.equals("http-user-agent", ignoreCase = true) -> extractedUa = v
                        k.equals("Referer", ignoreCase = true) || k.equals("Referrer", ignoreCase = true) || k.equals("http-referrer", ignoreCase = true) || k.equals("http-referer", ignoreCase = true) -> extractedReferer = v
                        k.equals("Origin", ignoreCase = true) || k.equals("http-origin", ignoreCase = true) -> extractedOrigin = v
                        k.equals("Cookie", ignoreCase = true) || k.equals("http-cookie", ignoreCase = true) -> extractedCookie = v
                        else -> dynamicHeaders[k] = v
                    }
                }
            }
        }

        // Apply custom headers from MediaItem
        currentMedia.customHeaders?.let { dynamicHeaders.putAll(it) }

        // Domain-specific smart headers (Toffee, Bioscope, TSports, etc.)
        val isToffee = cleanUrl.contains("toffeelive.com", ignoreCase = true) ||
                cleanUrl.contains("toffee", ignoreCase = true) ||
                cleanUrl.contains("bldcmprod-cdn", ignoreCase = true) ||
                currentMedia.category.contains("toffee", ignoreCase = true)

        if (isToffee) {
            if (extractedUa.isNullOrBlank()) extractedUa = "Toffee (Linux;Android 14)"
            if (extractedReferer.isNullOrBlank()) extractedReferer = "https://toffeelive.com/"
            if (extractedOrigin.isNullOrBlank()) extractedOrigin = "https://toffeelive.com"
        }

        val finalUserAgent = extractedUa ?: "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 NAFITV24"

        val requestHeaders = mutableMapOf<String, String>()
        requestHeaders["User-Agent"] = finalUserAgent
        if (!extractedReferer.isNullOrBlank()) {
            requestHeaders["Referer"] = extractedReferer
        }
        if (!extractedOrigin.isNullOrBlank()) {
            requestHeaders["Origin"] = extractedOrigin
        }
        if (!extractedCookie.isNullOrBlank()) {
            requestHeaders["Cookie"] = extractedCookie
        }
        requestHeaders["Accept"] = "*/*"
        requestHeaders["Connection"] = "keep-alive"
        requestHeaders.putAll(dynamicHeaders)

        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(25000)
            .setReadTimeoutMs(35000)
            .setUserAgent(finalUserAgent)
            .setDefaultRequestProperties(requestHeaders)

        val (finalCleanUrl, drmConfig) = com.example.util.DrmHelper.extractDrmConfig(
            rawUrl = currentUrl,
            itemScheme = currentMedia.drmScheme,
            itemLicenseUrl = currentMedia.drmLicenseUrl,
            itemLicenseKey = currentMedia.drmLicenseKey,
            itemHeaders = currentMedia.drmHeaders,
            itemManifestType = currentMedia.manifestType
        )

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpDataSourceFactory)

        if (drmConfig != null) {
            val drmSessionManager = com.example.util.DrmHelper.createDrmSessionManager(drmConfig, httpDataSourceFactory)
            if (drmSessionManager != null) {
                mediaSourceFactory.setDrmSessionManagerProvider { drmSessionManager }
            }
        }

        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val trackSelector = androidx.media3.exoplayer.trackselection.DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setAllowMultipleAdaptiveSelections(true)
            )
        }

        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 2500,
                /* maxBufferMs = */ 15000,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 600
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build().apply {
                val mediaItemBuilder = MediaItem.Builder()
                    .setUri(finalCleanUrl)

                if (drmConfig != null) {
                    val drmConfigBuilder = MediaItem.DrmConfiguration.Builder(drmConfig.schemeUuid)
                    if (!drmConfig.licenseUrl.isNullOrBlank()) {
                        drmConfigBuilder.setLicenseUri(drmConfig.licenseUrl)
                    }
                    if (drmConfig.headers.isNotEmpty()) {
                        drmConfigBuilder.setLicenseRequestHeaders(drmConfig.headers)
                    }
                    drmConfigBuilder.setMultiSession(true)
                    mediaItemBuilder.setDrmConfiguration(drmConfigBuilder.build())
                }

                val isMpd = finalCleanUrl.contains(".mpd", ignoreCase = true) ||
                        finalCleanUrl.contains("dash", ignoreCase = true) ||
                        drmConfig?.manifestType?.equals("mpd", ignoreCase = true) == true ||
                        currentMedia.manifestType?.equals("mpd", ignoreCase = true) == true

                val isM3u8 = finalCleanUrl.contains(".m3u8", ignoreCase = true) ||
                        finalCleanUrl.contains("hls", ignoreCase = true) ||
                        drmConfig?.manifestType?.equals("hls", ignoreCase = true) == true ||
                        currentMedia.manifestType?.equals("hls", ignoreCase = true) == true ||
                        isToffee

                if (isMpd) {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_MPD)
                } else if (isM3u8) {
                    mediaItemBuilder.setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                }

                setMediaItem(mediaItemBuilder.build())
                playWhenReady = true
                prepare()

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                isBuffering = true
                                errorMessage = null
                            }
                            Player.STATE_READY -> {
                                isBuffering = false
                                errorMessage = null
                                isPlaying = playWhenReady
                                durationMs = duration.coerceAtLeast(0L)
                            }
                            Player.STATE_ENDED -> {
                                isBuffering = false
                                isPlaying = false
                            }
                            Player.STATE_IDLE -> {
                                isBuffering = false
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        isBuffering = false
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        isBuffering = false
                        // Auto-switch to next server if available
                        if (servers.size > 1 && selectedServerIndex < servers.size - 1) {
                            selectedServerIndex++
                            currentUrl = servers[selectedServerIndex].url
                            errorMessage = "সার্ভার পরিবর্তন হচ্ছে: ${servers[selectedServerIndex].name}..."
                        } else {
                            errorMessage = "ভিডিও লোড হচ্ছে না (${error.errorCodeName})। বিকল্প সার্ভার বেছে নিন অথবা স্ট্রিম লিঙ্ক চেক করুন।"
                        }
                    }
                })
            }
    }

    // Periodic time progress tracker
    LaunchedEffect(exoPlayer, isPlaying) {
        while (true) {
            if (exoPlayer.isPlaying && !isDraggingSlider) {
                currentPositionMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                durationMs = exoPlayer.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    // Cleanup on dispose
    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    LaunchedEffect(isFullscreen, showControls) {
        try {
            focusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    // Switch to Next / Previous Channel (Smoothly switches in fullscreen without exiting)
    fun switchChannel(delta: Int) {
        val list = if (playlist.isNotEmpty()) playlist else listOf(currentMedia)
        val currentIndex = list.indexOfFirst { it.id == currentMedia.id || it.streamUrl == currentMedia.streamUrl }
        if (currentIndex != -1 && list.isNotEmpty()) {
            val nextIndex = (currentIndex + delta).mod(list.size)
            val nextItem = list[nextIndex]
            isBuffering = true
            currentMedia = nextItem
            selectedServerIndex = 0
            val newServers = nextItem.getAllServers()
            currentUrl = newServers.firstOrNull()?.url ?: nextItem.streamUrl
            errorMessage = null
            onSelectMedia(nextItem)
        }
    }

    fun toggleFullscreen() {
        val targetLandscape = !isFullscreen
        isFullscreen = targetLandscape
        activity?.requestedOrientation = if (targetLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    fun handleRemoteKeyEvent(keyEvent: androidx.compose.ui.input.key.KeyEvent): Boolean {
        if (keyEvent.type != KeyEventType.KeyDown) return false
        val nativeEvent = keyEvent.nativeKeyEvent
        val keyCode = nativeEvent.keyCode
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_SPACE -> {
                if (showControls) {
                    if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                } else {
                    showControls = true
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                switchChannel(-1)
                showControls = true
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                switchChannel(1)
                showControls = true
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (durationMs > 0) {
                    val target = maxOf(0L, currentPositionMs - 10000L)
                    exoPlayer.seekTo(target)
                } else {
                    switchChannel(-1)
                }
                showControls = true
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (durationMs > 0) {
                    val target = minOf(durationMs, currentPositionMs + 10000L)
                    exoPlayer.seekTo(target)
                } else {
                    switchChannel(1)
                }
                showControls = true
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exoPlayer.play()
                isPlaying = true
                true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exoPlayer.pause()
                isPlaying = false
                true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                switchChannel(1)
                showControls = true
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                switchChannel(-1)
                showControls = true
                true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (durationMs > 0) {
                    exoPlayer.seekTo(minOf(durationMs, currentPositionMs + 10000L))
                }
                true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (durationMs > 0) {
                    exoPlayer.seekTo(maxOf(0L, currentPositionMs - 10000L))
                }
                true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_BUTTON_Y -> {
                showQuickChannelDrawer = !showQuickChannelDrawer
                true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                if (showQuickChannelDrawer) {
                    showQuickChannelDrawer = false
                    true
                } else if (isFullscreen) {
                    toggleFullscreen()
                    true
                } else {
                    onBack()
                    true
                }
            }
            else -> false
        }
    }

    // Formatting milliseconds to mm:ss
    fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    val playerModifier = modifier
        .focusRequester(focusRequester)
        .focusable()
        .onKeyEvent { handleRemoteKeyEvent(it) }

    if (isFullscreen) {
        // FULLSCREEN LANDSCAPE VIEW (Edge-to-Edge)
        Box(
            modifier = playerModifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { showControls = !showControls }
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        this.resizeMode = resizeMode
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        setKeepContentOnPlayerReset(true)
                        keepScreenOn = true
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { playerView ->
                    playerView.player = exoPlayer
                    playerView.resizeMode = resizeMode
                },
                modifier = Modifier.fillMaxSize()
            )

            // Buffering Indicator with NAFI TV Logo & Bengali Loading text
            if (isBuffering) {
                PlayerBufferingLogoOverlay(mediaTitle = currentMedia.title, isCompact = false)
            }

            // Error Overlay
            if (errorMessage != null) {
                FullscreenErrorOverlay(
                    message = errorMessage ?: "",
                    onRetry = {
                        errorMessage = null
                        exoPlayer.seekTo(0)
                        exoPlayer.prepare()
                        exoPlayer.play()
                    }
                )
            }

            // Controls Overlay
            if (showControls) {
                FullscreenControlsOverlay(
                    media = currentMedia,
                    servers = servers,
                    selectedServerIndex = selectedServerIndex,
                    onSelectServer = { index ->
                        selectedServerIndex = index
                        currentUrl = servers[index].url
                        errorMessage = null
                    },
                    isPlaying = isPlaying,
                    isMuted = isMuted,
                    playbackSpeed = playbackSpeed,
                    currentPositionMs = currentPositionMs,
                    durationMs = durationMs,
                    isDraggingSlider = isDraggingSlider,
                    sliderPosition = sliderPosition,
                    onSliderChange = {
                        isDraggingSlider = true
                        sliderPosition = it
                    },
                    onSliderChangeFinished = {
                        isDraggingSlider = false
                        if (durationMs > 0) {
                            val seekTo = (sliderPosition * durationMs).toLong()
                            exoPlayer.seekTo(seekTo)
                        }
                    },
                    onPlayPause = {
                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                    },
                    onToggleMute = {
                        isMuted = !isMuted
                        exoPlayer.volume = if (isMuted) 0f else 1f
                    },
                    onToggleSpeed = {
                        playbackSpeed = when (playbackSpeed) {
                            1.0f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2.0f
                            else -> 1.0f
                        }
                        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
                    },
                    onPrevChannel = { switchChannel(-1) },
                    onNextChannel = { switchChannel(1) },
                    onToggleAspect = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    onToggleFullscreen = { toggleFullscreen() },
                    onToggleChannelDrawer = { showQuickChannelDrawer = !showQuickChannelDrawer },
                    onClose = { toggleFullscreen() }
                )
            }

            // Quick Channel Drawer in Fullscreen
            if (showQuickChannelDrawer && playlist.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(260.dp)
                        .align(Alignment.CenterEnd)
                        .background(Color(0xFF0F172A).copy(alpha = 0.95f))
                        .padding(12.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "চ্যানেল তালিকা (${playlist.size})",
                                color = Color(0xFF00E5FF),
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            IconButton(onClick = { showQuickChannelDrawer = false }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                            }
                        }
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(playlist) { item ->
                                val isCurrent = item.id == currentMedia.id
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isBuffering = true
                                            currentMedia = item
                                            selectedServerIndex = 0
                                            val newServers = item.getAllServers()
                                            currentUrl = newServers.firstOrNull()?.url ?: item.streamUrl
                                            errorMessage = null
                                            onSelectMedia(item)
                                            // Drawer stays open as requested
                                        },
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (isCurrent) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)) else null,
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isCurrent) Color(0xFF00E5FF).copy(alpha = 0.25f) else Color(0xFF1E293B)
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        AsyncImage(
                                            model = item.logoUrl ?: "https://images.unsplash.com/photo-1585829365295-ab7cd400c167?w=100",
                                            contentDescription = item.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color.White)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = item.title,
                                                    color = if (isCurrent) Color(0xFF00E5FF) else Color.White,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (isCurrent) {
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "▶ PLAYING",
                                                        color = Color(0xFF00E5FF),
                                                        fontWeight = FontWeight.ExtraBold,
                                                        fontSize = 9.sp
                                                    )
                                                }
                                            }
                                            Text(
                                                text = item.category,
                                                color = Color(0xFF94A3B8),
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // PORTRAIT EMBEDDED PLAYER VIEW (Screenshot 4 layout)
        Column(
            modifier = playerModifier
                .fillMaxSize()
                .background(Color(0xFF0B1120))
        ) {
            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "NAFI TV 24",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF00E5FF).copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = if (currentMedia.type == com.example.model.MediaType.MOVIE) "Movies" else "Live TV",
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = {
                            toggleFullscreen()
                            android.widget.Toast.makeText(context, "টিভি মোড (TV Mode) সক্রিয় করা হয়েছে", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Tv,
                            contentDescription = "TV Mode",
                            tint = if (isFullscreen) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = "Protected", tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = {
                            errorMessage = null
                            exoPlayer.seekTo(0)
                            exoPlayer.prepare()
                            exoPlayer.play()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = Color.White)
                    }
                }
            }

            // Embedded 16:9 Video Frame
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black)
                    .clickable { showControls = !showControls }
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            this.resizeMode = resizeMode
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                            setKeepContentOnPlayerReset(true)
                            keepScreenOn = true
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { playerView ->
                        playerView.player = exoPlayer
                        playerView.resizeMode = resizeMode
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Top bar overlay inside video player: Close (X) circle button + Server tag + HD Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                            )
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f))
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = servers.getOrNull(selectedServerIndex)?.name?.take(14) ?: "MAIN",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF2563EB).copy(alpha = 0.8f)
                    ) {
                        Text(
                            text = "HLS • ${currentMedia.quality}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Buffering Overlay with NAFI TV Logo & Bengali Loading text
                if (isBuffering) {
                    PlayerBufferingLogoOverlay(mediaTitle = currentMedia.title, isCompact = true)
                }

                // Error Overlay
                if (errorMessage != null) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier.padding(16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.95f))
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.Warning, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(28.dp))
                                Text(text = errorMessage ?: "", color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center)
                                Button(
                                    onClick = {
                                        errorMessage = null
                                        exoPlayer.seekTo(0)
                                        exoPlayer.prepare()
                                        exoPlayer.play()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("Retry", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Bottom Controls Bar inside Embedded Player (Screenshot 4 style)
                if (showControls) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                                )
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            // Time stamps & Scrubber Slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatTime(currentPositionMs),
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                val progressFraction = if (durationMs > 0) {
                                    (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                                } else 0f

                                Slider(
                                    value = if (isDraggingSlider) sliderPosition else progressFraction,
                                    onValueChange = {
                                        isDraggingSlider = true
                                        sliderPosition = it
                                    },
                                    onValueChangeFinished = {
                                        isDraggingSlider = false
                                        if (durationMs > 0) {
                                            exoPlayer.seekTo((sliderPosition * durationMs).toLong())
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                        .height(18.dp),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color(0xFF00E5FF),
                                        activeTrackColor = Color(0xFF00E5FF),
                                        inactiveTrackColor = Color(0xFF334155)
                                    )
                                )

                                Text(
                                    text = if (durationMs > 0) formatTime(durationMs) else if (currentMedia.isLive) "LIVE" else "00:00",
                                    color = if (currentMedia.isLive && durationMs <= 0) Color.Red else Color.LightGray,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Interactive Controls Row (Screenshot 4 icons)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Mute / Volume
                                IconButton(
                                    onClick = {
                                        isMuted = !isMuted
                                        exoPlayer.volume = if (isMuted) 0f else 1f
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                        contentDescription = "Mute",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Playback Speed
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF1E293B),
                                    modifier = Modifier.clickable {
                                        playbackSpeed = when (playbackSpeed) {
                                            1.0f -> 1.25f
                                            1.25f -> 1.5f
                                            1.5f -> 2.0f
                                            else -> 1.0f
                                        }
                                        exoPlayer.playbackParameters = PlaybackParameters(playbackSpeed)
                                    }
                                ) {
                                    Text(
                                        text = "${playbackSpeed}x",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }

                                // Previous Item
                                IconButton(
                                    onClick = { switchChannel(-1) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SkipPrevious,
                                        contentDescription = "Previous",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Play / Pause (Large Center Cyan Button)
                                IconButton(
                                    onClick = {
                                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E5FF))
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.Black,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                // Next Item
                                IconButton(
                                    onClick = { switchChannel(1) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SkipNext,
                                        contentDescription = "Next",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                // Aspect Ratio (Tv/Crop)
                                IconButton(
                                    onClick = {
                                        resizeMode = when (resizeMode) {
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.AspectRatio,
                                        contentDescription = "Aspect Ratio",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Fullscreen Toggle (Auto Rotates to Landscape)
                                IconButton(
                                    onClick = { toggleFullscreen() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Fullscreen,
                                        contentDescription = "Fullscreen",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Below Player Content in Portrait: Info, Servers, and Channel Switcher
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // Channel Title & Details
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentMedia.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (currentMedia.isLive) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color.Red)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(
                                    text = "LIVE NOW",
                                    color = Color.Red,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = "${currentMedia.category} • ${currentMedia.quality}",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    // Fullscreen Button Shortcut
                    Button(
                        onClick = { toggleFullscreen() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Rounded.Fullscreen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ফুল স্ক্রিন", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Multi-Server Chips (Only display when the channel actually has more than 1 server available)
                if (servers.size > 1) {
                    Text(
                        text = "সার্ভার নির্বাচন (${servers.size} টি সার্ভার উপলব্ধ):",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(servers) { index, server ->
                            val isSelected = selectedServerIndex == index
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.clickable {
                                    if (selectedServerIndex != index) {
                                        selectedServerIndex = index
                                        currentUrl = server.url
                                        isBuffering = true
                                        errorMessage = null
                                    }
                                }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Rounded.CheckCircle else Icons.Rounded.Dns,
                                        contentDescription = null,
                                        tint = if (isSelected) Color.Black else Color(0xFF00E5FF),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = server.name,
                                        color = if (isSelected) Color.Black else Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                }

                // Related / Other Channels Grid in Portrait
                Text(
                    text = "📺 অন্যান্য চ্যানেলসমূহ (${playlist.size}):",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(playlist) { item ->
                        val isCurrent = item.id == currentMedia.id || item.streamUrl == currentMedia.streamUrl
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    isBuffering = true
                                    currentMedia = item
                                    selectedServerIndex = 0
                                    val newServers = item.getAllServers()
                                    currentUrl = newServers.firstOrNull()?.url ?: item.streamUrl
                                    errorMessage = null
                                    onSelectMedia(item)
                                },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCurrent) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E293B)
                            ),
                            border = if (isCurrent) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)) else null
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    AsyncImage(
                                        model = item.logoUrl ?: "https://images.unsplash.com/photo-1585829365295-ab7cd400c167?w=100",
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(38.dp).clip(CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = item.title,
                                    color = if (isCurrent) Color(0xFF00E5FF) else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Fullscreen & Embedded Buffering / Loading Overlay Components
// (User Requested: কিছু চ্যানেল প্লে হতে সময় নেয় সেই গুলো প্লে হবার আগে আ্যপ লোগো দেখাবেন লোডিং হচ্ছে এই লেখা লোগোর নিচে থাকবে)
// ---------------------------------------------------------------------
@Composable
private fun PlayerBufferingLogoOverlay(
    mediaTitle: String = "",
    isCompact: Boolean = false
) {
    val infiniteTransition = rememberInfiniteTransition(label = "player_buffering_anim")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logoPulse"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.92f)),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.5.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color(0xFF00E5FF).copy(alpha = glowAlpha),
                        Color(0xFF3B82F6).copy(alpha = glowAlpha),
                        Color(0xFFA855F7).copy(alpha = glowAlpha * 0.7f)
                    )
                )
            ),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(
                    horizontal = if (isCompact) 20.dp else 32.dp,
                    vertical = if (isCompact) 14.dp else 22.dp
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)
            ) {
                // Animated NAFI TV Logo with Glowing Ambient Aura
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(if (isCompact) 56.dp else 76.dp)
                ) {
                    // Outer Soft Halo
                    Box(
                        modifier = Modifier
                            .size(if (isCompact) 56.dp else 76.dp)
                            .scale(pulseScale * 1.12f)
                            .alpha(glowAlpha * 0.4f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.radialGradient(
                                    listOf(Color(0xFF00E5FF), Color(0xFF3B82F6), Color.Transparent)
                                )
                            )
                    )

                    // Sharp Logo
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "NAFI TV Logo",
                        modifier = Modifier
                            .size(if (isCompact) 48.dp else 64.dp)
                            .scale(pulseScale)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit
                    )
                }

                // Bengali "লোডিং হচ্ছে..." text (As requested: "লোডিং হচ্ছে এই লেখা লোগোর নিচে থাকবে")
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "লোডিং হচ্ছে...",
                        color = Color.White,
                        fontSize = if (isCompact) 14.sp else 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    if (mediaTitle.isNotBlank()) {
                        Text(
                            text = mediaTitle,
                            color = Color(0xFF00E5FF),
                            fontSize = if (isCompact) 11.sp else 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Smooth Glowing Progress Bar
                LinearProgressIndicator(
                    modifier = Modifier
                        .width(if (isCompact) 100.dp else 130.dp)
                        .height(3.dp)
                        .clip(CircleShape),
                    color = Color(0xFF00E5FF),
                    trackColor = Color(0xFF334155)
                )

                // Subtitle Badge
                Text(
                    text = "NAFI TV 24 • ফাস্ট স্ট্রিমিং",
                    color = Color(0xFF94A3B8),
                    fontSize = if (isCompact) 9.sp else 10.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun FullscreenErrorOverlay(
    message: String,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(32.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B).copy(alpha = 0.95f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(36.dp))
                Text(text = message, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                Button(
                    onClick = onRetry,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("পুনরায় চেষ্টা করুন", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun FullscreenControlsOverlay(
    media: AppMediaItem,
    servers: List<StreamServer>,
    selectedServerIndex: Int,
    onSelectServer: (Int) -> Unit,
    isPlaying: Boolean,
    isMuted: Boolean,
    playbackSpeed: Float,
    currentPositionMs: Long,
    durationMs: Long,
    isDraggingSlider: Boolean,
    sliderPosition: Float,
    onSliderChange: (Float) -> Unit,
    onSliderChangeFinished: () -> Unit,
    onPlayPause: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeed: () -> Unit,
    onPrevChannel: () -> Unit,
    onNextChannel: () -> Unit,
    onToggleAspect: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onToggleChannelDrawer: () -> Unit,
    onClose: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close Fullscreen", tint = Color.White)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = media.title,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Text(
                        text = "${media.category} • ${media.quality}",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Quick Channel Drawer Toggle
                IconButton(onClick = onToggleChannelDrawer) {
                    Icon(Icons.Rounded.FormatListBulleted, contentDescription = "Channel List", tint = Color(0xFF00E5FF))
                }

                // Aspect Ratio
                IconButton(onClick = onToggleAspect) {
                    Icon(Icons.Rounded.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White)
                }

                // Exit Fullscreen
                IconButton(onClick = onToggleFullscreen) {
                    Icon(Icons.Rounded.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color(0xFF00E5FF))
                }
            }
        }

        // Bottom Controls Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))
                    )
                )
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Servers Row in Fullscreen
                if (servers.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(servers) { index, server ->
                            val isSelected = selectedServerIndex == index
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                modifier = Modifier.clickable { onSelectServer(index) }
                            ) {
                                Text(
                                    text = server.name,
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Progress Scrubber
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val formatTime = { ms: Long ->
                        if (ms <= 0L) "00:00"
                        else String.format("%02d:%02d", (ms / 1000) / 60, (ms / 1000) % 60)
                    }

                    Text(
                        text = formatTime(currentPositionMs),
                        color = Color(0xFF00E5FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val progressFraction = if (durationMs > 0) {
                        (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                    } else 0f

                    Slider(
                        value = if (isDraggingSlider) sliderPosition else progressFraction,
                        onValueChange = onSliderChange,
                        onValueChangeFinished = onSliderChangeFinished,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                            .height(20.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFF00E5FF),
                            activeTrackColor = Color(0xFF00E5FF),
                            inactiveTrackColor = Color(0xFF334155)
                        )
                    )

                    Text(
                        text = if (durationMs > 0) formatTime(durationMs) else if (media.isLive) "LIVE" else "00:00",
                        color = if (media.isLive && durationMs <= 0) Color.Red else Color.LightGray,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Controls row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Mute Toggle
                    IconButton(onClick = onToggleMute) {
                        Icon(
                            imageVector = if (isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                            contentDescription = "Mute",
                            tint = Color.White
                        )
                    }

                    // Speed
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color(0xFF1E293B),
                        modifier = Modifier.clickable { onToggleSpeed() }
                    ) {
                        Text(
                            text = "${playbackSpeed}x",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    // Prev Channel
                    IconButton(onClick = onPrevChannel) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous Channel", tint = Color.White, modifier = Modifier.size(28.dp))
                    }

                    // Center Play/Pause
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E5FF))
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.Black,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // Next Channel
                    IconButton(onClick = onNextChannel) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next Channel", tint = Color.White, modifier = Modifier.size(28.dp))
                    }

                    // Aspect Ratio
                    IconButton(onClick = onToggleAspect) {
                        Icon(Icons.Rounded.AspectRatio, contentDescription = "Aspect Ratio", tint = Color.White)
                    }

                    // Fullscreen exit
                    IconButton(onClick = onToggleFullscreen) {
                        Icon(Icons.Rounded.FullscreenExit, contentDescription = "Exit Fullscreen", tint = Color(0xFF00E5FF))
                    }
                }
            }
        }
    }
}
