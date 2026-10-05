package jp.co.updates.swingcheck.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import jp.co.updates.swingcheck.R
import jp.co.updates.swingcheck.display.SwingDateFormat
import java.util.TimeZone

@Composable
fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back))
    }
}

/** 端末のロケールに合わせた日時の書式。 */
@Composable
fun rememberSwingDateFormat(): SwingDateFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { SwingDateFormat(locale, TimeZone.getDefault()) }
}
