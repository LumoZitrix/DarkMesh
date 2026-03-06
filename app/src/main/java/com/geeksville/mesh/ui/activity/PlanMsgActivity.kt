package com.geeksville.mesh.ui.activity

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.emp3r0r7.darkmesh.R
import com.geeksville.mesh.plannedmessages.data.PlannedMessageDraft
import com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity
import com.geeksville.mesh.plannedmessages.domain.LateFireMode
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageDeliveryPolicy
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageScheduleType
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageSettings
import com.geeksville.mesh.plannedmessages.domain.SchedulerEngine
import com.geeksville.mesh.plannedmessages.domain.WeeklyDays
import com.geeksville.mesh.plannedmessages.ui.PlannedMessageEditorViewModel
import com.geeksville.mesh.service.QuickChatBridge
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

@AndroidEntryPoint
class PlanMsgActivity : AppCompatActivity() {

    private val viewModel: PlannedMessageEditorViewModel by viewModels()
    private val schedulerEngine = SchedulerEngine()

    private lateinit var destinationKey: String
    private lateinit var plannerStatus: TextView
    private lateinit var switchLateFire: SwitchCompat
    private lateinit var spinnerGrace: Spinner
    private lateinit var exactBanner: View
    private lateinit var exactBannerButton: Button
    private lateinit var recyclerRules: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var unsavedView: TextView
    private lateinit var btnAdd: Button
    private lateinit var btnSave: Button
    private lateinit var btnDiscard: Button

    private val rules = mutableListOf<RuleDraftUi>()
    private var persistedSnapshot: List<RuleDraftUi> = emptyList()
    private var hasPendingChanges = false
    private var isSaving = false
    private var exactAlarmAvailable = true
    private var settings = PlannedMessageSettings()
    private var nextLocalId = -1L
    private var suppressSettingsCallbacks = false
    private var settingsUiInitialized = false

    private val adapter by lazy {
        PlanMsgRuleAdapter(
            onRuleClick = { id -> showEditorDialog(id) },
            onRuleActionClick = { anchor, id -> showRuleActions(anchor, id) },
            onRuleEnabledChanged = { id, enabled -> toggleRuleEnabled(id, enabled) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planmsg)

        destinationKey = intent.getStringExtra(NODE_ID_EXTRA_PARAM).orEmpty()
        if (destinationKey.isBlank()) {
            Toast.makeText(this, "Unable to retrieve destination id", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupUi()
        observeViewModel()
        viewModel.bootstrap()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshExactAlarmCapability()
    }

    private fun setupUi() {
        findViewById<TextView>(R.id.txtNodeId).text = "Planning $destinationKey"
        plannerStatus = findViewById(R.id.plannerStatus)
        switchLateFire = findViewById(R.id.switchLateFireWithinGrace)
        spinnerGrace = findViewById(R.id.spinnerGraceWindow)
        exactBanner = findViewById(R.id.exactAlarmBanner)
        exactBannerButton = findViewById(R.id.exactAlarmBannerButton)
        recyclerRules = findViewById(R.id.recyclerRules)
        emptyView = findViewById(R.id.txtRulesEmpty)
        unsavedView = findViewById(R.id.txtUnsavedChanges)
        btnAdd = findViewById(R.id.btnAddRule)
        btnSave = findViewById(R.id.btnSave)
        btnDiscard = findViewById(R.id.btnDiscardChanges)

        spinnerGrace.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            GRACE_WINDOW_OPTIONS_MS.map { "${it / 60_000L} min" },
        )
        switchLateFire.setOnCheckedChangeListener { _, enabled ->
            if (suppressSettingsCallbacks || !settingsUiInitialized) return@setOnCheckedChangeListener
            spinnerGrace.isEnabled = enabled
            viewModel.updateLateFireWithinGrace(enabled, selectedGraceMs())
        }
        spinnerGrace.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSettingsCallbacks || !settingsUiInitialized) return
                viewModel.updateLateFireWithinGrace(switchLateFire.isChecked, selectedGraceMs())
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }

        recyclerRules.layoutManager = LinearLayoutManager(this)
        recyclerRules.adapter = adapter

        btnAdd.setOnClickListener { showEditorDialog(null) }
        btnSave.setOnClickListener {
            if (!hasPendingChanges || isSaving) return@setOnClickListener
            viewModel.saveRules(destinationKey, rules.map { it.toDraft() })
        }
        btnDiscard.setOnClickListener { discardChanges() }
        exactBannerButton.setOnClickListener { openExactAlarmSettings() }
    }

