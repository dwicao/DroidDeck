package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.input.ControllerPrefs
import com.droiddeck.launcher.input.HardwareButtonMapping
import com.droiddeck.launcher.input.DualSenseManager
import com.droiddeck.launcher.session.SessionPrefs

class ControllerActions(
    val onOsc: (String) -> Unit,
    val onTint: (Int) -> Unit,
    val onOpacity: (Int) -> Unit,
    val onSize: (Int) -> Unit,
    val onStickClick: (Boolean) -> Unit,
    val onAdaptiveSticks: (Boolean) -> Unit,
    val onRumble: (Boolean) -> Unit,
    val onDeadZone: (Int) -> Unit,
    val onRumblePhoneFallback: (Boolean) -> Unit,
    val onSteamButton: (Boolean) -> Unit,
    val onQamButton: (Boolean) -> Unit,
    val onKeyboardButton: (Boolean) -> Unit,
    val onSelectSteam: (Boolean) -> Unit,
    val onJoyConVr: (Boolean) -> Unit,
    val onDualSenseAudio: (Boolean) -> Unit,
    val onDualSenseLightbarMode: (String) -> Unit = {},
    val onDualSensePlayerLeds: (String) -> Unit = {},
    val onDualSensePlayerLedBrightness: (Int) -> Unit = {},
    val onDualSenseMicLedMode: (String) -> Unit = {},
    val onDualSenseTriggerMode: (String) -> Unit = {},
    val onDualSenseLeftTriggerMode: (String) -> Unit = {},
    val onDualSenseRightTriggerMode: (String) -> Unit = {},
    val onDualSenseTouchpad: (Boolean) -> Unit = {},
    val onDualSenseGyroAutoCal: (Boolean) -> Unit = {},
    val onEditLayout: () -> Unit,
    val onResetLayout: () -> Unit,
    val onMapping: () -> Unit,
    val onResetAll: () -> Unit,
    val onTestRumble: () -> Unit = {},
)

@Composable
private fun Swatch(color: Int) {
    Box(Modifier.size(14.dp).background(Color(color), CircleShape).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape))
}

@Composable
fun dualSenseTriggerChoices(): List<Pair<String, String>> = listOf(
    ControllerPrefs.TRIGGER_AUTO to stringResource(R.string.ctrl_dualsense_trigger_auto),
    ControllerPrefs.TRIGGER_FEEDBACK to stringResource(R.string.ctrl_dualsense_trigger_feedback),
    ControllerPrefs.TRIGGER_WEAPON to stringResource(R.string.ctrl_dualsense_trigger_weapon),
    ControllerPrefs.TRIGGER_BOW to stringResource(R.string.ctrl_dualsense_trigger_bow),
    ControllerPrefs.TRIGGER_GALLOPING to stringResource(R.string.ctrl_dualsense_trigger_galloping),
    ControllerPrefs.TRIGGER_VIBRATION to stringResource(R.string.ctrl_dualsense_trigger_vibration),
    ControllerPrefs.TRIGGER_MACHINE to stringResource(R.string.ctrl_dualsense_trigger_machine),
    ControllerPrefs.TRIGGER_SIMPLE_FEEDBACK to stringResource(R.string.ctrl_dualsense_trigger_simple_feedback),
    ControllerPrefs.TRIGGER_SIMPLE_WEAPON to stringResource(R.string.ctrl_dualsense_trigger_simple_weapon),
    ControllerPrefs.TRIGGER_SIMPLE_VIBRATION to stringResource(R.string.ctrl_dualsense_trigger_simple_vibration),
    ControllerPrefs.TRIGGER_LIMITED_FEEDBACK to stringResource(R.string.ctrl_dualsense_trigger_limited_feedback),
    ControllerPrefs.TRIGGER_LIMITED_WEAPON to stringResource(R.string.ctrl_dualsense_trigger_limited_weapon),
    ControllerPrefs.TRIGGER_OFF to stringResource(R.string.perf_cap_off),
)

@Composable
fun dualSensePlayerLedChoices(): List<Pair<String, String>> = listOf(
    ControllerPrefs.PLAYER_LEDS_P1 to stringResource(R.string.ctrl_dualsense_player_leds_p1),
    ControllerPrefs.PLAYER_LEDS_P2 to stringResource(R.string.ctrl_dualsense_player_leds_p2),
    ControllerPrefs.PLAYER_LEDS_P3 to stringResource(R.string.ctrl_dualsense_player_leds_p3),
    ControllerPrefs.PLAYER_LEDS_P4 to stringResource(R.string.ctrl_dualsense_player_leds_p4),
    ControllerPrefs.PLAYER_LEDS_ALL to stringResource(R.string.ctrl_dualsense_player_leds_all),
    ControllerPrefs.PLAYER_LEDS_BATTERY to stringResource(R.string.ctrl_dualsense_player_leds_battery),
    ControllerPrefs.PLAYER_LEDS_OFF to stringResource(R.string.perf_cap_off),
)

