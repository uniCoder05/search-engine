package searchengine.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(name = "site")
@NoArgsConstructor
@Getter
@Setter
@ToString(exclude = {"pages", "lemmas"})
public class SitePage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Enumerated(EnumType.STRING)
    @NotNull
    private Status status;

    @NotNull
    @Column(name = "status_time", nullable = false)
    private Timestamp statusTime;

    @Column(name = "last_error", length = 255)
    private String lastError;

    @NotBlank
    @Column(nullable = false, length = 255, unique = true)
    private String url;

    @NotBlank
    @Column(nullable = false, length = 255)
    private String name;

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 20)
    private List<Page> pages = new ArrayList<>();

    @OneToMany(mappedBy = "site", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @BatchSize(size = 20)
    private List<Lemma> lemmas = new ArrayList<>();

    public SitePage(Status status, Timestamp statusTime, String lastError, String url, String name) {
        this.status = status;
        this.statusTime = statusTime;
        this.lastError = lastError;
        this.url = url;
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SitePage site = (SitePage) o;
        return Objects.equals(url, site.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(url);
    }

    //Для автоматического обновления statusTime при сохранении SitePage
    @PrePersist
    protected void onPersist() {
        this.statusTime = Timestamp.valueOf(LocalDateTime.now());
    }
}
