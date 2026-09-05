/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
 
package org.lunaris.dolby.data.autoeq

object AutoEqSearch {
    fun search(query: String, index: List<IndexEntry>): List<IndexEntry> {
        val uniqueIndex = index.distinctBy { it.id }
        if (query.isBlank()) return uniqueIndex

        val normalizedQuery = query.filter { it.isLetterOrDigit() }.lowercase()

        val primary = uniqueIndex.filter { it.searchKey.startsWith(normalizedQuery) }
        val secondary = uniqueIndex.filter {
            it.searchKey.contains(normalizedQuery) && !it.searchKey.startsWith(normalizedQuery)
        }

        return primary + secondary
    }
}
