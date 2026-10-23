package com.sonycam.monitor.ui

import android.content.Context
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import com.sonycam.monitor.sony.DPC_SONY_MANUAL_FOCUS
import com.sonycam.monitor.sony.SonyCaptureStrategy
import com.sonycam.monitor.sony.SonyLiveViewState
import com.sonycam.monitor.sony.ExposureControl
import com.sonycam.monitor.sony.SonyReadings
import com.sonycam.monitor.sony.buildPickList
import com.sonycam.monitor.sony.canRecordMovie
import com.sonycam.monitor.sony.ShutterReading
import com.sonycam.monitor.sony.shutterReading
import kotlin.math.abs
import kotlin.math.roundToInt

private val MonitorBg = Color(0xFF0A0A0A)
// Translucent: the control bar floats over the live view.
private val PanelBg = Color(0xE6161616)
/** The floating panels (control bar, value and focus wheels) share one rounded shape. */
private val PanelShape = RoundedCornerShape(16.dp)

@Composable
fun MonitorApp(viewModel: MainViewModel, onRetry: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(0xFFFF6E40))) {
        Box(Modifier.fillMaxSize().background(MonitorBg)) {
            if (state.info == null) {
                ConnectPane(state.status, state.error, state.connecting, onRetry,
                    onDiag = { viewModel.exportDiagnostics() })
            } else {
                MonitorScreen(state, viewModel)
            }
        }
    }
}

@Composable
private fun ConnectPane(
    status: String,
    error: String?,
    connecting: Boolean,
    onRetry: () -> Unit,
    onDiag: () -> Unit,
) {
    Column(
        // The PC Remote instructions run to a dozen lines on a phone held in
        // landscape. Without the scroll they push the Connect button off the
        // bottom, which is worse than no instructions at all.
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Sony Monitor", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(status, color = Color(0xFFBBBBBB))
        error?.let {
            Spacer(Modifier.height(8.dp))
            // Left-aligned and monospaced on purpose: these are menu paths whose
            // indentation carries the nesting, and centring every line destroys
            // it. The column's CenterHorizontally would do exactly that.
            Text(
                it,
                color = Color(0xFFFF6E40),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(20.dp))
        if (connecting) CircularProgressIndicator(color = Color(0xFFFF6E40))
        else Button(onClick = onRetry) { Text("Connect") }
        if (error != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Guardar diagnóstico", color = Color(0xFF888888), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onDiag).padding(8.dp),
            )
        }
    }
}

