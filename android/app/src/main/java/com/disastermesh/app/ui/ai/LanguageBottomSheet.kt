package com.disastermesh.app.ui.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.ai.LanguagePreference
import com.disastermesh.app.ai.SurvivalLanguage
import com.disastermesh.app.databinding.BottomSheetLanguageBinding
import com.disastermesh.app.databinding.ItemLanguageRowBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class LanguageBottomSheet(
    private val onSelected: (SurvivalLanguage) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLanguageBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLanguageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnLangClose.setOnClickListener { dismiss() }
        binding.rvLanguages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLanguages.adapter = LanguageAdapter(LanguagePreference.current) { lang ->
            LanguagePreference.current = lang
            onSelected(lang)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class LanguageAdapter(
        private val currentLang: SurvivalLanguage,
        private val onClick: (SurvivalLanguage) -> Unit
    ) : RecyclerView.Adapter<LanguageAdapter.VH>() {

        private val items = SurvivalLanguage.entries

        inner class VH(val binding: ItemLanguageRowBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            ItemLanguageRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val lang = items[position]
            holder.binding.tvNativeName.text  = lang.nativeName
            holder.binding.tvEnglishName.text = lang.displayName
            holder.binding.tvCheckmark.visibility =
                if (lang == currentLang) View.VISIBLE else View.INVISIBLE
            holder.itemView.setOnClickListener { onClick(lang) }
        }
    }
}
