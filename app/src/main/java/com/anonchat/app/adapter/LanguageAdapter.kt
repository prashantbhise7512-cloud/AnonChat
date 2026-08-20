package com.anonchat.app.adapter

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.anonchat.app.R
import com.anonchat.app.ThemeManager
import com.anonchat.app.model.Language
import com.google.android.material.card.MaterialCardView

class LanguageAdapter(
    private val languages: List<Language>,
    private var selectedCode: String,
    private val onLanguageSelected: (Language) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.LanguageViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LanguageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_language_square, parent, false)
        return LanguageViewHolder(view)
    }

    override fun onBindViewHolder(holder: LanguageViewHolder, position: Int) {
        val lang = languages[position]
        val isSelected = (lang.code == selectedCode)
        holder.bind(lang, isSelected)
    }

    override fun getItemCount(): Int = languages.size

    inner class LanguageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardLanguage: View = itemView.findViewById(R.id.cardLanguage)
        private val tvNativeName: TextView = itemView.findViewById(R.id.tvNativeName)
        private val tvEnglishName: TextView = itemView.findViewById(R.id.tvEnglishName)
        private val ivCheck: ImageView = itemView.findViewById(R.id.ivCheck)

        fun bind(lang: Language, isSelected: Boolean) {
            tvNativeName.text = lang.nativeName
            tvEnglishName.text = lang.englishName

            if (isSelected) {
                cardLanguage.setBackgroundResource(R.drawable.bg_language_card_selected)
                ivCheck.visibility = View.VISIBLE
            } else {
                cardLanguage.setBackgroundResource(R.drawable.bg_language_card)
                ivCheck.visibility = View.GONE
            }

            itemView.setOnClickListener {
                if (selectedCode != lang.code) {
                    val oldIdx = languages.indexOfFirst { it.code == selectedCode }
                    selectedCode = lang.code
                    if (oldIdx >= 0) notifyItemChanged(oldIdx)
                    notifyItemChanged(bindingAdapterPosition)
                    onLanguageSelected(lang)
                }
            }
        }
    }
}
