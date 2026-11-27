package app.veltrio.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.veltrio.android.LocalTv
import app.veltrio.android.Nav
import app.veltrio.android.ui.Eyebrow
import app.veltrio.android.ui.PosterCard
import app.veltrio.android.ui.SectionHeader
import app.veltrio.shared.PosterItem
import app.veltrio.shared.TmdbService
import app.veltrio.shared.i18n.tr
import app.veltrio.shared.ui.Spacing
import app.veltrio.shared.ui.Type
import app.veltrio.shared.ui.VeltrioTheme
import coil3.compose.AsyncImage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

@Composable
fun ProviderHubScreen(provider: TmdbService.DiscoverProvider) {
    HubScaffold(
        eyebrow = tr("Plateforme"),
        title = provider.name,
        brandColor = Color(provider.colorHex),
        logoUrl = provider.logoUrl,
        loadPopularMovies = { TmdbService.discover("movie", providerId = provider.id, sortBy = "popularity.desc") },
        loadPopularSeries = { TmdbService.discover("series", providerId = provider.id, sortBy = "popularity.desc") },
        loadTopMovies = { TmdbService.discover("movie", providerId = provider.id, sortBy = "vote_average.desc", minVotes = 150) },
        loadTopSeries = { TmdbService.discover("series", providerId = provider.id, sortBy = "vote_average.desc", minVotes = 150) },
    )
}

@Composable
fun GenreHubScreen(genre: TmdbService.DiscoverGenre) {
    HubScaffold(
        eyebrow = tr("Genre"),
        title = genre.label,
        brandColor = Color(genre.colorHex),
        logoUrl = null,
        loadPopularMovies = { TmdbService.discover("movie", genreId = genre.movieId, sortBy = "popularity.desc") },
        loadPopularSeries = { TmdbService.discover("series", genreId = genre.tvId, sortBy = "popularity.desc") },
        loadTopMovies = { TmdbService.discover("movie", genreId = genre.movieId, sortBy = "vote_average.desc", minVotes = 200) },
        loadTopSeries = { TmdbService.discover("series", genreId = genre.tvId, sortBy = "vote_average.desc", minVotes = 200) },
    )
}

@Composable
private fun HubScaffold(
    eyebrow: String,
    title: String,
    brandColor: Color,
    logoUrl: String?,
    loadPopularMovies: suspend () -> List<PosterItem>,
    loadPopularSeries: suspend () -> List<PosterItem>,
    loadTopMovies: suspend () -> List<PosterItem>,
    loadTopSeries: suspend () -> List<PosterItem>,
) {
    val p = VeltrioTheme.palette
    val tv = LocalTv.current
    var popMovies by remember { mutableStateOf<List<PosterItem>?>(null) }
    var popSeries by remember { mutableStateOf<List<PosterItem>?>(null) }
    var topMovies by remember { mutableStateOf<List<PosterItem>?>(null) }
    var topSeries by remember { mutableStateOf<List<PosterItem>?>(null) }

    LaunchedEffect(title) {
        coroutineScope {
            val d1 = async { runCatching { loadPopularMovies() }.getOrDefault(emptyList()) }
            val d2 = async { runCatching { loadPopularSeries() }.getOrDefault(emptyList()) }
            val d3 = async { runCatching { loadTopMovies() }.getOrDefault(emptyList()) }
            val d4 = async { runCatching { loadTopSeries() }.getOrDefault(emptyList()) }
            popMovies = d1.await()
            popSeries = d2.await()
            topMovies = d3.await()
            topSeries = d4.await()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(p.background),
        contentPadding = PaddingValues(bottom = Spacing.xl),
    ) {
        item(key = "header") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to brandColor.copy(alpha = 0.34f),
                            1f to p.background,
                        ),
                    )
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { Nav.back() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp), tint = p.textSecondary)
                        Text(tr("Retour"), style = Type.label(13, FontWeight.SemiBold), color = p.textSecondary)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (logoUrl != null) {
                            AsyncImage(
                                model = logoUrl,
                                contentDescription = title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(if (tv) 64.dp else 54.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(14.dp)),
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Eyebrow(eyebrow, p.accentText)
                            Text(title, style = Type.displaySerif(if (tv) 38 else 32), color = p.textPrimary)
                        }
                    }
                }
            }
        }

        item(key = "pop-movies") { HubRow(1, tr("Films populaires"), tr("TMDB · Film"), popMovies) }
        item(key = "pop-series") { HubRow(2, tr("Séries populaires"), tr("TMDB · Série"), popSeries) }
        item(key = "top-movies") { HubRow(3, tr("Films les mieux notés"), tr("TMDB · Film"), topMovies) }
        item(key = "top-series") { HubRow(4, tr("Séries les mieux notées"), tr("TMDB · Série"), topSeries) }
    }
}

@Composable
private fun HubRow(number: Int, title: String, subtitle: String, items: List<PosterItem>?) {
    val p = VeltrioTheme.palette
    Column(Modifier.padding(top = Spacing.lg)) {
        SectionHeader(number, title, subtitle, Modifier.padding(horizontal = Spacing.lg))
        Spacer(Modifier.height(10.dp))
        when {
            items == null -> Text(tr("Chargement…"), style = Type.caption(12), color = p.textTertiary, modifier = Modifier.padding(horizontal = Spacing.lg))
            items.isEmpty() -> Text(tr("Rien dans ce catalogue."), style = Type.caption(12), color = p.textTertiary, modifier = Modifier.padding(horizontal = Spacing.lg))
            else -> LazyRow(contentPadding = PaddingValues(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { "$number-${it.id}" }) { item ->
                    PosterCard(item.title, item.posterUrl, item.subtitle) { openPoster(item) }
                }
            }
        }
    }
}
