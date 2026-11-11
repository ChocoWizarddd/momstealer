package com.nightlight.app.data.api;

import android.content.Context;

import com.nightlight.app.BuildConfig;
import com.nightlight.app.util.TokenStore;

import java.io.File;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.ConnectionPool;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Central HTTP layer. All REST calls go through this single client:
 * connection pooling, sane timeouts, an HTTP cache and the auth header.
 * UI code never constructs HTTP requests directly.
 */
public final class ApiClient {

    private static volatile OkHttpClient httpClient;
    private static volatile Retrofit retrofit;
    private static volatile MusicApi musicApi;
    private static volatile NightLightApi nightLightApi;

    private ApiClient() {
    }

    public static OkHttpClient httpClient(Context context) {
        if (httpClient == null) {
            synchronized (ApiClient.class) {
                if (httpClient == null) {
                    Context app = context.getApplicationContext();
                    Cache cache = new Cache(new File(app.getCacheDir(), "http_cache"), 12 * 1024 * 1024);

                    Interceptor auth = chain -> {
                        okhttp3.Request.Builder builder = chain.request().newBuilder();
                        String token = TokenStore.getToken();
                        if (token != null && !token.isEmpty()) {
                            builder.header("Authorization", "Bearer " + token);
                        }
                        return chain.proceed(builder.build());
                    };

                    OkHttpClient.Builder builder = new OkHttpClient.Builder()
                            .connectionPool(new ConnectionPool(8, 30, TimeUnit.SECONDS))
                            .connectTimeout(15, TimeUnit.SECONDS)
                            // Cold starts are absorbed by the background
                            // warmup ping in NightLightApp.onCreate, so reads
                            // fail fast instead of hanging the UI for over a
                            // minute when the backend stalls mid-session.
                            .readTimeout(25, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .cache(cache)
                            .addInterceptor(auth);

                    if (BuildConfig.DEBUG) {
                        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
                        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
                        builder.addInterceptor(logging);
                    }

                    httpClient = builder.build();
                }
            }
        }
        return httpClient;
    }

    public static Retrofit retrofit(Context context) {
        if (retrofit == null) {
            synchronized (ApiClient.class) {
                if (retrofit == null) {
                    retrofit = new Retrofit.Builder()
                            .baseUrl(BuildConfig.API_BASE_URL)
                            .client(httpClient(context))
                            .addConverterFactory(GsonConverterFactory.create())
                            .build();
                }
            }
        }
        return retrofit;
    }

    public static MusicApi musicApi(Context context) {
        if (musicApi == null) {
            synchronized (ApiClient.class) {
                if (musicApi == null) {
                    musicApi = retrofit(context).create(MusicApi.class);
                }
            }
        }
        return musicApi;
    }

    public static NightLightApi nightLightApi(Context context) {
        if (nightLightApi == null) {
            synchronized (ApiClient.class) {
                if (nightLightApi == null) {
                    nightLightApi = retrofit(context).create(NightLightApi.class);
                }
            }
        }
        return nightLightApi;
    }
}