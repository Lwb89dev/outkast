package net.primal.android.core.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import net.primal.android.R

@Composable
fun PrimalLogo(modifier: Modifier = Modifier, showBackground: Boolean = false) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        Image(
            painter = painterResource(id = R.drawable.outkast_icon),
            contentDescription = "OUTKAST",
        )
    }
}
