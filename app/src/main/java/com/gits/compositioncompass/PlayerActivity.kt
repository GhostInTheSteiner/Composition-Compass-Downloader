package com.gits.compositioncompass

import QuerySource
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Bundle
import android.os.Vibrator
import android.text.method.ScrollingMovementMethod
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.ActivityResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.arges.sepan.argmusicplayer.Callbacks.OnErrorListener
import com.arges.sepan.argmusicplayer.Callbacks.OnPlayingListener
import com.arges.sepan.argmusicplayer.Callbacks.OnPlaylistAudioChangedListener
import com.arges.sepan.argmusicplayer.Enums.ErrorType
import com.arges.sepan.argmusicplayer.Models.ArgAudio
import com.arges.sepan.argmusicplayer.Models.ArgAudioList
import com.arges.sepan.argmusicplayer.PlayerViews.ArgPlayerLargeView
import com.gits.compositioncompass.Configuration.CompositionRoot
import com.gits.compositioncompass.Queries.LastFMQuery
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.BluetoothDevice
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.ItemPicker
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.LocalFile
import com.gits.compositioncompass.StuffJavaIsTooConvolutedFor.Logger
import com.gits.compositioncompass.databinding.ActivityPlayerBinding
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import vibrateLong
import vibrateVeryLong
import java.io.File


