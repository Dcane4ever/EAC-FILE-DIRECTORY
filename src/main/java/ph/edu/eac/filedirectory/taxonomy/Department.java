package ph.edu.eac.filedirectory.taxonomy;

import jakarta.persistence.*;

/**
 * A school/department (e.g. "School of Engineering and Technology").
 * Seeded from the real eacdb registrar dump — sourceDepartmentId links back
 * to that dump's department_id purely for traceability/re-seeding, this app
 * never queries eacdb live.
 */
@Entity
@Table(name = "departments")
public class Department {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** department_id in the original eacdb dump, nullable for departments we add ourselves. */
    @Column(unique = true)
    private Integer sourceDepartmentId;

    @Column(nullable = false, unique = true, length = 10)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    protected Department() {
        // JPA
    }

    public Department(Integer sourceDepartmentId, String code, String name) {
        this.sourceDepartmentId = sourceDepartmentId;
        this.code = code;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Integer getSourceDepartmentId() {
        return sourceDepartmentId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
