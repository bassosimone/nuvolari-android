package net.measurementlab.ndt7.android;

import android.support.annotation.CheckResult;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.cert.X509Certificate;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

// TODO(bassosimone): do we need locking for this class?

public class Client extends WebSocketListener {

    @NonNull
    private final Settings settings;

    private double count = 0.0;
    private boolean rv = true;
    private long t0 = 0;
    private long tLast = 0;
    private final long measurementInterval = TimeUnit.NANOSECONDS.convert(250, TimeUnit.MILLISECONDS);

    @NonNull
    private static final String TAG = "Client";

    public Client(@NonNull Settings settings) {
        this.settings = settings;
    }

    public void onLogInfo(@Nullable String message) {
        Log.d(TAG, "onLogInfo: " + message);
        // NOTHING
    }

    public void onError(@Nullable String error) {
        Log.d(TAG, "onError: " + error);
        // NOTHING
    }

    public void onServerDownloadMeasurement(@NonNull Measurement measurement) {
        Log.d(TAG, "onServerDownloadMeasurement: " + measurement);
        // NOTHING
    }

    public void onClientDownloadMeasurement(@NonNull Measurement measurement){
        Log.d(TAG, "onClientDownloadMeasurement: " + measurement);
        // NOTHING
    }

    @Override
    public final void onOpen(WebSocket ws, Response resp) {
        onLogInfo("WebSocket onOpen");
    }

    @Override
    public final void onMessage(WebSocket ws,
                                String text) {
        onLogInfo("WebSocket onMessage");
        count += text.length();
        periodic();

        @NonNull
        final JSONObject doc;

        // Root
        try {
            doc = new JSONObject(text);
        } catch (JSONException e) {
            Log.d(TAG, "could not parse root json", e);
            return;
        }

        final double elapsed;

        // Top level
        try {
            elapsed = doc.getDouble("elapsed");
        } catch (JSONException e) {
            Log.e(TAG, "did not find valid elapsed time", e);
            return;
        }

        @Nullable
        Measurement.TcpInfo tcpInfo = null;

        // tcp_info
        try {
            JSONObject tcpInfoObject = doc.getJSONObject("tcp_info");

            double smoothedRtt = tcpInfoObject.getDouble("smoothed_rtt");
            double rttVar = tcpInfoObject.getDouble("rtt_var");
            tcpInfo = new Measurement.TcpInfo(smoothedRtt, rttVar);
        } catch (JSONException e) {
            Log.d(TAG, "did not find valid tcp_info", e);
        }

        @Nullable
        Measurement.BBRInfo bbrInfo = null;

        // bbr_info
        try {
            JSONObject bbrInfoObject = doc.getJSONObject("bbr_info");
            long bandwidth = bbrInfoObject.getLong("max_bandwidth");
            double minRtt = bbrInfoObject.getDouble("min_rtt");
            bbrInfo = new Measurement.BBRInfo(bandwidth, minRtt);
        } catch (JSONException e) { }

        @Nullable
        Measurement.AppInfo appInfo = null;

        // app_info
        try {
            JSONObject appInfoObject = doc.getJSONObject("app_info");
            appInfo = new Measurement.AppInfo(appInfoObject.getLong("num_bytes"));
        } catch (JSONException e) { }

        Measurement measurement = new Measurement(elapsed, tcpInfo, bbrInfo, appInfo);
        onServerDownloadMeasurement(measurement);
    }

    @Override
    public final void onMessage(WebSocket ws,
                                ByteString bytes) {
        count += bytes.size();
        periodic();
    }

    @Override
    public final void onClosing(WebSocket ws,
                                int code,
                                String reason) {
        // TODO(bassosimone): make sure code has the correct value otherwise
        // we must return an error to the caller.
        ws.close(1000, null);
    }

    @Override
    public final void onFailure(WebSocket ws,
                                Throwable t,
                                Response r) {
        onError(t.getMessage());
        rv = false;
    }

    @CheckResult
    public boolean runDownload() {
        URI uri;
        try {
            uri = new URI(
                "wss",
                null, // userInfo
                settings.hostname,
                (settings.port >= 0 && settings.port < 65536) ? settings.port : -1,
                "/ndt/v7/download",
                "",
                null
            );
        } catch (URISyntaxException e) {
            Log.e(TAG, "runDownload encountered exception", e);
            onError(e.getMessage());
            return false;
        }

        OkHttpClient.Builder builder = new OkHttpClient.Builder();

        if (settings.skipTlsCertificateVerification) {
            X509TrustManager x509TrustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain,
                                               String authType) { }

                @Override
                public void checkServerTrusted(X509Certificate[] chain,
                                               String authType) { }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[]{};
                }
            };

            try {
                final TrustManager[] trustAllCerts = new TrustManager[]{ x509TrustManager };
                final SSLContext sslContext = SSLContext.getInstance("SSL");
                sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
                final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
                builder.sslSocketFactory(sslSocketFactory, x509TrustManager);
            } catch (Exception e) {
                Log.e(TAG, "Encountered exception", e);
            }

            builder.hostnameVerifier((hostname, session) -> true);
        }

        OkHttpClient client = builder
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build();

        Request request = new Request.Builder()
            .url(uri.toString())
            .addHeader("Sec-WebSocket-Protocol", "net.measurementlab.ndt.v7")
            .build();

        client.newWebSocket(request, this);

        t0 = tLast = System.nanoTime();

        // Basically make the code synchronous here:
        ExecutorService svc = client.dispatcher().executorService();
        svc.shutdown();
        try {
            svc.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // TODO(bassosimone): how to handle this error condition?
            Log.e(TAG, "runDownload awaitTermination encountered exception", e);
        }

        return rv;
    }

    private void periodic() {
        long now = System.nanoTime();

        if (now - tLast > measurementInterval) {
            Measurement measurement = new Measurement(now - t0, null, null, null);
            tLast = now;
            onClientDownloadMeasurement(measurement);
        }
    }
}
