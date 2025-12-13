package eu.monniot.feed

import android.os.Bundle
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import eu.monniot.feed.ui.theme.FeedTheme
import java.text.SimpleDateFormat
import java.util.Locale

data class RssItem(
    val title: String,
    val description: String,
    val pubDate: String,
    val source: String
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FeedTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Sample data
                    val items = listOf(
                        RssItem(
                            title = "Android Studio Iguana",
                            description = "New features in Android Studio Iguana include improved App Quality Insights, UI Check for Compose, and more.",
                            pubDate = "Fri, 23 Feb 2024",
                            source = "Android Developers Blog"
                        ),
                        RssItem(
                            title = "Jetpack Compose 1.6",
                            description = "Performance improvements and new components are now available in the latest stable release of Jetpack Compose.",
                            pubDate = "Wed, 24 Jan 2024",
                            source = "Android Developers Blog"
                        ),
                        RssItem(
                            title = "Kotlin 2.0 Beta",
                            description = "Try out the K2 compiler with the new Kotlin 2.0 Beta release. It brings significant build speed improvements.",
                            pubDate = "Thu, 15 Feb 2024",
                            source = "Kotlin Blog"
                        )
                    )
                    
                    RssList(
                        items = items,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun RssList(items: List<RssItem>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(items) { item ->
            RssItemRow(item)
        }
    }
}

@Composable
fun RssItemRow(item: RssItem) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Title: First, max 2 lines
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Description: Follows title, max 4 lines
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Footer: Relative date and Source
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = getRelativeTime(item.pubDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = "•",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
                
                Spacer(modifier = Modifier.width(8.dp))
                
                Text(
                    text = item.source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

fun getRelativeTime(dateString: String): String {
    return try {
        // Trying to parse standard RSS date format or the sample format provided
        // Sample: "Fri, 23 Feb 2024"
        val format = SimpleDateFormat("EEE, d MMM yyyy", Locale.ENGLISH)
        val date = format.parse(dateString)
        if (date != null) {
             DateUtils.getRelativeTimeSpanString(
                 date.time,
                 System.currentTimeMillis(),
                 DateUtils.MINUTE_IN_MILLIS
             ).toString()
        } else {
            dateString
        }
    } catch (e: Exception) {
        dateString
    }
}

@Preview(showBackground = true)
@Composable
fun RssListPreview() {
    FeedTheme {
        val sampleItems = listOf(
            RssItem(
                "Title 1", 
                "Description 1 is a bit longer to test the layout and see how it wraps around multiple lines.", 
                "Mon, 01 Jan 2024", 
                "Source A"
            ),
            RssItem(
                "Title 2", 
                "Description 2", 
                "Tue, 02 Jan 2024", 
                "Source B"
            ),
            RssItem(
                "Title 3", 
                "Description 3", 
                "Wed, 03 Jan 2024", 
                "Source C"
            )
        )
        RssList(items = sampleItems)
    }
}

@Preview(showBackground = true)
@Composable
fun RssItemPreview() {
    FeedTheme {
        RssItemRow(
            RssItem(
                "Sample Title that might be long enough to wrap to a second line to verify the maxLines constraint", 
                "This is a sample description of the RSS item. It should be able to span up to four lines. ".repeat(3), 
                "Mon, 01 Jan 2024",
                "My Favorite Blog"
            )
        )
    }
}
