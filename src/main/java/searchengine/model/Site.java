package searchengine.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "site")
@NoArgsConstructor
@Getter
@Setter
public class Site {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    @Column(columnDefinition = "ENUM('INDEXING', 'INDEXED', 'FILED') NOT NULL")
    @Enumerated(EnumType.STRING)
    private Status status;
    @Column(columnDefinition = "DATETIME NOT NULL")
    private LocalDateTime statusTime;
    @Column(columnDefinition = "TEXT NULL")
    private String lastError;
    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String url;
    @Column(columnDefinition = "VARCHAR(255) NOT NULL")
    private String name;
    @OneToMany(mappedBy = "site")
    private List<Page> pages;
}
