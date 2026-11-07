package dev.kkd.infrastructure;

import javax.sql.DataSource;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MigrationConfiguration {
  @Bean
  SpringLiquibase liquibase(DataSource source, @Value("${kkd.demo-enabled}") boolean demo) {
    var migrations = new SpringLiquibase();
    migrations.setDataSource(source);
    migrations.setChangeLog("classpath:db/changelog.xml");
    migrations.setContexts(demo ? "demo" : "schema");
    return migrations;
  }
}
