package ph.edu.eac.filedirectory.taxonomy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseRepository extends JpaRepository<Course, Long> {
    Optional<Course> findBySourceCourseId(Integer sourceCourseId);
    List<Course> findByDepartmentId(Long departmentId);
}
