package com.example.movie;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Set;

@Entity
class Movie {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public String title;
    public Integer releaseYear;
    @Column(length = 2000)
    public String overview;
    public String posterUrl;
    @ElementCollection(fetch = FetchType.EAGER)
    @org.hibernate.annotations.BatchSize(size = 100) // load genres for a whole page in one query, not one per movie
    @CollectionTable(name = "movie_genre", joinColumns = @JoinColumn(name = "movie_id"))
    @Column(name = "genre")
    public Set<String> genres;
}

@Entity
@Table(name = "app_user")
class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @Column(nullable = false, unique = true)
    String username;
    @Column(nullable = false)
    String passwordHash;
}

// One rating per user per movie; the review text is optional (Letterboxd-style log entry).
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "movie_id"}))
class Rating {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    AppUser user;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    Movie movie;
    double score;
    @Column(length = 2000)
    String review;
    Instant updatedAt;
}

@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "movie_id"}))
class WatchlistItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    AppUser user;
    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    Movie movie;
    Instant addedAt;
}
