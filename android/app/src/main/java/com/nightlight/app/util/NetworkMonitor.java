package com.nightlight.app.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

/** Observes connectivity and exposes it as observable state. */
public final class NetworkMonitor {

    private static NetworkMonitor instance;
    private final MutableLiveData<Boolean> online = new MutableLiveData<>(true);
    private final ConnectivityManager.NetworkCallback callback;

    private NetworkMonitor(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                postOnline(true);
            }

            @Override
            public void onLost(Network network) {
                postOnline(false);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                postOnline(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            }
        };
        try {
            cm.registerDefaultNetworkCallback(callback);
        } catch (Exception ignored) {
            // Emulators without connectivity features: stay "online".
        }
    }

    public static synchronized NetworkMonitor get(Context context) {
        if (instance == null) {
            instance = new NetworkMonitor(context.getApplicationContext());
        }
        return instance;
    }

    public LiveData<Boolean> online() {
        return online;
    }

    public boolean isOnline() {
        return Boolean.TRUE.equals(online.getValue());
    }

    private void postOnline(boolean value) {
        AppExecutors.onMain(() -> {
            if (!Boolean.valueOf(value).equals(online.getValue())) {
                online.setValue(value);
            }
        });
    }
}