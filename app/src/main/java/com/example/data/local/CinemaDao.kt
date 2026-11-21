package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.AiMovieEntity
import com.example.data.model.CreatorChannelEntity
import com.example.data.model.WatchHistoryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CinemaDao {

    @Query("SELECT * FROM ai_movies ORDER BY isFeatured DESC, isScrapedLive DESC, ratingScore DESC, addedAt DESC")
    fun observeAllMovies(): Flow<List<AiMovieEntity>>

    @Query("SELECT * FROM ai_movies WHERE id = :movieId LIMIT 1")
    fun observeMovieById(movieId: String): Flow<AiMovieEntity?>

    @Query("SELECT * FROM ai_movies WHERE id = :movieId LIMIT 1")
    suspend fun getMovieById(movieId: String): AiMovieEntity?

    @Query("SELECT COUNT(*) FROM ai_movies")
    suspend fun getMovieCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovies(movies: List<AiMovieEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMoviesIgnoreConflict(movies: List<AiMovieEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMovie(movie: AiMovieEntity)

    @Query("SELECT * FROM watch_history")
    fun observeAllHistory(): Flow<List<WatchHistoryEntity>>

    @Query("SELECT * FROM watch_history WHERE movieId = :movieId LIMIT 1")
    suspend fun getHistoryByMovieId(movieId: String): WatchHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(entry: WatchHistoryEntity)

    @Query(
        """
        UPDATE watch_history
        SET lastWatchedAt = 0,
            watchCount = 0,
            watchedSeconds = 0,
            progressPercent = 0,
            isCompleted = 0
        WHERE movieId = :movieId
        """
    )
    suspend fun clearWatchRecordForMovie(movieId: String)

    @Query(
        """
        UPDATE watch_history
        SET lastWatchedAt = 0,
            watchCount = 0,
            watchedSeconds = 0,
            progressPercent = 0,
            isCompleted = 0
        """
    )
    suspend fun clearAllWatchLogs()

    // Creator Channels & Profiles
    @Query("SELECT * FROM creator_channels ORDER BY lastSyncedAt DESC, addedAt DESC")
    fun observeAllChannels(): Flow<List<CreatorChannelEntity>>

    @Query("SELECT * FROM creator_channels")
    suspend fun getAllChannels(): List<CreatorChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChannel(channel: CreatorChannelEntity)

    @Query("DELETE FROM creator_channels WHERE id = :channelId")
    suspend fun deleteChannelById(channelId: String)
}
