package com.geeksville.mesh.ui.activity

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.emp3r0r7.darkmesh.R
import com.geeksville.mesh.database.entity.NodeEntity
import com.geeksville.mesh.database.entity.QuickChatAction
import com.geeksville.mesh.plannedmessages.data.PlannedMessageDraft
import com.geeksville.mesh.plannedmessages.domain.LateFireMode
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageDeliveryPolicy
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageSettings
import com.geeksville.mesh.plannedmessages.domain.PlannedMessageScheduleType
import com.geeksville.mesh.plannedmessages.domain.WeeklyDays
import com.geeksville.mesh.plannedmessages.ui.PlannedMessageEditorViewModel
import com.geeksville.mesh.service.MeshService
import com.geeksville.mesh.service.QuickChatBridge
import dagger.hilt.android.AndroidEntryPoint
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@AndroidEntryPoint
class PlanMsgActivity : AppCompatActivity() {

    private val viewModel: PlannedMessageEditorViewModel by viewModels()
    private var meshService: MeshService? = null

    private lateinit var currentDestinationKey: String
    private var currentNodeName: String = ""
    private var broadcastChannel: Int? = null

    private lateinit var spinnerScheduleType: Spinner
    private lateinit var spinnerDay: Spinner
    private lateinit var spinnerPolicy: Spinner
    private lateinit var spinnerGraceWindow: Spinner
    private lateinit var quickMessagesSpinner: NDSpinner
    private lateinit var switchLateFireWithinGrace: SwitchCompat
    private lateinit var btnDate: Button
    private lateinit var btnTime: Button
    private lateinit var btnAdd: Button
    private lateinit var btnSave: Button
    private lateinit var inputMessage: EditText
    private lateinit var listRules: ListView

    private var selectedHour = -1
    private var selectedMinute = -1
    private var selectedDate: LocalDate = LocalDate.now()
    private var suppressDelayPrecisionCallbacks = false
    private var delayPrecisionInitialized = false
    private val rules = mutableListOf<RuleDraftUi>()
    private val rulesAdapter by lazy {
        ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planmsg)

