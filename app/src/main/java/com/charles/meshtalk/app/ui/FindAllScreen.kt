package com.charles.meshtalk.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charles.meshtalk.app.media.LocationFetcher
import com.charles.meshtalk.app.media.OfflineMapCache
import com.charles.meshtalk.app.repository.MeshRepository
import com.charles.meshtalk.app.sensors.rememberCompassHeading
import com.charles.meshtalk.app.ui.theme.SignalGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private data class RadarHeadingSample(val heading: Float, val rssi: Int, val time: Long)

/**
 * A live radar of everyone currently reachable over Bluetooth: a map of roughly where you are (best
 * effort, cached offline once fetched) with peers plotted around you — direction from a rolling
 * "which way was the signal strongest" estimate as you turn, distance from RSSI. Neither is exact:
 * there's no real Bluetooth direction-finding hardware here, so this is a hunt-the-signal aid, not a
 * precise position fix. Works with GPS and internet both off.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindAllScreen(repository: MeshRepository, onOpenPeer: (String) -> Unit) {
    val context = LocalContext.current
    val contacts by repository.contacts.collectAsState(initial = emptyList())
    val rssiMap by repository.peerRssi.collectAsState()
    val headingState = rememberCompassHeading()
    val heading = headingState.value

    var mapBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var locationUnavailable by remember { mutableStateOf(false) }
    var mapUnavailable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val location = withContext(Dispatchers.IO) { LocationFetcher.getLastKnownLocation(context) }
        if (location == null) {
            locationUnavailable = true
        } else {
            val file = withContext(Dispatchers.IO) {
                OfflineMapCache.getOrFetch(context, location.latitude, location.longitude, zoom = 15, withMarker = false)
            }
            if (file == null) {
                mapUnavailable = true
            } else {
                mapBitmap = BitmapFactory.decodeFile(file.absolutePath)
            }
        }
    }

    // Estimate a bearing per peer from a short rolling history of (heading, rssi) samples: the
    // circular mean of headings weighted by signal strength, so it drifts toward "whichever way I
    // was facing when the signal was strongest recently" as you turn or walk around.
    val bearingByPeer = remember { mutableStateMapOf<String, Float>() }
    val currentHeading = rememberUpdatedState(heading)
    val currentRssi = rememberUpdatedState(rssiMap)
    LaunchedEffect(Unit) {
        val history = mutableMapOf<String, MutableList<RadarHeadingSample>>()
        while (true) {
            delay(500)
            val now = System.currentTimeMillis()
            val h = currentHeading.value
            val rssiSnapshot = currentRssi.value
            for ((peerKey, rssi) in rssiSnapshot) {
                val samples = history.getOrPut(peerKey) { mutableListOf() }
                samples.add(RadarHeadingSample(h, rssi, now))
                samples.removeAll { now - it.time > 12_000 }
                if (samples.isEmpty()) continue
                val minRssi = samples.minOf { it.rssi }
                var sumSin = 0.0
                var sumCos = 0.0
                for (s in samples) {
                    val weight = (s.rssi - minRssi + 1).toDouble()
                    val rad = Math.toRadians(s.heading.toDouble())
                    sumSin += sin(rad) * weight
                    sumCos += cos(rad) * weight
                }
                bearingByPeer[peerKey] = ((Math.toDegrees(atan2(sumSin, sumCos)) + 360) % 360).toFloat()
            }
            val activeKeys = rssiSnapshot.keys
            history.keys.retainAll(activeKeys)
            bearingByPeer.keys.retainAll(activeKeys)
        }
    }

    val nearbyContacts = contacts.filter { rssiMap.containsKey(it.signingPubKeyHex) }
    val offlineContacts = contacts.filter { !rssiMap.containsKey(it.signingPubKeyHex) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Bluetooth Radar") })

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val diameter = minOf(maxWidth, 320.dp)
            Box(
                modifier = Modifier
                    .size(diameter)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                mapBitmap?.let { bmp ->
                    // Rotates opposite the phone's heading so "up" on screen always matches which
                    // way you're facing — the same forward-up frame the peer arrows already use,
                    // like a turn-by-turn navigation map.
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Map of your area",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().rotate(-heading).alpha(0.6f)
                    )
                }

                RadarOverlay(heading = heading)

                val diameterPx = with(androidx.compose.ui.platform.LocalDensity.current) { diameter.toPx() }
                val maxRadiusPx = diameterPx / 2 * 0.82f
                for (contact in nearbyContacts) {
                    val rssi = rssiMap[contact.signingPubKeyHex] ?: continue
                    val bearing = bearingByPeer[contact.signingPubKeyHex] ?: heading
                    val radiusPx = maxRadiusPx * (0.08f + 0.92f * proximityRadiusFraction(rssi))
                    val angleDeg = bearing - heading
                    val rad = Math.toRadians((angleDeg - 90).toDouble())
                    val offsetPx = Offset((radiusPx * cos(rad)).toFloat(), (radiusPx * sin(rad)).toFloat())
                    val (xDp, yDp) = with(androidx.compose.ui.platform.LocalDensity.current) {
                        offsetPx.x.toDp() to offsetPx.y.toDp()
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .offset(x = xDp, y = yDp)
                            .clickable { onOpenPeer(contact.signingPubKeyHex) }
                    ) {
                        Icon(
                            Icons.Filled.Navigation,
                            contentDescription = "Direction to ${contact.nickname}",
                            tint = SignalGreen,
                            modifier = Modifier.size(22.dp).rotate(angleDeg)
                        )
                        Text(
                            contact.nickname,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            proximityFor(rssi).first,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // "You" marker, always dead center.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Text(
            when {
                locationUnavailable ->
                    "No GPS fix — showing the radar without a map backdrop. Peer direction is a " +
                        "rough \"which way was the signal strongest\" estimate, not exact position."
                mapUnavailable ->
                    "No internet right now, so the map backdrop couldn't load (it'll be cached " +
                        "offline next time it does). Peer direction is a rough signal-strength " +
                        "estimate, not exact position."
                else ->
                    "You're always at the center, map rotates to match which way you're facing. " +
                        "Peer positions are relative Bluetooth signal estimates, not GPS " +
                        "placements — turn around slowly to help the arrows settle."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)
        )

        Text(
            "Nearby (${nearbyContacts.size})",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
        )
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(offlineContacts) { contact ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPeer(contact.signingPubKeyHex) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(contact.nickname, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Not detected nearby right now",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RadarOverlay(heading: Float) {
    androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = min(size.width, size.height) / 2f * 0.82f
        val ringColor = Color.Gray.copy(alpha = 0.35f)
        for (i in 1..4) {
            drawCircle(
                color = ringColor,
                radius = maxRadius * i / 4f,
                center = center,
                style = Stroke(width = 1.5f)
            )
        }
        // North indicator: rotates around the ring edge opposite the phone's current heading.
        val northAngle = Math.toRadians((-heading - 90).toDouble())
        val northPoint = Offset(
            center.x + (maxRadius * cos(northAngle)).toFloat(),
            center.y + (maxRadius * sin(northAngle)).toFloat()
        )
        drawCircle(color = Color(0xFFE05252), radius = 5f, center = northPoint)
    }
}
