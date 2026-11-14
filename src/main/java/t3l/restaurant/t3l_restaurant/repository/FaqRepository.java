package t3l.restaurant.t3l_restaurant.repository;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import t3l.restaurant.t3l_restaurant.document.FaqEntry;

public interface FaqRepository extends MongoRepository<FaqEntry, String> {
  List<FaqEntry> findByPublishedTrueOrderByDisplayOrderAsc();
}