        currentDestinationKey = intent.getStringExtra(NODE_ID_EXTRA_PARAM).orEmpty()
        if (currentDestinationKey.isBlank() || currentDestinationKey.contains("Unknown Channel")) {
            Toast.makeText(this, "Unable to retrieve destination id", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        currentNodeName = currentDestinationKey

        parseDestinationMetadata()
        bindMeshService()
        setupUi()
        observeViewModel()
        viewModel.bootstrap()
    }

    private fun parseDestinationMetadata() {
        if (!currentDestinationKey.contains(BROADCAST_ID_SIG)) return
        val split = currentDestinationKey.split("^")
        if (split.size >= 3) {
            broadcastChannel = split[0].toIntOrNull()
            currentNodeName = split[2]
        }
    }

    @SuppressLint("SetTextI18n")
    private fun setupUi() {
        spinnerScheduleType = findViewById(R.id.spinnerScheduleType)
        spinnerDay = findViewById(R.id.spinnerDay)
        spinnerPolicy = findViewById(R.id.spinnerPolicy)
        spinnerGraceWindow = findViewById(R.id.spinnerGraceWindow)
        quickMessagesSpinner = findViewById(R.id.quick_messages)
        switchLateFireWithinGrace = findViewById(R.id.switchLateFireWithinGrace)
        btnDate = findViewById(R.id.btnDate)
        btnTime = findViewById(R.id.btnTime)
        btnAdd = findViewById(R.id.btnAdd)
        btnSave = findViewById(R.id.btnSave)
        inputMessage = findViewById(R.id.inputMessage)
        listRules = findViewById(R.id.listRules)

        spinnerScheduleType.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            SCHEDULE_LABELS,
        )
        spinnerDay.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DAYS,
        )
        spinnerPolicy.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            POLICY_LABELS,
        )
        spinnerGraceWindow.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            GRACE_WINDOW_LABELS,
        )

        listRules.adapter = rulesAdapter
        listRules.setOnItemClickListener { _, _, position, _ ->
            if (position in rules.indices) {
                rules.removeAt(position)
                refreshRulesList()
            }
        }
        setupQuickMessagesSpinner()

        spinnerScheduleType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val isOneShot = selectedScheduleType() == PlannedMessageScheduleType.ONE_SHOT
                btnDate.isVisible = isOneShot
                spinnerDay.isVisible = !isOneShot
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        switchLateFireWithinGrace.setOnCheckedChangeListener { _, enabled ->
            if (suppressDelayPrecisionCallbacks || !delayPrecisionInitialized) return@setOnCheckedChangeListener
            spinnerGraceWindow.isEnabled = enabled
            viewModel.updateLateFireWithinGrace(
                enabled = enabled,
                graceMs = selectedGraceWindowMs(),
            )
        }
        spinnerGraceWindow.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressDelayPrecisionCallbacks || !delayPrecisionInitialized) return
                viewModel.updateLateFireWithinGrace(
                    enabled = switchLateFireWithinGrace.isChecked,
                    graceMs = selectedGraceWindowMs(),
                )
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        btnDate.setOnClickListener {
            DatePickerDialog(
                this,
                { _, year, month, dayOfMonth ->
                    selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                    btnDate.text = "Date: ${selectedDate.format(DATE_FORMATTER)}"
                },
                selectedDate.year,
                selectedDate.monthValue - 1,
                selectedDate.dayOfMonth,
            ).show()
        }

        btnTime.setOnClickListener {
            val now = Calendar.getInstance()
            val hour = if (selectedHour >= 0) selectedHour else now.get(Calendar.HOUR_OF_DAY)
            val minute = if (selectedMinute >= 0) selectedMinute else now.get(Calendar.MINUTE)
            TimePickerDialog(
                this,
                { _, hourOfDay, minuteOfHour ->
                    selectedHour = hourOfDay
                    selectedMinute = minuteOfHour
                    btnTime.text = String.format(Locale.getDefault(), "Time: %02d:%02d", hourOfDay, minuteOfHour)
                },
                hour,
                minute,
                true,
            ).show()
        }

        btnAdd.setOnClickListener { addRuleFromInputs() }
        btnSave.setOnClickListener {
            viewModel.saveRules(
                currentDestinationKey,
                rules.map { it.toDraft() },
            )
        }
        findViewById<TextView>(R.id.txtNodeId).text =
            "Planning ${if (broadcastChannel != null) "Chan" else "Node"} $currentNodeName"
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        val statusView = findViewById<TextView>(R.id.plannerStatus)
        viewModel.plannerEnabled.observe(this) { enabled ->
            statusView.text = "Global Planner Status: ${if (enabled) "ON" else "OFF"}"
            statusView.setTextColor(getColor(if (enabled) android.R.color.holo_green_dark else android.R.color.holo_red_dark))
        }
        viewModel.settings.observe(this) { settings ->
            renderDelayPrecisionSettings(settings)
        }

        viewModel.observeByDestination(currentDestinationKey).observe(this) { entities ->
            rules.clear()
            rules += entities.map { RuleDraftUi.fromEntity(it) }
            refreshRulesList()
        }

        viewModel.saveSuccess.observe(this) { success ->
            when (success) {
                true -> Toast.makeText(this, "Plan saved successfully for $currentNodeName", Toast.LENGTH_LONG).show()
                false -> Toast.makeText(this, "Unable to save planned messages", Toast.LENGTH_LONG).show()
                null -> Unit
            }
            if (success != null) {
                viewModel.clearSaveState()
            }
        }
    }

    private fun renderDelayPrecisionSettings(settings: PlannedMessageSettings) {
        suppressDelayPrecisionCallbacks = true
        val enabled = settings.lateFireMode != LateFireMode.SKIP
        switchLateFireWithinGrace.isChecked = enabled
        spinnerGraceWindow.isEnabled = enabled
        val selectedIndex = GRACE_WINDOW_OPTIONS_MS.indexOf(settings.skipMissedGraceMs)
            .takeIf { it >= 0 }
            ?: DEFAULT_GRACE_INDEX
        spinnerGraceWindow.setSelection(selectedIndex, false)
        suppressDelayPrecisionCallbacks = false
        delayPrecisionInitialized = true
    }

    private fun refreshRulesList() {
        rulesAdapter.clear()
        rulesAdapter.addAll(rules.map { it.toDisplayString() })
        rulesAdapter.notifyDataSetChanged()
    }

    private fun addRuleFromInputs() {
        if (selectedHour < 0 || selectedMinute < 0) {
            Toast.makeText(this, "Select a time", Toast.LENGTH_SHORT).show()
            return
        }
        val message = inputMessage.text.toString().trim()
        if (message.isEmpty()) {
            Toast.makeText(this, "Add a message", Toast.LENGTH_SHORT).show()
            return
        }

        val scheduleType = selectedScheduleType()
        val zoneId = ZoneId.systemDefault().id
        val policy = selectedDeliveryPolicy()
        val rule = if (scheduleType == PlannedMessageScheduleType.ONE_SHOT) {
            RuleDraftUi(
                messageText = message,
                scheduleType = scheduleType,
                daysOfWeekMask = 0,
                hourOfDay = selectedHour,
                minuteOfHour = selectedMinute,
                oneShotAtUtcEpochMs = selectedDate
                    .atTime(selectedHour, selectedMinute)
                    .atZone(ZoneId.of(zoneId))
                    .toInstant()
                    .toEpochMilli(),
                timezoneId = zoneId,
                deliveryPolicy = policy,
            )
        } else {
            RuleDraftUi(
                messageText = message,
                scheduleType = scheduleType,
                daysOfWeekMask = WeeklyDays.maskFor(DAY_OF_WEEK_FROM_INDEX[spinnerDay.selectedItemPosition]),
                hourOfDay = selectedHour,
                minuteOfHour = selectedMinute,
                oneShotAtUtcEpochMs = null,
                timezoneId = zoneId,
                deliveryPolicy = policy,
            )
        }

        rules += rule
        inputMessage.setText("")
        refreshRulesList()
    }

    private fun selectedScheduleType(): PlannedMessageScheduleType {
        return if (spinnerScheduleType.selectedItemPosition == 0) {
            PlannedMessageScheduleType.WEEKLY
        } else {
            PlannedMessageScheduleType.ONE_SHOT
        }
    }

    private fun selectedDeliveryPolicy(): PlannedMessageDeliveryPolicy {
        return if (spinnerPolicy.selectedItemPosition == 0) {
            PlannedMessageDeliveryPolicy.SKIP_MISSED
        } else {
            PlannedMessageDeliveryPolicy.CATCH_UP
        }
    }

    private fun selectedGraceWindowMs(): Long {
        val index = spinnerGraceWindow.selectedItemPosition
            .coerceIn(0, GRACE_WINDOW_OPTIONS_MS.lastIndex)
        return GRACE_WINDOW_OPTIONS_MS[index]
    }

    @SuppressLint("SetTextI18n")
    private fun bindMeshService() {
        val intent = Intent(this, MeshService::class.java).apply {
            action = MeshService.BIND_LOCAL_ACTION_INTENT
        }
        bindService(intent, meshServiceConnection, Context.BIND_AUTO_CREATE)
    }

    private val meshServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, service: IBinder) {
            val accessor = service as? MeshService.MeshServiceAccessor ?: return
            meshService = accessor.getService()
            resolveCurrentNodeName()
            val description = "Planning ${if (broadcastChannel != null) "Chan" else "Node"} $currentNodeName"
            findViewById<TextView>(R.id.txtNodeId).text = description
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            meshService = null
        }
    }

    private fun resolveCurrentNodeName() {
        val nodeMap: ConcurrentHashMap<Int, NodeEntity> = meshService?.nodeDBbyNodeNum ?: return
        val node = nodeMap[currentDestinationKey.toIntOrNull() ?: return]
        if (!node?.user?.longName.isNullOrBlank()) {
            currentNodeName = node?.user?.longName ?: currentNodeName
        }
    }

    private fun setupQuickMessagesSpinner() {
        val quickChats: List<QuickChatAction> = QuickChatBridge.getQuickChats(applicationContext)
        val displayStrings = mutableListOf("Select a quick chat command...")
        val messages = mutableListOf("")
        quickChats.forEach { action ->
            displayStrings += "${action.name}: ${action.message}"
            messages += action.message
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            displayStrings,
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        quickMessagesSpinner.adapter = adapter
        quickMessagesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    inputMessage.setText("")
                    return
                }
                val selectedMessage = messages[position]
                if (inputMessage.text.toString().trim() != selectedMessage) {
                    inputMessage.setText(selectedMessage)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(meshServiceConnection) }
            .onFailure { Log.w(TAG, "Tried to unbind but service was already unbound", it) }
    }

    data class RuleDraftUi(
        val messageText: String,
        val scheduleType: PlannedMessageScheduleType,
        val daysOfWeekMask: Int,
        val hourOfDay: Int,
        val minuteOfHour: Int,
        val oneShotAtUtcEpochMs: Long?,
        val timezoneId: String,
        val deliveryPolicy: PlannedMessageDeliveryPolicy,
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
            )
        }

        fun toDisplayString(): String {
            val policyText = if (deliveryPolicy == PlannedMessageDeliveryPolicy.SKIP_MISSED) "skip" else "catch-up"
            return if (scheduleType == PlannedMessageScheduleType.ONE_SHOT) {
                val whenText = oneShotAtUtcEpochMs?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.of(timezoneId))
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                } ?: "unknown"
                "ONCE $whenText - $messageText [$policyText]"
            } else {
                val dayLabel = DAYS[DAY_INDEX_FROM_MASK[daysOfWeekMask] ?: 0]
                "$dayLabel ${"%02d:%02d".format(hourOfDay, minuteOfHour)} - $messageText [$policyText]"
            }
        }

        companion object {
            fun fromEntity(entity: com.geeksville.mesh.plannedmessages.data.PlannedMessageEntity): RuleDraftUi {
                return RuleDraftUi(
                    messageText = entity.messageText,
                    scheduleType = entity.scheduleType,
                    daysOfWeekMask = entity.daysOfWeekMask,
                    hourOfDay = entity.hourOfDay,
                    minuteOfHour = entity.minuteOfHour,
                    oneShotAtUtcEpochMs = entity.oneShotAtUtcEpochMs,
                    timezoneId = entity.timezoneId,
                    deliveryPolicy = entity.deliveryPolicy,
                )
            }
        }
    }

    class NDSpinner @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0,
    ) : androidx.appcompat.widget.AppCompatSpinner(context, attrs, defStyleAttr) {
        override fun setSelection(position: Int, animate: Boolean) {
            val same = position == selectedItemPosition
            super.setSelection(position, animate)
            if (same) {
                onItemSelectedListener?.onItemSelected(this, selectedView, position, selectedItemId)
            }
        }

        override fun setSelection(position: Int) {
            val same = position == selectedItemPosition
            super.setSelection(position)
            if (same) {
                onItemSelectedListener?.onItemSelected(this, selectedView, position, selectedItemId)
            }
        }
    }

    companion object {
        private const val TAG = "PlanMsgActivity"
        const val NODE_ID_EXTRA_PARAM = "nodeId"
        const val BROADCAST_ID_SIG = "^all"
        const val SEPARATOR_DATE_MSG = "-"
        val DAYS = arrayOf("LUN", "MAR", "MER", "GIO", "VEN", "SAB", "DOM")
        private val DAY_OF_WEEK_FROM_INDEX = WeeklyDays.orderedDays
        private val DAY_INDEX_FROM_MASK = DAY_OF_WEEK_FROM_INDEX
            .mapIndexed { index, day -> WeeklyDays.maskFor(day) to index }
            .toMap()
        private val SCHEDULE_LABELS = listOf("Weekly", "One-shot")
        private val POLICY_LABELS = listOf("Skip missed", "Catch up")
        private val GRACE_WINDOW_OPTIONS_MS = PlannedMessageSettings.ALLOWED_GRACE_WINDOWS_MS
        private val GRACE_WINDOW_LABELS = GRACE_WINDOW_OPTIONS_MS.map { "${it / 60_000L} min" }
        private val DEFAULT_GRACE_INDEX = GRACE_WINDOW_OPTIONS_MS
            .indexOf(PlannedMessageSettings.DEFAULT_SKIP_MISSED_GRACE_MS)
            .takeIf { it >= 0 } ?: 0
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
