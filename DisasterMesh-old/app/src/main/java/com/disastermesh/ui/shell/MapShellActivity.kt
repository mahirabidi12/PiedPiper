package com.disastermesh.ui.shell

import android.graphics.Color
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.disastermesh.R
import com.disastermesh.UserSession
import com.disastermesh.ui.BottomNavHelper
import com.disastermesh.ui.GemmaStatusHelper
import com.disastermesh.ui.NavItem

class MapShellActivity : AppCompatActivity() {

    private lateinit var session: UserSession

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_shell)

        session = UserSession(this)
        bindTopBar()
        BottomNavHelper.bind(this, NavItem.MAP)
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
}
