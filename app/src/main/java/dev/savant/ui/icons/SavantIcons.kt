package dev.savant.ui.icons

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.savant.R
import dev.savant.ui.theme.Savant

/**
 * The icon set.
 *
 * Thin outline glyphs on a 24dp grid, 2dp stroke, round caps and joins — the Feather family, which
 * is the same lineage as Lucide. Bundled as vector drawables instead of material-icons-extended:
 * the app needs about fifty icons, not seven thousand, and none of them may be a sparkle, a magic
 * wand or anything else that shouts "AI".
 *
 * Values are resource ids so a caller can also hand one to [painterResource] directly.
 */
object SavantIcons {
    // chat + input bar
    @DrawableRes val attach: Int = R.drawable.ic_paperclip
    @DrawableRes val send: Int = R.drawable.ic_send
    @DrawableRes val message: Int = R.drawable.ic_message_square

    // files + storage
    @DrawableRes val file: Int = R.drawable.ic_file_text
    @DrawableRes val folder: Int = R.drawable.ic_folder
    @DrawableRes val drive: Int = R.drawable.ic_hard_drive
    @DrawableRes val database: Int = R.drawable.ic_database
    @DrawableRes val save: Int = R.drawable.ic_save
    @DrawableRes val upload: Int = R.drawable.ic_upload
    @DrawableRes val download: Int = R.drawable.ic_download
    @DrawableRes val share: Int = R.drawable.ic_share_2
    @DrawableRes val trash: Int = R.drawable.ic_trash_2
    @DrawableRes val copy: Int = R.drawable.ic_copy

    // editing + diff
    @DrawableRes val edit: Int = R.drawable.ic_edit_3
    @DrawableRes val check: Int = R.drawable.ic_check
    @DrawableRes val checkCircle: Int = R.drawable.ic_check_circle
    @DrawableRes val close: Int = R.drawable.ic_x
    @DrawableRes val undo: Int = R.drawable.ic_rotate_ccw
    @DrawableRes val refresh: Int = R.drawable.ic_refresh_cw
    @DrawableRes val plus: Int = R.drawable.ic_plus
    @DrawableRes val minus: Int = R.drawable.ic_minus
    @DrawableRes val slash: Int = R.drawable.ic_slash

    // structure + discovery
    @DrawableRes val list: Int = R.drawable.ic_list
    @DrawableRes val grid: Int = R.drawable.ic_grid
    @DrawableRes val layers: Int = R.drawable.ic_layers
    @DrawableRes val search: Int = R.drawable.ic_search
    @DrawableRes val filter: Int = R.drawable.ic_filter
    @DrawableRes val code: Int = R.drawable.ic_code
    @DrawableRes val terminal: Int = R.drawable.ic_terminal
    @DrawableRes val eye: Int = R.drawable.ic_eye
    @DrawableRes val eyeOff: Int = R.drawable.ic_eye_off

    // state + navigation
    @DrawableRes val chevronDown: Int = R.drawable.ic_chevron_down
    @DrawableRes val chevronUp: Int = R.drawable.ic_chevron_up
    @DrawableRes val chevronRight: Int = R.drawable.ic_chevron_right
    @DrawableRes val chevronLeft: Int = R.drawable.ic_chevron_left
    @DrawableRes val arrowLeft: Int = R.drawable.ic_arrow_left
    @DrawableRes val more: Int = R.drawable.ic_more_vertical
    @DrawableRes val external: Int = R.drawable.ic_external_link

    // status
    @DrawableRes val alert: Int = R.drawable.ic_alert_triangle
    @DrawableRes val info: Int = R.drawable.ic_info
    @DrawableRes val help: Int = R.drawable.ic_help_circle
    @DrawableRes val loader: Int = R.drawable.ic_loader
    @DrawableRes val lock: Int = R.drawable.ic_lock
    @DrawableRes val shield: Int = R.drawable.ic_shield
    @DrawableRes val key: Int = R.drawable.ic_key
    @DrawableRes val link: Int = R.drawable.ic_link
    @DrawableRes val globe: Int = R.drawable.ic_globe
    @DrawableRes val offline: Int = R.drawable.ic_wifi_off
    @DrawableRes val bookmark: Int = R.drawable.ic_bookmark
    @DrawableRes val flag: Int = R.drawable.ic_flag
    @DrawableRes val box: Int = R.drawable.ic_package
    @DrawableRes val activity: Int = R.drawable.ic_activity
    @DrawableRes val cpu: Int = R.drawable.ic_cpu
    @DrawableRes val settings: Int = R.drawable.ic_settings
}

/**
 * A thin outline icon at a sensible default size.
 *
 * `contentDescription` stays null for decorative use; pass one when the icon is the only label.
 */
@Composable
fun SavantIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
    tint: Color = Savant.colors.textMuted,
    size: Dp = 18.dp,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(id = id),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
