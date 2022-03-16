package com.example.musicdao.ui.screens.release

import android.app.Activity
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.example.musicdao.MusicActivity
import com.example.musicdao.core.model.Album
import com.example.musicdao.core.model.Song
import com.example.musicdao.core.torrent.api.DownloadingTrack
import com.example.musicdao.ui.components.ReleaseCover
import com.example.musicdao.ui.components.player.PlayerViewModel
import com.example.musicdao.ui.dateToShortString
import com.example.musicdao.ui.navigation.Screen
import com.example.musicdao.ui.screens.torrent.TorrentStatusScreen
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.collect
import java.io.File

@RequiresApi(Build.VERSION_CODES.O)
@ExperimentalMaterialApi
@Composable
fun ReleaseScreen(
    releaseId: String,
    playerViewModel: PlayerViewModel,
    navController: NavController
) {

    var state by remember { mutableStateOf(0) }
    val titles = listOf("RELEASE", "TORRENT")

    val viewModelFactory = EntryPointAccessors.fromActivity(
        LocalContext.current as Activity,
        MusicActivity.ViewModelFactoryProvider::class.java
    ).noteDetailViewModelFactory()

    val viewModel: ReleaseScreenViewModel = viewModel(
        factory = ReleaseScreenViewModel.provideFactory(viewModelFactory, releaseId = releaseId)
    )

    val torrentStatus by viewModel.torrentHandleState.collectAsState()
    val album by viewModel.saturatedReleaseState.observeAsState()

    val playingTrack = playerViewModel.playingTrack.collectAsState()

    // Audio Player
    val context = LocalContext.current

    fun play(track: Song, cover: File?) {
        playerViewModel.play(track, context, cover)
    }

    fun play(track: DownloadingTrack, cover: File?) {
        playerViewModel.play(
            Song(
                file = track.file,
                name = track.title,
                artist = track.artist,
                title = track.title,
            ),
            context, cover
        )
    }

    val scrollState = rememberScrollState()

    album?.let { album ->
        LaunchedEffect(
            key1 = playerViewModel,
            block = {
                viewModel.torrentHandleState.collect {
                    val current = playerViewModel.playingTrack.value ?: return@collect
                    val downloadingTracks =
                        viewModel.torrentHandleState.value?.downloadingTracks ?: return@collect
                    val isPlaying = playerViewModel.exoPlayer.isPlaying
                    val targetTrack =
                        downloadingTracks.find { it.file.name == current.file?.name } ?: return@collect

                    if (!isPlaying && targetTrack.progress > 20 && targetTrack.progress < 99) {
                        play(targetTrack, album.cover)
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(bottom = 150.dp)
        ) {
            TabRow(selectedTabIndex = state) {
                titles.forEachIndexed { index, title ->
                    Tab(
                        onClick = { state = index },
                        selected = (index == state),
                        text = { Text(title) }
                    )
                }
            }
            if (state == 0) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(top = 20.dp)
                ) {
                    ReleaseCover(
                        file = album.cover,
                        modifier = Modifier
                            .height(200.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(10))
                            .background(Color.DarkGray)
                            .shadow(10.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }
                Header(album, navController = navController)
                if (album.songs != null && album.songs.isNotEmpty()) {
                    val files = album.songs
                    files.map {

                        val isPlayingModifier = playingTrack.value?.let { current ->
                            if (it.title == current.title) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.onBackground
                            }
                        } ?: MaterialTheme.colors.onBackground

                        ListItem(
                            text = { Text(it.title, color = isPlayingModifier) },
                            secondaryText = { Text(it.artist, color = isPlayingModifier) },
                            trailing = {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.clickable { play(it, album.cover) }
                        )
                    }
                } else {
                    if (torrentStatus != null) {
                        val downloadingTracks = torrentStatus?.downloadingTracks
                        downloadingTracks?.map {
                            ListItem(
                                text = { Text(it.title) },
                                secondaryText = {
                                    Column {
                                        Text(it.artist, modifier = Modifier.padding(bottom = 5.dp))
                                        LinearProgressIndicator(progress = it.progress.toFloat() / 100)
                                    }
                                },
                                trailing = {
                                    Icon(
                                        imageVector = Icons.Filled.Menu,
                                        contentDescription = null
                                    )
                                },
                                modifier = Modifier.clickable {
//                                viewModel.setFilePriority(it)
                                    play(it, album.cover)
                                }
                            )
                        }
                        if (downloadingTracks == null || downloadingTracks.isEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                    }
                }
            }
            if (state == 1) {
                val current = torrentStatus
                if (current != null) {
                    TorrentStatusScreen(current)
                } else {
                    Text("Could not find torrent.")
                }
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun Header(album: Album, navController: NavController) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            album.title,
            style = MaterialTheme.typography.h6.merge(SpanStyle(fontWeight = FontWeight.ExtraBold)),
            modifier = Modifier.padding(bottom = 5.dp)
        )
        Text(
            album.artist,
            style = MaterialTheme.typography.body2.merge(SpanStyle(fontWeight = FontWeight.SemiBold)),
            modifier = Modifier.padding(bottom = 5.dp)
        )
        Text(
            "Album - ${dateToShortString(album.releaseDate.toString())}",
            style = MaterialTheme.typography.body2.merge(
                SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.Gray)
            ),
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    modifier = Modifier
                        .then(Modifier.padding(0.dp))
                        .align(Alignment.CenterVertically)
                )
                OutlinedButton(
                    onClick = {
                        navController.navigate(
                            Screen.Profile.createRoute(publicKey = album.publisher)
                        )
                    },
                    modifier = Modifier.padding(start = 10.dp)
                ) {
                    Text("View Artist", color = Color.White)
                }
            }
            OutlinedButton(onClick = { navController.navigate(Screen.Donate.route) }) {
                Text("Donate", color = Color.White)
            }
        }
    }
}
