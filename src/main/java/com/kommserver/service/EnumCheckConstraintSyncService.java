package com.kommserver.service;

import com.kommserver.model.db.Bot;
import com.kommserver.model.db.Channel;
import com.kommserver.model.db.Message;
import com.kommserver.model.db.ServerMember;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Hibernate auto-derives a {@code CHECK (col IN (...))} constraint from an {@code
 * @Enumerated(EnumType.STRING)} column's values the first time it creates that column — but
 * {@code ddl-auto=update} never revisits an existing constraint, so the moment the Java enum
 * gains a value, every database that already had the table starts rejecting it (the
 * MANAGE_BOTS/URL_IMAGE incident). This runs on every startup, mirrors {@link
 * PermissionService#migratePermissions()}'s pattern of self-healing existing data on boot, and
 * — unlike a Hibernate-side annotation change — actually fixes already-existing databases, not
 * just tables created from here on out: it diffs each constraint's current allowed values against
 * the enum's actual current values and, if they've drifted, drops and recreates the constraint.
 * No-op (and silent) when everything already matches, which is the common case on every boot.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EnumCheckConstraintSyncService {

    private final DataSource dataSource;

    private record EnumColumn(String table, String column, String constraintName, Class<? extends Enum<?>> enumClass) {}

    private static final List<EnumColumn> ENUM_COLUMNS = List.of(
            new EnumColumn("messages", "message_type", "messages_message_type_check", Message.MessageType.class),
            new EnumColumn("bots", "bot_type", "bots_bot_type_check", Bot.BotType.class),
            new EnumColumn("channels", "channel_type", "channels_channel_type_check", Channel.ChannelType.class),
            new EnumColumn("channel_role_permissions", "role", "channel_role_permissions_role_check", ServerMember.Role.class),
            new EnumColumn("server_custom_roles", "base_role", "server_custom_roles_base_role_check", ServerMember.Role.class),
            new EnumColumn("server_members", "role", "server_members_role_check", ServerMember.Role.class),
            new EnumColumn("server_role_permissions", "role", "server_role_permissions_role_check", ServerMember.Role.class)
    );

    // Pulls every single-quoted literal out of a constraint definition regardless of which SQL
    // shape Postgres/Hibernate used to express it (`= 'X'` for a single value vs
    // `= ANY (ARRAY['X','Y'])` for several) — same set either way.
    private static final Pattern VALUE_PATTERN = Pattern.compile("'([A-Za-z0-9_]+)'");

    @EventListener(ApplicationReadyEvent.class)
    public void syncEnumCheckConstraints() {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            int updated = 0;
            for (EnumColumn col : ENUM_COLUMNS) {
                try {
                    if (syncOne(conn, col)) updated++;
                } catch (SQLException e) {
                    log.error("Failed to sync CHECK constraint {} on {}.{}: {}",
                            col.constraintName(), col.table(), col.column(), e.getMessage(), e);
                }
            }
            log.debug("Enum CHECK constraint sync complete: {}/{} updated", updated, ENUM_COLUMNS.size());
        } catch (SQLException e) {
            log.error("Failed to sync enum CHECK constraints: {}", e.getMessage(), e);
        }
    }

    /** @return true if the constraint was out of date and got fixed. */
    private boolean syncOne(Connection conn, EnumColumn col) throws SQLException {
        Set<String> expected = Arrays.stream(col.enumClass().getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> actual = currentConstraintValues(conn, col.table(), col.constraintName());
        if (actual != null && actual.equals(expected)) {
            return false; // already in sync — the common case
        }

        String valueList = expected.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", "));
        try (Statement st = conn.createStatement()) {
            if (actual != null) {
                st.execute("ALTER TABLE " + col.table() + " DROP CONSTRAINT " + col.constraintName());
            }
            st.execute("ALTER TABLE " + col.table() + " ADD CONSTRAINT " + col.constraintName()
                    + " CHECK (" + col.column() + " = ANY (ARRAY[" + valueList + "]))");
        }

        log.info("Synced CHECK constraint {} on {}.{}: {} -> {}",
                col.constraintName(), col.table(), col.column(),
                actual == null ? "(missing)" : actual, expected);
        return true;
    }

    /** Null means the constraint (or its table) doesn't exist yet — nothing to sync against. */
    private Set<String> currentConstraintValues(Connection conn, String table, String constraintName) throws SQLException {
        String sql = "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conrelid = ?::regclass AND conname = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, constraintName);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Set<String> values = new TreeSet<>();
                Matcher m = VALUE_PATTERN.matcher(rs.getString(1));
                while (m.find()) values.add(m.group(1));
                return values;
            }
        } catch (SQLException e) {
            // conrelid cast throws if the table doesn't exist yet — treat like "nothing to sync".
            return null;
        }
    }
}
