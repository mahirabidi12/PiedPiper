package com.disastermesh.app.ui.ai

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.disastermesh.app.ai.LanguagePreference
import com.disastermesh.app.ai.SurvivalLanguage
import com.disastermesh.app.databinding.BottomSheetLanguageBinding
import com.disastermesh.app.databinding.ItemLanguageRowBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Searchable picker for all languages Gemma supports natively.
 *
 * NOTE FOR FUTURE MAINTAINERS:
 *   Gemma 3n E2B speaks every language in [SurvivalLanguage] natively. There is
 *   NO download step, NO localisation pack, NO LoRA module to fetch. Selecting
 *   a row mutates [LanguagePreference.current] and that string is passed to the
 *   System Prompt wrapper (see LanguagePromptWrapper). Never call a network
 *   API from this sheet.
 */
class LanguageBottomSheet(
    private val onSelected: (SurvivalLanguage) -> Unit
) : BottomSheetDialogFragment() {

    private var _binding: BottomSheetLanguageBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: LanguageAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetLanguageBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnLangClose.setOnClickListener { dismiss() }

        adapter = LanguageAdapter(LanguagePreference.current) { lang ->
            LanguagePreference.current = lang
            onSelected(lang)
            dismiss()
        }
        binding.rvLanguages.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLanguages.adapter = adapter

        // Live filter — re-narrow on every keystroke. Uses
        // SurvivalLanguage.search() which matches displayName, nativeName, and
        // ISO code so users can type "es", "spa", or "español" — all work.
        binding.etLanguageSearch.addTextChangedListener { editable ->
            adapter.submit(SurvivalLanguage.search(editable?.toString().orEmpty()))
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

        private var items: List<SurvivalLanguage> = SurvivalLanguage.entries

        fun submit(newItems: List<SurvivalLanguage>) {
            items = newItems
            notifyDataSetChanged()
        }

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
