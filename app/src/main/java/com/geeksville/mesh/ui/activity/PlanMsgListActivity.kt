package com.geeksville.mesh.ui.activity

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.emp3r0r7.darkmesh.R
import com.geeksville.mesh.database.entity.NodeEntity
import com.geeksville.mesh.plannedmessages.data.PlannedMessageDestinationSummary
import com.geeksville.mesh.plannedmessages.ui.PlannedMessageListViewModel
import com.geeksville.mesh.service.MeshService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

@AndroidEntryPoint
class PlanMsgListActivity : AppCompatActivity() {

    private val viewModel: PlannedMessageListViewModel by viewModels()
    private var meshService: MeshService? = null

    private lateinit var listView: ListView
    private lateinit var titleView: TextView
    private lateinit var plannerSwitch: SwitchCompat
    private lateinit var exactAlarmBanner: android.view.View
    private lateinit var exactAlarmBannerButton: Button

    private var summaries: List<PlannedMessageDestinationSummary> = emptyList()
    private var suppressSwitchCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planmsglist)

        listView = findViewById(R.id.listViewNodes)
        titleView = findViewById(R.id.planListTitle)
        plannerSwitch = findViewById(R.id.switchPlanning)
        exactAlarmBanner = findViewById(R.id.exactAlarmBanner)
        exactAlarmBannerButton = findViewById(R.id.exactAlarmBannerButton)

        bindMeshService()
        observeViewModel()
        exactAlarmBannerButton.setOnClickListener { openExactAlarmSettings() }
        titleView.setOnLongClickListener {
            showDebugSnapshot()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        renderList()
        viewModel.refreshExactAlarmCapability()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(meshServiceConnection) }
            .onFailure { Log.w(TAG, "Tried to unbind but service was already unbound", it) }
    }

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
            renderList()
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            meshService = null
            renderList()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeViewModel() {
        viewModel.destinationSummaries.observe(this) { rows ->
            summaries = rows
            renderList()
        }

        viewModel.plannerEnabled.observe(this) { enabled ->
            suppressSwitchCallback = true
            plannerSwitch.isChecked = enabled
            suppressSwitchCallback = false
            handleListVisibility(enabled)
        }
        viewModel.exactAlarmAvailable.observe(this) { canScheduleExact ->
            renderExactAlarmBanner(canScheduleExact)
        }

        plannerSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitchCallback) return@setOnCheckedChangeListener
            viewModel.setPlannerEnabled(isChecked)
            handleListVisibility(isChecked)
        }
    }

    private fun renderList() {
        val entries = summaries.map { summary -> summary.toSpannable(resolveDisplayName(summary.destinationKey)) }
        titleView.text = if (entries.isEmpty()) "No message has been planned." else "Planned Messages"

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            entries,
        )
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ ->
            val destinationKey = summaries.getOrNull(position)?.destinationKey ?: return@setOnItemClickListener
            val intent = Intent(this, PlanMsgActivity::class.java).apply {
                putExtra(PlanMsgActivity.NODE_ID_EXTRA_PARAM, destinationKey)
            }
            startActivity(intent)
        }
    }

    private fun resolveDisplayName(destinationKey: String): String {
        if (destinationKey.contains(PlanMsgActivity.BROADCAST_ID_SIG)) {
            val split = destinationKey.split("^")
            if (split.size >= 3) return "Chan ${split[2]}"
            return "Chan $destinationKey"
        }

        val nodeNum = destinationKey.toIntOrNull() ?: return "Node $destinationKey"
        val nodeMap: ConcurrentHashMap<Int, NodeEntity> = meshService?.nodeDBbyNodeNum ?: return "Node $destinationKey"
        val nodeName = nodeMap[nodeNum]?.user?.longName
        return if (nodeName.isNullOrBlank()) "Node $destinationKey" else "Node $nodeName"
    }

    private fun PlannedMessageDestinationSummary.toSpannable(labelPrefix: String): SpannableString {
        val label = "$labelPrefix Rules $ruleCount"
        val spannable = SpannableString(label)
        val name = labelPrefix.substringAfter(' ', "")
        if (name.isNotBlank()) {
            val start = label.indexOf(name)
            val end = start + name.length
            if (start >= 0 && end > start) {
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#4CAF50")),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return spannable
    }

    private fun handleListVisibility(serviceActive: Boolean) {
        listView.isEnabled = serviceActive
        listView.alpha = if (serviceActive) 1.0f else 0.5f
    }

    private fun renderExactAlarmBanner(canScheduleExactAlarms: Boolean) {
        val shouldShow = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !canScheduleExactAlarms
        exactAlarmBanner.isVisible = shouldShow
    }

    private fun openExactAlarmSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        runCatching {
            startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }.onFailure {
            Log.w(TAG, "Unable to open exact alarm settings", it)
        }
    }

    private fun showDebugSnapshot() {
        lifecycleScope.launch {
            val snapshot = viewModel.getDebugSnapshot()
            val message = buildString {
                appendLine("Exact alarms: ${if (snapshot.exactAlarmAvailable) "available" else "reduced precision"}")
                appendLine("Late mode: ${snapshot.settings.lateFireMode}")
                appendLine("Grace: ${snapshot.settings.skipMissedGraceMs / 60_000L} min")
                appendLine("Catch-up burst max: ${snapshot.settings.maxCatchUpBurst}")
                appendLine("Last alarm scheduled: ${formatUtc(snapshot.lastAlarmScheduledAtUtcMs)}")
                appendLine("Last alarm fired: ${formatUtc(snapshot.lastAlarmFiredAtUtcMs)}")
                appendLine("Last run: ${formatUtc(snapshot.lastRunAtUtcMs)}")
                appendLine("Last claimed: ${snapshot.lastClaimedCount}")
                appendLine("Last sent: ${snapshot.lastSentCount}")
                append("Last error: ${snapshot.lastErrorReason ?: "none"}")
            }
            AlertDialog.Builder(this@PlanMsgListActivity)
                .setTitle("Debug planned messages")
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun formatUtc(epochMs: Long?): String {
        if (epochMs == null) return "n/a"
        return Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .toString()
    }

    companion object {
        private const val TAG = "PlanMsgListActivity"
    }
}
