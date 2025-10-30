package searchengine.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import searchengine.model.Index;

import java.util.List;

@Repository
public interface IndexRepository extends JpaRepository<Index, Integer> {

    @Query("SELECT i FROM Index i WHERE i.page.id = :pageId AND i.lemma.id = :lemmaId")
    Index findIndexByPageIdAndLemmaId(
            @Param("pageId") Integer pageId,
            @Param("lemmaId") Integer lemmaId
    );

    @Query("SELECT i FROM Index i WHERE i.lemma.id = :lemmaId")
    List<Index> findIndexesByLemma(Integer lemmaId);

    @Query("SELECT i FROM Index i WHERE i.page.id = :pageId")
    List<Index> findAllByPageId(@Param("pageId") Integer pageId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Index i WHERE i.page.id = :pageId")
    void deleteAllByPageId(@Param("pageId") Integer pageId);
}
