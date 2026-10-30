package com.retrobox.ui.screens.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.retrobox.R
import com.retrobox.domain.Console
import com.retrobox.ui.theme.NeonBlue
import com.retrobox.ui.theme.SurfaceDark
import com.retrobox.ui.theme.SurfaceVariantDark

@Composable
fun ConsoleSidebar(
    selectedConsole: Console,
    onConsoleSelected: (Console) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SurfaceDark,
        modifier = modifier
            .fillMaxHeight()
            .width(110.dp)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)), shape = RoundedCornerShape(0.dp))
    ) {
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(Console.supported) { console ->
                ConsoleItem(
                    console = console,
                    isSelected = console == selectedConsole,
                    onClick = { onConsoleSelected(console) }
                )
            }
        }
    }
}

@Composable
fun ConsoleItem(
    console: Console,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val iconResId = console.iconResName?.let {
        context.resources.getIdentifier(it, "drawable", context.packageName)
    } ?: R.drawable.ic_launcher_foreground_asset_1793198266643

    val backgroundColor = if (isSelected) NeonBlue.copy(alpha = 0.1f) else Color.Transparent
    val borderColor = if (isSelected) NeonBlue else Color.White.copy(alpha = 0.1f)
    val shadowColor = if (isSelected) NeonBlue.copy(alpha = 0.5f) else Color.Transparent

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .shadow(elevation = if (isSelected) 8.dp else 0.dp, shape = RoundedCornerShape(12.dp), ambientColor = shadowColor, spotColor = shadowColor)
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .border(BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor), shape = RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(8.dp)
    ) {
        AsyncImage(
            model = iconResId,
            contentDescription = console.displayName,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentScale = ContentScale.Fit
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = console.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) NeonBlue else Color.White.copy(alpha = 0.6f)
        )
    }
}
