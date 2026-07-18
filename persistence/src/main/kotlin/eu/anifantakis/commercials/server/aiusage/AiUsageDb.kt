package eu.anifantakis.commercials.server.aiusage

import eu.anifantakis.commercials.server.scheduler.CentralDb

/** One aggregated usage row: a (user, provider, model) triple's lifetime totals. */
data class AiUsageRow(
    val username: String,
    val provider: String,
    val model: String,
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val lastUsedEpochMs: Long,
)

/**
 * Per-user AI-chat token metering (central schema). Aggregates on write -
 * one row per (user, provider, model), atomically bumped per request - so
 * reads are trivial and the table cannot grow with traffic, only with the
 * user x model matrix. Metering must never break a chat: callers wrap
 * [record] in a swallow-and-log.
 */
class AiUsageDb(private val db: CentralDb) {

    fun bootstrap() {
        db.connection().use { c ->
            c.createStatement().use { s ->
                s.executeUpdate(
                    """
                    CREATE TABLE IF NOT EXISTS ai_usage (
                        username VARCHAR(64) NOT NULL,
                        provider VARCHAR(16) NOT NULL,
                        model VARCHAR(64) NOT NULL,
                        requests BIGINT NOT NULL DEFAULT 0,
                        input_tokens BIGINT NOT NULL DEFAULT 0,
                        output_tokens BIGINT NOT NULL DEFAULT 0,
                        last_used TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (username, provider, model)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                    """.trimIndent()
                )
            }
        }
    }

    fun record(username: String, provider: String, model: String, inputTokens: Long, outputTokens: Long) {
        db.connection().use { c ->
            c.prepareStatement(
                """
                INSERT INTO ai_usage (username, provider, model, requests, input_tokens, output_tokens, last_used)
                VALUES (?, ?, ?, 1, ?, ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE
                    requests = requests + 1,
                    input_tokens = input_tokens + VALUES(input_tokens),
                    output_tokens = output_tokens + VALUES(output_tokens),
                    last_used = CURRENT_TIMESTAMP
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, username)
                ps.setString(2, provider)
                ps.setString(3, model)
                ps.setLong(4, inputTokens)
                ps.setLong(5, outputTokens)
                ps.executeUpdate()
            }
        }
    }

    /** Every row, most recently used first. */
    fun all(): List<AiUsageRow> = db.connection().use { c ->
        c.prepareStatement(
            "SELECT username, provider, model, requests, input_tokens, output_tokens, last_used " +
                "FROM ai_usage ORDER BY last_used DESC"
        ).use { ps ->
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            AiUsageRow(
                                username = rs.getString(1),
                                provider = rs.getString(2),
                                model = rs.getString(3),
                                requests = rs.getLong(4),
                                inputTokens = rs.getLong(5),
                                outputTokens = rs.getLong(6),
                                lastUsedEpochMs = rs.getTimestamp(7).time,
                            )
                        )
                    }
                }
            }
        }
    }
}
