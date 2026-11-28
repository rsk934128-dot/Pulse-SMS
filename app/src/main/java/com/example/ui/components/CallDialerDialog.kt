package com.example.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneForwarded
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.telephony.DeviceCallLogItem
import com.example.telephony.DialerContactItem
import com.example.telephony.SimAccountInfo

private data class DialKeySpec(
    val digit: String,
    val letters: String,
    val longPressChar: String? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CallDialerDialog(
    initialNumber: String = "",
    language: String,
    hasCallPhonePermission: Boolean,
    contacts: List<DialerContactItem>,
    recentCalls: List<DeviceCallLogItem>,
    simAccounts: List<SimAccountInfo>,
    onCall: (number: String, simSlotIndex: Int, openDialerOnly: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptic = LocalHapticFeedback.current

    var typedNumber by remember(initialNumber) { mutableStateOf(initialNumber) }
    var selectedSimSlot by remember { mutableIntStateOf(if (simAccounts.isNotEmpty()) 0 else -1) }

    val matchingSuggestions = remember(typedNumber, contacts, recentCalls) {
        val query = typedNumber.trim().lowercase()
        val combined = mutableListOf<DialerContactItem>()
        val seenNums = HashSet<String>()

        for (call in recentCalls) {
            val clean = call.number.trim()
            if (clean.isNotBlank() && clean != "Private / Unknown") {
                val key = clean.takeLast(10)
                if (seenNums.add(key)) {
                    combined.add(
                        DialerContactItem(
                            name = call.contactName?.takeIf { it.isNotBlank() } ?: clean,
                            number = clean
                        )
                    )
                }
            }
        }
        for (c in contacts) {
            val key = c.number.takeLast(10)
            if (seenNums.add(key)) {
                combined.add(c)
            }
        }

        if (query.isEmpty()) {
            combined.take(6)
        } else {
            val digitsOnlyQuery = query.filter { it.isDigit() || it == '+' }
            combined.filter { item ->
                item.name.lowercase().contains(query) ||
                    (digitsOnlyQuery.isNotEmpty() && item.number.contains(digitsOnlyQuery))
            }.take(6)
        }
    }

    val dialRows = remember {
        listOf(
            listOf(DialKeySpec("1", " "), DialKeySpec("2", "ABC"), DialKeySpec("3", "DEF")),
            listOf(DialKeySpec("4", "GHI"), DialKeySpec("5", "JKL"), DialKeySpec("6", "MNO")),
            listOf(DialKeySpec("7", "PQRS"), DialKeySpec("8", "TUV"), DialKeySpec("9", "WXYZ")),
            listOf(DialKeySpec("*", " "), DialKeySpec("0", "+", longPressChar = "+"), DialKeySpec("#", " "))
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Dialpad,
                            contentDescription = null,
                            tint = Color(0xFF059669),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = if (language == "BN") "ফোন কল ডায়ালার" else "Phone Call Dialer",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (hasCallPhonePermission) {
                                if (language == "BN") "ডাইরেক্ট কল ও সিম সাপোর্ট সক্রিয়" else "Direct call & SIM selection ready"
                            } else {
                                if (language == "BN") "নাম্বার লিখে কল বাটনে চাপুন" else "Enter number and tap Call"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = if (language == "BN") "বন্ধ করুন" else "Close"
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Matching Contacts / Recent Call Chips
            if (matchingSuggestions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    matchingSuggestions.forEach { item ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    typedNumber = item.number
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Column {
                                    Text(
                                        text = item.name,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (item.name != item.number) {
                                        Text(
                                            text = item.number,
                                            fontSize = 10.5.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Number Display & Editable Input
            OutlinedTextField(
                value = typedNumber,
                onValueChange = { next ->
                    typedNumber = next.filter { ch ->
                        ch.isDigit() || ch == '+' || ch == '*' || ch == '#' || ch == ',' || ch == ';' || ch == ' ' || ch == '-'
                    }
                },
                placeholder = {
                    Text(
                        text = if (language == "BN") "নাম্বার লিখুন (+8801...)" else "Enter phone number...",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        fontSize = 18.sp
                    )
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    letterSpacing = 1.2.sp
                ),
                trailingIcon = {
                    if (typedNumber.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        typedNumber = typedNumber.dropLast(1)
                                    },
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        typedNumber = ""
                                    }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = if (language == "BN") "মুছুন" else "Backspace",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dial_pad_number_input")
            )

            // SIM Selector Chips (shown when multiple SIMs detected)
            if (simAccounts.size > 1) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    simAccounts.forEach { sim ->
                        FilterChip(
                            selected = selectedSimSlot == sim.slotIndex,
                            onClick = { selectedSimSlot = sim.slotIndex },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.SimCard,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = sim.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3x4 T9 Dial Pad Grid
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                dialRows.forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowKeys.forEach { key ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(58.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .combinedClickable(
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            typedNumber += key.digit
                                        },
                                        onLongClick = {
                                            if (key.longPressChar != null) {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                typedNumber += key.longPressChar
                                            }
                                        }
                                    )
                                    .testTag("dial_key_${key.digit}")
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = key.digit,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (key.letters.isNotBlank()) {
                                        Text(
                                            text = key.letters,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            letterSpacing = 1.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom Action Row: System Dialer fallback + Prominent Green Call CTA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedIconButton(
                    onClick = {
                        if (typedNumber.isNotBlank()) {
                            onCall(typedNumber, selectedSimSlot, true)
                        }
                    },
                    enabled = typedNumber.isNotBlank(),
                    modifier = Modifier
                        .size(56.dp)
                        .testTag("dial_pad_system_dialer_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneForwarded,
                        contentDescription = if (language == "BN") "ফোন ডায়ালারে খুলুন" else "Open System Dialer"
                    )
                }

                Button(
                    onClick = {
                        if (typedNumber.isNotBlank()) {
                            onCall(typedNumber, selectedSimSlot, false)
                        }
                    },
                    enabled = typedNumber.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .testTag("dial_pad_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (language == "BN") "কল করুন" else "Call Now",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
