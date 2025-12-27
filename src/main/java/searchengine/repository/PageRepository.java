package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import searchengine.model.Page;

import java.util.Optional;


@Repository
public interface PageRepository extends JpaRepository<Page, Integer> {

    @Query(value = "SELECT * FROM page p WHERE p.site_id = :siteId AND p.path = :path", nativeQuery = true)
    Optional<Page> getPageBySiteIdAndPath(@Param("siteId") Integer siteId, @Param("path") String path);

    @Query("SELECT COUNT(p) FROM Page p WHERE p.site.id = :siteId")
    Integer findCountRecordBySiteId(@Param("siteId") Integer siteId);

    @Query("SELECT COUNT(p) FROM Page p WHERE (:siteId IS NULL OR p.site.id = :siteId)")
    Integer getCountPages(@Param("siteId") Integer siteId);
}
