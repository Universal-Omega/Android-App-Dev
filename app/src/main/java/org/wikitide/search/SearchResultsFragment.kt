package org.wikitide.search

import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import androidx.fragment.app.ListFragment

class SearchResultsFragment : ListFragment() {

    private var onPageSelectedListener: ((String) -> Unit)? = null

    override fun onListItemClick(l: ListView, v: View, position: Int, id: Long) {
        super.onListItemClick(l, v, position, id)
        val selectedPageTitle = l.adapter.getItem(position) as? String
        selectedPageTitle?.let { onPageSelectedListener?.invoke(it) }
    }

    fun setOnPageSelectedListener(listener: (String) -> Unit) {
        onPageSelectedListener = listener
    }

    fun updateSearchResults(results: List<String>) {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, results)
        listAdapter = adapter
    }
}

