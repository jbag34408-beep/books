package com.example.movie;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

record MovieStats(Double avgScore, long ratingCount) {}

record RatingView(Long movieId, String movieTitle, String username, double score, String review, Instant updatedAt) {}

interface MovieRepository extends JpaRepository<Movie, Long> {
    // cast(...) on nullable params: PostgreSQL cannot infer the type of a bare null parameter
    @Query("""
            select distinct m from Movie m left join m.genres g
            where (cast(:q as String) is null or lower(m.title) like lower(concat('%', cast(:q as String), '%')))
              and (cast(:genre as String) is null or g = cast(:genre as String))""")
    Page<Movie> search(String q, String genre, Pageable pageable);

    // Most-rated first, ties broken by average score. Excludes movies the user already rated (uid may be null).
    @Query("""
            select m from Rating r join r.movie m
            where cast(:uid as Long) is null or m.id not in (select x.movie.id from Rating x where x.user.id = :uid)
            group by m order by count(r) desc, avg(r.score) desc""")
    List<Movie> popular(Long uid, Pageable pageable);

    // Item-based collaborative filtering: movies liked (>= 4) by users who liked the same movies as you.
    @Query("""
            select m from Rating mine, Rating peer, Rating rec join rec.movie m
            where mine.user.id = :uid and mine.score >= 4
              and peer.movie = mine.movie and peer.user.id <> :uid and peer.score >= 4
              and rec.user = peer.user and rec.score >= 4
              and m.id not in (select x.movie.id from Rating x where x.user.id = :uid)
            group by m order by count(rec) desc, avg(rec.score) desc""")
    List<Movie> recommendedFor(Long uid, Pageable pageable);
}

interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);
}

interface RatingRepository extends JpaRepository<Rating, Long> {
    Optional<Rating> findByUserIdAndMovieId(Long userId, Long movieId);

    @Query("select new com.example.movie.MovieStats(avg(r.score), count(r)) from Rating r where r.movie.id = :movieId")
    MovieStats stats(Long movieId);

    @Query("""
            select new com.example.movie.RatingView(r.movie.id, r.movie.title, r.user.username, r.score, r.review, r.updatedAt)
            from Rating r where r.movie.id = :movieId order by r.updatedAt desc""")
    Page<RatingView> forMovie(Long movieId, Pageable pageable);

    @Query("""
            select new com.example.movie.RatingView(r.movie.id, r.movie.title, r.user.username, r.score, r.review, r.updatedAt)
            from Rating r where r.user.id = :userId order by r.updatedAt desc""")
    Page<RatingView> forUser(Long userId, Pageable pageable);
}

interface WatchlistRepository extends JpaRepository<WatchlistItem, Long> {
    Optional<WatchlistItem> findByUserIdAndMovieId(Long userId, Long movieId);

    @Query("select w.movie from WatchlistItem w where w.user.id = :userId order by w.addedAt desc")
    Page<Movie> moviesOf(Long userId, Pageable pageable);
}
