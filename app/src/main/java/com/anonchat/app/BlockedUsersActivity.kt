package com.anonchat.app

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class BlockedUsersActivity : AppCompatActivity() {

    private lateinit var rvBlocked: RecyclerView
    private lateinit var tvEmpty: TextView

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getLanguage(newBase)
        super.attachBaseContext(LocaleHelper.setLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_blocked_users)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        rvBlocked = findViewById(R.id.rvBlockedUsers)
        tvEmpty = findViewById(R.id.tvNoBlockedUsers)

        toolbar.setNavigationOnClickListener { finish() }

        rvBlocked.layoutManager = LinearLayoutManager(this)
        renderList()
    }

    private fun renderList() {
        val prefs = getSharedPreferences("anonchat_prefs", MODE_PRIVATE)
        val blocked = prefs.getStringSet("blocked_users", emptySet())?.toMutableList() ?: mutableListOf()

        if (blocked.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            rvBlocked.visibility = View.GONE
            return
        }

        tvEmpty.visibility = View.GONE
        rvBlocked.visibility = View.VISIBLE
        rvBlocked.adapter = BlockedAdapter(blocked) { uid ->
            AlertDialog.Builder(this)
                .setTitle("Unblock user?")
                .setMessage("You will be able to match with this user again.")
                .setPositiveButton("Unblock") { _, _ ->
                    val updated = prefs.getStringSet("blocked_users", emptySet())?.toMutableSet() ?: mutableSetOf()
                    updated.remove(uid)
                    prefs.edit().putStringSet("blocked_users", updated).apply()
                    renderList()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private class BlockedAdapter(
        private val users: List<String>,
        private val onUnblock: (String) -> Unit
    ) : RecyclerView.Adapter<BlockedAdapter.VH>() {

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val tvId: TextView = itemView.findViewById(android.R.id.text1)
            val btnUnblock: MaterialButton = itemView.findViewById(android.R.id.button1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_blocked_user, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val uid = users[position]
            holder.tvId.text = uid
            holder.btnUnblock.setOnClickListener { onUnblock(uid) }
        }

        override fun getItemCount() = users.size
    }
}
