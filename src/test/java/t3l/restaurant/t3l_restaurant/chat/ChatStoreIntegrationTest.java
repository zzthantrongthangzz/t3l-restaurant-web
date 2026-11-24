package t3l.restaurant.t3l_restaurant.chat;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.set;
import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;

class ChatStoreIntegrationTest {
  @Test
  @EnabledIfEnvironmentVariable(named = "T3L_CHAT_MONGO_TEST_URI", matches = ".+")
  void appendingDoesNotRestoreExpiredHistoryBeforeMongoTtlRemovesIt() {
    String name = "chat_qa_" + UUID.randomUUID().toString().replace("-", "");
    try (var client = MongoClients.create(System.getenv("T3L_CHAT_MONGO_TEST_URI"))) {
      var db = client.getDatabase(name);
      var store = new ChatStore(new MongoTemplate(client, name));
      try {
        for (int i = 0; i < 17; i++) store.append("thread", new ChatStore.Turn("turn " + i, "reply", null));
        assertThat(store.history("thread")).hasSize(16).first().isEqualTo(new ChatStore.Turn("turn 1", "reply", null));
        db.getCollection("chat_threads").updateOne(eq("_id", "thread"),
            set("expiresAt", Date.from(Instant.now().minusSeconds(1))));
        assertThat(store.history("thread")).isEmpty();
        var latest = new ChatStore.Turn("$expiresAt", "$turns", new Document("kind", "INFO"));
        store.append("thread", latest);
        assertThat(store.history("thread")).containsExactly(latest);
      } finally {
        db.drop();
      }
    }
  }
}
