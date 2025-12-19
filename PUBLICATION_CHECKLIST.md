# Publication Checklist

When ready to publish this library, complete these steps:

## 1. Convert from Application to Library

### build.gradle changes:
```gradle
plugins {
    id 'java-library'  // Change from 'java'
    id 'org.springframework.boot' version '3.4.0' apply false  // Add 'apply false'
    id 'io.spring.dependency-management' version '1.1.6'
}

// Add dependency management
dependencyManagement {
    imports {
        mavenBom org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES
    }
}

dependencies {
    api 'org.springframework.boot:spring-boot-starter-web'  // Change implementation → api
    api 'org.springframework.boot:spring-boot-starter-validation'
    api 'org.springframework.boot:spring-boot-starter-webflux'
    
    compileOnly 'jakarta.servlet:jakarta.servlet-api:6.0.0'  // Change implementation → compileOnly
    
    // Rest stays the same
}

// Add for publishing
java {
    withSourcesJar()
    withJavadocJar()
}
```

### settings.gradle:
```gradle
rootProject.name = 'composite-spring-boot-starter'  // Rename project
```

### Delete these files:
- [ ] `src/main/java/com/example/composite/CompositeApplication.java` (main class)
- [ ] `src/main/resources/application.properties` (users provide their own)
- [ ] `src/main/resources/application.yml` (if exists)

### Keep these files:
- [x] All service/config/filter/model classes
- [x] `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- [x] Test classes

## 2. Create Example/Demo Project

Create a separate project that uses your library:
```
composite-demo/
├── build.gradle
├── src/
│   └── main/
│       ├── java/
│       │   └── com/example/demo/
│       │       ├── DemoApplication.java
│       │       └── controller/
│       │           └── UserController.java  // Example endpoint with @CompositeEndpoint
│       └── resources/
│           └── application.properties  // Configure composite.* properties
```

### Demo build.gradle:
```gradle
dependencies {
    implementation 'com.example:composite-spring-boot-starter:0.0.1-SNAPSHOT'
    implementation 'org.springframework.boot:spring-boot-starter-web'
}
```

## 3. Documentation

### README.md must include:

- [ ] **Overview**: What the library does
- [ ] **Installation**: Gradle/Maven coordinates
- [ ] **Quick Start**: Minimal working example
- [ ] **Configuration**: All `composite.*` properties with defaults
- [ ] **Usage Examples**:
    - How to annotate endpoints with `@CompositeEndpoint`
    - Sample composite request JSON
    - Sample composite response JSON
- [ ] **Custom Controller**: How to disable default and provide your own
- [ ] **Header Injection**: What headers are added and why
- [ ] **Requirements**: Java 17+, Spring Boot 3.x, WebFlux
- [ ] **Performance Notes**: First request latency
- [ ] **Contributing**: (if open source)
- [ ] **License**: (if open source)

## 4. Publishing Setup

### For Maven Central (if open source):
```gradle
plugins {
    id 'maven-publish'
    id 'signing'
}

publishing {
    publications {
        mavenJava(MavenPublication) {
            from components.java
            
            pom {
                name = 'Composite Spring Boot Starter'
                description = 'Spring Boot starter for composite/batch API endpoints'
                url = 'https://github.com/yourusername/composite-spring-boot-starter'
                
                licenses {
                    license {
                        name = 'The Apache License, Version 2.0'
                        url = 'http://www.apache.org/licenses/LICENSE-2.0.txt'
                    }
                }
                
                developers {
                    developer {
                        id = 'yourusername'
                        name = 'Your Name'
                        email = 'your.email@example.com'
                    }
                }
                
                scm {
                    connection = 'scm:git:git://github.com/yourusername/composite-spring-boot-starter.git'
                    developerConnection = 'scm:git:ssh://github.com/yourusername/composite-spring-boot-starter.git'
                    url = 'https://github.com/yourusername/composite-spring-boot-starter'
                }
            }
        }
    }
    
    repositories {
        maven {
            url = version.endsWith('SNAPSHOT') 
                ? 'https://oss.sonatype.org/content/repositories/snapshots/' 
                : 'https://oss.sonatype.org/service/local/staging/deploy/maven2/'
            credentials {
                username = project.findProperty('ossrhUsername') ?: ''
                password = project.findProperty('ossrhPassword') ?: ''
            }
        }
    }
}

signing {
    sign publishing.publications.mavenJava
}
```

### For company internal repository:
```gradle
publishing {
    repositories {
        maven {
            url = 'https://your-company-nexus/repository/maven-releases/'
            credentials {
                username = project.findProperty('nexusUsername') ?: ''
                password = project.findProperty('nexusPassword') ?: ''
            }
        }
    }
}
```

## 5. Testing Before Publication

- [ ] Create demo project and verify library works as external dependency
- [ ] Test with default configuration
- [ ] Test with custom configuration (all properties)
- [ ] Test with custom controller
- [ ] Test with actuator enabled/disabled
- [ ] Test health endpoint
- [ ] Verify JavaDoc builds without errors: `./gradlew javadoc`
- [ ] Verify sources JAR is created: `./gradlew sourcesJar`
- [ ] Run all tests: `./gradlew test`

## 6. Versioning

Change version from `0.0.1-SNAPSHOT` to proper semantic version:
- `0.1.0` - Initial release
- `0.2.0` - New features (backward compatible)
- `1.0.0` - Production ready
- `1.1.0` - Minor improvements
- `2.0.0` - Breaking changes

## 7. Git/GitHub Setup (if open source)

- [ ] Create GitHub repository
- [ ] Add LICENSE file (Apache 2.0 recommended for Spring ecosystem)
- [ ] Add .gitignore
- [ ] Add comprehensive README
- [ ] Tag releases: `git tag v0.1.0`
- [ ] Create GitHub releases with changelog

## 8. Final Checks

- [ ] No hardcoded values (everything configurable)
- [ ] All public APIs have JavaDoc
- [ ] No System.out.println (use proper logging)
- [ ] No @Autowired field injection (use constructor injection)
- [ ] All beans have @ConditionalOnMissingBean where appropriate
- [ ] Configuration metadata generated (spring-configuration-metadata.json)
- [ ] No dependencies on specific versions (let Spring Boot manage)

## Current Status

- [x] Core functionality working
- [x] Auto-configuration setup
- [x] Properties classes
- [x] Health indicator
- [ ] README.md
- [ ] Example project
- [ ] Publishing configuration
- [ ] Conversion to library structure