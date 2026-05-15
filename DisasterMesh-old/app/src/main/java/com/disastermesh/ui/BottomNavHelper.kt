package com.disastermesh.ui

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.disastermesh.AssistantActivity
import com.disastermesh.MainActivity
import com.disastermesh.R
import com.disastermesh.Role
import com.disastermesh.UserSession
import com.disastermesh.authority.AuthorityActivity
import com.disastermesh.civilian.CivilianActivity
import com.disastermesh.ui.shell.CommsShellActivity
import com.disastermesh.ui.shell.MapShellActivity
import com.disastermesh.ui.shell.ProfileShellActivity

enum class NavItem {
    HOME,
    MAP,
    COMMS,
    ASSISTANT,
    PROFILE
}

object BottomNavHelper {

    fun bind(activity: Activity, active: NavItem) {
        val session = UserSession(activity)
        val role = session.role
        val accent = role.color()
        val muted = ContextCompat.getColor(activity, R.color.text_muted)

        val navHome = activity.findViewById<LinearLayout>(R.id.navHome)
        val navMap = activity.findViewById<LinearLayout>(R.id.navMap)
        val navComms = activity.findViewById<LinearLayout>(R.id.navComms)
        val navAi = activity.findViewById<LinearLayout>(R.id.navAi)
        val navProfile = activity.findViewById<LinearLayout>(R.id.navProfile)

        if (navHome == null || navMap == null || navComms == null || navAi == null || navProfile == null) {
            return
        }

        val homeLabel = activity.findViewById<TextView>(R.id.navHomeLabel)
        val mapLabel = activity.findViewById<TextView>(R.id.navMapLabel)
        val commsLabel = activity.findViewById<TextView>(R.id.navCommsLabel)
        val aiLabel = activity.findViewById<TextView>(R.id.navAiLabel)
        val profileLabel = activity.findViewById<TextView>(R.id.navProfileLabel)

        val homeIndicator = activity.findViewById<View>(R.id.navHomeIndicator)
        val mapIndicator = activity.findViewById<View>(R.id.navMapIndicator)
        val commsIndicator = activity.findViewById<View>(R.id.navCommsIndicator)
        val aiIndicator = activity.findViewById<View>(R.id.navAiIndicator)
        val profileIndicator = activity.findViewById<View>(R.id.navProfileIndicator)

        homeLabel?.text = when (role) {
            Role.AUTHORITY -> "CMD"
            Role.VOLUNTEER -> "TASKS"
            else -> "HOME"
        }

        applyState(homeLabel, homeIndicator, active == NavItem.HOME, accent, muted)
        applyState(mapLabel, mapIndicator, active == NavItem.MAP, accent, muted)
        applyState(commsLabel, commsIndicator, active == NavItem.COMMS, accent, muted)
        applyState(aiLabel, aiIndicator, active == NavItem.ASSISTANT, accent, muted)
        applyState(profileLabel, profileIndicator, active == NavItem.PROFILE, accent, muted)

        navHome.setOnClickListener {
            if (active == NavItem.HOME) return@setOnClickListener
            val target = when (role) {
                Role.USER -> CivilianActivity::class.java
                Role.AUTHORITY -> AuthorityActivity::class.java
                Role.VOLUNTEER -> MainActivity::class.java
            }
            launch(activity, target)
        }

        navMap.setOnClickListener {
            if (active == NavItem.MAP) return@setOnClickListener
            launch(activity, MapShellActivity::class.java)
        }

        navComms.setOnClickListener {
            if (active == NavItem.COMMS) return@setOnClickListener
            if (role == Role.VOLUNTEER) {
                launch(activity, MainActivity::class.java)
            } else {
                launch(activity, CommsShellActivity::class.java)
            }
        }

        navAi.setOnClickListener {
            if (active == NavItem.ASSISTANT) return@setOnClickListener
            launch(activity, AssistantActivity::class.java)
        }

        navProfile.setOnClickListener {
            if (active == NavItem.PROFILE) return@setOnClickListener
            launch(activity, ProfileShellActivity::class.java)
        }
    }

    private fun applyState(
        label: TextView?,
        indicator: View?,
        isActive: Boolean,
        accent: Int,
        muted: Int
    ) {
        label?.setTextColor(if (isActive) accent else muted)
        indicator?.setBackgroundColor(accent)
        indicator?.visibility = if (isActive) View.VISIBLE else View.INVISIBLE
    }

    private fun launch(activity: Activity, target: Class<*>) {
        if (activity.javaClass == target) return
        activity.startActivity(Intent(activity, target))
    }
}
