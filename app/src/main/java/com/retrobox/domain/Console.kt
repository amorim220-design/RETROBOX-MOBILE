package com.retrobox.domain

enum class Console(
    val id: String,
    val displayName: String,
    val extensions: List<String>,
    val iconResName: String? = null
) {
    NES("nes", "NES", listOf("nes", "unf", "unif", "zip"), null),
    SNES("snes", "SNES", listOf("smc", "sfc", "fig", "swc", "zip"), "img_snes_icon_1793198739143"),
    GB("gb", "Game Boy", listOf("gb", "zip"), null),
    GBC("gbc", "Game Boy Color", listOf("gbc", "zip"), null),
    GBA("gba", "GBA", listOf("gba", "agb", "zip"), "img_gba_icon_1793198738974"),
    MEGADRIVE("megadrive", "Mega Drive", listOf("bin", "md", "gen", "smd", "zip"), "img_megadrive_icon_1793198739047"),
    PS1("ps1", "PS1", listOf("bin", "cue", "iso", "chd", "pbp", "img"), "img_ps1_icon_1793198769446"),
    PSP("psp", "PSP", listOf("iso", "cso", "pbp", "chd"), "img_psp_icon_1793198739489"),
    N64("n64", "Nintendo 64", listOf("n64", "v64", "z64", "zip"), null),
    NDS("nds", "Nintendo DS", listOf("nds", "zip"), null);

    companion object {
        fun fromExtension(extension: String): Console? {
            val ext = extension.lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }

        fun fromId(id: String): Console? = entries.firstOrNull { it.id == id }

        /** Extension of the primary file of a game, ignoring trailing archive names. */
        fun fromFileName(fileName: String): Console? =
            fromExtension(fileName.substringAfterLast('.', ""))
    }
}
