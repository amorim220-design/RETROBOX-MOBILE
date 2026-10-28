package com.retrobox.emulation

/**
 * Normaliza os nomes de arquivo das ROMs para (a) um título limpo deTags e
 * (b) uma lista de candidatos para buscar a arte na internet.
 *
 * Exemplos:
 *  - "Donkey Kong Country (U) (V1.2) [!].smc" -> "Donkey Kong Country" (region USA)
 *  - "Mask, The (USA).sfc"                    -> "The Mask" (e tambem "Mask, The")
 *  - "1840 - Metal Slug Advance (E)(TRSI).gba"-> "Metal Slug Advance" (region Europe)
 */
object GameTitle {

    private val REGIONS = mapOf(
        "U" to "USA", "USA" to "USA",
        "E" to "Europe", "EUROPE" to "Europe",
        "J" to "Japan", "JAPAN" to "Japan",
        "W" to "World", "WORLD" to "World"
    )

    private val BRACKET_TAG = Regex("\\[[^\\]]*\\]")
    private val PAREN_TAG = Regex("\\(([^)]*)\\)")
    private val LEADING_NUMBER = Regex("^\\d{1,5}\\s*[-–—]\\s*")
    private val TRAILING_NOISE = listOf(
        "# GBA", "# GBC", "Gold Cartridge", "Gold Edition", "Disc 1", "Disc 2",
        "Rev 1", "Rev A", "Rev B"
    )
    private val COMMA_ARTICLE = Regex("^(.*),\\s*(The|A|An|Le|La|Los|Las|Der|Die|Das|El|Il|I)$")

    /** Título limpo (sem região, revisão, hacks nem tags entre colchetes). */
    fun clean(fileName: String): String {
        var stem = fileName.substringBeforeLast('.', fileName)
        stem = BRACKET_TAG.replace(stem, " ")
        stem = PAREN_TAG.replace(stem, " ")
        stem = LEADING_NUMBER.replace(stem, "")
        for (noise in TRAILING_NOISE) {
            stem = stem.replace(noise, "", ignoreCase = true)
        }
        stem = stem.replace(Regex("\\s+"), " ").trim().trim('-', '–', ' ')
        return reorderArticle(stem)
    }

    /** Região do arquivo (USA/Europe/Japan/World) quando declarada entre parênteses. */
    fun region(fileName: String): String? {
        val stem = fileName.substringBeforeLast('.', fileName)
        for (tag in PAREN_TAG.findAll(stem)) {
            val value = tag.groupValues[1].trim()
            // aceita "USA, Europe" -> usa a primeira regiao conhecida
            for (part in value.split(',')) {
                val key = part.trim().uppercase()
                REGIONS[key]?.let { return it }
            }
        }
        return null
    }

    /**
     * Candidatos de nome para procurar a capa, do mais provavel para o menos.
     */
    fun variants(fileName: String): List<String> {
        val stem = fileName.substringBeforeLast('.', fileName)
        val region = region(fileName)

        val cleaned = clean(fileName)
        val originalOrder = reorderArticle(cleaned, keepComma = true)
        val withoutNumber = clean(LEADING_NUMBER.replace(stem, ""))
        val withoutNoise = TRAILING_NOISE.fold(cleaned) { acc, noise ->
            acc.replace(noise, "", ignoreCase = true).replace(Regex("\\s+"), " ").trim()
        }

        val bases = linkedSetOf<String>()
        bases += cleaned
        bases += originalOrder
        bases += withoutNumber
        bases += withoutNoise

        val result = mutableListOf<String>()
        fun add(value: String) {
            val trimmed = value.replace(Regex("\\s+"), " ").trim()
            if (trimmed.length >= 2 && !result.contains(trimmed)) result += trimmed
        }

        for (base in bases) {
            if (region != null) add("$base ($region)")
            add(base)
        }
        for (r in listOf("USA", "Europe", "Japan", "World")) {
            add("$cleaned ($r)")
        }
        return result
    }

    private fun reorderArticle(title: String, keepComma: Boolean = false): String {
        if (keepComma) return title
        val match = COMMA_ARTICLE.find(title) ?: return title
        val (head, article) = match.destructured
        return "$article $head".trim()
    }
}
