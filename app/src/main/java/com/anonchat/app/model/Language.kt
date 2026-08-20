package com.anonchat.app.model

data class Language(
    val code: String,
    val nativeName: String,
    val englishName: String,
    var isSelected: Boolean = false
)
