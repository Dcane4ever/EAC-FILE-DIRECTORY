package ph.edu.eac.filedirectory.taxonomy;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProgramRepository extends JpaRepository<Program, Long> {
    Optional<Program> findByCode(String code);
    Optional<Program> findBySourceProgramId(Integer sourceProgramId);
    List<Program> findByDepartmentId(Long departmentId);
}