@Composable
private fun MonitorScreen(state: UiState, viewModel: MainViewModel) {
    val readings = state.readings
    val props by viewModel.props.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<ExposureControl?>(null) }
    // The focus drive is not one of the value controls, so it gets its own
    // selection: it is a drive with no position to pick from.
    var focusOpen by remember { mutableStateOf(false) }
    var fineFocus by remember { mutableStateOf(false) }
    val displayPrefs = LocalContext.current.getSharedPreferences("monitor_display", Context.MODE_PRIVATE)
    var peakingLevel by remember {
        mutableStateOf(FocusPeakingLevel.entries.getOrElse(displayPrefs.getInt("focus_peaking", 0)) {
            FocusPeakingLevel.OFF
        })
    }

    var framing by remember {
        mutableStateOf(LiveViewFraming.entries.getOrElse(
            displayPrefs.getInt("live_view_framing", LiveViewFraming.FILL.ordinal),
        ) { LiveViewFraming.FILL })
    }

    var controlsVisible by remember { mutableStateOf(true) }

    // Field-monitor layout: the live view owns the WHOLE screen and the
    // controls float over it; tapping the image hides/shows them.
    Box(Modifier.fillMaxSize()) {
        // ---- Viewfinder (Phase 5), tap to toggle the controls ------------
        val tapToggle = Modifier.fillMaxSize().clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            controlsVisible = !controlsVisible
            if (!controlsVisible) viewModel.stopFocusDrive()
        }
        LiveViewImage(viewModel, state.liveViewState, peakingLevel, framing, tapToggle)

        // Everything but the image stays clear of the front-camera cutout,
        // which the window now draws under. The live view alone uses it.
        //
        // Symmetrically: the cutout is on one short edge only, so insetting by
        // it alone pushed the control bar off-centre — a gap on the cutout side
        // and the REC button running into the curved corner on the other.
        // Both sides take the wider of the two.
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val cutout = WindowInsets.displayCutout
        val sideInset = with(density) {
            maxOf(cutout.getLeft(density, layoutDirection), cutout.getRight(density, layoutDirection)).toDp()
        }
        Box(
            Modifier.fillMaxSize().padding(
                start = sideInset, end = sideInset,
                top = with(density) { cutout.getTop(density).toDp() },
                bottom = with(density) { cutout.getBottom(density).toDp() },
            ),
        ) {

        // ---- Status overlays ---------------------------------------------
        // Hiding the controls clears the picture completely: the model label
        // and the display toggles go with the bar. REC and errors stay — they
        // are the two things a clean picture must not hide.
        if (controlsVisible) state.info?.let {
            val modeLabel = readings?.mode?.takeIf { m -> m != "--" }?.let { m -> "  ·  $m" } ?: ""
            Text(
                it.model + modeLabel, color = Color(0xFFBBBBBB), fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                    .background(Color(0x66000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (controlsVisible) {
            Text(
                framing.label,
                color = Color(0xFFBBBBBB),
                fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000))
                    .clickable {
                        framing = framing.next()
                        displayPrefs.edit().putInt("live_view_framing", framing.ordinal).apply()
                    }.padding(horizontal = 12.dp, vertical = 14.dp),
            )
            if (Build.VERSION.SDK_INT >= 33) {
                Text(
                    peakingLevel.label,
                    color = if (peakingLevel == FocusPeakingLevel.OFF) Color(0xFFBBBBBB) else Color(0xFFFF594F),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x99000000))
                        .clickable {
                            peakingLevel = peakingLevel.next()
                            displayPrefs.edit().putInt("focus_peaking", peakingLevel.ordinal).apply()
                        }.padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
            // Saves the compatibility/diagnostic transcript to Downloads —
            // testers with other camera models send that file for support.
            Text(
                "DIAG", color = Color(0xFF777777), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0x66000000))
                    .clickable { viewModel.exportDiagnostics() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            } // controlsVisible
            if (readings?.recording == true) RecIndicator()
        }

        // Control/capture errors used to be stored in UiState but were only
        // rendered on the disconnected screen. Keep them visible over the
        // monitor so a rejected save destination can never look like a
        // successful PHOTO press.
        //
        // It used to stay until the next PHOTO press, which is the only place
        // that cleared it, so a one-off "wait for the capture" sat over the live
        // view indefinitely. It now leaves by itself, or on a tap.
        state.error?.let { message ->
            LaunchedEffect(message) {
                delay(errorDisplayMillis(message))
                viewModel.dismissError(message)
            }
            Text(
                message,
                color = Color.White,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(horizontal = 28.dp, vertical = 46.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xE68A2A20))
                    .clickable { viewModel.dismissError(message) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }

        val modeRaw = readings?.modeRaw
        val isoAuto = readings?.iso?.startsWith("Auto") == true
        // Whether a control is adjustable comes from the camera's own IsEnabled
        // byte on mode-3 bodies, and from the measured exposure-program tables on
        // mode-2 ones — so an untested model greys the right tiles by itself.
        fun adjustable(ctl: ExposureControl) =
            state.canControl && ctl.editable(props, state.modeVer, modeRaw, isoAuto, state.dialect)
        // Never leave a control selected once the mode stops allowing it.
        if (selected?.let { !adjustable(it) } == true) selected = null
        // Leaving manual focus closes the drive and stops it: the camera would
        // ignore the notch anyway, and a wheel that does nothing is worse than
        // no wheel.
        if (focusOpen && readings?.manualFocus != true) {
            focusOpen = false
            viewModel.stopFocusDrive()
        }

        // ---- Floating controls (bottom), collapsible ---------------------
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Focus drive wheel, in the same slot as the value wheel below it.
            AnimatedVisibility(
                visible = controlsVisible && focusOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                FocusWheel(
                    maxSpeed = FOCUS_WHEEL_MAX_SPEED,
                    active = controlsVisible && focusOpen,
                    fine = fineFocus,
                    onToggleFine = { viewModel.stopFocusDrive(); fineFocus = !fineFocus },
                    onMove = { viewModel.moveFocus(it, fineFocus) },
                    onStop = viewModel::stopFocusDrive,
                    onClose = { focusOpen = false; viewModel.stopFocusDrive() },
                )
            }

            // Jog wheel for the selected setting
            AnimatedVisibility(
                visible = controlsVisible && selected != null && !focusOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                val ctl = selected
                val pick = if (ctl != null) {
                    buildPickList(ctl, props, state.modeVer, state.apertureLimits, state.dialect)
                } else null
                if (ctl != null && pick != null) {
                    // While a seek is walking the camera there (~0.8s per notch),
                    // pin the wheel to the chosen target instead of snapping back
                    // to the not-yet-changed camera value; the tile below shows
                    // the real value catching up.
                    val seeking by viewModel.seeking.collectAsStateWithLifecycle()
                    val pinned = seeking
                        ?.takeIf { it.ctl == ctl && it.propCode == pick.propCode }
                        ?.let { inFlight ->
                            pick.raws.indexOf(inFlight.targetRaw).takeIf { it >= 0 }
                        }
                    JogWheel(
                        label = ctl.label,
                        labels = pick.labels,
                        currentIndex = (pinned ?: pick.index).coerceIn(0, pick.labels.lastIndex),
                        pending = pinned != null && pinned != pick.index,
                        onSeek = { index ->
                            viewModel.seek(ctl, pick.propCode, pick.raws[index])
                        },
                    )
                }
            }

            // Control bar
            AnimatedVisibility(
                visible = controlsVisible && !focusOpen,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                Row(
                    Modifier.fillMaxWidth().clip(PanelShape).background(PanelBg)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Tiles keep a readable width and the strip scrolls when
                    // they stop fitting, instead of every tile shrinking until
                    // "f/4.5" breaks across two lines — which is what nine of
                    // them sharing the width evenly did. PHOTO and REC stay
                    // outside the scroll so they are always under the thumb.
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (ctl in ExposureControl.entries) {
                            if (ctl == ExposureControl.EV || ctl == ExposureControl.WB_GM ||
                                ctl == ExposureControl.WB_AB) continue
                            val editable = adjustable(ctl)
                            Tile(
                                label = ctl.label,
                                value = readings?.let { readingFor(ctl, it) } ?: "--",
                                selected = selected == ctl,
                                enabled = editable,
                                hint = if (!editable) ctl.lockReason(modeRaw) else null,
                                modifier = Modifier.width(104.dp),
                            ) {
                                if (editable) {
                                    if (focusOpen) {
                                        focusOpen = false
                                        viewModel.stopFocusDrive()
                                    }
                                    selected = if (selected == ctl) null else ctl
                                }
                            }
                            // The drive belongs beside the focus mode, not at
                            // the far end of a strip that has to be scrolled to
                            // reach — it is touched far more often than the
                            // white-balance fine tuning after it.
                            if (ctl == ExposureControl.FOCUS) {
                        // The drive is a control the camera offers, so it earns a
                        // tile like everything else — but only where it can do
                        // anything: the body has to advertise 0xD2D1 *and* the
                        // ring has to be the one in charge. In AF the camera
                        // ignores the notch.
                        val canFocus = readings?.manualFocus == true &&
                            props.containsKey(DPC_SONY_MANUAL_FOCUS)
                        Tile(
                            label = "FOCO",
                            value = if (canFocus) "◀▶" else "--",
                            selected = focusOpen,
                            enabled = canFocus,
                            hint = if (!canFocus) "sólo en MF" else null,
                            modifier = Modifier.width(104.dp),
                        ) {
                            if (canFocus) {
                                focusOpen = !focusOpen
                                if (focusOpen) selected = null else viewModel.stopFocusDrive()
                            }
                        }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    val canPhoto = state.captureStrategy != SonyCaptureStrategy.UNSUPPORTED
                    // BULB is the one exposure the camera does not time itself,
                    // so the button becomes open/close and shows the seconds
                    // running. Every other shutter keeps the plain PHOTO press.
                    val bulbSince by viewModel.bulbOpenSince.collectAsStateWithLifecycle()
                    // Only a real BULB report arms the button. An unreadable
                    // shutter (0xFFFFFFFF, which an A7 III emits while warming
                    // up) must not offer to hold the shutter open.
                    val shutterProp = ExposureControl.SHUTTER.effectiveProp(props, state.modeVer, state.dialect)
                    val bulbMode = shutterProp?.currentValue
                        ?.let { shutterReading(it) == ShutterReading.Bulb } == true
                    var bulbElapsed by remember { mutableIntStateOf(0) }
                    LaunchedEffect(bulbSince) {
                        val since = bulbSince
                        bulbElapsed = 0
                        while (since != null) {
                            bulbElapsed = ((System.currentTimeMillis() - since) / 1000).toInt()
                            delay(200)
                        }
                    }
                    ActionButton(
                        text = when {
                            bulbSince != null -> "CERRAR\n%d:%02d".format(bulbElapsed / 60, bulbElapsed % 60)
                            !canPhoto -> "PHOTO\nNO DISP."
                            bulbMode -> "BULB\nABRIR"
                            else -> "PHOTO"
                        },
                        bg = if (bulbSince != null) Color(0xFFB00020) else Color(0xFF2A2A2A),
                        enabled = canPhoto,
                    ) {
                        if (bulbMode || bulbSince != null) viewModel.toggleBulb()
                        else viewModel.capturePhoto()
                    }
                    // Whether a still mode records over PTP is a camera setting,
                    // not a protocol fact: an A7 III with "MOVIE button: Always"
                    // records from M, and with "Movie Mode Only" it ACKs and
                    // ignores. Guessing here left the button dead for people who
                    // could record, so the advertised control is what arms it and
                    // MainViewModel reports what the camera actually did.
                    val canRec = state.canMovieRecord
                    val stillMode = state.modeVer == 2 && !canRecordMovie(modeRaw)
                    ActionButton(
                        text = when {
                            readings?.recording == true -> "STOP"
                            canRec -> "REC"
                            else -> "REC\nNO DISP."
                        },
                        bg = when {
                            readings?.recording == true -> Color(0xFFB00020)
                            canRec && !stillMode -> Color(0xFF7A1620)
                            canRec -> Color(0xFF4A1218)
                            else -> Color(0xFF2A2A2A)
                        },
                        enabled = canRec || readings?.recording == true,
                    ) { viewModel.toggleVideo() }
                }
            }

            // Grab handle so a hidden bar is discoverable
            if (!controlsVisible) {
                Box(
                    Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                        .size(width = 48.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x66FFFFFF)),
                )
            }
        }
        } // cutout-safe overlays
    }
}

