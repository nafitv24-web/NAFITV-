package com.example.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.BuildConfig
import com.example.R
import com.example.data.MediaRepository
import com.example.model.AppUpdateInfo
import com.example.model.MediaItem
import com.example.model.MediaType
import com.example.model.PlaylistInfo
import com.example.model.StreamServer
import com.example.player.VideoPlayerScreen
import com.example.ui.AppUpdateDialog
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class AppTab(val title: String, val englishLabel: String) {
    EVENTS("ম্যাচ", "Events"),
    LIVE_TV("টিভি", "Live TV"),
    MOVIES("মুভি", "Movies"),
    PLAYLIST("প্লেলিস্ট", "Playlist"),
    MENU("মেনু", "Menu")
}

enum class AdminTab(val label: String) {
    CHANNELS("Live TV Channels"),
    MOVIES("Movies"),
    PLAYLISTS("Playlists"),
    SPORTS("Sports Matches"),
    APP_UPDATE("App Updates"),
    FIREBASE("Firebase Cloud")
}

@Composable
fun NafiTvMainApp() {
    val context = LocalContext.current
    val activity = context as? Activity
    val repository = remember { MediaRepository(context) }
    val coroutineScope = rememberCoroutineScope()

    var currentTab by remember { mutableStateOf(AppTab.EVENTS) }
    var selectedMediaItem by remember { mutableStateOf<MediaItem?>(null) }
    var activePlaybackPlaylist by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isTvMode by remember { mutableStateOf(false) }
    var isAdminViewActive by remember { mutableStateOf(false) }

    // App Exit Confirmation State
    var showExitConfirmationDialog by remember { mutableStateOf(false) }

    // Synchronize screen orientation based on Mobile/TV mode toggle
    LaunchedEffect(isTvMode) {
        if (isTvMode) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // App Update State
    var availableUpdateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }

    // State lists - initialize with saved custom streams (no hardcoded channels)
    var sportsList by remember {
        val deleted = repository.getDeletedIds()
        val customSports = repository.getCustomStreams().filter { it.type == MediaType.LIVE_EVENT }.filterNot { deleted.contains(it.id) }
        mutableStateOf(customSports)
    }
    var liveTvList by remember {
        val deleted = repository.getDeletedIds()
        val customTv = repository.getCustomStreams().filter { it.type == MediaType.LIVE_TV }.filterNot { deleted.contains(it.id) }
        mutableStateOf(customTv)
    }
    var moviesList by remember {
        val deleted = repository.getDeletedIds()
        val customMov = repository.getCustomStreams().filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES }.filterNot { deleted.contains(it.id) }
        mutableStateOf(customMov)
    }
    var playlistsList by remember { mutableStateOf(repository.getInitialPlaylists() + repository.getCustomPlaylists()) }
    var customList by remember { mutableStateOf(repository.getCustomStreams()) }
    var m3uList by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf(repository.getFavoriteIds()) }
    var isRefreshing by remember { mutableStateOf(false) }

    fun checkForUpdates(isManualCheck: Boolean = false) {
        coroutineScope.launch {
            try {
                val updateInfo = repository.fetchAppUpdateInfo()
                if (updateInfo != null && updateInfo.versionCode > BuildConfig.VERSION_CODE) {
                    availableUpdateInfo = updateInfo
                    if (isManualCheck || updateInfo.isForceUpdate || !repository.isUpdateDismissed(updateInfo.versionCode)) {
                        showUpdateDialog = true
                    }
                } else if (isManualCheck) {
                    Toast.makeText(context, "আপনার অ্যাপটি লেটেস্ট ভার্সনে আছে (v${BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isManualCheck) {
                    Toast.makeText(context, "আপডেট চেক ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Auto-fetch data (Firebase Firestore + RTDB + Sports M3U + TV M3U + Playlists + App Updates)
    fun refreshAllData() {
        coroutineScope.launch {
            isRefreshing = true
            try {
                val deleted = repository.getDeletedIds()

                // 0. Fetch latest remote App Config (Live TV, Sports, Movies M3U URLs) from Firebase
                try {
                    repository.fetchAppConfigFromFirebase()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 1. Parallel asynchronous fetching of Sports M3U, Live TV M3U, Movies M3U and Firebase Cloud
                val sportsM3uUrl = repository.getSavedSportsM3uUrl()
                val sportsM3uDeferred = async {
                    if (sportsM3uUrl.isNotBlank()) {
                        repository.parseM3uFromUrl(sportsM3uUrl).map { it.copy(type = MediaType.LIVE_EVENT) }
                    } else emptyList()
                }

                val liveTvM3uUrl = repository.getSavedLiveTvM3uUrl()
                val liveTvM3uDeferred = async {
                    if (liveTvM3uUrl.isNotBlank()) {
                        repository.parseM3uFromUrl(liveTvM3uUrl).map { it.copy(type = MediaType.LIVE_TV) }
                    } else emptyList()
                }

                val moviesM3uUrl = repository.getSavedMoviesM3uUrl()
                val moviesM3uDeferred = async {
                    if (moviesM3uUrl.isNotBlank()) {
                        repository.parseM3uFromUrl(moviesM3uUrl).map { it.copy(type = MediaType.MOVIE) }
                    } else emptyList()
                }

                val fbItemsDeferred = async { repository.fetchFromFirebase().filterNot { deleted.contains(it.id) } }
                val fbPlaylistsDeferred = async { repository.fetchPlaylistsFromFirebase() }

                // Await all parallel fetches
                val parsedSportsM3u = sportsM3uDeferred.await().filterNot { deleted.contains(it.id) }
                val parsedTvM3u = liveTvM3uDeferred.await().filterNot { deleted.contains(it.id) }
                val parsedMoviesM3u = moviesM3uDeferred.await().filterNot { deleted.contains(it.id) }
                val fbItems = fbItemsDeferred.await()
                val fbPlaylists = fbPlaylistsDeferred.await()

                val fbSports = fbItems.filter { it.type == MediaType.LIVE_EVENT }
                val fbTv = fbItems.filter { it.type == MediaType.LIVE_TV }
                val fbMov = fbItems.filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES }

                val customStreams = repository.getCustomStreams().filterNot { deleted.contains(it.id) }
                val customSports = customStreams.filter { it.type == MediaType.LIVE_EVENT }
                val customTv = customStreams.filter { it.type == MediaType.LIVE_TV }
                val customMov = customStreams.filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES }

                // 2. Playlists: from Firebase Cloud + Admin custom playlists
                val allPlaylists = (fbPlaylists + repository.getCustomPlaylists())
                    .distinctBy { it.id }
                    .filterNot { deleted.contains(it.id) }
                playlistsList = allPlaylists
                val playlistIds = allPlaylists.map { it.id }.toSet()

                // 3. Set distinct channel, sports & movie lists from Firebase Cloud + Sports M3U + Admin custom additions (Excluding Playlists)
                sportsList = (customSports + fbSports + parsedSportsM3u)
                    .filterNot { it.id.startsWith("pl_") || playlistIds.contains(it.id) }
                    .distinctBy { it.id }

                liveTvList = (customTv + fbTv + parsedTvM3u)
                    .filterNot { it.id.startsWith("pl_") || playlistIds.contains(it.id) }
                    .distinctBy { it.id }

                moviesList = (customMov + fbMov + parsedMoviesM3u)
                    .filterNot { it.id.startsWith("pl_") || playlistIds.contains(it.id) }
                    .distinctBy { it.id }

                m3uList = (parsedSportsM3u + parsedTvM3u + parsedMoviesM3u)
                    .filterNot { it.id.startsWith("pl_") || playlistIds.contains(it.id) }
                    .distinctBy { it.id }

                // 4. Custom streams & favorites
                customList = customStreams
                favoriteIds = repository.getFavoriteIds()

                // 5. App update check
                checkForUpdates(isManualCheck = false)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isRefreshing = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshAllData()
    }

    if (selectedMediaItem != null) {
        val currentPlayList = if (activePlaybackPlaylist.isNotEmpty()) {
            activePlaybackPlaylist
        } else {
            when (currentTab) {
                AppTab.LIVE_TV -> (liveTvList + customList.filter { it.type == MediaType.LIVE_TV } + m3uList.filter { it.type == MediaType.LIVE_TV }).distinctBy { it.id }
                AppTab.MOVIES -> (moviesList + customList.filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES } + m3uList.filter { it.type == MediaType.MOVIE }).distinctBy { it.id }
                AppTab.EVENTS -> sportsList.distinctBy { it.id }
                AppTab.PLAYLIST -> (customList + m3uList).distinctBy { it.id }
                else -> (liveTvList + sportsList + moviesList + customList + m3uList).distinctBy { it.id }
            }
        }
        VideoPlayerScreen(
            mediaItem = selectedMediaItem!!,
            playlist = currentPlayList,
            isTvMode = isTvMode,
            onSelectMedia = { selectedMediaItem = it },
            onBack = { selectedMediaItem = null }
        )
    } else if (isAdminViewActive) {
        // FULLSCREEN ADMIN CONTROL APP (Exact UI from Screenshot 1 & 3)
        AdminControlAppScreen(
            repository = repository,
            sportsList = sportsList,
            liveTvList = liveTvList + customList.filter { it.type == MediaType.LIVE_TV },
            moviesList = moviesList + customList.filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES },
            playlistsList = playlistsList,
            onExitAdmin = { isAdminViewActive = false },
            onDataChanged = { refreshAllData() }
        )
    } else {
        // Intercept mobile back press when at root screens to prevent abrupt closing
        BackHandler {
            if (currentTab != AppTab.EVENTS) {
                currentTab = AppTab.EVENTS
            } else {
                showExitConfirmationDialog = true
            }
        }

        if (isTvMode) {
            // TV MODE: Sidebar Menu Rail on Left + Content on Right (Matching uploaded screenshot)
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF020617))
            ) {
                // Left Vertical Navigation Rail
                Surface(
                    modifier = Modifier
                        .width(86.dp)
                        .fillMaxHeight()
                        .statusBarsPadding()
                        .navigationBarsPadding(),
                    color = Color(0xFF0B1328),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppTab.values().forEach { tab ->
                            val isSelected = currentTab == tab
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) Color(0xFF00E5FF).copy(alpha = 0.16f) else Color.Transparent,
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.45f)) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentTab = tab }
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = when (tab) {
                                            AppTab.EVENTS -> Icons.Rounded.EmojiEvents
                                            AppTab.LIVE_TV -> Icons.Rounded.Tv
                                            AppTab.MOVIES -> Icons.Rounded.Movie
                                            AppTab.PLAYLIST -> Icons.Rounded.Folder
                                            AppTab.MENU -> Icons.Rounded.Menu
                                        },
                                        contentDescription = tab.englishLabel,
                                        tint = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Text(
                                        text = tab.englishLabel,
                                        color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        textAlign = TextAlign.Center,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                // Right Content Area with Top Action Header
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .navigationBarsPadding()
                        .background(Color(0xFF020617))
                ) {
                    // Top Action Bar Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F172A))
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = R.drawable.app_logo),
                                    contentDescription = "NAFI TV Logo",
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                if (currentTab == AppTab.EVENTS) {
                                    Text(
                                        text = "Live Events",
                                        color = Color.White,
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "NAFI TV 24",
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(7.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFEF4444))
                                            )
                                        }
                                        Text(
                                            text = currentTab.englishLabel,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            // Action Quick Controls (Mobile / TV Toggle + Refresh)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Layout Toggle: Mobile Pill
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (!isTvMode) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E293B),
                                    border = if (!isTvMode) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)) else null,
                                    modifier = Modifier.clickable {
                                        if (isTvMode) {
                                            isTvMode = false
                                            Toast.makeText(context, "📱 মোবাইল মোড সক্রিয় হয়েছে (Portrait)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Smartphone,
                                            contentDescription = "Mobile Mode",
                                            tint = if (!isTvMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Mobile",
                                            color = if (!isTvMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Layout Toggle: TV Pill
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isTvMode) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E293B),
                                    border = if (isTvMode) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)) else null,
                                    modifier = Modifier.clickable {
                                        if (!isTvMode) {
                                            isTvMode = true
                                            Toast.makeText(context, "📺 টিভি মোড সক্রিয় হয়েছে (Landscape & TV Layout)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Tv,
                                            contentDescription = "TV Mode",
                                            tint = if (isTvMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "TV",
                                            color = if (isTvMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Refresh Button
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF1E293B),
                                    modifier = Modifier.clickable { refreshAllData() }
                                ) {
                                    Box(modifier = Modifier.padding(7.dp)) {
                                        if (isRefreshing) {
                                            CircularProgressIndicator(
                                                color = Color(0xFF00E5FF),
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = "Refresh",
                                                tint = Color.White,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Main Content in TV Mode
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f)
                    ) {
                        when (currentTab) {
                            AppTab.EVENTS -> EventsScreen(
                                sports = sportsList.distinctBy { it.id },
                                favoriteIds = favoriteIds,
                                isTvMode = isTvMode,
                                onSelectMedia = {
                                    selectedMediaItem = it
                                    activePlaybackPlaylist = sportsList.distinctBy { sp -> sp.id }
                                },
                                onToggleFavorite = { id ->
                                    repository.toggleFavorite(id)
                                    favoriteIds = repository.getFavoriteIds()
                                }
                            )

                            AppTab.LIVE_TV -> LiveTvTabScreen(
                                channels = (liveTvList + customList.filter { it.type == MediaType.LIVE_TV } + m3uList.filter { it.type == MediaType.LIVE_TV }).distinctBy { it.id },
                                favoriteIds = favoriteIds,
                                isTvMode = isTvMode,
                                onSelectMedia = {
                                    selectedMediaItem = it
                                    activePlaybackPlaylist = (liveTvList + customList.filter { ch -> ch.type == MediaType.LIVE_TV } + m3uList.filter { ch -> ch.type == MediaType.LIVE_TV }).distinctBy { ch -> ch.id }
                                },
                                onToggleFavorite = { id ->
                                    repository.toggleFavorite(id)
                                    favoriteIds = repository.getFavoriteIds()
                                }
                            )

                            AppTab.MOVIES -> MoviesTabScreen(
                                movies = (moviesList + customList.filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES } + m3uList.filter { it.type == MediaType.MOVIE }).distinctBy { it.id },
                                favoriteIds = favoriteIds,
                                isTvMode = isTvMode,
                                onSelectMedia = {
                                    selectedMediaItem = it
                                    activePlaybackPlaylist = (moviesList + customList.filter { mv -> mv.type == MediaType.MOVIE || mv.type == MediaType.SERIES } + m3uList.filter { mv -> mv.type == MediaType.MOVIE }).distinctBy { mv -> mv.id }
                                },
                                onToggleFavorite = { id ->
                                    repository.toggleFavorite(id)
                                    favoriteIds = repository.getFavoriteIds()
                                }
                            )

                            AppTab.PLAYLIST -> PlaylistTabScreen(
                                playlists = playlistsList,
                                repository = repository,
                                isTvMode = isTvMode,
                                onSelectMedia = { item, playlist ->
                                    selectedMediaItem = item
                                    activePlaybackPlaylist = playlist
                                }
                            )

                            AppTab.MENU -> MenuScreen(
                                repository = repository,
                                customList = customList,
                                onOpenAdminApp = { isAdminViewActive = true },
                                onCheckForUpdates = { checkForUpdates(isManualCheck = true) },
                                availableUpdateInfo = availableUpdateInfo,
                                onPlayDirectStream = { url, title ->
                                    val directItem = MediaItem(
                                        id = "direct_${System.currentTimeMillis()}",
                                        title = title.ifBlank { "Direct Stream" },
                                        category = "Direct Stream",
                                        type = MediaType.LIVE_TV,
                                        streamUrl = url,
                                        isLive = true
                                    )
                                    selectedMediaItem = directItem
                                    activePlaybackPlaylist = listOf(directItem)
                                },
                                onM3uLoaded = { list ->
                                    m3uList = list
                                    currentTab = AppTab.PLAYLIST
                                },
                                onCustomAdded = { item ->
                                    repository.saveCustomStream(item)
                                    customList = repository.getCustomStreams()
                                    coroutineScope.launch {
                                        repository.pushToFirebase(item)
                                    }
                                    Toast.makeText(context, "চ্যানেল লাইভ তালিকায় যুক্ত হয়েছে!", Toast.LENGTH_SHORT).show()
                                },
                                onResetDefaults = {
                                    repository.resetToDefaults()
                                    sportsList = repository.getInitialSports()
                                    liveTvList = repository.getInitialLiveTv()
                                    moviesList = repository.getInitialMoviesSeries()
                                    customList = emptyList()
                                    m3uList = emptyList()
                                    favoriteIds = emptySet()
                                    Toast.makeText(context, "ডিফল্ট রিসেট সফল হয়েছে!", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // MOBILE MODE: Standard Scaffold with Top Bar + Content + Bottom Navigation Bar
            Scaffold(
                topBar = {
                    // Top Action Bar Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F172A))
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = R.drawable.app_logo),
                                    contentDescription = "NAFI TV Logo",
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp)),
                                    contentScale = ContentScale.Fit
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                if (currentTab == AppTab.EVENTS) {
                                    Text(
                                        text = "Live Events",
                                        color = Color.White,
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "NAFI TV 24",
                                                color = Color.White,
                                                fontSize = 17.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(8.dp)
                                                    .clip(CircleShape)
                                                    .background(Color(0xFFEF4444))
                                            )
                                        }
                                        Text(
                                            text = currentTab.englishLabel,
                                            color = Color(0xFF94A3B8),
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                            }

                            // Action Quick Controls
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Layout Toggle: Mobile Pill
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (!isTvMode) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E293B),
                                    border = if (!isTvMode) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)) else null,
                                    modifier = Modifier.clickable {
                                        if (isTvMode) {
                                            isTvMode = false
                                            Toast.makeText(context, "📱 মোবাইল মোড সক্রিয় হয়েছে (Portrait)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Smartphone,
                                            contentDescription = "Mobile Mode",
                                            tint = if (!isTvMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Mobile",
                                            color = if (!isTvMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Layout Toggle: TV Pill
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (isTvMode) Color(0xFF00E5FF).copy(alpha = 0.2f) else Color(0xFF1E293B),
                                    border = if (isTvMode) androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFF00E5FF)) else null,
                                    modifier = Modifier.clickable {
                                        if (!isTvMode) {
                                            isTvMode = true
                                            Toast.makeText(context, "📺 টিভি মোড সক্রিয় হয়েছে (Landscape & TV Layout)", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Tv,
                                            contentDescription = "TV Mode",
                                            tint = if (isTvMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "TV",
                                            color = if (isTvMode) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Refresh Button
                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF1E293B),
                                    modifier = Modifier.clickable { refreshAllData() }
                                ) {
                                    Box(modifier = Modifier.padding(8.dp)) {
                                        if (isRefreshing) {
                                            CircularProgressIndicator(
                                                color = Color(0xFF00E5FF),
                                                strokeWidth = 2.dp,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.Refresh,
                                                contentDescription = "Refresh",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = Color(0xFF0F172A),
                        contentColor = Color.White
                    ) {
                        AppTab.values().forEach { tab ->
                            val selected = currentTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { currentTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = when (tab) {
                                            AppTab.EVENTS -> Icons.Rounded.EmojiEvents
                                            AppTab.LIVE_TV -> Icons.Rounded.Tv
                                            AppTab.MOVIES -> Icons.Rounded.Movie
                                            AppTab.PLAYLIST -> Icons.Rounded.Folder
                                            AppTab.MENU -> Icons.Rounded.Menu
                                        },
                                        contentDescription = tab.englishLabel,
                                        tint = if (selected) Color(0xFF00E5FF) else Color(0xFF94A3B8)
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.englishLabel,
                                        color = if (selected) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color(0xFF1E293B)
                                )
                            )
                        }
                    }
                },
                containerColor = Color(0xFF020617)
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                ) {
                    when (currentTab) {
                        AppTab.EVENTS -> EventsScreen(
                            sports = sportsList.distinctBy { it.id },
                            favoriteIds = favoriteIds,
                            isTvMode = isTvMode,
                            onSelectMedia = {
                                selectedMediaItem = it
                                activePlaybackPlaylist = sportsList.distinctBy { sp -> sp.id }
                            },
                            onToggleFavorite = { id ->
                                repository.toggleFavorite(id)
                                favoriteIds = repository.getFavoriteIds()
                            }
                        )

                        AppTab.LIVE_TV -> LiveTvTabScreen(
                            channels = (liveTvList + customList.filter { it.type == MediaType.LIVE_TV } + m3uList.filter { it.type == MediaType.LIVE_TV }).distinctBy { it.id },
                            favoriteIds = favoriteIds,
                            isTvMode = isTvMode,
                            onSelectMedia = {
                                selectedMediaItem = it
                                activePlaybackPlaylist = (liveTvList + customList.filter { ch -> ch.type == MediaType.LIVE_TV } + m3uList.filter { ch -> ch.type == MediaType.LIVE_TV }).distinctBy { ch -> ch.id }
                            },
                            onToggleFavorite = { id ->
                                repository.toggleFavorite(id)
                                favoriteIds = repository.getFavoriteIds()
                            }
                        )

                        AppTab.MOVIES -> MoviesTabScreen(
                            movies = (moviesList + customList.filter { it.type == MediaType.MOVIE || it.type == MediaType.SERIES } + m3uList.filter { it.type == MediaType.MOVIE }).distinctBy { it.id },
                            favoriteIds = favoriteIds,
                            isTvMode = isTvMode,
                            onSelectMedia = {
                                selectedMediaItem = it
                                activePlaybackPlaylist = (moviesList + customList.filter { mv -> mv.type == MediaType.MOVIE || mv.type == MediaType.SERIES } + m3uList.filter { mv -> mv.type == MediaType.MOVIE }).distinctBy { mv -> mv.id }
                            },
                            onToggleFavorite = { id ->
                                repository.toggleFavorite(id)
                                favoriteIds = repository.getFavoriteIds()
                            }
                        )

                        AppTab.PLAYLIST -> PlaylistTabScreen(
                            playlists = playlistsList,
                            repository = repository,
                            isTvMode = isTvMode,
                            onSelectMedia = { item, playlist ->
                                selectedMediaItem = item
                                activePlaybackPlaylist = playlist
                            }
                        )

                        AppTab.MENU -> MenuScreen(
                            repository = repository,
                            customList = customList,
                            onOpenAdminApp = { isAdminViewActive = true },
                            onCheckForUpdates = { checkForUpdates(isManualCheck = true) },
                            availableUpdateInfo = availableUpdateInfo,
                            onPlayDirectStream = { url, title ->
                                val directItem = MediaItem(
                                    id = "direct_${System.currentTimeMillis()}",
                                    title = title.ifBlank { "Direct Stream" },
                                    category = "Direct Stream",
                                    type = MediaType.LIVE_TV,
                                    streamUrl = url,
                                    isLive = true
                                )
                                selectedMediaItem = directItem
                                activePlaybackPlaylist = listOf(directItem)
                            },
                            onM3uLoaded = { list ->
                                m3uList = list
                                currentTab = AppTab.PLAYLIST
                            },
                            onCustomAdded = { item ->
                                repository.saveCustomStream(item)
                                customList = repository.getCustomStreams()
                                coroutineScope.launch {
                                    repository.pushToFirebase(item)
                                }
                                Toast.makeText(context, "চ্যানেল লাইভ তালিকায় যুক্ত হয়েছে!", Toast.LENGTH_SHORT).show()
                            },
                            onResetDefaults = {
                                repository.resetToDefaults()
                                sportsList = repository.getInitialSports()
                                liveTvList = repository.getInitialLiveTv()
                                moviesList = repository.getInitialMoviesSeries()
                                customList = emptyList()
                                m3uList = emptyList()
                                favoriteIds = emptySet()
                                Toast.makeText(context, "ডিফল্ট রিসেট সফল হয়েছে!", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    // In-App Update Dialog (Beautiful Animated M3 Popup for Auto-Updating App)
    if (showUpdateDialog && availableUpdateInfo != null) {
        val info = availableUpdateInfo!!
        AppUpdateDialog(
            updateInfo = info,
            onDismiss = {
                repository.dismissUpdate(info.versionCode)
                showUpdateDialog = false
            }
        )
    }

    // App Exit Confirmation Dialog (User requested: মোবাইলের ব্যাক বাটনে ক্লিক করলে যেন অ্যাপ থেকে বের হওয়ার আগে পারমিশন চায়)
    if (showExitConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmationDialog = false },
            shape = RoundedCornerShape(20.dp),
            containerColor = Color(0xFF1E293B),
            tonalElevation = 8.dp,
            icon = {
                Image(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = "NAFI TV Logo",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
            },
            title = {
                Text(
                    text = "অ্যাপ থেকে বের হতে চান?",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Text(
                    text = "আপনি কি নিশ্চিতভাবে NAFI TV 24 অ্যাপটি বন্ধ করতে চান?",
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExitConfirmationDialog = false
                        activity?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.height(42.dp)
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("হ্যাঁ, বের হন", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showExitConfirmationDialog = false },
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFCBD5E1)),
                    modifier = Modifier.height(42.dp)
                ) {
                    Text("না, থাকুন", fontSize = 13.sp)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// SCREEN: ADMIN CONTROL APP (Exact UI from Screenshot 3)
// -------------------------------------------------------------
@Composable
fun AdminControlAppScreen(
    repository: MediaRepository,
    sportsList: List<MediaItem>,
    liveTvList: List<MediaItem>,
    moviesList: List<MediaItem>,
    playlistsList: List<PlaylistInfo>,
    onExitAdmin: () -> Unit,
    onDataChanged: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // BackHandler to exit Admin View back to main app
    BackHandler {
        onExitAdmin()
    }

    var selectedAdminTab by remember { mutableStateOf(AdminTab.CHANNELS) }

    // App Update Form State
    var updateVersionCode by remember { mutableStateOf("2") }
    var updateVersionName by remember { mutableStateOf("2.5.0") }
    var updateDownloadUrl by remember { mutableStateOf("https://github.com/google/ai-studio/releases/download/v2.5.0/NAFITV24_v2.5.apk") }
    var updateReleaseNotes by remember {
        mutableStateOf("• নতুন ফাস্ট লাইভ স্পোর্টস মাল্টি-সার্ভার যোগ করা হয়েছে\n• লাইভ ম্যাচ স্কোরবোর্ড ও কাউন্টডাউন টাইমার\n• ফুলস্ক্রিন ও হাই কোয়ালিটি আল্ট্রা এইচডি প্লেয়ার\n• বাগ ফিক্স এবং ফাস্ট লোডিং স্পিড")
    }
    var updateApkSize by remember { mutableStateOf("18.5 MB") }
    var updateReleaseDate by remember { mutableStateOf("15 Aug 2026") }
    var updateIsForce by remember { mutableStateOf(false) }
    var isSavingUpdate by remember { mutableStateOf(false) }
    var previewUpdateDialog by remember { mutableStateOf<AppUpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        val cached = repository.getCachedAppUpdateInfo()
        if (cached != null) {
            updateVersionCode = cached.versionCode.toString()
            updateVersionName = cached.versionName
            updateDownloadUrl = cached.downloadUrl
            updateReleaseNotes = cached.releaseNotes
            updateApkSize = cached.apkSize
            updateReleaseDate = cached.releaseDate
            updateIsForce = cached.isForceUpdate
        } else {
            coroutineScope.launch {
                val remote = repository.fetchAppUpdateInfo()
                if (remote != null) {
                    updateVersionCode = remote.versionCode.toString()
                    updateVersionName = remote.versionName
                    updateDownloadUrl = remote.downloadUrl
                    updateReleaseNotes = remote.releaseNotes
                    updateApkSize = remote.apkSize
                    updateReleaseDate = remote.releaseDate
                    updateIsForce = remote.isForceUpdate
                }
            }
        }
    }

    // Playlists Form State
    var playlistTitle by remember { mutableStateOf("") }
    var playlistUrl by remember { mutableStateOf("") }
    var playlistLogoUrl by remember { mutableStateOf("") }
    var playlistDescription by remember { mutableStateOf("") }
    var playlistToDelete by remember { mutableStateOf<PlaylistInfo?>(null) }

    // Sports Form State
    var sportCategory by remember { mutableStateOf("Cricket") }
    var sportStatus by remember { mutableStateOf("● Live Now") }
    var tournamentName by remember { mutableStateOf("") }
    var team1Name by remember { mutableStateOf("") }
    var team1Score by remember { mutableStateOf("") }
    var team1LogoUrl by remember { mutableStateOf("") }
    var team2Name by remember { mutableStateOf("") }
    var team2Score by remember { mutableStateOf("") }
    var team2LogoUrl by remember { mutableStateOf("") }
    var matchTimeFormatted by remember { mutableStateOf("") }
    var countdownHours by remember { mutableStateOf("") }

    // Sports Multi-Server dynamic list (Default 3 servers, expandable to unlimited!)
    var sportsServers by remember {
        mutableStateOf(
            listOf(
                StreamServer("T SPORTS", ""),
                StreamServer("TT", ""),
                StreamServer("TEMP", "")
            )
        )
    }

    // Match Edit Dialog State (CRITICAL USER REQUEST: খেলা এডিট ও খেলা চলাকালীন নতুন সার্ভার লিংক যোগ)
    var editingMatchItem by remember { mutableStateOf<MediaItem?>(null) }
    var editTournament by remember { mutableStateOf("") }
    var editSportCategory by remember { mutableStateOf("Cricket") }
    var editSportStatus by remember { mutableStateOf("● Live Now") }
    var editTeam1Name by remember { mutableStateOf("") }
    var editTeam1Score by remember { mutableStateOf("") }
    var editTeam1Logo by remember { mutableStateOf("") }
    var editTeam2Name by remember { mutableStateOf("") }
    var editTeam2Score by remember { mutableStateOf("") }
    var editTeam2Logo by remember { mutableStateOf("") }
    var editMatchTime by remember { mutableStateOf("") }
    var editCountdownHours by remember { mutableStateOf("") }
    var editServers by remember { mutableStateOf<List<StreamServer>>(emptyList()) }
    var editSportDropdownExpanded by remember { mutableStateOf(false) }
    var editStatusDropdownExpanded by remember { mutableStateOf(false) }

    // Main M3U Source URLs for Live TV, Sports and Movies
    var liveTvM3uInput by remember { mutableStateOf(repository.getSavedLiveTvM3uUrl()) }
    var sportsM3uInput by remember { mutableStateOf(repository.getSavedSportsM3uUrl()) }
    var moviesM3uInput by remember { mutableStateOf(repository.getSavedMoviesM3uUrl()) }

    // Firebase Database State & Diagnostics
    var firebaseUrlInput by remember { mutableStateOf(repository.getSavedFirebaseUrl()) }
    var isTestingFirebase by remember { mutableStateOf(false) }
    var firebaseTestResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    // Channel Form Server 1 and Server 2
    var server1Url by remember { mutableStateOf("") }
    var server2Url by remember { mutableStateOf("") }

    // Score Update Dialog State
    var updatingItem by remember { mutableStateOf<MediaItem?>(null) }
    var updateScore1 by remember { mutableStateOf("") }
    var updateScore2 by remember { mutableStateOf("") }

    // Item Deletion Dialog State
    var itemToDelete by remember { mutableStateOf<MediaItem?>(null) }

    // Channel Form State
    var channelName by remember { mutableStateOf("") }
    var channelCategory by remember { mutableStateOf("Sports TV") }
    var channelLogoUrl by remember { mutableStateOf("") }

    // Channel (Live TV) Edit Dialog State
    var editingChannelItem by remember { mutableStateOf<MediaItem?>(null) }
    var editChannelName by remember { mutableStateOf("") }
    var editChannelCategory by remember { mutableStateOf("Sports TV") }
    var editChannelLogoUrl by remember { mutableStateOf("") }
    var editChannelServers by remember { mutableStateOf<List<StreamServer>>(emptyList()) }
    var editChannelCategoryDropdownExpanded by remember { mutableStateOf(false) }

    // Playlist Edit Dialog State
    var editingPlaylistItem by remember { mutableStateOf<PlaylistInfo?>(null) }
    var editPlaylistTitle by remember { mutableStateOf("") }
    var editPlaylistUrl by remember { mutableStateOf("") }
    var editPlaylistLogoUrl by remember { mutableStateOf("") }
    var editPlaylistDescription by remember { mutableStateOf("") }

    // Movie Form State
    var movieTitle by remember { mutableStateOf("") }
    var movieCategory by remember { mutableStateOf("Bangla Movie") }
    var moviePosterUrl by remember { mutableStateOf("") }
    var movieDesc by remember { mutableStateOf("") }

    // Movie & Series Edit Dialog State
    var editingMovieItem by remember { mutableStateOf<MediaItem?>(null) }
    var editMovieTitle by remember { mutableStateOf("") }
    var editMovieCategory by remember { mutableStateOf("Bangla Movie") }
    var editMoviePosterUrl by remember { mutableStateOf("") }
    var editMovieDesc by remember { mutableStateOf("") }
    var editMovieServers by remember { mutableStateOf<List<StreamServer>>(emptyList()) }
    var editMovieCategoryDropdownExpanded by remember { mutableStateOf(false) }

    val channelCategoryOptions = listOf("Sports TV", "News", "Entertainment", "Bangla", "Indian", "Kids", "Music", "Infotainment", "Religious")
    val movieCategoryOptions = listOf("Bangla Movie", "Hindi Dubbed", "Hollywood", "South Movie", "Web Series", "Natok", "Animation")

    val sportOptions = listOf("Cricket", "Football", "Tennis", "Basketball", "Racing", "Badminton")
    val statusOptions = listOf("● Live Now", "Upcoming", "Finished")
    var sportDropdownExpanded by remember { mutableStateOf(false) }
    var statusDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {
        // Admin Top Header Bar (Matching Screenshot 3)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A))
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painter = painterResource(id = R.drawable.app_logo),
                        contentDescription = "NAFI TV Logo",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "NAFI TV 24",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFF10B981)
                            ) {
                                Text(
                                    text = "ADMIN APP",
                                    color = Color.Black,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Firebase Admin Control App",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E293B)
                    ) {
                        Box(modifier = Modifier.padding(6.dp)) {
                            Icon(Icons.Rounded.Smartphone, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.2f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
                    ) {
                        Box(modifier = Modifier.padding(6.dp)) {
                            Icon(Icons.Rounded.VerifiedUser, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E293B),
                        modifier = Modifier.clickable { onDataChanged() }
                    ) {
                        Box(modifier = Modifier.padding(6.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Welcome Card with Realtime Sync Badge
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2563EB).copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Security,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "NAFI TV 24 - Admin Control App",
                                        color = Color.White,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // Firebase Realtime Sync pill
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF065F46).copy(alpha = 0.4f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Firebase Realtime Sync",
                                        color = Color(0xFF10B981),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        Text(
                            text = "এখানে তথ্য পরিবর্তন করলে সাথে সাথে ইউজার অ্যাপে লাইভ আপডেট হয়ে যাবে।",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = {
                                    // Seed rich sample data with multi-servers & live scores
                                    coroutineScope.launch {
                                        val sampleSports = repository.getInitialSports()
                                        val sampleTv = repository.getInitialLiveTv()
                                        val sampleMov = repository.getInitialMoviesSeries()
                                        sampleSports.forEach { repository.saveCustomStream(it); repository.pushToFirebase(it) }
                                        sampleTv.forEach { repository.saveCustomStream(it); repository.pushToFirebase(it) }
                                        sampleMov.forEach { repository.saveCustomStream(it); repository.pushToFirebase(it) }
                                        onDataChanged()
                                        Toast.makeText(context, "স্যাম্পল ডেটা ক্লাউডে সিঙ্ক হয়েছে!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Sample Data", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            Button(
                                onClick = onExitAdmin,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.ExitToApp, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ইউজার অ্যাপে যান", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            // Admin Segmented Filter Tabs (Channels / Movies / Playlists / Sports / Updates)
            item {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Button(
                            onClick = { selectedAdminTab = AdminTab.CHANNELS },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedAdminTab == AdminTab.CHANNELS) Color(0xFF2563EB) else Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("📺 Live (${liveTvList.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    item {
                        Button(
                            onClick = { selectedAdminTab = AdminTab.MOVIES },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedAdminTab == AdminTab.MOVIES) Color(0xFF2563EB) else Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("🎬 Movies (${moviesList.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    item {
                        Button(
                            onClick = { selectedAdminTab = AdminTab.PLAYLISTS },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedAdminTab == AdminTab.PLAYLISTS) Color(0xFF2563EB) else Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("📂 Playlists (${playlistsList.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    item {
                        Button(
                            onClick = { selectedAdminTab = AdminTab.SPORTS },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedAdminTab == AdminTab.SPORTS) Color(0xFF2563EB) else Color(0xFF1E293B),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text("🏆 Sports (${sportsList.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    item {
                        Button(
                            onClick = { selectedAdminTab = AdminTab.APP_UPDATE },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedAdminTab == AdminTab.APP_UPDATE) Color(0xFF10B981) else Color(0xFF1E293B),
                                contentColor = if (selectedAdminTab == AdminTab.APP_UPDATE) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.RocketLaunch, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("🚀 App Updates", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    item {
                        Button(
                            onClick = { selectedAdminTab = AdminTab.FIREBASE },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedAdminTab == AdminTab.FIREBASE) Color(0xFFFF9800) else Color(0xFF1E293B),
                                contentColor = if (selectedAdminTab == AdminTab.FIREBASE) Color.Black else Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("🔥 Firebase Cloud", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // TAB 1 CONTENT: SPORTS MATCHES ADMIN (Multi-Server Support)
            // -------------------------------------------------------------
            if (selectedAdminTab == AdminTab.SPORTS) {
                // 1. Primary M3U Playlist Manager for Sports
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.SportsSoccer, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("⚽ স্পোর্টস ম্যাচ M3U প্লেলিস্ট লিংক (Sports M3U)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text(
                                text = "এখানে এক বা একাধিক M3U প্লেলিস্ট লিংক দিতে পারবেন (প্রতি লাইনে একটি করে অথবা কমা দিয়ে)। সমস্ত লিংক স্বয়ংক্রিয়ভাবে ক্লাউড ডেটাবেস দিয়ে ইউজারের অ্যাপে সিঙ্ক হয়ে যাবে।",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                            OutlinedTextField(
                                value = sportsM3uInput,
                                onValueChange = { sportsM3uInput = it },
                                placeholder = { Text("https://url1.m3u\nhttps://url2.m3u", color = Color(0xFF64748B), fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 5
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (sportsM3uInput.isNotBlank()) {
                                            val url = sportsM3uInput.trim()
                                            repository.saveSportsM3uUrl(url)
                                            coroutineScope.launch {
                                                repository.pushAppConfigToFirebase(sportsM3u = url)
                                            }
                                            onDataChanged()
                                            Toast.makeText(context, "✅ স্পোর্টস M3U ক্লাউডে সেভ ও সিঙ্ক হয়েছে!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "সঠিক স্পোর্টস M3U লিংক দিন", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1.3f).height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("সেভ ও সিঙ্ক করুন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        sportsM3uInput = MediaRepository.DEFAULT_SPORTS_M3U_URL
                                        repository.saveSportsM3uUrl(MediaRepository.DEFAULT_SPORTS_M3U_URL)
                                        coroutineScope.launch {
                                            repository.pushAppConfigToFirebase(sportsM3u = MediaRepository.DEFAULT_SPORTS_M3U_URL)
                                        }
                                        onDataChanged()
                                        Toast.makeText(context, "ডিফল্ট স্পোর্টস M3U রিসেট ও সিঙ্ক হয়েছে", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                                ) {
                                    Text("ডিফল্ট লিংক", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Create Live or Upcoming Sports Match",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Sport Category & Status Dropdown
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Category
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = sportCategory,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = {
                                            IconButton(onClick = { sportDropdownExpanded = !sportDropdownExpanded }) {
                                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = Color.White)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().clickable { sportDropdownExpanded = true },
                                        colors = customFieldColors(),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )
                                    DropdownMenu(
                                        expanded = sportDropdownExpanded,
                                        onDismissRequest = { sportDropdownExpanded = false },
                                        modifier = Modifier.background(Color(0xFF1E293B))
                                    ) {
                                        sportOptions.forEach { opt ->
                                            DropdownMenuItem(
                                                text = { Text(opt, color = Color.White) },
                                                onClick = {
                                                    sportCategory = opt
                                                    sportDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Status
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = sportStatus,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = {
                                            IconButton(onClick = { statusDropdownExpanded = !statusDropdownExpanded }) {
                                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = Color.White)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().clickable { statusDropdownExpanded = true },
                                        colors = customFieldColors(),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )
                                    DropdownMenu(
                                        expanded = statusDropdownExpanded,
                                        onDismissRequest = { statusDropdownExpanded = false },
                                        modifier = Modifier.background(Color(0xFF1E293B))
                                    ) {
                                        statusOptions.forEach { opt ->
                                            DropdownMenuItem(
                                                text = { Text(opt, color = Color.White) },
                                                onClick = {
                                                    sportStatus = opt
                                                    statusDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            // Preset Quick Fill Buttons
                            Text("⚡ দ্রুত প্রিসেট ম্যাচ তৈরি করুন (Quick Presets):", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF1E293B),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                        modifier = Modifier.clickable {
                                            sportCategory = "Cricket"
                                            sportStatus = "Upcoming"
                                            tournamentName = "Cricket 🏏 || Bangladesh vs Australia Test Series 2026"
                                            team1Name = "Bangladesh"
                                            team1LogoUrl = "https://flagcdn.com/w160/bd.png"
                                            team2Name = "Australia"
                                            team2LogoUrl = "https://flagcdn.com/w160/au.png"
                                            matchTimeFormatted = "06:30 AM, Aug 13"
                                            countdownHours = ""
                                            sportsServers = listOf(
                                                StreamServer("T SPORTS", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
                                                StreamServer("TT", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"),
                                                StreamServer("TEMP", "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8")
                                            )
                                        }
                                    ) {
                                        Text("🇧🇩 BD vs AUS 🇦🇺", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                    }
                                }
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF1E293B),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                        modifier = Modifier.clickable {
                                            sportCategory = "Cricket"
                                            sportStatus = "● Live Now"
                                            tournamentName = "NF Women | The Hundred Women's Competition 2026"
                                            team1Name = "Trent Rockets"
                                            team1LogoUrl = "https://images.unsplash.com/photo-1579952363873-27f3bade9f55?w=160"
                                            team2Name = "Southern Brave"
                                            team2LogoUrl = "https://images.unsplash.com/photo-1517649763962-0c623266ddc0?w=160"
                                            matchTimeFormatted = "Live Now"
                                            countdownHours = "113"
                                            sportsServers = listOf(
                                                StreamServer("T SPORTS", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
                                                StreamServer("TT", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"),
                                                StreamServer("TEMP", "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8")
                                            )
                                        }
                                    ) {
                                        Text("🚀 Trent vs Brave 🛡️", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                    }
                                }
                                item {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF1E293B),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                        modifier = Modifier.clickable {
                                            sportCategory = "Cricket"
                                            sportStatus = "Upcoming"
                                            tournamentName = "Cricket 🏏 || Sri Lanka vs India Test Series 2026"
                                            team1Name = "Sri Lanka"
                                            team1LogoUrl = "https://flagcdn.com/w160/lk.png"
                                            team2Name = "India"
                                            team2LogoUrl = "https://flagcdn.com/w160/in.png"
                                            matchTimeFormatted = "10:30 AM, Aug 15"
                                            countdownHours = ""
                                            sportsServers = listOf(
                                                StreamServer("T SPORTS", "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"),
                                                StreamServer("TT", "https://cph-p2p-msl.akamaized.net/hls/live/2000341/test/master.m3u8"),
                                                StreamServer("TEMP", "https://bitdash-a.akamaihd.net/content/sintel/hls/playlist.m3u8")
                                            )
                                        }
                                    ) {
                                        Text("🇱🇰 SL vs IND 🇮🇳", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                    }
                                }
                            }

                            // Tournament Banner Name
                            OutlinedTextField(
                                value = tournamentName,
                                onValueChange = { tournamentName = it },
                                placeholder = { Text("Tournament (e.g. Cricket 🏏 || Bangladesh vs Australia)", color = Color(0xFF64748B), fontSize = 13.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            // Team 1 Section
                            Text("🛡️ Team 1 Details:", color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = team1Name,
                                    onValueChange = { team1Name = it },
                                    placeholder = { Text("Team 1 Name (e.g. Bangladesh)", color = Color(0xFF64748B), fontSize = 13.sp) },
                                    modifier = Modifier.weight(1.2f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = team1Score,
                                    onValueChange = { team1Score = it },
                                    placeholder = { Text("Score (e.g. 154/4)", color = Color(0xFF64748B), fontSize = 13.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                            }
                            OutlinedTextField(
                                value = team1LogoUrl,
                                onValueChange = { team1LogoUrl = it },
                                placeholder = { Text("Team 1 Logo URL (or flagcdn.com/w160/bd.png)", color = Color(0xFF64748B), fontSize = 13.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            // Team 2 Section
                            Text("🛡️ Team 2 Details:", color = Color(0xFF60A5FA), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = team2Name,
                                    onValueChange = { team2Name = it },
                                    placeholder = { Text("Team 2 Name (e.g. Australia)", color = Color(0xFF64748B), fontSize = 13.sp) },
                                    modifier = Modifier.weight(1.2f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = team2Score,
                                    onValueChange = { team2Score = it },
                                    placeholder = { Text("Score (e.g. 142/8)", color = Color(0xFF64748B), fontSize = 13.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                            }
                            OutlinedTextField(
                                value = team2LogoUrl,
                                onValueChange = { team2LogoUrl = it },
                                placeholder = { Text("Team 2 Logo URL (or flagcdn.com/w160/au.png)", color = Color(0xFF64748B), fontSize = 13.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            // Match Time & Countdown Section
                            Text("⏱️ সময় ও কাউন্টডাউন টাইমার (Match Time & Countdown):", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = matchTimeFormatted,
                                    onValueChange = { matchTimeFormatted = it },
                                    placeholder = { Text("Time (e.g. 06:30 AM, Aug 13)", color = Color(0xFF64748B), fontSize = 12.sp) },
                                    modifier = Modifier.weight(1.2f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = countdownHours,
                                    onValueChange = { countdownHours = it },
                                    placeholder = { Text("Countdown (e.g. 113 hrs)", color = Color(0xFF64748B), fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                            }

                            // Dynamic Multi-Server Streams Section (CRITICAL USER REQUEST: একাধিক মাল্টি সার্ভার যোগ)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "⚡ মাল্টি-সার্ভার স্ট্রিম লিঙ্কসমূহ (${sportsServers.size} টি সার্ভার):",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "আনলিমিটেড সার্ভার সাপোর্ট",
                                    color = Color(0xFF10B981),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            // Dynamic list of server inputs
                            sportsServers.forEachIndexed { index, server ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = server.name,
                                        onValueChange = { newName ->
                                            sportsServers = sportsServers.toMutableList().also {
                                                it[index] = it[index].copy(name = newName)
                                            }
                                        },
                                        placeholder = { Text("Server ${index + 1} Name", color = Color(0xFF64748B), fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f),
                                        colors = customFieldColors(),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = server.url,
                                        onValueChange = { newUrl ->
                                            sportsServers = sportsServers.toMutableList().also {
                                                it[index] = it[index].copy(url = newUrl)
                                            }
                                        },
                                        placeholder = { Text("Stream URL (.m3u8 / mp4)", color = Color(0xFF64748B), fontSize = 11.sp) },
                                        modifier = Modifier.weight(2f),
                                        colors = customFieldColors(),
                                        shape = RoundedCornerShape(12.dp),
                                        singleLine = true
                                    )
                                    if (sportsServers.size > 1) {
                                        IconButton(
                                            onClick = {
                                                sportsServers = sportsServers.toMutableList().also { it.removeAt(index) }
                                            },
                                            modifier = Modifier.size(36.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                contentDescription = "সার্ভার সরান",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Add More Server Button
                            OutlinedButton(
                                onClick = {
                                    val nextNum = sportsServers.size + 1
                                    val defaultName = when (nextNum) {
                                        1 -> "T SPORTS"
                                        2 -> "TT"
                                        3 -> "TEMP"
                                        4 -> "HD SERVER 4"
                                        5 -> "SERVER 5 (4K)"
                                        6 -> "SERVER 6 (HLS)"
                                        else -> "SERVER $nextNum"
                                    }
                                    sportsServers = sportsServers + StreamServer(defaultName, "")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("➕ আরও সার্ভার/চ্যানেল লিঙ্ক যোগ করুন (+ Add Server)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            // Publish Match Button
                            Button(
                                onClick = {
                                    val validServers = sportsServers.mapNotNull {
                                        if (it.url.isNotBlank()) StreamServer(it.name.ifBlank { "Server" }, it.url.trim()) else null
                                    }

                                    if ((team1Name.isNotBlank() || tournamentName.isNotBlank()) && validServers.isNotEmpty()) {
                                        val matchTitle = if (team1Name.isNotBlank() && team2Name.isNotBlank()) {
                                             "$team1Name vs $team2Name"
                                        } else {
                                            tournamentName.ifBlank { "Live Match" }
                                        }

                                        val parsedCountdown = countdownHours.toLongOrNull()?.let { it * 3600L } ?: countdownHours.toLongOrNull()

                                        val matchItem = MediaItem(
                                            id = "sport_${System.currentTimeMillis()}",
                                            title = matchTitle,
                                            tournament = tournamentName.ifBlank { null },
                                            category = sportCategory,
                                            type = MediaType.LIVE_EVENT,
                                            streamUrl = validServers.first().url,
                                            backupUrl = validServers.getOrNull(1)?.url,
                                            servers = validServers,
                                            isLive = sportStatus.contains("Live", ignoreCase = true),
                                            status = sportStatus,
                                            eventTime = matchTimeFormatted.ifBlank { sportStatus },
                                            team1 = team1Name.takeIf { it.isNotBlank() },
                                            team1Logo = team1LogoUrl.takeIf { it.isNotBlank() },
                                            team2 = team2Name.takeIf { it.isNotBlank() },
                                            team2Logo = team2LogoUrl.takeIf { it.isNotBlank() },
                                            matchTimeFormatted = matchTimeFormatted.takeIf { it.isNotBlank() },
                                            countdownTargetSeconds = parsedCountdown,
                                            score1 = team1Score.takeIf { it.isNotBlank() },
                                            score2 = team2Score.takeIf { it.isNotBlank() },
                                            quality = "1080p FHD"
                                        )

                                        repository.saveCustomStream(matchItem)
                                        coroutineScope.launch {
                                            repository.pushToFirebase(matchItem)
                                        }
                                        onDataChanged()

                                        // Reset fields
                                        tournamentName = ""
                                        team1Name = ""
                                        team1Score = ""
                                        team1LogoUrl = ""
                                        team2Name = ""
                                        team2Score = ""
                                        team2LogoUrl = ""
                                        matchTimeFormatted = ""
                                        countdownHours = ""
                                        sportsServers = listOf(
                                            StreamServer("T SPORTS", ""),
                                            StreamServer("TT", ""),
                                            StreamServer("TEMP", "")
                                        )
                                        Toast.makeText(context, "ম্যাচ সফলভাবে পাবলিশ ও Firebase এ সংরক্ষিত হয়েছে!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "টিম এর নাম এবং কমপক্ষে একটি সার্ভার লিঙ্ক লিখুন", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                            ) {
                                Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Publish Match to Firebase", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                        }
                    }
                }

                // Firestore Sports Events List Header
                item {
                    Text(
                        text = "Firestore Sports Events List:",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Sports Items List
                items(sportsList) { item ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Surface(
                                    color = Color(0xFF2563EB).copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB))
                                ) {
                                    Text(
                                        text = item.tournament ?: item.category,
                                        color = Color(0xFF60A5FA),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                        maxLines = 1
                                    )
                                }

                                Surface(
                                    color = if (item.isLive) Color(0xFFEF4444) else Color(0xFFF59E0B),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = if (item.isLive) "Live" else "Upcoming",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            // Match Title & Scores
                            Text(
                                text = if (item.score1 != null && item.score2 != null && item.team1 != null && item.team2 != null) {
                                    "${item.team1} (${item.score1}) vs ${item.team2} (${item.score2})"
                                } else {
                                    item.title
                                },
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )

                            // Servers Chip Count
                            val serverCount = item.getAllServers().size
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Dns, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "$serverCount টি সার্ভার সক্রিয়",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 11.sp
                                )
                            }

                            // Action Buttons (Edit Match, Update Score, Delete)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Full Edit Match Button
                                Button(
                                    onClick = {
                                        editingMatchItem = item
                                        editTournament = item.tournament ?: ""
                                        editSportCategory = item.category
                                        editSportStatus = if (item.isLive) "● Live Now" else (item.status ?: "Upcoming")
                                        editTeam1Name = item.team1 ?: ""
                                        editTeam1Score = item.score1 ?: ""
                                        editTeam1Logo = item.team1Logo ?: ""
                                        editTeam2Name = item.team2 ?: ""
                                        editTeam2Score = item.score2 ?: ""
                                        editTeam2Logo = item.team2Logo ?: ""
                                        editMatchTime = item.matchTimeFormatted ?: item.eventTime ?: ""
                                        editCountdownHours = item.countdownTargetSeconds?.let { (it / 3600).toString() } ?: ""
                                        val curServers = item.getAllServers()
                                        editServers = if (curServers.isNotEmpty()) curServers else listOf(StreamServer("Server 1", item.streamUrl))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("খেলা এডিট", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                // Quick Score Update Button
                                Button(
                                    onClick = {
                                        updatingItem = item
                                        updateScore1 = item.score1 ?: ""
                                        updateScore2 = item.score2 ?: ""
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Scoreboard, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("স্কোর", fontSize = 12.sp)
                                }

                                IconButton(
                                    onClick = {
                                        itemToDelete = item
                                    }
                                ) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Color(0xFFEF4444))
                                }
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // TAB 2 CONTENT: LIVE TV CHANNELS ADMIN
            // -------------------------------------------------------------
            if (selectedAdminTab == AdminTab.CHANNELS) {
                // 1. Primary M3U Playlist Manager for Live TV Channels (User: "সব থেকে ভালো হয় চ্যানেল অপশনে এরকম প্লেলিস্ট লিংক এড করা এডমিন প্যানেল থেকে")
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Link, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("📡 লাইভ টিভি চ্যানেল প্লেলিস্ট লিংক (Live TV M3U)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text(
                                text = "এখানে এক বা একাধিক M3U প্লেলিস্ট লিংক দিতে পারবেন (প্রতি লাইনে একটি করে অথবা কমা দিয়ে)। সরাসরি চ্যানেল লোড হবে ও ক্লাউডে সিঙ্ক থাকবে:",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                            OutlinedTextField(
                                value = liveTvM3uInput,
                                onValueChange = { liveTvM3uInput = it },
                                placeholder = { Text("https://url1.m3u\nhttps://url2.m3u", color = Color(0xFF64748B), fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 5
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (liveTvM3uInput.isNotBlank()) {
                                            val url = liveTvM3uInput.trim()
                                            repository.saveLiveTvM3uUrl(url)
                                            coroutineScope.launch {
                                                repository.pushAppConfigToFirebase(liveTvM3u = url)
                                            }
                                            onDataChanged()
                                            Toast.makeText(context, "✅ লাইভ টিভি M3U ক্লাউডে সেভ ও সিঙ্ক হয়েছে!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "সঠিক M3U লিংক দিন", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1.3f).height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("সেভ ও সিঙ্ক করুন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        liveTvM3uInput = MediaRepository.DEFAULT_LIVE_TV_M3U_URL
                                        repository.saveLiveTvM3uUrl(MediaRepository.DEFAULT_LIVE_TV_M3U_URL)
                                        coroutineScope.launch {
                                            repository.pushAppConfigToFirebase(liveTvM3u = MediaRepository.DEFAULT_LIVE_TV_M3U_URL)
                                        }
                                        onDataChanged()
                                        Toast.makeText(context, "ডিফল্ট Nafitv24.m3u লিঙ্ক রিসেট ও সিঙ্ক হয়েছে", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                                ) {
                                    Text("ডিফল্ট লিংক", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(text = "➕ Add Single Custom Channel (একক চ্যানেল)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                            OutlinedTextField(
                                value = channelName,
                                onValueChange = { channelName = it },
                                placeholder = { Text("Channel Name (e.g. T Sports HD)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = server1Url,
                                onValueChange = { server1Url = it },
                                placeholder = { Text("Server 1 (Primary Stream URL)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = server2Url,
                                onValueChange = { server2Url = it },
                                placeholder = { Text("Server 2 (Backup Stream URL - Optional)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = channelLogoUrl,
                                onValueChange = { channelLogoUrl = it },
                                placeholder = { Text("Channel Logo URL (Optional)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            Button(
                                onClick = {
                                    if (channelName.isNotBlank() && server1Url.isNotBlank()) {
                                        val servers = mutableListOf<StreamServer>()
                                        servers.add(StreamServer("সার্ভার ১ (Main)", server1Url.trim()))
                                        if (server2Url.isNotBlank()) servers.add(StreamServer("সার্ভার ২ (Backup)", server2Url.trim()))

                                        val item = MediaItem(
                                            id = "tv_${System.currentTimeMillis()}",
                                            title = channelName.trim(),
                                            category = channelCategory,
                                            type = MediaType.LIVE_TV,
                                            streamUrl = server1Url.trim(),
                                            backupUrl = server2Url.trim().takeIf { it.isNotBlank() },
                                            servers = servers,
                                            logoUrl = channelLogoUrl.trim().takeIf { it.isNotBlank() },
                                            isLive = true
                                        )
                                        repository.saveCustomStream(item)
                                        coroutineScope.launch {
                                            repository.pushToFirebase(item)
                                        }
                                        onDataChanged()
                                        channelName = ""
                                        server1Url = ""
                                        server2Url = ""
                                        channelLogoUrl = ""
                                        Toast.makeText(context, "চ্যানেল পাবলিশ হয়েছে!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Publish Channel to Firestore", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                items(liveTvList) { item ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("${item.category} • ${item.getAllServers().size} টি সার্ভার", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            // Edit Channel Button
                            Button(
                                onClick = {
                                    editingChannelItem = item
                                    editChannelName = item.title
                                    editChannelCategory = item.category
                                    editChannelLogoUrl = item.logoUrl ?: ""
                                    val curServers = item.getAllServers()
                                    editChannelServers = if (curServers.isNotEmpty()) curServers else listOf(StreamServer("সার্ভার ১ (Main)", item.streamUrl))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("এডিট", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    itemToDelete = item
                                }
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // TAB 3 CONTENT: MOVIES & SERIES ADMIN
            // -------------------------------------------------------------
            if (selectedAdminTab == AdminTab.MOVIES) {
                // 1. Primary M3U Playlist Manager for Movies (User: "এবং মুভি অপশনেও প্লেলি লিঙ্ক এড করা এতে firebase উপর চাপ পড়বে কম")
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Movie, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("🎬 মুভি ও সিরিজ প্লেলিস্ট লিংক (Movies M3U)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            }
                            Text(
                                text = "এখানে এক বা একাধিক মুভি M3U প্লেলিস্ট লিঙ্ক যোগ করতে পারবেন (প্রতি লাইনে একটি করে অথবা কমা দিয়ে)। এতে Firebase এ চাপ পড়বে না এবং সহজে ব্রাউজ করা যাবে:",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                            OutlinedTextField(
                                value = moviesM3uInput,
                                onValueChange = { moviesM3uInput = it },
                                placeholder = { Text("https://url1.m3u\nhttps://url2.m3u", color = Color(0xFF64748B), fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 5
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (moviesM3uInput.isNotBlank()) {
                                            val url = moviesM3uInput.trim()
                                            repository.saveMoviesM3uUrl(url)
                                            coroutineScope.launch {
                                                repository.pushAppConfigToFirebase(moviesM3u = url)
                                            }
                                            onDataChanged()
                                            Toast.makeText(context, "✅ মুভি M3U ক্লাউডে সেভ ও সিঙ্ক হয়েছে!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "অনুগ্রহ করে একটি সঠিক মুভি M3U URL দিন", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1.3f).height(44.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B), contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Rounded.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("সেভ ও সিঙ্ক করুন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        moviesM3uInput = MediaRepository.DEFAULT_MOVIES_M3U_URL
                                        repository.saveMoviesM3uUrl(MediaRepository.DEFAULT_MOVIES_M3U_URL)
                                        coroutineScope.launch {
                                            repository.pushAppConfigToFirebase(moviesM3u = MediaRepository.DEFAULT_MOVIES_M3U_URL)
                                        }
                                        onDataChanged()
                                        Toast.makeText(context, "মুভি প্লেলিস্ট রিসেট ও সিঙ্ক হয়েছে", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(44.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                                ) {
                                    Text("ডিফল্ট লিংক", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(text = "➕ Add Single Movie or Series (একক মুভি)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                            OutlinedTextField(
                                value = movieTitle,
                                onValueChange = { movieTitle = it },
                                placeholder = { Text("Movie Title (e.g. Toofan 2026)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = server1Url,
                                onValueChange = { server1Url = it },
                                placeholder = { Text("Server 1 (Video Stream URL)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = server2Url,
                                onValueChange = { server2Url = it },
                                placeholder = { Text("Server 2 (Fast Alternative URL)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = moviePosterUrl,
                                onValueChange = { moviePosterUrl = it },
                                placeholder = { Text("Poster Image URL", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            Button(
                                onClick = {
                                    if (movieTitle.isNotBlank() && server1Url.isNotBlank()) {
                                        val servers = mutableListOf<StreamServer>()
                                        servers.add(StreamServer("সার্ভার ১ (HD)", server1Url.trim()))
                                        if (server2Url.isNotBlank()) servers.add(StreamServer("সার্ভার ২ (4K)", server2Url.trim()))

                                        val item = MediaItem(
                                            id = "mov_${System.currentTimeMillis()}",
                                            title = movieTitle.trim(),
                                            category = movieCategory,
                                            type = MediaType.MOVIE,
                                            streamUrl = server1Url.trim(),
                                            backupUrl = server2Url.trim().takeIf { it.isNotBlank() },
                                            servers = servers,
                                            logoUrl = moviePosterUrl.trim().takeIf { it.isNotBlank() },
                                            isLive = false
                                        )
                                        repository.saveCustomStream(item)
                                        coroutineScope.launch { repository.pushToFirebase(item) }
                                        onDataChanged()
                                        movieTitle = ""
                                        server1Url = ""
                                        server2Url = ""
                                        moviePosterUrl = ""
                                        Toast.makeText(context, "মুভি যুক্ত হয়েছে!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Publish Movie to Firestore", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                items(moviesList) { item ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("${item.category} • ${item.getAllServers().size} টি সার্ভার", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            // Edit Movie Button
                            Button(
                                onClick = {
                                    editingMovieItem = item
                                    editMovieTitle = item.title
                                    editMovieCategory = item.category
                                    editMoviePosterUrl = item.logoUrl ?: ""
                                    editMovieDesc = item.description ?: ""
                                    val curServers = item.getAllServers()
                                    editMovieServers = if (curServers.isNotEmpty()) curServers else listOf(StreamServer("সার্ভার ১ (HD)", item.streamUrl))
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("এডিট", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    itemToDelete = item
                                }
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // TAB 4 CONTENT: PLAYLISTS ADMIN (M3U Playlists Management)
            // -------------------------------------------------------------
            if (selectedAdminTab == AdminTab.PLAYLISTS) {
                // Central M3U Links Hub
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.FeaturedPlayList, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("🌐 সেন্ট্রাল M3U প্লেলিস্ট কনফিগারেশন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                            Text(
                                text = "এখানে থাকা M3U লিংক থেকে সরাসরি চ্যানেল, খেলা ও মুভি লোড হবে (Firebase ডেটাবেসে লোড হবে না)। প্রতিটিতে এক বা একাধিক M3U লিংক দিতে পারেন (প্রতি লাইনে একটি করে অথবা কমা দিয়ে):",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )

                            // 1. Live TV Channels M3U URL
                            Text("1. লাইভ টিভি চ্যানেল M3U URL (একাধিক দেওয়া যাবে):", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = liveTvM3uInput,
                                onValueChange = { liveTvM3uInput = it },
                                placeholder = { Text("https://url1.m3u\nhttps://url2.m3u", color = Color(0xFF64748B), fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 5
                            )

                            // 2. Sports Matches M3U URL
                            Text("2. লাইভ স্পোর্টস ম্যাচ M3U URL (একাধিক দেওয়া যাবে):", color = Color(0xFF10B981), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = sportsM3uInput,
                                onValueChange = { sportsM3uInput = it },
                                placeholder = { Text("https://url1.m3u\nhttps://url2.m3u", color = Color(0xFF64748B), fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 5
                            )

                            // 3. Movies M3U URL
                            Text("3. মুভি ও সিরিজ M3U URL (একাধিক দেওয়া যাবে):", color = Color(0xFFF59E0B), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            OutlinedTextField(
                                value = moviesM3uInput,
                                onValueChange = { moviesM3uInput = it },
                                placeholder = { Text("https://url1.m3u\nhttps://url2.m3u", color = Color(0xFF64748B), fontSize = 12.sp) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 5
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        val liveUrl = liveTvM3uInput.trim()
                                        val sportsUrl = sportsM3uInput.trim()
                                        val movUrl = moviesM3uInput.trim()
                                        repository.saveLiveTvM3uUrl(liveUrl)
                                        repository.saveSportsM3uUrl(sportsUrl)
                                        repository.saveMoviesM3uUrl(movUrl)
                                        coroutineScope.launch {
                                            repository.pushAppConfigToFirebase(liveTvM3u = liveUrl, sportsM3u = sportsUrl, moviesM3u = movUrl)
                                        }
                                        onDataChanged()
                                        Toast.makeText(context, "✅ সকল M3U লিংক সেভ ও ক্লাউডে সিঙ্ক সম্পন্ন হয়েছে!", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1.3f).height(46.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Rounded.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("সব M3U সেভ ও সিঙ্ক", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        liveTvM3uInput = MediaRepository.DEFAULT_LIVE_TV_M3U_URL
                                        sportsM3uInput = MediaRepository.DEFAULT_SPORTS_M3U_URL
                                        moviesM3uInput = MediaRepository.DEFAULT_MOVIES_M3U_URL
                                        repository.saveLiveTvM3uUrl(MediaRepository.DEFAULT_LIVE_TV_M3U_URL)
                                        repository.saveSportsM3uUrl(MediaRepository.DEFAULT_SPORTS_M3U_URL)
                                        repository.saveMoviesM3uUrl(MediaRepository.DEFAULT_MOVIES_M3U_URL)
                                        coroutineScope.launch {
                                            repository.pushAppConfigToFirebase(
                                                liveTvM3u = MediaRepository.DEFAULT_LIVE_TV_M3U_URL,
                                                sportsM3u = MediaRepository.DEFAULT_SPORTS_M3U_URL,
                                                moviesM3u = MediaRepository.DEFAULT_MOVIES_M3U_URL
                                            )
                                        }
                                        onDataChanged()
                                        Toast.makeText(context, "সকল ডিফল্ট M3U লিংক রিসেট ও সিঙ্ক হয়েছে", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF94A3B8))
                                ) {
                                    Text("ডিফল্ট লিংক রিসেট", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(text = "➕ Add Custom M3U Playlist (কাস্টম প্লেলিস্ট)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)

                            OutlinedTextField(
                                value = playlistTitle,
                                onValueChange = { playlistTitle = it },
                                placeholder = { Text("Playlist Name (e.g. NAFI TV 24 Official)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = playlistUrl,
                                onValueChange = { playlistUrl = it },
                                placeholder = { Text("Playlist M3U / M3U8 URL (Raw Github/Server)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = playlistLogoUrl,
                                onValueChange = { playlistLogoUrl = it },
                                placeholder = { Text("Logo / Banner Image URL (Optional)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = playlistDescription,
                                onValueChange = { playlistDescription = it },
                                placeholder = { Text("Short Description (Optional)", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            Button(
                                onClick = {
                                    if (playlistTitle.isNotBlank() && playlistUrl.isNotBlank()) {
                                        val newPl = PlaylistInfo(
                                            id = "pl_${System.currentTimeMillis()}",
                                            title = playlistTitle.trim(),
                                            url = playlistUrl.trim(),
                                            logoUrl = playlistLogoUrl.trim().takeIf { it.isNotBlank() },
                                            description = playlistDescription.trim().takeIf { it.isNotBlank() },
                                            channelCount = 0
                                        )
                                        repository.saveCustomPlaylist(newPl)
                                        coroutineScope.launch {
                                            repository.pushPlaylistToFirebase(newPl)
                                        }
                                        onDataChanged()
                                        playlistTitle = ""
                                        playlistUrl = ""
                                        playlistLogoUrl = ""
                                        playlistDescription = ""
                                        Toast.makeText(context, "প্লেলিস্ট যুক্ত হয়েছে!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().height(48.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Publish Playlist to Firestore", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                items(playlistsList) { playlist ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF2563EB).copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (!playlist.logoUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = playlist.logoUrl,
                                        contentDescription = playlist.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                } else {
                                    Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = Color(0xFF00E5FF))
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(playlist.title, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(playlist.url, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            // Edit Playlist Button
                            Button(
                                onClick = {
                                    editingPlaylistItem = playlist
                                    editPlaylistTitle = playlist.title
                                    editPlaylistUrl = playlist.url
                                    editPlaylistLogoUrl = playlist.logoUrl ?: ""
                                    editPlaylistDescription = playlist.description ?: ""
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("এডিট", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    playlistToDelete = playlist
                                }
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color(0xFFEF4444))
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // TAB 5 CONTENT: IN-APP UPDATE & VERSION MANAGEMENT
            // -------------------------------------------------------------
            if (selectedAdminTab == AdminTab.APP_UPDATE) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.RocketLaunch, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "ইন-অ্যাপ আপডেট ও ভার্সন কন্ট্রোল",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Text(
                                text = "এখানে নতুন ভার্সন ও ডিরেক্ট APK ডাউনলোড লিংক দিয়ে পাবলিশ করলে সমস্ত ইউজারদের ডিভাইসে সুন্দর ইন-অ্যাপ ডাউনলোড ও ইনস্টল পপআপ চলে যাবে।",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                lineHeight = 17.sp
                            )

                            // Current vs Cloud Version Status Banner
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0F172A),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("বর্তমান বিল্ড ভার্সন:", color = Color(0xFF64748B), fontSize = 11.sp)
                                        Text("v${BuildConfig.VERSION_NAME} (Code ${BuildConfig.VERSION_CODE})", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF10B981).copy(alpha = 0.2f),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF10B981))
                                    ) {
                                        Text(
                                            text = "ক্লাউড: v$updateVersionName (Code $updateVersionCode)",
                                            color = Color(0xFF34D399),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Version Code and Version Name Fields
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = updateVersionCode,
                                    onValueChange = { updateVersionCode = it },
                                    label = { Text("Version Code (যেমন 26, 27)", fontSize = 11.sp) },
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedTextField(
                                    value = updateVersionName,
                                    onValueChange = { updateVersionName = it },
                                    label = { Text("Version Name (যেমন 2.5.2)", fontSize = 11.sp) },
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    modifier = Modifier.weight(1.2f)
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        updateVersionCode = com.example.BuildConfig.VERSION_CODE.toString()
                                        updateVersionName = com.example.BuildConfig.VERSION_NAME
                                        Toast.makeText(context, "বর্তমান অ্যাপ ভার্সন পূরণ করা হয়েছে (v${com.example.BuildConfig.VERSION_NAME})", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Icon(Icons.Rounded.Sync, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("বর্তমান ভার্সন দিয়ে পূরণ করুন (v${com.example.BuildConfig.VERSION_NAME})", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Direct APK Download URL
                            OutlinedTextField(
                                value = updateDownloadUrl,
                                onValueChange = { updateDownloadUrl = it },
                                label = { Text("Direct APK Download URL (.apk লিংক)") },
                                placeholder = { Text("https://example.com/nafitv24_v2.5.apk", color = Color(0xFF475569)) },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // APK Size & Release Date
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedTextField(
                                    value = updateApkSize,
                                    onValueChange = { updateApkSize = it },
                                    label = { Text("APK Size (যেমন 18.5 MB)", fontSize = 11.sp) },
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )

                                OutlinedTextField(
                                    value = updateReleaseDate,
                                    onValueChange = { updateReleaseDate = it },
                                    label = { Text("Release Date (যেমন 15 Aug 2026)", fontSize = 11.sp) },
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true,
                                    modifier = Modifier.weight(1.2f)
                                )
                            }

                            // Release Notes Field
                            OutlinedTextField(
                                value = updateReleaseNotes,
                                onValueChange = { updateReleaseNotes = it },
                                label = { Text("নতুন ফিচার ও চেঞ্জলগ (Release Notes)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                minLines = 3,
                                maxLines = 6,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // Mandatory Force Update Toggle Switch
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = Color(0xFF0F172A),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("বাধ্যতামূলক আপডেট (Force Update)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        Text("চালু রাখলে আপডেট না করা পর্যন্ত ব্যবহারকারী অ্যাপের মেনুতে ঢুকতে পারবে না", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                    Switch(
                                        checked = updateIsForce,
                                        onCheckedChange = { updateIsForce = it },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = Color(0xFFEF4444)
                                        )
                                    )
                                }
                            }

                            // Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        previewUpdateDialog = AppUpdateInfo(
                                            versionCode = updateVersionCode.toIntOrNull() ?: 2,
                                            versionName = updateVersionName.ifBlank { "2.5.0" },
                                            downloadUrl = updateDownloadUrl,
                                            releaseNotes = updateReleaseNotes,
                                            isForceUpdate = updateIsForce,
                                            apkSize = updateApkSize,
                                            releaseDate = updateReleaseDate
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF)),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                                ) {
                                    Icon(Icons.Rounded.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("পপআপ প্রিভিউ", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        val code = updateVersionCode.toIntOrNull() ?: 2
                                        val info = AppUpdateInfo(
                                            versionCode = code,
                                            versionName = updateVersionName.ifBlank { "2.5.0" },
                                            downloadUrl = updateDownloadUrl.trim(),
                                            releaseNotes = updateReleaseNotes.trim(),
                                            isForceUpdate = updateIsForce,
                                            apkSize = updateApkSize.trim(),
                                            releaseDate = updateReleaseDate.trim()
                                        )
                                        coroutineScope.launch {
                                            isSavingUpdate = true
                                            val ok = repository.pushAppUpdateInfo(info)
                                            isSavingUpdate = false
                                            if (ok) {
                                                Toast.makeText(context, "নতুন ভার্সন সফলভাবে Firebase ক্লাউডে পাবলিশ হয়েছে!", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(context, "Firebase এ সেভ হয়েছে ও ক্যাশে সংরক্ষিত হয়েছে", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .weight(1.5f)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black)
                                ) {
                                    Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (isSavingUpdate) "পাবলিশ হচ্ছে..." else "Firebase এ পাবলিশ করুন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }

            // -------------------------------------------------------------
            // TAB 6 CONTENT: FIREBASE CLOUD REALTIME DATABASE SETTINGS
            // -------------------------------------------------------------
            if (selectedAdminTab == AdminTab.FIREBASE) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.CloudSync, contentDescription = null, tint = Color(0xFFFF9800), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("🔥 Firebase Realtime Database সিঙ্ক সেটিংস", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }

                            Text(
                                text = "এখানে আপনার Firebase Realtime Database URL সেট করুন। এখান থেকে যোগ করা সকল খেলা, চ্যানেল এবং প্লেলিস্ট সরাসরি Firebase ডেটাবেসে সেভ হবে এবং ইনস্টল করা সকল ইউজারের অ্যাপে সরাসরি লাইভ দেখা যাবে।",
                                color = Color(0xFF94A3B8),
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )

                            OutlinedTextField(
                                value = firebaseUrlInput,
                                onValueChange = {
                                    firebaseUrlInput = it
                                    firebaseTestResult = null
                                },
                                label = { Text("Firebase Database URL") },
                                placeholder = { Text("https://your-project.firebaseio.com/", color = Color(0xFF64748B)) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true
                            )

                            // Test Connection & Save Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        coroutineScope.launch {
                                            isTestingFirebase = true
                                            firebaseTestResult = repository.testFirebaseConnection(firebaseUrlInput.trim())
                                            isTestingFirebase = false
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                                ) {
                                    if (isTestingFirebase) {
                                        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("টেস্ট হচ্ছে...", fontSize = 11.sp)
                                    } else {
                                        Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("টেস্ট কানেকশন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (firebaseUrlInput.isNotBlank()) {
                                            repository.saveFirebaseUrl(firebaseUrlInput.trim())
                                            onDataChanged()
                                            Toast.makeText(context, "✅ Firebase URL সফলভাবে সেভ হয়েছে!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "অনুগ্রহ করে একটি সঠিক Firebase URL দিন", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981), contentColor = Color.Black)
                                ) {
                                    Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("সেভ করুন", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                }
                            }

                            // Test Result Banner
                            firebaseTestResult?.let { res ->
                                Surface(
                                    color = if (res.first) Color(0xFF065F46).copy(alpha = 0.35f) else Color(0xFF7F1D1D).copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(10.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (res.first) Color(0xFF10B981) else Color(0xFFEF4444)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (res.first) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                                            contentDescription = null,
                                            tint = if (res.first) Color(0xFF10B981) else Color(0xFFEF4444),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = res.second,
                                            color = if (res.first) Color(0xFFA7F3D0) else Color(0xFFFECACA),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(color = Color(0xFF334155), modifier = Modifier.padding(vertical = 4.dp))

                            // Firebase Rules Setup Instructions Box
                            Surface(
                                color = Color(0xFF0F172A),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "📋 Firebase ডেটাবেস রুলস (Rules):",
                                        color = Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        text = "Firebase Console > Realtime Database > Rules ট্যাবে গিয়ে নিচের রুলস দিয়ে 'Publish' করুন:",
                                        color = Color(0xFFCBD5E1),
                                        fontSize = 11.sp
                                    )
                                    Surface(
                                        color = Color(0xFF020617),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "{\n  \"rules\": {\n    \".read\": true,\n    \".write\": true\n  }\n}",
                                            color = Color(0xFF10B981),
                                            fontSize = 11.sp,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            modifier = Modifier.padding(8.dp)
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

    // Dialog for Previewing App Update
    if (previewUpdateDialog != null) {
        AppUpdateDialog(
            updateInfo = previewUpdateDialog!!,
            onDismiss = { previewUpdateDialog = null }
        )
    }

    // Dialog for Deleting Playlist (Confirmation)
    if (playlistToDelete != null) {
        val target = playlistToDelete!!
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = Color(0xFF1E293B),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("প্লেলিস্ট ডিলিট নিশ্চিতকরণ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = "আপনি কি '${target.title}' প্লেলিস্টটি স্থায়ীভাবে ডিলিট করতে চান?",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toRemove = target
                        playlistToDelete = null
                        coroutineScope.launch {
                            repository.deletePlaylist(toRemove.id)
                            onDataChanged()
                            Toast.makeText(context, "${toRemove.title} প্লেলিস্ট ডিলিট করা হয়েছে!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { playlistToDelete = null },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
                ) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        )
    }

    // =========================================================================
    // FULL MATCH & MULTI-SERVER EDIT DIALOG (CRITICAL USER REQUIREMENT)
    // =========================================================================
    if (editingMatchItem != null) {
        val target = editingMatchItem!!
        Dialog(
            onDismissRequest = { editingMatchItem = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF2563EB).copy(alpha = 0.2f),
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Edit, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "খেলা ও সার্ভার এডিট করুন",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "লাইভ চলাকালীন নতুন সার্ভার লিংক যোগ বা পরিবর্তন",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }
                        IconButton(onClick = { editingMatchItem = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable Edit Form
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Tournament
                        item {
                            OutlinedTextField(
                                value = editTournament,
                                onValueChange = { editTournament = it },
                                label = { Text("টুর্নামেন্ট / সিরিজ (Tournament / Series)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Category & Status
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Category Dropdown
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = editSportCategory,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("খেলা (Category)", fontSize = 11.sp) },
                                        trailingIcon = {
                                            IconButton(onClick = { editSportDropdownExpanded = true }) {
                                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = Color(0xFF00E5FF))
                                            }
                                        },
                                        colors = customFieldColors(),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    DropdownMenu(
                                        expanded = editSportDropdownExpanded,
                                        onDismissRequest = { editSportDropdownExpanded = false },
                                        modifier = Modifier.background(Color(0xFF1E293B))
                                    ) {
                                        sportOptions.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option, color = Color.White) },
                                                onClick = {
                                                    editSportCategory = option
                                                    editSportDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                // Status Dropdown
                                Box(modifier = Modifier.weight(1f)) {
                                    OutlinedTextField(
                                        value = editSportStatus,
                                        onValueChange = {},
                                        readOnly = true,
                                        label = { Text("অবস্থা (Status)", fontSize = 11.sp) },
                                        trailingIcon = {
                                            IconButton(onClick = { editStatusDropdownExpanded = true }) {
                                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = Color(0xFF00E5FF))
                                            }
                                        },
                                        colors = customFieldColors(),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    DropdownMenu(
                                        expanded = editStatusDropdownExpanded,
                                        onDismissRequest = { editStatusDropdownExpanded = false },
                                        modifier = Modifier.background(Color(0xFF1E293B))
                                    ) {
                                        statusOptions.forEach { option ->
                                            DropdownMenuItem(
                                                text = { Text(option, color = Color.White) },
                                                onClick = {
                                                    editSportStatus = option
                                                    editStatusDropdownExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Team 1 Name & Score
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = editTeam1Name,
                                    onValueChange = { editTeam1Name = it },
                                    label = { Text("Team 1 Name") },
                                    modifier = Modifier.weight(1.3f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = editTeam1Score,
                                    onValueChange = { editTeam1Score = it },
                                    label = { Text("Team 1 Score") },
                                    placeholder = { Text("e.g. 182/4", color = Color(0xFF64748B), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                            }
                        }

                        // Team 1 Logo URL
                        item {
                            OutlinedTextField(
                                value = editTeam1Logo,
                                onValueChange = { editTeam1Logo = it },
                                label = { Text("Team 1 Logo URL (Optional)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Team 2 Name & Score
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = editTeam2Name,
                                    onValueChange = { editTeam2Name = it },
                                    label = { Text("Team 2 Name") },
                                    modifier = Modifier.weight(1.3f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = editTeam2Score,
                                    onValueChange = { editTeam2Score = it },
                                    label = { Text("Team 2 Score") },
                                    placeholder = { Text("e.g. 150/8", color = Color(0xFF64748B), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                            }
                        }

                        // Team 2 Logo URL
                        item {
                            OutlinedTextField(
                                value = editTeam2Logo,
                                onValueChange = { editTeam2Logo = it },
                                label = { Text("Team 2 Logo URL (Optional)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Match Time & Countdown
                        item {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = editMatchTime,
                                    onValueChange = { editMatchTime = it },
                                    label = { Text("Time (e.g. 06:30 AM)") },
                                    modifier = Modifier.weight(1.2f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = editCountdownHours,
                                    onValueChange = { editCountdownHours = it },
                                    label = { Text("Countdown (hrs)") },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                            }
                        }

                        // Multi-Server Header
                        item {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "⚡ মাল্টি-সার্ভার স্ট্রিম লিঙ্কসমূহ (${editServers.size} টি সার্ভার):",
                                    color = Color(0xFF00E5FF),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = Color(0xFF10B981).copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "খেলা চলাকালীন এডিটেবল",
                                        color = Color(0xFF10B981),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }

                        // Dynamic Server Inputs for Edit
                        items(editServers.size) { index ->
                            val server = editServers[index]
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = server.name,
                                    onValueChange = { newName ->
                                        editServers = editServers.toMutableList().also {
                                            it[index] = it[index].copy(name = newName)
                                        }
                                    },
                                    placeholder = { Text("সার্ভার নাম", color = Color(0xFF64748B), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = server.url,
                                    onValueChange = { newUrl ->
                                        editServers = editServers.toMutableList().also {
                                            it[index] = it[index].copy(url = newUrl)
                                        }
                                    },
                                    placeholder = { Text("Stream URL (.m3u8 / mp4)", color = Color(0xFF64748B), fontSize = 11.sp) },
                                    modifier = Modifier.weight(2f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                if (editServers.size > 1) {
                                    IconButton(
                                        onClick = {
                                            editServers = editServers.toMutableList().also { it.removeAt(index) }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "Remove Server",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Add Server Button in Edit Dialog
                        item {
                            OutlinedButton(
                                onClick = {
                                    val nextNum = editServers.size + 1
                                    val defaultName = when (nextNum) {
                                        1 -> "T SPORTS"
                                        2 -> "TT"
                                        3 -> "TEMP"
                                        4 -> "HD SERVER 4"
                                        5 -> "SERVER 5 (4K)"
                                        6 -> "SERVER 6 (HLS)"
                                        else -> "SERVER $nextNum"
                                    }
                                    editServers = editServers + StreamServer(defaultName, "")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("➕ আরও নতুন সার্ভার/চ্যানেল লিঙ্ক যোগ করুন (+ Add Server)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Bottom Action Buttons (Save & Update / Cancel)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { editingMatchItem = null },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
                        ) {
                            Text("বাতিল (Cancel)", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                val validServers = editServers.mapNotNull {
                                    if (it.url.isNotBlank()) StreamServer(it.name.ifBlank { "Server" }, it.url.trim()) else null
                                }

                                if (validServers.isNotEmpty()) {
                                    val matchTitle = if (editTeam1Name.isNotBlank() && editTeam2Name.isNotBlank()) {
                                        "$editTeam1Name vs $editTeam2Name"
                                    } else {
                                        editTournament.ifBlank { target.title }
                                    }

                                    val parsedCountdown = editCountdownHours.toLongOrNull()?.let { it * 3600L } ?: editCountdownHours.toLongOrNull()

                                    val updatedMatch = target.copy(
                                        title = matchTitle,
                                        tournament = editTournament.takeIf { it.isNotBlank() },
                                        category = editSportCategory,
                                        streamUrl = validServers.first().url,
                                        backupUrl = validServers.getOrNull(1)?.url,
                                        servers = validServers,
                                        isLive = editSportStatus.contains("Live", ignoreCase = true),
                                        status = editSportStatus,
                                        eventTime = editMatchTime.ifBlank { editSportStatus },
                                        team1 = editTeam1Name.takeIf { it.isNotBlank() },
                                        team1Logo = editTeam1Logo.takeIf { it.isNotBlank() },
                                        team2 = editTeam2Name.takeIf { it.isNotBlank() },
                                        team2Logo = editTeam2Logo.takeIf { it.isNotBlank() },
                                        matchTimeFormatted = editMatchTime.takeIf { it.isNotBlank() },
                                        countdownTargetSeconds = parsedCountdown,
                                        score1 = editTeam1Score.takeIf { it.isNotBlank() },
                                        score2 = editTeam2Score.takeIf { it.isNotBlank() }
                                    )

                                    repository.saveCustomStream(updatedMatch)
                                    coroutineScope.launch {
                                        repository.pushToFirebase(updatedMatch)
                                    }
                                    onDataChanged()
                                    editingMatchItem = null
                                    Toast.makeText(context, "খেলা ও সমস্ত সার্ভার লিঙ্ক সফলভাবে আপডেট হয়েছে!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "কমপক্ষে একটি কার্যকর সার্ভার স্ট্রিম লিংক দিন", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("ম্যাচ আপডেট করুন", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 2. LIVE TV CHANNEL EDIT DIALOG (User: এডমিন প্যানেলে সবগুলো এডিট করার অপশন)
    // =========================================================================
    if (editingChannelItem != null) {
        val target = editingChannelItem!!
        Dialog(
            onDismissRequest = { editingChannelItem = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.90f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF2563EB).copy(alpha = 0.2f),
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "টিভি চ্যানেল ও সার্ভার এডিট করুন",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = target.title,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(onClick = { editingChannelItem = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable Edit Form
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Channel Name
                        item {
                            OutlinedTextField(
                                value = editChannelName,
                                onValueChange = { editChannelName = it },
                                label = { Text("চ্যানেলের নাম (Channel Name)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Category Dropdown
                        item {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = editChannelCategory,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("ক্যাটাগরি (Category)") },
                                    trailingIcon = {
                                        IconButton(onClick = { editChannelCategoryDropdownExpanded = !editChannelCategoryDropdownExpanded }) {
                                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = Color.White)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().clickable { editChannelCategoryDropdownExpanded = true },
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                DropdownMenu(
                                    expanded = editChannelCategoryDropdownExpanded,
                                    onDismissRequest = { editChannelCategoryDropdownExpanded = false },
                                    modifier = Modifier.background(Color(0xFF1E293B))
                                ) {
                                    channelCategoryOptions.forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = Color.White) },
                                            onClick = {
                                                editChannelCategory = opt
                                                editChannelCategoryDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Logo URL
                        item {
                            OutlinedTextField(
                                value = editChannelLogoUrl,
                                onValueChange = { editChannelLogoUrl = it },
                                label = { Text("লোগো URL (Logo Image URL)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Multi-Servers Section Header
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.Dns, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "স্ট্রিমিং সার্ভার ও ব্যাকআপ লিংক (${editChannelServers.size} টি সার্ভার):",
                                        color = Color(0xFF00E5FF),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Dynamic Server Inputs
                        itemsIndexed(editChannelServers) { idx, srv ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = srv.name,
                                    onValueChange = { newName ->
                                        editChannelServers = editChannelServers.toMutableList().also {
                                            it[idx] = it[idx].copy(name = newName)
                                        }
                                    },
                                    placeholder = { Text("Server ${idx + 1} Name", color = Color(0xFF64748B), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = srv.url,
                                    onValueChange = { newUrl ->
                                        editChannelServers = editChannelServers.toMutableList().also {
                                            it[idx] = it[idx].copy(url = newUrl)
                                        }
                                    },
                                    placeholder = { Text("Stream URL (.m3u8 / .mpd)", color = Color(0xFF64748B), fontSize = 11.sp) },
                                    modifier = Modifier.weight(2f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                if (editChannelServers.size > 1) {
                                    IconButton(
                                        onClick = {
                                            editChannelServers = editChannelServers.toMutableList().also { it.removeAt(idx) }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "সার্ভার সরান",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Add Server Button
                        item {
                            OutlinedButton(
                                onClick = {
                                    val nextNum = editChannelServers.size + 1
                                    editChannelServers = editChannelServers + StreamServer("সার্ভার $nextNum", "")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF))
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("➕ আরও সার্ভার যোগ করুন (+ Add Server)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Dialog Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { editingChannelItem = null },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
                        ) {
                            Text("বাতিল (Cancel)", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                val validServers = editChannelServers.mapNotNull {
                                    if (it.url.isNotBlank()) StreamServer(it.name.ifBlank { "Server" }, it.url.trim()) else null
                                }

                                if (editChannelName.isNotBlank() && validServers.isNotEmpty()) {
                                    val updatedChannel = target.copy(
                                        title = editChannelName.trim(),
                                        category = editChannelCategory,
                                        type = MediaType.LIVE_TV,
                                        streamUrl = validServers.first().url,
                                        backupUrl = validServers.getOrNull(1)?.url,
                                        servers = validServers,
                                        logoUrl = editChannelLogoUrl.trim().takeIf { it.isNotBlank() },
                                        isLive = true
                                    )

                                    repository.saveCustomStream(updatedChannel)
                                    coroutineScope.launch {
                                        repository.pushToFirebase(updatedChannel)
                                    }
                                    onDataChanged()
                                    editingChannelItem = null
                                    Toast.makeText(context, "${updatedChannel.title} সফলভাবে আপডেট ও ক্লাউডে সিঙ্ক হয়েছে!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "চ্যানেলের নাম এবং কমপক্ষে একটি কার্যকর সার্ভার স্ট্রিম লিংক দিন", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("চ্যানেল আপডেট করুন", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 3. PLAYLIST EDIT DIALOG (User: এডমিন প্যানেলে প্লেলিস্ট এডিট করার অপশন)
    // =========================================================================
    if (editingPlaylistItem != null) {
        val target = editingPlaylistItem!!
        Dialog(
            onDismissRequest = { editingPlaylistItem = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .wrapContentHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF00E5FF).copy(alpha = 0.2f),
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.FeaturedPlayList, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "প্লেলিস্ট এডিট করুন",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = target.title,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(onClick = { editingPlaylistItem = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                        }
                    }

                    HorizontalDivider(color = Color(0xFF334155))

                    // Playlist Title
                    OutlinedTextField(
                        value = editPlaylistTitle,
                        onValueChange = { editPlaylistTitle = it },
                        label = { Text("প্লেলিস্টের নাম (Playlist Name)") },
                        colors = customFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Playlist M3U URL
                    OutlinedTextField(
                        value = editPlaylistUrl,
                        onValueChange = { editPlaylistUrl = it },
                        label = { Text("প্লেলিস্ট M3U / M3U8 URL") },
                        colors = customFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Logo / Banner URL
                    OutlinedTextField(
                        value = editPlaylistLogoUrl,
                        onValueChange = { editPlaylistLogoUrl = it },
                        label = { Text("লোগো / ব্যানার URL (ঐচ্ছিক)") },
                        colors = customFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Description
                    OutlinedTextField(
                        value = editPlaylistDescription,
                        onValueChange = { editPlaylistDescription = it },
                        label = { Text("বিবরণ (Short Description)") },
                        colors = customFieldColors(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    HorizontalDivider(color = Color(0xFF334155))

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { editingPlaylistItem = null },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
                        ) {
                            Text("বাতিল", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                if (editPlaylistTitle.isNotBlank() && editPlaylistUrl.isNotBlank()) {
                                    val updatedPl = target.copy(
                                        title = editPlaylistTitle.trim(),
                                        url = editPlaylistUrl.trim(),
                                        logoUrl = editPlaylistLogoUrl.trim().takeIf { it.isNotBlank() },
                                        description = editPlaylistDescription.trim().takeIf { it.isNotBlank() }
                                    )

                                    repository.saveCustomPlaylist(updatedPl)
                                    coroutineScope.launch {
                                        repository.pushPlaylistToFirebase(updatedPl)
                                    }
                                    onDataChanged()
                                    editingPlaylistItem = null
                                    Toast.makeText(context, "${updatedPl.title} প্লেলিস্ট সফলভাবে আপডেট হয়েছে!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "প্লেলিস্টের নাম এবং M3U URL উভয়ই পূরণ করুন", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                        ) {
                            Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("প্লেলিস্ট সেভ করুন", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 4. MOVIE & SERIES EDIT DIALOG (User: এডমিন প্যানেলে সবগুলো অপশন এডিট করার সুবিধা)
    // =========================================================================
    if (editingMovieItem != null) {
        val target = editingMovieItem!!
        Dialog(
            onDismissRequest = { editingMovieItem = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.90f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                shape = CircleShape,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.MovieFilter, contentDescription = null, tint = Color(0xFFFBBF24), modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "মুভি ও সিরিজ এডিট করুন",
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = target.title,
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            }
                        }
                        IconButton(onClick = { editingMovieItem = null }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable Edit Form
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Movie Title
                        item {
                            OutlinedTextField(
                                value = editMovieTitle,
                                onValueChange = { editMovieTitle = it },
                                label = { Text("মুভির নাম / শিরোনাম (Title)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Category Dropdown
                        item {
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedTextField(
                                    value = editMovieCategory,
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("ক্যাটাগরি (Category)") },
                                    trailingIcon = {
                                        IconButton(onClick = { editMovieCategoryDropdownExpanded = !editMovieCategoryDropdownExpanded }) {
                                            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, tint = Color.White)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().clickable { editMovieCategoryDropdownExpanded = true },
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                DropdownMenu(
                                    expanded = editMovieCategoryDropdownExpanded,
                                    onDismissRequest = { editMovieCategoryDropdownExpanded = false },
                                    modifier = Modifier.background(Color(0xFF1E293B))
                                ) {
                                    movieCategoryOptions.forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt, color = Color.White) },
                                            onClick = {
                                                editMovieCategory = opt
                                                editMovieCategoryDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        // Poster URL
                        item {
                            OutlinedTextField(
                                value = editMoviePosterUrl,
                                onValueChange = { editMoviePosterUrl = it },
                                label = { Text("পোস্টার / থাম্বনেইল URL (Poster URL)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Description
                        item {
                            OutlinedTextField(
                                value = editMovieDesc,
                                onValueChange = { editMovieDesc = it },
                                label = { Text("মুভির বিবরণ (Description)") },
                                colors = customFieldColors(),
                                shape = RoundedCornerShape(12.dp),
                                singleLine = false,
                                minLines = 2,
                                maxLines = 4,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        // Multi-Servers Section Header
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Dns, contentDescription = null, tint = Color(0xFFF59E0B), modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "মুভি স্ট্রিমিং সার্ভার (${editMovieServers.size} টি সার্ভার):",
                                    color = Color(0xFFF59E0B),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // Dynamic Server Inputs
                        itemsIndexed(editMovieServers) { idx, srv ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = srv.name,
                                    onValueChange = { newName ->
                                        editMovieServers = editMovieServers.toMutableList().also {
                                            it[idx] = it[idx].copy(name = newName)
                                        }
                                    },
                                    placeholder = { Text("Server ${idx + 1} Name", color = Color(0xFF64748B), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = srv.url,
                                    onValueChange = { newUrl ->
                                        editMovieServers = editMovieServers.toMutableList().also {
                                            it[idx] = it[idx].copy(url = newUrl)
                                        }
                                    },
                                    placeholder = { Text("Movie Stream URL (mp4 / m3u8)", color = Color(0xFF64748B), fontSize = 11.sp) },
                                    modifier = Modifier.weight(2f),
                                    colors = customFieldColors(),
                                    shape = RoundedCornerShape(12.dp),
                                    singleLine = true
                                )
                                if (editMovieServers.size > 1) {
                                    IconButton(
                                        onClick = {
                                            editMovieServers = editMovieServers.toMutableList().also { it.removeAt(idx) }
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.DeleteOutline,
                                            contentDescription = "সার্ভার সরান",
                                            tint = Color(0xFFEF4444),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Add Server Button
                        item {
                            OutlinedButton(
                                onClick = {
                                    val nextNum = editMovieServers.size + 1
                                    val defaultName = if (nextNum == 2) "সার্ভার ২ (4K)" else "সার্ভার $nextNum"
                                    editMovieServers = editMovieServers + StreamServer(defaultName, "")
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.5f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFF59E0B))
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("➕ আরও মুভি সার্ভার যোগ করুন (+ Add Server)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF334155))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Dialog Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { editingMovieItem = null },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
                        ) {
                            Text("বাতিল (Cancel)", fontSize = 13.sp)
                        }

                        Button(
                            onClick = {
                                val validServers = editMovieServers.mapNotNull {
                                    if (it.url.isNotBlank()) StreamServer(it.name.ifBlank { "HD Server" }, it.url.trim()) else null
                                }

                                if (editMovieTitle.isNotBlank() && validServers.isNotEmpty()) {
                                    val updatedMovie = target.copy(
                                        title = editMovieTitle.trim(),
                                        category = editMovieCategory,
                                        type = MediaType.MOVIE,
                                        streamUrl = validServers.first().url,
                                        backupUrl = validServers.getOrNull(1)?.url,
                                        servers = validServers,
                                        logoUrl = editMoviePosterUrl.trim().takeIf { it.isNotBlank() },
                                        description = editMovieDesc.trim().takeIf { it.isNotBlank() },
                                        isLive = false
                                    )

                                    repository.saveCustomStream(updatedMovie)
                                    coroutineScope.launch {
                                        repository.pushToFirebase(updatedMovie)
                                    }
                                    onDataChanged()
                                    editingMovieItem = null
                                    Toast.makeText(context, "${updatedMovie.title} সফলভাবে আপডেট ও ক্লাউডে সিঙ্ক হয়েছে!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "মুভির নাম এবং কমপক্ষে একটি কার্যকর সার্ভার স্ট্রিম লিংক দিন", Toast.LENGTH_SHORT).show()
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1.5f)
                                .height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF59E0B), contentColor = Color.Black)
                        ) {
                            Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("মুভি আপডেট করুন", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    // Dialog for Live Score Updating
    if (updatingItem != null) {
        val target = updatingItem!!
        AlertDialog(
            onDismissRequest = { updatingItem = null },
            containerColor = Color(0xFF1E293B),
            title = {
                Text("লাইভ স্কোর আপডেট করুন", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${target.team1 ?: "Team 1"} vs ${target.team2 ?: "Team 2"}", color = Color(0xFF00E5FF), fontSize = 13.sp)
                    OutlinedTextField(
                        value = updateScore1,
                        onValueChange = { updateScore1 = it },
                        label = { Text("${target.team1 ?: "Team 1"} Score") },
                        colors = customFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = updateScore2,
                        onValueChange = { updateScore2 = it },
                        label = { Text("${target.team2 ?: "Team 2"} Score") },
                        colors = customFieldColors(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = target.copy(score1 = updateScore1.trim(), score2 = updateScore2.trim())
                        repository.saveCustomStream(updated)
                        coroutineScope.launch {
                            repository.pushToFirebase(updated)
                        }
                        onDataChanged()
                        updatingItem = null
                        Toast.makeText(context, "স্কোর আপডেট হয়েছে!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                ) {
                    Text("সংরক্ষণ", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { updatingItem = null }) {
                    Text("বাতিল", color = Color.White)
                }
            }
        )
    }

    // Dialog for Deleting Item (Confirmation)
    if (itemToDelete != null) {
        val target = itemToDelete!!
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            containerColor = Color(0xFF1E293B),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.DeleteSweep, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("আইটেম ডিলিট নিশ্চিতকরণ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = "আপনি কি '${target.title}' নিশ্চিতভাবে ডিলিট করতে চান?\nএটি লোকাল মেমোরি ও ক্লাউড ডেটাবেস থেকে স্থায়ীভাবে মুছে যাবে।",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toRemove = target
                        itemToDelete = null
                        coroutineScope.launch {
                            repository.deleteMediaItem(toRemove)
                            onDataChanged()
                            Toast.makeText(context, "${toRemove.title} সফলভাবে ডিলিট করা হয়েছে!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete (মুছে ফেলুন)", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { itemToDelete = null },
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF475569))
                ) {
                    Text("Cancel (বাতিল)", fontSize = 12.sp)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// MENU & ADMIN CONTROL PANEL SCREEN (Matching Screenshot 1 & 2)
// -------------------------------------------------------------
@Composable
fun MenuScreen(
    repository: MediaRepository,
    customList: List<MediaItem>,
    onOpenAdminApp: () -> Unit,
    onPlayDirectStream: (url: String, title: String) -> Unit,
    onM3uLoaded: (List<MediaItem>) -> Unit,
    onCustomAdded: (MediaItem) -> Unit,
    onResetDefaults: () -> Unit,
    onCheckForUpdates: () -> Unit = {},
    availableUpdateInfo: AppUpdateInfo? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 1. Direct Stream State
    var directUrl by remember { mutableStateOf("") }
    var directTitle by remember { mutableStateOf("") }

    // 2. Load Custom M3U State
    var remoteM3uUrl by remember { mutableStateOf(repository.getSavedM3uUrl()) }
    var isLoadingM3u by remember { mutableStateOf(false) }

    // File picker launcher for .m3u / .m3u8
    val m3uFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val parsed = repository.parseM3uFromUri(it)
            if (parsed.isNotEmpty()) {
                onM3uLoaded(parsed)
                Toast.makeText(context, "${parsed.size} টি চ্যানেল ফাইল থেকে লোড হয়েছে!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "প্লেলিস্ট ফাইলটি পড়া যায়নি!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 3. Add Custom TV Channel State
    var channelName by remember { mutableStateOf("") }
    var channelCategory by remember { mutableStateOf("Sports") }
    var channelStreamUrl by remember { mutableStateOf("") }
    var channelLogoUrl by remember { mutableStateOf("") }
    val categoryOptions = listOf("Sports", "News", "Entertainment", "Movie", "Music", "Kids", "Infotainment", "Religious")
    var categoryDropdownExpanded by remember { mutableStateOf(false) }

    // 4. Admin Privacy / Login Dialog
    var showAdminLoginDialog by remember { mutableStateOf(false) }
    var adminPinInput by remember { mutableStateOf("") }
    var adminLoginError by remember { mutableStateOf<String?>(null) }

    // 5. Reset Defaults Dialog
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // CARD 1: Play Direct Stream Link (HLS / DASH / MP4)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Play Direct Stream Link (HLS / DASH / MP4)",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedTextField(
                        value = directUrl,
                        onValueChange = { directUrl = it },
                        placeholder = { Text("Enter stream URL (e.g. https://.../stream.m3u8)", color = Color(0xFF64748B), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = customFieldColors(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = directTitle,
                            onValueChange = { directTitle = it },
                            placeholder = { Text("Stream Title (Optional)", color = Color(0xFF64748B), fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            colors = customFieldColors(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = {
                                if (directUrl.isNotBlank()) {
                                    onPlayDirectStream(directUrl.trim(), directTitle.trim())
                                } else {
                                    Toast.makeText(context, "দয়া করে স্ট্রিম লিঙ্ক লিখুন", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2563EB),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text("Play Now", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // CARD 2: Load Custom M3U Playlist
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.Link,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Load Custom M3U Playlist",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = remoteM3uUrl,
                            onValueChange = { remoteM3uUrl = it },
                            placeholder = { Text("Remote M3U URL (e.g. https://.../list.m3u)", color = Color(0xFF64748B), fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            colors = customFieldColors(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp)
                        )

                        Button(
                            onClick = {
                                if (remoteM3uUrl.isNotBlank()) {
                                    isLoadingM3u = true
                                    repository.saveM3uUrl(remoteM3uUrl.trim())
                                    coroutineScope.launch {
                                        val parsed = repository.parseM3uFromUrl(remoteM3uUrl.trim())
                                        isLoadingM3u = false
                                        if (parsed.isNotEmpty()) {
                                            onM3uLoaded(parsed)
                                            Toast.makeText(context, "${parsed.size} টি চ্যানেল লোড হয়েছে!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "M3U লিঙ্কটি কাজ করছে না", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2563EB),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            if (isLoadingM3u) {
                                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text("Load", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    // Upload .m3u / .m3u8 File from Device button
                    OutlinedButton(
                        onClick = { m3uFileLauncher.launch("*/*") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF00E5FF)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Upload .m3u / .m3u8 File from Device",
                            fontWeight = FontWeight.Medium,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // CARD 3: Add Custom TV Channel
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AddCircleOutline,
                            contentDescription = null,
                            tint = Color(0xFF00E5FF),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add Custom TV Channel",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedTextField(
                        value = channelName,
                        onValueChange = { channelName = it },
                        placeholder = { Text("Channel Name (e.g. Sports HD)", color = Color(0xFF64748B), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = customFieldColors(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = channelCategory,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                IconButton(onClick = { categoryDropdownExpanded = !categoryDropdownExpanded }) {
                                    Icon(
                                        imageVector = Icons.Rounded.ArrowDropDown,
                                        contentDescription = "Dropdown",
                                        tint = Color.White
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { categoryDropdownExpanded = true },
                            colors = customFieldColors(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        DropdownMenu(
                            expanded = categoryDropdownExpanded,
                            onDismissRequest = { categoryDropdownExpanded = false },
                            modifier = Modifier.background(Color(0xFF1E293B))
                        ) {
                            categoryOptions.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat, color = Color.White) },
                                    onClick = {
                                        channelCategory = cat
                                        categoryDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = channelStreamUrl,
                        onValueChange = { channelStreamUrl = it },
                        placeholder = { Text("Stream URL (.m3u8 or video link)", color = Color(0xFF64748B), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = customFieldColors(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    OutlinedTextField(
                        value = channelLogoUrl,
                        onValueChange = { channelLogoUrl = it },
                        placeholder = { Text("Logo Image URL (Optional)", color = Color(0xFF64748B), fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = customFieldColors(),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = {
                            if (channelName.isNotBlank() && channelStreamUrl.isNotBlank()) {
                                val item = MediaItem(
                                    id = "channel_${System.currentTimeMillis()}",
                                    title = channelName.trim(),
                                    category = channelCategory,
                                    type = if (channelCategory.equals("Movie", true)) MediaType.MOVIE else MediaType.LIVE_TV,
                                    streamUrl = channelStreamUrl.trim(),
                                    servers = listOf(StreamServer("সার্ভার ১", channelStreamUrl.trim())),
                                    logoUrl = channelLogoUrl.trim().ifBlank { null },
                                    isLive = !channelCategory.equals("Movie", true)
                                )
                                onCustomAdded(item)
                                channelName = ""
                                channelStreamUrl = ""
                                channelLogoUrl = ""
                            } else {
                                Toast.makeText(context, "চ্যানেলের নাম এবং স্ট্রিম লিংক লিখুন", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2563EB),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlaylistAdd,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Add to Live TV List",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // CARD 4: এডমিন অ্যাপ (Admin Control Panel) 🔒 (Privacy Protected)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2563EB).copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = "Admin Shield",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "এডমিন অ্যাপ (Admin Control Panel)",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Rounded.Lock,
                                    contentDescription = "Lock",
                                    tint = Color(0xFFF59E0B),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = "চ্যানেল, লাইভ খেলা, মুভি ও প্লেলিস্ট নিয়ন্ত্রণের জন্য এডমিন অ্যাপে ঢুকুন",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = { showAdminLoginDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF2563EB),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Login",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // CARD 5: App Version & In-App Update Check
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2563EB).copy(alpha = 0.25f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.RocketLaunch,
                                    contentDescription = "Update Rocket",
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "অ্যাপ আপডেট ও ভার্সন",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "বর্তমান ভার্সন: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                        }

                        if (availableUpdateInfo != null && availableUpdateInfo.versionCode > BuildConfig.VERSION_CODE) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFEF4444).copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444))
                            ) {
                                Text(
                                    text = "v${availableUpdateInfo.versionName} প্রস্তুত!",
                                    color = Color(0xFFF87171),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    Text(
                        text = "নতুন নতুন টিভি চ্যানেল, ফাস্ট স্পোর্টস সার্ভার এবং উন্নত ভিডিও প্লেয়ার সুবিধার জন্য অ্যাপ নিয়মিত আপডেট রাখুন।",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    Button(
                        onClick = onCheckForUpdates,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (availableUpdateInfo != null && availableUpdateInfo.versionCode > BuildConfig.VERSION_CODE) Color(0xFF10B981) else Color(0xFF2563EB),
                            contentColor = if (availableUpdateInfo != null && availableUpdateInfo.versionCode > BuildConfig.VERSION_CODE) Color.Black else Color.White
                        )
                    ) {
                        Icon(
                            imageVector = if (availableUpdateInfo != null && availableUpdateInfo.versionCode > BuildConfig.VERSION_CODE) Icons.Rounded.Download else Icons.Rounded.SystemUpdate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (availableUpdateInfo != null && availableUpdateInfo.versionCode > BuildConfig.VERSION_CODE) "📥 এখনই নতুন ভার্সন আপডেট করুন (v${availableUpdateInfo.versionName})" else "🚀 নতুন আপডেট চেক করুন (Check Updates)",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // CARD 6: About NAFI TV 24
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.app_logo),
                            contentDescription = "NAFI TV Logo",
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Fit
                        )
                        Column {
                            Text(
                                text = "NAFI TV 24",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ultimate Live TV & Sports Streaming App",
                                color = Color(0xFF00E5FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Text(
                        text = "NAFI TV 24 is a full-featured Live TV, Sports & M3U Media Streaming application. Features include custom HLS stream decoding, auto-failover servers, mobile & TV remote compatible layouts, M3U file upload parsing, and live matchup countdowns.",
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    HorizontalDivider(color = Color(0xFF334155), thickness = 0.5.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Version ${com.example.BuildConfig.VERSION_NAME} (Build ${com.example.BuildConfig.VERSION_CODE})",
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }

    // DIALOG: Admin Privacy Password Login (Hidden & Protected)
    if (showAdminLoginDialog) {
        AlertDialog(
            onDismissRequest = {
                showAdminLoginDialog = false
                adminPinInput = ""
                adminLoginError = null
            },
            containerColor = Color(0xFF1E293B),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("এডমিন পাসওয়ার্ড দিন", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "এডমিন প্যানেলে প্রবেশ করার জন্য গোপন পাসওয়ার্ড দিন:",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = adminPinInput,
                        onValueChange = { adminPinInput = it },
                        placeholder = { Text("••••••••", color = Color(0xFF64748B)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        colors = customFieldColors(),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    if (adminLoginError != null) {
                        Text(
                            text = adminLoginError ?: "",
                            color = Color(0xFFEF4444),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (repository.verifyAdminPin(adminPinInput)) {
                            showAdminLoginDialog = false
                            adminPinInput = ""
                            adminLoginError = null
                            onOpenAdminApp()
                        } else {
                            adminLoginError = "ভুল পাসওয়ার্ড! পুনরায় চেষ্টা করুন।"
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black)
                ) {
                    Text("লগইন", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdminLoginDialog = false }) {
                    Text("বাতিল", color = Color.White)
                }
            }
        )
    }
}

// -------------------------------------------------------------
// TAB 1: EVENTS / SPORTS SCREEN (Exact match with user screenshot)
// -------------------------------------------------------------
@Composable
fun TeamLogoBadge(
    teamName: String,
    logoUrl: String?,
    modifier: Modifier = Modifier
) {
    val fallbackFlagUrl = when {
        teamName.contains("Bangladesh", ignoreCase = true) || teamName.contains("BD", ignoreCase = true) -> "https://flagcdn.com/w160/bd.png"
        teamName.contains("Australia", ignoreCase = true) || teamName.contains("AUS", ignoreCase = true) -> "https://flagcdn.com/w160/au.png"
        teamName.contains("India", ignoreCase = true) || teamName.contains("IND", ignoreCase = true) -> "https://flagcdn.com/w160/in.png"
        teamName.contains("Sri Lanka", ignoreCase = true) || teamName.contains("SL", ignoreCase = true) -> "https://flagcdn.com/w160/lk.png"
        teamName.contains("Pakistan", ignoreCase = true) || teamName.contains("PAK", ignoreCase = true) -> "https://flagcdn.com/w160/pk.png"
        teamName.contains("England", ignoreCase = true) || teamName.contains("ENG", ignoreCase = true) -> "https://flagcdn.com/w160/gb-eng.png"
        teamName.contains("Trent Rockets", ignoreCase = true) -> "https://images.unsplash.com/photo-1579952363873-27f3bade9f55?w=160"
        teamName.contains("Southern Brave", ignoreCase = true) -> "https://images.unsplash.com/photo-1517649763962-0c623266ddc0?w=160"
        teamName.contains("Real Madrid", ignoreCase = true) -> "https://images.unsplash.com/photo-1508098682722-e99c43a406b2?w=160"
        teamName.contains("Barcelona", ignoreCase = true) -> "https://images.unsplash.com/photo-1518091043644-c1d4457512c6?w=160"
        else -> null
    }

    val finalUrl = logoUrl?.takeIf { it.isNotBlank() } ?: fallbackFlagUrl

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF0F172A),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
        modifier = modifier.size(44.dp)
    ) {
        if (finalUrl != null) {
            AsyncImage(
                model = finalUrl,
                contentDescription = teamName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2563EB).copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = teamName.take(2).uppercase(),
                    color = Color(0xFF60A5FA),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

fun calculateEventRemainingSeconds(sport: MediaItem, tickCount: Long): Long {
    val raw = sport.countdownTargetSeconds
    if (raw != null && raw > 0L) {
        if (raw > 1_000_000_000_000L) {
            val diff = raw - System.currentTimeMillis()
            return maxOf(0L, diff / 1000L)
        } else if (raw > 1_000_000_000L) {
            val nowSec = System.currentTimeMillis() / 1000L
            return maxOf(0L, raw - nowSec)
        } else {
            return maxOf(0L, raw - tickCount)
        }
    }

    val timeStr = sport.eventTime ?: sport.matchTimeFormatted
    if (!timeStr.isNullOrBlank()) {
        try {
            val nowCal = java.util.Calendar.getInstance()
            val currentYear = nowCal.get(java.util.Calendar.YEAR)
            val currentMonth = nowCal.get(java.util.Calendar.MONTH)
            val currentDay = nowCal.get(java.util.Calendar.DAY_OF_MONTH)

            val patterns = listOf(
                "yyyy-MM-dd HH:mm",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd",
                "hh:mm a, MMM dd yyyy",
                "hh:mm a, MMM dd",
                "hh:mm a",
                "HH:mm"
            )
            for (pattern in patterns) {
                try {
                    val sdf = java.text.SimpleDateFormat(pattern, java.util.Locale.US)
                    sdf.timeZone = java.util.TimeZone.getDefault()
                    val date = sdf.parse(timeStr.trim())
                    if (date != null) {
                        val targetCal = java.util.Calendar.getInstance().apply { time = date }
                        if (pattern == "hh:mm a, MMM dd") {
                            targetCal.set(java.util.Calendar.YEAR, currentYear)
                        } else if (pattern == "hh:mm a" || pattern == "HH:mm") {
                            targetCal.set(java.util.Calendar.YEAR, currentYear)
                            targetCal.set(java.util.Calendar.MONTH, currentMonth)
                            targetCal.set(java.util.Calendar.DAY_OF_MONTH, currentDay)
                        }
                        val diffMillis = targetCal.timeInMillis - System.currentTimeMillis()
                        return maxOf(0L, diffMillis / 1000L)
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    return 0L
}

fun isEventLiveNow(sport: MediaItem, tickCount: Long): Boolean {
    if (sport.isLive || sport.status.equals("LIVE", ignoreCase = true) || sport.status.contains("LIVE NOW", ignoreCase = true)) {
        return true
    }
    val rem = calculateEventRemainingSeconds(sport, tickCount)
    if (rem == 0L && (sport.countdownTargetSeconds != null || !sport.eventTime.isNullOrBlank() || !sport.matchTimeFormatted.isNullOrBlank())) {
        return true
    }
    return false
}

@Composable
fun EventsScreen(
    sports: List<MediaItem>,
    favoriteIds: Set<String>,
    isTvMode: Boolean = false,
    onSelectMedia: (MediaItem) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    var selectedCategory by remember { mutableStateOf("Cricket") }
    var selectedStatus by remember { mutableStateOf("🔴 Live") }

    // Dynamic categories extracted from all sports matches
    val categories = remember(sports) {
        val defaultCats = listOf("Cricket", "Football", "Hockey", "More", "All")
        val uniqueCats = sports.map { it.category.trim() }.filter { it.isNotBlank() && !it.equals("All", ignoreCase = true) }.distinct()
        (defaultCats + uniqueCats).distinct()
    }
    val statusFilters = listOf("🔴 Live", "Upcoming", "Today", "Recent Results", "All")

    // Live ticking countdown state
    var tickCount by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000L)
            tickCount++
        }
    }

    val filteredSports = sports.filter { item ->
        val isLive = isEventLiveNow(item, tickCount)
        val catMatches = when (selectedCategory) {
            "All" -> true
            "Cricket" -> item.category.contains("Cricket", ignoreCase = true) || item.tournament?.contains("Cricket", ignoreCase = true) == true || item.title.contains("Cricket", ignoreCase = true)
            "Football" -> item.category.contains("Football", ignoreCase = true) || item.tournament?.contains("Football", ignoreCase = true) == true || item.title.contains("Football", ignoreCase = true)
            "Hockey" -> item.category.contains("Hockey", ignoreCase = true) || item.tournament?.contains("Hockey", ignoreCase = true) == true || item.title.contains("Hockey", ignoreCase = true)
            "More" -> !item.category.contains("Cricket", ignoreCase = true) && !item.category.contains("Football", ignoreCase = true)
            else -> item.category.contains(selectedCategory, ignoreCase = true) || item.tournament?.contains(selectedCategory, ignoreCase = true) == true || item.title.contains(selectedCategory, ignoreCase = true)
        }
        val statusMatches = when (selectedStatus) {
            "All" -> true
            "🔴 Live" -> isLive
            "Upcoming" -> !isLive
            "Today" -> true
            "Recent Results" -> !isLive
            else -> true
        }
        catMatches && statusMatches
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617)),
        contentPadding = PaddingValues(top = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // -------------------------------------------------------------
        // FILTER ROW 1: SPORTS CATEGORIES (Cricket, Football, Hockey, More)
        // -------------------------------------------------------------
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { cat ->
                    val isSelected = selectedCategory == cat
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B),
                        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.5f)),
                        modifier = Modifier.clickable { selectedCategory = cat }
                    ) {
                        Text(
                            text = cat,
                            color = if (isSelected) Color(0xFF020617) else Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // FILTER ROW 2: STATUS FILTERS (🔴 Live, Upcoming, Today, Recent Results)
        // -------------------------------------------------------------
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(statusFilters) { status ->
                    val isSelected = selectedStatus == status
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B),
                        border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.5f)),
                        modifier = Modifier.clickable { selectedStatus = status }
                    ) {
                        Text(
                            text = status,
                            color = if (isSelected) Color(0xFF020617) else if (status == "🔴 Live") Color(0xFFEF4444) else if (status == "Upcoming") Color(0xFFFBBF24) else Color(0xFF94A3B8),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // EMPTY STATE IF NO MATCHES
        // -------------------------------------------------------------
        if (filteredSports.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.EmojiEvents,
                            contentDescription = null,
                            tint = Color(0xFF475569),
                            modifier = Modifier.size(54.dp)
                        )
                        Text(
                            text = "কোনো লাইভ ম্যাচ বা ইভেন্ট পাওয়া যায়নি",
                            color = Color(0xFF94A3B8),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // -------------------------------------------------------------
        // EVENT MATCH CARDS (Clean, direct & multi-server)
        // -------------------------------------------------------------
        items(filteredSports) { sport ->
            val remainingSecs = calculateEventRemainingSeconds(sport, tickCount)
            val isLiveNow = isEventLiveNow(sport, tickCount)
            val matchFullTitle = when {
                !sport.tournament.isNullOrBlank() -> sport.tournament!!
                !sport.title.isNullOrBlank() && !sport.title.equals("Live Match", ignoreCase = true) -> sport.title
                !sport.team1.isNullOrBlank() && !sport.team2.isNullOrBlank() -> "${sport.category} 🏏 || ${sport.team1} vs ${sport.team2}"
                else -> "${sport.category} || Live Match"
            }
            val servers = sport.getAllServers()

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clickable {
                        onSelectMedia(sport)
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D33))
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Top Full-Width Notice Box for Match Title & Status (User requested: নোটিশ এর মতন সেই বক্সেই সম্পূর্ণ দেখায়)
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (sport.category.contains("Football", ignoreCase = true)) Icons.Rounded.SportsSoccer else Icons.Rounded.SportsCricket,
                                contentDescription = null,
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(18.dp)
                            )

                            Text(
                                text = matchFullTitle,
                                color = Color(0xFFE2E8F0),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .weight(1f)
                                    .basicMarquee(
                                        iterations = Int.MAX_VALUE,
                                        initialDelayMillis = 800
                                    ),
                                maxLines = 1,
                                softWrap = false
                            )

                            // Status Badge
                            if (isLiveNow) {
                                Surface(
                                    color = Color(0xFFEF4444).copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFFEF4444))
                                        )
                                        Text(
                                            text = "LIVE",
                                            color = Color(0xFFEF4444),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            } else {
                                Surface(
                                    color = Color(0xFFF59E0B).copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(6.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        text = "UPCOMING",
                                        color = Color(0xFFFBBF24),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Teams, Countdown / Score and Action Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Team 1 (Left)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            TeamLogoBadge(
                                teamName = sport.team1 ?: "Team 1",
                                logoUrl = sport.team1Logo
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = sport.team1 ?: "Team 1",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Center: Live Score or Countdown Timer or Match Time
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.weight(1.6f)
                        ) {
                            if (sport.score1 != null && sport.score2 != null && (sport.score1!!.isNotBlank() || sport.score2!!.isNotBlank())) {
                                // Live Score Display
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF0F172A),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.5f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = sport.score1 ?: "0",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                        Text("-", color = Color(0xFF94A3B8), fontSize = 13.sp)
                                        Text(
                                            text = sport.score2 ?: "0",
                                            color = Color(0xFF00E5FF),
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            } else if (!isLiveNow && remainingSecs > 0L) {
                                // Dynamic Live Ticking Countdown Calculation (Ticks every second)
                                val days = remainingSecs / 86400L
                                val hours = (remainingSecs % 86400L) / 3600L
                                val mins = (remainingSecs % 3600L) / 60L
                                val secs = remainingSecs % 60L
                                val countdownStr = if (days > 0) {
                                    String.format("⏳ %dd %02dh %02dm %02ds", days, hours, mins, secs)
                                } else {
                                    String.format("⏳ %02dh %02dm %02ds", hours, mins, secs)
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF450A0A).copy(alpha = 0.6f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        text = countdownStr,
                                        color = Color(0xFFFCA5A5),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            } else {
                                // Live Match streaming indicator
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF1E3A8A).copy(alpha = 0.5f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6).copy(alpha = 0.6f))
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xFF60A5FA))
                                        )
                                        Text(
                                            text = sport.matchTimeFormatted ?: sport.eventTime ?: "LIVE MATCH",
                                            color = Color(0xFF93C5FD),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }

                        // Team 2 (Right)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            TeamLogoBadge(
                                teamName = sport.team2 ?: "Team 2",
                                logoUrl = sport.team2Logo
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = sport.team2 ?: "Team 2",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Multi-Server Selector Row & Play Action
                    HorizontalDivider(color = Color(0xFF1E293B), thickness = 0.8.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Multi Server Chips
                        LazyRow(
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(servers) { srv ->
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color(0xFF1E293B),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                    modifier = Modifier.clickable {
                                        onSelectMedia(sport.copy(streamUrl = srv.url))
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Dns,
                                            contentDescription = null,
                                            tint = Color(0xFF60A5FA),
                                            modifier = Modifier.size(11.dp)
                                        )
                                        Text(
                                            text = srv.name,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Watch Live / Play Button
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isLiveNow) Color(0xFFDC2626) else Color(0xFF2563EB),
                            modifier = Modifier.clickable {
                                onSelectMedia(sport)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (isLiveNow) "Watch Live" else "Play",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 2: LIVE TV SCREEN (Screenshot 3 layout)
// -------------------------------------------------------------
@Composable
fun LiveTvTabScreen(
    channels: List<MediaItem>,
    favoriteIds: Set<String>,
    isTvMode: Boolean = false,
    onSelectMedia: (MediaItem) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = listOf(
        "All",
        "❤️ Favorites",
        "Bangladeshi",
        "Entertainment",
        "Islamic",
        "Sports",
        "News",
        "Kids",
        "Movies",
        "Music",
        "Infotainment"
    )

    val filtered = channels.filter { item ->
        val matchesSearch = item.title.contains(searchQuery, ignoreCase = true) ||
                item.category.contains(searchQuery, ignoreCase = true) ||
                (item.country != null && item.country.contains(searchQuery, ignoreCase = true))

        val matchesCategory = when (selectedCategory) {
            "All" -> true
            "❤️ Favorites" -> favoriteIds.contains(item.id)
            "Bangladeshi" -> item.category.contains("bangla", ignoreCase = true) ||
                    item.category.contains("bd", ignoreCase = true) ||
                    item.country?.equals("BD", ignoreCase = true) == true ||
                    item.title.contains("BTV", ignoreCase = true) ||
                    item.title.contains("Somoy", ignoreCase = true) ||
                    item.title.contains("Jamuna", ignoreCase = true) ||
                    item.title.contains("Channel 24", ignoreCase = true)
            "Entertainment" -> item.category.contains("entertain", ignoreCase = true) ||
                    item.category.contains("drama", ignoreCase = true) ||
                    item.category.contains("general", ignoreCase = true)
            "Islamic" -> item.category.contains("islam", ignoreCase = true) ||
                    item.category.contains("quran", ignoreCase = true) ||
                    item.category.contains("peace", ignoreCase = true) ||
                    item.title.contains("Peace", ignoreCase = true) ||
                    item.title.contains("Quran", ignoreCase = true)
            "Sports" -> item.category.contains("sport", ignoreCase = true) ||
                    item.category.contains("cricket", ignoreCase = true) ||
                    item.category.contains("football", ignoreCase = true) ||
                    item.title.contains("Sports", ignoreCase = true) ||
                    item.title.contains("Ten", ignoreCase = true)
            "News" -> item.category.contains("news", ignoreCase = true) ||
                    item.title.contains("News", ignoreCase = true) ||
                    item.title.contains("Somoy", ignoreCase = true)
            "Kids" -> item.category.contains("kid", ignoreCase = true) ||
                    item.category.contains("cartoon", ignoreCase = true) ||
                    item.category.contains("animation", ignoreCase = true)
            "Movies" -> item.category.contains("movie", ignoreCase = true) ||
                    item.category.contains("cinema", ignoreCase = true)
            "Music" -> item.category.contains("music", ignoreCase = true) ||
                    item.category.contains("song", ignoreCase = true)
            "Infotainment" -> item.category.contains("info", ignoreCase = true) ||
                    item.category.contains("doc", ignoreCase = true) ||
                    item.category.contains("nat geo", ignoreCase = true) ||
                    item.category.contains("discovery", ignoreCase = true)
            else -> item.category.contains(selectedCategory, ignoreCase = true)
        }

        matchesSearch && matchesCategory
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("টিভি চ্যানেল খুঁজুন (যেমন: Somoy, T Sports, BTV)", color = Color(0xFF94A3B8), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF00E5FF)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Color.White)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            shape = RoundedCornerShape(14.dp),
            colors = customFieldColors(),
            singleLine = true
        )

        // Filter Categories Horizontal Scroll (Screenshot 3 style)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(categories) { category ->
                val isSelected = selectedCategory == category
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B),
                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.clickable { selectedCategory = category }
                ) {
                    Text(
                        text = category,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Section Title with dynamic count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "📁 Live Channels (${filtered.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF00E5FF).copy(alpha = 0.15f)
            ) {
                Text(
                    text = "Auto-Sync",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // 3-Column Grid of Channels (Screenshot 3 style)
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.TvOff, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(48.dp))
                    Text("কোনো চ্যানেল পাওয়া যায়নি", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("অন্য কি-ওয়ার্ড দিয়ে সার্চ করুন অথবা ক্যাটাগরি পরিবর্তন করুন।", color = Color(0xFF94A3B8), fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (isTvMode) 5 else 3),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered) { channel ->
                    val isFav = favoriteIds.contains(channel.id)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMedia(channel) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            // Favorite toggle button at top right
                            IconButton(
                                onClick = { onToggleFavorite(channel.id) },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(28.dp)
                                    .padding(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = "Favorite",
                                    tint = if (isFav) Color(0xFFEF4444) else Color(0xFF64748B),
                                    modifier = Modifier.size(16.dp)
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Channel Logo in White Circle
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .background(Color.White),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!channel.logoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = channel.logoUrl,
                                            contentDescription = channel.title,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .size(46.dp)
                                                .clip(CircleShape)
                                        )
                                    } else {
                                        // Channel Initials
                                        val initials = channel.title.take(3).uppercase()
                                        Text(
                                            text = initials,
                                            color = Color(0xFF0F172A),
                                            fontWeight = FontWeight.Black,
                                            fontSize = 13.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Channel Title
                                Text(
                                    text = channel.title,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                // Category / Country Badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = Color(0xFF0F172A)
                                ) {
                                    Text(
                                        text = channel.country ?: channel.category.take(8),
                                        color = Color(0xFF00E5FF),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
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

// -------------------------------------------------------------
// TAB 3: MOVIES SCREEN (Screenshot 2 layout)
// -------------------------------------------------------------
@Composable
fun MoviesTabScreen(
    movies: List<MediaItem>,
    favoriteIds: Set<String>,
    isTvMode: Boolean = false,
    onSelectMedia: (MediaItem) -> Unit,
    onToggleFavorite: (String) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = listOf("All", "Bangla", "Hindi", "Hollywood", "Bollywood", "South", "Action", "Drama")

    val filtered = movies.filter { item ->
        val matchesSearch = item.title.contains(searchQuery, ignoreCase = true) ||
                item.category.contains(searchQuery, ignoreCase = true)

        val matchesCategory = when (selectedCategory) {
            "All" -> true
            else -> item.category.contains(selectedCategory, ignoreCase = true) ||
                    (item.description != null && item.description.contains(selectedCategory, ignoreCase = true))
        }

        matchesSearch && matchesCategory
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF020617))
    ) {
        // Search Bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("মুভি খুঁজুন (যেমন: DIN HINDI, Peddi, Toofan)", color = Color(0xFF94A3B8), fontSize = 13.sp) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF00E5FF)) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Color.White)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            shape = RoundedCornerShape(14.dp),
            colors = customFieldColors(),
            singleLine = true
        )

        // Filter Categories Horizontal Scroll (Screenshot 2 style)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            items(categories) { category ->
                val isSelected = selectedCategory == category
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isSelected) Color(0xFF00E5FF) else Color(0xFF1E293B),
                    border = if (isSelected) null else androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier.clickable { selectedCategory = category }
                ) {
                    Text(
                        text = category,
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                    )
                }
            }
        }

        // Section Title with dynamic count
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "📁 Movies Collection (${filtered.size})",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = Color(0xFF2563EB).copy(alpha = 0.2f)
            ) {
                Text(
                    text = "Ultra HD Cinema",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }

        // 2-Column Grid of Movies (Screenshot 2 style)
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Rounded.MovieFilter, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(48.dp))
                    Text("কোনো মুভি পাওয়া যায়নি", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("অন্য কি-ওয়ার্ড বা ক্যাটাগরি বেছে নিন।", color = Color(0xFF94A3B8), fontSize = 12.sp)
                }
            }
        } else {
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (isTvMode) 4 else 2),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(filtered) { movie ->
                    val isFav = favoriteIds.contains(movie.id)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectMedia(movie) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                    ) {
                        Column {
                            // Poster with Badges
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                            ) {
                                AsyncImage(
                                    model = movie.logoUrl ?: "https://images.unsplash.com/photo-1536440136628-849c177e76a1?w=400",
                                    contentDescription = movie.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Gradient shade
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color.Black.copy(alpha = 0.3f),
                                                    Color.Transparent,
                                                    Color(0xFF1E293B).copy(alpha = 0.95f)
                                                )
                                            )
                                        )
                                    )

                                // Top-Left Quality Badge (HD / 4K)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFF2563EB),
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .padding(8.dp)
                                ) {
                                    Text(
                                        text = movie.quality,
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }

                                // Top-Right Rating Badge (⭐ 8.0, ⭐ 8.8)
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFF59E0B),
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.Star,
                                            contentDescription = null,
                                            tint = Color.Black,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text(
                                            text = movie.rating ?: "8.5",
                                            color = Color.Black,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                // Favorite heart overlay
                                IconButton(
                                    onClick = { onToggleFavorite(movie.id) },
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(4.dp)
                                        .size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                        contentDescription = "Favorite",
                                        tint = if (isFav) Color(0xFFEF4444) else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            // Movie Title and Metadata
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = movie.title,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "${movie.category} • ${movie.year ?: "2024"}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -------------------------------------------------------------
// TAB 4: PLAYLIST SCREEN (Multi-Playlist M3U Hub)
// -------------------------------------------------------------
@Composable
fun PlaylistTabScreen(
    playlists: List<PlaylistInfo>,
    repository: MediaRepository,
    isTvMode: Boolean,
    onSelectMedia: (MediaItem, List<MediaItem>) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var activePlaylist by remember { mutableStateOf<PlaylistInfo?>(null) }
    var playlistChannels by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var isLoadingPlaylist by remember { mutableStateOf(false) }
    var channelSearchQuery by remember { mutableStateOf("") }
    var selectedChannelCategory by remember { mutableStateOf("All") }

    // Intercept back button when viewing channels within a playlist
    BackHandler(enabled = activePlaylist != null) {
        activePlaylist = null
        playlistChannels = emptyList()
        channelSearchQuery = ""
    }

    fun loadPlaylist(pl: PlaylistInfo) {
        activePlaylist = pl
        coroutineScope.launch {
            isLoadingPlaylist = true
            try {
                val items = repository.parseM3uFromUrl(pl.url)
                playlistChannels = items
                if (items.isEmpty()) {
                    Toast.makeText(context, "এই প্লেলিস্টে কোনো চ্যানেল পাওয়া যায়নি!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(context, "প্লেলিস্ট লোড করতে সমস্যা হয়েছে: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingPlaylist = false
            }
        }
    }

    if (activePlaylist != null) {
        // Detailed Playlist Channels Browser View
        val currentPl = activePlaylist!!
        val categories = remember(playlistChannels) {
            listOf("All") + playlistChannels.map { it.category }.distinct().filter { it.isNotBlank() }
        }

        val filteredChannels = remember(playlistChannels, channelSearchQuery, selectedChannelCategory) {
            playlistChannels.filter { item ->
                val matchesCategory = (selectedChannelCategory == "All" || item.category == selectedChannelCategory)
                val matchesQuery = channelSearchQuery.isBlank() ||
                        item.title.contains(channelSearchQuery, ignoreCase = true) ||
                        item.category.contains(channelSearchQuery, ignoreCase = true)
                matchesCategory && matchesQuery
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1120))
        ) {
            // Header Bar
            Surface(
                color = Color(0xFF1E293B),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            activePlaylist = null
                            playlistChannels = emptyList()
                            channelSearchQuery = ""
                        }
                    ) {
                        Icon(
                            Icons.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = currentPl.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isLoadingPlaylist) "লোড হচ্ছে..." else "${playlistChannels.size} টি চ্যানেল পাওয়া গেছে",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = { loadPlaylist(currentPl) }
                    ) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = "Refresh",
                            tint = Color(0xFF00E5FF)
                        )
                    }
                }
            }

            if (isLoadingPlaylist) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFF00E5FF))
                        Text("প্লেলিস্টের চ্যানেলসমূহ লোড হচ্ছে...", color = Color(0xFFCBD5E1), fontSize = 13.sp)
                    }
                }
            } else if (playlistChannels.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Rounded.CloudOff, contentDescription = null, tint = Color(0xFF64748B), modifier = Modifier.size(54.dp))
                        Text("কোনো চ্যানেল লোড করা যায়নি", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("লিংকটি চেক করুন অথবা রিফ্রেশ বাটনে ক্লিক করুন।", color = Color(0xFF94A3B8), fontSize = 12.sp, textAlign = TextAlign.Center)
                        Button(
                            onClick = { loadPlaylist(currentPl) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                        ) {
                            Text("পুনরায় চেষ্টা করুন")
                        }
                    }
                }
            } else {
                // Search Bar inside Playlist
                OutlinedTextField(
                    value = channelSearchQuery,
                    onValueChange = { channelSearchQuery = it },
                    placeholder = { Text("${currentPl.title}-এ চ্যানেল খুঁজুন...", color = Color(0xFF64748B), fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF00E5FF)) },
                    trailingIcon = {
                        if (channelSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { channelSearchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Color(0xFF94A3B8))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = customFieldColors(),
                    singleLine = true
                )

                // Category Chips
                if (categories.size > 2) {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        items(categories) { cat ->
                            val isSelected = (selectedChannelCategory == cat)
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) Color(0xFF2563EB) else Color(0xFF1E293B),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF00E5FF) else Color(0xFF334155)
                                ),
                                modifier = Modifier.clickable { selectedChannelCategory = cat }
                            ) {
                                Text(
                                    text = cat,
                                    color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                }

                // Channels Grid / List
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredChannels) { channel ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectMedia(channel, playlistChannels) },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0F172A)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!channel.logoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = channel.logoUrl,
                                            contentDescription = channel.title,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize().padding(4.dp)
                                        )
                                    } else {
                                        Icon(Icons.Rounded.LiveTv, contentDescription = null, tint = Color(0xFF00E5FF))
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = channel.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = channel.category,
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        maxLines = 1
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = Color(0xFF2563EB).copy(alpha = 0.2f),
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = "Play",
                                            tint = Color(0xFF00E5FF),
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Main Playlist Directory View (Only Name & Logo, clean & beautiful UI)
        var playlistSearchQuery by remember { mutableStateOf("") }
        val filteredPlaylists = remember(playlists, playlistSearchQuery) {
            if (playlistSearchQuery.isBlank()) playlists
            else playlists.filter { it.title.contains(playlistSearchQuery, ignoreCase = true) }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0B1120))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.FolderSpecial,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "IPTV Playlists",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF2563EB).copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2563EB).copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "${filteredPlaylists.size} টি প্লেলিস্ট",
                        color = Color(0xFF00E5FF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Search Bar
            if (playlists.size > 2) {
                OutlinedTextField(
                    value = playlistSearchQuery,
                    onValueChange = { playlistSearchQuery = it },
                    placeholder = { Text("প্লেলিস্ট খুঁজুন...", color = Color(0xFF64748B), fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color(0xFF00E5FF), modifier = Modifier.size(20.dp)) },
                    trailingIcon = {
                        if (playlistSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { playlistSearchQuery = "" }) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = customFieldColors(),
                    singleLine = true
                )
            }

            if (filteredPlaylists.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(54.dp)
                        )
                        Text(
                            text = "কোনো প্লেলিস্ট পাওয়া যায়নি",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            } else {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(if (isTvMode) 3 else 2),
                    contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(filteredPlaylists) { playlist ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { loadPlaylist(playlist) },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155).copy(alpha = 0.8f))
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                // Playlist Logo (Clean, centered & high visual quality)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(125.dp)
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(
                                                    Color(0xFF0F172A),
                                                    Color(0xFF1E293B)
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (!playlist.logoUrl.isNullOrBlank()) {
                                        AsyncImage(
                                            model = playlist.logoUrl,
                                            contentDescription = playlist.title,
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(14.dp)
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    Brush.linearGradient(
                                                        listOf(Color(0xFF00E5FF).copy(alpha = 0.25f), Color(0xFF2563EB).copy(alpha = 0.35f))
                                                    )
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.LiveTv,
                                                contentDescription = null,
                                                tint = Color(0xFF00E5FF),
                                                modifier = Modifier.size(30.dp)
                                            )
                                        }
                                    }
                                }

                                // Playlist Name (Clean, Bold, Aesthetic)
                                Surface(
                                    color = Color(0xFF0F172A).copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = playlist.title,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
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
    }
}

// -------------------------------------------------------------
// TEXT FIELD COLORS HELPER
// -------------------------------------------------------------
@Composable
fun customFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF00E5FF),
    unfocusedBorderColor = Color(0xFF334155),
    focusedContainerColor = Color(0xFF0F172A),
    unfocusedContainerColor = Color(0xFF0F172A),
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White
)
