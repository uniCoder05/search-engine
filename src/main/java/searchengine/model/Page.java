package searchengine.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "page", uniqueConstraints = {@UniqueConstraint(columnNames = {"path", "site_id"})})
@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(of = {"path", "site"})
@ToString(exclude = {"pageContent", "indices"})
public class Page implements Comparable<Page> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @NotNull
    @Column(nullable = false, length = 255)
    private String path;

    @NotNull
    @Column(name = "response_code", nullable = false)
    private Integer answerCode;

    @Lob
    @NotNull
    @Column(name = "content", nullable = false)
    private String pageContent;

    @OneToMany(mappedBy = "page", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Index> indices = new ArrayList<>();

    public Page(Site site, String path, Integer answerCode, String pageContent) {
        this.site = site;
        this.path = path;
        this.answerCode = answerCode;
        this.pageContent = pageContent;
    }

    @Override
    public int compareTo(Page other) {
        int compareSite = Objects.compare(this.site.getId(), other.site.getId(), Integer::compareTo);
        return compareSite != 0 ? compareSite : this.path.compareTo(other.path);
    }
}