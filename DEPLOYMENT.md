# EAC File Directory WAR Deployment

This project is packaged as a WAR for an external Apache Tomcat server.

## Build

Run:

```bat
package-war.bat
```

The output is:

```text
target\eacmnl#filedirectory.war
```

## Tomcat Target

Use:

- Java 21
- Apache Tomcat compatible with Spring Boot 4 / Jakarta Servlet APIs
- MySQL or MariaDB with the `eac_directory` schema
- A persistent local storage folder for uploaded files
- ONLYOFFICE Document Server reachable by the browser and by the app

## Deployment Name

Copy the WAR to Tomcat `webapps`.

Always deploy it with this exact name:

```text
eacmnl#filedirectory.war
```

the app runs at:

```text
http://server:8084/eacmnl/filedirectory
```

Keep this in sync with `APP_BASE_URL` and `ONLYOFFICE_APP_BASE_URL`.

## Required Production Configuration

Set these as environment variables, Tomcat service variables, or values loaded from a `.env` file in Tomcat's working directory.

```properties
DB_URL=jdbc:mysql://localhost:3306/eac_directory?useSSL=false&serverTimezone=UTC
DB_USERNAME=root
DB_PASSWORD=

MAIL_USERNAME=your-eac-mailbox@example.com
MAIL_APP_PASSWORD=your-mail-app-password

APP_BASE_URL=http://server:8084/eacmnl/filedirectory

EAC_STORAGE_ROOT=C:/eac-file-directory/data

ONLYOFFICE_URL=http://server:8085
ONLYOFFICE_JWT_ENABLED=true
ONLYOFFICE_JWT_SECRET=change-this-to-a-long-shared-secret
ONLYOFFICE_APP_BASE_URL=http://host.docker.internal:8084/eacmnl/filedirectory
```

## ONLYOFFICE

For local Docker Compose:

```bat
docker compose up -d onlyoffice
```

The secret in `.env` or the server environment must match:

```text
ONLYOFFICE_JWT_SECRET
```

and Docker Compose's:

```text
JWT_SECRET
```

## Important Notes

- Do not use the `test` Spring profile in production.
- Do not store uploaded files inside Tomcat `webapps`; use a persistent folder such as `C:/eac-file-directory/data`.
- `spring.jpa.hibernate.ddl-auto=update` is still enabled for now. Before final production launch, replace this with proper database migrations.
- The WAR filename `eacmnl#filedirectory.war` maps to the Tomcat context path `/eacmnl/filedirectory`.
- Document preview URLs must include `/eacmnl/filedirectory` in `APP_BASE_URL` and `ONLYOFFICE_APP_BASE_URL`.

## Smoke Test

After deployment:

1. Open the app URL.
2. Register or log in with an `@eac.edu.ph` account.
3. Browse files.
4. Open a PDF file detail page and confirm inline PDF preview works.
5. Open a DOCX/PPTX file detail page and confirm inline ONLYOFFICE preview works.
6. Upload a test file and confirm it appears in the admin approval queue.
7. Approve the test file and confirm it appears in Browse.
