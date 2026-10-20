package ru.hod.chess;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;
import ru.hod.chess.core.*;

/** Uses the real HTTP adapter/JSON codec and an in-memory HTTPS connection; no provider calls. */
public final class TookenTransportTest {
    private static final String FIXTURE_KEY = "fixture-not-a-real-api-key";
    private static ApiEngine.Request request() {
        return new ApiEngine.Request(Position.START, Position.START, List.of(), List.of("e2e4", "d2d4"), "8 r n b q k b n r", false);
    }
    private static String response(String content, String reason) throws Exception {
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", reason)
                .put("message", new JSONObject().put("content", content)))).toString();
    }
    private static final class Connection extends HttpsURLConnection {
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        final CountDownLatch reading = new CountDownLatch(1);
        volatile boolean disconnected, block, errorRead;
        int status = 200, opens;
        String response = "";
        Connection() throws Exception { super(new URL("https://tooken.club/v1/chat/completions")); }
        public void connect() { opens++; }
        public boolean usingProxy() { return false; }
        public void disconnect() { disconnected = true; }
        public String getCipherSuite() { return "TLS_AES_256_GCM_SHA384"; }
        public Certificate[] getLocalCertificates() { return null; }
        public Certificate[] getServerCertificates() { return new Certificate[0]; }
        public OutputStream getOutputStream() { return sent; }
        public int getResponseCode() { return status; }
        public InputStream getErrorStream() { errorRead = true; return new ByteArrayInputStream(FIXTURE_KEY.getBytes(StandardCharsets.UTF_8)); }
        public InputStream getInputStream() {
            reading.countDown();
            if (!block) return new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8));
            return new InputStream() {
                public int read() throws IOException {
                    while (!disconnected) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Interrupted");
                        try { Thread.sleep(5); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new IOException("Interrupted"); }
                    }
                    throw new IOException("Disconnected");
                }
            };
        }
    }
    private static TookenTransport transport(ApiModel model, Connection connection) {
        return new TookenTransport(model, () -> FIXTURE_KEY, () -> connection);
    }
    private static IOException fails(Connection c) throws Exception {
        try (TookenTransport client = transport(ApiModel.SONNET, c)) {
            try { client.choose(request(), 1000); fail("response accepted"); return null; }
            catch (IOException expected) { assertFalse(expected.getMessage().contains(FIXTURE_KEY)); return expected; }
        }
    }
    @Test public void buildsBoundedTextRequestForEveryModel() throws Exception {
        for (ApiModel model : ApiModel.values()) {
            Connection c = new Connection(); c.response = response("e2e4", "stop");
            try (TookenTransport client = transport(model, c)) { assertEquals("e2e4", client.choose(request(), 1000)); }
            JSONObject body = new JSONObject(c.sent.toString(StandardCharsets.UTF_8));
            assertEquals(model.id(), body.getString("model"));
            assertFalse(body.getBoolean("stream"));
            assertEquals(1024, body.getInt(model.isGpt() ? "max_completion_tokens" : "max_tokens"));
            assertFalse(body.has(model.isGpt() ? "max_tokens" : "max_completion_tokens"));
            JSONArray messages = body.getJSONArray("messages"); assertEquals(2, messages.length());
            assertEquals(ApiEngine.SYSTEM_PROMPT, messages.getJSONObject(0).getString("content"));
            assertEquals(request().prompt(), messages.getJSONObject(1).getString("content"));
            assertFalse(body.toString().contains(FIXTURE_KEY)); assertFalse(body.toString().contains("image_url"));
            assertEquals("Bearer " + FIXTURE_KEY, c.getRequestProperty("Authorization"));
            assertEquals("POST", c.getRequestMethod()); assertFalse(c.getInstanceFollowRedirects());
            assertEquals(1000, c.getReadTimeout()); assertTrue(c.disconnected);
        }
    }
    @Test public void neverReadsOrEchoesHttpErrorBodies() throws Exception {
        for (int status : new int[]{301, 307, 400, 401, 402, 403, 404, 429, 500, 503}) {
            Connection c = new Connection(); c.status = status;
            IOException problem = fails(c); assertNotNull(problem.getMessage());
            assertFalse(c.errorRead); assertTrue(c.disconnected);
        }
    }
    @Test public void acceptsExplicitNullError()throws Exception{
        Connection c=new Connection();c.response=new JSONObject(response("e2e4","stop")).put("error",JSONObject.NULL).toString();
        try(TookenTransport client=transport(ApiModel.SONNET,c)){assertEquals("e2e4",client.choose(request(),1000));}
    }
    @Test public void invalidDeadlineNeverOpensConnection()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        try(TookenTransport client=new TookenTransport(ApiModel.SONNET,()->FIXTURE_KEY,()->{calls.incrementAndGet();throw new IOException("must not open");})){
            for(int value:new int[]{-1,0,20_001})try{client.choose(request(),value);fail();}catch(IllegalArgumentException expected){}
            assertEquals(0,calls.get());
        }
    }
    @Test public void refusesIncompleteAmbiguousAndMalformedResponses() throws Exception {
        for (String body : new String[]{"not json", "{}", "{\"choices\":[]}",
                "{\"error\":{\"message\":\"" + FIXTURE_KEY + "\"}}", response("e2e4", "length"),
                response("e2e4", "tool_calls"), response("x".repeat(65), "stop"),
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":null}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":[],\"tool_calls\":[]}}]}",
                "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"e2e4\",\"tool_calls\":[{}]}}]}",
                "{\"choices\":[{},{}]}", "x".repeat(65537)}) {
            Connection c = new Connection(); c.response = body; fails(c);
        }
    }
    @Test public void missingKeyDoesNotOpenConnectionOrLeakException() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        try (TookenTransport client = new TookenTransport(ApiModel.SOL, () -> { throw new IOException(FIXTURE_KEY); }, () -> { opens.incrementAndGet(); throw new IOException("must not open"); })) {
            try { client.choose(request(), 1000); fail(); } catch (IOException expected) { assertFalse(expected.getMessage().contains(FIXTURE_KEY)); }
            assertEquals(0, opens.get());
        }
    }
    @Test public void networkFailureHasNoAutomaticRetryOrRawDetails() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (TookenTransport client = new TookenTransport(ApiModel.SOL, () -> FIXTURE_KEY, () -> { calls.incrementAndGet(); throw new IOException(FIXTURE_KEY); })) {
            try { client.choose(request(), 1000); fail(); } catch (IOException expected) { assertFalse(expected.getMessage().contains(FIXTURE_KEY)); }
            assertEquals(1, calls.get());
        }
    }
    @Test public void deadlineDisconnectsBlockedRead() throws Exception {
        Connection c = new Connection(); c.block = true;
        long start = System.nanoTime();
        try (TookenTransport client = transport(ApiModel.SONNET, c)) {
            try { client.choose(request(), 100); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("API")); }
        }
        assertTrue(c.disconnected); assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
    }
    @Test public void interruptCancelsRequestAndPreservesFlag() throws Exception {
        Connection c = new Connection(); c.block = true; TookenTransport client = transport(ApiModel.SONNET, c);
        AtomicReference<Throwable> failure = new AtomicReference<>(); AtomicBoolean flag = new AtomicBoolean();
        Thread worker = new Thread(() -> { try { client.choose(request(), 3000); } catch (Throwable stop) { failure.set(stop); flag.set(Thread.currentThread().isInterrupted()); } });
        try {
            worker.start(); assertTrue(c.reading.await(2, TimeUnit.SECONDS)); worker.interrupt(); worker.join(2000);
            assertFalse(worker.isAlive()); assertTrue(failure.get() instanceof InterruptedException); assertTrue(flag.get()); assertTrue(c.disconnected);
        } finally { client.close(); worker.interrupt(); worker.join(2000); }
    }
    @Test public void closeCancelsAndRejectsConcurrentAndLaterRequests() throws Exception {
        Connection c = new Connection(); c.block = true; TookenTransport client = transport(ApiModel.SONNET, c);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { client.choose(request(), 3000); } catch (Throwable stop) { failure.set(stop); } });
        try {
            worker.start(); assertTrue(c.reading.await(2, TimeUnit.SECONDS));
            try { client.choose(request(), 100); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("уже")); }
            client.close(); worker.join(2000); assertFalse(worker.isAlive()); assertTrue(failure.get() instanceof InterruptedException);
            try { client.choose(request(), 100); fail(); } catch (InterruptedException expected) { /* closed, no I/O */ }
        } finally { client.close(); worker.interrupt(); worker.join(2000); }
    }
}