/**
 * How long the monitor's error banner stays up. Long enough to read: the
 * save-destination help is a whole menu path, "wait for the capture" is five
 * words. Bounded so that nothing covers the live view for long.
 */
internal fun errorDisplayMillis(message: String): Long =
    (ERROR_BANNER_BASE_MS + message.length * ERROR_BANNER_PER_CHAR_MS)
        .coerceIn(ERROR_BANNER_MIN_MS, ERROR_BANNER_MAX_MS)

private const val ERROR_BANNER_BASE_MS = 2_000L
private const val ERROR_BANNER_PER_CHAR_MS = 60L
private const val ERROR_BANNER_MIN_MS = 4_000L
private const val ERROR_BANNER_MAX_MS = 15_000L

/** Frame arrivals invalidate only the image, independently of the moving wheel. */
@Composable
private fun LiveViewImage(viewModel: MainViewModel, state: SonyLiveViewState,
                          peakingLevel: FocusPeakingLevel, framing: LiveViewFraming,
                          modifier: Modifier) {
    val frame by viewModel.frame.collectAsStateWithLifecycle()
    val bitmap = frame
    if (bitmap != null) {
        FocusPeakingImage(bitmap, peakingLevel, framing, modifier)
    } else {
        Box(modifier) {
            val label = when (state) {
                SonyLiveViewState.UNSUPPORTED -> "LIVE VIEW\nNO COMPATIBLE"
                SonyLiveViewState.UNVERIFIED -> "LIVE VIEW\nCOMPROBANDO…"
                SonyLiveViewState.DISABLED -> "LIVE VIEW\nNO DISPONIBLE"
                SonyLiveViewState.AVAILABLE -> "LIVE VIEW\nESPERANDO IMAGEN"
            }
            Text(
                label, color = Color(0xFF2A2A2A), fontSize = 32.sp,
                fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center),
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun readingFor(ctl: ExposureControl, r: SonyReadings): String = when (ctl) {
    ExposureControl.ISO -> r.iso
    ExposureControl.APERTURE -> r.aperture
    ExposureControl.SHUTTER -> r.shutter
    ExposureControl.EV -> r.exposureComp
    ExposureControl.WB -> r.whiteBalance
    ExposureControl.TEMP -> r.colorTemp
    ExposureControl.WB_GM -> r.wbShiftGm
    ExposureControl.WB_AB -> r.wbShiftAb
    ExposureControl.FOCUS -> r.focusMode
}

@Composable
private fun Tile(
    label: String,
    value: String,
    selected: Boolean,
    enabled: Boolean,
    hint: String?,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val labelColor = if (enabled) MaterialTheme.colorScheme.primary else Color(0xFF5A5A5A)
    val valueColor = if (enabled) Color.White else Color(0xFF5A5A5A)
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFF241611) else Color(0xFF1E1E1E))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 10.dp),
    ) {
        Text(label, color = labelColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Text(value, color = valueColor, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace)
        if (hint != null) {
            Text(hint, color = Color(0xFF6A6A6A), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun ActionButton(text: String, bg: Color, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, color = if (enabled) Color.White else Color(0xFF6A6A6A),
            fontWeight = FontWeight.Bold, fontSize = if (text.contains('\n')) 10.sp else 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun RecIndicator(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFF3B30)))
        Text("REC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/**
 * Animated value wheel. The real values scroll horizontally under a fixed center
 * caret; the big number changes live as you drag (before the camera responds).
 * On release, [onSeek] drives the camera to the chosen index (closed-loop, so it
 * copes with the A7 III needing several relative steps per displayed value).
 */
@Composable
private fun JogWheel(
    label: String,
    labels: List<String>,
    currentIndex: Int,
    pending: Boolean,
    onSeek: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()

    // Styles live out here because the spacing below is measured from them.
    val baseStyle = TextStyle(fontSize = 15.sp, fontFamily = FontFamily.Monospace, color = Color.White)
    val hiStyle = TextStyle(fontSize = 19.sp, fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold, color = accent)

    // Spacing between values has to clear the **widest label in this list**, not
    // a constant. A fixed 92dp pitch was fine while every wheel held something
    // like "102400", "f/4.5" or "1/8000"; white balance brought "Fluor. luz día"
    // and "Incandescente", which are wider than the slot, so neighbours were
    // drawn on top of each other and none of them could be read.
    //
    // Measured in the selected style, which is the larger of the two, so the
    // highlighted entry cannot outgrow its own slot either.
    val minSlotPx = with(LocalDensity.current) { 92.dp.toPx() }
    val gutterPx = with(LocalDensity.current) { 24.dp.toPx() }
    val slotPx = remember(labels, hiStyle, minSlotPx, gutterPx) {
        val widest = labels.maxOfOrNull { measurer.measure(it, hiStyle).size.width.toFloat() } ?: 0f
        maxOf(minSlotPx, widest + gutterPx)
    }
    // Drag distance per notch. Decoupled from the visual spacing: at 92dp/notch a
    // short flick rounded to 0 notches and the wheel "snapped back" doing nothing.
    val dragPerNotchPx = with(LocalDensity.current) { 40.dp.toPx() }
    val lastIdx = labels.lastIndex.coerceAtLeast(0)

    // pointerInput runs in a long-lived coroutine. Capturing currentIndex/onSeek
    // directly would freeze the values from the composition that created it:
    // after ISO 100 -> 2000, the next gesture could therefore start at 100.
    val latestCurrentIndex by rememberUpdatedState(currentIndex)
    val latestOnSeek by rememberUpdatedState(onSeek)

    var dragging by remember(label, labels) { mutableStateOf(false) }
    var baseIndex by remember(label, labels) { mutableIntStateOf(currentIndex) }
    var scroll by remember(label, labels) { mutableFloatStateOf(0f) } // in slot units

    // Follow the camera's reported value whenever we're not actively dragging.
    LaunchedEffect(label, currentIndex, dragging) {
        if (!dragging) { baseIndex = currentIndex; scroll = 0f }
    }

    // When settled, currentIndex is authoritative immediately. Do not wait one
    // composition frame for LaunchedEffect to copy it into remembered state.
    val selF = wheelSelectionPosition(currentIndex, dragging, baseIndex, scroll, lastIdx)
    val sel = selF.roundToInt().coerceIn(0, lastIdx)

    Column(
        Modifier.fillMaxWidth().clip(PanelShape).background(Color(0xE60E0E0E)).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        // Dimmed while the camera is still walking toward the pinned target.
        Text(labels.getOrElse(sel) { "--" },
            color = if (pending && !dragging) Color(0xFFAAAAAA) else Color.White,
            fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier.fillMaxWidth().height(56.dp).pointerInput(label, lastIdx) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        dragging = true
                        baseIndex = latestCurrentIndex.coerceIn(0, lastIdx)
                        scroll = 0f
                    },
                    onDragEnd = {
                        val target = (baseIndex + scroll).roundToInt().coerceIn(0, lastIdx)
                        dragging = false; scroll = 0f
                        latestOnSeek(target)
                    },
                    onDragCancel = { dragging = false; scroll = 0f },
                ) { _, dragAmount ->
                    val before = (baseIndex + scroll).roundToInt()
                    // drag left -> higher values, clamped to the list bounds
                    scroll = (scroll - dragAmount / dragPerNotchPx)
                        .coerceIn(-baseIndex.toFloat(), (lastIdx - baseIndex).toFloat())
                    if ((baseIndex + scroll).roundToInt() != before) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                for (i in (selF.toInt() - 4)..(selF.toInt() + 4)) {
                    if (i < 0 || i > lastIdx) continue
                    val x = cx + (i - selF) * slotPx
                    if (x < -slotPx || x > size.width + slotPx) continue
                    val layout = measurer.measure(labels[i], if (i == sel) hiStyle else baseStyle)
                    val alpha = (1f - abs(i - selF) / 4f).coerceIn(0.12f, 1f)
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, cy - layout.size.height / 2f),
                        alpha = alpha)
                }
                drawLine(accent, Offset(cx, 4f), Offset(cx, size.height - 4f), 3f)
            }
        }
    }
}