@Composable
fun dualSenseMicLedChoices(): List<Pair<String, String>> = listOf(
    ControllerPrefs.MIC_LED_MUTE to stringResource(R.string.ctrl_dualsense_mic_led_mute),
    ControllerPrefs.MIC_LED_ON to stringResource(R.string.ctrl_dualsense_mic_led_on),
    ControllerPrefs.MIC_LED_PULSE to stringResource(R.string.ctrl_dualsense_mic_led_pulse),
    ControllerPrefs.MIC_LED_OFF to stringResource(R.string.perf_cap_off),
)

@Composable
fun ColumnScope.DualSenseControllerRows(host: MenuHost, c: ControllerPrefs.Settings, a: ControllerActions) {
    val status = DualSenseManager.liveStatus
    val statusSubtitle = if (status.connected) {
        stringResource(
            R.string.ctrl_dualsense_status_connected,
            if (status.isEdge) "DualSense Edge" else "DualSense",
            status.transport,
            status.batteryPercent,
            if (status.isCharging) stringResource(R.string.ctrl_dualsense_charging) else "",
            if (status.headphonesPlugged) stringResource(R.string.ctrl_dualsense_headphones) else "",
            if (status.gyroCalibrated) stringResource(R.string.ctrl_dualsense_gyro_cal) else "",
        )
    } else {
        stringResource(R.string.ctrl_dualsense_status_disconnected)
    }
    val triggerChoices = dualSenseTriggerChoices()
    SettingsRow(stringResource(R.string.ctrl_dualsense_status), statusSubtitle) {}
    ChoiceRow(
        host, "dualsense-lightbar", stringResource(R.string.ctrl_dualsense_lightbar), stringResource(R.string.ctrl_dualsense_lightbar_hint),
        listOf(
            ControllerPrefs.LIGHTBAR_TINT to stringResource(R.string.ctrl_dualsense_lightbar_tint),
            ControllerPrefs.LIGHTBAR_BATTERY to stringResource(R.string.ctrl_dualsense_lightbar_battery),
            ControllerPrefs.LIGHTBAR_PS_BLUE to stringResource(R.string.ctrl_dualsense_lightbar_ps_blue),
            ControllerPrefs.LIGHTBAR_OFF to stringResource(R.string.perf_cap_off),
        ),
        c.dualSenseLightbarMode,
        onPick = a.onDualSenseLightbarMode,
    )
    ChoiceRow(
        host, "dualsense-player-leds", stringResource(R.string.ctrl_dualsense_player_leds), null,
        dualSensePlayerLedChoices(),
        c.dualSensePlayerLeds,
        onPick = a.onDualSensePlayerLeds,
    )
    ChoiceRow(
        host, "dualsense-led-brightness", stringResource(R.string.ctrl_dualsense_led_brightness), null,
        listOf(
            0 to stringResource(R.string.ctrl_dualsense_led_high),
            1 to stringResource(R.string.ctrl_dualsense_led_medium),
            2 to stringResource(R.string.ctrl_dualsense_led_low),
        ),
        c.dualSensePlayerLedBrightness,
        onPick = a.onDualSensePlayerLedBrightness,
    )
    ChoiceRow(
        host, "dualsense-mic-led", stringResource(R.string.ctrl_dualsense_mic_led), null,
        dualSenseMicLedChoices(),
        c.dualSenseMicLedMode,
        onPick = a.onDualSenseMicLedMode,
    )
    ChoiceRow(
        host, "dualsense-triggers", stringResource(R.string.ctrl_dualsense_triggers), stringResource(R.string.ctrl_dualsense_triggers_hint),
        triggerChoices,
        c.dualSenseTriggerMode,
        onPick = a.onDualSenseTriggerMode,
    )
    ChoiceRow(
        host, "dualsense-triggers-l2", stringResource(R.string.ctrl_dualsense_triggers_l2), null,
        triggerChoices,
        c.dualSenseLeftTriggerMode,
        onPick = a.onDualSenseLeftTriggerMode,
    )
    ChoiceRow(
        host, "dualsense-triggers-r2", stringResource(R.string.ctrl_dualsense_triggers_r2), null,
        triggerChoices,
        c.dualSenseRightTriggerMode,
        onPick = a.onDualSenseRightTriggerMode,
    )
    ToggleRow(host, "dualsense-touchpad", stringResource(R.string.ctrl_dualsense_touchpad), stringResource(R.string.ctrl_dualsense_touchpad_hint), c.dualSenseTouchpad, onChange = a.onDualSenseTouchpad)
    ToggleRow(host, "dualsense-gyro-autocal", stringResource(R.string.ctrl_dualsense_gyro_autocal), stringResource(R.string.ctrl_dualsense_gyro_autocal_hint), c.dualSenseGyroAutoCal, onChange = a.onDualSenseGyroAutoCal)
    ToggleRow(host, "controller-dualsense-audio", stringResource(R.string.ctrl_dualsense_audio), stringResource(R.string.ctrl_dualsense_audio_hint), c.dualSenseAudio, onChange = a.onDualSenseAudio)
}

