package com.quantumhorizon.quantumcrews

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DatabaseBootstrap {
    @Bean
    fun migrations(@Value("\${spring.datasource.password}") appPassword: String) = FlywayMigrationStrategy { flyway ->
        flyway.migrate()
        // Apply the configured application credential after initial role creation, without changing migration checksums.
        flyway.configuration.dataSource.connection.use { connection ->
            val sql = connection.prepareStatement("SELECT format('ALTER ROLE qc_app PASSWORD %L', ?::text)").use { statement ->
                statement.setString(1, appPassword)
                statement.executeQuery().use { rows -> rows.next(); rows.getString(1) }
            }
            connection.createStatement().use { it.execute(sql) }
        }
    }
}
