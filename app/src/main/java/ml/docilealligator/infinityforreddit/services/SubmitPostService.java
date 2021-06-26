package ml.docilealligator.infinityforreddit.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.bumptech.glide.Glide;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;

import ml.docilealligator.infinityforreddit.Flair;
import ml.docilealligator.infinityforreddit.Infinity;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper;
import ml.docilealligator.infinityforreddit.events.SubmitCrosspostEvent;
import ml.docilealligator.infinityforreddit.events.SubmitImagePostEvent;
import ml.docilealligator.infinityforreddit.events.SubmitTextOrLinkPostEvent;
import ml.docilealligator.infinityforreddit.events.SubmitVideoOrGifPostEvent;
import ml.docilealligator.infinityforreddit.post.Post;
import ml.docilealligator.infinityforreddit.post.SubmitPost;
import ml.docilealligator.infinityforreddit.utils.APIUtils;
import ml.docilealligator.infinityforreddit.utils.NotificationUtils;
import retrofit2.Retrofit;

public class SubmitPostService extends Service {
    public static final String EXTRA_ACCESS_TOKEN = "EAT";
    public static final String EXTRA_SUBREDDIT_NAME = "ESN";
    public static final String EXTRA_TITLE = "ET";
    public static final String EXTRA_CONTENT = "EC";
    public static final String EXTRA_KIND = "EK";
    public static final String EXTRA_FLAIR = "EF";
    public static final String EXTRA_IS_SPOILER = "EIS";
    public static final String EXTRA_IS_NSFW = "EIN";
    public static final String EXTRA_POST_TYPE = "EPT";
    public static final int EXTRA_POST_TEXT_OR_LINK = 0;
    public static final int EXTRA_POST_TYPE_IMAGE = 1;
    public static final int EXTRA_POST_TYPE_VIDEO = 2;
    public static final int EXTRA_POST_TYPE_CROSSPOST = 3;
    private static final String EXTRA_MEDIA_URI = "EU";
    @Inject
    @Named("oauth")
    Retrofit mOauthRetrofit;
    @Inject
    @Named("upload_media")
    Retrofit mUploadMediaRetrofit;
    @Inject
    @Named("upload_video")
    Retrofit mUploadVideoRetrofit;
    @Inject
    CustomThemeWrapper mCustomThemeWrapper;
    @Inject
    Executor mExecutor;
    private Handler handler;
    private ServiceHandler serviceHandler;

    public SubmitPostService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Handler that receives messages from the thread
    private final class ServiceHandler extends Handler {
        public ServiceHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            Bundle bundle = msg.getData();
            String accessToken = bundle.getString(EXTRA_ACCESS_TOKEN);
            String subredditName = bundle.getString(EXTRA_SUBREDDIT_NAME);
            String title = bundle.getString(EXTRA_TITLE);
            Flair flair = bundle.getParcelable(EXTRA_FLAIR);
            boolean isSpoiler = bundle.getBoolean(EXTRA_IS_SPOILER, false);
            boolean isNSFW = bundle.getBoolean(EXTRA_IS_NSFW, false);
            int postType = bundle.getInt(EXTRA_POST_TYPE, EXTRA_POST_TEXT_OR_LINK);

