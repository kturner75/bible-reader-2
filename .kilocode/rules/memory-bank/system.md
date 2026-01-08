# System Architecture: KJV Bible Reader

## High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        Frontend (Browser)                        │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │              Vanilla JavaScript Application                 ││
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐││
│  │  │   Reading   │ │  Navigation │ │    Search Interface     │││
│  │  │    View     │ │   Controls  │ │    (Smart Search)       │││
│  │  └─────────────┘ └─────────────┘ └─────────────────────────┘││
│  │  ┌─────────────────────────────────────────────────────────┐││
│  │  │              Page Layout Engine                         │││
│  │  │   (Dynamic viewport-fitted two-column pagination)       │││
│  │  └─────────────────────────────────────────────────────────┘││
│  │  ┌─────────────────────────────────────────────────────────┐││
│  │  │              LocalStorage Layer                         │││
│  │  │        (Current verse persistence)                      │││
│  │  └─────────────────────────────────────────────────────────┘││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
                              │
                              │ REST API
                              ▼
┌─────────────────────────────────────────────────────────────────┐
│                   Backend (Spring Boot 3)                        │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                   REST Controllers                          ││
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐││
│  │  │   Verses    │ │   Books/    │ │     Search              │││
│  │  │   API       │ │  Chapters   │ │     API                 │││
│  │  └─────────────┘ └─────────────┘ └─────────────────────────┘││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                   Service Layer                             ││
│  │  ┌─────────────┐ ┌─────────────┐ ┌─────────────────────────┐││
│  │  │   Bible     │ │  Reference  │ │     Lucene Search       │││
│  │  │   Service   │ │   Parser    │ │       Service           │││
│  │  └─────────────┘ └─────────────┘ └─────────────────────────┘││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────┐│
│  │                   Data Layer                                ││
│  │  ┌─────────────────────────┐ ┌─────────────────────────────┐││
│  │  │   In-Memory KJV Data    │ │   Lucene In-Memory Index    │││
│  │  │   (Loaded at startup)   │ │   (Built at startup)        │││
│  │  └─────────────────────────┘ └─────────────────────────────┘││
│  └─────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────┘
```

## Key Components

### Frontend Components

#### 1. Reading View
- Two-column CSS Multi-Column Layout
- Dynamic verse loading based on viewport
- Verse highlight for current selection
- No scrolling - page-turn navigation only

#### 2. Page Layout Engine
- Calculates exact verses that fit viewport
- Manages page boundaries
- Handles transitions between pages

#### 3. Navigation System
- Keyboard shortcuts (j/k, arrows, h/l, ,/., </>)
- Cascading dropdowns (Book → Chapter → Verse)
- URL parameter support (?vid=N)

#### 4. Smart Search
- Bible reference detection and parsing
- Full-text search fallback
- Results overlay for search matches

### Backend Components

#### 1. REST API
- `/api/verses` - Fetch verses by range or ID
- `/api/books` - List all books
- `/api/chapters` - List chapters for a book
- `/api/search` - Full-text search endpoint

#### 2. Bible Service
- Loads full KJV text at startup
- Provides efficient verse lookup
- Manages book/chapter/verse relationships

#### 3. Lucene Search Service
- Builds in-memory search index at startup
- Provides full-text search capabilities
- Returns verse references with context

## Data Flow

### Reading Flow
1. User navigates to URL or continues reading
2. Frontend determines current verse (URL param or LocalStorage)
3. Frontend requests verses for current page from API
4. Backend returns verse data
5. Frontend calculates page boundaries based on viewport
6. Frontend renders two-column layout

### Navigation Flow
1. User presses navigation key
2. Frontend updates current verse tracker
3. If verse on current page → only highlight moves
4. If verse beyond page boundary → fetch new page

### Search Flow
1. User enters search query
2. Frontend detects if it's a Bible reference
3. If reference → parse and navigate directly
4. If text search → send to backend Lucene search
5. Display results overlay or navigate to result

## Design Patterns

### Frontend Patterns
- **Module Pattern**: Encapsulated components
- **Observer Pattern**: Event-driven updates
- **State Machine**: Page navigation states

### Backend Patterns
- **Repository Pattern**: Data access abstraction
- **Service Layer**: Business logic encapsulation
- **DTO Pattern**: Clean API responses

## Performance Considerations
- All data loaded in memory at startup
- No database - all lookups are O(1) or O(log n)
- Minimal API payload sizes
- Frontend caches current page data
