package ph.edu.eac.filedirectory.taxonomy;

import jakarta.persistence.*;

/**
 * A file type/category (Research Paper, Thesis, Book, Code, PPTX, PDF, Dataset, etc.).
 * Managed by admins; seeded with a starter set (see DataSeeder).
 */
@Entity
@Table(name = "categories")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String name;

    /** Font Awesome / icon identifier, matching the existing EAC visual style. */
    private String icon;

    protected Category() {
        // JPA
    }

    public Category(String name, String icon) {
        this.name = name;
        this.icon = icon;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }
}
