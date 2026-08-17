package com.example.util

import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

@OptIn(UnstableApi::class)
object DrmHelper {

    data class DrmConfig(
        val schemeUuid: UUID,
        val licenseUrl: String? = null,
        val localKeyBytes: ByteArray? = null,
        val headers: Map<String, String> = emptyMap(),
        val manifestType: String? = null
    )

    /**
     * Resolves DRM configuration from MediaItem metadata or pipe-delimited parameters in URL
     */
    fun extractDrmConfig(
        rawUrl: String,
        itemScheme: String? = null,
        itemLicenseUrl: String? = null,
        itemLicenseKey: String? = null,
        itemHeaders: Map<String, String>? = null,
        itemManifestType: String? = null
    ): Pair<String, DrmConfig?> {
        var cleanUrl = rawUrl.trim()
        var schemeStr = itemScheme
        var licenseKeyOrUrl = itemLicenseUrl ?: itemLicenseKey
        var manifestType = itemManifestType
        val drmHeaders = mutableMapOf<String, String>()
        itemHeaders?.let { drmHeaders.putAll(it) }

        // Extract from pipe syntax: url|license_type=clearkey&license_key=...
        if (cleanUrl.contains("|")) {
            val parts = cleanUrl.split("|", limit = 2)
            cleanUrl = parts[0].trim()
            val pairs = parts[1].split("&")
            for (pair in pairs) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    val k = kv[0].trim().lowercase()
                    val v = try {
                        java.net.URLDecoder.decode(kv[1].trim(), "UTF-8")
                    } catch (_: Exception) {
                        kv[1].trim()
                    }
                    when {
                        k == "license_type" || k == "drm_type" || k == "drmscheme" || k == "license_type=clearkey" || k.contains("clearkey") -> {
                            if (schemeStr.isNullOrBlank()) {
                                schemeStr = if (v.isNotBlank()) v else "clearkey"
                            }
                        }
                        k == "license_key" || k == "drm_key" || k == "key" || k == "clearkey" || k == "drmlicense" -> {
                            if (licenseKeyOrUrl.isNullOrBlank()) {
                                licenseKeyOrUrl = v
                            }
                            if (schemeStr.isNullOrBlank()) {
                                schemeStr = "clearkey"
                            }
                        }
                        k == "manifest_type" || k == "type" -> {
                            if (manifestType.isNullOrBlank()) {
                                manifestType = v
                            }
                        }
                        k.startsWith("drm_header_") -> {
                            val headerName = k.removePrefix("drm_header_")
                            drmHeaders[headerName] = v
                        }
                    }
                }
            }
        }

        // Auto-detect if rawUrl or license key specifies ClearKey or Widevine
        if (schemeStr.isNullOrBlank() && !licenseKeyOrUrl.isNullOrBlank()) {
            schemeStr = if (licenseKeyOrUrl.startsWith("http://", ignoreCase = true) || licenseKeyOrUrl.startsWith("https://", ignoreCase = true)) {
                if (licenseKeyOrUrl.contains("widevine", ignoreCase = true)) "widevine" else "clearkey"
            } else {
                "clearkey"
            }
        }

        if (schemeStr.isNullOrBlank() && licenseKeyOrUrl.isNullOrBlank()) {
            return Pair(cleanUrl, null)
        }

        val uuid = when {
            schemeStr?.contains("widevine", ignoreCase = true) == true -> C.WIDEVINE_UUID
            schemeStr?.contains("playready", ignoreCase = true) == true -> C.PLAYREADY_UUID
            else -> C.CLEARKEY_UUID
        }

        // Process ClearKey or Widevine
        val finalLicense = licenseKeyOrUrl?.trim() ?: ""
        if (finalLicense.isBlank()) {
            return Pair(cleanUrl, DrmConfig(uuid, manifestType = manifestType))
        }

        // If it is an HTTP/HTTPS license server URL
        if (finalLicense.startsWith("http://", ignoreCase = true) || finalLicense.startsWith("https://", ignoreCase = true)) {
            return Pair(
                cleanUrl,
                DrmConfig(
                    schemeUuid = uuid,
                    licenseUrl = finalLicense,
                    headers = drmHeaders,
                    manifestType = manifestType
                )
            )
        }

        // Otherwise, it's local ClearKey key pairs (hex keyId:key or JWK JSON)
        val jwkBytes = buildClearKeyJwkBytes(finalLicense)
        return Pair(
            cleanUrl,
            DrmConfig(
                schemeUuid = uuid,
                localKeyBytes = jwkBytes,
                headers = drmHeaders,
                manifestType = manifestType
            )
        )
    }

    /**
     * Converts KeyId:Key pairs (hex, base64, or JSON) into valid W3C ClearKey JWK JSON response
     */
    fun buildClearKeyJwkBytes(keyInput: String): ByteArray? {
        var cleanInput = keyInput.trim()
        if (cleanInput.isBlank()) return null

        // Strip any pipe headers: "key:key|User-Agent=..."
        if (cleanInput.contains("|")) {
            cleanInput = cleanInput.substringBefore("|").trim()
        }

        // 1. If it is already a JWK JSON set: e.g. {"keys":[{"kty":"oct",...}]}
        if (cleanInput.startsWith("{") && cleanInput.contains("\"keys\"")) {
            return cleanInput.toByteArray(Charsets.UTF_8)
        }

        // 2. If it is a key-value JSON dictionary: e.g. {"key_id_hex": "key_hex"}
        if (cleanInput.startsWith("{")) {
            try {
                val json = JSONObject(cleanInput)
                val keysArray = JSONArray()
                val iter = json.keys()
                while (iter.hasNext()) {
                    val kid = iter.next()
                    val key = json.getString(kid)
                    val keyObj = formatJwkKeyObject(kid, key)
                    if (keyObj != null) {
                        keysArray.put(keyObj)
                    }
                }
                if (keysArray.length() > 0) {
                    val jwk = JSONObject()
                    jwk.put("keys", keysArray)
                    jwk.put("type", "temporary")
                    return jwk.toString().toByteArray(Charsets.UTF_8)
                }
            } catch (_: Exception) {}
        }

        // 3. Delimited format: handles single or multiple keys in any of the following formats:
        //    "kid:key"
        //    "kid1:key1,kid2:key2"
        //    "kid1:key1 kid2:key2"
        //    "kid1:key1:kid2:key2"
        //    "kid1\nkey1\nkid2\nkey2"
        try {
            val keysArray = JSONArray()
            val tokens = cleanInput.split(Regex("[,;\\s\\n]+")).filter { it.isNotBlank() }

            val rawParts = mutableListOf<String>()
            for (token in tokens) {
                if (token.contains(":")) {
                    val subParts = token.split(":").filter { it.isNotBlank() }
                    rawParts.addAll(subParts)
                } else {
                    rawParts.add(token)
                }
            }

            // Pair up elements (0,1), (2,3), (4,5) ...
            var i = 0
            while (i < rawParts.size - 1) {
                val kidRaw = rawParts[i].trim()
                val keyRaw = rawParts[i + 1].trim()
                val keyObj = formatJwkKeyObject(kidRaw, keyRaw)
                if (keyObj != null) {
                    keysArray.put(keyObj)
                }
                i += 2
            }

            if (keysArray.length() > 0) {
                val jwk = JSONObject()
                jwk.put("keys", keysArray)
                jwk.put("type", "temporary")
                return jwk.toString().toByteArray(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return null
    }

    private fun formatJwkKeyObject(kidRaw: String, keyRaw: String): JSONObject? {
        val kidB64 = toBase64UrlSafe(kidRaw)
        val keyB64 = toBase64UrlSafe(keyRaw)
        if (kidB64.isBlank() || keyB64.isBlank()) return null

        val obj = JSONObject()
        obj.put("kty", "oct")
        obj.put("k", keyB64)
        obj.put("kid", kidB64)
        return obj
    }

    private fun toBase64UrlSafe(raw: String): String {
        val clean = raw.trim().replace("\"", "").replace("'", "")
        if (clean.isBlank()) return ""

        // If it's a 32-character or standard hex string (with or without dashes)
        val hexClean = clean.replace("-", "").replace("0x", "").replace(":", "")
        if (hexClean.length >= 16 && hexClean.length % 2 == 0 && hexClean.matches(Regex("^[0-9a-fA-F]+$"))) {
            val bytes = hexStringToByteArray(hexClean)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()
        }

        // If it's standard Base64, convert to URL-safe Base64 without padding
        return try {
            val decoded = Base64.decode(clean, Base64.DEFAULT)
            Base64.encodeToString(decoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP).trim()
        } catch (_: Exception) {
            clean.replace("+", "-").replace("/", "_").trimEnd('=').trim()
        }
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Builds a DrmSessionManager for ExoPlayer
     */
    fun createDrmSessionManager(
        config: DrmConfig,
        httpDataSourceFactory: DataSource.Factory
    ): DrmSessionManager? {
        return try {
            if (config.localKeyBytes != null) {
                // ClearKey local key callback with JWK response
                val callback = LocalMediaDrmCallback(config.localKeyBytes)
                DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(config.schemeUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true)
                    .setPlayClearSamplesWithoutKeys(true)
                    .build(callback)
            } else if (!config.licenseUrl.isNullOrBlank()) {
                // HTTP License callback for online DRM servers
                val callback = HttpMediaDrmCallback(config.licenseUrl, httpDataSourceFactory)
                config.headers.forEach { (k, v) ->
                    callback.setKeyRequestProperty(k, v)
                }
                DefaultDrmSessionManager.Builder()
                    .setUuidAndExoMediaDrmProvider(config.schemeUuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
                    .setMultiSession(true)
                    .setPlayClearSamplesWithoutKeys(true)
                    .build(callback)
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
