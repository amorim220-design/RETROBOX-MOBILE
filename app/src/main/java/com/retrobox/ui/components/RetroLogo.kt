package com.retrobox.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrobox.R
import com.retrobox.ui.theme.NeonBlue

@Composable
fun RetroLogo(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            painter = painterResource(id = R.drawable.ic_launcher_foreground_asset_1793198266643),
            contentDescription = null,
            tint = NeonBlue,
            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "RETROBOX",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            ),
            color = Color.White
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "MOBILE",
            style = MaterialTheme.typography.titleLarge.copy(
                fontSize = 18.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 2.sp
            ),
            color = NeonBlue
        )
    }
}
