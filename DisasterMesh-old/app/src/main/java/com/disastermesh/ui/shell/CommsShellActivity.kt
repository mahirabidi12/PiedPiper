package com.disastermesh.ui.shell

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.disastermesh.R
import com.disastermesh.UserSession
import com.disastermesh.ai.GemmaClient
import com.disastermesh.ui.BottomNavHelper
import com.disastermesh.ui.NavItem

class CommsShellActivity : AppCompatActivity() {

    private lateinit var session: UserSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_comms_shell)

        session = UserSession(this)
        bindTopBar()
        bindChannelShells()
        BottomNavHelper.bind(this, NavItem.COMMS)
    }

    private fun bindTopBar() {
        findViewById<TextView>(R.id.tvShellName).text = session.name
        findViewById<TextView>(R.id.tvShellRoleBadge).apply {
            text = session.role.badgeLabel
            setBackgroundColor(session.role.color())
            setTextColor(Color.WHITE)
        }

        val aiStatus = findViewById<TextView>(R.id.tvShellAiStatus)
        GemmaClient.warmUp(this) { status ->
            aiStatus.text = when (status) {
                GemmaClient.Status.READY -> "AI READY"
                GemmaClient.Status.LOADING -> "AI LOADING"
                GemmaClient.Status.ABSENT -> "MODEL ABSENT"
                GemmaClient.Status.ERROR -> "AI ERROR"
            }
            aiStatus.setTextColor(
                if (status == GemmaClient.Status.READY) Color.parseColor("#3FB950")
                else Color.parseColor("#71717A")
            )
        }

        findViewById<TextView>(R.id.tvShellPeerStatus).text = "MESH --"
    }

    private fun bindChannelShells() {
        val toastText = "Channel shell only. Wire mesh chat rooms here."
        findViewById<TextView>(R.id.cardRoomAll).setOnClickListener {
            Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.cardRoomVol).setOnClickListener {
            Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.cardRoomAuth).setOnClickListener {
            Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
        }
    }
}