@Composable
fun ColumnScope.ControllerRows(host: MenuHost, oscMode: String, c: ControllerPrefs.Settings, a: ControllerActions) {
    ChoiceRow(
        host, "controller-osc", stringResource(R.string.mode_osc), stringResource(R.string.ctrl_osc_hint),
        listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always), SessionPrefs.OSC_STEAM_TOUCH to stringResource(R.string.osc_steam_touch), SessionPrefs.OSC_STEAM_QAM to stringResource(R.string.mode_osc_qam), SessionPrefs.OSC_NEVER to stringResource(R.string.common_never)),
        oscMode, note = stringResource(R.string.mode_osc_note), onPick = a.onOsc,
    )
    val tintOpen = host.open == "controller-tint"
    SettingsRow(stringResource(R.string.ctrl_color), stringResource(R.string.ctrl_color_hint), highlighted = tintOpen) {
        Box {
            ValueChip(
                ControllerPrefs.tints.firstOrNull { it.first == c.tint }?.second?.let { stringResource(it) } ?: stringResource(R.string.ctrl_custom),
                tintOpen,
                modifier = Modifier.paneItem("setting:controller-tint"),
            ) {
                host.open = if (tintOpen) null else "controller-tint"
            }
            AnchoredMenu(tintOpen, onDismiss = { if (host.open == "controller-tint") host.open = null }, title = stringResource(R.string.ctrl_color)) { firstItemFocus ->
                val focusIndex = ControllerPrefs.tints.indexOfFirst { it.first == c.tint }.takeIf { it >= 0 } ?: 0
                ControllerPrefs.tints.forEachIndexed { index, (color, name) ->
                    MenuItem(stringResource(name), checked = c.tint == color, leading = { Swatch(color) }, focusRequester = if (index == focusIndex) firstItemFocus else null) {
                        a.onTint(color)
                        host.open = null
                    }
                }
            }
        }
    }
    ChoiceRow(host, "controller-opacity", stringResource(R.string.ctrl_opacity), null, ControllerPrefs.opacities.map { it to stringResource(R.string.ctrl_percent, it) }, c.opacity, onPick = a.onOpacity)
    ChoiceRow(host, "controller-size", stringResource(R.string.ctrl_size), stringResource(R.string.ctrl_size_hint), ControllerPrefs.sizes.map { it to stringResource(R.string.ctrl_percent, it) }, c.size, onPick = a.onSize)
    ToggleRow(host, "controller-stick-click", stringResource(R.string.ctrl_stick_click), stringResource(R.string.ctrl_stick_click_hint), c.stickClick, onChange = a.onStickClick)
    ToggleRow(host, "controller-adaptive", stringResource(R.string.ctrl_adaptive), stringResource(R.string.ctrl_adaptive_hint), c.adaptiveSticks, onChange = a.onAdaptiveSticks)
    ToggleRow(host, "controller-rumble", stringResource(R.string.ctrl_rumble), null, c.rumble, onChange = a.onRumble)
    ChoiceRow(host, "controller-dead-zone", stringResource(R.string.ctrl_dead_zone), stringResource(R.string.ctrl_dead_zone_hint), ControllerPrefs.deadZones.map { it to stringResource(R.string.ctrl_percent, it) }, c.deadZone, onPick = a.onDeadZone)
    ToggleRow(host, "controller-rumble-phone", stringResource(R.string.ctrl_rumble_phone), stringResource(R.string.ctrl_rumble_phone_hint), c.rumblePhoneFallback, onChange = a.onRumblePhoneFallback)
    ActionRow(stringResource(R.string.ctrl_rumble_test), stringResource(R.string.ctrl_rumble_test_hint), stringResource(R.string.ctrl_rumble_test_button), a.onTestRumble)
    ToggleRow(host, "controller-steam", stringResource(R.string.ctrl_steam_button), null, c.steamButton, onChange = a.onSteamButton)
    ToggleRow(host, "controller-qam", stringResource(R.string.ctrl_qam_button), null, c.qamButton, onChange = a.onQamButton)
    ToggleRow(host, "controller-keyboard", stringResource(R.string.ctrl_keyboard_button), null, c.keyboardButton, onChange = a.onKeyboardButton)
    ToggleRow(host, "controller-select-steam", stringResource(R.string.ctrl_select_steam), stringResource(R.string.ctrl_select_steam_hint), c.selectSteam, onChange = a.onSelectSteam)
    ToggleRow(host, "controller-joycon-vr", stringResource(R.string.ctrl_joycon_vr), stringResource(R.string.ctrl_joycon_vr_hint), c.joyConVr, onChange = a.onJoyConVr)
    DualSenseControllerRows(host, c, a)
    SettingsRow(stringResource(R.string.ctrl_layout), if (c.customLayout) stringResource(R.string.ctrl_layout_custom) else stringResource(R.string.ctrl_layout_default)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton(stringResource(R.string.ctrl_edit)) { a.onEditLayout() }
            SecondaryButton(stringResource(R.string.ctrl_reset), enabled = c.customLayout) { a.onResetLayout() }
        }
    }
    val remapped = c.mapping.count { (id, target) -> id != target }
    ActionRow(stringResource(R.string.ctrl_mapping), if (remapped == 0) stringResource(R.string.ctrl_mapping_default) else stringResource(R.string.ctrl_mapping_count, remapped, c.mapping.size), stringResource(R.string.ctrl_configure), a.onMapping)
    ActionRow(stringResource(R.string.ctrl_reset_all), stringResource(R.string.ctrl_reset_all_hint), stringResource(R.string.ctrl_reset), a.onResetAll)
}

