package com.geeksville.mesh.ui.activity

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.emp3r0r7.darkmesh.R
import com.geeksville.mesh.database.entity.NodeEntity
import com.geeksville.mesh.plannedmessages.data.PlannedMessageDestinationSummary
import com.geeksville.mesh.plannedmessages.ui.PlannedMessageListViewModel
import com.geeksville.mesh.service.MeshService
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.ConcurrentHashMap

@AndroidEntryPoint
class PlanMsgListActivity : AppCompatActivity() {

    private val viewModel: PlannedMessageListViewModel by viewModels()
    private var meshService: MeshService? = null

    private lateinit var listView: ListView
    private lateinit var titleView: TextView
    private lateinit var plannerSwitch: SwitchCompat

    private var summaries: List<PlannedMessageDestinationSummary> = emptyList()
    private var suppressSwitchCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_planmsglist)

        listView = findViewById(R.id.listViewNodes)
        titleView = findViewById(R.id.planListTitle)
        plannerSwitch = findViewById(R.id.switchPlanning)

        bindMeshService()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        renderList()
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

    companion object {
        private const val TAG = "PlanMsgListActivity"
    }
}
