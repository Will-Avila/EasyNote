package com.will.noteharbor.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import com.will.noteharbor.data.NoteColor

/** Colors shared by programmatic views so light and dark themes stay legible. */
data class UiPalette(
    val isDark: Boolean,
    val canvas: Int,
    val text: Int,
    val secondaryText: Int,
    val mutedText: Int,
    val faintText: Int,
    val inputSurface: Int,
    val inputBorder: Int,
    val selectedChip: Int,
    val selectedChipText: Int,
    val unselectedChip: Int,
    val unselectedChipText: Int,
    val accent: Int,
    val accentSoft: Int,
    val fab: Int,
    val fabIcon: Int,
    val cardText: Int,
    val cardBody: Int,
    val cardFooter: Int,

    val dialogSurface: Int,
    val dialogText: Int,
    val dialogButton: Int,
    val dialogControlSurface: Int,
    val dialogControlBorder: Int,
) {
    companion object {
        fun from(context: Context): UiPalette {
            val isDark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            return if (isDark) dark() else light()
        }

        fun cardBackground(color: NoteColor, isDark: Boolean): Int = Color.parseColor(
            if (isDark) color.darkBackgroundHex else color.backgroundHex,
        )

        fun cardAccent(color: NoteColor, isDark: Boolean): Int = Color.parseColor(
            if (isDark) color.darkAccentHex else color.accentHex,
        )

        private fun light() = UiPalette(
            isDark = false,
            canvas = Color.parseColor("#FAF8F4"),
            text = Color.parseColor("#1F2430"),
            secondaryText = Color.parseColor("#566273"),
            mutedText = Color.parseColor("#647083"),
            faintText = Color.parseColor("#748092"),
            inputSurface = Color.parseColor("#E4E8EE"),
            inputBorder = Color.parseColor("#B7C0CC"),
            selectedChip = Color.parseColor("#1F2430"),
            selectedChipText = Color.WHITE,
            unselectedChip = Color.parseColor("#EEF0F4"),
            unselectedChipText = Color.parseColor("#697386"),
            accent = Color.parseColor("#2B8A78"),
            accentSoft = Color.parseColor("#CDEFE5"),
            fab = Color.parseColor("#4F46E5"),
            fabIcon = Color.WHITE,
            cardText = Color.parseColor("#1F2430"),
            cardBody = Color.parseColor("#424B5B"),
            cardFooter = Color.parseColor("#7A8391"),

            dialogSurface = Color.WHITE,
            dialogText = Color.parseColor("#1F2430"),
            dialogButton = Color.parseColor("#2B8A78"),
            dialogControlSurface = Color.parseColor("#E4E8EE"),
            dialogControlBorder = Color.parseColor("#C5CCD6"),
        )

        private fun dark() = UiPalette(
            isDark = true,
            canvas = Color.parseColor("#15171D"),
            text = Color.parseColor("#F6F7FA"),
            secondaryText = Color.parseColor("#CDD3DE"),
            mutedText = Color.parseColor("#B8C1CE"),
            faintText = Color.parseColor("#88919F"),
            inputSurface = Color.parseColor("#2D333E"),
            inputBorder = Color.parseColor("#596575"),
            selectedChip = Color.parseColor("#CDEFE5"),
            selectedChipText = Color.parseColor("#15221F"),
            unselectedChip = Color.parseColor("#262A33"),
            unselectedChipText = Color.parseColor("#C0C6D2"),
            accent = Color.parseColor("#8CE0CC"),
            accentSoft = Color.parseColor("#244E47"),
            fab = Color.parseColor("#8B5CF6"),
            fabIcon = Color.WHITE,
            cardText = Color.parseColor("#F6F7FA"),
            cardBody = Color.parseColor("#D5DAE2"),
            cardFooter = Color.parseColor("#AAB3C0"),

            dialogSurface = Color.parseColor("#24262E"),
            dialogText = Color.parseColor("#F6F7FA"),
            dialogButton = Color.parseColor("#8CE0CC"),
            dialogControlSurface = Color.parseColor("#303641"),
            dialogControlBorder = Color.parseColor("#4B5565"),
        )
    }
}
