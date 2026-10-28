package com.example.domain

enum class Console(
    val id: String,
    val displayName: String,
    val extensions: List<String>,
    val iconResName: String? = null
) {
    PS1("ps1", "PS1", listOf("bin", "cue", "iso"), "img_ps1_icon_1793198769446"),
    PSP("psp", "PSP", listOf("iso", "cso"), "img_psp_icon_1793198739489"),
    SNES("snes", "SNES", listOf("smc", "sfc", "zip"), "img_snes_icon_1793198739143"),
    MEGADRIVE("megadrive", "Mega Drive", listOf("bin", "md", "gen", "zip"), "img_megadrive_icon_1793198739047"),
    GBA("gba", "GBA", listOf("gba", "zip"), "img_gba_icon_1793198738974"),
    NDS("nds", "Nintendo DS", listOf("nds", "zip"), null),
    N64("n64", "Nintendo 64", listOf("n64", "v64", "z64", "zip"), null)
}