class PlayerActivity : AppCompatActivity(), OnPlaylistAudioChangedListener, OnErrorListener,
    CompoundButton.OnCheckedChangeListener, DialogInterface.OnClickListener {
    private var playerValue: String = ""
    private var triggersValue: Boolean = false
    private var ignoreUp: Boolean = false
    private var favorites: String = ""
    private var recylebin: String = ""
    private var favoritesMoreInteresting: String = ""
    private var favoritesLessInteresting: String = ""
    private var targetLike: String = ""
    private var targetDislike: String = ""
    private var currentAudio: ArgAudio? = null
    private var audioList: ArgAudioList = ArgAudioList(false)
    private lateinit var query: LastFMQuery
    private lateinit var preferencesReader: SharedPreferences
    private lateinit var preferencesWriter: SharedPreferences.Editor
    private lateinit var audioManager: AudioManager
    private lateinit var source: ItemPicker
    private lateinit var vibrator: Vibrator
    private lateinit var player: ArgPlayerLargeView
    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerControls: List<View>
    private lateinit var composition: CompositionRoot
    private lateinit var logger: Logger

    override fun onPause() {
        super.onPause();
        mute_ifVolumeTrigger()
    }

    override fun onResume() {
        super.onResume();
        CompositionRoot.getInstance(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        unmute_ifVolumeTrigger()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        composition = CompositionRoot.getInstance(this)
        logger = composition.logger

        try {
            preferencesWriter = composition.preferencesWriter
            preferencesReader = composition.preferencesReader

            val automated =
                composition.options.rootDirectory + "/" + composition.options.automatedDirectory

            composition.changeQuerySource(QuerySource.LastFM)
            query = composition.query as LastFMQuery

            recylebin = "$automated/Recycle Bin"
            favorites = "$automated/Favorites"
            favoritesMoreInteresting = "$favorites/More Interesting"
            favoritesLessInteresting = "$favorites/Less Interesting"

            listOf(recylebin, favorites, favoritesMoreInteresting, favoritesLessInteresting)
                .forEach { File(it).mkdirs() }

            source = ItemPicker(this, ::sourceSuccess, ::sourceError)

            playerControls = listOf(findViewById(R.id.like), findViewById(R.id.dislike))
            playerControls.forEach { it.isEnabled = false }

            vibrator = applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            audioManager = applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            //get views
            player = findViewById<ArgPlayerLargeView>(R.id.argmusicplayer)
            val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
            val description = findViewById<EditText>(R.id.description)

            //get preferences
            triggersValue = preferencesReader.getBoolean("view:${triggers.id}", false)
            playerValue = preferencesReader.getString("view:${player.id}", "")!!

            player.setOnPlaylistAudioChangedListener(this)
            player.setOnErrorListener(this)
            player.enableNotification(this)

            triggers.setOnCheckedChangeListener(this)
            triggers.isChecked = triggersValue

            description.setVerticalScrollBarEnabled(true)
            description.setMovementMethod(ScrollingMovementMethod())

            if (playerValue.isNotEmpty())
                AlertDialog.Builder(this).let {
                    //vice-versa so we can instantly press "Load" without waiting for the volume-slider to disappear
                    it.setPositiveButton("Ignore", this)
                    it.setNegativeButton("Load", this)
                    it.setMessage("Should I load the last station for you?")
                    it.setTitle("Load last station")
                    it.show()
                }

        } catch (e: Exception) {
            logger.error(e)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean =
        onKeyPress(keyCode, event, false)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean =
        onKeyPress(keyCode, event, true)

    fun onKeyPress(keyCode: Int, event: KeyEvent?, keyDown: Boolean): Boolean {
        val keyUp = !keyDown

        if (keyDown && keyCode == KeyEvent.KEYCODE_BACK) close()

        //handle volume buttons
        else if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked) {

            if (keyUp && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                unmute() //station still open, restore previous volume

                if (ignoreUp) ignoreUp = false
                else like(findViewById(R.id.like))

                return true

            } else if (keyUp && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (ignoreUp) ignoreUp = false
                else {
                    unmute() //station is closed anyway, no reason to restore previous volume
                    dislike(findViewById(R.id.dislike))
                }

                return true

            } else if (keyDown && keyCode == KeyEvent.KEYCODE_VOLUME_UP && event!!.repeatCount > 5) {
                ignoreUp = true
                unmute()
                like(findViewById(R.id.like), true)

                return true

            } else if (keyDown && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event!!.repeatCount == 5) {
                ignoreUp = true
                close()

                return true

            }
        }

        return false
    }

    fun like(view: View) = like(view, false)

    fun like(view: View, toMoreInteresting: Boolean = false) {
        val source = File(currentAudio!!.path)
        val target =
            if (toMoreInteresting)
                File("$favoritesMoreInteresting/${source.name}")
            else
                File("$targetLike/${source.name}")

        if (source.renameTo(target) && findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
            vibrator.vibrateLong()

        player.seekTo(player.duration.toInt())
    }

    fun dislike(view: View) {
        val source = File(currentAudio!!.path)
        val target = File("$targetDislike/${source.name}")

        if (source.renameTo(target) && findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
            vibrator.vibrateLong()

        player.seekTo(player.duration.toInt())
    }

    fun browse(view: View) {
        source.folder()
    }

    fun close(view: View) = close()

    fun close() {
        mute()
        player.stop()
        logger.notifier.cancelAll()

        if (findViewById<CheckBox>(R.id.volume_button_triggers).isChecked)
            vibrator.vibrateVeryLong()

        finish()
    }

    private fun mute(showUI: Int = AudioManager.FLAG_SHOW_UI) =
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, showUI)

    private fun unmute(showUI: Int = AudioManager.FLAG_SHOW_UI) =
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 4, showUI)

    private fun setTarget(path: String) {

        if (path.startsWith(favorites)) {
            targetLike = favoritesMoreInteresting
            targetDislike = favoritesLessInteresting
        } else {
            targetLike = favorites
            targetDislike = recylebin
        }
    }

    private fun playFolder(path: String) {
        audioList = ArgAudioList(false)
        LocalFile(path).listFiles().forEach {
            audioList.add(
                ArgAudio.createFromFilePath(
                    it.nameWithoutExtension.split(" - ").first(),
                    it.nameWithoutExtension.split(" - ").last(),
                    it.originalPath
                )
            )
        }

        player.playPlaylist(audioList)

        unmute_ifVolumeTrigger()

        preferencesWriter.putString("view:${R.id.argmusicplayer}", path)
        preferencesWriter.apply()
    }

    private fun unmute_ifVolumeTrigger(showUI: Int = AudioManager.FLAG_SHOW_UI) {
        val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
        if (triggers.isChecked) unmute(showUI)
    }

    private fun mute_ifVolumeTrigger(showUI: Int = AudioManager.FLAG_SHOW_UI) {
        val triggers = findViewById<CheckBox>(R.id.volume_button_triggers)
        if (triggers.isChecked) mute(showUI)
    }

    private fun sourceSuccess(result: ActivityResult, file: File) = sourceSuccess(file.absolutePath)

    private fun sourceSuccess(filePath: String) {
        setTarget(filePath)
        playFolder(filePath)
        playerControls.forEach { it.isEnabled = true }
    }

    private fun sourceError(activityResult: ActivityResult) {
        logger.error(Exception("Couldn't select folder: ${activityResult.resultCode}\n\n$activityResult"))
    }

    override fun onPlaylistAudioChanged(playlist: ArgAudioList?, currentAudioIndex: Int) {
        currentAudio = playlist?.get(currentAudioIndex)

        GlobalScope.launch(newSingleThreadContext("search-artist")) {
            try {
                runOnUiThread {
                    findViewById<TextView>(R.id.description_title).text = "Artist"
                    findViewById<TextView>(R.id.description).text = ""
                    findViewById<TextView>(R.id.genres).text = ""
                }

                query.searchArtist(currentAudio!!.singer, true).first().let {
                    runOnUiThread {
                        findViewById<TextView>(R.id.description_title).text = it.name
                        findViewById<TextView>(R.id.description).text = it.biography
                        findViewById<TextView>(R.id.genres).text = it.genres.joinToString()
                    }
                }
            }
            catch (e: Exception) {
                logger.warn(e)
            }
        }
    }

    override fun onError(errorType: ErrorType?, description: String?) {
        logger.error(Exception("Error during playback: $errorType: $description"))
    }

    override fun onCheckedChanged(checkBox: CompoundButton?, checked: Boolean) {
        when (checkBox!!.id) {
            R.id.volume_button_triggers -> {
                //so we hear the audio on the bluetooth receiver (usually a car radio)
                if (checked) unmute()
                else mute()

                preferencesWriter.putBoolean("view:${R.id.volume_button_triggers}", checked)
                preferencesWriter.apply()
            }
        }
    }

    override fun onClick(dialog: DialogInterface?, which: Int) {
        when (which) {
            DialogInterface.BUTTON_POSITIVE -> {} // ignore
            DialogInterface.BUTTON_NEGATIVE -> sourceSuccess(playerValue)
        }
    }

    fun testAVRCP1(view: View) {
        try {
            val device = BluetoothDevice(this)
            device.sendTextOverAVRCP()
        }
        catch (e: Exception) {
            logger.error(e)
        }
    }

    fun testAVRCP2(view: View) {
        try {
            val avrcp = Intent("com.android.music.metachanged")
            avrcp.putExtra("track", "song title")
            avrcp.putExtra("artist", "artist name")
            avrcp.putExtra("album", "album name")
            applicationContext.sendBroadcast(avrcp)
        }
        catch (e: Exception) {
            logger.error(e)
        }
    }
}