package t3l.restaurant.t3l_restaurant.chat;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.*;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class ChatStore {
  record Turn(String user, String reply, Document context) {}
  private final MongoTemplate mongo;
  private volatile boolean indexed;

  ChatStore(MongoTemplate mongo) { this.mongo = mongo; }

  List<Turn> history(String id) {
    indexes();
    Document doc = threads().find(eq("_id", id)).first();
    if (doc == null || doc.getDate("expiresAt") == null || !doc.getDate("expiresAt").after(new Date())) return List.of();
    List<Turn> turns = new ArrayList<>();
    for (Document turn : doc.getList("turns", Document.class, List.of())) {
      turns.add(new Turn(turn.getString("user"), turn.getString("reply"), turn.get("context", Document.class)));
    }
    return turns;
  }

  void append(String id, Turn turn) {
    var now = Instant.now();
    var entry = new Document("user", turn.user()).append("reply", turn.reply()).append("context", turn.context());
    var previous = new Document("$cond", List.of(new Document("$gt", List.of("$expiresAt", Date.from(now))),
        new Document("$ifNull", List.of("$turns", List.of())), List.of()));
    var turns = new Document("$concatArrays", List.of(previous, new Document("$literal", List.of(entry))));
    var update = new Document("turns", new Document("$slice", List.of(turns, -16)))
        .append("expiresAt", Date.from(now.plus(Duration.ofDays(2))));
    threads().updateOne(eq("_id", id), List.of(new Document("$set", update)), new UpdateOptions().upsert(true));
  }

  void clear(String id) { threads().deleteOne(eq("_id", id)); }

  boolean allow(String key, Duration window, int limit) {
    indexes();
    long seconds = window.toSeconds();
    long bucket = Math.floorDiv(Instant.now().getEpochSecond(), seconds);
    var expiry = Date.from(Instant.ofEpochSecond((bucket + 1) * seconds + 60));
    var query = eq("_id", key + ":" + bucket);
    var update = combine(inc("hits", 1), setOnInsert("expiresAt", expiry));
    var collection = mongo.getCollection("chat_limits");
    try {
      return collection.findOneAndUpdate(query, update, new FindOneAndUpdateOptions().upsert(true)
          .returnDocument(ReturnDocument.AFTER)).getInteger("hits") <= limit;
    } catch (MongoWriteException error) {
      if (error.getCode() != 11000) throw error;
      var existing = collection.findOneAndUpdate(query, update,
          new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
      return existing != null && existing.getInteger("hits") <= limit;
    }
  }

  private MongoCollection<Document> threads() { return mongo.getCollection("chat_threads"); }

  private synchronized void indexes() {
    if (indexed) return;
    var ttl = new IndexOptions().expireAfter(0L, TimeUnit.SECONDS);
    threads().createIndex(new Document("expiresAt", 1), ttl);
    mongo.getCollection("chat_limits").createIndex(new Document("expiresAt", 1), ttl);
    indexed = true;
  }

  static String hash(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
