package db.migration;

import com.readthekjv.util.NaturalKeyParser;
import com.readthekjv.util.VerseRangeParser;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Rewrites any non-canonical natural_key values in the passages table.
 * MemorizationService historically stored user-supplied keys verbatim (e.g. "1,2,3"
 * instead of the canonical "1:3"). PassageService.upsert has always written canonical
 * keys, so only user-owned rows added via memorization can be affected.
 *
 * For each row whose natural_key differs from its canonical form:
 *   - If the canonical key already exists for the same user, skip (log a warning).
 *   - Otherwise UPDATE in place.
 *
 * After this migration MemorizationService normalizes keys before writing,
 * so non-canonical rows can no longer be created.
 */
public class V18__canonicalize_passage_natural_keys extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V18__canonicalize_passage_natural_keys.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();

        List<Row> rows = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, user_id, natural_key FROM passages WHERE natural_key IS NOT NULL")) {
            while (rs.next()) {
                rows.add(new Row(
                        (UUID) rs.getObject(1),
                        rs.getObject(2),   // Long or null for globals
                        rs.getString(3)));
            }
        }

        String checkSql = "SELECT COUNT(*) FROM passages WHERE user_id = ? AND natural_key = ? AND id <> ?";
        String checkGlobalSql = "SELECT COUNT(*) FROM passages WHERE user_id IS NULL AND natural_key = ? AND id <> ?";
        String updateSql = "UPDATE passages SET natural_key = ? WHERE id = ?";

        try (PreparedStatement psCheck = conn.prepareStatement(checkSql);
             PreparedStatement psCheckGlobal = conn.prepareStatement(checkGlobalSql);
             PreparedStatement psUpdate = conn.prepareStatement(updateSql)) {

            int updated = 0;
            int skipped = 0;
            for (Row row : rows) {
                String canonical;
                try {
                    canonical = VerseRangeParser.naturalKeyFromRanges(
                            VerseRangeParser.rangesFromNaturalKey(row.naturalKey));
                } catch (Exception e) {
                    log.warn("V18: skipping passage {} — unparseable key '{}'", row.id, row.naturalKey);
                    skipped++;
                    continue;
                }
                if (canonical.equals(row.naturalKey)) continue; // already canonical

                // Check for conflict: same user already has the canonical key
                boolean conflict;
                if (row.userId == null) {
                    psCheckGlobal.setString(1, canonical);
                    psCheckGlobal.setObject(2, row.id);
                    try (ResultSet cr = psCheckGlobal.executeQuery()) {
                        cr.next();
                        conflict = cr.getInt(1) > 0;
                    }
                } else {
                    psCheck.setObject(1, row.userId);
                    psCheck.setString(2, canonical);
                    psCheck.setObject(3, row.id);
                    try (ResultSet cr = psCheck.executeQuery()) {
                        cr.next();
                        conflict = cr.getInt(1) > 0;
                    }
                }

                if (conflict) {
                    log.warn("V18: skipping passage {} (key='{}') — canonical form '{}' already exists for same user",
                            row.id, row.naturalKey, canonical);
                    skipped++;
                    continue;
                }

                psUpdate.setString(1, canonical);
                psUpdate.setObject(2, row.id);
                psUpdate.executeUpdate();
                updated++;
            }

            log.info("V18: canonicalized {} passage natural_key(s), skipped {}", updated, skipped);
        }
    }

    private record Row(UUID id, Object userId, String naturalKey) {}
}
