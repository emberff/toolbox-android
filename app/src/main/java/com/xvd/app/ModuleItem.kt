package com.xvd.app

data class ModuleItem(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val target: Class<*>
)
