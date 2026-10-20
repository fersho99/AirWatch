package com.airwatch.wear.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.InlineSlider
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.airwatch.wear.Action
import com.airwatch.wear.BtHidLink
import com.airwatch.wear.Engine
import com.airwatch.wear.Gesture
import com.airwatch.wear.LinkState
import com.airwatch.wear.Phase
import com.airwatch.wear.Step
import com.airwatch.wear.Transport
import com.airwatch.wear.Wrist
import kotlin.math.roundToInt

private enum class Screen { HOME, MOUSE, SETTINGS, WIZARD, PRES, PRES_MORE, PRES_GOTO, PRES_NOTES, PRES_TIMER }

private fun backTarget(s: Screen): Screen = when (s) {
    Screen.PRES_GOTO, Screen.PRES_NOTES, Screen.PRES_TIMER -> Screen.PRES_MORE
    Screen.PRES_MORE -> Screen.PRES
    else -> Screen.HOME
}

@Composable
fun AirWatchApp() {
    MaterialTheme {
        var screen by remember { mutableStateOf(if (Engine.settings.value.setupDone) Screen.HOME else Screen.WIZARD) }
        val sas by Engine.link.pairingSas.collectAsState()

        BackHandler(enabled = screen != Screen.HOME && sas == null) { screen = backTarget(screen) }

        // Todas las pantallas de presentacion comparten el modo: el movimiento ya no mueve el mouse real
        // y la pantalla se queda encendida (los gestos solo funcionan con la pantalla encendida).
        val view = LocalView.current
        val inPresentation = screen.name.startsWith("PRES")
        DisposableEffect(inPresentation) {
            if (inPresentation) {
                Engine.setPresenting(true)
                view.keepScreenOn = true
            }
            onDispose {
                if (inPresentation) {
                    Engine.setPresenting(false)
                    view.keepScreenOn = false
                }
            }
        }

        val code = sas
        if (code != null) {
            PairingScreen(code)
        } else when (screen) {
            Screen.HOME -> HomeScreen(
                onMouse = { screen = Screen.MOUSE },
                onSettings = { screen = Screen.SETTINGS },
                onWizard = { screen = Screen.WIZARD },
                onPresent = { screen = Screen.PRES },
            )
            Screen.MOUSE -> MouseScreen()
            Screen.SETTINGS -> SettingsScreen(onWizard = { screen = Screen.WIZARD })
            Screen.WIZARD -> WizardScreen(onDone = { screen = Screen.HOME })
            Screen.PRES -> PresentScreen(onMore = { screen = Screen.PRES_MORE })
            Screen.PRES_MORE -> PresentMoreScreen(
                onGoto = { screen = Screen.PRES_GOTO },
                onNotes = { screen = Screen.PRES_NOTES },
                onTimer = { screen = Screen.PRES_TIMER },
            )
            Screen.PRES_GOTO -> PresentGotoScreen(onDone = { screen = Screen.PRES })
            Screen.PRES_NOTES -> PresentNotesScreen()
            Screen.PRES_TIMER -> PresentTimerScreen()
        }
    }
}

private fun phaseLabel(p: Phase) = when (p) {
    Phase.STOPPED -> "Detenido"
    Phase.NETWORK -> "Activando Wi-Fi"
    Phase.DISCOVERING -> "Buscando PC"
    Phase.CONNECTING -> "Conectando"
    Phase.PAIRING -> "Emparejando"
    Phase.CONNECTED -> "Conectado"
    Phase.ERROR -> "Sin conexión"
}

