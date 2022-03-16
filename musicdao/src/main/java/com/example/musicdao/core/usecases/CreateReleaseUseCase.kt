package com.example.musicdao.core.usecases

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.example.musicdao.CachePath
import com.example.musicdao.core.repositories.AlbumRepository
import com.example.musicdao.core.torrent.TorrentEngine
import java.nio.file.Paths
import java.util.*
import javax.inject.Inject

class CreateReleaseUseCase @Inject constructor(
    private val albumRepository: AlbumRepository,
    private val torrentEngine: TorrentEngine,
    private val cachePath: CachePath
) {

    @RequiresApi(Build.VERSION_CODES.O)
    suspend operator fun invoke(
        artist: String,
        title: String,
        releaseDate: String,
        uris: List<Uri>,
        context: Context
    ): Boolean {
        val releaseId = UUID.randomUUID().toString()
        Log.d("MusicDao", "CreateReleaseUseCase: $releaseId")

        val root = torrentEngine.simulateDownload(context, uris, releaseId)
        if (root == null) {
            Log.d("MusicDao", "CreateReleaseUseCase: could not simulate download")
            return false
        }

//        val contentFolder = TorrentEngine.rootToContentFolder(root) ?: return false
//        val torrentInfo = TorrentEngine.createTorrentInfo(contentFolder)
//        val torrentFile = Paths.get("${cachePath.getPath()}/torrents/$releaseId.torrent").toFile()
//        torrentFile.writeBytes(torrentInfo.bencode())
//        if (torrentInfo == null) {
//            Log.d("MusicDao", "CreateReleaseUseCase: could not calculate info-hash")
//            return false
//        }

        val magnet = root.second.makeMagnetUri()
        val publishResult = albumRepository.create(
            releaseId = releaseId,
            magnet = magnet,
            title = title,
            artist = artist,
            releaseDate = releaseDate,
        )
        if (!publishResult) {
            Log.d("MusicDao", "Release: publishing to network failed")
            return false
        }

        torrentEngine.download(magnet, root.first)
        return true
    }
}
