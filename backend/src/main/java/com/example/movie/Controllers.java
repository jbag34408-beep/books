package com.example.movie;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

record SignupRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_]{3,20}") String username,
        @NotNull @Size(min = 8, max = 72) String password) {} // BCrypt ignores bytes past 72

record RatingRequest(
        @DecimalMin("0.5") @DecimalMax("5.0") double score,
        @Size(max = 2000) String review) {
    @AssertTrue(message = "score must be in 0.5 steps")
    public boolean isHalfStep() {
        return score * 2 == Math.rint(score * 2);
    }
}

record MovieDetail(Movie movie, Double avgScore, long ratingCount) {}

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    AuthController(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    void signup(@Valid @RequestBody SignupRequest req) {
        if (users.existsByUsername(req.username())) throw new ResponseStatusException(HttpStatus.CONFLICT, "username taken");
        var u = new AppUser();
        u.username = req.username();
        u.passwordHash = encoder.encode(req.password());
        try {
            users.save(u);
        } catch (DataIntegrityViolationException e) { // lost a race with a concurrent signup
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username taken");
        }
    }
}

@RestController
@RequestMapping("/api/movies")
class MovieController {
    private final MovieRepository movies;
    private final RatingRepository ratings;
    private final UserRepository users;

    MovieController(MovieRepository movies, RatingRepository ratings, UserRepository users) {
        this.movies = movies;
        this.ratings = ratings;
        this.users = users;
    }

    @GetMapping
    Page<Movie> search(@RequestParam(required = false) String q, @RequestParam(required = false) String genre, Pageable pageable) {
        return movies.search(q, genre, pageable);
    }

    @GetMapping("/popular")
    List<Movie> popular(@RequestParam(defaultValue = "20") int size) {
        return movies.popular(null, PageRequest.of(0, Math.min(size, 100)));
    }

    @GetMapping("/{id}")
    MovieDetail detail(@PathVariable Long id) {
        var stats = ratings.stats(id);
        return new MovieDetail(find(id), stats.avgScore(), stats.ratingCount());
    }

    @GetMapping("/{id}/ratings")
    Page<RatingView> movieRatings(@PathVariable Long id, Pageable pageable) {
        return ratings.forMovie(find(id).id, pageable);
    }

    @PutMapping("/{id}/rating")
    @Transactional
    void rate(@PathVariable Long id, @Valid @RequestBody RatingRequest req, Authentication auth) {
        var user = MeController.user(users, auth);
        var r = ratings.findByUserIdAndMovieId(user.id, id).orElseGet(() -> {
            var n = new Rating();
            n.user = user;
            n.movie = find(id);
            return n;
        });
        r.score = req.score();
        r.review = req.review();
        r.updatedAt = Instant.now();
        ratings.save(r);
    }

    @DeleteMapping("/{id}/rating")
    @Transactional
    void unrate(@PathVariable Long id, Authentication auth) {
        ratings.findByUserIdAndMovieId(MeController.user(users, auth).id, id).ifPresent(ratings::delete);
    }

    private Movie find(Long id) {
        return movies.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "movie not found"));
    }
}

@RestController
@RequestMapping("/api/me")
class MeController {
    private final UserRepository users;
    private final MovieRepository movies;
    private final RatingRepository ratings;
    private final WatchlistRepository watchlist;

    MeController(UserRepository users, MovieRepository movies, RatingRepository ratings, WatchlistRepository watchlist) {
        this.users = users;
        this.movies = movies;
        this.ratings = ratings;
        this.watchlist = watchlist;
    }

    static AppUser user(UserRepository users, Authentication auth) {
        return users.findByUsername(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    @GetMapping("/ratings")
    Page<RatingView> myRatings(Authentication auth, Pageable pageable) {
        return ratings.forUser(user(users, auth).id, pageable);
    }

    @GetMapping("/recommendations")
    List<Movie> recommendations(Authentication auth, @RequestParam(defaultValue = "20") int size) {
        var uid = user(users, auth).id;
        var page = PageRequest.of(0, Math.min(size, 100));
        var recs = movies.recommendedFor(uid, page);
        return recs.isEmpty() ? movies.popular(uid, page) : recs; // cold start: no liked movies yet
    }

    @GetMapping("/watchlist")
    Page<Movie> myWatchlist(Authentication auth, Pageable pageable) {
        return watchlist.moviesOf(user(users, auth).id, pageable);
    }

    @PutMapping("/watchlist/{movieId}")
    @Transactional
    void addToWatchlist(@PathVariable Long movieId, Authentication auth) {
        var user = user(users, auth);
        if (watchlist.findByUserIdAndMovieId(user.id, movieId).isPresent()) return;
        var w = new WatchlistItem();
        w.user = user;
        w.movie = movies.findById(movieId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "movie not found"));
        w.addedAt = Instant.now();
        watchlist.save(w);
    }

    @DeleteMapping("/watchlist/{movieId}")
    @Transactional
    void removeFromWatchlist(@PathVariable Long movieId, Authentication auth) {
        watchlist.findByUserIdAndMovieId(user(users, auth).id, movieId).ifPresent(watchlist::delete);
    }
}
