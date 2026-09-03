package com.sjpickard.pcloudmusic.cloud

import android.util.Log
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "PCloudApiClient"

/** Any non-2xx HTTP response (including pCloud's 429 rate-limit responses)
 * already falls into this and gets retried by withRetry below - no special
 * casing needed to "widen retry to cover 429", it's covered by construction. */
private suspend fun <T> withRetry(what: String, attempts: Int = 3, block: suspend () -> T): T {
    var lastError: Exception? = null
    repeat(attempts) { attempt ->
        try {
            return block()
        } catch (e: IOException) {
            lastError = e
            Log.w(TAG, "$what failed (attempt ${attempt + 1}/$attempts): ${e.javaClass.name}: ${e.message}")
            if (attempt < attempts - 1) delay(500L * (attempt + 1))
        }
    }
    throw lastError!!
}

/** One entry from a pCloud /listfolder response. [fileId] is set only for
 * files (pCloud's "fileid") - used later to fetch a download link. There's
 * no equivalent pre-authenticated download URL on the listing itself (unlike
 * OneDrive's @microsoft.graph.downloadUrl), so getDownloadUrl is a separate
 * round-trip per file - see [PCloudApiClient.getDownloadUrl]. */
data class PCloudItem(
    val name: String,
    val isFolder: Boolean,
    val fileId: Long?,
    val size: Long,
)

/**
 * Thin wrapper over the plain pCloud REST endpoints this app needs -
 * mirrors OneDriveApiClient's shape (listChildren/fetchPrefix/getDownloadUrl/
 * downloadToFile) against https://docs.pcloud.com/methods/ instead of the
 * Graph API. All calls go to the account's own data-center host
 * (PCloudAuthManager.apiBaseUrl - "api.pcloud.com" US or "eapi.pcloud.com"
 * Europe, fixed at sign-in time).
 */
class PCloudApiClient(
    private val authManager: PCloudAuthManager,
    private val client: OkHttpClient = OkHttpClient(),
) {

    suspend fun listChildren(path: String): List<PCloudItem> = withContext(Dispatchers.IO) {
        val base = authManager.apiBaseUrl() ?: throw IllegalStateException("not signed in to pCloud")
        val token = authManager.accessToken() ?: throw IllegalStateException("not signed in to pCloud")
        withRetry("listChildren('$path')") {
            val url = "$base/listfolder?path=${encodePath(path)}&access_token=$token"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful) {
                    throw IOException("listChildren('$path') failed: HTTP ${response.code}")
                }
                val json = JSONObject(bodyString ?: "{}")
                if (json.optInt("result") != 0) {
                    throw IOException("listChildren('$path') failed: pCloud error ${json.optInt("result")}: ${json.optString("error")}")
                }
                val contents = json.optJSONObject("metadata")?.optJSONArray("contents") ?: JSONArray()
                (0 until contents.length()).map { i -> parseItem(contents.getJSONObject(i)) }
            }
        }
    }

    /** A fresh download link for a file already known by its pCloud fileid -
     * TrackEntity.remotePath. pCloud download links expire, so this is
     * called right before playback/download, never persisted. */
    suspend fun getDownloadUrl(fileId: Long): String? = withContext(Dispatchers.IO) {
        val base = authManager.apiBaseUrl() ?: return@withContext null
        val token = authManager.accessToken() ?: return@withContext null
        try {
            val url = "$base/getfilelink?fileid=$fileId&access_token=$token"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string()
                if (!response.isSuccessful) {
                    Log.w(TAG, "getDownloadUrl($fileId) failed: HTTP ${response.code} body=$bodyString")
                    return@withContext null
                }
                val json = JSONObject(bodyString ?: return@withContext null)
                if (json.optInt("result") != 0) {
                    Log.w(TAG, "getDownloadUrl($fileId) pCloud error ${json.optInt("result")}: ${json.optString("error")}")
                    return@withContext null
                }
                val hosts = json.optJSONArray("hosts")
                val path = json.optString("path").takeIf { it.isNotBlank() }
                if (hosts == null || hosts.length() == 0 || path == null) {
                    Log.w(TAG, "getDownloadUrl($fileId): missing hosts/path in response: $bodyString")
                    return@withContext null
                }
                "https://${hosts.getString(0)}$path"
            }
        } catch (e: Exception) {
            Log.e(TAG, "getDownloadUrl($fileId) threw", e)
            null
        }
    }

    /** First [maxBytes] of a file's content via a ranged GET, for
     * Id3Reader.readFromPrefix during scanning - see OneDriveApiClient's
     * identical fetchPrefix for why this returns an empty array (not an
     * exception) on failure. Confirming this ranged GET actually returns
     * `206 Partial Content` (not a full 200) against a real pCloud download
     * link is the "five-minute empirical check" called for before trusting
     * on-device seek/scrub - see the plan's Build order step 5. */
    suspend fun fetchPrefix(downloadUrl: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        try {
            withRetry("fetchPrefix") {
                val request = Request.Builder()
                    .url(downloadUrl)
                    .header("Range", "bytes=0-${maxBytes - 1}")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("fetchPrefix failed: HTTP ${response.code}")
                    response.body?.bytes() ?: ByteArray(0)
                }
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /** Streams the full file at [downloadUrl] to [destination] for "keep
     * offline" downloads - writes to a ".part" sibling first, renamed into
     * place only on a clean finish (same pattern as OneDriveApiClient). */
    suspend fun downloadToFile(downloadUrl: String, destination: File) = withContext(Dispatchers.IO) {
        destination.parentFile?.mkdirs()
        val partFile = File(destination.parentFile, "${destination.name}.part")
        val request = Request.Builder().url(downloadUrl).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("download failed: HTTP ${response.code}")
            val body = response.body ?: throw IOException("download failed: empty response body")
            partFile.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
        if (!partFile.renameTo(destination)) {
            throw IOException("failed to move ${partFile.path} to ${destination.path}")
        }
    }

    private fun parseItem(item: JSONObject): PCloudItem {
        val isFolder = item.optBoolean("isfolder", false)
        return PCloudItem(
            name = item.getString("name"),
            isFolder = isFolder,
            fileId = if (isFolder) null else item.optLong("fileid"),
            size = item.optLong("size"),
        )
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
}
