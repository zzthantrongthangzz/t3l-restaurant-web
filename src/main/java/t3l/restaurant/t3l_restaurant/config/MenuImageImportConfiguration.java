package t3l.restaurant.t3l_restaurant.config;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import t3l.restaurant.t3l_restaurant.service.MenuImageImportService;

@Configuration
@ConditionalOnProperty(name = "app.import-menu-images", havingValue = "true")
public class MenuImageImportConfiguration {
  private static final Logger logger = LoggerFactory.getLogger(MenuImageImportConfiguration.class);

  @Bean
  CommandLineRunner importMenuImages(MenuImageImportService importer) {
    return args -> {
      MenuImageImportService.ImportSummary result =
          importer.importCatalog(Path.of("img"), Path.of("database/menu-image-catalog.tsv"));
      logger.info(
          "Imported {} menu images: {} new dishes, {} updated dishes.",
          result.total(),
          result.created(),
          result.updated());
    };
  }
}
