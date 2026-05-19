package com.example.seeforyou.screens

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.example.seeforyou.R
import com.example.seeforyou.services.FirebaseService

data class LogEntry(
    val label: String,
    val confidence: String,
    val timestamp: String
)

class LogFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val logs = mutableListOf<LogEntry>()
    private lateinit var adapter: LogAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_log, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.rv_logs)
        adapter      = LogAdapter(logs)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter       = adapter

        loadLogs()
    }

    private fun loadLogs() {
        FirebaseService.getLogsReference()
            .orderByKey()
            .limitToLast(100)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    logs.clear()
                    for (child in snapshot.children.toList().reversed()) {
                        val label = child.child("label").value?.toString() ?: ""
                        val conf  = child.child("confidence").value?.toString() ?: ""
                        val time  = child.child("timestamp").value?.toString() ?: ""
                        logs.add(LogEntry(label, conf, time))
                    }
                    adapter.notifyDataSetChanged()
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }
}

class LogAdapter(private val items: List<LogEntry>) :
    RecyclerView.Adapter<LogAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLabel: TextView = view.findViewById(R.id.tv_log_label)
        val tvTime: TextView  = view.findViewById(R.id.tv_log_time)
        val tvConf: TextView  = view.findViewById(R.id.tv_log_conf)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item      = items[position]
        val confFloat = item.confidence.toFloatOrNull() ?: 0f
        val confPct   = (confFloat * 100).toInt()

        holder.tvLabel.text = item.label.replaceFirstChar { it.uppercase() }
        holder.tvTime.text  = item.timestamp
        holder.tvConf.text  = "$confPct%"
        holder.tvConf.setTextColor(
            if (confFloat > 0.7f)
                android.graphics.Color.parseColor("#2E7D32")
            else
                android.graphics.Color.parseColor("#E65100")
        )
    }

    override fun getItemCount() = items.size
}