            if (postType == EXTRA_POST_TEXT_OR_LINK) {
                String content = bundle.getString(EXTRA_CONTENT);
                String kind = bundle.getString(EXTRA_KIND);
                submitTextOrLinkPost(accessToken, subredditName, title, content, flair, isSpoiler, isNSFW, kind);
            } else if (postType == EXTRA_POST_TYPE_CROSSPOST) {
                String content = bundle.getString(EXTRA_CONTENT);
                submitCrosspost(mExecutor, handler, accessToken, subredditName, title, content,
                        flair, isSpoiler, isNSFW);
            } else if (postType == EXTRA_POST_TYPE_IMAGE) {
                Uri mediaUri = Uri.parse(bundle.getString(EXTRA_MEDIA_URI));
                submitImagePost(accessToken, mediaUri, subredditName, title, flair, isSpoiler, isNSFW);
            } else {
                Uri mediaUri = Uri.parse(bundle.getString(EXTRA_MEDIA_URI));
                submitVideoPost(accessToken, mediaUri, subredditName, title, flair, isSpoiler, isNSFW);
            }
        }
    }

    @Override
    public void onCreate() {
        ((Infinity) getApplication()).getAppComponent().inject(this);
        // Start up the thread running the service. Note that we create a
        // separate thread because the service normally runs in the process's
        // main thread, which we don't want to block. We also make it
        // background priority so CPU-intensive work doesn't disrupt our UI.
        handler = new Handler();
        HandlerThread thread = new HandlerThread("ServiceStartArguments",
                Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        serviceHandler = new ServiceHandler(thread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ((Infinity) getApplication()).getAppComponent().inject(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NotificationUtils.CHANNEL_SUBMIT_POST,
                    NotificationUtils.CHANNEL_SUBMIT_POST,
                    NotificationManager.IMPORTANCE_LOW
            );

            NotificationManagerCompat manager = NotificationManagerCompat.from(this);
            manager.createNotificationChannel(serviceChannel);
        }

        int randomNotificationIdOffset = new Random().nextInt(10000);
        int postType = intent.getIntExtra(EXTRA_POST_TYPE, EXTRA_POST_TEXT_OR_LINK);
        Bundle bundle = intent.getExtras();

        if (postType == EXTRA_POST_TEXT_OR_LINK) {
            startForeground(NotificationUtils.SUBMIT_POST_SERVICE_NOTIFICATION_ID + randomNotificationIdOffset, createNotification(R.string.posting));
        } else if (postType == EXTRA_POST_TYPE_CROSSPOST) {
            startForeground(NotificationUtils.SUBMIT_POST_SERVICE_NOTIFICATION_ID + randomNotificationIdOffset, createNotification(R.string.posting));
        } else if (postType == EXTRA_POST_TYPE_IMAGE) {
            bundle.putString(EXTRA_MEDIA_URI, intent.getData().toString());
            startForeground(NotificationUtils.SUBMIT_POST_SERVICE_NOTIFICATION_ID + randomNotificationIdOffset, createNotification(R.string.posting_image));
        } else {
            bundle.putString(EXTRA_MEDIA_URI, intent.getData().toString());
            startForeground(NotificationUtils.SUBMIT_POST_SERVICE_NOTIFICATION_ID + randomNotificationIdOffset, createNotification(R.string.posting_video));
        }

        Message msg = serviceHandler.obtainMessage();
        msg.setData(bundle);
        serviceHandler.sendMessage(msg);

        return START_NOT_STICKY;
    }

    private Notification createNotification(int stringResId) {
        return new NotificationCompat.Builder(this, NotificationUtils.CHANNEL_SUBMIT_POST)
                .setContentTitle(getString(stringResId))
                .setContentText(getString(R.string.please_wait))
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(mCustomThemeWrapper.getColorPrimaryLightTheme())
                .build();
    }

    private void submitTextOrLinkPost(String accessToken, String subredditName, String title, String content, Flair flair, boolean isSpoiler, boolean isNSFW, String kind) {
        SubmitPost.submitTextOrLinkPost(mExecutor, handler, mOauthRetrofit, accessToken,
                getResources().getConfiguration().locale, subredditName, title, content, flair, isSpoiler,
                isNSFW, kind, new SubmitPost.SubmitPostListener() {
                    @Override
                    public void submitSuccessful(Post post) {
                        handler.post(() -> EventBus.getDefault().post(new SubmitTextOrLinkPostEvent(true, post, null)));

                        stopService();
                    }

                    @Override
                    public void submitFailed(@Nullable String errorMessage) {
                        handler.post(() -> EventBus.getDefault().post(new SubmitTextOrLinkPostEvent(false, null, errorMessage)));

                        stopService();
                    }
                });
    }

    private void submitCrosspost(Executor executor, Handler handler, String accessToken, String subredditName,
                                 String title, String content, Flair flair, boolean isSpoiler, boolean isNSFW) {
        SubmitPost.submitCrosspost(executor, handler, mOauthRetrofit, accessToken, subredditName, title,
                content, flair, isSpoiler, isNSFW, APIUtils.KIND_CROSSPOST, new SubmitPost.SubmitPostListener() {
                    @Override
                    public void submitSuccessful(Post post) {
                        handler.post(() -> EventBus.getDefault().post(new SubmitCrosspostEvent(true, post, null)));

                        stopService();
                    }

                    @Override
                    public void submitFailed(@Nullable String errorMessage) {
                        handler.post(() -> EventBus.getDefault().post(new SubmitCrosspostEvent(false, null, errorMessage)));

                        stopService();
                    }
                });
    }

    private void submitImagePost(String accessToken, Uri mediaUri, String subredditName, String title, Flair flair, boolean isSpoiler, boolean isNSFW) {
        try {
            Bitmap resource = Glide.with(this).asBitmap().load(mediaUri).submit().get();
            SubmitPost.submitImagePost(mExecutor, handler, mOauthRetrofit, mUploadMediaRetrofit,
                    accessToken, subredditName, title, resource, flair, isSpoiler, isNSFW,
                    new SubmitPost.SubmitPostListener() {
                        @Override
                        public void submitSuccessful(Post post) {
                            handler.post(() -> {
                                EventBus.getDefault().post(new SubmitImagePostEvent(true, null));
                                Toast.makeText(SubmitPostService.this, R.string.image_is_processing, Toast.LENGTH_SHORT).show();
                            });

                            stopService();
                        }

                        @Override
                        public void submitFailed(@Nullable String errorMessage) {
                            handler.post(() -> EventBus.getDefault().post(new SubmitImagePostEvent(false, errorMessage)));

                            stopService();
                        }
                    });
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
            handler.post(() -> EventBus.getDefault().post(new SubmitImagePostEvent(false, getString(R.string.error_processing_image))));
            stopService();
        }
    }

    private void submitVideoPost(String accessToken, Uri mediaUri, String subredditName, String title,
                                 Flair flair, boolean isSpoiler, boolean isNSFW) {
        try {
            InputStream in = getContentResolver().openInputStream(mediaUri);
            String type = getContentResolver().getType(mediaUri);
            String cacheFilePath;
            if (type != null && type.contains("gif")) {
                cacheFilePath = getExternalCacheDir() + "/" + mediaUri.getLastPathSegment() + ".gif";
            } else {
                cacheFilePath = getExternalCacheDir() + "/" + mediaUri.getLastPathSegment() + ".mp4";
            }

            copyFileToCache(in, cacheFilePath);

            Bitmap resource = Glide.with(this).asBitmap().load(mediaUri).submit().get();

            if (type != null) {
                SubmitPost.submitVideoPost(mExecutor, handler, mOauthRetrofit, mUploadMediaRetrofit,
                        mUploadVideoRetrofit, accessToken, subredditName, title, new File(cacheFilePath),
                        type, resource, flair, isSpoiler, isNSFW, new SubmitPost.SubmitPostListener() {
                            @Override
                            public void submitSuccessful(Post post) {
                                handler.post(() -> {
                                    EventBus.getDefault().post(new SubmitVideoOrGifPostEvent(true, false, null));
                                    if (type.contains("gif")) {
                                        Toast.makeText(SubmitPostService.this, R.string.gif_is_processing, Toast.LENGTH_SHORT).show();
                                    } else {
                                        Toast.makeText(SubmitPostService.this, R.string.video_is_processing, Toast.LENGTH_SHORT).show();
                                    }
                                });


                                stopService();
                            }

                            @Override
                            public void submitFailed(@Nullable String errorMessage) {
                                handler.post(() -> EventBus.getDefault().post(new SubmitVideoOrGifPostEvent(false, false, errorMessage)));

                                stopService();
                            }
                        });
            } else {
                handler.post(() -> EventBus.getDefault().post(new SubmitVideoOrGifPostEvent(false, true, null)));

                stopService();
            }
        } catch (IOException | InterruptedException | ExecutionException e) {
            e.printStackTrace();
            handler.post(() -> EventBus.getDefault().post(new SubmitVideoOrGifPostEvent(false, true, null)));

            stopService();
        }
    }

    private static void copyFileToCache(InputStream fileInputStream, String destinationFilePath) throws IOException {
        OutputStream out = new FileOutputStream(destinationFilePath);
        byte[] buf = new byte[2048];
        int len;
        while ((len = fileInputStream.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
    }

    private void stopService() {
        stopForeground(true);
        stopSelf();
    }
}
