package com.geeksville.mesh.ui.activity

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.emp3r0r7.darkmesh.R

data class PlanMsgRuleCardModel(
    val localId: Long,
    val messagePreview: String,
    val scheduleSummary: String,
    val nextRunSummary: String,
    val policySummary: String,
    val statusSummary: String,
    val warningsSummary: String?,
    val isEnabled: Boolean,
)

class PlanMsgRuleAdapter(
    private val onRuleClick: (Long) -> Unit,
    private val onRuleActionClick: (View, Long) -> Unit,
    private val onRuleEnabledChanged: (Long, Boolean) -> Unit,
) : ListAdapter<PlanMsgRuleCardModel, PlanMsgRuleAdapter.RuleViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_planmsg_rule_card, parent, false)
        return RuleViewHolder(view)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RuleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageText: TextView = itemView.findViewById(R.id.txtRuleMessage)
        private val scheduleText: TextView = itemView.findViewById(R.id.txtRuleSchedule)
        private val nextRunText: TextView = itemView.findViewById(R.id.txtRuleNextRun)
        private val policyText: TextView = itemView.findViewById(R.id.txtRulePolicy)
        private val statusText: TextView = itemView.findViewById(R.id.txtRuleStatus)
        private val warningsText: TextView = itemView.findViewById(R.id.txtRuleWarnings)
        private val enabledSwitch: SwitchCompat = itemView.findViewById(R.id.switchRuleEnabledCard)
        private val actionsButton: ImageButton = itemView.findViewById(R.id.btnRuleActions)

        fun bind(item: PlanMsgRuleCardModel) {
            messageText.text = item.messagePreview
            scheduleText.text = item.scheduleSummary
            nextRunText.text = item.nextRunSummary
            policyText.text = item.policySummary
            statusText.text = item.statusSummary

            warningsText.visibility = if (item.warningsSummary.isNullOrBlank()) View.GONE else View.VISIBLE
            warningsText.text = item.warningsSummary

            enabledSwitch.setOnCheckedChangeListener(null)
            enabledSwitch.isChecked = item.isEnabled
            enabledSwitch.setOnCheckedChangeListener { _, checked ->
                onRuleEnabledChanged(item.localId, checked)
            }

            itemView.setOnClickListener { onRuleClick(item.localId) }
            actionsButton.setOnClickListener { onRuleActionClick(it, item.localId) }
        }
    }

    private companion object {
        val DiffCallback = object : DiffUtil.ItemCallback<PlanMsgRuleCardModel>() {
            override fun areItemsTheSame(oldItem: PlanMsgRuleCardModel, newItem: PlanMsgRuleCardModel): Boolean {
                return oldItem.localId == newItem.localId
            }

            override fun areContentsTheSame(oldItem: PlanMsgRuleCardModel, newItem: PlanMsgRuleCardModel): Boolean {
                return oldItem == newItem
            }
        }
    }
}
