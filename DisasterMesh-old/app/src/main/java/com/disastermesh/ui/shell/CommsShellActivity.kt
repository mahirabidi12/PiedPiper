package com.disastermesh.ui.shell

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.disastermesh.R
import com.disastermesh.UserSession
import com.disastermesh.ui.BottomNavHelper
import com.disastermesh.ui.GemmaStatusHelper
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
        GemmaStatusHelper.bind(this, findViewById(R.id.tvShellAiStatus))
        findViewById<TextView>(R.id.tvShellPeerStatus).text = getString(R.string.mesh_placeholder)
    }

    private fun bindChannelShells() {
        val toastText = getString(R.string.shell_comms_placeholder)
        listOf(R.id.cardRoomAll, R.id.cardRoomVol, R.id.cardRoomAuth).forEach { id ->
            findViewById<TextView>(id).setOnClickListener {
                Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
