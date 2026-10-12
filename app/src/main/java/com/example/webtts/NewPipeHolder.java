package com.example.webtts;

import okhttp3.OkHttpClient;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;
import org.schabi.newpipe.extractor.localization.Localization;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * V2.2: Khoi tao NewPipeExtractor 1 lan (copy tu youtube-lite/NewPipeHolder).
 * Dung OkHttp 3.14.9 (tuong thich Java 8 / minSdk 29).
 */
public final class NewPipeHolder {
    private static volatile boolean inited = false;

    private NewPipeHolder() {}

    public static synchronized void initIfNeeded() {
        if (inited) return;
        final OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build();

        Downloader downloader = new Downloader() {
            @Override
            public org.schabi.newpipe.extractor.downloader.Response execute(
                    org.schabi.newpipe.extractor.downloader.Request request)
                    throws IOException, ReCaptchaException {
                String method = request.httpMethod();
                String url = request.url();
                Map<String, List<String>> headers = request.headers();
                byte[] dataToSend = request.dataToSend();

                okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(url);
                if (headers != null) {
                    for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                        if (e.getValue() == null) continue;
                        for (String v : e.getValue()) {
                            builder.addHeader(e.getKey(), v);
                        }
                    }
                }
                if ("POST".equalsIgnoreCase(method) && dataToSend != null) {
                    builder.post(okhttp3.RequestBody.create(null, dataToSend));
                } else if ("HEAD".equalsIgnoreCase(method)) {
                    builder.head();
                } else {
                    builder.get();
                }
                builder.header("User-Agent",
                        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36");

                try (okhttp3.Response response = client.newCall(builder.build()).execute()) {
                    int code = response.code();
                    String message = response.message() != null ? response.message() : "";
                    Map<String, List<String>> respHeaders = new HashMap<>();
                    if (response.headers() != null) {
                        for (String name : response.headers().names()) {
                            respHeaders.put(name, new ArrayList<>(response.headers().values(name)));
                        }
                    }
                    String body = response.body() != null ? response.body().string() : "";
                    String latestUrl = response.request() != null
                            && response.request().url() != null
                            ? response.request().url().toString() : url;
                    return new org.schabi.newpipe.extractor.downloader.Response(
                            code, message, respHeaders, body, latestUrl);
                }
            }
        };

        NewPipe.init(downloader, Localization.DEFAULT);
        inited = true;
    }
}
