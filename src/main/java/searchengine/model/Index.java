package searchengine.model;

import javax.persistence.*;

@Entity
@Table(name = "search_index")
public class Index {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(name = "page_id", columnDefinition = "INT NOT NULL")
    private int pageId;
    @Column(name = "lemma_id", columnDefinition = "INT NOT NULL")
    private int lemmaId;
    @Column(name = "rating", columnDefinition = "FLOAT NOT NULL")
    private float rank;
}
