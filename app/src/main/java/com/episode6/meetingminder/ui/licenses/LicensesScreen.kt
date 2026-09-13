package com.episode6.meetingminder.ui.licenses

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.episode6.meetingminder.LicenseNotices
import com.episode6.meetingminder.R
import com.episode6.meetingminder.ui.theme.MeetingMinderTheme
import com.episode6.meetingminder.ui.util.MarkdownBlock
import com.episode6.meetingminder.ui.util.basicMarkdownToBlocks

/** Renders THIRD_PARTY_LICENSES.md (embedded at build time as [LicenseNotices]). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licenses_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val linkColor = MaterialTheme.colorScheme.primary
        val blocks = remember(linkColor) {
            basicMarkdownToBlocks(LicenseNotices.MARKDOWN, linkColor = linkColor)
                // the document's own h1 would just repeat the scaffold title
                .filterNot { it is MarkdownBlock.Heading && it.level == 1 }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            blocks.forEach { block ->
                when (block) {
                    is MarkdownBlock.Heading -> Text(
                        block.text,
                        style = if (block.level <= 2) {
                            MaterialTheme.typography.titleLarge
                        } else {
                            MaterialTheme.typography.titleMedium
                        },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    is MarkdownBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                    is MarkdownBlock.Bullet -> Row {
                        Text("•", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(8.dp))
                        Text(block.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LicensesScreenPreview() {
    MeetingMinderTheme {
        LicensesScreen(onBack = {})
    }
}
