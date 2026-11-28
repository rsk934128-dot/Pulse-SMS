package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.ConnectedPeerDevice
import com.example.telephony.CapturedDeviceLocation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class PlottedDevicePin(
    val deviceId: String,
    val deviceName: String,
    val latitude: Double,
    val longitude: Double,
    val label: String?,
    val isOnline: Boolean,
    val isSelf: Boolean,
    val batteryLevel: Int,
    val accuracyM: Double?
)

@Composable
fun DeviceLocationMapCard(
    language: String,
    currentDeviceId: String,
    currentDeviceName: String,
    currentLocation: CapturedDeviceLocation?,
    connectedPeerDevices: List<ConnectedPeerDevice>,
    hasLocationPermission: Boolean,
    isSystemLocationEnabled: Boolean,
    isLiveLocationSharingEnabled: Boolean,
    isRefreshingLocation: Boolean,
    onTurnOnLocation: () -> Unit,
    onToggleLocationSharing: (Boolean) -> Unit,
    onRefreshLocation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isBn = language == "BN"
    val isLocationActive = hasLocationPermission && isSystemLocationEnabled && isLiveLocationSharingEnabled

    // Merge connected devices with local current device coordinates if available
    val pins = remember(connectedPeerDevices, currentLocation, currentDeviceId, currentDeviceName, isLocationActive) {
        val list = mutableListOf<PlottedDevicePin>()
        val seenIds = mutableSetOf<String>()

        connectedPeerDevices.forEach { d ->
            val isSelf = d.deviceId == currentDeviceId
            val lat = if (isSelf && isLocationActive && currentLocation != null) currentLocation.latitude else d.latitude
            val lng = if (isSelf && isLocationActive && currentLocation != null) currentLocation.longitude else d.longitude
            val lbl = if (isSelf && isLocationActive && currentLocation != null) currentLocation.label else d.locationLabel
            if (lat != null && lng != null && lat in -90.0..90.0 && lng in -180.0..180.0) {
                seenIds.add(d.deviceId)
                list.add(
                    PlottedDevicePin(
                        deviceId = d.deviceId,
                        deviceName = d.deviceName,
                        latitude = lat,
                        longitude = lng,
                        label = lbl,
                        isOnline = d.isOnline || isSelf,
                        isSelf = isSelf,
                        batteryLevel = d.batteryLevel,
                        accuracyM = if (isSelf && currentLocation?.accuracyMeters != null) {
                            currentLocation.accuracyMeters.toDouble()
                        } else d.locationAccuracyM
                    )
                )
            }
        }

        if (!seenIds.contains(currentDeviceId) && isLocationActive && currentLocation != null) {
            list.add(
                0,
                PlottedDevicePin(
                    deviceId = currentDeviceId,
                    deviceName = currentDeviceName,
                    latitude = currentLocation.latitude,
                    longitude = currentLocation.longitude,
                    label = currentLocation.label,
                    isOnline = true,
                    isSelf = true,
                    batteryLevel = -1,
                    accuracyM = currentLocation.accuracyMeters?.toDouble()
                )
            )
        }
        list
    }

    var selectedPinId by remember(pins) {
        mutableStateOf(pins.firstOrNull { it.isSelf }?.deviceId ?: pins.firstOrNull()?.deviceId)
    }
    val activePin = pins.firstOrNull { it.deviceId == selectedPinId } ?: pins.firstOrNull()

    val infiniteTransition = rememberInfiniteTransition(label = "radarPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.65f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1700),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1324)),
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isLocationActive) Color(0xFF10B981).copy(alpha = 0.55f) else Color(0xFF38BDF8).copy(alpha = 0.35f),
                shape = RoundedCornerShape(18.dp)
            )
            .testTag("device_location_map_card")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = if (isLocationActive) {
                                        listOf(Color(0xFF059669), Color(0xFF0891B2))
                                    } else {
                                        listOf(Color(0xFF2563EB), Color(0xFF0284C7))
                                    }
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isLocationActive) Icons.Default.MyLocation else Icons.Default.Map,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (isBn) "🗺️ ডিভাইস লাইভ লোকেশন ম্যাপ" else "🗺️ Live Device Location Map",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = if (isBn) {
                                "${pins.size} টি ডিভাইস ম্যাপে পিন করা আছে"
                            } else {
                                "${pins.size} device(s) pinned on map"
                            },
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                    }
                }

                if (hasLocationPermission && isSystemLocationEnabled) {
                    Switch(
                        checked = isLiveLocationSharingEnabled,
                        onCheckedChange = onToggleLocationSharing,
                        modifier = Modifier.testTag("live_location_sharing_switch")
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Primary Turn On / Refresh Location Button Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val buttonText = when {
                    !hasLocationPermission -> if (isBn) "📍 লোকেশন চালু করুন (অনুমতি দিন)" else "📍 Turn On Location (Permission)"
                    !isSystemLocationEnabled -> if (isBn) "📍 ফোনের GPS লোকেশন চালু করুন" else "📍 Enable Phone GPS Location"
                    !isLiveLocationSharingEnabled -> if (isBn) "📍 লাইভ লোকেশন শেয়ার চালু করুন" else "📍 Turn On Live Location"
                    else -> if (isBn) "🔄 লোকেশন আপডেট করুন" else "🔄 Refresh Location"
                }
                val buttonContainerColor = when {
                    !hasLocationPermission || !isLiveLocationSharingEnabled -> Color(0xFF059669)
                    !isSystemLocationEnabled -> Color(0xFFD97706)
                    else -> Color(0xFF0284C7)
                }

                Button(
                    onClick = {
                        if (isLocationActive) onRefreshLocation() else onTurnOnLocation()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = buttonContainerColor),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("turn_on_device_location_button")
                ) {
                    if (isRefreshingLocation) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (isLocationActive) Icons.Default.Refresh else Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = buttonText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (activePin != null) {
                    OutlinedButton(
                        onClick = { openPinInExternalMaps(context, activePin) },
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("open_external_device_map_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Explore,
                            contentDescription = if (isBn) "গুগল ম্যাপে দেখুন" else "Open in Maps",
                            tint = Color(0xFF38BDF8),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isBn) "ম্যাপ অ্যাপ" else "Maps",
                            color = Color(0xFFBAE6FD),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive Dark Cartographic Radar Map Canvas
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF12263F), Color(0xFF070D19))
                        )
                    )
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            ) {
                val widthPx = with(LocalDensity.current) { maxWidth.toPx() }
                val heightPx = with(LocalDensity.current) { maxHeight.toPx() }

                val projectedPins = remember(pins, widthPx, heightPx) {
                    computeProjectedPositions(pins, widthPx, heightPx)
                }

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val w = size.width
                    val h = size.height
                    val gridColor = Color(0xFF38BDF8).copy(alpha = 0.12f)
                    val cols = 6
                    val rows = 4
                    for (c in 1 until cols) {
                        val x = w * c / cols
                        drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                    }
                    for (r in 1 until rows) {
                        val y = h * r / rows
                        drawLine(gridColor, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
                    }

                    // Radar concentric circles centered on selected or center
                    val radarCenter = projectedPins.firstOrNull { it.first.deviceId == activePin?.deviceId }?.second
                        ?: Offset(w / 2f, h / 2f)
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    listOf(0.22f, 0.42f, 0.65f).forEach { factor ->
                        drawCircle(
                            color = Color(0xFF10B981).copy(alpha = 0.16f),
                            radius = max(w, h) * factor * 0.5f,
                            center = radarCenter,
                            style = Stroke(width = 1.2f, pathEffect = dashEffect)
                        )
                    }

                    // Connect multiple devices with subtle telemetry vector lines
                    if (projectedPins.size > 1) {
                        for (i in 0 until projectedPins.size - 1) {
                            drawLine(
                                color = Color(0xFF38BDF8).copy(alpha = 0.35f),
                                start = projectedPins[i].second,
                                end = projectedPins[i + 1].second,
                                strokeWidth = 2f,
                                pathEffect = dashEffect
                            )
                        }
                    }

                    // Draw each device pin
                    projectedPins.forEach { (pin, pos) ->
                        val isSelected = pin.deviceId == activePin?.deviceId
                        val baseColor = when {
                            pin.isSelf -> Color(0xFF10B981)
                            pin.isOnline -> Color(0xFF38BDF8)
                            else -> Color(0xFFF59E0B)
                        }

                        if (isSelected) {
                            drawCircle(
                                color = baseColor.copy(alpha = pulseAlpha),
                                radius = 16f * pulseScale,
                                center = pos
                            )
                        }
                        drawCircle(
                            color = Color.White,
                            radius = if (isSelected) 12f else 9f,
                            center = pos
                        )
                        drawCircle(
                            color = baseColor,
                            radius = if (isSelected) 9f else 6.5f,
                            center = pos
                        )
                    }
                }

                // Floating labels for plotted pins
                projectedPins.forEach { (pin, pos) ->
                    val isSelected = pin.deviceId == activePin?.deviceId
                    val offsetX = (pos.x - 42f).roundToInt().coerceIn(8, (widthPx - 140f).roundToInt().coerceAtLeast(8))
                    val offsetY = (pos.y + 14f).roundToInt().coerceIn(8, (heightPx - 36f).roundToInt().coerceAtLeast(8))

                    Surface(
                        onClick = { selectedPinId = pin.deviceId },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Color(0xFF065F46).copy(alpha = 0.92f) else Color(0xFF0F172A).copy(alpha = 0.88f),
                        modifier = Modifier
                            .offset { IntOffset(offsetX, offsetY) }
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFF34D399) else Color(0xFF334155),
                                shape = RoundedCornerShape(8.dp)
                            )
                    ) {
                        Text(
                            text = if (pin.isSelf) "📍 ${pin.deviceName.take(14)}" else "📱 ${pin.deviceName.take(14)}",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                // Compass badge top-right
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (pins.isEmpty()) "GPS OFF" else "N ↑ · LIVE",
                        color = if (pins.isEmpty()) Color(0xFFFBBF24) else Color(0xFF6EE7B7),
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }

                // Empty state overlay when no device has active coordinates yet
                if (pins.isEmpty()) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOff,
                            contentDescription = null,
                            tint = Color(0xFF64748B),
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (isBn) "কোনো ডিভাইসের লোকেশন এখনো পাওয়া যায়নি" else "No device coordinates pinned yet",
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isBn) "উপরের 'লোকেশন চালু করুন' বাটনে ট্যাপ করলে ডিভাইসের পিন ম্যাপে দেখাবে" else "Tap Turn On Location above to plot real device coordinates",
                            color = Color(0xFF94A3B8),
                            fontSize = 10.5.sp
                        )
                    }
                }
            }

            // Device switcher chips & active pin details
            if (pins.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    pins.forEach { pin ->
                        val isSelected = pin.deviceId == activePin?.deviceId
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedPinId = pin.deviceId },
                            label = {
                                Text(
                                    text = if (pin.isSelf) "${pin.deviceName} (${if (isBn) "এই ফোন" else "Self"})" else pin.deviceName,
                                    fontSize = 11.sp,
                                    maxLines = 1
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF065F46),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF1E293B),
                                labelColor = Color(0xFFCBD5E1)
                            )
                        )
                    }
                }

                if (activePin != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF111C33),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "📍 ${activePin.label ?: String.format(Locale.US, "%.5f, %.5f", activePin.latitude, activePin.longitude)}",
                                color = Color(0xFF6EE7B7),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Lat %.5f, Lng %.5f%s",
                                    activePin.latitude,
                                    activePin.longitude,
                                    activePin.accuracyM?.let { " · ±${it.roundToInt()}m" } ?: ""
                                ),
                                color = Color(0xFF94A3B8),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun computeProjectedPositions(
    pins: List<PlottedDevicePin>,
    widthPx: Float,
    heightPx: Float
): List<Pair<PlottedDevicePin, Offset>> {
    if (pins.isEmpty() || widthPx <= 0f || heightPx <= 0f) return emptyList()
    if (pins.size == 1) {
        return listOf(pins.first() to Offset(widthPx * 0.5f, heightPx * 0.44f))
    }
    val minLat = pins.minOf { it.latitude }
    val maxLat = pins.maxOf { it.latitude }
    val minLng = pins.minOf { it.longitude }
    val maxLng = pins.maxOf { it.longitude }
    val latSpan = abs(maxLat - minLat)
    val lngSpan = abs(maxLng - minLng)

    val padX = widthPx * 0.16f
    val padY = heightPx * 0.22f
    val usableW = (widthPx - padX * 2f).coerceAtLeast(1f)
    val usableH = (heightPx - padY * 2f).coerceAtLeast(1f)

    return pins.mapIndexed { idx, pin ->
        val nx = if (lngSpan < 0.0002) {
            0.3f + (idx % 3) * 0.2f
        } else {
            ((pin.longitude - minLng) / lngSpan).toFloat().coerceIn(0f, 1f)
        }
        val ny = if (latSpan < 0.0002) {
            0.35f + (idx % 2) * 0.2f
        } else {
            1f - ((pin.latitude - minLat) / latSpan).toFloat().coerceIn(0f, 1f)
        }
        pin to Offset(padX + nx * usableW, padY + ny * usableH)
    }
}

fun openPinInExternalMaps(context: Context, pin: PlottedDevicePin) {
    try {
        val label = Uri.encode(pin.label ?: pin.deviceName)
        val uri = Uri.parse(
            String.format(Locale.US, "geo:%.6f,%.6f?q=%.6f,%.6f(%s)", pin.latitude, pin.longitude, pin.latitude, pin.longitude, label)
        )
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Throwable) {
        try {
            val webUri = Uri.parse(
                String.format(Locale.US, "https://www.google.com/maps?q=%.6f,%.6f", pin.latitude, pin.longitude)
            )
            context.startActivity(Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (_: Throwable) {
        }
    }
}