/** Pure wheel-state reducer, kept testable without an Android/Compose runtime. */
internal fun wheelSelectionPosition(
    currentIndex: Int,
    dragging: Boolean,
    dragBaseIndex: Int,
    dragOffset: Float,
    lastIndex: Int,
): Float {
    val max = lastIndex.coerceAtLeast(0)
    return if (dragging) {
        (dragBaseIndex + dragOffset).coerceIn(0f, max.toFloat())
    } else {
        currentIndex.coerceIn(0, max).toFloat()
    }
}

/**
 * Relative focus wheel. Its marks follow the finger and stay put on release;
 * they do not represent an absolute lens position (0xD2D1 is only a drive).
 * Only finger movement produces lens nudges. A stationary finger lets the user
 * inspect the live image without releasing; reversing takes effect immediately.
 */
private const val FOCUS_WHEEL_MAX_SPEED = 7

@Composable
private fun FocusWheel(
    maxSpeed: Int,
    active: Boolean,
    fine: Boolean,
    onToggleFine: () -> Unit,
    onMove: (Float) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val accent = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current.density
    var wheel by remember { mutableStateOf(FocusWheelState()) }
    val speed = wheel.speed
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnStop by rememberUpdatedState(onStop)

    Row(
        Modifier.fillMaxWidth().height(64.dp).clip(PanelShape).background(Color(0xC00E0E0E))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.width(88.dp)) {
            Text("FOCO", color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Text(
                when {
                    speed > 0 -> "LEJOS"
                    speed < 0 -> "CERCA"
                    else -> "Arrastra"
                },
                color = if (speed == 0) Color(0xFFAAAAAA) else Color.White,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
        Box(
            Modifier.weight(1f).height(48.dp).pointerInput(active, maxSpeed, density, fine) {
                // AnimatedVisibility retains this node during its exit. Disable
                // input as soon as it starts hiding, and cancel an ongoing drag.
                if (!active) return@pointerInput
                try {
                    // This dedicated strip has no competing scroll gesture.
                    // Start on the first movement, without the drag touch-slop.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        wheel = wheel.stop()
                        val gesture = FocusGestureFilter()
                        try {
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!change.pressed || change.isConsumed) break
                                val rawDelta = (change.position.x - change.previousPosition.x) / density
                                change.consume()
                                // The label, marks and camera all use the same
                                // accepted motion; noise cannot flip just one.
                                val delta = gesture.move(rawDelta)
                                if (delta == 0f) continue
                                val previousSpeed = wheel.speed
                                wheel = wheel.drag(delta, maxSpeed)
                                if ((wheel.speed > 0) != (previousSpeed > 0) || previousSpeed == 0) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                latestOnMove(delta)
                            }
                        } finally {
                            wheel = wheel.stop()
                            latestOnStop()
                        }
                    }
                } finally {
                    // Disposing/restarting pointerInput cancels its coroutine;
                    // the detector need not call onDragCancel for that case.
                    wheel = wheel.stop()
                    latestOnStop()
                }
            },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val tick = 16.dp.toPx()
                val phase = wheel.phaseDp.dp.toPx()
                val count = (size.width / tick).toInt() + 6
                val edge = 52.dp.toPx()
                for (i in -count..count) {
                    val x = cx + i * tick + phase
                    if (x < edge || x > size.width - edge) continue
                    val h = if (i % 5 == 0) 12.dp.toPx() else 7.dp.toPx()
                    drawLine(
                        if (speed != 0) accent.copy(alpha = 0.75f) else Color(0xFF777777),
                        Offset(x, cy - h), Offset(x, cy + h), 1.dp.toPx(),
                    )
                }
                val ends = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Color(0xFFAAAAAA))
                val near = measurer.measure("CERCA", ends)
                drawText(near, topLeft = Offset(8f, cy - near.size.height / 2f))
                val far = measurer.measure("LEJOS", ends)
                drawText(far, topLeft = Offset(size.width - far.size.width - 8f, cy - far.size.height / 2f))
                // Fixed caret over a rotating strip, with no spring-back offset.
                drawLine(accent, Offset(cx, 2f), Offset(cx, size.height - 2f), 3f)
            }
        }
        Box(
            Modifier.size(width = 60.dp, height = 48.dp).clip(RoundedCornerShape(8.dp))
                .background(if (fine) Color(0xFF292929) else accent.copy(alpha = 0.18f))
                .clickable(onClick = onToggleFine),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (fine) "Fino" else "Normal", color = if (fine) Color.White else accent,
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        Box(
            Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF292929)).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Text("Listo", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