    private fun observeViewModel() {
        viewModel.plannerEnabled.observe(this) { enabled ->
            plannerStatus.text = "Global Planner Status: ${if (enabled) "ON" else "OFF"}"
            plannerStatus.setTextColor(
                getColor(if (enabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark),
            )
        }
        viewModel.settings.observe(this) {
            settings = it
            renderSettings(it)
            recomputeAllRules()
        }
        viewModel.exactAlarmAvailable.observe(this) { canExact ->
            exactAlarmAvailable = canExact
            exactBanner.isVisible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canExact
            refreshRuleList()
        }
        viewModel.observeByDestination(destinationKey).observe(this) { entities ->
            persistedSnapshot = entities.map { RuleDraftUi.fromEntity(it) }
            nextLocalId = minOf(nextLocalId, (entities.minOfOrNull { it.id } ?: 0L) - 1L)
            if (!hasPendingChanges) {
                rules.clear()
                rules += persistedSnapshot
                sortRules()
                refreshRuleList()
            }
        }
        viewModel.saveInProgress.observe(this) { saving ->
            isSaving = saving
            btnSave.isEnabled = hasPendingChanges && !saving
            btnSave.text = if (saving) "Saving..." else "Salva regole"
            btnDiscard.isEnabled = hasPendingChanges && !saving
            btnAdd.isEnabled = !saving
        }
        viewModel.saveSuccess.observe(this) { ok ->
            when (ok) {
                true -> {
                    hasPendingChanges = false
                    Toast.makeText(this, "Regole salvate", Toast.LENGTH_LONG).show()
                    refreshPendingState()
                }

                false -> Toast.makeText(this, "Unable to save planned messages", Toast.LENGTH_LONG).show()
                null -> Unit
            }
            if (ok != null) viewModel.clearSaveState()
        }
    }

    private fun renderSettings(newSettings: PlannedMessageSettings) {
        suppressSettingsCallbacks = true
        val enabled = newSettings.lateFireMode != LateFireMode.SKIP
        switchLateFire.isChecked = enabled
        spinnerGrace.isEnabled = enabled
        val idx = GRACE_WINDOW_OPTIONS_MS.indexOf(newSettings.skipMissedGraceMs).takeIf { it >= 0 } ?: DEFAULT_GRACE_INDEX
        spinnerGrace.setSelection(idx, false)
        suppressSettingsCallbacks = false
        settingsUiInitialized = true
    }

    private fun selectedGraceMs(): Long {
        val idx = spinnerGrace.selectedItemPosition.coerceIn(0, GRACE_WINDOW_OPTIONS_MS.lastIndex)
        return GRACE_WINDOW_OPTIONS_MS[idx]
    }

    private fun showEditorDialog(localId: Long?) {
        val existing = localId?.let { rules.firstOrNull { r -> r.localId == it } }
        var selectedType = existing?.scheduleType ?: PlannedMessageScheduleType.WEEKLY
        var daysMask = existing?.daysOfWeekMask ?: WeeklyDays.maskFor(DayOfWeek.MONDAY)
        var hour = existing?.hourOfDay ?: Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        var minute = existing?.minuteOfHour ?: Calendar.getInstance().get(Calendar.MINUTE)
        val zone = runCatching { ZoneId.of(existing?.timezoneId ?: ZoneId.systemDefault().id) }.getOrDefault(ZoneId.systemDefault())
        var date = existing?.oneShotAtUtcEpochMs?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: LocalDate.now()
        var policy = existing?.deliveryPolicy ?: PlannedMessageDeliveryPolicy.SKIP_MISSED

        val root = LayoutInflater.from(this).inflate(R.layout.dialog_planmsg_rule_editor, null)
        val toggle = root.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.toggleScheduleType)
        val weeklySection = root.findViewById<View>(R.id.weeklySection)
        val dayGroup = root.findViewById<ChipGroup>(R.id.chipGroupDays)
        val quickGroup = root.findViewById<ChipGroup>(R.id.chipGroupQuickMessages)
        val inputMessage = root.findViewById<EditText>(R.id.inputMessage)
        val btnDate = root.findViewById<Button>(R.id.btnDate)
        val btnTime = root.findViewById<Button>(R.id.btnTime)
        val spinnerPolicy = root.findViewById<Spinner>(R.id.spinnerPolicy)
        val switchEnabled = root.findViewById<SwitchCompat>(R.id.switchRuleEnabled)
        val preview = root.findViewById<TextView>(R.id.txtRulePreview)
        val occurrences = root.findViewById<TextView>(R.id.txtRuleOccurrences)

        inputMessage.setText(existing?.messageText.orEmpty())
        switchEnabled.isChecked = existing?.isEnabled ?: true
        spinnerPolicy.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, POLICY_LABELS)
        spinnerPolicy.setSelection(if (policy == PlannedMessageDeliveryPolicy.SKIP_MISSED) 0 else 1, false)

