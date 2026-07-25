package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Migrates flat passage_collection_verses into passage_collection_members.
 * Each contiguous run of verse ids becomes (or reuses) a user-owned Passage row.
 * Version 17.1 so it runs after V17__passage_collection_members.sql.
 */
public class V17_1__MigrateCollectionVersesToPassages extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();

        // Skip if the old table was already dropped (re-run / fresh env after drop)
        try (ResultSet rs = conn.getMetaData().getTables(null, null, "passage_collection_verses", null)) {
            if (!rs.next()) {
                return;
            }
        }

        List<Long> collectionIds = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id FROM passage_collections ORDER BY id")) {
            while (rs.next()) {
                collectionIds.add(rs.getLong(1));
            }
        }

        String selectVerses = """
            SELECT verse_id FROM passage_collection_verses
            WHERE collection_id = ? ORDER BY position
            """;
        String selectUser = "SELECT user_id FROM passage_collections WHERE id = ?";
        String findPassage = """
            SELECT id FROM passages WHERE user_id = ? AND natural_key = ?
            """;
        String insertPassage = """
            INSERT INTO passages (id, user_id, from_verse_id, to_verse_id, natural_key, created_at)
            VALUES (?, ?, ?, ?, ?, now())
            """;
        String insertMember = """
            INSERT INTO passage_collection_members (collection_id, position, passage_id)
            VALUES (?, ?, ?)
            ON CONFLICT DO NOTHING
            """;
        String countMembers = "SELECT COUNT(*) FROM passage_collection_members WHERE collection_id = ?";

        try (PreparedStatement psVerses = conn.prepareStatement(selectVerses);
             PreparedStatement psUser = conn.prepareStatement(selectUser);
             PreparedStatement psFind = conn.prepareStatement(findPassage);
             PreparedStatement psInsPassage = conn.prepareStatement(insertPassage);
             PreparedStatement psInsMember = conn.prepareStatement(insertMember);
             PreparedStatement psCount = conn.prepareStatement(countMembers)) {

            for (Long collectionId : collectionIds) {
                psCount.setLong(1, collectionId);
                try (ResultSet crs = psCount.executeQuery()) {
                    crs.next();
                    if (crs.getInt(1) > 0) {
                        continue; // already migrated
                    }
                }

                psUser.setLong(1, collectionId);
                long userId;
                try (ResultSet urs = psUser.executeQuery()) {
                    if (!urs.next()) continue;
                    userId = urs.getLong(1);
                }

                List<Integer> verseIds = new ArrayList<>();
                psVerses.setLong(1, collectionId);
                try (ResultSet vrs = psVerses.executeQuery()) {
                    while (vrs.next()) {
                        verseIds.add(vrs.getInt(1));
                    }
                }
                if (verseIds.isEmpty()) continue;

                List<int[]> runs = contiguousRuns(verseIds);
                int position = 0;
                for (int[] run : runs) {
                    int from = run[0];
                    int to = run[1];
                    String naturalKey = from == to ? String.valueOf(from) : from + ":" + to;

                    UUID passageId = null;
                    psFind.setLong(1, userId);
                    psFind.setString(2, naturalKey);
                    try (ResultSet frs = psFind.executeQuery()) {
                        if (frs.next()) {
                            passageId = (UUID) frs.getObject(1);
                        }
                    }
                    if (passageId == null) {
                        passageId = UUID.randomUUID();
                        psInsPassage.setObject(1, passageId);
                        psInsPassage.setLong(2, userId);
                        psInsPassage.setInt(3, from);
                        psInsPassage.setInt(4, to);
                        psInsPassage.setString(5, naturalKey);
                        psInsPassage.executeUpdate();
                    }

                    psInsMember.setLong(1, collectionId);
                    psInsMember.setInt(2, position++);
                    psInsMember.setObject(3, passageId);
                    psInsMember.executeUpdate();
                }
            }
        }

        try (Statement st = conn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS passage_collection_verses");
        }
    }

    /** Each int[] is {from, to} inclusive for a contiguous run. */
    static List<int[]> contiguousRuns(List<Integer> ids) {
        List<int[]> runs = new ArrayList<>();
        if (ids.isEmpty()) return runs;
        int from = ids.get(0);
        int to = ids.get(0);
        for (int i = 1; i < ids.size(); i++) {
            int id = ids.get(i);
            if (id == to + 1) {
                to = id;
            } else {
                runs.add(new int[]{from, to});
                from = to = id;
            }
        }
        runs.add(new int[]{from, to});
        return runs;
    }
}
