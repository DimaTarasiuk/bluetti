package dev.eb3a.widget

data class ColorTheme(
    val name: String,
    val mainBackground: Int,
    val nightBackground: Int,
    val brand: Int,
    val fg: Int,
    val muted: Int,
    val accent: Int,
    val batteryOk: Int,
    val batteryMid: Int,
    val batteryLow: Int,
    val ac: Int,
    val dc: Int,
    val surface: Int,
    val line: Int,
)

object ColorThemes {
    val all = listOf(
        ColorTheme(
            name = "Forest",
            mainBackground = R.drawable.bg_main_green,
            nightBackground = R.drawable.bg_night_green,
            brand = 0xFF1F8F5C.toInt(),
            fg = 0xFFEAF3EE.toInt(),
            muted = 0xFF8B9A91.toInt(),
            accent = 0xFF3DDC97.toInt(),
            batteryOk = 0xFF1F8F5C.toInt(),
            batteryMid = 0xFFFFB84D.toInt(),
            batteryLow = 0xFFFF5C5C.toInt(),
            ac = 0xFF5AB0FF.toInt(),
            dc = 0xFFFFB84D.toInt(),
            surface = 0xFF141E1A.toInt(),
            line = 0xFF24332C.toInt(),
        ),
        ColorTheme(
            name = "Beige",
            mainBackground = R.drawable.bg_main_beige,
            nightBackground = R.drawable.bg_night_beige,
            brand = 0xFFC4A574.toInt(),
            fg = 0xFFF3E6D4.toInt(),
            muted = 0xFF9A8B76.toInt(),
            accent = 0xFFC4A574.toInt(),
            batteryOk = 0xFFB8955F.toInt(),
            batteryMid = 0xFFD4A05A.toInt(),
            batteryLow = 0xFFC46B5A.toInt(),
            ac = 0xFFC4A574.toInt(),
            dc = 0xFFE0C49A.toInt(),
            surface = 0xFF242018.toInt(),
            line = 0xFF3A3228.toInt(),
        ),
        ColorTheme(
            name = "New Year",
            mainBackground = R.drawable.bg_main_nye,
            nightBackground = R.drawable.bg_night_nye,
            brand = 0xFFE8C76B.toInt(),
            fg = 0xFFFFF8F0.toInt(),
            muted = 0xFFB8A090.toInt(),
            accent = 0xFFE04B4B.toInt(),
            batteryOk = 0xFFE8C76B.toInt(),
            batteryMid = 0xFFE8A05A.toInt(),
            batteryLow = 0xFFE04B4B.toInt(),
            ac = 0xFFE85A5A.toInt(),
            dc = 0xFFE8C76B.toInt(),
            surface = 0xFF1C1012.toInt(),
            line = 0xFF3A2428.toInt(),
        ),
    )

    fun get(index: Int): ColorTheme = all[index.coerceIn(0, all.lastIndex)]

    fun nextIndex(index: Int): Int = (index + 1) % all.size
}
