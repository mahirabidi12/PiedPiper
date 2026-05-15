package com.disastermesh.authority

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.R
import com.disastermesh.db.AppDatabase
import com.disastermesh.models.SignalStatus

class AreaDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_area_detail)

        val areaLabel = intent.getStringExtra(AuthorityActivity.EXTRA_AREA_LABEL) ?: "Area"
        val signalIds = intent.getStringArrayListExtra(AuthorityActivity.EXTRA_SIGNAL_IDS) ?: arrayListOf()

        findViewById<TextView>(R.id.tvDetailAreaLabel).text = areaLabel
        findViewById<TextView>(R.id.tvDetailBack).setOnClickListener { finish() }

        val db      = AppDatabase.getInstance(this)
        val all     = db.signalDao().getAll().map { it.toSignal() }
        val signals = all.filter { it.id in signalIds }
            .sortedWith(compareByDescending { it.priority.level })

        val totalCount = signals.size
        val openCount = signals.count { it.status != SignalStatus.RESOLVED }
        val affectedCount = signals.mapNotNull { it.peopleCount }.sum().takeIf { it > 0 }

        val rv      = findViewById<RecyclerView>(R.id.rvDetailSignals)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter  = SignalAdapter(signals)

        findViewById<TextView>(R.id.tvDetailCount).text =
            "${signals.size} signal${if (signals.size != 1) "s" else ""} in this area"

        findViewById<TextView>(R.id.tvZoneTotal).text = "TOTAL\n$totalCount"
        findViewById<TextView>(R.id.tvZoneOpen).text = "OPEN\n$openCount"
        findViewById<TextView>(R.id.tvZoneAffected).text =
            "AFFECTED\n${affectedCount ?: "--"}"

        findViewById<TextView>(R.id.tvZoneGemma).setOnClickListener {
            Toast.makeText(this, "Gemma zone analysis shell ready.", Toast.LENGTH_SHORT).show()
        }
    }
}
