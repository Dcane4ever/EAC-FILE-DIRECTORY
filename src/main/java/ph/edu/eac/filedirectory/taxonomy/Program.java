package ph.edu.eac.filedirectory.taxonomy;

import jakarta.persistence.*;

/**
 * A degree program under a department (e.g. BSIT under School of Engineering
 * and Technology). Treated as a first-class level between department and
 * course, per PLAN.md §4a: School/Department -> Program -> Course -> Files.
 */
@Entity
@Table(name = "programs")
public class Program {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** program_id in the original eacdb dump, nullable for programs we add ourselves. */
    @Column(unique = true)
    private Integer sourceProgramId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @ManyToOne(optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    /** e.g. "COLLEGE" - kept as free text to match the source data's variety. */
    private String level;

    private Integer durationYears;

    protected Program() {
        // JPA
    }

    public Program(Integer sourceProgramId, String code, String name, Department department,
                    String level, Integer durationYears) {
        this.sourceProgramId = sourceProgramId;
        this.code = code;
        this.name = name;
        this.department = department;
        this.level = level;
        this.durationYears = durationYears;
    }

    public Long getId() {
        return id;
    }

    public Integer getSourceProgramId() {
        return sourceProgramId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Department getDepartment() {
        return department;
    }

    public String getLevel() {
        return level;
    }

    public Integer getDurationYears() {
        return durationYears;
    }
}