// ====================================================================== Conexión
@Composable
private fun HomeScreen(onMouse: () -> Unit, onSettings: () -> Unit, onWizard: () -> Unit, onPresent: () -> Unit) {
    val ctx = LocalContext.current
    val link by Engine.linkState.collectAsState()
    val latency by Engine.link.latencyMs.collectAsState()
    val running by Engine.running.collectAsState()
    val listState = rememberScalingLazyListState()
    val paired = remember(link.phase, running) { Engine.isPaired() }

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            item { StatusBlock(link, latency) }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {},
                    enabled = false,
                    label = { Text("Método: Wi-Fi") },
                    secondaryLabel = { Text("BT HID y vía celular: Etapa 4") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { if (running) Engine.requestStop(ctx) else Engine.requestStart(ctx) },
                    enabled = running || paired,
                    label = { Text(if (running) "Desconectar" else if (paired) "Conectar" else "Empareja primero") },
                    colors = ChipDefaults.primaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.requestStart(ctx, pair = true) },
                    label = { Text(if (paired) "Re-emparejar" else "Emparejar") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onMouse,
                    enabled = link.phase == Phase.CONNECTED,
                    label = { Text("Modo mouse") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onPresent,
                    enabled = link.phase == Phase.CONNECTED,
                    label = { Text("Modo presentación") },
                    secondaryLabel = { Text("Diapositivas, láser, temporizador") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onWizard,
                    label = { Text("Calibrar muñeca") },
                    secondaryLabel = { Text("Ejes y gestos") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onSettings,
                    label = { Text("Ajustes") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

@Composable
private fun StatusBlock(link: LinkState, latency: Int?) {
    val color = when (link.phase) {
        Phase.CONNECTED -> Color(0xFF66BB6A)
        Phase.ERROR -> Color(0xFFEF5350)
        else -> MaterialTheme.colors.onBackground
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(phaseLabel(link.phase), color = color, fontSize = 18.sp, textAlign = TextAlign.Center)
        if (link.detail.isNotBlank()) {
            Text(link.detail, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
        if (link.phase == Phase.CONNECTED && latency != null) {
            Text("$latency ms", fontSize = 12.sp, color = if (latency < 30) Color(0xFF66BB6A) else Color(0xFFFFA726))
        }
    }
}

// ====================================================================== Emparejamiento
@Composable
private fun PairingScreen(sas: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Text("¿Coincide con el PC?", fontSize = 13.sp, textAlign = TextAlign.Center)
        Text(sas.chunked(3).joinToString(" "), fontSize = 30.sp, color = MaterialTheme.colors.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { Engine.link.confirmPairing(false) },
                colors = ButtonDefaults.secondaryButtonColors(),
            ) { Text("✗") }
            Button(onClick = { Engine.link.confirmPairing(true) }) { Text("✓") }
        }
    }
}

// ====================================================================== Mouse
@Composable
private fun MouseScreen() {
    val tracking by Engine.tracking.collectAsState()
    val latency by Engine.link.latencyMs.collectAsState()
    val link by Engine.linkState.collectAsState()
    val scrolling by Engine.scrollActive.collectAsState()
    val dragging by Engine.dragging.collectAsState()
    val gesture by Engine.lastGesture.collectAsState()
    val focus = remember { FocusRequester() }
    val view = LocalView.current

    LaunchedEffect(Unit) { focus.requestFocus() }
    DisposableEffect(Unit) {
        view.keepScreenOn = true               // la pantalla encendida mantiene el Wi-Fi en baja latencia
        onDispose { view.keepScreenOn = false }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onRotaryScrollEvent { Engine.scroll(it.verticalScrollPixels); true }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val status = when {
                link.phase != Phase.CONNECTED -> "Sin conexión"
                scrolling -> "↕ Modo scroll"
                dragging -> "✥ Arrastrando"
                tracking -> "● Activo" + (latency?.let { " · $it ms" } ?: "")
                else -> "❚❚ En pausa"
            }
            Text(
                status,
                fontSize = 12.sp,
                color = if (tracking && link.phase == Phase.CONNECTED) Color(0xFF66BB6A) else Color(0xFFFFA726),
            )
            gesture?.let { Text("${it.gesture.label}: ${it.action.label}", fontSize = 10.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MouseButton("Izq", "left")
                MouseButton("Der", "right")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(onClick = { Engine.toggleTracking() }, label = { Text(if (tracking) "Pausa" else "Seguir") })
                CompactChip(onClick = { Engine.recenter() }, label = { Text("Centrar") })
            }
            // Respaldo tactil del bisel/corona (el Watch 8 sin bisel fisico no tiene rueda)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactChip(onClick = { Engine.scroll(-40f) }, label = { Text("▲") })
                CompactChip(onClick = { Engine.scroll(40f) }, label = { Text("▼") })
            }
        }
    }
}

@Composable
private fun MouseButton(label: String, name: String) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colors.primary)
            .pointerInput(name) {
                detectTapGestures(onPress = {
                    Engine.button(name, "down")       // mantener = arrastrar
                    Engine.haptic(12)
                    tryAwaitRelease()
                    Engine.button(name, "up")
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colors.onPrimary)
    }
}

// ====================================================================== Ajustes
@Composable
private fun SettingsScreen(onWizard: () -> Unit) {
    val ctx = LocalContext.current
    val s by Engine.settings.collectAsState()
    val listState = rememberScalingLazyListState()

    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
            item { Text("Ajustes", fontSize = 16.sp) }
            item {
                SettingSlider("Sensibilidad", s.sensitivity, 0.2f..3.0f, 13, { "%.1f×".format(it) }) { v ->
                    Engine.update { it.copy(sensitivity = v) }
                }
            }
            item {
                SettingSlider("Suavizado", s.smoothing, 0f..0.9f, 8, { "${(it * 100).roundToInt()}%" }) { v ->
                    Engine.update { it.copy(smoothing = v) }
                }
            }
            item {
                SettingSlider("Zona muerta", s.deadzone, 0f..0.3f, 14, { "%.2f rad/s".format(it) }) { v ->
                    Engine.update { it.copy(deadzone = v) }
                }
            }
            item {
                SettingSlider("Aceleración", s.accel, 0f..2f, 9, { "%.1f".format(it) }) { v ->
                    Engine.update { it.copy(accel = v) }
                }
            }
            item {
                SettingSlider("Frecuencia de envío", s.sendHz.toFloat(), 60f..100f, 7, { "${it.roundToInt()} Hz" }) { v ->
                    Engine.update { it.copy(sendHz = (v / 5f).roundToInt() * 5) }
                }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.update { it.copy(invertX = !it.invertX) } },
                    label = { Text("Invertir X") },
                    secondaryLabel = { Text(if (s.invertX) "Activado" else "No") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.update { it.copy(invertY = !it.invertY) } },
                    label = { Text("Invertir Y") },
                    secondaryLabel = { Text(if (s.invertY) "Activado" else "No") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item { Text("Gestos", fontSize = 14.sp) }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.update { it.copy(gesturesEnabled = !it.gesturesEnabled) } },
                    label = { Text("Gestos de muñeca") },
                    secondaryLabel = { Text(if (s.gesturesEnabled) "Activados" else "Desactivados") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                SettingSlider("Deslizar tras el toque", s.presSwipeDeg, 8f..40f, 15, { "%.0f°".format(it) }) { v ->
                    Engine.update { it.copy(presSwipeDeg = v) }
                }
            }
            for (g in listOf(Gesture.ROLL_IN, Gesture.ROLL_OUT)) {
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            Engine.update {
                                val all = Action.entries
                                it.withAction(g, all[(all.indexOf(it.actionFor(g)) + 1) % all.size])
                            }
                        },
                        label = { Text(g.label) },
                        secondaryLabel = { Text(s.actionFor(g).label) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
            item {
                SettingSlider("Umbral de toque", s.tapThreshold, 0.8f..5f, 14, { "%.1f m/s²".format(it) }) { v ->
                    Engine.update { it.copy(tapThreshold = v) }
                }
            }
            item {
                SettingSlider("Umbral de giro", s.rollThreshold, 3f..10f, 14, { "%.1f rad/s".format(it) }) { v ->
                    Engine.update { it.copy(rollThreshold = v) }
                }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.update { it.copy(scrollPose = !it.scrollPose) } },
                    label = { Text("Scroll inclinando") },
                    secondaryLabel = { Text(if (s.scrollPose) "Activado" else "Desactivado") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.fixNeutral() },
                    label = { Text("Fijar posición normal") },
                    secondaryLabel = { Text("Mano en reposo, palma hacia adentro") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item { Text("Presentación", fontSize = 14.sp) }
            for (g in Gesture.entries) {
                item {
                    Chip(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            Engine.update {
                                val all = Action.entries
                                it.withPresAction(g, all[(all.indexOf(it.presActionFor(g)) + 1) % all.size])
                            }
                        },
                        label = { Text(g.label) },
                        secondaryLabel = { Text(s.presActionFor(g).label) },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
            }
            item {
                SettingSlider("Apagar láser tras", s.presLaserIdleS.toFloat(), 3f..30f, 26, { "%.0f s".format(it) }) { v ->
                    Engine.update { it.copy(presLaserIdleS = v.toInt()) }
                }
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.update { it.copy(presHaptics = !it.presHaptics) } },
                    label = { Text("Vibrar al hacer gestos") },
                    secondaryLabel = { Text(if (s.presHaptics) "Sí" else "No (solo el temporizador)") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val all = Transport.entries
                        Engine.setTransport(all[(all.indexOf(s.transport) + 1) % all.size])
                    },
                    label = { Text("Conexión") },
                    secondaryLabel = { Text(s.transport.label) },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { BtHidLink.requestDiscoverable(ctx) },
                    label = { Text("Emparejar Bluetooth") },
                    secondaryLabel = { Text("Hace visible el reloj 2 min") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onWizard,
                    label = { Text("Reconfigurar") },
                    secondaryLabel = { Text("Muñeca: ${s.wrist.label}") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
            item {
                Chip(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { Engine.forgetPairing(ctx) },
                    label = { Text("Olvidar emparejamiento") },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }
    }
}

@Composable
internal fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onChange: (Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text("$label: ${format(value)}", fontSize = 12.sp)
        InlineSlider(
            value = value,
            onValueChange = onChange,
            increaseIcon = { Text("+") },
            decreaseIcon = { Text("−") },
            valueRange = range,
            steps = steps,
        )
    }
}

// ====================================================================== Asistente de configuración
@Composable
private fun WizardScreen(onDone: () -> Unit) {
    val st by Engine.wizardState.collectAsState()
    val view = LocalView.current
    val listState = rememberScalingLazyListState()

    DisposableEffect(Unit) {
        Engine.startWizard()
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
            Engine.stopWizard()
        }
    }

    val w = st
    Scaffold(
        timeText = { TimeText() },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) },
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            if (w == null) {
                item { Text("Preparando…", fontSize = 13.sp) }
                return@ScalingLazyColumn
            }
            item { Text("Paso ${w.stepIndex}/${w.stepCount}", fontSize = 11.sp, color = MaterialTheme.colors.primary) }
            item { Text(w.message, fontSize = 13.sp, textAlign = TextAlign.Center) }
            if (w.progress.isNotBlank()) {
                item { Text(w.progress, fontSize = 16.sp, color = Color(0xFF66BB6A), textAlign = TextAlign.Center) }
            }
            w.warning?.let { item { Text(it, fontSize = 11.sp, color = Color(0xFFFFA726), textAlign = TextAlign.Center) } }
            w.summary?.let { lines -> items(lines.size) { Text(lines[it], fontSize = 11.sp, textAlign = TextAlign.Center) } }

            when (w.step) {
                Step.WRIST -> {
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { Engine.wizardSetWrist(Wrist.LEFT) },
                            label = { Text("Izquierda") },
                            colors = ChipDefaults.primaryChipColors(),
                        )
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { Engine.wizardSetWrist(Wrist.RIGHT) },
                            label = { Text("Derecha") },
                            colors = ChipDefaults.primaryChipColors(),
                        )
                    }
                }
                Step.SUMMARY -> {
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { Engine.wizardSave(); onDone() },
                            label = { Text("Guardar") },
                            colors = ChipDefaults.primaryChipColors(),
                        )
                    }
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { Engine.wizardRestart() },
                            label = { Text("Repetir todo") },
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                }
                else -> {
                    item {
                        Chip(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { Engine.wizardRetry() },
                            label = { Text("Reintentar") },
                            colors = ChipDefaults.secondaryChipColors(),
                        )
                    }
                    if (w.canSkip) {
                        item {
                            Chip(
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { Engine.wizardSkip() },
                                label = { Text("Saltar") },
                                colors = ChipDefaults.secondaryChipColors(),
                            )
                        }
                    }
                }
            }
        }
    }
}
