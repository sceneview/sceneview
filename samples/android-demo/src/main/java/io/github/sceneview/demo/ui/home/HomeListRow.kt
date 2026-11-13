package io.github.sceneview.demo.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import io.github.sceneview.demo.DemoEntry
import io.github.sceneview.demo.DemoPreviews
import io.github.sceneview.demo.theme.SceneViewTokens

/** Platform list row: an existing demo capture and its title, without image tint or overlays. */
@Composable
fun DemoListRow(demo: DemoEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val preview = DemoPreviews.resourceFor(demo.id, isSystemInDarkTheme())
    ListItem(
        headlineContent = { Text(stringResource(demo.titleRes)) },
        supportingContent = { Text(stringResource(demo.subtitleRes)) },
        leadingContent = {
            if (preview != null) Image(
                painterResource(preview), contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(SceneViewTokens.Home.listThumbnail),
            ) else Icon(demo.icon, null)
        },
        modifier = modifier.testTag(HomeTestTags.row(demo.id)).clickable(role = Role.Button, onClick = onClick),
    )
}
