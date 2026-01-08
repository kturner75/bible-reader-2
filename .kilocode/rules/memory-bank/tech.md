# Technology Stack: KJV Bible Reader

## Backend Stack

### Core Framework
- **Java 21** - Latest LTS with modern features (records, pattern matching, virtual threads)
- **Spring Boot 3** - Minimal configuration, REST API support
- **Maven** - Build and dependency management

### Search Engine
- **Apache Lucene** - In-memory full-text search index
  - Standard analyzer for English text
  - Phrase queries for multi-word searches
  - Highlighting for search result context

### Data Storage
- **In-Memory Data** - Full KJV text loaded at startup
  - No external database required
  - Fast O(1) verse lookups by ID
  - Efficient chapter/book navigation

## Frontend Stack

### Core Technologies
- **HTML5** - Semantic markup, accessibility
- **CSS3** - Multi-column layout, CSS Grid/Flexbox
- **Vanilla JavaScript (ES6+)** - No frameworks

### Typography
- **EB Garamond** (Google Fonts) - Primary reading font
  - High legibility for extended reading
  - Classic book typography feel
  - Fallback: Georgia, serif

### Browser APIs
- **LocalStorage** - Persist current reading position
- **History API** - Clean URL navigation
- **ResizeObserver** - Responsive layout calculations

## API Design

### REST Endpoints
```
GET /api/verses?from={id}&count={n}    - Fetch verse range
GET /api/verses/{id}                    - Single verse
GET /api/books                          - List all books
GET /api/books/{id}/chapters            - Chapters for a book
GET /api/search?q={query}               - Full-text search
GET /api/reference/{ref}                - Parse reference string
```

### Response Format
```json
{
  "verses": [
    {
      "id": 1,
      "book": "Genesis",
      "bookId": 1,
      "chapter": 1,
      "verse": 1,
      "text": "In the beginning God created..."
    }
  ]
}
```

## Build & Deployment

### Development
- Maven for backend builds
- Static files served from `src/main/resources/static`
- Single JAR deployment

### Production
- Embedded Tomcat (Spring Boot default)
- Single executable JAR
- No external dependencies (database, cache)

## Key Dependencies (to be added to pom.xml)

```xml
<!-- Spring Boot Starter Web -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<!-- Apache Lucene -->
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-core</artifactId>
    <version>9.x</version>
</dependency>
<dependency>
    <groupId>org.apache.lucene</groupId>
    <artifactId>lucene-queryparser</artifactId>
    <version>9.x</version>
</dependency>
```

## Development Environment
- IDE: IntelliJ IDEA (project in IdeaProjects)
- Java: 21
- OS: macOS
- Shell: zsh
