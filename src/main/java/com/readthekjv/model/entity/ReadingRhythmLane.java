package com.readthekjv.model.entity;

import jakarta.persistence.*;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;

/**
 * One lane of a {@link ReadingRhythm}: an ordered list of books plus a cursor
 * marking the last chapter the reader finished.
 *
 * <p>{@code dayOfWeek} is a surfacing hint for the dashboard, not a constraint.
 * Any lane may be opened and advanced on any day; nothing checks the weekday.
 */
@Entity
@Table(name = "reading_rhythm_lanes")
public class ReadingRhythmLane {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rhythm_id", nullable = false)
    private ReadingRhythm rhythm;

    /**
     * Ordering within the rhythm, maintained explicitly by the service.
     *
     * <p>Not an {@code @OrderColumn}: on a bidirectional one-to-many Hibernate
     * inserts the child row first and sets the order column in a follow-up UPDATE,
     * which violates this column's NOT NULL constraint. Owning the value here keeps
     * the INSERT complete — the same reason passage_collection_verses carries an
     * explicit position.
     */
    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 60)
    private String name;

    /** ISO-8601 day number: 1 = Monday … 7 = Sunday. Null means "any day". */
    @Column(name = "day_of_week")
    private Short dayOfWeek;

    /** Book of the last chapter finished; null means the lane has not started. */
    @Column(name = "cursor_book_id")
    private Integer cursorBookId;

    /** Last chapter finished within {@link #cursorBookId}; 0 when not started. */
    @Column(name = "cursor_chapter", nullable = false)
    private int cursorChapter = 0;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "reading_rhythm_lane_books",
                     joinColumns = @JoinColumn(name = "lane_id"))
    @OrderColumn(name = "position")
    @Column(name = "book_id", nullable = false)
    private List<Integer> bookIds = new ArrayList<>();

    /** Clear the cursor — used by "restart lane" and when the cursor book is dropped. */
    public void resetCursor() {
        this.cursorBookId = null;
        this.cursorChapter = 0;
    }

    public boolean isStarted() {
        return cursorBookId != null;
    }

    /** Convenience for callers that prefer the JDK type; null when unscheduled. */
    public DayOfWeek dayOfWeekEnum() {
        return dayOfWeek == null ? null : DayOfWeek.of(dayOfWeek);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public ReadingRhythm getRhythm() { return rhythm; }
    public void setRhythm(ReadingRhythm rhythm) { this.rhythm = rhythm; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Short getDayOfWeek() { return dayOfWeek; }
    public void setDayOfWeek(Short dayOfWeek) { this.dayOfWeek = dayOfWeek; }
    public Integer getCursorBookId() { return cursorBookId; }
    public void setCursorBookId(Integer cursorBookId) { this.cursorBookId = cursorBookId; }
    public int getCursorChapter() { return cursorChapter; }
    public void setCursorChapter(int cursorChapter) { this.cursorChapter = cursorChapter; }
    public List<Integer> getBookIds() { return bookIds; }
}
