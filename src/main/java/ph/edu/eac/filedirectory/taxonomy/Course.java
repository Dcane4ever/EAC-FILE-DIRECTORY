package ph.edu.eac.filedirectory.taxonomy;

import jakarta.persistence.*;

/**
 * A course under a department (e.g. "CC101 - Introduction to Computing").
 * The source eacdb dump links courses to department_id only (not program_id),
 * so we mirror that; program association for files (if any) is set per-file,
 * not derived from the course.
 */
@Entity
@Table(name = "courses")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** course_id in the original eacdb dump, nullable for courses we add ourselves. */
    @Column(unique = true)
    private Integer sourceCourseId;

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String title;

    @ManyToOne(optional = false)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(length = 1000)
    private String description;

    protected Course() {
        // JPA
    }

    public Course(Integer sourceCourseId, String code, String title, Department department, String description) {
        this.sourceCourseId = sourceCourseId;
        this.code = code;
        this.title = title;
        this.department = department;
        this.description = description;
    }

    public Long getId() {
        return id;
    }

    public Integer getSourceCourseId() {
        return sourceCourseId;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public Department getDepartment() {
        return department;
    }

    public String getDescription() {
        return description;
    }
}
