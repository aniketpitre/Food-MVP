package app.foodfeed.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Location
import android.location.LocationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import android.os.CancellationSignal
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.max

/** Facts about the phone that change how hard the app works it. */
object Device {
    /**
     * Low-end: Android's own low-RAM flag, under 3 GB of memory, or a really small per-app
     * heap. These phones get Lite mode by default. (The per-app heap used to count at 192 MB,
     * which caught plenty of ordinary mid-range phones and switched their effects off.)
     */
    fun isLowEnd(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return am.isLowRamDevice || info.totalMem < 3L * 1024 * 1024 * 1024 || am.memoryClass < 128
    }
}

/** What happened when we asked the phone where it is. */
sealed interface LocationResult {
    data class Found(val coords: Coords) : LocationResult
    /** No permission (or it was denied). */
    data object NoPermission : LocationResult
    /** Location is switched off in the phone's quick settings. */
    data object Off : LocationResult
    /** On and allowed, but no fix came back in time (indoors, no network...). */
    data object Unavailable : LocationResult
}

object DeviceLocation {
    /** Ask for both: people can still pick "Approximate" in the system dialog. */
    val PERMISSIONS = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasPermission(context: Context) =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) || granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    fun isOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    /** Opens the phone's location settings so people can switch location on. */
    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Coordinates, or null for any failure. */
    suspend fun current(context: Context, precise: Boolean = false): Coords? =
        (locate(context, precise) as? LocationResult.Found)?.coords

    fun hasPrecise(context: Context) = granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * Where the phone is. Quick mode (finding your city) takes a recent reading or the first
     * provider to answer. Precise mode (the map, distances) only trusts readings that are
     * fresh and accurate: it asks GPS, network and the fused provider at once, keeps the
     * most accurate answer, and stops early once one is within 30 m. Gives up after 15 s
     * and then returns the best it has.
     */
    @SuppressLint("MissingPermission")
    suspend fun locate(context: Context, precise: Boolean = false): LocationResult {
        if (!hasPermission(context)) return LocationResult.NoPermission
        if (!isOn(context)) return LocationResult.Off
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val fine = hasPrecise(context)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            if (fine) add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.PASSIVE_PROVIDER)
        }.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        val maxAge = if (precise) 60_000L else 10 * 60_000L
        val maxError = if (precise) 50f else 5_000f
        val now = System.currentTimeMillis()
        val known = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        val recent = known
            .filter { now - it.time < maxAge && (!it.hasAccuracy() || it.accuracy <= maxError) }
            .minByOrNull { if (it.hasAccuracy()) it.accuracy else 9_999f }
        if (recent != null) return LocationResult.Found(recent.toCoords())

        val active = providers.filter { it != LocationManager.PASSIVE_PROVIDER }
        val good = 30f
        var best: Location? = null
        if (active.isNotEmpty()) withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                val signals = active.map { CancellationSignal() }
                var pending = active.size
                cont.invokeOnCancellation { signals.forEach { it.cancel() } }
                fun done() {
                    signals.forEach { it.cancel() }
                    if (cont.isActive) cont.resume(Unit)
                }
                active.forEachIndexed { i, provider ->
                    try {
                        LocationManagerCompat.getCurrentLocation(lm, provider, signals[i], ContextCompat.getMainExecutor(context)) { loc ->
                            pending--
                            if (!cont.isActive) return@getCurrentLocation
                            if (loc != null && (best == null || accuracyOf(loc) < accuracyOf(best!!))) best = loc
                            val b = best
                            when {
                                // Quick mode: any answer will do.
                                !precise && b != null -> done()
                                // Precise mode: stop as soon as it's within 30 m.
                                b != null && accuracyOf(b) <= good -> done()
                                pending == 0 -> done()
                            }
                        }
                    } catch (e: Exception) {
                        pending--
                        if (pending == 0) done()
                    }
                }
            }
        }
        val pick = best ?: known.maxByOrNull { it.time }
        return if (pick != null) LocationResult.Found(pick.toCoords()) else LocationResult.Unavailable
    }

    private fun accuracyOf(l: Location) = if (l.hasAccuracy()) l.accuracy else 9_999f

    /**
     * Follows the phone while a screen needs it (the map): GPS when precise location is
     * allowed, else network, every few seconds or 5 m. Returns a stop function.
     */
    @SuppressLint("MissingPermission")
    fun follow(context: Context, onUpdate: (Coords) -> Unit): () -> Unit {
        if (!hasPermission(context) || !isOn(context)) return {}
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            hasPrecise(context) && runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) -> LocationManager.GPS_PROVIDER
            runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
            else -> return {}
        }
        val listener = androidx.core.location.LocationListenerCompat { loc -> onUpdate(loc.toCoords()) }
        val request = androidx.core.location.LocationRequestCompat.Builder(3_000L)
            .setMinUpdateDistanceMeters(5f)
            .setQuality(androidx.core.location.LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .build()
        val stop: () -> Unit = { runCatching { LocationManagerCompat.removeUpdates(lm, listener) } }
        return try {
            LocationManagerCompat.requestLocationUpdates(lm, provider, request, ContextCompat.getMainExecutor(context), listener)
            stop
        } catch (e: Exception) {
            {}
        }
    }

    /** A sentence for the person when we could not get a location. */
    fun explain(result: LocationResult): String? = when (result) {
        is LocationResult.Found -> null
        LocationResult.NoPermission -> "Location permission is off for FoodFeed. Allow it in the prompt or in Android settings."
        LocationResult.Off -> "Your phone's location is switched off. Turn it on and try again."
        LocationResult.Unavailable -> "Couldn't get a location fix. Try again near a window or with mobile data on."
    }

    private fun Location.toCoords() = Coords(latitude, longitude, if (hasAccuracy()) accuracy else null)
}

object ImageCompressor {
    private const val MAX_EDGE = 1600

    /**
     * Decodes a picked photo, applies its EXIF rotation, scales it to at most 1600 px and
     * re-encodes it as JPEG. Re-encoding drops all metadata, including GPS location, before
     * anything leaves the phone. The server re-encodes again into its own sizes.
     */
    suspend fun compress(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file is not a photo." }

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Could not read the photo.")

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        val scale = minOf(1f, MAX_EDGE.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0f) postRotate(rotation)
        }
        val output = if (matrix.isIdentity) decoded
        else Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)

        ByteArrayOutputStream().use { out ->
            output.compress(Bitmap.CompressFormat.JPEG, 85, out)
            if (output !== decoded) output.recycle()
            decoded.recycle()
            out.toByteArray()
        }
    }
}
