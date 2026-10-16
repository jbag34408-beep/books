package com.example.movie;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiTest {
    static final EmbeddedPostgres pg;

    static {
        try {
            pg = EmbeddedPostgres.start();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> pg.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
    }

    @LocalServerPort
    int port;
    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> call(String method, String path, String user, String json) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":password123").getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    void signup(String name) throws Exception {
        assertEquals(201, call("POST", "/api/auth/signup", null, "{\"username\":\"" + name + "\",\"password\":\"password123\"}").statusCode());
    }

    @Test
    void ratingAndRecommendationFlow() throws Exception {
        // public catalog, Korean seed data decoded correctly
        var search = call("GET", "/api/movies?genre=SF&sort=releaseYear", null, null);
        assertEquals(200, search.statusCode());
        assertTrue(search.body().contains("인터스텔라"), search.body());
        assertFalse(search.body().contains("기생충"));

        signup("alice");
        signup("bob");
        assertEquals(409, call("POST", "/api/auth/signup", null, "{\"username\":\"alice\",\"password\":\"password123\"}").statusCode());
        assertEquals(400, call("POST", "/api/auth/signup", null, "{\"username\":\"x\",\"password\":\"short\"}").statusCode());

        // writes need auth, scores must be 0.5..5 in half steps
        assertEquals(401, call("PUT", "/api/movies/1/rating", null, "{\"score\":4}").statusCode());
        assertEquals(400, call("PUT", "/api/movies/1/rating", "alice", "{\"score\":4.3}").statusCode());
        assertEquals(400, call("PUT", "/api/movies/1/rating", "alice", "{\"score\":5.5}").statusCode());
        assertEquals(404, call("PUT", "/api/movies/999/rating", "alice", "{\"score\":4}").statusCode());

        // alice and bob both love 기생충 (1); bob also loves 올드보이 (2) -> recommend 2 to alice
        assertEquals(200, call("PUT", "/api/movies/1/rating", "alice", "{\"score\":5,\"review\":\"최고\"}").statusCode());
        assertEquals(200, call("PUT", "/api/movies/1/rating", "alice", "{\"score\":4.5,\"review\":\"다시 보니 4.5\"}").statusCode()); // upsert
        assertEquals(200, call("PUT", "/api/movies/1/rating", "bob", "{\"score\":5}").statusCode());
        assertEquals(200, call("PUT", "/api/movies/2/rating", "bob", "{\"score\":4.5}").statusCode());
        assertEquals(200, call("PUT", "/api/movies/3/rating", "bob", "{\"score\":2}").statusCode());

        var detail = call("GET", "/api/movies/1", null, null).body();
        assertTrue(detail.contains("\"avgScore\":4.75") && detail.contains("\"ratingCount\":2"), detail);
        var reviews = call("GET", "/api/movies/1/ratings", null, null).body();
        assertTrue(reviews.contains("다시 보니 4.5") && !reviews.contains("passwordHash"), reviews);

        var recs = call("GET", "/api/me/recommendations", "alice", null).body();
        // 올드보이 only: not the disliked 살인의 추억 or the already-rated 기생충
        assertTrue(recs.contains("올드보이") && !recs.contains("살인의 추억") && !recs.contains("기생충"), recs);

        // cold start: carol has rated nothing -> popular fallback, most-rated first
        signup("carol");
        var popular = call("GET", "/api/me/recommendations", "carol", null).body();
        assertTrue(popular.indexOf("기생충") >= 0 && popular.indexOf("기생충") < popular.indexOf("올드보이"), popular);
        var anonPopular = call("GET", "/api/movies/popular", null, null).body();
        assertTrue(anonPopular.indexOf("기생충") >= 0 && anonPopular.indexOf("기생충") < anonPopular.indexOf("올드보이"), anonPopular);
        var byTitle = call("GET", "/api/movies?q=" + java.net.URLEncoder.encode("인터", StandardCharsets.UTF_8), null, null).body();
        assertTrue(byTitle.contains("인터스텔라") && byTitle.contains("\"totalElements\":1"), byTitle);

        // watchlist is idempotent
        assertEquals(200, call("PUT", "/api/me/watchlist/7", "alice", null).statusCode());
        assertEquals(200, call("PUT", "/api/me/watchlist/7", "alice", null).statusCode());
        var wl = call("GET", "/api/me/watchlist", "alice", null).body();
        assertTrue(wl.contains("인터스텔라") && wl.contains("\"totalElements\":1"), wl);
        assertEquals(200, call("DELETE", "/api/me/watchlist/7", "alice", null).statusCode());
        assertTrue(call("GET", "/api/me/watchlist", "alice", null).body().contains("\"totalElements\":0"));
    }
}
