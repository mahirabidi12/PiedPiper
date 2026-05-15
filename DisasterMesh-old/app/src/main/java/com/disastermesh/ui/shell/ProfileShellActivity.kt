package com.disastermesh.ui.shell

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.disastermesh.R
import com.disastermesh.UserSession
import com.disastermesh.ai.GemmaClient
import com.disastermesh.ui.BottomNavHelper
import com.disastermesh.ui.NavItem

class ProfileShellActivity : AppCompatActivity() {

    private lateinit var session: UserSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_shell)

        session = UserSession(this)
        bindTopBar()

        if (supportFragmentManager.findFragmentById(R.id.profileContainer) == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.profileContainer, ProfileShellFragment())
                .commit()
        }

        BottomNavHelper.bind(this, NavItem.PROFILE)
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
}