        WeeklyDays.orderedDays.forEach { day ->
            val chip = Chip(this).apply {
                text = DAY_LABELS[day]
                isCheckable = true
                isChecked = WeeklyDays.contains(daysMask, day)
            }
            chip.setOnCheckedChangeListener { _, checked ->
                daysMask = if (checked) daysMask or WeeklyDays.maskFor(day) else daysMask and WeeklyDays.maskFor(day).inv()
                refreshDialogPreview(
                    preview = preview,
                    occurrences = occurrences,
                    message = inputMessage.text.toString(),
                    scheduleType = selectedType,
                    daysMask = daysMask,
                    hour = hour,
                    minute = minute,
                    date = date,
                    zone = zone,
                    policy = policy,
                    enabled = switchEnabled.isChecked,
                )
            }
            dayGroup.addView(chip)
        }

        QuickChatBridge.getQuickChats(applicationContext).take(8).forEach { quick ->
            val chip = Chip(this).apply {
                text = quick.name
            }
            chip.setOnClickListener { inputMessage.setText(quick.message) }
            quickGroup.addView(chip)
        }

        fun updateDateText() {
            btnDate.text = "Date: ${date.format(DATE_FORMAT)}"
        }
        fun updateTimeText() {
            btnTime.text = String.format(Locale.getDefault(), "Time: %02d:%02d", hour, minute)
        }
        fun updateMode() {
            val oneShot = selectedType == PlannedMessageScheduleType.ONE_SHOT
            weeklySection.isVisible = !oneShot
            btnDate.isVisible = oneShot
            refreshDialogPreview(
                preview = preview,
                occurrences = occurrences,
                message = inputMessage.text.toString(),
                scheduleType = selectedType,
                daysMask = daysMask,
                hour = hour,
                minute = minute,
                date = date,
                zone = zone,
                policy = policy,
                enabled = switchEnabled.isChecked,
            )
        }

        updateDateText()
        updateTimeText()
        if (selectedType == PlannedMessageScheduleType.ONE_SHOT) toggle.check(R.id.btnTypeOneShot) else toggle.check(R.id.btnTypeWeekly)
        updateMode()

        toggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedType = if (checkedId == R.id.btnTypeOneShot) PlannedMessageScheduleType.ONE_SHOT else PlannedMessageScheduleType.WEEKLY
            updateMode()
        }
        btnDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    date = LocalDate.of(y, m + 1, d)
                    updateDateText()
                    updateMode()
                },
                date.year,
                date.monthValue - 1,
                date.dayOfMonth,
            ).show()
        }
        btnTime.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, min ->
                    hour = h
                    minute = min
                    updateTimeText()
                    updateMode()
                },
                hour,
                minute,
                true,
            ).show()
        }
        spinnerPolicy.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                policy = if (position == 0) PlannedMessageDeliveryPolicy.SKIP_MISSED else PlannedMessageDeliveryPolicy.CATCH_UP
                updateMode()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        inputMessage.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = updateMode()
        })
        switchEnabled.setOnCheckedChangeListener { _, _ -> updateMode() }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Nuova regola" else "Modifica regola")
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (existing == null) "Aggiungi" else "Aggiorna", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val message = inputMessage.text.toString().trim()
                if (message.isBlank()) {
                    inputMessage.error = "Inserisci un messaggio"
                    return@setOnClickListener
                }
                if (selectedType == PlannedMessageScheduleType.WEEKLY && daysMask == 0) {
                    Toast.makeText(this, "Seleziona almeno un giorno", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val oneShotAt = if (selectedType == PlannedMessageScheduleType.ONE_SHOT) {
                    date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
                } else {
                    null
                }
                val base = RuleDraftUi(
                    localId = existing?.localId ?: allocateLocalId(),
                    databaseId = existing?.databaseId,
                    messageText = message,
                    scheduleType = selectedType,
                    daysOfWeekMask = if (selectedType == PlannedMessageScheduleType.WEEKLY) daysMask else 0,
                    hourOfDay = hour,
                    minuteOfHour = minute,
                    oneShotAtUtcEpochMs = oneShotAt,
                    timezoneId = zone.id,
                    deliveryPolicy = policy,
                    isEnabled = switchEnabled.isChecked,
                    nextTriggerAtUtcEpochMs = existing?.nextTriggerAtUtcEpochMs,
                    hadTimezoneFallback = existing?.hadTimezoneFallback ?: false,
                    attemptCountSinceLastFire = existing?.attemptCountSinceLastFire ?: 0,
                    lastFiredAtUtcEpochMs = existing?.lastFiredAtUtcEpochMs,
                    lastAttemptedAtUtcEpochMs = existing?.lastAttemptedAtUtcEpochMs,
                )
                upsertRule(recomputeRule(base))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun refreshDialogPreview(
        preview: TextView,
        occurrences: TextView,
        message: String,
        scheduleType: PlannedMessageScheduleType,
        daysMask: Int,
        hour: Int,
        minute: Int,
        date: LocalDate,
        zone: ZoneId,
        policy: PlannedMessageDeliveryPolicy,
        enabled: Boolean,
    ) {
        val draft = recomputeRule(
            RuleDraftUi(
                localId = Long.MIN_VALUE,
                databaseId = null,
                messageText = message.trim(),
                scheduleType = scheduleType,
                daysOfWeekMask = daysMask,
                hourOfDay = hour,
                minuteOfHour = minute,
                oneShotAtUtcEpochMs = if (scheduleType == PlannedMessageScheduleType.ONE_SHOT) date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli() else null,
                timezoneId = zone.id,
                deliveryPolicy = policy,
                isEnabled = enabled,
                nextTriggerAtUtcEpochMs = null,
                hadTimezoneFallback = false,
                attemptCountSinceLastFire = 0,
                lastFiredAtUtcEpochMs = null,
                lastAttemptedAtUtcEpochMs = null,
            ),
        )
        preview.text = if (draft.scheduleType == PlannedMessageScheduleType.ONE_SHOT) {
            "Invierà una volta il ${draft.oneShotAtUtcEpochMs?.let { formatDateTime(it) } ?: "n/a"}"
        } else {
            "Invierà ogni ${formatDays(draft.daysOfWeekMask)} alle ${formatTime(draft.hourOfDay, draft.minuteOfHour)}"
        }
        val next = nextOccurrences(draft, 3)
        occurrences.text = if (next.isEmpty()) "Prossime occorrenze: nessuna" else "Prossime occorrenze: ${next.joinToString(" | ") { formatDateTime(it) }}"
    }

    private fun showRuleActions(anchor: View, localId: Long) {
        val rule = rules.firstOrNull { it.localId == localId } ?: return
        PopupMenu(this, anchor).apply {
            inflate(R.menu.menu_planmsg_rule_actions)
            menu.findItem(R.id.action_rule_toggle).title = if (rule.isEnabled) "Disabilita" else "Abilita"
            setOnMenuItemClickListener { handleRuleAction(it, localId) }
            show()
        }
    }
    private fun handleRuleAction(item: MenuItem, localId: Long): Boolean {
        return when (item.itemId) {
            R.id.action_rule_edit -> {
                showEditorDialog(localId)
                true
            }

            R.id.action_rule_duplicate -> {
                val source = rules.firstOrNull { it.localId == localId } ?: return false
                upsertRule(
                    recomputeRule(
                        source.copy(
                            localId = allocateLocalId(),
                            databaseId = null,
                            attemptCountSinceLastFire = 0,
                            lastFiredAtUtcEpochMs = null,
                            lastAttemptedAtUtcEpochMs = null,
                            messageText = "${source.messageText} (copy)",
                        ),
                    ),
                )
                true
            }

            R.id.action_rule_toggle -> {
                val source = rules.firstOrNull { it.localId == localId } ?: return false
                toggleRuleEnabled(localId, !source.isEnabled)
                true
            }

            R.id.action_rule_delete -> {
                confirmDelete(localId)
                true
            }

            else -> false
        }
    }

    private fun confirmDelete(localId: Long) {
        val index = rules.indexOfFirst { it.localId == localId }
        if (index < 0) return
        AlertDialog.Builder(this)
            .setTitle("Elimina regola")
            .setMessage("Vuoi eliminare questa regola?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val removed = rules.removeAt(index)
                markRulesChanged()
                Snackbar.make(findViewById(R.id.planMsgRoot), "Regola eliminata", Snackbar.LENGTH_LONG)
                    .setAction("Undo") {
                        rules.add(index.coerceIn(0, rules.size), removed)
                        markRulesChanged()
                    }
                    .show()
            }
            .show()
    }

    private fun toggleRuleEnabled(localId: Long, enabled: Boolean) {
        val index = rules.indexOfFirst { it.localId == localId }
        if (index < 0) return
        val current = rules[index]
        if (current.isEnabled == enabled) return
        rules[index] = recomputeRule(current.copy(isEnabled = enabled))
        markRulesChanged()
    }

    private fun upsertRule(rule: RuleDraftUi) {
        val index = rules.indexOfFirst { it.localId == rule.localId }
        if (index >= 0) rules[index] = rule else rules += rule
        markRulesChanged()
    }

    private fun markRulesChanged() {
        hasPendingChanges = true
        sortRules()
        refreshRuleList()
        refreshPendingState()
    }

    private fun discardChanges() {
        if (!hasPendingChanges || isSaving) return
        AlertDialog.Builder(this)
            .setTitle("Annulla modifiche")
            .setMessage("Vuoi scartare le modifiche non salvate?")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                hasPendingChanges = false
                rules.clear()
                rules += persistedSnapshot
                sortRules()
                refreshRuleList()
                refreshPendingState()
            }
            .show()
    }

    private fun refreshPendingState() {
        unsavedView.isVisible = hasPendingChanges
        btnSave.isEnabled = hasPendingChanges && !isSaving
        btnDiscard.isEnabled = hasPendingChanges && !isSaving
    }

    private fun refreshRuleList() {
        adapter.submitList(rules.map { it.toCardModel(exactAlarmAvailable) })
        val hasRules = rules.isNotEmpty()
        recyclerRules.isVisible = hasRules
        emptyView.isVisible = !hasRules
    }

    private fun sortRules() {
        rules.sortWith(
            compareBy<RuleDraftUi>(
                { if (it.isEnabled && it.nextTriggerAtUtcEpochMs != null) 0 else if (it.isEnabled) 1 else 2 },
                { it.nextTriggerAtUtcEpochMs ?: Long.MAX_VALUE },
                { it.oneShotAtUtcEpochMs ?: Long.MAX_VALUE },
                { it.localId },
            ),
        )
    }

    private fun recomputeAllRules() {
        if (rules.isEmpty()) return
        for (i in rules.indices) rules[i] = recomputeRule(rules[i])
        sortRules()
        refreshRuleList()
    }

    private fun recomputeRule(rule: RuleDraftUi): RuleDraftUi {
        val resolved = schedulerEngine.initializeForScheduling(rule.toComputationEntity(), System.currentTimeMillis(), settings)
        return rule.copy(
            isEnabled = resolved.isEnabled,
            nextTriggerAtUtcEpochMs = resolved.nextTriggerAtUtcEpochMs,
            hadTimezoneFallback = resolved.hadTimezoneFallback,
        )
    }

    private fun nextOccurrences(rule: RuleDraftUi, maxCount: Int): List<Long> {
        if (!rule.isEnabled || maxCount <= 0) return emptyList()
        val now = System.currentTimeMillis()
        return when (rule.scheduleType) {
            PlannedMessageScheduleType.ONE_SHOT -> listOfNotNull(rule.oneShotAtUtcEpochMs).filter { it >= now }.take(maxCount)
            PlannedMessageScheduleType.WEEKLY -> {
                if (rule.daysOfWeekMask == 0) return emptyList()
                val zone = runCatching { ZoneId.of(rule.timezoneId) }.getOrDefault(ZoneId.systemDefault())
                val time = LocalTime.of(rule.hourOfDay, rule.minuteOfHour)
                val start = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                buildList {
                    for (offset in 0..30) {
                        if (size >= maxCount) break
                        val day = start.plusDays(offset.toLong())
                        if (!WeeklyDays.contains(rule.daysOfWeekMask, day.dayOfWeek)) continue
                        val candidate = LocalDateTime.of(day, time).atZone(zone).toInstant().toEpochMilli()
                        if (candidate >= now) add(candidate)
                    }
                }
            }
        }
    }
    private fun formatDays(mask: Int): String {
        return WeeklyDays.orderedDays
            .filter { WeeklyDays.contains(mask, it) }
            .mapNotNull { DAY_LABELS[it] }
            .ifEmpty { listOf("n/a") }
            .joinToString(", ")
    }

    private fun formatDateTime(epochMs: Long): String {
        return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMAT)
    }

    private fun formatTime(hour: Int, minute: Int): String {
        return String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                },
            )
        }
    }

    private fun allocateLocalId(): Long {
        val id = nextLocalId
        nextLocalId -= 1L
        return id
    }

    private data class RuleDraftUi(
        val localId: Long,
        val databaseId: Long?,
        val messageText: String,
        val scheduleType: PlannedMessageScheduleType,
        val daysOfWeekMask: Int,
        val hourOfDay: Int,
        val minuteOfHour: Int,
        val oneShotAtUtcEpochMs: Long?,
        val timezoneId: String,
        val deliveryPolicy: PlannedMessageDeliveryPolicy,
        val isEnabled: Boolean,
        val nextTriggerAtUtcEpochMs: Long?,
        val hadTimezoneFallback: Boolean,
        val attemptCountSinceLastFire: Int,
        val lastFiredAtUtcEpochMs: Long?,
        val lastAttemptedAtUtcEpochMs: Long?,
    ) {
        fun toDraft(): PlannedMessageDraft {
            return PlannedMessageDraft(
                messageText = messageText,
                scheduleType = scheduleType,
                daysOfWeekMask = daysOfWeekMask,
                hourOfDay = hourOfDay,
                minuteOfHour = minuteOfHour,
                oneShotAtUtcEpochMs = oneShotAtUtcEpochMs,
                timezoneId = timezoneId,
                deliveryPolicy = deliveryPolicy,
                isEnabled = isEnabled,
            )
        }

        fun toComputationEntity(): PlannedMessageEntity {
            return PlannedMessageEntity(
                id = databaseId ?: 0L,
                destinationKey = "",
                messageText = messageText,
                scheduleType = scheduleType,
                daysOfWeekMask = daysOfWeekMask,
                hourOfDay = hourOfDay,
                minuteOfHour = minuteOfHour,
                oneShotAtUtcEpochMs = oneShotAtUtcEpochMs,
                timezoneId = timezoneId,
                deliveryPolicy = deliveryPolicy,
                isEnabled = isEnabled,
                nextTriggerAtUtcEpochMs = nextTriggerAtUtcEpochMs,
                attemptCountSinceLastFire = attemptCountSinceLastFire,
                lastFiredAtUtcEpochMs = lastFiredAtUtcEpochMs,
                lastAttemptedAtUtcEpochMs = lastAttemptedAtUtcEpochMs,
                hadTimezoneFallback = hadTimezoneFallback,
            )
        }

        fun toCardModel(exactAlarmAvailable: Boolean): PlanMsgRuleCardModel {
            fun formatEpoch(epochMs: Long): String {
                return Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(DATE_TIME_FORMAT)
            }
            fun formatMask(mask: Int): String {
                return WeeklyDays.orderedDays
                    .filter { WeeklyDays.contains(mask, it) }
                    .map { day ->
                        when (day) {
                            DayOfWeek.MONDAY -> "Lun"
                            DayOfWeek.TUESDAY -> "Mar"
                            DayOfWeek.WEDNESDAY -> "Mer"
                            DayOfWeek.THURSDAY -> "Gio"
                            DayOfWeek.FRIDAY -> "Ven"
                            DayOfWeek.SATURDAY -> "Sab"
                            DayOfWeek.SUNDAY -> "Dom"
                        }
                    }
                    .ifEmpty { listOf("n/a") }
                    .joinToString(", ")
            }
            val schedule = if (scheduleType == PlannedMessageScheduleType.ONE_SHOT) {
                "One-shot - ${oneShotAtUtcEpochMs?.let { formatEpoch(it) } ?: "n/a"}"
            } else {
                "Weekly - ${formatMask(daysOfWeekMask)} ${String.format(Locale.getDefault(), "%02d:%02d", hourOfDay, minuteOfHour)}"
            }
            val next = when {
                isEnabled && nextTriggerAtUtcEpochMs != null -> "Prossima esecuzione: ${formatEpoch(nextTriggerAtUtcEpochMs)}"
                isEnabled -> "Prossima esecuzione: non disponibile"
                else -> "Regola disabilitata"
            }
            val warns = buildList {
                if (!exactAlarmAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("Precisione ridotta")
                if (hadTimezoneFallback) add("Timezone fallback")
                if (attemptCountSinceLastFire > 0) add("Tentativi: $attemptCountSinceLastFire")
            }.joinToString(" - ").ifBlank { null }
            return PlanMsgRuleCardModel(
                localId = localId,
                messagePreview = messageText,
                scheduleSummary = schedule,
                nextRunSummary = next,
                policySummary = "Policy ritardi: ${if (deliveryPolicy == PlannedMessageDeliveryPolicy.SKIP_MISSED) "Skip missed" else "Catch up"}",
                statusSummary = if (isEnabled) "Stato: Abilitata" else "Stato: Disabilitata",
                warningsSummary = warns,
                isEnabled = isEnabled,
            )
        }

        companion object {
            fun fromEntity(entity: PlannedMessageEntity): RuleDraftUi {
                return RuleDraftUi(
                    localId = entity.id,
                    databaseId = entity.id,
                    messageText = entity.messageText,
                    scheduleType = entity.scheduleType,
                    daysOfWeekMask = entity.daysOfWeekMask,
                    hourOfDay = entity.hourOfDay,
                    minuteOfHour = entity.minuteOfHour,
                    oneShotAtUtcEpochMs = entity.oneShotAtUtcEpochMs,
                    timezoneId = entity.timezoneId,
                    deliveryPolicy = entity.deliveryPolicy,
                    isEnabled = entity.isEnabled,
                    nextTriggerAtUtcEpochMs = entity.nextTriggerAtUtcEpochMs,
                    hadTimezoneFallback = entity.hadTimezoneFallback,
                    attemptCountSinceLastFire = entity.attemptCountSinceLastFire,
                    lastFiredAtUtcEpochMs = entity.lastFiredAtUtcEpochMs,
                    lastAttemptedAtUtcEpochMs = entity.lastAttemptedAtUtcEpochMs,
                )
            }
        }
    }

    companion object {
        const val NODE_ID_EXTRA_PARAM = "nodeId"
        const val BROADCAST_ID_SIG = "^all"
        const val SEPARATOR_DATE_MSG = "-"

        private val GRACE_WINDOW_OPTIONS_MS = PlannedMessageSettings.ALLOWED_GRACE_WINDOWS_MS
        private val DEFAULT_GRACE_INDEX = GRACE_WINDOW_OPTIONS_MS
            .indexOf(PlannedMessageSettings.DEFAULT_SKIP_MISSED_GRACE_MS)
            .takeIf { it >= 0 } ?: 0
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        private val DAY_LABELS = mapOf(
            DayOfWeek.MONDAY to "Lun",
            DayOfWeek.TUESDAY to "Mar",
            DayOfWeek.WEDNESDAY to "Mer",
            DayOfWeek.THURSDAY to "Gio",
            DayOfWeek.FRIDAY to "Ven",
            DayOfWeek.SATURDAY to "Sab",
            DayOfWeek.SUNDAY to "Dom",
        )
        private val POLICY_LABELS = listOf("Skip missed", "Catch up")

    }
}

