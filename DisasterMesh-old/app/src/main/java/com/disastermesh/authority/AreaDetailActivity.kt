package com.disastermesh.authority

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.R
import com.disastermesh.db.AppDatabase

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

        val rv      = findViewById<RecyclerView>(R.id.rvDetailSignals)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter  = SignalAdapter(signals)

        findViewById<TextView>(R.id.tvDetailCount).text =
            "${signals.size} signal${if (signals.size != 1) "s" else ""} in this area"
    }
}
