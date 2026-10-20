package com.airwatch.wear.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.airwatch.wear.Engine
import com.airwatch.wear.PointerMode
import com.airwatch.wear.Profiles
import com.airwatch.wear.RotaryStepper
import com.airwatch.wear.clampSlide
import com.airwatch.wear.formatClock
import kotlinx.coroutines.delay

private val GREEN = Color(0xFF66BB6A)
private val AMBER = Color(0xFFFFA726)
private val RED = Color(0xFFEF5350)

// ====================================================================== Pantalla principal de presentacion
@Composable
internal fun PresentScreen(onMore: () -> Unit) {
    val slide by Engine.slide.collectAsState()
    val timer by Engine.timerUi.collectAsState()
    val pointer by Engine.pointer.collectAsState()
    val profile by Engine.profile.collectAsState()
    val toast by Engine.toast.collectAsState()
    val focus = remember { FocusRequester() }
    val stepper = remember { RotaryStepper(40f) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(toast) { if (toast != null) { delay(2600); tick++ } }
    val shownToast = toast
    val toastText: String? =
        if (shownToast != null && tick >= 0 && SystemClock.elapsedRealtime() - shownToast.second < 2500L) shownToast.first else null

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Corona / bisel (si el reloj lo tiene): una muesca = una diapositiva
            .onRotaryScrollEvent {
                val n = stepper.add(it.verticalScrollPixels)
                repeat(kotlin.math.abs(n)) { Engine.pres(if (n > 0) "next" else "prev") }
                true
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(slide?.label() ?: "–/?", fontSize = 20.sp)
                Text(formatClock(timer.remainingMs), fontSize = 14.sp, color = timerColor(timer.remainingMs, timer.running))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("◀") { Engine.pres("prev") }
                BigButton("▶") { Engine.pres("next") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                LaserToggle(active = pointer == PointerMode.LASER)
                CompactChip(onClick = onMore, label = { Text("⋯") }, colors = ChipDefaults.secondaryChipColors())
            }
            Text(
                toastText ?: (profile?.label ?: "Perfil: …"),
                fontSize = 10.sp,
                color = if (toastText != null) AMBER else MaterialTheme.colors.onBackground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun timerColor(remainingMs: Long, running: Boolean): Color = when {
    !running && remainingMs > 0 -> Color.Unspecified
    remainingMs <= 60_000L -> RED
    remainingMs <= 5 * 60_000L -> AMBER
    else -> GREEN
}

@Composable
private fun BigButton(label: String, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colors.primary)
            .pointerInput(label) {
                detectTapGestures(onTap = {
                    onTap()
                    Engine.haptic(12)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 22.sp, color = MaterialTheme.colors.onPrimary)
    }
}

/** Laser: un toque lo enciende y otro lo apaga (tambien se apaga solo tras unos segundos sin mover la mano). */
@Composable
private fun LaserToggle(active: Boolean) {
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 32.dp)
            .clip(CircleShape)
            .background(if (active) Color(0xFFD32F2F) else MaterialTheme.colors.surface)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { Engine.toggleLaser() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(if (active) "Láser ●" else "Láser", fontSize = 12.sp)
    }
}

// ====================================================================== Mas opciones
@Composable
internal fun PresentMoreScreen(onGoto: () -> Unit, onNotes: () -> Unit, onTimer: () -> Unit) {
    val s by Engine.settings.collectAsState()
    val slide by Engine.slide.collectAsState()
    val profile by Engine.profile.collectAsState()
    val endArmed by Engine.endArmed.collectAsState()
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            item { MoreChip("Ir a diapositiva", slide?.label()) { onGoto() } }
            item {
                val notes = slide?.notes.orEmpty()
                MoreChip("Notas", if (notes.isBlank()) "Sin notas (solo PowerPoint)" else "Ver resumen") { onNotes() }
            }
            item { MoreChip("Temporizador", "${s.presMinutes} min") { onTimer() } }
            item { MoreChip("Iniciar presentación") { Engine.startShow(fromCurrent = false) } }
            item { MoreChip("Iniciar desde la actual") { Engine.startShow(fromCurrent = true) } }
            item { MoreChip("Pantalla negra") { Engine.pres("black") } }
            item { MoreChip("Pantalla blanca") { Engine.pres("white") } }
            item {
                val shown = if (s.presProfile == "auto") "Auto: ${profile?.label ?: "…"}" else Profiles.label(s.presProfile)
                MoreChip("Aplicación", shown) { Engine.cycleProfile() }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.requestEnd() },
                    label = { Text(if (endArmed) "¿Salir? Repite" else "Salir de la presentación") },
                    colors = if (endArmed) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

@Composable
private fun MoreChip(label: String, secondary: String? = null, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        label = { Text(label) },
        secondaryLabel = secondary?.let { { Text(it) } },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

// ====================================================================== Ir a una diapositiva (tactil + corona / bisel)
@Composable
internal fun PresentGotoScreen(onDone: () -> Unit) {
    val slide by Engine.slide.collectAsState()
    val total = slide?.total
    var n by remember { mutableStateOf(clampSlide(slide?.cur ?: 1, total)) }
    val focus = remember { FocusRequester() }
    val stepper = remember { RotaryStepper(40f) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { n = clampSlide(n + stepper.add(it.verticalScrollPixels), total); true }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ir a diapositiva", fontSize = 11.sp)
            Text("$n" + (total?.let { " / $it" } ?: ""), fontSize = 28.sp, color = MaterialTheme.colors.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (d in listOf(-10, -1, 1, 10)) {
                    CompactChip(
                        onClick = { n = clampSlide(n + d, total) },
                        label = { Text(if (d > 0) "+$d" else "$d", fontSize = 11.sp) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
            if (slide?.exact != true) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CompactChip(onClick = { Engine.setSlidesTotal(((total ?: n) - 1).coerceAtLeast(1)) },
                        label = { Text("Total −", fontSize = 10.sp) }, colors = ChipDefaults.secondaryChipColors())
                    CompactChip(onClick = { Engine.setSlidesTotal((total ?: n) + 1) },
                        label = { Text("Total +", fontSize = 10.sp) }, colors = ChipDefaults.secondaryChipColors())
                }
            }
            CompactChip(
                onClick = { Engine.pres("goto", n); onDone() },
                label = { Text("Ir") },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
    }
}

// ====================================================================== Notas del orador
@Composable
internal fun PresentNotesScreen() {
    val slide by Engine.slide.collectAsState()
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            item { Text("Notas · ${slide?.label() ?: "–/?"}", fontSize = 12.sp, color = MaterialTheme.colors.primary) }
            item {
                val notes = slide?.notes.orEmpty()
                Text(
                    if (notes.isBlank()) "Sin notas. El PC solo las lee de PowerPoint." else notes,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ====================================================================== Temporizador
@Composable
internal fun PresentTimerScreen() {
    val t by Engine.timerUi.collectAsState()
    val s by Engine.settings.collectAsState()
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(formatClock(t.remainingMs), fontSize = 30.sp, color = timerColor(t.remainingMs, t.running))
                    Text(
                        (if (t.remainingMs < 0) "Tiempo excedido · " else "Restante · ") + "total ${s.presMinutes} min",
                        fontSize = 10.sp,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    CompactChip(onClick = { Engine.timerStartPause() },
                        label = { Text(if (t.running) "Pausa" else "Iniciar") }, colors = ChipDefaults.primaryChipColors())
                    CompactChip(onClick = { Engine.timerReset() },
                        label = { Text("Reiniciar") }, colors = ChipDefaults.secondaryChipColors())
                }
            }
            item {
                SettingSlider("Duración", s.presMinutes.toFloat(), 5f..120f, 22, { "${it.toInt()} min" }) { v ->
                    Engine.setPresMinutes(((v / 5f).toInt() * 5).coerceAtLeast(5))
                }
            }
            item {
                Text(
                    "Vibra a la mitad y a 5, 2 y 1 min del final.",
                    fontSize = 10.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
