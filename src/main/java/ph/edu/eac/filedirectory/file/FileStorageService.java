package ph.edu.eac.filedirectory.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import ph.edu.eac.filedirectory.taxonomy.Department;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Year;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Handles writing uploaded files to local disk under eac.storage.root, in a
 * <department>/<year>/<uuid>_<original-name> layout, and reading them back
 * for download. The database only ever stores the resulting relative path.
 */
@Service
public class FileStorageService {

    private final Path storageRoot;

    public FileStorageService(@Value("${eac.storage.root}") String storageRoot) {
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create storage root: " + this.storageRoot, e);
        }
    }

    public record StoredFile(String relativePath, long size, String checksumSha256) {
    }

    public StoredFile store(MultipartFile upload, Department department) {
        String originalName = StringUtils.cleanPath(upload.getOriginalFilename() == null ? "file" : upload.getOriginalFilename());
        String safeName = originalName.replaceAll("[^a-zA-Z0-9._-]", "_");
        String year = String.valueOf(Year.now().getValue());
        String storedName = UUID.randomUUID() + "_" + safeName;
        String relativePath = department.getCode() + "/" + year + "/" + storedName;

        Path target = storageRoot.resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid file path");
        }

        try {
            Files.createDirectories(target.getParent());
            String checksum;
            try (InputStream in = upload.getInputStream()) {
                checksum = copyAndHash(in, target);
            }
            long size = Files.size(target);
            return new StoredFile(relativePath, size, checksum);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store uploaded file", e);
        }
    }

    public Path resolve(String relativePath) {
        Path target = storageRoot.resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new IllegalArgumentException("Invalid file path");
        }
        return target;
    }

    private String copyAndHash(InputStream in, Path target) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                 var digestOut = new java.security.DigestOutputStream(out, digest)) {
                in.transferTo(digestOut);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
