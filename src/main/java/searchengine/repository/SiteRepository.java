package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.Site;

@Repository
public interface SiteRepository extends JpaRepository<Site, Integer> {
    @Query(value = "SELECT * FROM Site s WHERE s.url = :host limit 1", nativeQuery = true)
    Site getSitePageByUrl(@Param("host") String host);
}
