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

@NoArgsConstructor
@Getter
@Setter
@EqualsAndHashCode(of = {"lemma", "site"})
@ToString(exclude = {"indexes"})
@Entity
@Table(name = "lemma", uniqueConstraints = {@UniqueConstraint(columnNames = {"lemma_text", "site_id"})})
public class Lemma implements Comparable<Lemma> {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id", nullable = false)
    private Site site;

    @NotNull
    @Column(name = "lemma_text", nullable = false, length = 255)
    private String lemma;

    @NotNull
    @Column(nullable = false)
    private Integer frequency;

    @OneToMany(mappedBy = "lemma", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Index> indexes = new ArrayList<>();

    public Lemma(String lemma, Site site) {
        this.lemma = lemma;
        this.site = site;
        this.frequency = 0;
    }

    public Lemma(String lemma, Integer frequency, Site site) {
        this.lemma = lemma;
        this.frequency = frequency;
        this.site = site;
    }

    public void increaseFrequency() {
        this.frequency++;
    }

    public void decreaseFrequency() {
        this.frequency--;
    }

    public void increaseFrequencyByValue(int value) {
        this.frequency += value;
    }

    @Override
    public int compareTo(Lemma other) {
        int compareLemma = this.lemma.compareTo(other.lemma);
        return compareLemma != 0 ? compareLemma : Integer.compare(this.site.getId(), other.site.getId());
    }
}