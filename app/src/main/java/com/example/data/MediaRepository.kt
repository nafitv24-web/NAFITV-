package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.example.model.AppUpdateInfo
import com.example.model.MediaItem
import com.example.model.MediaType
import com.example.model.PlaylistInfo
import com.example.model.StreamServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class MediaRepository(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("nafitv_prefs", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        const val FIREBASE_PROJECT_ID = "nafitv24-live"
        const val FIREBASE_API_KEY = "AIzaSyDEhKK6T9kpKHICq4VSAXWoIQwQtfDFAX8"
        const val FIRESTORE_DATABASE_ID = "(default)"
        const val DEFAULT_RTDB_URL = "https://nafitv24-live-default-rtdb.firebaseio.com/"
        const val DEFAULT_LIVE_TV_M3U_URL = "https://raw.githubusercontent.com/nfiptv24-max/NAFITV/refs/heads/main/Nafitv24.m3u"
        const val DEFAULT_SPORTS_M3U_URL = "https://raw.githubusercontent.com/nfiptv24-max/NAFITV/refs/heads/main/NAFI%20Sports.m3u"
        const val DEFAULT_MOVIES_M3U_URL = ""
        const val DEFAULT_M3U_URL = DEFAULT_LIVE_TV_M3U_URL
        const val DEFAULT_ADMIN_PIN = "40541273"
    }

    // Admin Privacy / PIN Management
    fun getAdminPin(): String {
        val stored = prefs.getString("admin_pin", null)
        if (stored.isNullOrBlank() || stored == "2424") {
            return DEFAULT_ADMIN_PIN
        }
        return stored
    }

    fun setAdminPin(pin: String) {
        prefs.edit().putString("admin_pin", pin).apply()
    }

    fun verifyAdminPin(pin: String): Boolean {
        val current = getAdminPin().trim()
        return pin.trim() == current || pin.trim() == DEFAULT_ADMIN_PIN
    }

    // -------------------------------------------------------------
    // Deleted Items Persistence (Ensures deleted items NEVER reappear)
    // -------------------------------------------------------------
    fun getDeletedIds(): Set<String> {
        return prefs.getStringSet("deleted_ids", emptySet()) ?: emptySet()
    }

    fun addDeletedId(id: String) {
        val current = getDeletedIds().toMutableSet()
        current.add(id)
        prefs.edit().putStringSet("deleted_ids", current).apply()
    }

    fun clearDeletedIds() {
        prefs.edit().remove("deleted_ids").apply()
    }

    // No hardcoded sample sports - strictly loads from Sports M3U, Firebase RTDB & Admin Added streams
    fun getInitialSports(): List<MediaItem> {
        return emptyList()
    }

    // No hardcoded sample TV channels - strictly loads from Live TV M3U (Nafitv24.m3u), Firebase RTDB & Admin Added channels
    fun getInitialLiveTv(): List<MediaItem> {
        return emptyList()
    }

    // No hardcoded sample movies - strictly loads from Movies M3U, Firebase RTDB & Admin Added movies
    fun getInitialMoviesSeries(): List<MediaItem> {
        return emptyList()
    }

    // Custom streams saved locally in SharedPreferences
    fun getCustomStreams(): List<MediaItem> {
        val deleted = getDeletedIds()
        val jsonStr = prefs.getString("custom_streams", "[]") ?: "[]"
        val list = mutableListOf<MediaItem>()
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.optString("id", "c_$i")
                if (!deleted.contains(id)) {
                    list.add(parseMediaFromJsonObj(id, obj))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveCustomStream(item: MediaItem) {
        val current = getCustomStreams().toMutableList()
        current.removeAll { it.id == item.id }
        current.add(0, item)
        saveCustomList(current)
    }

    fun saveCustomList(list: List<MediaItem>) {
        val jsonArray = JSONArray()
        list.forEach { item ->
            jsonArray.put(serializeMediaToJsonObj(item))
        }
        prefs.edit().putString("custom_streams", jsonArray.toString()).apply()
    }

    fun deleteCustomStream(id: String) {
        addDeletedId(id)
        val current = getCustomStreams().filterNot { it.id == id }
        saveCustomList(current)
    }

    suspend fun deleteMediaItem(item: MediaItem): Boolean {
        return deleteMediaItem(item.id, item.type)
    }

    suspend fun deleteMediaItem(id: String, type: MediaType): Boolean {
        // 1. Mark in permanent deleted set
        addDeletedId(id)
        // 2. Remove from local custom streams
        val current = getCustomStreams().filterNot { it.id == id }
        saveCustomList(current)
        // 3. Remove from Firebase
        return deleteFromFirebase(id, type)
    }

    fun updateScore(id: String, score1: String, score2: String) {
        val current = getCustomStreams().map {
            if (it.id == id) it.copy(score1 = score1, score2 = score2) else it
        }
        saveCustomList(current)
    }

    // Favorite management
    fun getFavoriteIds(): Set<String> {
        return prefs.getStringSet("favorites", emptySet()) ?: emptySet()
    }

    fun toggleFavorite(id: String): Boolean {
        val favs = getFavoriteIds().toMutableSet()
        val isFav: Boolean
        if (favs.contains(id)) {
            favs.remove(id)
            isFav = false
        } else {
            favs.add(id)
            isFav = true
        }
        prefs.edit().putStringSet("favorites", favs).apply()
        return isFav
    }

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }

    // M3U parser from Uri
    fun parseM3uFromUri(uri: Uri): List<MediaItem> {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return emptyList()
            val reader = BufferedReader(InputStreamReader(inputStream))
            val lines = reader.readLines()
            reader.close()
            parseM3uLines(lines)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    // M3U parser from URL (Supports single or multiple URLs separated by newlines, commas, or semicolons)
    suspend fun parseM3uFromUrl(rawInput: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val urls = extractUrls(rawInput)
        if (urls.isEmpty()) return@withContext emptyList()
        if (urls.size == 1) {
            return@withContext fetchSingleM3uUrl(urls[0])
        }
        val deferredList = urls.map { singleUrl ->
            async { fetchSingleM3uUrl(singleUrl) }
        }
        deferredList.awaitAll().flatten().distinctBy {
            if (it.streamUrl.isNotBlank()) it.streamUrl else it.id
        }
    }

    fun extractUrls(input: String): List<String> {
        if (input.isBlank()) return emptyList()
        return input.split(Regex("[\r\n,;]+"))
            .map { it.trim() }
            .filter { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            .distinct()
    }

    private suspend fun fetchSingleM3uUrl(url: String): List<MediaItem> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NAFITV24/2.4.0 (Android ExoPlayer)")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val content = response.body?.string() ?: return@withContext emptyList()
            parseM3uLines(content.lines())
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun parseM3uLines(lines: List<String>): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        var currentTitle = ""
        var currentLogo: String? = null
        var currentGroup = "Live TV"
        var currentCountry: String? = null
        var currentUserAgent: String? = null
        var currentReferrer: String? = null
        var currentCookie: String? = null
        var currentOrigin: String? = null
        var currentDrmScheme: String? = null
        var currentDrmKey: String? = null
        var currentDrmLicenseUrl: String? = null
        var currentManifestType: String? = null
        val currentCustomHeaders = mutableMapOf<String, String>()
        val currentDrmHeaders = mutableMapOf<String, String>()
        var currentId = 1

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("#EXTINF:", ignoreCase = true)) {
                val groupMatch = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE).find(trimmed)
                currentGroup = groupMatch?.groupValues?.get(1)?.trim() ?: "Live TV"

                val logoMatch = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE).find(trimmed)
                currentLogo = logoMatch?.groupValues?.get(1)?.trim()

                val countryMatch = Regex("""tvg-country="([^"]*)"""", RegexOption.IGNORE_CASE).find(trimmed)
                currentCountry = countryMatch?.groupValues?.get(1)?.trim()

                val commaIndex = trimmed.lastIndexOf(',')
                currentTitle = if (commaIndex != -1) {
                    trimmed.substring(commaIndex + 1).trim()
                } else {
                    "Channel $currentId"
                }

                // If country tag is in the title e.g. "Arirang World KR" or "[BD]"
                if (currentCountry == null) {
                    val matchCode = Regex("""\b(BD|KR|IN|US|UK|PK|SA|UAE)\b""", RegexOption.IGNORE_CASE).find(currentTitle)
                    currentCountry = matchCode?.groupValues?.get(1)?.uppercase()
                }
            } else if (trimmed.startsWith("#EXTVLCOPT:", ignoreCase = true)) {
                val opt = trimmed.substringAfter(":").trim()
                val optKey = opt.substringBefore("=").trim().lowercase()
                val optVal = opt.substringAfter("=").trim()
                when {
                    optKey.contains("user-agent") -> currentUserAgent = optVal
                    optKey.contains("referrer") || optKey.contains("referer") -> currentReferrer = optVal
                    optKey.contains("origin") -> currentOrigin = optVal
                    optKey.contains("cookie") -> currentCookie = optVal
                    optKey.contains("clearkey") || optKey.contains("license_key") || optKey.contains("drm_key") -> currentDrmKey = optVal
                    optKey.contains("license_type") || optKey.contains("drm_type") -> currentDrmScheme = optVal
                    else -> currentCustomHeaders[optKey] = optVal
                }
            } else if (trimmed.startsWith("#EXTHTTP:", ignoreCase = true)) {
                val jsonPart = trimmed.substringAfter(":").trim()
                try {
                    val jsonObj = JSONObject(jsonPart)
                    if (jsonObj.has("User-Agent")) currentUserAgent = jsonObj.optString("User-Agent")
                    if (jsonObj.has("user-agent")) currentUserAgent = jsonObj.optString("user-agent")
                    if (jsonObj.has("Referer")) currentReferrer = jsonObj.optString("Referer")
                    if (jsonObj.has("referer")) currentReferrer = jsonObj.optString("referer")
                    if (jsonObj.has("Origin")) currentOrigin = jsonObj.optString("Origin")
                    if (jsonObj.has("origin")) currentOrigin = jsonObj.optString("origin")
                    if (jsonObj.has("Cookie")) currentCookie = jsonObj.optString("Cookie")
                    if (jsonObj.has("cookie")) currentCookie = jsonObj.optString("cookie")
                    val keys = jsonObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        currentCustomHeaders[k] = jsonObj.optString(k)
                    }
                } catch (_: Exception) {}
            } else if (trimmed.startsWith("#KODIPROP:", ignoreCase = true)) {
                val prop = trimmed.substringAfter(":").trim()
                val propKey = prop.substringBefore("=").trim().lowercase()
                val propVal = prop.substringAfter("=").trim()
                when {
                    propKey.contains("license_type") || propKey.contains("drm_type") || propKey.contains("license_security") || propKey.contains("drm_scheme") -> {
                        currentDrmScheme = propVal
                    }
                    propKey.contains("license_key") || propKey.contains("drm_key") || propKey.contains("clearkey") || propKey.contains("license_data") || propKey.contains("drm_license") -> {
                        if (propVal.startsWith("http://", ignoreCase = true) || propVal.startsWith("https://", ignoreCase = true)) {
                            currentDrmLicenseUrl = propVal
                        } else {
                            currentDrmKey = propVal
                        }
                    }
                    propKey.contains("manifest_type") || propKey.contains("stream_type") -> {
                        currentManifestType = propVal
                    }
                    propKey.contains("stream_headers") || propKey.contains("manifest_headers") -> {
                        val pairs = propVal.split("&")
                        for (pair in pairs) {
                            val kv = pair.split("=", limit = 2)
                            if (kv.size == 2) {
                                val k = kv[0].trim()
                                val v = try { java.net.URLDecoder.decode(kv[1].trim(), "UTF-8") } catch (_: Exception) { kv[1].trim() }
                                when {
                                    k.equals("User-Agent", ignoreCase = true) -> currentUserAgent = v
                                    k.equals("Referer", ignoreCase = true) || k.equals("Referrer", ignoreCase = true) -> currentReferrer = v
                                    k.equals("Origin", ignoreCase = true) -> currentOrigin = v
                                    k.equals("Cookie", ignoreCase = true) -> currentCookie = v
                                    else -> currentCustomHeaders[k] = v
                                }
                            }
                        }
                    }
                    else -> {
                        currentCustomHeaders[propKey] = propVal
                    }
                }
            } else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                var streamUrl = trimmed
                if (streamUrl.contains("|")) {
                    val pipeParts = streamUrl.split("|", limit = 2)
                    streamUrl = pipeParts[0].trim()
                    val queryHeaders = pipeParts[1].split("&")
                    for (qh in queryHeaders) {
                        val kv = qh.split("=", limit = 2)
                        if (kv.size == 2) {
                            val k = kv[0].trim()
                            val rawV = kv[1].trim()
                            val v = try {
                                java.net.URLDecoder.decode(rawV, "UTF-8")
                            } catch (_: Exception) {
                                rawV
                            }
                            when {
                                k.equals("User-Agent", ignoreCase = true) || k.equals("http-user-agent", ignoreCase = true) -> currentUserAgent = v
                                k.equals("Referer", ignoreCase = true) || k.equals("Referrer", ignoreCase = true) || k.equals("http-referer", ignoreCase = true) -> currentReferrer = v
                                k.equals("Origin", ignoreCase = true) || k.equals("http-origin", ignoreCase = true) -> currentOrigin = v
                                k.equals("Cookie", ignoreCase = true) || k.equals("http-cookie", ignoreCase = true) -> currentCookie = v
                                k.equals("license_type", ignoreCase = true) || k.equals("drm_type", ignoreCase = true) -> currentDrmScheme = v
                                k.equals("license_key", ignoreCase = true) || k.equals("drm_key", ignoreCase = true) || k.equals("clearkey", ignoreCase = true) -> currentDrmKey = v
                                k.equals("manifest_type", ignoreCase = true) -> currentManifestType = v
                                else -> currentCustomHeaders[k] = v
                            }
                        }
                    }
                }

                // Automatic intelligent headers detection for Toffee & OTT streams
                val isToffee = streamUrl.contains("toffeelive.com", ignoreCase = true) ||
                        streamUrl.contains("toffee", ignoreCase = true) ||
                        streamUrl.contains("bldcmprod-cdn", ignoreCase = true) ||
                        currentGroup.contains("toffee", ignoreCase = true)

                if (isToffee) {
                    if (currentUserAgent.isNullOrBlank()) currentUserAgent = "Toffee (Linux;Android 14)"
                    if (currentReferrer.isNullOrBlank()) currentReferrer = "https://toffeelive.com/"
                    if (currentOrigin.isNullOrBlank()) currentOrigin = "https://toffeelive.com"
                }

                val isSport = currentGroup.contains("sport", ignoreCase = true) ||
                        currentTitle.contains("sport", ignoreCase = true) ||
                        currentTitle.contains("cricket", ignoreCase = true) ||
                        currentTitle.contains("football", ignoreCase = true)

                val isMovie = currentGroup.contains("movie", ignoreCase = true) ||
                        currentGroup.contains("cinema", ignoreCase = true) ||
                        currentGroup.contains("vod", ignoreCase = true)

                val mediaType = when {
                    isSport -> MediaType.LIVE_EVENT
                    isMovie -> MediaType.MOVIE
                    else -> MediaType.LIVE_TV
                }

                // Clean display title
                val cleanTitle = currentTitle.ifEmpty { "Channel $currentId" }

                items.add(
                    MediaItem(
                        id = "m3u_${currentId++}_${System.currentTimeMillis() % 10000}",
                        title = cleanTitle,
                        category = currentGroup,
                        type = mediaType,
                        streamUrl = streamUrl,
                        servers = listOf(
                            StreamServer("সার্ভার ১ (Main)", streamUrl)
                        ),
                        logoUrl = currentLogo,
                        country = currentCountry,
                        isLive = mediaType != MediaType.MOVIE,
                        quality = "HD",
                        rating = if (mediaType == MediaType.MOVIE) "8.5" else null,
                        year = if (mediaType == MediaType.MOVIE) "2024" else null,
                        userAgent = currentUserAgent,
                        referrer = currentReferrer,
                        cookie = currentCookie,
                        origin = currentOrigin,
                        customHeaders = if (currentCustomHeaders.isNotEmpty()) currentCustomHeaders.toMap() else null,
                        drmScheme = currentDrmScheme,
                        drmLicenseUrl = currentDrmLicenseUrl,
                        drmLicenseKey = currentDrmKey,
                        drmHeaders = if (currentDrmHeaders.isNotEmpty()) currentDrmHeaders.toMap() else null,
                        manifestType = currentManifestType
                    )
                )

                currentTitle = ""
                currentLogo = null
                currentCountry = null
                currentUserAgent = null
                currentReferrer = null
                currentCookie = null
                currentOrigin = null
                currentDrmScheme = null
                currentDrmKey = null
                currentDrmLicenseUrl = null
                currentManifestType = null
                currentCustomHeaders.clear()
                currentDrmHeaders.clear()
            }
        }
        return items
    }

    // -------------------------------------------------------------
    // Firebase Realtime Database & Cloud Firestore Integration
    // -------------------------------------------------------------
    suspend fun testFirebaseConnection(url: String = getSavedFirebaseUrl()): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        var isAnyConnected = false

        // 1. Test Firestore REST API
        try {
            val firestoreUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/$FIRESTORE_DATABASE_ID/documents/sports?key=$FIREBASE_API_KEY"
            val req = Request.Builder().url(firestoreUrl).header("User-Agent", "NAFITV24-Android/2.5.0").build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful || resp.code == 404) {
                isAnyConnected = true
                results.add("✅ Cloud Firestore সক্রিয় ($FIRESTORE_DATABASE_ID)")
            } else if (resp.code == 403 || resp.code == 401) {
                results.add("⚠️ Firestore পারমিশন রুলস চেক করুন (HTTP ${resp.code})")
            }
        } catch (e: Exception) {
            // ignore
        }

        // 2. Test Realtime Database
        if (url.isNotBlank()) {
            try {
                val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
                val targetUrl = if (cleanUrl.endsWith(".json")) cleanUrl else "$cleanUrl/.json"

                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "NAFITV24-Android/2.5.0")
                    .build()

                val response = client.newCall(request).execute()
                val code = response.code
                if (response.isSuccessful) {
                    isAnyConnected = true
                    results.add("✅ Realtime Database সক্রিয় (HTTP $code)")
                } else if (code == 401 || code == 403) {
                    results.add("⚠️ RTDB Rules এ \".read\": true, \".write\": true দিন")
                } else {
                    results.add("ℹ️ RTDB Status: HTTP $code")
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        if (isAnyConnected) {
            Pair(true, results.joinToString(" | "))
        } else {
            Pair(false, if (results.isNotEmpty()) results.joinToString(" | ") else "⚠️ কানেকশন এরর: সার্ভারে পৌঁছানো সম্ভব হয়নি")
        }
    }

    private suspend fun fetchFromFirestore(): List<MediaItem> = withContext(Dispatchers.IO) {
        val deleted = getDeletedIds()
        val items = mutableListOf<MediaItem>()
        val collections = listOf("sports", "events", "matches", "channels", "movies")
        val databases = listOf(FIRESTORE_DATABASE_ID, "(default)")

        for (dbId in databases) {
            for (col in collections) {
                try {
                    val firestoreUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/$dbId/documents/$col?key=$FIREBASE_API_KEY"
                    val req = Request.Builder().url(firestoreUrl).header("User-Agent", "NAFITV24-Android/2.5.0").build()
                    val resp = client.newCall(req).execute()
                    if (!resp.isSuccessful) continue
                    val body = resp.body?.string() ?: continue
                    if (body.isBlank() || !body.startsWith("{")) continue

                    val json = JSONObject(body)
                    val docs = json.optJSONArray("documents") ?: continue
                    for (i in 0 until docs.length()) {
                        val doc = docs.optJSONObject(i) ?: continue
                        val name = doc.optString("name", "")
                        val docId = name.substringAfterLast("/")
                        if (docId.isBlank() || deleted.contains(docId)) continue

                        val fields = doc.optJSONObject("fields") ?: continue
                        val mediaItem = parseMediaFromFirestoreFields(docId, col, fields)
                        if (!deleted.contains(mediaItem.id)) {
                            items.add(mediaItem)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        items
    }

    private fun parseMediaFromFirestoreFields(docId: String, col: String, fields: JSONObject): MediaItem {
        fun s(key: String): String {
            val v = fields.optJSONObject(key) ?: return ""
            return v.optString("stringValue", "")
        }
        fun b(key: String, def: Boolean = false): Boolean {
            val v = fields.optJSONObject(key) ?: return def
            return if (v.has("booleanValue")) v.optBoolean("booleanValue", def) else def
        }
        fun l(key: String): Long? {
            val v = fields.optJSONObject(key) ?: return null
            return if (v.has("integerValue")) v.optLong("integerValue") else null
        }

        val typeStr = s("type").uppercase()
        val mediaType = when {
            typeStr.contains("EVENT") || col == "sports" || col == "events" || col == "matches" -> MediaType.LIVE_EVENT
            typeStr.contains("MOVIE") || typeStr.contains("SERIES") || col == "movies" -> MediaType.MOVIE
            else -> MediaType.LIVE_TV
        }

        val title = s("title").ifBlank { s("name").ifBlank { docId } }
        val streamUrl = s("streamUrl").ifBlank { s("url") }
        val backupUrl = s("backupUrl").takeIf { it.isNotBlank() }
        val logoUrl = s("logoUrl").ifBlank { s("logo").ifBlank { s("poster") } }.takeIf { it.isNotBlank() }
        val category = s("category").ifBlank { s("sport").ifBlank { if (mediaType == MediaType.LIVE_EVENT) "Sports" else "General" } }
        val tournament = s("tournament").takeIf { it.isNotBlank() }
        val team1 = s("team1").takeIf { it.isNotBlank() }
        val team2 = s("team2").takeIf { it.isNotBlank() }
        val team1Logo = s("team1Logo").takeIf { it.isNotBlank() }
        val team2Logo = s("team2Logo").takeIf { it.isNotBlank() }
        val matchTime = s("matchTimeFormatted").ifBlank { s("eventTime") }.takeIf { it.isNotBlank() }
        val status = s("status").ifBlank { if (mediaType == MediaType.LIVE_EVENT) "LIVE" else "ON AIR" }
        val isLive = b("isLive", true)
        val description = s("description").takeIf { it.isNotBlank() }
        val countdown = l("countdownTargetSeconds")

        return MediaItem(
            id = s("id").ifBlank { docId },
            title = title,
            streamUrl = streamUrl,
            backupUrl = backupUrl,
            logoUrl = logoUrl,
            category = category,
            type = mediaType,
            tournament = tournament,
            team1 = team1,
            team2 = team2,
            team1Logo = team1Logo,
            team2Logo = team2Logo,
            matchTimeFormatted = matchTime,
            status = status,
            isLive = isLive,
            description = description,
            countdownTargetSeconds = countdown,
            drmScheme = s("drmScheme").ifBlank { s("license_type") }.takeIf { it.isNotBlank() },
            drmLicenseUrl = s("drmLicenseUrl").takeIf { it.isNotBlank() },
            drmLicenseKey = s("drmLicenseKey").ifBlank { s("license_key") }.ifBlank { s("clearkey") }.takeIf { it.isNotBlank() },
            manifestType = s("manifestType").ifBlank { s("manifest_type") }.takeIf { it.isNotBlank() }
        )
    }

    suspend fun fetchFromFirebase(url: String = getSavedFirebaseUrl()): List<MediaItem> = withContext(Dispatchers.IO) {
        val deleted = getDeletedIds()
        val items = mutableListOf<MediaItem>()

        // 1. Fetch from Firestore REST
        val firestoreItems = fetchFromFirestore()
        items.addAll(firestoreItems)

        // 2. Fetch from Firebase Realtime Database
        if (url.isNotBlank()) {
            try {
                val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
                val targetUrl = if (cleanUrl.endsWith(".json")) cleanUrl else "$cleanUrl/.json"

                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "NAFITV24-Android/2.5.0")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    if (body.isNotEmpty() && body != "null") {
                        if (body.startsWith("{")) {
                            val jsonObject = JSONObject(body)
                            // "playlists", "app_config", "app_updates", "settings" are handled separately and must not be parsed as TV channels
                            val subKeys = listOf("sports", "events", "matches", "channels", "movies", "custom")
                            var foundNested = false
                            for (sub in subKeys) {
                                if (jsonObject.has(sub)) {
                                    foundNested = true
                                    val subObj = jsonObject.optJSONObject(sub)
                                    if (subObj != null) {
                                        val keys = subObj.keys()
                                        while (keys.hasNext()) {
                                            val k = keys.next()
                                            if (!deleted.contains(k) && !k.startsWith("pl_")) {
                                                val itemObj = subObj.optJSONObject(k)
                                                if (itemObj != null && !itemObj.has("channelCount")) {
                                                    val item = parseMediaFromJsonObj(k, itemObj)
                                                    if (!deleted.contains(item.id) && !item.id.startsWith("pl_")) {
                                                        items.add(item)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (!foundNested) {
                                val keys = jsonObject.keys()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    // Skip system collections and playlists
                                    if (key == "playlists" || key == "app_config" || key == "app_updates" || key == "deleted_ids" || key == "settings" || key.startsWith("pl_")) {
                                        continue
                                    }
                                    if (!deleted.contains(key)) {
                                        val obj = jsonObject.optJSONObject(key)
                                        if (obj != null && !obj.has("channelCount")) {
                                            val item = parseMediaFromJsonObj(key, obj)
                                            if (!deleted.contains(item.id) && !item.id.startsWith("pl_")) {
                                                items.add(item)
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (body.startsWith("[")) {
                            val jsonArray = JSONArray(body)
                            for (i in 0 until jsonArray.length()) {
                                val obj = jsonArray.optJSONObject(i)
                                if (obj != null && !obj.has("channelCount")) {
                                    val id = obj.optString("id", "fb_$i")
                                    if (!deleted.contains(id) && !id.startsWith("pl_")) {
                                        val item = parseMediaFromJsonObj(id, obj)
                                        if (!deleted.contains(item.id) && !item.id.startsWith("pl_")) {
                                            items.add(item)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        items.distinctBy { it.id }.filterNot { deleted.contains(it.id) || it.id.startsWith("pl_") }
    }

    suspend fun pushToFirebase(
        item: MediaItem,
        url: String = getSavedFirebaseUrl()
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var anySuccess = false
        var lastError = ""

        val collections = when (item.type) {
            MediaType.LIVE_EVENT -> listOf("events", "sports", "matches")
            MediaType.LIVE_TV -> listOf("channels")
            MediaType.MOVIE, MediaType.SERIES -> listOf("movies")
        }

        // 1. Push to Firestore REST
        try {
            val firestoreObj = JSONObject()
            val fields = JSONObject()
            fun fs(key: String, value: String?) {
                if (!value.isNullOrBlank()) {
                    fields.put(key, JSONObject().put("stringValue", value))
                }
            }
            fun fb(key: String, value: Boolean) {
                fields.put(key, JSONObject().put("booleanValue", value))
            }
            fun fi(key: String, value: Long?) {
                if (value != null) {
                    fields.put(key, JSONObject().put("integerValue", value.toString()))
                }
            }

            fs("id", item.id)
            fs("title", item.title)
            fs("name", item.title)
            fs("streamUrl", item.streamUrl)
            fs("url", item.streamUrl)
            fs("backupUrl", item.backupUrl)
            fs("logoUrl", item.logoUrl)
            fs("logo", item.logoUrl)
            fs("category", item.category)
            fs("type", item.type.name)
            fs("tournament", item.tournament)
            fs("team1", item.team1)
            fs("team2", item.team2)
            fs("team1Logo", item.team1Logo)
            fs("team2Logo", item.team2Logo)
            fs("matchTimeFormatted", item.matchTimeFormatted)
            fs("status", item.status)
            fb("isLive", item.isLive)
            fs("description", item.description)
            fi("countdownTargetSeconds", item.countdownTargetSeconds)
            fs("drmScheme", item.drmScheme)
            fs("drmLicenseUrl", item.drmLicenseUrl)
            fs("drmLicenseKey", item.drmLicenseKey)
            fs("manifestType", item.manifestType)

            firestoreObj.put("fields", fields)
            val fsBody = firestoreObj.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val primaryCol = collections.first()
            val databases = listOf(FIRESTORE_DATABASE_ID, "(default)")
            for (dbId in databases) {
                try {
                    val fsUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/$dbId/documents/$primaryCol/${item.id}?key=$FIREBASE_API_KEY"
                    val fsReq = Request.Builder().url(fsUrl).patch(fsBody).build()
                    val fsResp = client.newCall(fsReq).execute()
                    if (fsResp.isSuccessful) {
                        anySuccess = true
                    }
                } catch (e: Exception) {
                    // continue
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Push to Realtime Database
        if (url.isNotBlank()) {
            try {
                val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
                val jsonObject = serializeMediaToJsonObj(item)
                val body = jsonObject.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

                for (col in collections) {
                    val targetUrl = "$cleanUrl/$col/${item.id}.json"
                    val request = Request.Builder().url(targetUrl).put(body).build()
                    val response = client.newCall(request).execute()
                    if (response.isSuccessful) {
                        anySuccess = true
                    } else {
                        lastError = "HTTP ${response.code}: ${response.message}"
                    }
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "RTDB নেটওয়ার্ক এরর"
            }
        }

        if (anySuccess) {
            Pair(true, "Firebase ক্লাউডে সফলভাবে সেভ হয়েছে")
        } else {
            Pair(false, lastError.ifBlank { "Firebase ক্লাউড আপলোড ব্যর্থ হয়েছে" })
        }
    }

    suspend fun deleteFromFirebase(
        id: String,
        type: MediaType,
        url: String = getSavedFirebaseUrl()
    ): Boolean = withContext(Dispatchers.IO) {
        var anySuccess = false

        // 1. Delete from Firestore REST
        val collections = listOf("events", "sports", "matches", "channels", "movies", "playlists", "custom")
        val databases = listOf(FIRESTORE_DATABASE_ID, "(default)")
        for (dbId in databases) {
            for (col in collections) {
                try {
                    val fsUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/$dbId/documents/$col/$id?key=$FIREBASE_API_KEY"
                    val req = Request.Builder().url(fsUrl).delete().build()
                    val resp = client.newCall(req).execute()
                    if (resp.isSuccessful) anySuccess = true
                } catch (e: Exception) {
                    // Ignore single path error
                }
            }
        }

        // 2. Delete from Realtime Database
        if (url.isNotBlank()) {
            try {
                val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
                for (col in collections) {
                    try {
                        val targetUrl = "$cleanUrl/$col/$id.json"
                        val request = Request.Builder().url(targetUrl).delete().build()
                        val response = client.newCall(request).execute()
                        if (response.isSuccessful) {
                            anySuccess = true
                        }
                    } catch (e: Exception) {
                        // Ignore single path error
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        anySuccess
    }

    fun serializeMediaToJsonObj(item: MediaItem): JSONObject {
        val obj = JSONObject()
        obj.put("id", item.id)
        obj.put("name", item.title)
        obj.put("title", item.title)
        obj.put("tournament", item.tournament ?: "")
        obj.put("sport", item.category)
        obj.put("category", item.category)
        obj.put("type", item.type.name)
        obj.put("url", item.streamUrl)
        obj.put("streamUrl", item.streamUrl)
        obj.put("backupUrl", item.backupUrl ?: "")
        obj.put("logo", item.logoUrl ?: "")
        obj.put("logoUrl", item.logoUrl ?: "")
        obj.put("poster", item.logoUrl ?: "")
        obj.put("description", item.description ?: "")
        obj.put("isLive", item.isLive)
        obj.put("status", item.status)
        obj.put("eventTime", item.eventTime ?: "")
        obj.put("team1", item.team1 ?: "")
        obj.put("team2", item.team2 ?: "")
        obj.put("team1Logo", item.team1Logo ?: "")
        obj.put("team2Logo", item.team2Logo ?: "")
        obj.put("matchTimeFormatted", item.matchTimeFormatted ?: "")
        if (item.countdownTargetSeconds != null) {
            obj.put("countdownTargetSeconds", item.countdownTargetSeconds)
            obj.put("startTime", item.countdownTargetSeconds)
        }
        obj.put("score1", item.score1 ?: "")
        obj.put("score2", item.score2 ?: "")
        obj.put("quality", item.quality)
        obj.put("rating", item.rating ?: "")
        obj.put("year", item.year ?: "")
        obj.put("country", item.country ?: "")
        obj.put("drmScheme", item.drmScheme ?: "")
        obj.put("drmLicenseUrl", item.drmLicenseUrl ?: "")
        obj.put("drmLicenseKey", item.drmLicenseKey ?: "")
        obj.put("manifestType", item.manifestType ?: "")

        // Multiple servers array
        val serversArr = JSONArray()
        item.getAllServers().forEach { server ->
            val sObj = JSONObject()
            sObj.put("name", server.name)
            sObj.put("url", server.url)
            serversArr.put(sObj)
        }
        obj.put("servers", serversArr)
        return obj
    }

    fun parseMediaFromJsonObj(id: String, obj: JSONObject): MediaItem {
        val typeStr = obj.optString("type", "")
        val categoryStr = obj.optString("category", obj.optString("sport", "General"))
        val mediaType = when {
            typeStr.equals("LIVE_EVENT", ignoreCase = true) || categoryStr.contains("Cricket", ignoreCase = true) || categoryStr.contains("Football", ignoreCase = true) || categoryStr.contains("Sport", ignoreCase = true) -> MediaType.LIVE_EVENT
            typeStr.equals("MOVIE", ignoreCase = true) || typeStr.equals("SERIES", ignoreCase = true) || obj.has("poster") || obj.has("year") -> MediaType.MOVIE
            else -> MediaType.LIVE_TV
        }

        val serversList = mutableListOf<StreamServer>()
        val serversArr = obj.optJSONArray("servers")
        if (serversArr != null) {
            for (i in 0 until serversArr.length()) {
                val sObj = serversArr.optJSONObject(i)
                if (sObj != null) {
                    val name = sObj.optString("name", "সার্ভার ${i + 1}")
                    val sUrl = sObj.optString("url", "")
                    if (sUrl.isNotBlank()) {
                        serversList.add(StreamServer(name, sUrl))
                    }
                }
            }
        }

        val primaryStream = obj.optString("url", obj.optString("streamUrl", ""))
        val backup = obj.optString("backupUrl", null)
        val logo = obj.optString("logo", obj.optString("logoUrl", obj.optString("poster", null))).takeIf { it?.isNotBlank() == true }

        val startTm = if (obj.has("startTime")) obj.optLong("startTime") else if (obj.has("countdownTargetSeconds")) obj.optLong("countdownTargetSeconds") else null

        return MediaItem(
            id = id,
            title = obj.optString("name", obj.optString("title", "NAFI Stream")),
            tournament = obj.optString("tournament", null).takeIf { it?.isNotBlank() == true },
            category = categoryStr,
            type = mediaType,
            streamUrl = primaryStream,
            backupUrl = backup,
            servers = serversList,
            logoUrl = logo,
            description = obj.optString("description", null).takeIf { it?.isNotBlank() == true },
            isLive = obj.optBoolean("isLive", true),
            status = obj.optString("status", "Live Now"),
            eventTime = obj.optString("eventTime", obj.optString("time", null)).takeIf { it?.isNotBlank() == true },
            team1 = obj.optString("team1", null).takeIf { it?.isNotBlank() == true },
            team2 = obj.optString("team2", null).takeIf { it?.isNotBlank() == true },
            team1Logo = obj.optString("team1Logo", null).takeIf { it?.isNotBlank() == true },
            team2Logo = obj.optString("team2Logo", null).takeIf { it?.isNotBlank() == true },
            matchTimeFormatted = obj.optString("matchTimeFormatted", null).takeIf { it?.isNotBlank() == true },
            countdownTargetSeconds = startTm,
            score1 = obj.optString("score1", null).takeIf { it?.isNotBlank() == true },
            score2 = obj.optString("score2", null).takeIf { it?.isNotBlank() == true },
            quality = obj.optString("quality", "HD"),
            rating = obj.optString("rating", null).takeIf { it?.isNotBlank() == true },
            year = obj.optString("year", null).takeIf { it?.isNotBlank() == true },
            country = obj.optString("country", null).takeIf { it?.isNotBlank() == true },
            drmScheme = obj.optString("drmScheme", obj.optString("license_type", null)).takeIf { it?.isNotBlank() == true },
            drmLicenseUrl = obj.optString("drmLicenseUrl", null).takeIf { it?.isNotBlank() == true },
            drmLicenseKey = obj.optString("drmLicenseKey", obj.optString("license_key", obj.optString("clearkey", null))).takeIf { it?.isNotBlank() == true },
            manifestType = obj.optString("manifestType", obj.optString("manifest_type", null)).takeIf { it?.isNotBlank() == true }
        )
    }

    // -------------------------------------------------------------
    // PLAYLISTS MANAGEMENT (Initial, Local & Firebase Cloud)
    // -------------------------------------------------------------
    fun getInitialPlaylists(): List<PlaylistInfo> {
        return emptyList()
    }

    fun getCustomPlaylists(): List<PlaylistInfo> {
        val deleted = getDeletedIds()
        val jsonStr = prefs.getString("custom_playlists", "[]") ?: "[]"
        val list = mutableListOf<PlaylistInfo>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val id = obj.optString("id", "pl_custom_$i")
                if (!deleted.contains(id)) {
                    list.add(
                        PlaylistInfo(
                            id = id,
                            title = obj.optString("title", obj.optString("name", "Playlist")),
                            url = obj.optString("url", ""),
                            logoUrl = obj.optString("logoUrl", obj.optString("logo", null)).takeIf { it?.isNotBlank() == true },
                            description = obj.optString("description", null).takeIf { it?.isNotBlank() == true },
                            channelCount = obj.optInt("channelCount", 0)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveCustomPlaylist(playlist: PlaylistInfo) {
        val current = getCustomPlaylists().toMutableList()
        current.removeAll { it.id == playlist.id }
        current.add(0, playlist)
        saveCustomPlaylistsList(current)
    }

    fun saveCustomPlaylistsList(list: List<PlaylistInfo>) {
        val arr = JSONArray()
        list.forEach { p ->
            val obj = JSONObject()
            obj.put("id", p.id)
            obj.put("title", p.title)
            obj.put("name", p.title)
            obj.put("url", p.url)
            obj.put("logoUrl", p.logoUrl ?: "")
            obj.put("logo", p.logoUrl ?: "")
            obj.put("description", p.description ?: "")
            obj.put("channelCount", p.channelCount)
            arr.put(obj)
        }
        prefs.edit().putString("custom_playlists", arr.toString()).apply()
    }

    suspend fun deletePlaylist(id: String): Boolean {
        addDeletedId(id)
        val current = getCustomPlaylists().filterNot { it.id == id }
        saveCustomPlaylistsList(current)
        return deleteFromFirebase(id, MediaType.LIVE_TV)
    }

    suspend fun pushPlaylistToFirebase(playlist: PlaylistInfo, url: String = getSavedFirebaseUrl()): Boolean = withContext(Dispatchers.IO) {
        var success = false

        // 1. Push to Firestore
        try {
            val firestoreObj = JSONObject()
            val fields = JSONObject()
            fields.put("id", JSONObject().put("stringValue", playlist.id))
            fields.put("title", JSONObject().put("stringValue", playlist.title))
            fields.put("name", JSONObject().put("stringValue", playlist.title))
            fields.put("url", JSONObject().put("stringValue", playlist.url))
            if (!playlist.logoUrl.isNullOrBlank()) {
                fields.put("logoUrl", JSONObject().put("stringValue", playlist.logoUrl))
                fields.put("logo", JSONObject().put("stringValue", playlist.logoUrl))
            }
            if (!playlist.description.isNullOrBlank()) {
                fields.put("description", JSONObject().put("stringValue", playlist.description))
            }
            fields.put("channelCount", JSONObject().put("integerValue", playlist.channelCount.toString()))

            firestoreObj.put("fields", fields)
            val fsBody = firestoreObj.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val databases = listOf(FIRESTORE_DATABASE_ID, "(default)")
            for (dbId in databases) {
                try {
                    val fsUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/$dbId/documents/playlists/${playlist.id}?key=$FIREBASE_API_KEY"
                    val fsReq = Request.Builder().url(fsUrl).patch(fsBody).build()
                    val fsResp = client.newCall(fsReq).execute()
                    if (fsResp.isSuccessful) success = true
                } catch (e: Exception) {
                    // continue
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Push to RTDB
        if (url.isNotBlank()) {
            try {
                val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
                val obj = JSONObject()
                obj.put("id", playlist.id)
                obj.put("title", playlist.title)
                obj.put("name", playlist.title)
                obj.put("url", playlist.url)
                obj.put("logoUrl", playlist.logoUrl ?: "")
                obj.put("logo", playlist.logoUrl ?: "")
                obj.put("description", playlist.description ?: "")
                obj.put("channelCount", playlist.channelCount)

                val body = obj.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val targetUrl = "$cleanUrl/playlists/${playlist.id}.json"
                val req = Request.Builder().url(targetUrl).put(body).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) success = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        success
    }

    suspend fun fetchPlaylistsFromFirebase(url: String = getSavedFirebaseUrl()): List<PlaylistInfo> = withContext(Dispatchers.IO) {
        val deleted = getDeletedIds()
        val list = mutableListOf<PlaylistInfo>()

        // 1. Fetch from Firestore
        val databases = listOf(FIRESTORE_DATABASE_ID, "(default)")
        for (dbId in databases) {
            try {
                val fsUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/$dbId/documents/playlists?key=$FIREBASE_API_KEY"
                val req = Request.Builder().url(fsUrl).header("User-Agent", "NAFITV24-Android/2.5.0").build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) continue
                val body = resp.body?.string() ?: continue
                if (body.isBlank() || !body.startsWith("{")) continue

                val json = JSONObject(body)
                val docs = json.optJSONArray("documents") ?: continue
                for (i in 0 until docs.length()) {
                    val doc = docs.optJSONObject(i) ?: continue
                    val name = doc.optString("name", "")
                    val docId = name.substringAfterLast("/")
                    if (docId.isBlank() || deleted.contains(docId)) continue

                    val fields = doc.optJSONObject("fields") ?: continue
                    fun s(k: String): String = fields.optJSONObject(k)?.optString("stringValue", "") ?: ""
                    fun count(k: String): Int = fields.optJSONObject(k)?.optInt("integerValue", 0) ?: 0

                    val pId = s("id").ifBlank { docId }
                    if (!deleted.contains(pId)) {
                        list.add(
                            PlaylistInfo(
                                id = pId,
                                title = s("title").ifBlank { s("name").ifBlank { "Playlist" } },
                                url = s("url"),
                                logoUrl = s("logoUrl").ifBlank { s("logo") }.takeIf { it.isNotBlank() },
                                description = s("description").takeIf { it.isNotBlank() },
                                channelCount = count("channelCount")
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Fetch from RTDB
        if (url.isNotBlank()) {
            try {
                val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
                val targetUrl = "$cleanUrl/playlists.json"
                val req = Request.Builder().url(targetUrl).header("User-Agent", "NAFITV24-Android/2.5.0").build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body.isNotEmpty() && body != "null" && body.startsWith("{")) {
                        val jsonObject = JSONObject(body)
                        val keys = jsonObject.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (!deleted.contains(k)) {
                                val obj = jsonObject.optJSONObject(k)
                                if (obj != null) {
                                    val id = obj.optString("id", k)
                                    if (!deleted.contains(id)) {
                                        list.add(
                                            PlaylistInfo(
                                                id = id,
                                                title = obj.optString("title", obj.optString("name", "Playlist")),
                                                url = obj.optString("url", ""),
                                                logoUrl = obj.optString("logoUrl", obj.optString("logo", null)).takeIf { it?.isNotBlank() == true },
                                                description = obj.optString("description", null).takeIf { it?.isNotBlank() == true },
                                                channelCount = obj.optInt("channelCount", 0)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        list.distinctBy { it.id }.filterNot { deleted.contains(it.id) }
    }

    fun saveFirebaseUrl(url: String) {
        prefs.edit().putString("saved_firebase_url", url).apply()
    }

    fun getSavedFirebaseUrl(): String {
        val stored = prefs.getString("saved_firebase_url", null)
        if (stored.isNullOrBlank() || stored.contains("elaborate-airfoil") || stored.contains("nafitv24-default-rtdb")) {
            return DEFAULT_RTDB_URL
        }
        return stored
    }

    fun saveM3uUrl(url: String) {
        saveLiveTvM3uUrl(url)
    }

    fun getSavedM3uUrl(): String {
        return getSavedLiveTvM3uUrl()
    }

    fun saveLiveTvM3uUrl(url: String) {
        prefs.edit().putString("saved_live_tv_m3u_url", url).apply()
    }

    fun getSavedLiveTvM3uUrl(): String {
        return prefs.getString("saved_live_tv_m3u_url", DEFAULT_LIVE_TV_M3U_URL) ?: DEFAULT_LIVE_TV_M3U_URL
    }

    fun saveSportsM3uUrl(url: String) {
        prefs.edit().putString("saved_sports_m3u_url", url).apply()
    }

    fun getSavedSportsM3uUrl(): String {
        return prefs.getString("saved_sports_m3u_url", DEFAULT_SPORTS_M3U_URL) ?: DEFAULT_SPORTS_M3U_URL
    }

    fun saveMoviesM3uUrl(url: String) {
        prefs.edit().putString("saved_movies_m3u_url", url).apply()
    }

    fun getSavedMoviesM3uUrl(): String {
        return prefs.getString("saved_movies_m3u_url", DEFAULT_MOVIES_M3U_URL) ?: DEFAULT_MOVIES_M3U_URL
    }

    // Push remote configuration (Live TV M3U, Sports M3U, Movies M3U) to Firebase RTDB and Firestore
    suspend fun pushAppConfigToFirebase(
        liveTvM3u: String = getSavedLiveTvM3uUrl(),
        sportsM3u: String = getSavedSportsM3uUrl(),
        moviesM3u: String = getSavedMoviesM3uUrl(),
        url: String = getSavedFirebaseUrl()
    ): Boolean = withContext(Dispatchers.IO) {
        var success = false
        // 1. Push to RTDB
        if (url.isNotBlank()) {
            try {
                val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
                val obj = JSONObject()
                obj.put("liveTvM3uUrl", liveTvM3u)
                obj.put("sportsM3uUrl", sportsM3u)
                obj.put("moviesM3uUrl", moviesM3u)
                val body = obj.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
                val targetUrl = "$cleanUrl/app_config.json"
                val req = Request.Builder().url(targetUrl).put(body).build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) success = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        // 2. Push to Firestore
        try {
            val firestoreObj = JSONObject()
            val fields = JSONObject()
            fields.put("liveTvM3uUrl", JSONObject().put("stringValue", liveTvM3u))
            fields.put("sportsM3uUrl", JSONObject().put("stringValue", sportsM3u))
            fields.put("moviesM3uUrl", JSONObject().put("stringValue", moviesM3u))
            firestoreObj.put("fields", fields)
            val fsBody = firestoreObj.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val databases = listOf(FIRESTORE_DATABASE_ID, "(default)")
            for (dbId in databases) {
                val fsUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/$dbId/documents/settings/app_config?key=$FIREBASE_API_KEY"
                val fsReq = Request.Builder().url(fsUrl).patch(fsBody).build()
                val fsResp = client.newCall(fsReq).execute()
                if (fsResp.isSuccessful) success = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        success
    }

    // Fetch remote configuration (Live TV M3U, Sports M3U, Movies M3U) from Firebase RTDB and Firestore
    suspend fun fetchAppConfigFromFirebase(url: String = getSavedFirebaseUrl()): Triple<String, String, String>? = withContext(Dispatchers.IO) {
        var remoteLiveTv: String? = null
        var remoteSports: String? = null
        var remoteMovies: String? = null

        // 1. Fetch from Firestore
        val databases = listOf(FIRESTORE_DATABASE_ID, "(default)")
        for (dbId in databases) {
            try {
                val fsUrl = "https://firestore.googleapis.com/v1/projects/$FIREBASE_PROJECT_ID/databases/$dbId/documents/settings/app_config?key=$FIREBASE_API_KEY"
                val req = Request.Builder().url(fsUrl).header("User-Agent", "NAFITV24-Android/2.5.0").build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank() && body.startsWith("{")) {
                        val json = JSONObject(body)
                        val fields = json.optJSONObject("fields")
                        if (fields != null) {
                            remoteLiveTv = fields.optJSONObject("liveTvM3uUrl")?.optString("stringValue")
                            remoteSports = fields.optJSONObject("sportsM3uUrl")?.optString("stringValue")
                            remoteMovies = fields.optJSONObject("moviesM3uUrl")?.optString("stringValue")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Fetch from RTDB (takes priority if present)
        if (url.isNotBlank()) {
            try {
                val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
                val targetUrl = "$cleanUrl/app_config.json"
                val req = Request.Builder().url(targetUrl).header("User-Agent", "NAFITV24-Android/2.5.0").build()
                val resp = client.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: ""
                    if (body.isNotBlank() && body != "null" && body.startsWith("{")) {
                        val obj = JSONObject(body)
                        if (obj.has("liveTvM3uUrl")) remoteLiveTv = obj.optString("liveTvM3uUrl")
                        if (obj.has("sportsM3uUrl")) remoteSports = obj.optString("sportsM3uUrl")
                        if (obj.has("moviesM3uUrl")) remoteMovies = obj.optString("moviesM3uUrl")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (remoteLiveTv != null || remoteSports != null || remoteMovies != null) {
            val finalLiveTv = if (!remoteLiveTv.isNullOrBlank()) remoteLiveTv!! else getSavedLiveTvM3uUrl()
            val finalSports = if (!remoteSports.isNullOrBlank()) remoteSports!! else getSavedSportsM3uUrl()
            val finalMovies = if (!remoteMovies.isNullOrBlank()) remoteMovies!! else getSavedMoviesM3uUrl()
            // Cache locally so offline access uses the latest remote config
            if (remoteLiveTv?.isNotBlank() == true) saveLiveTvM3uUrl(finalLiveTv)
            if (remoteSports?.isNotBlank() == true) saveSportsM3uUrl(finalSports)
            if (remoteMovies?.isNotBlank() == true) saveMoviesM3uUrl(finalMovies)
            Triple(finalLiveTv, finalSports, finalMovies)
        } else {
            null
        }
    }

    // -------------------------------------------------------------
    // APP UPDATE & VERSION MANAGEMENT (Firebase RTDB + Local Cache)
    // -------------------------------------------------------------
    suspend fun fetchAppUpdateInfo(url: String = getSavedFirebaseUrl()): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
            val targetUrl = "$cleanUrl/app_update.json"
            val req = Request.Builder().url(targetUrl).header("User-Agent", "NAFITV24-Android/2.4.0").build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return@withContext getCachedAppUpdateInfo()
            val body = resp.body?.string() ?: return@withContext getCachedAppUpdateInfo()
            if (body.isBlank() || body == "null") return@withContext getCachedAppUpdateInfo()

            val obj = JSONObject(body)
            val info = AppUpdateInfo(
                versionCode = obj.optInt("versionCode", 1),
                versionName = obj.optString("versionName", "1.0"),
                downloadUrl = obj.optString("downloadUrl", ""),
                releaseNotes = obj.optString("releaseNotes", ""),
                isForceUpdate = obj.optBoolean("isForceUpdate", false),
                minSupportedVersionCode = obj.optInt("minSupportedVersionCode", 1),
                apkSize = obj.optString("apkSize", ""),
                releaseDate = obj.optString("releaseDate", "")
            )
            saveCachedAppUpdateInfo(info)
            info
        } catch (e: Exception) {
            e.printStackTrace()
            getCachedAppUpdateInfo()
        }
    }

    suspend fun pushAppUpdateInfo(info: AppUpdateInfo, url: String = getSavedFirebaseUrl()): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = if (url.endsWith("/")) url.removeSuffix("/") else url
            val obj = JSONObject()
            obj.put("versionCode", info.versionCode)
            obj.put("versionName", info.versionName)
            obj.put("downloadUrl", info.downloadUrl)
            obj.put("releaseNotes", info.releaseNotes)
            obj.put("isForceUpdate", info.isForceUpdate)
            obj.put("minSupportedVersionCode", info.minSupportedVersionCode)
            obj.put("apkSize", info.apkSize)
            obj.put("releaseDate", info.releaseDate)

            val body = obj.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val targetUrl = "$cleanUrl/app_update.json"
            val req = Request.Builder().url(targetUrl).put(body).build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                saveCachedAppUpdateInfo(info)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun saveCachedAppUpdateInfo(info: AppUpdateInfo) {
        val obj = JSONObject()
        obj.put("versionCode", info.versionCode)
        obj.put("versionName", info.versionName)
        obj.put("downloadUrl", info.downloadUrl)
        obj.put("releaseNotes", info.releaseNotes)
        obj.put("isForceUpdate", info.isForceUpdate)
        obj.put("minSupportedVersionCode", info.minSupportedVersionCode)
        obj.put("apkSize", info.apkSize)
        obj.put("releaseDate", info.releaseDate)
        prefs.edit().putString("cached_app_update", obj.toString()).apply()
    }

    fun getCachedAppUpdateInfo(): AppUpdateInfo? {
        val json = prefs.getString("cached_app_update", null) ?: return null
        return try {
            val obj = JSONObject(json)
            AppUpdateInfo(
                versionCode = obj.optInt("versionCode", 1),
                versionName = obj.optString("versionName", "1.0"),
                downloadUrl = obj.optString("downloadUrl", ""),
                releaseNotes = obj.optString("releaseNotes", ""),
                isForceUpdate = obj.optBoolean("isForceUpdate", false),
                minSupportedVersionCode = obj.optInt("minSupportedVersionCode", 1),
                apkSize = obj.optString("apkSize", ""),
                releaseDate = obj.optString("releaseDate", "")
            )
        } catch (e: Exception) {
            null
        }
    }

    fun isUpdateDismissed(versionCode: Int): Boolean {
        return prefs.getInt("dismissed_update_version", 0) == versionCode
    }

    fun dismissUpdate(versionCode: Int) {
        prefs.edit().putInt("dismissed_update_version", versionCode).apply()
    }
}
