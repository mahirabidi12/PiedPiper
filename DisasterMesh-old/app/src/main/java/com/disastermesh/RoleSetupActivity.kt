package com.disastermesh

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class RoleSetupActivity : AppCompatActivity() {

    private lateinit var session: UserSession
    private var selectedRole: Role = Role.USER

    private lateinit var cardUser: LinearLayout
    private lateinit var cardVolunteer: LinearLayout
    private lateinit var cardAuthority: LinearLayout
    private lateinit var etName: EditText
    private lateinit var btnJoin: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_setup)

        session = UserSession(this)

        cardUser      = findViewById(R.id.cardUser)
        cardVolunteer = findViewById(R.id.cardVolunteer)
        cardAuthority = findViewById(R.id.cardAuthority)
        etName        = findViewById(R.id.etName)
        btnJoin       = findViewById(R.id.btnJoin)

        cardUser.setOnClickListener      { selectRole(Role.USER) }
        cardVolunteer.setOnClickListener { selectRole(Role.VOLUNTEER) }
        cardAuthority.setOnClickListener { selectRole(Role.AUTHORITY) }

        btnJoin.setOnClickListener { handleJoin() }

        selectRole(Role.USER)
    }

    private fun selectRole(role: Role) {
        selectedRole = role

        // Reset all cards
        setCardState(cardUser,      Role.USER,      role == Role.USER)
        setCardState(cardVolunteer, Role.VOLUNTEER, role == Role.VOLUNTEER)
        setCardState(cardAuthority, Role.AUTHORITY, role == Role.AUTHORITY)

        // Update join button colour
        btnJoin.backgroundTintList =
            android.content.res.ColorStateList.valueOf(role.color())
    }

    private fun setCardState(card: LinearLayout, role: Role, selected: Boolean) {
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * resources.displayMetrics.density
            if (selected) {
                setColor(role.bgColor())
                setStroke((2 * resources.displayMetrics.density).toInt(), role.color())
            } else {
                setColor(0xFF161B22.toInt())
                setStroke((1 * resources.displayMetrics.density).toInt(), 0xFF30363D.toInt())
            }
        }
        card.background = bg

        // Dim/brighten the indicator dot
        val dot = card.findViewWithTag<View>("dot_${role.name}")
        dot?.visibility = if (selected) View.VISIBLE else View.INVISIBLE
    }

    private fun handleJoin() {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
            etName.requestFocus()
            return
        }
        if (name.length < 2) {
            Toast.makeText(this, "Name must be at least 2 characters", Toast.LENGTH_SHORT).show()
            return
        }
        session.save(name, selectedRole)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
