package t3l.restaurant.t3l_restaurant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

class ChatVectorIndexIntegrationTest {
  @Test
  @EnabledIfEnvironmentVariable(named = "T3L_CHAT_VECTOR_TEST_URI", matches = ".+")
  void createsAtlasIndexAndSearchesWithConfiguredDimensions() throws Exception {
    String uri = System.getenv("T3L_CHAT_VECTOR_TEST_URI");
    String model = "atlas-qa-" + UUID.randomUUID();
    int dimensions = 768;
    var connection = new com.mongodb.ConnectionString(uri);
    String name = "chat_public_vectors_" + ChatStore.hash(model + ":" + dimensions + ":v1").substring(0, 16);
    try (var client = MongoClients.create(connection)) {
      var collection = client.getDatabase(connection.getDatabase()).getCollection(name);
      var index = new ChatVectorIndex(null, new ChatSettings("", "test", model, dimensions, 4, 30, 8, uri));
      try {
        index.ensureIndex();
        List<Double> query = new ArrayList<>(Collections.nCopies(dimensions, 0d));
        query.set(0, 1d);
        List<Double> opposite = new ArrayList<>(query);
        opposite.set(0, -1d);
        index.upsert("close", "hash-close", "/faq", "Giờ mở cửa", "Giờ phục vụ", query);
        index.upsert("far", "hash-far", "/faq", "Chính sách", "Chính sách đặt bàn", opposite);
        List<ChatVectorIndex.Hit> matches = List.of();
        for (int attempt = 0; attempt < 35 && matches.size() < 2; attempt++) {
          Thread.sleep(500);
          if (index.ready()) matches = index.search(query);
        }
        assertThat(matches).extracting(ChatVectorIndex.Hit::id).containsExactly("close", "far");
        assertThat(matches.getFirst().score()).isGreaterThan(0.95);
        index.prune(Set.of("far"));
        assertThat(index.versions()).extracting(value -> value.getString("_id")).containsExactly("close");
      } finally {
        index.close();
        collection.drop();
      }
    }
  }
}
