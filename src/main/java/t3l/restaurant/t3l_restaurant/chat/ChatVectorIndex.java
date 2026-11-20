package t3l.restaurant.t3l_restaurant.chat;

import static com.mongodb.client.model.Filters.eq;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

@Component
class ChatVectorIndex {
  record Hit(String id, String hash, double score) {}
  private final MongoTemplate mongo;
  private final MongoClient atlas;
  private final String database;
  private final int dimensions;
  private final String collection;

  ChatVectorIndex(MongoTemplate mongo, ChatSettings settings) {
    this.mongo = mongo;
    this.dimensions = settings.dimensions;
    this.collection = "chat_public_vectors_" + ChatStore.hash(
        settings.embeddingModel + ":" + dimensions + ":v1").substring(0, 16);
    if (settings.vectorUri.isBlank()) {
      atlas = null;
      database = null;
    } else {
      var connection = new ConnectionString(settings.vectorUri);
      database = connection.getDatabase();
      if (database == null) throw new IllegalArgumentException("CHAT_VECTOR_MONGODB_URI cần chứa tên database.");
      atlas = MongoClients.create(MongoClientSettings.builder().applyConnectionString(connection)
          .applyToClusterSettings(builder -> builder.serverSelectionTimeout(2, TimeUnit.SECONDS))
          .applyToSocketSettings(builder -> builder.connectTimeout(2, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS))
          .build());
    }
  }

  boolean ready() {
    var index = vectors().aggregate(List.of(indexStatus())).maxTime(2, TimeUnit.SECONDS).first();
    return index != null && Boolean.TRUE.equals(index.getBoolean("queryable"));
  }

  void ensureIndex() {
    if (vectors().aggregate(List.of(indexStatus())).maxTime(2, TimeUnit.SECONDS).first() != null) return;
    try {
      db().createCollection(collection);
    } catch (MongoException error) {
      if (error.getCode() != 48) throw error; // NamespaceExists
    }
    var field = new Document("type", "vector").append("path", "embedding")
        .append("numDimensions", dimensions).append("similarity", "cosine");
    db().runCommand(new Document("createSearchIndexes", collection).append("indexes", List.of(
        new Document("name", "semantic").append("type", "vectorSearch")
            .append("definition", new Document("fields", List.of(field))))));
  }

  List<Document> versions() {
    return vectors().find().projection(new Document("hash", 1)).into(new java.util.ArrayList<>());
  }

  void upsert(String id, String hash, String source, String question, String answer, List<Double> embedding) {
    var doc = new Document("_id", id).append("hash", hash).append("source", source)
        .append("question", question).append("answer", answer).append("embedding", embedding)
        .append("updatedAt", new java.util.Date());
    vectors().replaceOne(eq("_id", id), doc, new ReplaceOptions().upsert(true));
  }

  void prune(Set<String> removed) {
    if (!removed.isEmpty()) vectors().deleteMany(com.mongodb.client.model.Filters.in("_id", removed));
  }

  List<Hit> search(List<Double> query) {
    var stage = new Document("$vectorSearch", new Document("index", "semantic").append("path", "embedding")
        .append("queryVector", query).append("numCandidates", 100).append("limit", 6));
    var projection = new Document("$project", new Document("hash", 1).append("score", new Document("$meta", "vectorSearchScore")));
    return vectors().aggregate(List.of(stage, projection)).maxTime(2, TimeUnit.SECONDS)
        .map(doc -> new Hit(doc.getString("_id"), doc.getString("hash"), doc.getDouble("score")))
        .into(new java.util.ArrayList<>());
  }

  private Document indexStatus() { return new Document("$listSearchIndexes", new Document("name", "semantic")); }
  private MongoCollection<Document> vectors() { return db().getCollection(collection); }
  private MongoDatabase db() { return atlas == null ? mongo.getDb() : atlas.getDatabase(database); }

  @PreDestroy
  void close() { if (atlas != null) atlas.close(); }
}
