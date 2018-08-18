package com.dooboolab.fluttersound;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.plugin.common.PluginRegistry.Registrar;

/** FlutterSoundPlugin */
public class FlutterSoundPlugin implements MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, AudioInterface{ ;
  final static String TAG = "FlutterSoundPlugin";
  final static String RECORD_STREAM = "com.dooboolab.fluttersound/record";
  final static String PLAY_STREAM= "com.dooboolab.fluttersound/play";

  private static Registrar reg;
  final private AudioModel model = new AudioModel();
  private Timer mTimer = new Timer();
  private static MethodChannel channel;

  /** Plugin registration. */
  public static void registerWith(Registrar registrar) {
    channel = new MethodChannel(registrar.messenger(), "flutter_sound");
    channel.setMethodCallHandler(new FlutterSoundPlugin());
    reg = registrar;
  }

  @Override
  public void onMethodCall(MethodCall call, Result result) {
    String path = call.argument("path");
    switch (call.method) {
      case "getPlatformVersion":
        result.success("Android " + android.os.Build.VERSION.RELEASE);
        break;
      case "startRecorder":
        this.startRecorder(path, result);
        break;
      case "stopRecorder":
        this.stopRecorder(result);
        break;
      case "startPlayer":
        this.startPlayer(path, result);
        break;
      case "stopPlayer":
        this.stopPlayer(result);
        break;
      case "pausePlayer":
        this.pausePlayer(result);
        break;
      case "resumePlayer":
        this.resumePlayer(result);
        break;
      case "seekPlayer":
        int sec = call.argument("sec");
        this.seekToPlayer(sec, result);
      default:
        result.notImplemented();
        break;
    }
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    switch (requestCode) {
      case REQUEST_RECORD_AUDIO_PERMISSION:
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED)
          return true;
        break;
    }
    return false;
  }

  @Override
  public void startRecorder(String path, Result result) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (
          reg.activity().checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
              || reg.activity().checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
          ) {
        reg.activity().requestPermissions(new String[]{
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        }, 0);
        return;
      }
    }

    Log.d(TAG, "startRecorder");

    if (path == null) {
      path = AudioModel.DEFAULT_FILE_LOCATION;
    }

    if (this.model.getMediaRecorder() == null) {
      this.model.setMediaRecorder(new MediaRecorder());
      this.model.getMediaRecorder().setAudioSource(MediaRecorder.AudioSource.MIC);
      this.model.getMediaRecorder().setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      this.model.getMediaRecorder().setAudioEncoder(MediaRecorder.AudioEncoder.DEFAULT);
      this.model.getMediaRecorder().setOutputFile(path);
    }

    try {
      this.model.getMediaRecorder().prepare();
      this.model.getMediaRecorder().start();

      final long systemTime = SystemClock.elapsedRealtime();
      this.model.setRecorderTicker(new Runnable() {
        @Override
        public void run() {
          long time = SystemClock.elapsedRealtime() - systemTime;
          Log.d(TAG, "elapsedTime: " + SystemClock.elapsedRealtime());
          Log.d(TAG, "time: " + time);

          DateFormat format = new SimpleDateFormat("mm:ss:SS", Locale.US);
          String displayTime = format.format(time);
          model.setRecordTime(time);
        }
      });
      this.model.getRecorderTicker().run();
    } catch (Exception e) {
      Log.e(TAG, "Exception: ", e);
    }
  }

  @Override
  public void stopRecorder(Result result) {
    if (this.model.getMediaRecorder() == null) {
      Log.d(TAG, "mediaRecorder is null");
      return;
    }
    this.model.getMediaRecorder().stop();
    this.model.getMediaRecorder().release();
    this.model.setMediaRecorder(null);
  }

  @Override
  public void startPlayer(final String path, Result result) {
    if (this.model.getMediaPlayer() != null) {
      Boolean isPaused = !this.model.getMediaPlayer().isPlaying()
          && this.model.getMediaPlayer().getCurrentPosition() > 1;

      if (isPaused) {
        this.model.getMediaPlayer().start();
        return;
      }

      Log.e(TAG, "Player is already running. Stop it first.");
      return;
    } else {
      this.model.setMediaPlayer(new MediaPlayer());
    }
    mTimer = new Timer();

    try {
      if (path == null) {
        this.model.getMediaPlayer().setDataSource(AudioModel.DEFAULT_FILE_LOCATION);
      } else {
        this.model.getMediaPlayer().setDataSource(path);
      }

      this.model.getMediaPlayer().setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(final MediaPlayer mp) {
          Log.d(TAG, "mediaPlayer prepared and start");
          mp.start();

          /*
           * Set timer task to send event to RN.
           */
          TimerTask mTask = new TimerTask() {
            @Override
            public void run() {
              // long time = mp.getCurrentPosition();
              // DateFormat format = new SimpleDateFormat("mm:ss:SS", Locale.US);
              // final String displayTime = format.format(time);
              try {
                JSONObject json = new JSONObject();
                json.put("duration", mp.getDuration());
                json.put("current_position", mp.getCurrentPosition());
                channel.invokeMethod("updateProgress", json);
              } catch (JSONException je) {
                Log.d(TAG, "Json Exception: " + je.toString());
              }
            }
          };

          mTimer.schedule(mTask, 0, model.PLAY_DELAY_MILLIS);
          String resolvedPath = path == null ? AudioModel.DEFAULT_FILE_LOCATION : path;
          result.success((resolvedPath));
        }
      });
      /*
       * Detect when finish playing.
       */
      this.model.getMediaPlayer().setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mp) {
          /*
           * Reset player.
           */
          Log.d(TAG, "Plays completed.");
          try {
            JSONObject json = new JSONObject();
            json.put("duration", mp.getDuration());
            json.put("current_position", mp.getCurrentPosition());
            channel.invokeMethod("audioPlayerDidFinishPlaying", json);
          } catch (JSONException je) {
            Log.d(TAG, "Json Exception: " + je.toString());
          }
          mTimer.cancel();
          mp.stop();
          mp.release();
          model.setMediaPlayer(null);
        }
      });
      this.model.getMediaPlayer().prepare();
    } catch (Exception e) {
      Log.e(TAG, "startPlayer() exception");
    }
  }

  @Override
  public void stopPlayer(Result result) {
    mTimer.cancel();

    if (this.model.getMediaPlayer() == null) {
      return;
    }

    try {
      this.model.getMediaPlayer().stop();
      this.model.getMediaPlayer().release();
      this.model.setMediaPlayer(null);
    } catch (Exception e) {
      Log.e(TAG, "stopPlay exception: " + e.getMessage());
    }
  }

  @Override
  public void pausePlayer(Result result) {
    if (this.model.getMediaPlayer() == null) {
      return;
    }

    try {
      this.model.getMediaPlayer().pause();
    } catch (Exception e) {
      Log.e(TAG, "pausePlay exception: " + e.getMessage());
    }
  }

  @Override
  public void resumePlayer(Result result) {
    if (this.model.getMediaPlayer() == null) {
      return;
    }

    if (this.model.getMediaPlayer().isPlaying()) {
      return;
    }

    try {
      this.model.getMediaPlayer().seekTo(this.model.getMediaPlayer().getCurrentPosition());
      this.model.getMediaPlayer().start();
    } catch (Exception e) {
      Log.e(TAG, "mediaPlayer resume: " + e.getMessage());
    }
  }

  @Override
  public void seekToPlayer(int sec, Result result) {
    if (this.model.getMediaPlayer() == null) {
      return;
    }

    int currentMillis = this.model.getMediaPlayer().getCurrentPosition();
    int millis = sec * 1000 + currentMillis;

    Log.d(TAG, "seekTo: " + millis);

    this.model.getMediaPlayer().seekTo(millis);
  }
}
