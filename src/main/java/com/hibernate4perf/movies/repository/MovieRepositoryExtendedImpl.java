package com.hibernate4perf.movies.repository;

import com.hibernate4perf.movies.domain.Certification;
import com.hibernate4perf.movies.domain.Movie;
import com.hibernate4perf.movies.domain.Movie_;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;

@Repository
public class MovieRepositoryExtendedImpl implements MovieRepositoryExtended {

    private static final Logger LOGGER = LoggerFactory.getLogger(MovieRepositoryExtendedImpl.class);

    @PersistenceContext
    EntityManager entityManager;

    @Transactional
    @Override
    public Movie update(Movie movie) {
        entityManager.getReference(Movie.class, movie.getId());
        return entityManager.merge(movie);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<Movie> findWithCertification(String operation, Certification certif) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Movie> query = builder.createQuery(Movie.class);
        Root<Movie> root = query.from(Movie.class);

        Predicate predicat;
        if ("<".equals(operation)) {
            predicat = builder.lessThan(root.get(Movie_.CERTIFICATION), certif);
        } else if ("<=".equals(operation)) {
            predicat = builder.lessThanOrEqualTo(root.get(Movie_.CERTIFICATION), certif);
        } else if ("=".equals(operation)) {
            predicat = builder.equal(root.get(Movie_.CERTIFICATION), certif);
        } else if (">".equals(operation)) {
            predicat = builder.greaterThan(root.get(Movie_.CERTIFICATION), certif);
        } else if (">=".equals(operation)) {
            predicat = builder.greaterThanOrEqualTo(root.get(Movie_.CERTIFICATION), certif);
        } else {
            throw new IllegalArgumentException("valeur de paramètre de recherche incorrect : " + operation);
        }
        query.where(predicat);

        return entityManager.createQuery(query).getResultList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Movie> getMoviesWithAwardsAndReviews() {
        List<Movie> movies = entityManager
                .createQuery("select distinct m from Movie m left join fetch m.reviews", Movie.class)
                .getResultList();
        return entityManager
                .createQuery("select distinct m from Movie m left join fetch m.awards where m in (:movies)",
                        Movie.class)
                .setParameter("movies", movies)
                .getResultList();

    }

}
