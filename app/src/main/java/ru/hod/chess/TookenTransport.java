package ru.hod.chess;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;
import ru.hod.chess.core.ApiEngine;
import ru.hod.chess.core.ApiModel;

/** No image input, redirects, raw provider error messages, retries or engine fallback. */
final class TookenTransport implements ApiEngine.Transport {
    private static final String ENDPOINT = "https://tooken.club/v1/chat/completions";
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread t = new Thread(task, "hod-api-watchdog"); t.setDaemon(true); return t;
    });
    interface KeySource { String get() throws IOException; }
    interface Connections { HttpsURLConnection open() throws IOException; }
    private final KeySource keySource;
    private final Connections connections;
    private final ApiModel model;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean closed;
    private volatile HttpsURLConnection active;

    TookenTransport(Context context, ApiModel model) {
        this(model, () -> ApiKeyStore.load(context.getApplicationContext()),
                () -> (HttpsURLConnection) new URL(ENDPOINT).openConnection());
    }
    TookenTransport(ApiModel model, KeySource keySource, Connections connections) {
        this.model = model; this.keySource = keySource; this.connections = connections;
    }
    public String choose(ApiEngine.Request request, int timeoutMillis) throws Exception {
        if(timeoutMillis<1||timeoutMillis>ApiEngine.TIMEOUT_MILLIS)throw new IllegalArgumentException("Неверный предел ожидания API");
        if (!busy.compareAndSet(false, true)) throw new IOException("API-запрос уже выполняется");
        HttpsURLConnection connection = null;
        ScheduledFuture<?> guard = null;
        Thread owner = Thread.currentThread();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        AtomicBoolean expired = new AtomicBoolean();
        try {
            requireActive(owner);
            String key;
            try { key = keySource.get(); }
            catch (IOException missing) { throw new ApiFailure("Введи ключ Tooken Club заново в главных настройках ХОД"); }
            JSONObject payload = new JSONObject().put("model", model.id()).put("stream", false)
                    .put(model.isGpt() ? "max_completion_tokens" : "max_tokens", 1024)
                    .put("messages", new JSONArray()
                            .put(new JSONObject().put("role", "system").put("content", ApiEngine.SYSTEM_PROMPT))
                            .put(new JSONObject().put("role", "user").put("content", request.prompt())));
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            if (body.length > 65_536) throw new IOException("API-запрос слишком большой");
            connection = connections.open();
            active = connection;
            requireActive(owner);
            HttpsURLConnection target = connection;
            guard = WATCHDOG.scheduleAtFixedRate(() -> {
                boolean timeout = System.nanoTime() >= deadline;
                if (timeout) expired.set(true);
                if (timeout || closed || owner.isInterrupted()) target.disconnect();
            }, 0, 100, TimeUnit.MILLISECONDS);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(Math.min(8_000, timeoutMillis));
            connection.setReadTimeout(timeoutMillis);
            connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + key);
            connection.setFixedLengthStreamingMode(body.length);
            try (var output = connection.getOutputStream()) { output.write(body); }
            int code = connection.getResponseCode();
            if (code != 200) throw new ApiFailure(statusMessage(code));
            byte[] bytes;
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int length;
                while ((length = input.read(buffer)) != -1) {
                    requireActive(owner);
                    if (output.size() + length > 65_536) throw new ApiFailure("API вернул слишком большой ответ");
                    output.write(buffer, 0, length);
                }
                bytes = output.toByteArray();
            }
            requireActive(owner);
            if (expired.get() || System.nanoTime() >= deadline) throw new ApiFailure("API не ответил за 20 секунд · повтори вручную");
            try {
                JSONObject response = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                if (response.has("error")&&!response.isNull("error")) throw new ApiFailure("Сервис вернул ошибку API · проверь кабинет Tooken Club");
                JSONArray choices = response.getJSONArray("choices");
                if (choices.length() != 1) throw new ApiFailure("Неоднозначный ответ API");
                JSONObject choice = choices.getJSONObject(0);
                if (!"stop".equals(choice.optString("finish_reason"))) throw new ApiFailure("API не завершил ответ · нажми «Повторить API»");
                JSONObject message = choice.getJSONObject("message");
                if (message.has("tool_calls") && !message.isNull("tool_calls") && message.getJSONArray("tool_calls").length() > 0)
                    throw new ApiFailure("API запросил инструмент вместо хода");
                Object content = message.get("content");
                if (!(content instanceof String text) || text.length() > 64) throw new ApiFailure("API не вернул один ход");
                return text;
            } catch (org.json.JSONException malformed) { throw new ApiFailure("Неверный формат ответа API"); }
        } catch (IOException failure) {
            requireActive(owner);
            if (expired.get() || System.nanoTime() >= deadline) throw new IOException("API не ответил за 20 секунд · повтори вручную");
            if (failure instanceof ApiFailure) throw failure;
            // Do not surface provider bodies/headers or exceptions containing credentials.
            throw new IOException("Не удалось выполнить API-запрос · проверь интернет и ключ в настройках");
        } finally {
            if (guard != null) guard.cancel(false);
            if (connection != null) connection.disconnect();
            active = null; busy.set(false);
        }
    }
    private void requireActive(Thread owner) throws InterruptedException {
        if (closed || owner.isInterrupted()) throw new InterruptedException("API-запрос отменён");
    }
    private static String statusMessage(int code) {
        return switch (code) {
            case 400 -> "API отклонил параметры запроса · сверь инструкцию Tooken Club";
            case 401, 403 -> "API отклонил ключ или доступ к модели";
            case 402 -> "Недостаточно баланса Tooken Club";
            case 404 -> "API-модель или endpoint не найдены";
            case 429 -> "Лимит запросов API · подожди и повтори вручную";
            default -> code >= 300 && code < 400 ? "API перенаправил запрос · переход отключён" : "Сервис API недоступен · HTTP " + code;
        };
    }
    public void close() { closed = true; HttpsURLConnection connection = active; if (connection != null) connection.disconnect(); }
    private static final class ApiFailure extends IOException { ApiFailure(String message) { super(message); } }
}
