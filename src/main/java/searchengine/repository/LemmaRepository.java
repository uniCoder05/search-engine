package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.Lemma;

import java.util.List;

@Repository
public interface LemmaRepository extends JpaRepository<Lemma, Integer> {
    @Query(value = "SELECT * FROM Lemma l WHERE l.lemma_text = :lemma AND l.site_id = :siteId FOR UPDATE", nativeQuery = true)
    Lemma lemmaExist(@Param("lemma") String lemma, @Param("siteId") Integer siteId);

    @Query("SELECT COUNT(l) FROM Lemma l WHERE l.site.id = :siteId")
    Integer findCountRecordBySiteId(@Param("siteId") Integer siteId);

    @Query("SELECT l.frequency FROM Lemma l WHERE l.lemma = :lemma AND (:siteId IS NULL OR l.site.id = :siteId)")
    Integer findCountPageByLemma(@Param("lemma") String lemma, @Param("siteId") Integer siteId);

    @Query("SELECT l.id FROM Lemma l WHERE l.lemma = :lemma")
    Integer findIdLemma(@Param("lemma") String lemma);

    @Query("SELECT l FROM Lemma l WHERE l.lemma = :lemma AND (:siteId IS NULL OR l.site.id = :siteId)")
    List<Lemma> findLemmasByLemmaAndSiteId(@Param("lemma") String lemma, @Param("siteId") Integer siteId);
}