@Composable
fun ControllerMappingPage(
    mapping: Map<String, String>,
    hardwareBindings: List<HardwareButtonMapping.Binding>,
    recording: Boolean,
    onRecord: () -> Unit,
    onHardwarePick: (HardwareButtonMapping.Binding, String) -> Unit,
    onHardwareRemove: (HardwareButtonMapping.Binding) -> Unit,
    onPick: (String, String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val host = rememberMenuHost()
    SettingsPage(
        host, title = stringResource(R.string.ctrl_mapping), eyebrow = stringResource(R.string.setup_controller),
        lede = stringResource(R.string.ctrl_mapping_lede),
        onBack = onBack,
    ) {
        SettingsGroup(stringResource(R.string.shortcuts_title)) {
            PhysicalShortcutRows()
        }
        SettingsGroup(stringResource(R.string.ctrl_hardware_buttons)) {
            ActionRow(
                stringResource(R.string.ctrl_learn_button),
                stringResource(if (recording) R.string.ctrl_press_button else R.string.ctrl_learn_hint),
                stringResource(if (recording) R.string.common_cancel else R.string.ctrl_configure),
                onRecord,
            )
            val hardwareTargets = ControllerPrefs.targets.filter { it.first != ControllerPrefs.OFF }
                .map { (id, label) -> id to stringResource(label) }
            hardwareBindings.forEachIndexed { index, binding ->
                val label = if (binding.motionButton > 0) stringResource(
                    R.string.ctrl_mouse_button, binding.deviceName, binding.motionButton,
                ) else binding.label
                ChoiceRow(host, "hardware-$index", label, null, hardwareTargets, binding.target) {
                    onHardwarePick(binding, it)
                }
                ActionRow(label, null, stringResource(R.string.ctrl_remove_button), onClick = { onHardwareRemove(binding) })
            }
        }
        SettingsGroup(stringResource(R.string.ctrl_onscreen_buttons)) {
            val targets = ControllerPrefs.targets.map { (id, label) -> id to stringResource(label) }
            for ((id, name) in ControllerPrefs.mappable) {
                ChoiceRow(host, "map-$id", stringResource(name), null, targets, mapping[id] ?: id) { onPick(id, it) }
            }
        }
        SettingsGroup(stringResource(R.string.ctrl_defaults)) {
            ActionRow(stringResource(R.string.ctrl_reset_mapping), stringResource(R.string.ctrl_mapping_default), stringResource(R.string.ctrl_reset), onReset)
        }
    }
}

@Composable
fun DualSenseSettingsGroup(host: MenuHost, c: ControllerPrefs.Settings, a: ControllerActions) {
    SettingsGroup(stringResource(R.string.ctrl_dualsense_group)) {
        DualSenseControllerRows(host, c, a)
    }
}

@Composable
fun ControllerMappingPage(
    mapping: Map<String, String>,
    onPick: (String, String) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    ControllerMappingPage(
        mapping = mapping,
        hardwareBindings = emptyList(),
        recording = false,
        onRecord = {},
        onHardwarePick = { _, _ -> },
        onHardwareRemove = {},
        onPick = onPick,
        onReset = onReset,
        onBack = onBack,
    )
}
